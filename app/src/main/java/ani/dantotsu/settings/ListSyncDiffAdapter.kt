package ani.dantotsu.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.sync.ListCompare
import ani.dantotsu.databinding.ItemListSyncDiffBinding
import ani.dantotsu.loadImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Renders the out-of-date media of one comparison subsection: cover, title, the specific field
 * differences ("current → target"), and a per-row Sync/Remove button. [onSync] is invoked with the
 * entry and its adapter position; the caller performs the sync and calls [removeAt] on success.
 *
 * MangaBaka deletion rows arrive without a title/cover (the library list endpoint doesn't embed
 * series info), so those are fetched lazily on bind via [scope] — because the list is collapsed by
 * default and RecyclerView only binds visible rows, we only hit the network for rows actually shown.
 */
class ListSyncDiffAdapter(
    private val items: MutableList<ListCompare.DiffEntry>,
    private val scope: CoroutineScope,
    private val onSync: (ListCompare.DiffEntry, Int) -> Unit,
) : RecyclerView.Adapter<ListSyncDiffAdapter.Holder>() {

    /** Cache of lazily-fetched (title, cover) keyed by MangaBaka series id. */
    private val seriesInfo = HashMap<Long, Pair<String?, String?>>()

    inner class Holder(val binding: ItemListSyncDiffBinding) : RecyclerView.ViewHolder(binding.root) {
        var job: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemListSyncDiffBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        val b = holder.binding
        val context = b.root.context
        holder.job?.cancel()

        if (entry.delete) {
            val trackerName = context.getString(
                if (entry.tracker == ListCompare.Tracker.MAL) R.string.myanimelist else R.string.mangabaka
            )
            b.diffChanges.text = context.getString(R.string.list_diff_only_on, trackerName)
            b.diffSync.setIconResource(R.drawable.ic_round_delete_24)
            b.diffSync.contentDescription = context.getString(R.string.remove)
        } else {
            b.diffChanges.text = entry.diffs.joinToString("\n") { d ->
                "${label(context, d.field, entry.isAnime)}: ${d.from} → ${d.to}"
            }
            b.diffSync.setIconResource(R.drawable.ic_round_sync_24)
            b.diffSync.contentDescription = context.getString(R.string.sync)
        }

        bindTitleAndCover(holder, entry, position)

        b.diffSync.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onSync(items[pos], pos)
        }
    }

    /** Shows the title/cover, fetching them lazily for MangaBaka deletion rows that lack them. */
    private fun bindTitleAndCover(holder: Holder, entry: ListCompare.DiffEntry, position: Int) {
        val b = holder.binding
        val context = b.root.context
        val seriesId = entry.mangaBakaSeriesId
        if (entry.title.isNotBlank() || seriesId == null) {
            b.diffTitle.text = entry.title
            b.diffCover.loadImage(entry.coverUrl)
            return
        }
        val cached = seriesInfo[seriesId]
        if (cached != null) {
            b.diffTitle.text = cached.first ?: context.getString(R.string.list_diff_mangabaka_id, seriesId)
            b.diffCover.loadImage(cached.second)
            return
        }
        b.diffTitle.text = context.getString(R.string.list_diff_mangabaka_id, seriesId)
        b.diffCover.setImageDrawable(null)
        holder.job = scope.launch {
            val info = ListCompare.mangaBakaSeriesInfo(seriesId) ?: return@launch
            seriesInfo[seriesId] = info
            if (holder.bindingAdapterPosition == position) {
                b.diffTitle.text = info.first ?: context.getString(R.string.list_diff_mangabaka_id, seriesId)
                b.diffCover.loadImage(info.second)
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.job = null
    }

    fun removeAt(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    private fun label(context: Context, field: ListCompare.DiffField, isAnime: Boolean): String =
        when (field) {
            ListCompare.DiffField.STATUS -> context.getString(R.string.list_diff_status)
            ListCompare.DiffField.PROGRESS ->
                context.getString(if (isAnime) R.string.list_diff_episodes else R.string.list_diff_chapters)
            ListCompare.DiffField.VOLUME -> context.getString(R.string.list_diff_volumes)
            ListCompare.DiffField.SCORE -> context.getString(R.string.list_diff_score)
        }
}
