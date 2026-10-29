package ani.dantotsu.media.anime

import ani.dantotsu.settings.SectionCard
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemEpisodeCompactBinding
import ani.dantotsu.databinding.ItemEpisodeGridBinding
import ani.dantotsu.databinding.ItemEpisodeListBinding
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.bindSelectionTint
import ani.dantotsu.media.popIn
import ani.dantotsu.media.MediaType
import ani.dantotsu.parsers.DynamicAnimeParser
import ani.dantotsu.setAnimation
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.customAlertDialog
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import android.widget.NumberPicker
import ani.dantotsu.currContext
import ani.dantotsu.download.anime.AnimeDownloader
import kotlin.math.ln
import kotlin.math.pow

/**
 * Past this share of an episode it counts as watched: the player starts it over rather than
 * resuming a few seconds from the end, and the episode list shows its bar full to match.
 */
const val WATCHED_FRACTION = 0.92f

fun handleProgress(cont: LinearLayout, bar: View, empty: View, mediaId: Int, ep: String) {
    val curr = PrefManager.getNullableCustomVal("${mediaId}_${ep}", null, Long::class.java)
    val max = PrefManager.getNullableCustomVal("${mediaId}_${ep}_max", null, Long::class.java)
    if (curr != null && max != null) {
        cont.visibility = View.VISIBLE
        val div = (curr.toFloat() / max.toFloat()).let { if (it > WATCHED_FRACTION) 1f else it }
        val barParams = bar.layoutParams as LinearLayout.LayoutParams
        barParams.weight = div
        bar.layoutParams = barParams
        val params = empty.layoutParams as LinearLayout.LayoutParams
        params.weight = 1 - div
        empty.layoutParams = params
    } else {
        cont.visibility = View.GONE
        val barParams = bar.layoutParams as LinearLayout.LayoutParams
        barParams.weight = 0f
        bar.layoutParams = barParams
    }
}

