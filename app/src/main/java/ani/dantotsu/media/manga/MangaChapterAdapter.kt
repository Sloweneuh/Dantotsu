package ani.dantotsu.media.manga

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.currContext
import ani.dantotsu.databinding.ItemChapterGapBinding
import ani.dantotsu.databinding.ItemChapterGapCompactBinding
import ani.dantotsu.databinding.ItemChapterListBinding
import ani.dantotsu.databinding.ItemEpisodeCompactBinding
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.bindSelectionTint
import ani.dantotsu.media.popIn
import ani.dantotsu.media.manga.mangareader.PdfPageRenderer
import ani.dantotsu.parsers.DynamicMangaParser
import ani.dantotsu.setAnimation
import ani.dantotsu.util.customAlertDialog
import eu.kanade.tachiyomi.source.online.HttpSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MangaChapterAdapter(
    private var type: Int,
    private val media: Media,
    private val fragment: MangaReadFragment,
    private val mangaReadSources: ani.dantotsu.parsers.MangaReadSources,
    var arr: ArrayList<MangaChapterListItem> = arrayListOf(),
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_LIST = 0
        const val VIEW_TYPE_COMPACT = 1
        const val VIEW_TYPE_GAP = 2
        const val VIEW_TYPE_GAP_COMPACT = 3

        /**
         * Payload marker for download-state-only changes (progress %, downloaded/downloading icon).
         * Rebinding with this payload updates just the download button/progress text instead of the
         * whole item, so frequent progress ticks don't replay the item's entrance animation or touch
         * unrelated views (title, date, scanlator, viewed cover) — that full rebind was causing the
         * chapter row to visibly flash and bounce on every download progress update.
         */
        private const val PAYLOAD_DOWNLOAD_STATE = "download_state"

        /** Payload marker for a selection-highlight-only change. */
        private const val PAYLOAD_SELECTION = "selection"
    }

    /**
     * Chapters picked by long-press, keyed by unique number. Kept as chapters rather than numbers
     * so a selection spans the chapter-range tabs: switching tabs swaps [arr] but keeps these.
     * While any are picked, a tap toggles a chapter instead of opening it, and the read tab shows
     * its selection toolbar.
     */
    val selection = linkedMapOf<String, MangaChapter>()

    /** Called whenever [selection] changes. */
    var onSelectionChanged: (() -> Unit)? = null

    fun selectedChapters(): List<MangaChapter> = selection.values.toList()

    fun toggleSelection(chapter: MangaChapter) {
        val number = chapter.uniqueNumber()
        if (selection.remove(number) == null) selection[number] = chapter
        val index = chapterIndexOf(number)
        if (index >= 0) notifyItemChanged(index, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke()
    }

    fun selectAll() {
        arr.forEach { if (it is MangaChapterListItem.Chapter) selection[it.chapter.uniqueNumber()] = it.chapter }
        notifyItemRangeChanged(0, arr.size, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke()
    }

    /** Drops selected chapters that are no longer listed at all (e.g. after a source change). */
    fun keepSelectionWithin(chapters: Collection<MangaChapter>) {
        val listed = chapters.mapTo(HashSet()) { it.uniqueNumber() }
        if (selection.keys.retainAll(listed)) onSelectionChanged?.invoke()
    }

    fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        notifyItemRangeChanged(0, arr.size, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke()
    }

    /** True for a chapter that's downloaded or currently downloading. */
    fun hasDownload(chapter: MangaChapter): Boolean {
        val number = chapter.uniqueNumber()
        return activeDownloads.contains(number) || isDownloaded(number)
    }

    fun isDownloading(chapter: MangaChapter): Boolean = activeDownloads.contains(chapter.uniqueNumber())

    /** Tap: toggles while selecting, opens otherwise. Long-press: starts or extends a selection. */
    private fun bindChapterClicks(holder: RecyclerView.ViewHolder) {
        holder.itemView.setOnClickListener {
            val item = arr.getOrNull(holder.bindingAdapterPosition) as? MangaChapterListItem.Chapter
                ?: return@setOnClickListener
            if (selection.isNotEmpty()) toggleSelection(item.chapter)
            else fragment.onMangaChapterClick(item.chapter)
        }
        holder.itemView.setOnLongClickListener {
            val item = arr.getOrNull(holder.bindingAdapterPosition) as? MangaChapterListItem.Chapter
                ?: return@setOnLongClickListener false
            toggleSelection(item.chapter)
            true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_COMPACT -> ChapterCompactViewHolder(
                ItemEpisodeCompactBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
            VIEW_TYPE_LIST -> ChapterListViewHolder(
                ItemChapterListBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
            VIEW_TYPE_GAP -> ChapterGapViewHolder(
                ItemChapterGapBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
            VIEW_TYPE_GAP_COMPACT -> ChapterGapCompactViewHolder(
                ItemChapterGapCompactBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
            else -> throw IllegalArgumentException()
        }
    }

    override fun getItemViewType(position: Int): Int {
        if (position < 0 || position >= arr.size) return VIEW_TYPE_LIST
        return when (arr[position]) {
            is MangaChapterListItem.Gap -> if (type == VIEW_TYPE_COMPACT) VIEW_TYPE_GAP_COMPACT else VIEW_TYPE_GAP
            is MangaChapterListItem.Chapter -> if (type == VIEW_TYPE_COMPACT) VIEW_TYPE_COMPACT else VIEW_TYPE_LIST
        }
    }

    override fun getItemCount(): Int = arr.size

    // Helper to find the adapter position of a chapter by its unique number
    private fun chapterIndexOf(chapterNumber: String): Int {
        return arr.indexOfFirst {
            it is MangaChapterListItem.Chapter && it.chapter.uniqueNumber() == chapterNumber
        }
    }

    // Helper to get actual chapter count after a given adapter position (for multi-op dialogs).
    // Premium chapters can't be downloaded or deleted, so they're excluded from this count.
    private fun chapterCountFrom(adapterPosition: Int): Int {
        return arr.drop(adapterPosition)
            .count { it is MangaChapterListItem.Chapter && !it.chapter.isPremium() }
    }

    inner class ChapterGapViewHolder(val binding: ItemChapterGapBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class ChapterGapCompactViewHolder(val binding: ItemChapterGapCompactBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class ChapterCompactViewHolder(val binding: ItemEpisodeCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            bindChapterClicks(this)
        }
    }

    private val activeDownloads = mutableSetOf<String>()
    private val downloadedChapters = mutableSetOf<String>()

    /** Last reported percentage per downloading chapter; the ring spins until there is one. */
    private val downloadPercent = mutableMapOf<String, Int>()

    /** Chapters whose download just completed: their icon pops in once on the next bind. */
    private val justDownloaded = mutableSetOf<String>()

    /**
     * When reading from the offline source, every listed chapter is local by definition, so
     * treat them all as downloaded (shows the delete affordance instead of a download icon).
     * Set right before the chapter list is (re)bound, so no extra notify is needed here.
     */
    var offlineMode: Boolean = false

    private fun isDownloaded(chapterNumber: String): Boolean =
        offlineMode || downloadedChapters.contains(chapterNumber)

    /**
     * If [chapter] is bundled inside a one-file PDF, returns how many chapters share that
     * file (they'd all be deleted together); otherwise 1.
     */
    private fun pdfSiblings(chapter: MangaChapter): List<MangaChapter> {
        val uri = PdfPageRenderer.decodeChapter(chapter.link)?.first ?: return listOf(chapter)
        return arr.filterIsInstance<MangaChapterListItem.Chapter>()
            .map { it.chapter }
            .filter { PdfPageRenderer.decodeChapter(it.link)?.first == uri }
    }

    /** "First - Last" chapter range covered by a set of bundled sibling chapters. */
    private fun pdfSiblingRange(siblings: List<MangaChapter>): String {
        val sorted = siblings.sortedBy {
            MediaNameAdapter.findChapterNumber(it.number) ?: Float.MAX_VALUE
        }
        val first = sorted.first()
        val last = sorted.last()
        return "${first.title ?: first.number} - ${last.title ?: last.number}"
    }

    fun startDownload(chapterNumber: String) {
        // If completion was already processed (e.g. a fast batch download finished before
        // this "started" signal arrived), don't flip the chapter back to the downloading
        // state — that would leave the icon stuck spinning instead of showing "downloaded".
        if (downloadedChapters.contains(chapterNumber)) return
        activeDownloads.add(chapterNumber)
        val position = chapterIndexOf(chapterNumber)
        if (position != -1) notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
    }

    fun stopDownload(chapterNumber: String) {
        if (activeDownloads.remove(chapterNumber)) justDownloaded.add(chapterNumber)
        downloadPercent.remove(chapterNumber)
        downloadedChapters.add(chapterNumber)
        val position = chapterIndexOf(chapterNumber)
        if (position != -1) {
            (arr[position] as? MangaChapterListItem.Chapter)?.chapter?.progress = "Downloaded"
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    /**
     * Marks a chapter as downloaded (delete icon) without the transient "Downloaded" label.
     * Used when restoring state on a list rebuild, matching how ordinary downloads look once
     * their fresh chapter objects (with empty progress) are re-bound.
     */
    fun markDownloaded(chapterNumber: String) {
        activeDownloads.remove(chapterNumber)
        downloadPercent.remove(chapterNumber)
        downloadedChapters.add(chapterNumber)
        val position = chapterIndexOf(chapterNumber)
        if (position != -1) notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
    }

    fun deleteDownload(chapterNumber: MangaChapter) {
        downloadedChapters.remove(chapterNumber.uniqueNumber())
        val position = chapterIndexOf(chapterNumber.uniqueNumber())
        if (position != -1) {
            (arr[position] as? MangaChapterListItem.Chapter)?.chapter?.progress = ""
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    fun purgeDownload(chapterNumber: String) {
        activeDownloads.remove(chapterNumber)
        downloadPercent.remove(chapterNumber)
        downloadedChapters.remove(chapterNumber)
        val position = chapterIndexOf(chapterNumber)
        if (position != -1) {
            (arr[position] as? MangaChapterListItem.Chapter)?.chapter?.progress = ""
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    fun updateDownloadProgress(chapterNumber: String, progress: Int, stats: String = "") {
        // Ignore a stale tick for a chapter that's no longer actively downloading. A page whose
        // fetch was already in flight when the chapter was cancelled has no suspension point left
        // before it saves to disk and broadcasts its progress, so it can still emit one last
        // update *after* the cancel's own broadcast clears the line — silently resurrecting the
        // frozen "Downloading: ..." text regardless of broadcast arrival order. This check makes
        // the fix independent of that race instead of trying to win it.
        if (!activeDownloads.contains(chapterNumber)) return
        downloadPercent[chapterNumber] = progress
        val position = chapterIndexOf(chapterNumber)
        if (position != -1) {
            val text = if (stats.isEmpty()) "Downloading: ${progress}%"
            else "Downloading: ${progress}% · $stats"
            (arr[position] as? MangaChapterListItem.Chapter)?.chapter?.progress = text
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    fun downloadNChaptersFrom(position: Int, n: Int) {
        if (position < 0 || position >= arr.size) return
        var count = 0
        val toDownload = mutableListOf<MangaChapter>()
        for (i in position until arr.size) {
            if (count >= n) break
            val item = arr[i]
            if (item !is MangaChapterListItem.Chapter) continue
            // Premium chapters have no downloadable content, so skip them entirely without
            // consuming one of the N slots — they're not part of the mass download range.
            if (item.chapter.isPremium()) continue
            count++
            val chapterNumber = item.chapter.uniqueNumber()
            if (!activeDownloads.contains(chapterNumber) && !isDownloaded(chapterNumber)) {
                toDownload += item.chapter
            }
        }
        // Downloaded as one ordered batch (rather than one independent call per chapter) so the
        // resulting download queue matches this chapter order instead of racing on each
        // chapter's page-list fetch time.
        if (toDownload.isNotEmpty()) fragment.downloadChaptersInOrder(toDownload)
    }

    fun deleteNChaptersFrom(position: Int, n: Int) {
        if (position < 0 || position >= arr.size) return
        var count = 0
        for (i in position until arr.size) {
            if (count >= n) break
            val item = arr[i]
            if (item !is MangaChapterListItem.Chapter) continue
            count++
            val chapterNumber = item.chapter.uniqueNumber()
            when {
                activeDownloads.contains(chapterNumber) ->
                    fragment.onMangaChapterStopDownloadClick(item.chapter)
                isDownloaded(chapterNumber) ->
                    fragment.onMangaChapterRemoveDownloadClick(item.chapter)
            }
        }
    }

    inner class ChapterListViewHolder(val binding: ItemChapterListBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(chapter: MangaChapter) {
            val chapterNumber = chapter.uniqueNumber()
            val progress = chapter.progress
            if (chapter.isPremium()) {
                // No downloadable content behind a premium chapter — hide the download
                // affordance entirely instead of showing a button that can't do anything.
                binding.itemChapterTitle.visibility = View.GONE
                binding.itemChapterTitle.text = ""
                binding.itemDownload.visibility = View.GONE
                return
            }
            binding.itemDownload.visibility = View.VISIBLE
            // An empty (not just null) progress means "nothing to show" — e.g. purgeDownload
            // clears it to "" on cancel/delete — so it must collapse the row too, or it's left
            // visible-but-empty, reserving blank space under the date/scanlator line.
            if (!progress.isNullOrEmpty()) {
                binding.itemChapterTitle.visibility = View.VISIBLE
                binding.itemChapterTitle.text = progress
            } else {
                binding.itemChapterTitle.visibility = View.GONE
                binding.itemChapterTitle.text = ""
            }
            val ring = binding.itemDownloadProgress
            if (activeDownloads.contains(chapterNumber)) {
                // The ring stands in for the icon; the button stays under it to stop the download.
                binding.itemDownload.alpha = 0f
                val percent = downloadPercent[chapterNumber]
                if (percent == null) {
                    if (!ring.isIndeterminate) {
                        ring.isVisible = false
                        ring.isIndeterminate = true
                    }
                } else {
                    if (ring.isIndeterminate) {
                        ring.isVisible = false
                        ring.isIndeterminate = false
                    }
                    ring.setProgressCompat(percent, true)
                }
                ring.isVisible = true
                return
            }
            ring.isVisible = false
            binding.itemDownload.alpha = 1f
            binding.itemDownload.tag = chapterNumber
            val justFinished = justDownloaded.remove(chapterNumber)
            if (offlineMode) {
                // Everything is local; show the delete affordance directly (no check flash).
                binding.itemDownload.setImageResource(R.drawable.ic_round_delete_24)
                binding.itemDownload.rotation = 0f
            } else if (isDownloaded(chapterNumber)) {
                if (justFinished) {
                    // A download that just completed: pop in a check, then settle on delete.
                    binding.itemDownload.setImageResource(R.drawable.ic_circle_check)
                    binding.itemDownload.popIn()
                    binding.itemDownload.postDelayed({
                        // The row may have been recycled for another chapter meanwhile.
                        if (binding.itemDownload.tag == chapterNumber &&
                            !activeDownloads.contains(chapterNumber)
                        ) binding.itemDownload.setImageResource(R.drawable.ic_round_delete_24)
                    }, 1000)
                } else {
                    // Already on disk when the list loaded: straight to delete.
                    binding.itemDownload.setImageResource(R.drawable.ic_round_delete_24)
                }
                binding.itemDownload.rotation = 0f
            } else {
                binding.itemDownload.setImageResource(R.drawable.ic_download_24)
                binding.itemDownload.rotation = 0f
            }
        }

        init {
            bindChapterClicks(this)
            binding.itemDownload.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in 0 until arr.size) {
                    val item = arr[pos] as? MangaChapterListItem.Chapter ?: return@setOnClickListener
                    val chapter = item.chapter
                    if (chapter.isPremium()) return@setOnClickListener
                    val chapterNumber = chapter.uniqueNumber()
                    when {
                        activeDownloads.contains(chapterNumber) -> {
                            fragment.onMangaChapterStopDownloadClick(chapter)
                            return@setOnClickListener
                        }
                        isDownloaded(chapterNumber) -> {
                            val siblings = pdfSiblings(chapter)
                            val prettyName = chapter.title ?: chapter.number
                            it.context.customAlertDialog().apply {
                                setTitle(it.context.getString(R.string.delete_chapter))
                                // A one-file PDF bundles several chapters; make it clear that
                                // deleting one removes every chapter sharing the file, and name
                                // the chapter range that will be removed.
                                if (siblings.size > 1) {
                                    setMessage(
                                        it.context.getString(
                                            R.string.delete_one_file_warning_range,
                                            siblings.size,
                                            pdfSiblingRange(siblings)
                                        )
                                    )
                                } else {
                                    setMessage(
                                        it.context.getString(
                                            R.string.are_you_sure_delete_item,
                                            prettyName
                                        )
                                    )
                                }
                                setPosButton(R.string.delete) {
                                    fragment.onMangaChapterRemoveDownloadClick(chapter)
                                }
                                setNegButton(R.string.cancel)
                                show()
                            }
                            return@setOnClickListener
                        }
                        else -> fragment.downloadChapterWithPdfPrompt(chapter)
                    }
                }
            }
            binding.itemDownload.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos in 0 until arr.size) {
                    val item = arr[pos] as? MangaChapterListItem.Chapter ?: return@setOnLongClickListener true
                    val chapterNumber = item.chapter.uniqueNumber()
                    val chaptersAvailable = chapterCountFrom(pos)
                    if (activeDownloads.contains(chapterNumber) || isDownloaded(chapterNumber)) {
                        fragment.requireContext().customAlertDialog().apply {
                            setTitle("Multi Chapter Deleter")
                            setMessage("Enter the number of chapters to delete")
                            val input = NumberPicker(currContext())
                            input.minValue = 1
                            input.maxValue = chaptersAvailable
                            input.value = 1
                            setCustomView(input)
                            setPosButton(R.string.ok) {
                                binding.root.context.customAlertDialog().apply {
                                    setTitle("Delete Chapters")
                                    setMessage("Are you sure you want to delete the next ${input.value} chapters?")
                                    setPosButton(R.string.yes) {
                                        deleteNChaptersFrom(pos, input.value)
                                    }
                                    setNegButton(R.string.no)
                                }.show()
                            }
                            setNegButton(R.string.cancel)
                            show()
                        }
                    } else {
                        it.context.customAlertDialog().apply {
                            setTitle("Multi Chapter Downloader")
                            setMessage("Enter the number of chapters to download")
                            val input = NumberPicker(currContext())
                            input.minValue = 1
                            input.maxValue = chaptersAvailable
                            input.value = 1
                            setCustomView(input)
                            setPosButton("OK") {
                                downloadNChaptersFrom(pos, input.value)
                            }
                            setNegButton("Cancel")
                            show()
                        }
                    }
                }
                true
            }

            binding.itemChapterBrowser.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in 0 until arr.size) {
                    val item = arr[pos] as? MangaChapterListItem.Chapter ?: return@setOnClickListener
                    fragment.openChapterInBrowser(item.chapter)
                }
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_DOWNLOAD_STATE) && holder is ChapterListViewHolder) {
            val item = arr.getOrNull(position) as? MangaChapterListItem.Chapter ?: return
            holder.bind(item.chapter)
            return
        }
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_SELECTION }) {
            val item = arr.getOrNull(position) as? MangaChapterListItem.Chapter ?: return
            holder.itemView.bindSelectionTint(item.chapter.uniqueNumber() in selection, animate = true)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ChapterGapViewHolder -> {
                val gap = arr[position] as MangaChapterListItem.Gap
                val ctx = holder.binding.root.context
                holder.binding.itemChapterGapText.text = if (gap.count == 1)
                    ctx.getString(R.string.chapter_missing_single)
                else
                    ctx.getString(R.string.chapters_missing, gap.count)
            }

            is ChapterGapCompactViewHolder -> {
                val gap = arr[position] as MangaChapterListItem.Gap
                holder.binding.itemChapterGapCompactNumber.text = gap.fromNumber.toInt().toString()
            }

            is ChapterCompactViewHolder -> {
                val item = arr[position] as? MangaChapterListItem.Chapter ?: return
                val ep = item.chapter
                val binding = holder.binding
                setAnimation(fragment.requireContext(), binding.root)
                val parsedNumber = MediaNameAdapter.findChapterNumber(ep.number)?.toInt()
                binding.itemEpisodeNumber.text = parsedNumber?.toString() ?: ep.number

                if (media.userProgress != null) {
                    if ((MediaNameAdapter.findChapterNumber(ep.number) ?: 9999f) <= media.userProgress!!.toFloat()) {
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                    } else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                    }
                }
                holder.itemView.bindSelectionTint(ep.uniqueNumber() in selection, animate = false)
            }

            is ChapterListViewHolder -> {
                val item = arr[position] as? MangaChapterListItem.Chapter ?: return
                val ep = item.chapter
                val binding = holder.binding
                holder.bind(ep)
                setAnimation(fragment.requireContext(), binding.root)
                binding.itemChapterNumber.text = ep.number

                setupChapterBrowserButton(binding.itemChapterBrowser)

                if (ep.date != null) {
                    binding.itemChapterDateLayout.visibility = View.VISIBLE
                    binding.itemChapterDate.text = formatDate(ep.date)
                }
                if (ep.scanlator != null) {
                    binding.itemChapterDateLayout.visibility = View.VISIBLE
                    binding.itemChapterScan.text = ep.scanlator.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                    }
                }
                if (formatDate(ep.date) == "" || ep.scanlator == null) {
                    binding.itemChapterDateDivider.visibility = View.GONE
                } else binding.itemChapterDateDivider.visibility = View.VISIBLE

                // Read chapters are dimmed; the eye icon is left off, the dim says it.
                binding.itemEpisodeViewed.visibility = View.GONE
                binding.itemEpisodeViewedCover.visibility =
                    if (media.userProgress != null &&
                        (MediaNameAdapter.findChapterNumber(ep.number) ?: 9999f) <= media.userProgress!!.toFloat()
                    ) View.VISIBLE else View.GONE
                holder.itemView.bindSelectionTint(ep.uniqueNumber() in selection, animate = false)
            }
        }
    }

    fun updateType(t: Int) {
        type = t
    }

    private fun formatDate(timestamp: Long?): String {
        timestamp ?: return ""

        val targetDate = Date(timestamp)
        if (targetDate < Date(946684800000L)) return ""

        val currentDate = Date()
        val difference = currentDate.time - targetDate.time

        return when (val daysDifference = difference / (1000 * 60 * 60 * 24)) {
            0L -> {
                val hoursDifference = difference / (1000 * 60 * 60)
                val minutesDifference = (difference / (1000 * 60)) % 60
                when {
                    hoursDifference > 0 -> "$hoursDifference hour${if (hoursDifference > 1) "s" else ""} ago"
                    minutesDifference > 0 -> "$minutesDifference minute${if (minutesDifference > 1) "s" else ""} ago"
                    else -> "Just now"
                }
            }
            1L -> "1 day ago"
            in 2..6 -> "$daysDifference days ago"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(targetDate)
        }
    }

    private fun setupChapterBrowserButton(button: View) {
        val parser = mangaReadSources[media.selected!!.sourceIndex]
        if (parser is DynamicMangaParser) {
            val httpSource = parser.extension.sources.getOrNull(parser.sourceLanguage) as? HttpSource
            button.visibility = if (httpSource != null) View.VISIBLE else View.GONE
        } else {
            button.visibility = View.GONE
        }
    }
}
