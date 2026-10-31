package ani.dantotsu.media.anime

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.DialogInterface
import android.content.pm.ActivityInfo
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
//import androidx.compose.ui.test.performClick
//import androidx.compose.ui.geometry.isEmpty
//import androidx.compose.ui.semantics.text
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
//import androidx.glance.visibility
//import androidx.glance.visibility
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withCreated
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.addons.torrent.TorrentAddonManager
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.copyToClipboard
import ani.dantotsu.currActivity
import ani.dantotsu.currContext
import ani.dantotsu.databinding.BottomSheetSelectorBinding
import ani.dantotsu.databinding.ItemStreamBinding
import ani.dantotsu.databinding.ItemUrlBinding
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.video.Helper
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBars
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.SubtitleDownloader
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.Download.download
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.SettingsSourcesActivity
import ani.dantotsu.settings.sourcesSettingsIntent
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.toast
import ani.dantotsu.tryWith
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DecimalFormat
import java.util.Locale


class SelectorDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSelectorBinding? = null
    private val binding get() = _binding!!
    val model: MediaDetailsViewModel by activityViewModels()
    private var scope: CoroutineScope = lifecycleScope
    private var media: Media? = null
    private var episode: Episode? = null
    private var prevEpisode: String? = null
    private var makeDefault = false
    private var selected: String? = null
    private var launch: Boolean? = null
    private var isDownloadMenu: Boolean? = null
    private var episodes: ArrayList<String>? = null

    /**
     * TV mode's list of servers: the same streams the phone list shows, as they arrive, drawn as rows
     * a remote can move through (see [TvServerList]). The phone list's rows are small and, arriving
     * after the sheet had tried to focus its list, left a remote nothing to press. Downloads keep the
     * phone list — TV mode doesn't download yet.
     */
    private val tvRows = androidx.compose.runtime.mutableStateListOf<Pair<VideoExtractor, Int>>()
    private var tvLoading by androidx.compose.runtime.mutableStateOf(true)
    /** The server being picked on its own (the remembered default), with the way to stop it. */
    private var tvAuto by androidx.compose.runtime.mutableStateOf<String?>(null)
    private var tvCancelAuto: (() -> Unit)? = null
    private var tvMakeDefault by androidx.compose.runtime.mutableStateOf(false)
    private val tvList: Boolean
        get() = isDownloadMenu != true && context?.let { ani.dantotsu.tv.TvMode.isOn(it) } == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            selected = it.getString("server")
            launch = it.getBoolean("launch", true)
            prevEpisode = it.getString("prev")
            isDownloadMenu = it.getBoolean("isDownload")
            episodes = it.getStringArrayList("episodes")
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSelectorBinding.inflate(inflater, container, false)
        val window = dialog?.window
        window?.statusBarColor = Color.TRANSPARENT
        window?.navigationBarColor =
            requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)
        // TV mode: the panel is the sheet's only content. The phone layout is still inflated, for
        // the code above to drive as it always has, but never attached: shown, it surfaced again
        // whenever that code made one of its parts visible.
        return if (tvList) tvSheetView() else binding.root
    }

    interface EpisodeDownloadListener {
        fun onFinishingUserSelection(selectedServerName: String,
                                     selectedSubtitles: MutableList<String>,
                                     selectedAudioTracks: MutableList<String>)
    }
    class EpisodeDownloadHandler(private val _onFinishingUserSelection: (String, MutableList<String>, MutableList<String>) -> Unit)
        : EpisodeDownloadListener{
        override fun onFinishingUserSelection(selectedServerName: String,
                                              selectedSubtitles: MutableList<String>,
                                              selectedAudioTracks: MutableList<String>) {
            _onFinishingUserSelection(selectedServerName, selectedSubtitles, selectedAudioTracks)
        }
    }

    /**
     * Pins the screen's orientation for as long as this dialog is up.
     *
     * Extracting a server's video runs on [scope], which is this fragment's own lifecycle scope,
     * and the details screen declares no `configChanges` — so rotating destroys the activity,
     * cancels the extraction mid-flight and discards the transient extractor data the player is
     * about to be handed through `ExoplayerView.media`. The result is a player that opens with
     * nothing to play.
     *
     * The dialog is only up for a few seconds, so holding the orientation for its lifetime removes
     * the whole class of problem. Surviving the rotation properly would mean hoisting the
     * extraction into the view model and re-driving the launch after recreation, which is a much
     * larger change to this flow.
     */
    private fun lockOrientation(lock: Boolean) {
        activity?.requestedOrientation = if (lock) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        lockOrientation(true)
        var loaded = false
        model.getMedia().observe(viewLifecycleOwner) { m ->
            media = m
            if (media != null && !loaded) {
                loaded = true

                fun fail(resId: Int){
                    snackString(getString(resId))
                    tryWith {
                        dismiss()
                    }
                }
                fun initializeVideoServerSelector(ep: Episode, onEpisodeDownloadHandler: EpisodeDownloadHandler? = null) {
                    binding.selectorRecyclerView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        bottomMargin = navBarHeight
                    }
                    binding.selectorRecyclerView.adapter = null
                    binding.selectorProgressBar.visibility = View.VISIBLE
                    tvLoading = true
                    makeDefault = PrefManager.getVal(PrefName.MakeDefault)
                    tvMakeDefault = makeDefault
                    binding.selectorMakeDefault.isChecked = makeDefault
                    binding.selectorMakeDefault.setOnClickListener {
                        makeDefault = binding.selectorMakeDefault.isChecked
                        PrefManager.setVal(PrefName.MakeDefault, makeDefault)
                    }
                    binding.selectorRecyclerView.layoutManager =
                        LinearLayoutManager(
                            requireActivity(),
                            LinearLayoutManager.VERTICAL,
                            false
                        )
                    val adapter = ExtractorAdapter(onEpisodeDownloadHandler)
                    binding.selectorRecyclerView.adapter = adapter

                    if (!ep.allStreams) {
                        scope.launch(Dispatchers.IO) {
                            model.loadEpisodeVideos(ep, media!!.selected!!.sourceIndex)
                            withContext(Dispatchers.Main) {
                                adapter.addAll(ep.extractors)
                                binding.selectorProgressBar.visibility = View.GONE
                                tvLoading = false
                                if (adapter.itemCount == 0) {
                                    fail(R.string.stream_selection_empty)
                                }
                                if (model.watchSources!!.isDownloadedSource(media?.selected!!.sourceIndex)) {
                                    adapter.performClick(0)
                                }
                            }
                        }
                    } else {
                        media!!.anime?.episodes?.set(media!!.anime?.selectedEpisode!!, ep)
                        adapter.addAll(ep.extractors)
                        if (ep.extractors?.size == 0) {
                            fail(R.string.stream_selection_empty)
                        }
                        if (model.watchSources!!.isDownloadedSource(media?.selected!!.sourceIndex)) {
                            adapter.performClick(0)
                        }
                        binding.selectorProgressBar.visibility = View.GONE
                        tvLoading = false
                    }
                }
                suspend fun loadEpisodeSingleServer(episodeName: String, selectedServerName: String): Boolean{
                    media?.anime?.selectedEpisode = episodeName
                    val ep = media?.anime?.episodes?.get(media?.anime?.selectedEpisode)!!
                    episode = ep

                    var success = false
                    scope.launch(Dispatchers.IO) {
                        success = model.loadEpisodeSingleVideo(
                            ep,
                            media!!.selected!!,
                            selectedServerName = selectedServerName
                        )
                    }.join()
                    Log.d("AnimeDownloader", "Loading Episode Server State: $success")
                    return success
                }
                fun startEpisodeDownload(episodeName: String, selectedServerName: String,
                                         selectedSubtitles: MutableList<String>,
                                         selectedAudioTracks: MutableList<String>){
                    fun downloadUsingSingleServer(extractor: VideoExtractor): Boolean {
                        val episode =
                            media!!.anime!!.episodes!![media!!.anime!!.selectedEpisode!!]!!
                        media!!.anime!!.episodes!![media!!.anime!!.selectedEpisode!!]!!.selectedExtractor =
                            extractor.server.name
                        media!!.anime!!.episodes!![media!!.anime!!.selectedEpisode!!]!!.selectedVideo =
                            episode.selectedVideo
                        if ((PrefManager.getVal(PrefName.DownloadManager) as Int) != 0) {
                            download(
                                requireActivity(),
                                media!!.anime!!.episodes!![media!!.anime!!.selectedEpisode!!]!!,
                                media!!.userPreferredName
                            )
                        }
                        else {
                            val downloadAddonManager: DownloadAddonManager = Injekt.get()
                            if (!downloadAddonManager.isAvailable()) {
                                val context = currContext() ?: requireContext()
                                context.customAlertDialog().apply {
                                    setTitle(R.string.download_addon_not_installed)
                                    setMessage(R.string.would_you_like_to_install)
                                    setPosButton(R.string.yes) {
                                        ContextCompat.startActivity(
                                            context,
                                            sourcesSettingsIntent(
                                                context,
                                                SettingsSourcesActivity.Section.ADDONS
                                            ),
                                            null
                                        )
                                    }
                                    setNegButton(R.string.no) {
                                        return@setNegButton
                                    }
                                    show()
                                }
                                return false
                            }
                            val subtitles = extractor.subtitles
                            val subtitlesToDownload: MutableList<Pair<String, String>> = mutableListOf()
                            subtitles.forEach {
                                if(it.language in selectedSubtitles){
                                    subtitlesToDownload.add(Pair(it.file.url, it.language))
                                }
                            }

                            val audioTracks = extractor.audioTracks
                            val audioTracksToDownload: MutableList<Pair<String, String>> = mutableListOf()
                            audioTracks.forEach {
                                if(it.lang in selectedAudioTracks){
                                    audioTracksToDownload.add(Pair(it.url, it.lang))
                                }
                            }

                            val selectedVideo =
                                if (extractor.videos.size > episode.selectedVideo) extractor.videos[episode.selectedVideo] else null
                            val activity = currActivity() ?: requireActivity()
                            if (selectedVideo == null) {
                                snackString(R.string.no_video_selected)
                                return true
                            }
                            val url = selectedVideo.file.url
                            val isTorrent = url.startsWith("magnet:") || url.endsWith(".torrent")
                            val torrentExtension = Injekt.get<TorrentAddonManager>()
                            if (isTorrent && !torrentExtension.isAvailable()) {
                                toast(R.string.torrent_addon_not_available)
                                return false
                            }
                            // The activity's scope, not this dialog's: it is dismissed as soon as
                            // this returns, while the torrent add-on may still be answering.
                            ((activity as? LifecycleOwner) ?: requireActivity()).lifecycleScope.launch {
                                if (isTorrent) {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val extension =
                                                torrentExtension.extension!!.extension
                                            torrentExtension.torrentHash?.let {
                                                extension.removeTorrent(it)
                                            }
                                            val index = if (url.contains("index=")) {
                                                url.substringAfter("index=")
                                                    .toIntOrNull() ?: 0
                                            } else 0
                                            Logger.log("Sending: ${url}, ${selectedVideo.quality}, $index")
                                            val currentTorrent = extension.addTorrent(
                                                url,
                                                selectedVideo.quality.toString(),
                                                "",
                                                "",
                                                false
                                            )
                                            torrentExtension.torrentHash =
                                                currentTorrent.hash
                                            selectedVideo.file.url =
                                                extension.getLink(currentTorrent, index)
                                            Logger.log("Received: ${selectedVideo.file.url}")
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Injekt.get<CrashlyticsInterface>()
                                            .logException(e)
                                        Logger.log(e)
                                        toast("Error starting video: ${e.message}")
                                        // The url is still the magnet link; downloading it would fail.
                                        return@launch
                                    }
                                }
                                Helper.startAnimeDownloadService(
                                    activity,
                                    media!!.mainName(),
                                    episode.number,
                                    selectedVideo,
                                    subtitlesToDownload,
                                    audioTracksToDownload,
                                    media,
                                    episode.thumb?.url ?: media!!.banner
                                    ?: media!!.cover
                                )
                                val intent =
                                    Intent(AnimeWatchFragment.ACTION_DOWNLOAD_STARTED).apply {
                                        putExtra(
                                            AnimeWatchFragment.EXTRA_EPISODE_NUMBER,
                                            episode.number,
                                        )
                                        putExtra("mediaId", media?.id)
                                    }
                                activity.sendBroadcast(intent)
                            }
                        }
                        return true
                    }

                    media?.anime?.selectedEpisode = episodeName
                    val ep = media?.anime?.episodes?.get(episodeName)!!
                    episode = ep

                    Log.d("AnimeDownloader", "Downloading Episode: ${ep.number}, server: $selectedServerName")

                    if (ep.extractors?.find { it.server.name == selectedServerName } == null)
                        fail(R.string.auto_select_server_error)
                    else {
                        media!!.anime?.episodes?.set(media!!.anime?.selectedEpisode!!, ep)
                        val selectedExtractor =
                            ep.extractors?.find { it.server.name == selectedServerName }
                        if(!downloadUsingSingleServer(selectedExtractor!!))
                            fail(R.string.auto_select_server_error)
                    }
                }

                Log.d("AnimeDownloader", "Selected Server for watching: $selected")
                if(episodes.isNullOrEmpty()){
                    fail(R.string.empty_episodes_list)
                }
                if (isDownloadMenu == false) {
                    media?.anime?.selectedEpisode = episodes?.get(0)
                    val ep = media?.anime?.episodes?.get(media?.anime?.selectedEpisode)
                    episode = ep
                    if (ep != null) {
                        // A downloaded episode plays straight from disk: it needs no server
                        // list, no extractor round trip and nothing for the user to pick, so it
                        // wins over whatever the selected source would stream.
                        scope.launch {
                            val local = withContext(Dispatchers.IO) {
                                model.loadDownloadedEpisode(ep, media!!)
                            }
                            if (local) {
                                startExoplayer(media!!)
                                return@launch
                            }
                            if (selected != null) {
                                binding.selectorListContainer.visibility = View.GONE
                                binding.selectorAutoListContainer.visibility = View.VISIBLE
                                binding.selectorAutoText.text = selected
                                val cancelAuto: () -> Unit = {
                                    media!!.selected!!.server = null
                                    model.saveSelected(media!!.id, media!!.selected!!)
                                    tryWith {
                                        dismiss()
                                    }
                                }
                                binding.selectorCancel.setOnClickListener { cancelAuto() }
                                tvCancelAuto = cancelAuto
                                tvAuto = selected

                                fun load() {
                                    val size =
                                        if (model.watchSources!!.isDownloadedSource(media!!.selected!!.sourceIndex)) {
                                            ep.extractors?.firstOrNull()?.videos?.size
                                        } else {
                                            ep.extractors?.find { it.server.name == selected }?.videos?.size
                                        }

                                    if (size != null && size >= media!!.selected!!.video) {
                                        media!!.anime!!.episodes?.get(media!!.anime!!.selectedEpisode!!)?.selectedExtractor =
                                            selected
                                        media!!.anime!!.episodes?.get(media!!.anime!!.selectedEpisode!!)?.selectedVideo =
                                            media!!.selected!!.video
                                        startExoplayer(media!!)
                                    } else {
                                        prevEpisode = null
                                        fail(R.string.auto_select_server_error)
                                    }
                                }

                                if (ep.extractors?.find { it.server.name == selected } == null) {
                                    scope.launch{
                                        if(!withContext(Dispatchers.IO){
                                            loadEpisodeSingleServer(ep.number, selected!!)
                                        }){
                                            media!!.selected!!.server = null
                                            model.saveSelected(media!!.id, media!!.selected!!)
                                            prevEpisode = null
                                            fail(R.string.auto_select_server_error)
                                        }
                                        else load()
                                    }
                                } else load()
                            }
                            else
                                initializeVideoServerSelector(ep)
                        }
                    }
                }
                else {
                    binding.selectorMakeDefault.visibility = View.GONE
                    media?.anime?.selectedEpisode = episodes?.get(0)
                    val ep = media?.anime?.episodes?.get(media?.anime?.selectedEpisode)
                    episode = ep

                    if (ep != null) {
                        val downloadHandler =
                        EpisodeDownloadHandler(_onFinishingUserSelection = { selectedServerName,
                                                                             selectedSubtitles,
                                                                             selectedAudioTracks ->
                            binding.selectorListContainer.visibility = View.GONE
                            binding.selectorAutoListContainer.visibility = View.VISIBLE
                            binding.selectorTitle.text = "Starting Download"
                            binding.selectorAutoText.text =
                                "Starting download using server:\n$selectedServerName"
                            binding.selectorCancel.visibility = View.GONE

                            scope.launch(Dispatchers.IO) {
                                val serverSelectionScope = CoroutineScope(Dispatchers.IO)
                                val serverSelectionTasks = mutableListOf<Deferred<Unit>>()
                                for (episodeName in episodes!!.drop(1)) {
                                    serverSelectionTasks.add(serverSelectionScope.async {
                                        if(!loadEpisodeSingleServer(episodeName, selectedServerName)){
                                            Log.d("AnimeDownloader", "Error loading server $selectedServerName for episode $episodeName")
                                            fail(R.string.auto_select_server_error)
                                        }
                                    })
                                }
                                serverSelectionTasks.awaitAll()

                                for(episodeName in episodes!!){
                                    startEpisodeDownload(episodeName, selectedServerName, selectedSubtitles, selectedAudioTracks)
                                }
                                tryWith{
                                    dismiss()
                                }
                            }
                        })
                        initializeVideoServerSelector(ep, downloadHandler)
                    }
                }
            }
        }
      super.onViewCreated(view, savedInstanceState)
    }

    private val externalPlayerResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        Logger.log(result.data.toString())
    }

    private fun exportMagnetIntent(episode: Episode, video: Video): Intent {
        val amnis = "com.amnis"
        return Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(amnis, "$amnis.gui.player.PlayerActivity")
            data = Uri.parse(video.file.url)
            putExtra("title", "${media?.name} - ${episode.title}")
            putExtra("position", 0)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("secure_uri", true)
            val headersArray = arrayOf<String>()
            video.file.headers.forEach {
                headersArray.plus(arrayOf(it.key, it.value))
            }
            putExtra("headers", headersArray)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("UnsafeOptInUsageError")
    fun startExoplayer(media: Media) {
        prevEpisode = null

        episode?.let { ep ->
            val video = ep.extractors?.find {
                it.server.name == ep.selectedExtractor
            }?.videos?.getOrNull(ep.selectedVideo)
            video?.file?.url?.let { url ->
                if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
                    val torrentExtension = Injekt.get<TorrentAddonManager>()
                    if (torrentExtension.isAvailable()) {
                        val activity = currActivity() ?: requireActivity()
                        launchIO {
                            try {
                                val extension = torrentExtension.extension!!.extension
                                torrentExtension.torrentHash?.let {
                                    extension.removeTorrent(it)
                                }
                                val index = if (url.contains("index=")) {
                                    url.substringAfter("index=").toIntOrNull() ?: 0
                                } else 0
                                Logger.log("Sending: ${url}, ${video.quality}, $index")
                                val currentTorrent = extension.addTorrent(
                                    url, video.quality.toString(), "", "", false
                                )
                                torrentExtension.torrentHash = currentTorrent.hash
                                video.file.url = extension.getLink(currentTorrent, index)
                                Logger.log("Received: ${video.file.url}")
                                if (launch == true) {
                                    Intent(activity, ExoplayerView::class.java).apply {
                                        ExoplayerView.media = media
                                        ExoplayerView.initialized = true
                                        startActivity(this)
                                    }
                                } else {
                                    model.setEpisode(
                                        media.anime!!.episodes!![media.anime.selectedEpisode!!]!!,
                                        "startExo no launch"
                                    )
                                }
                                dismiss()
                            } catch (e: Exception) {
                                Injekt.get<CrashlyticsInterface>().logException(e)
                                Logger.log(e)
                                toast("Error starting video: ${e.message}")
                                dismiss()
                            }
                        }
                    } else {
                        try {
                            externalPlayerResult.launch(exportMagnetIntent(ep, video))
                        } catch (e: ActivityNotFoundException) {
                            val amnis = "com.amnis"
                            try {
                                startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("market://details?id=$amnis")
                                    )
                                )
                                dismiss()
                            } catch (e: ActivityNotFoundException) {
                                startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://play.google.com/store/apps/details?id=$amnis")
                                    )
                                )
                            }
                        }
                    }
                    return
                }
            }
        }

        dismiss()
        if (launch!!) {
            stopAddingToList()
            val intent = Intent(activity, ExoplayerView::class.java)
            ExoplayerView.media = media
            ExoplayerView.initialized = true
            startActivity(intent)
        } else {
            model.setEpisode(
                media.anime!!.episodes!![media.anime.selectedEpisode!!]!!,
                "startExo no launch"
            )
        }
    }

    private fun stopAddingToList() {
        episode?.extractorCallback = null
        episode?.also {
            it.extractors = it.extractors?.toMutableList()
        }
    }

    private inner class ExtractorAdapter(private val onEpisodeDownloadHandler: EpisodeDownloadHandler? = null) :
        RecyclerView.Adapter<ExtractorAdapter.StreamViewHolder>() {
        val links = mutableListOf<VideoExtractor>()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreamViewHolder =
            StreamViewHolder(
                ItemStreamBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

        override fun onBindViewHolder(holder: StreamViewHolder, position: Int) {
            val extractor = links.getOrNull(position) as? VideoExtractor
            if (extractor == null) {
                holder.binding.streamName.visibility = View.GONE
                holder.binding.streamRecyclerView.adapter = null
                holder.itemView.visibility = View.GONE
                return
            }
            holder.itemView.visibility = View.VISIBLE
            holder.binding.streamName.text = ""//extractor.server.name
            holder.binding.streamName.visibility = View.GONE

            holder.binding.streamRecyclerView.layoutManager = LinearLayoutManager(requireContext())
            holder.binding.streamRecyclerView.adapter = VideoAdapter(extractor, onEpisodeDownloadHandler)
        }

        override fun getItemCount(): Int = links.size

        fun add(videoExtractor: VideoExtractor) {
            if (videoExtractor.videos.isNotEmpty()) {
                links.add(videoExtractor)
                notifyItemInserted(links.size - 1)
                if (tvList) tvRows.addAll(videoExtractor.videos.indices.map { videoExtractor to it })
            }
        }

        fun addAll(extractors: List<VideoExtractor>?) {
            val nonNull = extractors?.filterNotNull() ?: return
            val start = links.size
            links.addAll(nonNull)
            notifyItemRangeInserted(start, nonNull.size)
            if (tvList) nonNull.forEach { e -> tvRows.addAll(e.videos.indices.map { e to it }) }
        }

        fun performClick(position: Int) {
            try {
                val extractor = links.getOrNull(position) as? VideoExtractor ?: return
                media!!.anime!!.episodes!![media!!.anime!!.selectedEpisode!!]?.selectedExtractor =
                    extractor.server.name
                media!!.anime!!.episodes!![media!!.anime!!.selectedEpisode!!]?.selectedVideo = 0
                startExoplayer(media!!)
            } catch (e: Exception) {
                Injekt.get<CrashlyticsInterface>().logException(e)
            }
        }

        private inner class StreamViewHolder(val binding: ItemStreamBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    /** "1080p • Dub": a stream's quality and whether it's subbed or dubbed, as its row shows it. */
    private fun videoDetails(extractor: VideoExtractor, video: Video): String {
        val serverLabel = extractor.server.name.lowercase(Locale.ROOT)
        val subDub = when {
            Regex("\\bsoft[ -]?(sub|subbed)\\b").containsMatchIn(serverLabel) -> "Soft Sub"
            Regex("\\bhard[ -]?(sub|subbed)\\b").containsMatchIn(serverLabel) -> "Hard Sub"
            Regex("\\b(dub|dubbed)\\b").containsMatchIn(serverLabel) -> "Dub"
            Regex("\\b(sub|subbed)\\b").containsMatchIn(serverLabel) -> "Sub"
            else -> media?.selected?.sourceIndex?.let { sourceIndex ->
                model.watchSources?.get(sourceIndex)?.let { parser ->
                    if (parser.isDubAvailableSeparately()) {
                        if (parser.selectDub) "Dub" else "Sub"
                    } else null
                }
            }
        }
        // Some extensions put a server number where the height goes (Re:ANIME's 1 and 2, read as
        // "1p"): anything under the smallest real resolution isn't one, and says nothing.
        val resolution = video.quality?.takeIf { it >= 144 }?.let { "${it}p" }
        return listOf(resolution, subDub).filterNotNull().distinct().joinToString(" • ")
    }

    /** What picking a stream does, from a phone row or a TV one: play it, remembered if asked to. */
    private fun pickVideo(extractor: VideoExtractor, position: Int) {
        tryWith(true) {
            media!!.anime!!.episodes!![media!!.anime!!.selectedEpisode!!]?.selectedExtractor =
                extractor.server.name
            media!!.anime!!.episodes!![media!!.anime!!.selectedEpisode!!]?.selectedVideo = position
            if (makeDefault) {
                media!!.selected!!.server = extractor.server.name
                media!!.selected!!.video = position
                model.saveSelected(media!!.id, media!!.selected!!)
            }
            startExoplayer(media!!)
        }
    }

    /**
     * TV mode's sheet (see [onCreateView]): the servers as [tvRows] arrive, a spinner until they have,
     * the default switch, or the remembered server being picked on its own ([ani.dantotsu.tv.TvServerSheet]).
     */
    private fun tvSheetView(): View = androidx.compose.ui.platform.ComposeView(requireContext()).apply {
        // As tall as what it holds: handed over without a size, the sheet gave it the whole screen.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tvRows.clear()
        setContent {
            ani.dantotsu.tv.TvTheme(rateLimit = false, fill = false) {
                ani.dantotsu.tv.TvServerSheet(
                    rows = tvRows,
                    loading = tvLoading,
                    autoServer = tvAuto,
                    onCancelAuto = { tvCancelAuto?.invoke() },
                    makeDefault = tvMakeDefault,
                    onMakeDefault = {
                        makeDefault = it
                        tvMakeDefault = it
                        PrefManager.setVal(PrefName.MakeDefault, it)
                    },
                    name = { it.first.server.name },
                    details = { (extractor, i) -> videoDetails(extractor, extractor.videos[i]) },
                    onPick = { (extractor, i) -> pickVideo(extractor, i) },
                )
            }
        }
    }

    private inner class VideoAdapter(private val extractor: VideoExtractor,private val onEpisodeDownloadHandler: EpisodeDownloadHandler?) :
        RecyclerView.Adapter<VideoAdapter.UrlViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UrlViewHolder {
            return UrlViewHolder(
                ItemUrlBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }

        override fun onBindViewHolder(holder: UrlViewHolder, position: Int) {
            val binding = holder.binding
            val video = extractor.videos[position]
            if (isDownloadMenu == true) {
                binding.urlDownload.visibility = View.VISIBLE
            } else {
                binding.urlDownload.visibility = View.GONE
            }
            val subtitles = extractor.subtitles
            if (subtitles.isNotEmpty()) {
                binding.urlSub.visibility = View.VISIBLE
            } else {
                binding.urlSub.visibility = View.GONE
            }
            binding.urlSub.setOnClickListener {
                if (subtitles.isNotEmpty()) {
                    val subtitleNames = subtitles.map { it.language }
                    var subtitleToDownload: Subtitle? = null
                    requireActivity().customAlertDialog().apply {
                        setTitle(R.string.download_subtitle)
                        singleChoiceItems(subtitleNames.toTypedArray()) { which ->
                            subtitleToDownload = subtitles[which]
                        }
                        setPosButton(R.string.download) {
                            scope.launch {
                                if (subtitleToDownload != null) {
                                    SubtitleDownloader.downloadSubtitle(
                                        requireContext(),
                                        subtitleToDownload!!.file.url,
                                        DownloadedType(
                                            media!!.mainName(),
                                            media!!.anime!!.episodes!![media!!.anime!!.selectedEpisode!!]!!.number,
                                            MediaType.ANIME
                                        )
                                    )
                                }
                            }
                        }
                        setNegButton(R.string.cancel) {}
                    }.show()
                } else {
                    snackString(R.string.no_subtitles_available)
                }
            }
            binding.urlDownload.setSafeOnClickListener {
                media!!.anime!!.episodes!![media!!.anime!!.selectedEpisode!!]!!.selectedExtractor =
                    extractor.server.name
                media!!.anime!!.episodes!![media!!.anime!!.selectedEpisode!!]!!.selectedVideo =
                    position
                if ((PrefManager.getVal(PrefName.DownloadManager) as Int) != 0) {
                    download(
                        requireActivity(),
                        media!!.anime!!.episodes!![media!!.anime!!.selectedEpisode!!]!!,
                        media!!.userPreferredName
                    )
                }
                else {
                    val episode =
                        media!!.anime!!.episodes!![media!!.anime!!.selectedEpisode!!]!!
                    val selectedVideo =
                        if (extractor.videos.size > episode.selectedVideo) extractor.videos[episode.selectedVideo] else null
                    val downloadAddonManager: DownloadAddonManager = Injekt.get()
                    if (!downloadAddonManager.isAvailable()) {
                        val context = currContext() ?: requireContext()
                        context.customAlertDialog().apply {
                            setTitle(R.string.download_addon_not_installed)
                            setMessage(R.string.would_you_like_to_install)
                            setPosButton(R.string.yes) {
                                ContextCompat.startActivity(
                                    context,
                                    sourcesSettingsIntent(context, SettingsSourcesActivity.Section.ADDONS),
                                    null
                                )
                            }
                            setNegButton(R.string.no) {
                                return@setNegButton
                            }
                            show()
                        }
                        dismiss()
                        return@setSafeOnClickListener
                    }
                    selectedVideo?.file?.url?.let { url ->
                        if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
                            val torrentExtension = Injekt.get<TorrentAddonManager>()
                            if (!torrentExtension.isAvailable()) {
                                toast(R.string.torrent_addon_not_available)
                                return@setSafeOnClickListener
                            }
                        }
                    }
                    
                    val subtitleNames = subtitles.map { it.language }
                    var selectedSubtitles: MutableList<String> = mutableListOf()
                    var selectedAudioTracks: MutableList<String> = mutableListOf()

                    val currContext = currContext() ?: requireContext()
                    
                    fun go(){
                        onEpisodeDownloadHandler?.onFinishingUserSelection(extractor.server.name, selectedSubtitles, selectedAudioTracks)
                    }
                    
                    fun checkAudioTracks() {
                        val audioTracks = extractor.audioTracks.map { it.lang }
                        if (audioTracks.isNotEmpty()) {
                            val audioNamesArray = audioTracks.toTypedArray()
                            val checkedItems = BooleanArray(audioNamesArray.size) { false }

                            currContext.customAlertDialog().apply { // ToTest
                                setTitle(R.string.download_audio_tracks)
                                multiChoiceItems(audioNamesArray, checkedItems) {
                                    it.forEachIndexed { index, isChecked ->
                                        val audioName = extractor.audioTracks[index].lang
                                        if (isChecked) {
                                            selectedAudioTracks.add(audioName)
                                        } else {
                                            selectedAudioTracks.remove(audioName)
                                        }
                                    }
                                }
                                setPosButton(R.string.download) {
                                    go()
                                }
                                setNegButton(R.string.skip) {
                                    selectedAudioTracks = mutableListOf()
                                    go()
                                }
                                setNeutralButton(R.string.cancel) {
                                    selectedAudioTracks = mutableListOf()
                                }
                                show()
                            }
                        } else {
                            go()
                        }
                    }
                    if (subtitles.isNotEmpty()) { // ToTest
                        val subtitleNamesArray = subtitleNames.toTypedArray()
                        val checkedItems = BooleanArray(subtitleNamesArray.size) { false }

                        currContext.customAlertDialog().apply {
                            setTitle(R.string.download_subtitle)
                            multiChoiceItems(subtitleNamesArray, checkedItems) {
                                it.forEachIndexed { index, isChecked ->
                                    val subtitleName = subtitles[index].language
                                    if (isChecked) {
                                        selectedSubtitles.add(subtitleName)
                                    } else {
                                        selectedSubtitles.remove(subtitleName)
                                    }
                                }
                            }
                            setPosButton(R.string.download) {
                                checkAudioTracks()
                            }
                            setNegButton(R.string.skip) {
                                selectedSubtitles = mutableListOf()
                                checkAudioTracks()
                            }
                            setNeutralButton(R.string.cancel) {
                                selectedSubtitles = mutableListOf()
                            }
                            show()
                        }
                    } else {
                        checkAudioTracks()
                    }
                }
            }
            if (video.format == VideoType.CONTAINER) {
                binding.urlSize.isVisible = video.size != null
                // if video size is null or 0, show "Unknown Size" else show the size in MB
                val sizeText = getString(
                    R.string.mb_size, "${if (video.extraNote != null) " : " else ""}${
                        if (video.size == 0.0) getString(R.string.size_unknown) else DecimalFormat("#.##").format(
                            video.size ?: 0
                        )
                    }"
                )
                binding.urlSize.text = sizeText
            }
            val serverName = extractor.server.name
            binding.urlQuality.text = serverName
            val details = videoDetails(extractor, video)
            binding.urlNote.text = details
            binding.urlNote.visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
        }

        override fun getItemCount(): Int = extractor.videos.size

        private inner class UrlViewHolder(val binding: ItemUrlBinding) :
            RecyclerView.ViewHolder(binding.root) {
            init {
                itemView.setSafeOnClickListener {
                    if (isDownloadMenu == true) {
                        binding.urlDownload.performClick()
                        return@setSafeOnClickListener
                    }
                    Log.d("AnimeDownloader", "Should start the player")
                    pickVideo(extractor, bindingAdapterPosition)
                }
                itemView.setOnLongClickListener {
                    val video = extractor.videos[bindingAdapterPosition]
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(video.file.url), "video/*")
                    }
                    copyToClipboard(video.file.url, true)
                    dismiss()
                    startActivity(Intent.createChooser(intent, "Open Video in :"))
                    true
                }
            }
        }
    }

    companion object {

        fun newInstance(
            server: String? = null,
            la: Boolean = true,
            prev: String? = null,
            isDownload: Boolean,
            episodes: ArrayList<String>
        ): SelectorDialogFragment =
            SelectorDialogFragment().apply {
                arguments = Bundle().apply {
                    putString("server", server)
                    putBoolean("launch", la)
                    putString("prev", prev)
                    putBoolean("isDownload", isDownload)
                    putStringArrayList("episodes", episodes)
                }
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {}

    override fun onDismiss(dialog: DialogInterface) {
        // The launched player manages its own orientation, so hand control back either way.
        lockOrientation(false)
        if (launch == false) {
            activity?.hideSystemBars()
            model.epChanged.postValue(true)
            if (prevEpisode != null) {
                media?.anime?.selectedEpisode = prevEpisode
                model.setEpisode(media?.anime?.episodes?.get(prevEpisode) ?: return, "prevEp")
            }
        }
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