@OptIn(UnstableApi::class)
class EpisodeAdapter(
    private var type: Int,
    private val media: Media,
    private val fragment: AnimeWatchFragment,
    private val watchSources: ani.dantotsu.parsers.WatchSources,
    var arr: List<Episode> = arrayListOf(),
    var offlineMode: Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val context = fragment.requireContext()

    private companion object {
        /** Payload: only the download button / status changed (progress ticks). */
        const val PAYLOAD_DOWNLOAD_STATE = "download_state"

        /** Payload: only the selection highlight changed. */
        const val PAYLOAD_SELECTION = "selection"
    }

    /**
     * Episodes picked by long-press, by number. Kept as episodes rather than numbers so a
     * selection spans the episode-range tabs. While any are picked, a tap toggles an episode
     * instead of playing it, and the Watch tab shows its selection toolbar.
     */
    val selection = linkedMapOf<String, Episode>()

    /** Called whenever [selection] changes. */
    var onSelectionChanged: (() -> Unit)? = null

    fun selectedEpisodes(): List<Episode> = selection.values.toList()

    fun toggleSelection(episode: Episode) {
        if (selection.remove(episode.number) == null) selection[episode.number] = episode
        val index = arr.indexOfFirst { it.number == episode.number }
        if (index >= 0) notifyItemChanged(index, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke()
    }

    fun selectAll() {
        arr.forEach { selection[it.number] = it }
        notifyItemRangeChanged(0, arr.size, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke()
    }

    fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        notifyItemRangeChanged(0, arr.size, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke()
    }

    /** Drops selected episodes that are no longer listed at all (e.g. after a source change). */
    fun keepSelectionWithin(episodes: Collection<Episode>) {
        val listed = episodes.mapTo(HashSet()) { it.number }
        if (selection.keys.retainAll(listed)) onSelectionChanged?.invoke()
    }

    fun isDownloading(episodeNumber: String) = AnimeDownloader.isDownloading(media.id, episodeNumber)

    /** True for an episode that's downloaded or currently downloading. */
    fun hasDownload(episodeNumber: String) =
        downloadedEpisodes.contains(episodeNumber) || isDownloading(episodeNumber)

    /** Tap: toggles while selecting, plays otherwise. Long-press: starts or extends a selection. */
    private fun bindEpisodeClicks(holder: RecyclerView.ViewHolder) {
        holder.itemView.setOnClickListener {
            val episode = arr.getOrNull(holder.bindingAdapterPosition) ?: return@setOnClickListener
            if (selection.isNotEmpty()) toggleSelection(episode)
            else fragment.onEpisodeClick(episode.number)
        }
        holder.itemView.setOnLongClickListener {
            val episode = arr.getOrNull(holder.bindingAdapterPosition) ?: return@setOnLongClickListener false
            toggleSelection(episode)
            true
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val ep = arr.getOrNull(position)
        if (ep != null && payloads.isNotEmpty() && payloads.all { it == PAYLOAD_SELECTION || it == PAYLOAD_DOWNLOAD_STATE }) {
            if (PAYLOAD_SELECTION in payloads) holder.itemView.bindSelectionTint(ep.number in selection, animate = true)
            if (PAYLOAD_DOWNLOAD_STATE in payloads && holder is EpisodeListViewHolder) {
                holder.bind(ep.number, ep.downloadProgress, ep.desc)
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return (when (viewType) {
            0 -> EpisodeListViewHolder(
                ItemEpisodeListBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            1 -> EpisodeGridViewHolder(
                ItemEpisodeGridBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            2 -> EpisodeCompactViewHolder(
                ItemEpisodeCompactBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> throw IllegalArgumentException()
        })
    }

    override fun getItemViewType(position: Int): Int {
        return type
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val ep = arr[position]
        val title = if (!ep.title.isNullOrEmpty() && ep.title != "null") {
            ep.title?.let { MediaNameAdapter.removeEpisodeNumber(it) }
        } else {
            ep.number
        } ?: ""

        when (holder) {
            is EpisodeListViewHolder -> {
                val binding = holder.binding
                setAnimation(fragment.requireContext(), holder.binding.root)

                val thumb =
                    ep.thumb?.let { if (it.url.isNotEmpty()) GlideUrl(it.url) { it.headers } else null }
                Glide.with(binding.itemMediaImage).load(thumb ?: media.cover).override(400, 0)
                    .into(binding.itemMediaImage)
                binding.itemEpisodeNumber.text = ep.number
                binding.itemEpisodeTitle.text = if (ep.number == title) "Episode $title" else title

                // Show browser button if it's a dynamic parser with HttpSource
                setupEpisodeBrowserButton(binding.itemEpisodeBrowser)

                if (ep.filler) {
                    binding.itemEpisodeFiller.visibility = View.VISIBLE
                    binding.itemEpisodeFillerView.visibility = View.VISIBLE
                } else {
                    binding.itemEpisodeFiller.visibility = View.GONE
                    binding.itemEpisodeFillerView.visibility = View.GONE
                }
                binding.itemEpisodeDesc.isVisible = !ep.desc.isNullOrBlank()
                binding.itemEpisodeDesc.text = ep.desc ?: ""
                holder.bind(ep.number, ep.downloadProgress, ep.desc)

                // Watched episodes are dimmed; no eye icon, the dim says it.
                binding.itemEpisodeViewed.visibility = View.GONE
                binding.itemEpisodeViewedCover.isVisible = isWatched(ep)
                holder.itemView.bindSelectionTint(ep.number in selection, animate = false)

                handleProgress(
                    binding.itemMediaProgressCont,
                    binding.itemMediaProgress,
                    binding.itemMediaProgressEmpty,
                    media.id,
                    ep.number
                )
            }

            is EpisodeGridViewHolder -> {
                val binding = holder.binding
                setAnimation(fragment.requireContext(), holder.binding.root)

                val thumb =
                    ep.thumb?.let { if (it.url.isNotEmpty()) GlideUrl(it.url) { it.headers } else null }
                Glide.with(binding.itemMediaImage).load(thumb ?: media.cover).override(400, 0)
                    .into(binding.itemMediaImage)

                binding.itemEpisodeNumber.text = ep.number
                binding.itemEpisodeTitle.text = title

                // Show browser button if it's a dynamic parser with HttpSource
                setupEpisodeBrowserButton(binding.itemEpisodeBrowser)

                if (ep.filler) {
                    binding.itemEpisodeFiller.visibility = View.VISIBLE
                    binding.itemEpisodeFillerView.visibility = View.VISIBLE
                } else {
                    binding.itemEpisodeFiller.visibility = View.GONE
                    binding.itemEpisodeFillerView.visibility = View.GONE
                }
                // Watched episodes are dimmed; no eye icon, the dim says it.
                binding.itemEpisodeViewed.visibility = View.GONE
                binding.itemEpisodeViewedCover.isVisible = isWatched(ep)
                holder.itemView.bindSelectionTint(ep.number in selection, animate = false)
                handleProgress(
                    binding.itemMediaProgressCont,
                    binding.itemMediaProgress,
                    binding.itemMediaProgressEmpty,
                    media.id,
                    ep.number
                )
            }

            is EpisodeCompactViewHolder -> {
                val binding = holder.binding
                setAnimation(fragment.requireContext(), holder.binding.root)
                binding.itemEpisodeNumber.text = ep.number
                binding.itemEpisodeFillerView.isVisible = ep.filler

                // Show browser button if it's a dynamic parser with HttpSource
                setupEpisodeBrowserButton(binding.itemEpisodeBrowser)

                binding.itemEpisodeViewedCover.isVisible = isWatched(ep)
                holder.itemView.bindSelectionTint(ep.number in selection, animate = false)
                handleProgress(
                    binding.itemMediaProgressCont,
                    binding.itemMediaProgress,
                    binding.itemMediaProgressEmpty,
                    media.id,
                    ep.number
                )
            }
        }
    }

    private fun isWatched(ep: Episode): Boolean =
        media.userProgress != null && (ep.number.toFloatOrNull() ?: 9999f) <= media.userProgress!!.toFloat()

    override fun getItemCount(): Int = arr.size
    private val downloadedEpisodes = mutableSetOf<String>()

    /** Last reported percentage per downloading episode; the ring spins until there is one. */
    private val downloadPercent = mutableMapOf<String, Int>()

    /** Episodes whose download just completed: their icon pops in once on the next bind. */
    private val justDownloaded = mutableSetOf<String>()

    private fun notifyDownloadState(episodeNumber: String) {
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
    }

    fun startDownload(episodeNumber: String) {
        if (downloadedEpisodes.contains(episodeNumber) ||
            AnimeDownloader.isDownloading(media.id, episodeNumber))
                return
        AnimeDownloader.startDownload(media.id, episodeNumber)
        // Find the position of the chapter and notify only that item
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = ""
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    @OptIn(UnstableApi::class)
    fun addToDownloadedEpisodes(episodeNumber: String, size: Double) {
        if (AnimeDownloader.isDownloading(media.id, episodeNumber)) justDownloaded.add(episodeNumber)
        downloadPercent.remove(episodeNumber)
        AnimeDownloader.stopDownload(media.id, episodeNumber)
        downloadedEpisodes.add(episodeNumber)
        // Find the position of the chapter and notify only that item
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = "Downloaded" + ": (${"%.1f".format(size)} MB)"
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    fun deleteDownload(episodeNumber: String) {
        downloadedEpisodes.remove(episodeNumber)
        downloadPercent.remove(episodeNumber)
        // Find the position of the chapter and notify only that item
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = null
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    fun purgeDownload(episodeNumber: String) {
        AnimeDownloader.stopDownload(media.id, episodeNumber)
        downloadPercent.remove(episodeNumber)
        downloadedEpisodes.remove(episodeNumber)
        // Find the position of the chapter and notify only that item
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = "Failed"
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    fun updateDownloadProgress(episodeNumber: String, progress: Int, stats: String = "") {
        downloadPercent[episodeNumber] = progress
        // Find the position of the chapter and notify only that item
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress =
                if (stats.isEmpty()) "Downloading: $progress%"
                else "Downloading: $progress% · $stats"
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }


    inner class EpisodeCompactViewHolder(val binding: ItemEpisodeCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            bindEpisodeClicks(this)

            binding.itemEpisodeBrowser.setOnClickListener {
                if (bindingAdapterPosition < arr.size && bindingAdapterPosition >= 0) {
                    fragment.openEpisodeInBrowser(arr[bindingAdapterPosition])
                }
            }
        }
    }

    inner class EpisodeGridViewHolder(val binding: ItemEpisodeGridBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            bindEpisodeClicks(this)

            binding.itemEpisodeBrowser.setOnClickListener {
                if (bindingAdapterPosition < arr.size && bindingAdapterPosition >= 0) {
                    fragment.openEpisodeInBrowser(arr[bindingAdapterPosition])
                }
            }
        }
    }

    inner class EpisodeListViewHolder(val binding: ItemEpisodeListBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            bindEpisodeClicks(this)
            binding.itemDownload.setOnClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    val episodeNumber = arr[bindingAdapterPosition].number
                    if(AnimeDownloader.isDownloading(media.id, episodeNumber)){
                        fragment.onAnimeEpisodeStopDownloadClick(episodeNumber)
                        return@setOnClickListener
                    } else if (downloadedEpisodes.contains(episodeNumber)) {
                        binding.root.context.customAlertDialog().apply {
                            setTitle(context.getString(R.string.delete_episode))
                            setMessage(context.getString(R.string.are_you_sure_delete_episode, episodeNumber))
                            setPosButton(R.string.yes) {
                                fragment.onAnimeEpisodeRemoveDownloadClick(episodeNumber)
                            }
                            setNegButton(R.string.no)
                        }.show()
                        return@setOnClickListener
                    } else {
                        fragment.onAnimeEpisodesDownload(arrayListOf(episodeNumber))
                    }
                }
            }
            binding.itemDownload.setOnLongClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    val episodeNumber = arr[bindingAdapterPosition].number
                    if (downloadedEpisodes.contains(episodeNumber)) {
                        //fragment.fixDownload(episodeNumber)
                        fragment.requireContext().customAlertDialog().apply {
                            setTitle("Multi Episode Deleter")
                            setMessage("Enter the number of episodes to delete")
                            val input = NumberPicker(currContext())
                            input.minValue = 1
                            input.maxValue = itemCount - bindingAdapterPosition
                            input.value = 1
                            setCustomView(input)
                            setPosButton(R.string.ok) {
                                binding.root.context.customAlertDialog().apply {
                                    setTitle("Delete Episodes")
                                    setMessage("Are you sure you want to delete Episodes $episodeNumber -> ${arr[bindingAdapterPosition + input.value - 1].number}?")
                                    setPosButton(R.string.yes) {
                                        fragment.multiDelete(episodeNumber, input.value)
                                    }
                                    setNegButton(R.string.no)
                                }.show()
                            }
                            setNegButton(R.string.cancel)
                            show()
                        }
                    }
                    else {
                        fragment.requireContext().customAlertDialog().apply {
                            setTitle("Multi Episode Downloader")
                            setMessage("Enter the number of episodes to download")
                            val input = NumberPicker(currContext())
                            input.minValue = 1
                            input.maxValue = itemCount - bindingAdapterPosition
                            input.value = 1
                            setCustomView(input)
                            setPosButton(R.string.ok) {
                                fragment.multiDownload(episodeNumber, input.value)
                            }
                            setNegButton(R.string.cancel)
                            show()
                        }
                    }
                }

                true
            }
            binding.itemEpisodeDesc.setOnClickListener {
                SectionCard.animateHeightChange(binding.itemEpisodeDesc) {
                    if (binding.itemEpisodeDesc.maxLines == 3)
                        binding.itemEpisodeDesc.maxLines = 100
                    else
                        binding.itemEpisodeDesc.maxLines = 3
                }
            }

            binding.itemEpisodeBrowser.setOnClickListener {
                if (bindingAdapterPosition < arr.size && bindingAdapterPosition >= 0) {
                    fragment.openEpisodeInBrowser(arr[bindingAdapterPosition])
                }
            }
        }

        fun bind(episodeNumber: String, progress: String?, desc: String?) {
            // Nothing useful to do with a download button while looking at the Downloaded
            // source itself - every episode shown here is already downloaded, and there's no
            // network to fetch a new one anyway.
            binding.itemDownload.isVisible = !offlineMode
            // The synopsis and the download status are two separate lines in this row, not
            // alternatives - showing "Downloaded: (12.3 MB)" is no reason to hide a synopsis
            // that's actually there.
            binding.itemEpisodeDesc.visibility =
                if (!desc.isNullOrBlank()) View.VISIBLE else View.GONE
            if (progress != null) {
                if(progress == "")
                    binding.itemDownloadStatus.visibility = View.GONE
                else
                    binding.itemDownloadStatus.visibility = View.VISIBLE
                binding.itemDownloadStatus.text = progress
            } else {
                binding.itemDownloadStatus.visibility = View.GONE
                binding.itemDownloadStatus.text = ""
            }
            val ring = binding.itemDownloadProgress
            if (AnimeDownloader.isDownloading(media.id, episodeNumber)) {
                // The ring stands in for the icon; the button stays under it to stop the download.
                binding.itemDownload.alpha = 0f
                val percent = downloadPercent[episodeNumber]
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
            binding.itemDownload.rotation = 0f
            binding.itemDownload.tag = episodeNumber
            if (downloadedEpisodes.contains(episodeNumber)) {
                if (justDownloaded.remove(episodeNumber)) {
                    // A download that just completed: pop in a check, then settle on delete.
                    binding.itemDownload.setImageResource(R.drawable.ic_circle_check)
                    binding.itemDownload.popIn()
                    binding.itemDownload.postDelayed({
                        // The row may have been recycled for another episode meanwhile.
                        if (binding.itemDownload.tag == episodeNumber &&
                            downloadedEpisodes.contains(episodeNumber)
                        ) binding.itemDownload.setImageResource(R.drawable.ic_round_delete_24)
                    }, 1000)
                } else {
                    // Already on disk when the list loaded: straight to delete.
                    binding.itemDownload.setImageResource(R.drawable.ic_round_delete_24)
                }
            } else {
                binding.itemDownload.setImageResource(R.drawable.ic_download_24)
            }
        }
    }

    fun updateType(t: Int) {
        type = t
    }

    private fun bytesToHuman(bytes: Long): String? {
        if (bytes < 0) return null
        val unit = 1000
        if (bytes < unit) return "$bytes B"
        val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
        val pre = ("KMGTPE")[exp - 1]
        return String.format("%.1f %sB", bytes / unit.toDouble().pow(exp.toDouble()), pre)
    }

    private fun setupEpisodeBrowserButton(button: View) {
        // There's nothing to open in a browser for the Downloaded source - offlineMode is the
        // direct signal for that, rather than re-deriving it from the parser type below (which
        // depends on media.selected!!.sourceIndex already pointing at the right entry).
        if (offlineMode) {
            button.visibility = View.GONE
            return
        }
        val parser = watchSources[media.selected!!.sourceIndex]
        if (parser is DynamicAnimeParser) {
            val httpSource = parser.extension.sources.getOrNull(parser.sourceLanguage) as? AnimeHttpSource
            if (httpSource != null) {
                button.visibility = View.VISIBLE
            } else {
                button.visibility = View.GONE
            }
        } else {
            button.visibility = View.GONE
        }
    }
}


