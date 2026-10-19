package ani.dantotsu.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.text.inSpans
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.sync.ListCompare
import ani.dantotsu.databinding.ItemListSyncDiffBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.loadImage
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Renders the out-of-date media of one comparison subsection: cover, title, each differing field as
 * old → new, and a per-row Sync/Remove button. Tapping a row expands a [CompareTable] of every
 * tracked field on both sides, with the differing rows highlighted. [onSync] is invoked with the entry and its adapter position; the caller performs
 * the sync and calls [removeAt] on success.
 *
 * Some rows arrive without a title and/or cover — MangaBaka deletions and anything sourced from
 * MangaUpdates (see [bindTitleAndCover]) — so those are fetched lazily on bind via [scope]. Because
 * the list is collapsed by default and RecyclerView only binds visible rows, we only hit the network
 * for rows actually shown.
 */
class ListSyncDiffAdapter(
    private val items: MutableList<ListCompare.DiffEntry>,
    private val scope: CoroutineScope,
    private val onSync: (ListCompare.DiffEntry, Int) -> Unit,
) : RecyclerView.Adapter<ListSyncDiffAdapter.Holder>() {

    /** Cache of lazily-fetched (title, cover), keyed by the id it was looked up by. */
    private val seriesInfo = HashMap<String, Pair<String?, String?>>()

    /** Entries whose detail panel is expanded, tracked by identity so list edits don't disturb it. */
    private val expanded: MutableSet<ListCompare.DiffEntry> =
        Collections.newSetFromMap(IdentityHashMap())

    /** Entries whose sync is in flight; their button spins and can't be pressed again. */
    private val syncing: MutableSet<ListCompare.DiffEntry> =
        Collections.newSetFromMap(IdentityHashMap())

    /** Marks an entry as syncing (or done), refreshing its row so the button reflects it. */
    fun setSyncing(entry: ListCompare.DiffEntry, value: Boolean) {
        val changed = if (value) syncing.add(entry) else syncing.remove(entry)
        if (!changed) return
        val position = items.indexOfFirst { it === entry }
        if (position >= 0) notifyItemChanged(position)
    }

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
        // Stop any spin left over from the entry this recycled holder used to show, before the icon
        // it wraps is swapped out from under it.
        b.diffSync.setIconSpinning(false)

        if (entry.delete) {
            val trackerName = context.getString(trackerNameRes(entry.tracker))
            b.diffChanges.text = context.getString(R.string.list_diff_only_on, trackerName)
            b.diffChanges.setTextColor(context.getThemeColor(androidx.appcompat.R.attr.colorError))
            b.diffSync.setIconResource(R.drawable.ic_round_delete_24)
            b.diffSync.contentDescription = context.getString(R.string.remove)
            b.diffSync.backgroundTintList = ColorStateList.valueOf(
                context.getThemeColor(MaterialR.attr.colorErrorContainer)
            )
            b.diffSync.iconTint = ColorStateList.valueOf(
                context.getThemeColor(MaterialR.attr.colorOnErrorContainer)
            )
        } else {
            b.diffChanges.text = changesText(context, entry)
            b.diffChanges.setTextColor(context.getThemeColor(MaterialR.attr.colorOnSurfaceVariant))
            b.diffSync.setIconResource(R.drawable.ic_round_sync_24)
            b.diffSync.contentDescription = context.getString(R.string.sync)
            CompareTable.tintAccentButton(b.diffSync)
        }

        bindTitleAndCover(holder, entry, position)

        // Expandable detail: available whenever we have both-side field values (i.e. not a deletion).
        val expandable = entry.detail.isNotEmpty()
        val isExpanded = expandable && entry in expanded
        b.diffChevron.visibility = if (expandable) View.VISIBLE else View.GONE
        b.diffChevron.rotation = if (isExpanded) 180f else 0f
        SectionCard.resetPanel(b.diffDetail)
        b.diffDetail.visibility = if (isExpanded) View.VISIBLE else View.GONE
        if (isExpanded) bindDetail(holder, entry) else b.diffDetail.removeAllViews()
        b.diffHeader.isClickable = expandable
        b.diffHeader.setOnClickListener {
            if (!expandable) return@setOnClickListener
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val e = items[pos]
            if (!expanded.remove(e)) expanded.add(e)
            // Opened in place rather than by rebinding the row, which snapped it to its new height.
            val open = e in expanded
            b.diffChevron.animate().rotation(if (open) 180f else 0f).setDuration(220).start()
            if (open) bindDetail(holder, e)
            SectionCard.setExpanded(b.diffDetail, open) { b.diffDetail.removeAllViews() }
        }

        val inFlight = entry in syncing
        b.diffSync.isEnabled = !inFlight
        b.diffSync.setIconSpinning(inFlight)
        b.diffSync.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onSync(items[pos], pos)
        }
    }

    /**
     * What changes, one field a line: the field, the tracker's value struck through, and the value
     * it will take in the accent colour, so the new value is what the eye lands on.
     */
    private fun changesText(ctx: Context, entry: ListCompare.DiffEntry): CharSequence {
        val muted = ctx.getThemeColor(MaterialR.attr.colorOnSurfaceVariant)
        val faded = MaterialColors.compositeARGBWithAlpha(muted, 150)
        val accent = ctx.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        val out = SpannableStringBuilder()
        entry.diffs.forEachIndexed { i, d ->
            if (i > 0) out.append('\n')
            out.append(label(ctx, d.field, entry.isAnime)).append("  ")
            out.inSpans(ForegroundColorSpan(faded), StrikethroughSpan()) { append(d.from) }
            out.inSpans(ForegroundColorSpan(faded)) { append("  →  ") }
            out.inSpans(ForegroundColorSpan(accent), StyleSpan(Typeface.BOLD)) { append(d.to) }
        }
        return out
    }

    /** The field-by-field table: the source list's value, then the tracker's, differing rows marked. */
    private fun bindDetail(holder: Holder, entry: ListCompare.DiffEntry) {
        val ctx = holder.binding.root.context
        val notSet = ctx.getString(R.string.list_diff_not_set)
        CompareTable.fill(
            holder.binding.diffDetail,
            sourceIcons = listOf(sourceIcon(entry)),
            destIcons = listOf(destIcon(entry)),
            rows = entry.detail.map { row ->
                CompareTable.Row(
                    label = label(ctx, row.field, entry.isAnime),
                    source = row.source ?: notSet,
                    dest = row.dest ?: notSet,
                    differs = row.differs,
                )
            },
        )
    }

    /** Which source this row is pushing from — MangaUpdates entries are the ones carrying a MU id. */
    private fun sourceIcon(entry: ListCompare.DiffEntry): Int =
        if (entry.muSeriesId != null) R.drawable.ic_round_mangaupdates_24 else R.drawable.ic_anilist

    private fun destIcon(entry: ListCompare.DiffEntry): Int = when (entry.tracker) {
        ListCompare.Tracker.MAL -> R.drawable.ic_myanimelist
        ListCompare.Tracker.KITSU -> R.drawable.ic_kitsu
        ListCompare.Tracker.SIMKL -> R.drawable.ic_simkl
        ListCompare.Tracker.MANGABAKA -> R.drawable.ic_round_mangabaka_24
        ListCompare.Tracker.COMICK -> R.drawable.ic_round_comick_24
    }

    private fun trackerNameRes(tracker: ListCompare.Tracker): Int = when (tracker) {
        ListCompare.Tracker.MAL -> R.string.myanimelist
        ListCompare.Tracker.KITSU -> R.string.kitsu
        ListCompare.Tracker.SIMKL -> R.string.simkl
        ListCompare.Tracker.MANGABAKA -> R.string.mangabaka
        ListCompare.Tracker.COMICK -> R.string.comick
    }

    /**
     * Shows the title/cover, looking up whatever the row didn't arrive with. MangaBaka deletion rows
     * have neither (the library list endpoint embeds no series info); MangaUpdates rows have a title
     * but no cover (the MangaUpdates list API returns none, and a row being *added* to MyAnimeList
     * has no picture there to borrow either). Both resolve through MangaBaka — by series id where we
     * have one, by MangaUpdates id otherwise.
     */
    private fun bindTitleAndCover(holder: Holder, entry: ListCompare.DiffEntry, position: Int) {
        val b = holder.binding
        val context = b.root.context
        val mangaBakaId = entry.mangaBakaSeriesId
        val muId = entry.muSeriesId

        // The row's own values win; anything fetched only fills a gap.
        fun show(fetched: Pair<String?, String?>?) {
            val title = entry.title.takeIf { it.isNotBlank() } ?: fetched?.first
            b.diffTitle.text = title
                ?: mangaBakaId?.let { context.getString(R.string.list_diff_mangabaka_id, it) }
                ?: ""
            val cover = entry.coverUrl ?: fetched?.second
            if (cover != null) b.diffCover.loadImage(cover) else b.diffCover.setImageDrawable(null)
        }

        val key = when {
            mangaBakaId != null -> "mb:$mangaBakaId"
            muId != null -> "mu:$muId"
            else -> null
        }
        if ((entry.title.isNotBlank() && entry.coverUrl != null) || key == null) {
            show(null)
            return
        }
        val cached = seriesInfo[key]
        if (cached != null) {
            show(cached)
            return
        }
        show(null)
        holder.job = scope.launch {
            val info = (
                if (mangaBakaId != null) ListCompare.mangaBakaSeriesInfo(mangaBakaId)
                else ListCompare.mangaUpdatesSeriesInfo(muId!!)
                ) ?: return@launch
            seriesInfo[key] = info
            if (holder.bindingAdapterPosition == position) show(info)
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.job = null
        holder.binding.diffSync.setIconSpinning(false)
    }

    fun removeAt(position: Int) {
        if (position in items.indices) {
            expanded.remove(items[position])
            syncing.remove(items[position])
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
        val kept = newItems.toHashSet()
        expanded.retainAll(kept)
        syncing.retainAll(kept)
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
