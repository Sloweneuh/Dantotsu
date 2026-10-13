package ani.dantotsu.media.novel

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemChapterListBinding
import ani.dantotsu.media.bindSelectionTint
import ani.dantotsu.parsers.novel.lnreader.LNReaderChapter
import ani.dantotsu.parsers.novel.lnreader.LNReaderDates
import ani.dantotsu.setAnimation

/**
 * The novel chapter list shown on the media page.
 *
 * Deliberately the same row layout the manga chapter list uses, so a novel reads like everything
 * else in the app rather than like a separate feature. What differs is what a row can offer: a
 * chapter is fetched, not a file to stream, so there is no per-chapter download state to animate —
 * saving happens over a run of chapters from the header.
 */
class NovelChapterAdapter(
    private val onChapterClicked: (Int) -> Unit,
    private val onOpenInBrowser: (Int) -> Unit,
    private val onDownload: (Int) -> Unit,
) : RecyclerView.Adapter<NovelChapterAdapter.Holder>() {

    /**
     * Chapters picked by long-press, keyed by their index in the novel, so a selection spans the
     * chapter-range tabs. While any are picked, a tap toggles a chapter instead of opening it,
     * and the Read tab shows its selection toolbar.
     */
    val selection = linkedMapOf<Int, LNReaderChapter>()

    /** Called whenever [selection] changes. */
    var onSelectionChanged: (() -> Unit)? = null

    fun toggleSelection(row: Row) {
        if (selection.remove(row.indexInNovel) == null) selection[row.indexInNovel] = row.chapter
        val position = rows.indexOfFirst { it.indexInNovel == row.indexInNovel }
        if (position >= 0) notifyItemChanged(position, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke()
    }

    fun selectAll() {
        rows.forEach { selection[it.indexInNovel] = it.chapter }
        notifyItemRangeChanged(0, rows.size, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke()
    }

    fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        notifyItemRangeChanged(0, rows.size, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke()
    }

    /** Drops selected chapters that are no longer in the novel (e.g. after a source change). */
    fun keepSelectionWithin(chapterCount: Int) {
        if (selection.keys.retainAll { it < chapterCount }) onSelectionChanged?.invoke()
    }

    private companion object {
        /** Payload: only the selection highlight changed. */
        const val PAYLOAD_SELECTION = "selection"
    }

    /** The slice currently on screen, together with each chapter's index in the full list. */
    private var rows: List<Row> = emptyList()

    data class Row(
        val chapter: LNReaderChapter,
        val indexInNovel: Int,
        /** Counted by the tracker — what the eye means on every other chapter list in the app. */
        val tracked: Boolean,
        val downloaded: Boolean,
    )

    fun submit(new: List<Row>) {
        rows = new
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemChapterListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val b = holder.binding
        setAnimation(b.root.context, b.root)

        b.itemChapterNumber.text = row.chapter.name
        b.itemChapterTitle.isVisible = false

        // Read state comes from the list and nothing else, exactly as it does for manga and anime:
        // the dimming overlay says "your list counts this", and having opened a chapter on this
        // device is not a claim the row makes. (No eye icon: the dim says it, as for manga.)
        b.itemEpisodeViewed.isVisible = false
        b.itemEpisodeViewedCover.isVisible = row.tracked

        // A row saves just its own chapter; the header's button is for a run of them. Neither is
        // offered for a saved run, where the chapter is already on disk and has no page behind it.
        b.itemDownload.isVisible = !row.downloaded
        b.itemChapterBrowser.isVisible = !row.downloaded
        b.itemDownload.setOnClickListener { onDownload(row.indexInNovel) }
        b.itemChapterBrowser.setOnClickListener { onOpenInBrowser(row.indexInNovel) }

        val date = LNReaderDates.format(row.chapter.releaseTime)
        val hasDate = date.isNotBlank()
        b.itemChapterDateLayout.isVisible = hasDate || row.downloaded
        b.itemChapterDate.isVisible = hasDate
        b.itemChapterDate.text = date
        b.itemChapterScan.isVisible = row.downloaded
        b.itemChapterScan.text = if (row.downloaded) "Saved" else ""
        b.itemChapterDateDivider.isVisible = hasDate && row.downloaded

        b.root.bindSelectionTint(row.indexInNovel in selection, animate = false)
        b.root.setOnClickListener {
            if (selection.isNotEmpty()) toggleSelection(row) else onChapterClicked(row.indexInNovel)
        }
        b.root.setOnLongClickListener {
            toggleSelection(row)
            true
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        val row = rows.getOrNull(position)
        if (row != null && payloads.isNotEmpty() && payloads.all { it == PAYLOAD_SELECTION }) {
            holder.binding.root.bindSelectionTint(row.indexInNovel in selection, animate = true)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    class Holder(val binding: ItemChapterListBinding) : RecyclerView.ViewHolder(binding.root)
}
