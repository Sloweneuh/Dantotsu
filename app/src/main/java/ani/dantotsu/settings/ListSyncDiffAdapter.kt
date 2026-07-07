package ani.dantotsu.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.sync.ListCompare
import ani.dantotsu.databinding.ItemListSyncDiffBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.loadImage
import com.google.android.material.R as MaterialR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Renders the out-of-date media of one comparison subsection: cover, title, a one-line summary of the
 * field differences, and a per-row Sync/Remove button. Tapping a row expands a two-column detail
 * (source vs destination) showing every tracked field, with the differing rows highlighted on the
 * destination side. [onSync] is invoked with the entry and its adapter position; the caller performs
 * the sync and calls [removeAt] on success.
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

    /** Entries whose detail panel is expanded, tracked by identity so list edits don't disturb it. */
    private val expanded: MutableSet<ListCompare.DiffEntry> =
        Collections.newSetFromMap(IdentityHashMap())

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

        // Expandable detail: available whenever we have both-side field values (i.e. not a deletion).
        val expandable = entry.detail.isNotEmpty()
        val isExpanded = expandable && entry in expanded
        b.diffChevron.visibility = if (expandable) View.VISIBLE else View.GONE
        b.diffChevron.rotation = if (isExpanded) 180f else 0f
        b.diffDetail.visibility = if (isExpanded) View.VISIBLE else View.GONE
        if (isExpanded) bindDetail(holder, entry) else {
            b.diffDetailSource.removeAllViews()
            b.diffDetailDest.removeAllViews()
        }
        b.diffHeader.isClickable = expandable
        b.diffHeader.setOnClickListener {
            if (!expandable) return@setOnClickListener
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val e = items[pos]
            if (!expanded.remove(e)) expanded.add(e)
            notifyItemChanged(pos)
        }

        b.diffSync.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onSync(items[pos], pos)
        }
    }

    /** Fills the two detail columns: source (target) values on the left, destination on the right. */
    private fun bindDetail(holder: Holder, entry: ListCompare.DiffEntry) {
        val ctx = holder.binding.root.context
        val src = holder.binding.diffDetailSource
        val dst = holder.binding.diffDetailDest
        src.removeAllViews()
        dst.removeAllViews()
        src.addView(iconHeader(ctx, sourceIcon(entry)))
        dst.addView(iconHeader(ctx, destIcon(entry)))
        val notSet = ctx.getString(R.string.list_diff_not_set)
        for (row in entry.detail) {
            val label = label(ctx, row.field, entry.isAnime)
            src.addView(lineView(ctx, "$label: ${row.source ?: notSet}", highlight = false))
            val destText = if (row.differs) "$label: ${row.dest ?: notSet} → ${row.source ?: notSet}"
            else "$label: ${row.dest ?: notSet}"
            dst.addView(lineView(ctx, destText, highlight = row.differs))
        }
    }

    private fun sourceIcon(entry: ListCompare.DiffEntry): Int = when {
        entry.tracker == ListCompare.Tracker.MAL -> R.drawable.ic_anilist
        entry.muSeriesId != null -> R.drawable.ic_round_mangaupdates_24
        else -> R.drawable.ic_anilist
    }

    private fun destIcon(entry: ListCompare.DiffEntry): Int =
        if (entry.tracker == ListCompare.Tracker.MAL) R.drawable.ic_myanimelist
        else R.drawable.ic_round_mangabaka_24

    private fun iconHeader(ctx: Context, iconRes: Int): ImageView {
        val size = dp(ctx, 18)
        return ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { bottomMargin = dp(ctx, 6) }
            setImageResource(iconRes)
            ImageViewCompat.setImageTintList(
                this, ColorStateList.valueOf(ctx.getThemeColor(MaterialR.attr.colorPrimary))
            )
        }
    }

    private fun lineView(ctx: Context, text: String, highlight: Boolean): TextView =
        TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 3) }
            textSize = 12f
            this.text = text
            if (highlight) {
                setBackgroundResource(R.drawable.bg_list_sync_diff_highlight)
                val h = dp(ctx, 6)
                val v = dp(ctx, 4)
                setPadding(h, v, h, v)
                setTextColor(ctx.getThemeColor(MaterialR.attr.colorOnPrimaryContainer))
            } else {
                setTextColor(ctx.getThemeColor(MaterialR.attr.colorOnSurfaceVariant))
            }
        }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

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
            expanded.remove(items[position])
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    /**
     * Replaces the whole list. Used by "Sync all" to drop the entries that synced while keeping any
     * failures for retry, without re-running the (network-heavy) full comparison.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun replaceAll(newItems: List<ListCompare.DiffEntry>) {
        expanded.retainAll(newItems.toHashSet())
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    private fun label(context: Context, field: ListCompare.DiffField, isAnime: Boolean): String =
        when (field) {
            ListCompare.DiffField.STATUS -> context.getString(R.string.list_diff_status)
            ListCompare.DiffField.PROGRESS ->
                context.getString(if (isAnime) R.string.list_diff_episodes else R.string.list_diff_chapters)
            ListCompare.DiffField.VOLUME -> context.getString(R.string.list_diff_volumes)
            ListCompare.DiffField.SCORE -> context.getString(R.string.list_diff_score)
            ListCompare.DiffField.START_DATE -> context.getString(R.string.list_diff_start_date)
            ListCompare.DiffField.END_DATE -> context.getString(R.string.list_diff_end_date)
        }
}
