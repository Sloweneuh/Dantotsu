package ani.dantotsu.media.manga

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.NumberPicker
import androidx.lifecycle.coroutineScope
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.updateProgress
import ani.dantotsu.currContext
import ani.dantotsu.databinding.ItemChapterGapBinding
import ani.dantotsu.databinding.ItemChapterGapCompactBinding
import ani.dantotsu.databinding.ItemChapterListBinding
import ani.dantotsu.databinding.ItemEpisodeCompactBinding
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.parsers.DynamicMangaParser
import ani.dantotsu.setAnimation
import ani.dantotsu.util.customAlertDialog
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    // Helper to get actual chapter count after a given adapter position (for multi-op dialogs)
    private fun chapterCountFrom(adapterPosition: Int): Int {
        return arr.drop(adapterPosition).count { it is MangaChapterListItem.Chapter }
    }

    inner class ChapterGapViewHolder(val binding: ItemChapterGapBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class ChapterGapCompactViewHolder(val binding: ItemChapterGapCompactBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class ChapterCompactViewHolder(val binding: ItemEpisodeCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in 0 until arr.size) {
                    val item = arr[pos]
                    if (item is MangaChapterListItem.Chapter)
                        fragment.onMangaChapterClick(item.chapter)
                }
            }
        }
    }

    private val activeDownloads = mutableSetOf<String>()
    private val downloadedChapters = mutableSetOf<String>()

    fun startDownload(chapterNumber: String) {
        activeDownloads.add(chapterNumber)
        val position = chapterIndexOf(chapterNumber)
        if (position != -1) notifyItemChanged(position)
    }

    fun stopDownload(chapterNumber: String) {
        activeDownloads.remove(chapterNumber)
        downloadedChapters.add(chapterNumber)
        val position = chapterIndexOf(chapterNumber)
        if (position != -1) {
            (arr[position] as? MangaChapterListItem.Chapter)?.chapter?.progress = "Downloaded"
            notifyItemChanged(position)
        }
    }

    fun deleteDownload(chapterNumber: MangaChapter) {
        downloadedChapters.remove(chapterNumber.uniqueNumber())
        val position = chapterIndexOf(chapterNumber.uniqueNumber())
        if (position != -1) {
            (arr[position] as? MangaChapterListItem.Chapter)?.chapter?.progress = ""
            notifyItemChanged(position)
        }
    }

    fun purgeDownload(chapterNumber: String) {
        activeDownloads.remove(chapterNumber)
        downloadedChapters.remove(chapterNumber)
        val position = chapterIndexOf(chapterNumber)
        if (position != -1) {
            (arr[position] as? MangaChapterListItem.Chapter)?.chapter?.progress = ""
            notifyItemChanged(position)
        }
    }

    fun updateDownloadProgress(chapterNumber: String, progress: Int) {
        val position = chapterIndexOf(chapterNumber)
        if (position != -1) {
            (arr[position] as? MangaChapterListItem.Chapter)?.chapter?.progress = "Downloading: ${progress}%"
            notifyItemChanged(position)
        }
    }

    fun downloadNChaptersFrom(position: Int, n: Int) {
        if (position < 0 || position >= arr.size) return
        var count = 0
        for (i in position until arr.size) {
            if (count >= n) break
            val item = arr[i]
            if (item !is MangaChapterListItem.Chapter) continue
            count++
            val chapterNumber = item.chapter.uniqueNumber()
            if (!activeDownloads.contains(chapterNumber) && !downloadedChapters.contains(chapterNumber)) {
                fragment.onMangaChapterDownloadClick(item.chapter)
            }
        }
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
                downloadedChapters.contains(chapterNumber) ->
                    fragment.onMangaChapterRemoveDownloadClick(item.chapter)
            }
        }
    }

    inner class ChapterListViewHolder(val binding: ItemChapterListBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val activeCoroutines = mutableSetOf<String>()

        fun bind(chapterNumber: String, progress: String?) {
            if (progress != null) {
                binding.itemChapterTitle.visibility = View.VISIBLE
                binding.itemChapterTitle.text = "$progress"
            } else {
                binding.itemChapterTitle.visibility = View.GONE
                binding.itemChapterTitle.text = ""
            }
            if (activeDownloads.contains(chapterNumber)) {
                binding.itemDownload.setImageResource(R.drawable.ic_sync)
                startOrContinueRotation(chapterNumber) {
                    binding.itemDownload.rotation = 0f
                }
            } else if (downloadedChapters.contains(chapterNumber)) {
                binding.itemDownload.setImageResource(R.drawable.ic_circle_check)
                binding.itemDownload.postDelayed({
                    binding.itemDownload.setImageResource(R.drawable.ic_round_delete_24)
                    binding.itemDownload.rotation = 0f
                }, 1000)
            } else {
                binding.itemDownload.setImageResource(R.drawable.ic_download_24)
                binding.itemDownload.rotation = 0f
            }
        }

        private fun startOrContinueRotation(chapterNumber: String, resetRotation: () -> Unit) {
            if (!isRotationCoroutineRunningFor(chapterNumber)) {
                val scope = fragment.lifecycle.coroutineScope
                scope.launch {
                    activeCoroutines.add(chapterNumber)
                    while (activeDownloads.contains(chapterNumber)) {
                        binding.itemDownload.animate().rotationBy(360f).setDuration(1000)
                            .setInterpolator(LinearInterpolator()).start()
                        delay(1000)
                    }
                    activeCoroutines.remove(chapterNumber)
                    resetRotation()
                }
            }
        }

        private fun isRotationCoroutineRunningFor(chapterNumber: String): Boolean {
            return chapterNumber in activeCoroutines
        }

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in 0 until arr.size) {
                    val item = arr[pos]
                    if (item is MangaChapterListItem.Chapter)
                        fragment.onMangaChapterClick(item.chapter)
                }
            }
            binding.itemDownload.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in 0 until arr.size) {
                    val item = arr[pos] as? MangaChapterListItem.Chapter ?: return@setOnClickListener
                    val chapter = item.chapter
                    val chapterNumber = chapter.uniqueNumber()
                    when {
                        activeDownloads.contains(chapterNumber) -> {
                            fragment.onMangaChapterStopDownloadClick(chapter)
                            return@setOnClickListener
                        }
                        downloadedChapters.contains(chapterNumber) -> {
                            it.context.customAlertDialog().apply {
                                setTitle(it.context.getString(R.string.delete_chapter))
                                setMessage(it.context.getString(R.string.are_you_sure_delete_item, chapterNumber))
                                setPosButton(R.string.delete) {
                                    fragment.onMangaChapterRemoveDownloadClick(chapter)
                                }
                                setNegButton(R.string.cancel)
                                show()
                            }
                            return@setOnClickListener
                        }
                        else -> fragment.onMangaChapterDownloadClick(chapter)
                    }
                }
            }
            binding.itemDownload.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos in 0 until arr.size) {
                    val item = arr[pos] as? MangaChapterListItem.Chapter ?: return@setOnLongClickListener true
                    val chapterNumber = item.chapter.uniqueNumber()
                    val chaptersAvailable = chapterCountFrom(pos)
                    if (activeDownloads.contains(chapterNumber) || downloadedChapters.contains(chapterNumber)) {
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
                        binding.itemEpisodeCont.setOnLongClickListener {
                            updateProgress(media, MediaNameAdapter.findChapterNumber(ep.number).toString())
                            true
                        }
                    }
                }
            }

            is ChapterListViewHolder -> {
                val item = arr[position] as? MangaChapterListItem.Chapter ?: return
                val ep = item.chapter
                val binding = holder.binding
                holder.bind(ep.uniqueNumber(), ep.progress)
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

                if (ep.progress.isNullOrEmpty()) {
                    binding.itemChapterTitle.visibility = View.GONE
                } else binding.itemChapterTitle.visibility = View.VISIBLE

                if (media.userProgress != null) {
                    if ((MediaNameAdapter.findChapterNumber(ep.number) ?: 9999f) <= media.userProgress!!.toFloat()) {
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                        binding.itemEpisodeViewed.visibility = View.VISIBLE
                    } else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                        binding.itemEpisodeViewed.visibility = View.GONE
                        binding.root.setOnLongClickListener {
                            updateProgress(media, MediaNameAdapter.findChapterNumber(ep.number).toString())
                            true
                        }
                    }
                } else {
                    binding.itemEpisodeViewedCover.visibility = View.GONE
                    binding.itemEpisodeViewed.visibility = View.GONE
                }
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
