package ani.dantotsu.media.novel

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemChapterListBinding
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
    private val onChapterLongClicked: (Int) -> Unit,
    private val onOpenInBrowser: (Int) -> Unit,
    private val onDownload: (Int) -> Unit,
) : RecyclerView.Adapter<NovelChapterAdapter.Holder>() {

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
        // the eye and its dimming overlay say "your list counts this", and having opened a chapter
        // on this device is not a claim the row makes.
        b.itemEpisodeViewed.isVisible = row.tracked
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

        b.root.setOnClickListener { onChapterClicked(row.indexInNovel) }
        b.root.setOnLongClickListener {
            onChapterLongClicked(row.indexInNovel)
            true
        }
    }

    class Holder(val binding: ItemChapterListBinding) : RecyclerView.ViewHolder(binding.root)
}
