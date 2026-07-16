package ani.dantotsu.media.manga

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.CheckBox
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.ContextCompat
import androidx.core.math.MathUtils.clamp
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.connections.handoff.HandoffLoadingOverlay
import ani.dantotsu.connections.handoff.HandoffNavigator
import ani.dantotsu.databinding.FragmentMediaSourceBinding
import ani.dantotsu.downloadStats
import ani.dantotsu.formatBytes
import ani.dantotsu.download.DownloadItem
import ani.dantotsu.download.DownloadTracker
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.DownloadsManager.Companion.compareName
import ani.dantotsu.download.manga.MangaDownloaderService
import ani.dantotsu.download.manga.MangaServiceDataSingleton
import ani.dantotsu.dp
import ani.dantotsu.isOnMeteredNetwork
import ani.dantotsu.isOnline
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.manga.mangareader.ChapterLoaderDialog
import ani.dantotsu.navBarHeight
import ani.dantotsu.notifications.subscription.SubscriptionHelper
import ani.dantotsu.notifications.subscription.SubscriptionHelper.Companion.saveSubscription
import ani.dantotsu.parsers.DynamicMangaParser
import ani.dantotsu.parsers.HMangaSources
import ani.dantotsu.parsers.MangaParser
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.media.manga.mangareader.PDF_CHAPTERS_FILE
import ani.dantotsu.media.manga.mangareader.PdfChapterMetadata
import ani.dantotsu.media.manga.mangareader.PdfPageRenderer
import ani.dantotsu.parsers.OfflineMangaParser
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.setNavigationTheme
import ani.dantotsu.settings.extensionprefs.MangaSourcePreferencesFragment
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import com.google.gson.Gson
import ani.dantotsu.util.StoragePermissions.Companion.accessAlertDialog
import ani.dantotsu.util.StoragePermissions.Companion.hasDirAccess
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.appbar.AppBarLayout
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.source.ConfigurableSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

open class MangaReadFragment : Fragment(), ScanlatorSelectionListener {
    private var _binding: FragmentMediaSourceBinding? = null
    private val binding get() = _binding!!
    private val model: MediaDetailsViewModel by activityViewModels()

    private lateinit var media: Media

    private var start = 0
    private var end: Int? = null
    private var style: Int? = null
    private var reverse = false

    private lateinit var headerAdapter: MangaReadAdapter
    private lateinit var chapterAdapter: MangaChapterAdapter

    val downloadManager = Injekt.get<DownloadsManager>()

    // One-file PDF downloads register a single library entry (the range), so the individual
    // chapters they contain have no per-chapter download record. These maps let the online
    // chapter list still show and delete those bundled chapters.
    // Live: task uniqueName -> the constituent chapters' unique numbers (pending download).
    private val pendingOneFileBundles = HashMap<String, List<String>>()
    // Persistent: a bundled chapter's unique number -> its bundle folder (range) name.
    private val bundledChapterFolders = HashMap<String, String>()

    var screenWidth = 0f
    private var progress = View.VISIBLE

    var continueEp: Boolean = false
    var loaded = false

    // "Continue on another device" auto-launch: open this chapter once chapters load.
    private var handoffNumber: String? = null
    private var handoffSourceName: String? = null
    // The extension entry the sender matched, so the chapter list loads without re-searching.
    private var handoffSourceMedia: ShowResponse? = null
    private var handoffPending = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaSourceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val intentFilter = IntentFilter().apply {
            addAction(ACTION_DOWNLOAD_STARTED)
            addAction(ACTION_DOWNLOAD_FINISHED)
            addAction(ACTION_DOWNLOAD_FAILED)
            addAction(ACTION_DOWNLOAD_PROGRESS)
        }

        ContextCompat.registerReceiver(
            requireContext(),
            downloadStatusReceiver,
            intentFilter,
            ContextCompat.RECEIVER_EXPORTED
        )

        binding.mediaSourceRecycler.updatePadding(bottom = binding.mediaSourceRecycler.paddingBottom + navBarHeight)
        screenWidth = resources.displayMetrics.widthPixels.dp

        var maxGridSize = (screenWidth / 100f).roundToInt()
        maxGridSize = max(4, maxGridSize - (maxGridSize % 2))

        val gridLayoutManager = GridLayoutManager(requireContext(), maxGridSize)

        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                if (position == 0) return maxGridSize  // header
                // Map combined-adapter position to chapter-adapter local position
                val localPos = position - headerAdapter.itemCount
                if (localPos < 0 || localPos >= chapterAdapter.itemCount) return maxGridSize
                return when (chapterAdapter.getItemViewType(localPos)) {
                    MangaChapterAdapter.VIEW_TYPE_COMPACT -> 1
                    MangaChapterAdapter.VIEW_TYPE_GAP_COMPACT -> 1
                    else -> maxGridSize
                }
            }
        }

        binding.mediaSourceRecycler.layoutManager = gridLayoutManager

        binding.ScrollTop.setOnClickListener {
            binding.mediaSourceRecycler.scrollToPosition(10)
            binding.mediaSourceRecycler.smoothScrollToPosition(0)
        }
        binding.mediaSourceRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                val position = gridLayoutManager.findFirstVisibleItemPosition()
                if (position > 2) {
                    binding.ScrollTop.translationY = -navBarHeight.toFloat()
                    binding.ScrollTop.visibility = View.VISIBLE
                } else {
                    binding.ScrollTop.visibility = View.GONE
                }
            }
        })
        model.scrolledToTop.observe(viewLifecycleOwner) {
            if (it) binding.mediaSourceRecycler.scrollToPosition(0)
        }

        continueEp = model.continueMedia ?: false

        // Read a pending "Continue on another device" request (manga only).
        activity?.intent?.let { i ->
            // Always pick up the source name from a handoff (even media-only).
            if (i.getBooleanExtra(HandoffNavigator.EXTRA_IS_ANIME, false)) return@let
            handoffSourceName = i.getStringExtra(HandoffNavigator.EXTRA_SOURCE)
            handoffSourceMedia = @Suppress("DEPRECATION")
                (i.getSerializableExtra(HandoffNavigator.EXTRA_SOURCE_MEDIA) as? ShowResponse)
            if (i.getBooleanExtra(HandoffNavigator.EXTRA_AUTO_START, false)) {
                handoffNumber = i.getStringExtra(HandoffNavigator.EXTRA_NUMBER)
                handoffPending = handoffNumber != null
                // Consume so rotation / re-entry doesn't re-trigger it.
                i.removeExtra(HandoffNavigator.EXTRA_AUTO_START)
            }
        }

        model.getMedia().observe(viewLifecycleOwner) {
            if (it != null) {
                media = it
                progress = View.GONE
                binding.mediaInfoProgressBar.visibility = progress

                if (media.format == "MANGA" || media.format == "ONE SHOT") {
                    media.selected = model.loadSelected(media)

                    subscribed =
                        SubscriptionHelper.getSubscriptions().containsKey(media.id)

                    style = media.selected!!.recyclerStyle
                    reverse = media.selected!!.recyclerReversed

                    if (!loaded) {
                        model.mangaReadSources = if (media.isAdult) HMangaSources else MangaSources

                        headerAdapter = MangaReadAdapter(it, this, model.mangaReadSources!!)
                        headerAdapter.scanlatorSelectionListener = this
                        chapterAdapter =
                            MangaChapterAdapter(
                                style ?: PrefManager.getVal(PrefName.MangaDefaultView),
                                media,
                                this,
                                model.mangaReadSources!!
                            )

                        for (download in downloadManager.mangaDownloadedTypes) {
                            if (media.compareName(download.titleName)) {
                                chapterAdapter.stopDownload(download.uniqueName)
                            }
                        }

                        binding.mediaSourceRecycler.adapter =
                            ConcatAdapter(headerAdapter, chapterAdapter)

                        // For a handoff, switch to the source the sender used (if installed),
                        // otherwise tell the user it's missing and skip the auto-launch.
                        // The source NAME is the source of truth (loadSelected resolves the index
                        // from it on every emission), so persist the name, not just the index.
                        handoffSourceName?.let { name ->
                            val idx = model.mangaReadSources!!.names.indexOf(name)
                            if (idx >= 0) {
                                media.selected!!.sourceIndex = idx
                                model.saveSelectedSourceName(media.id, name)
                                model.saveSelected(media.id, media.selected!!)
                            } else {
                                handoffPending = false
                                // The matched entry belongs to the missing source; don't seed it
                                // into whatever source we fall back to.
                                handoffSourceMedia = null
                                activity?.let { HandoffLoadingOverlay.hide(it) }
                                snackString(
                                    getString(
                                        R.string.handoff_source_missing,
                                        name
                                    )
                                )
                            }
                            handoffSourceName = null
                        }

                        val seededMedia = handoffSourceMedia
                        handoffSourceMedia = null
                        if (handoffPending) activity?.let { act ->
                            HandoffLoadingOverlay.updateStatus(
                                act, getString(R.string.handoff_loading_chapters)
                            )
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            val offline =
                                !isOnline(binding.root.context) || PrefManager.getVal(PrefName.OfflineMode)
                            if (offline) media.selected!!.sourceIndex =
                                model.mangaReadSources!!.list.lastIndex
                            // A handoff carries the exact source entry the sender matched: seed it
                            // so the chapter list loads directly instead of re-searching by title.
                            if (seededMedia != null && !offline) {
                                model.overrideMangaChapters(
                                    media.selected!!.sourceIndex, seededMedia, media.id
                                )
                            } else {
                                model.loadMangaChapters(media, media.selected!!.sourceIndex)
                            }
                        }
                        loaded = true
                    } else {
                        reload()
                        // Refresh browser button after reload completes
                        lifecycleScope.launch {
                            headerAdapter.refreshBrowserButton()
                        }
                    }
                } else {
                    binding.mediaNotSupported.visibility = View.VISIBLE
                    binding.mediaNotSupported.text =
                        getString(R.string.not_supported, media.format ?: "")
                }
            }
        }

        model.getMangaChapters().observe(viewLifecycleOwner) { _ ->
            updateChapters()
        }
    }

    override fun onScanlatorsSelected() {
        updateChapters()
    }

    /** Downloadable chapters for the range picker: excludes ones already downloaded or in-flight. */
    fun downloadableChapters(): List<MangaChapter> {
        val chapters = media.manga?.chapters?.values?.toList() ?: return emptyList()
        return chapters.filterNot { isChapterDownloadedOrActive(it) }
    }

    private fun isChapterDownloadedOrActive(ch: MangaChapter): Boolean {
        val key = ch.uniqueNumber()
        if (bundledChapterFolders.containsKey(key)) return true
        if (downloadManager.queryDownload(
                media.mainName(), ch.title ?: ch.number, MediaType.MANGA
            )
        ) return true
        val serviceKey = ch.title ?: ch.number
        return DownloadTracker.items.value.any {
            it.type == MediaType.MANGA && it.serviceKey == serviceKey
        }
    }

    fun multiDownload(
        chaptersToDownload: List<MangaChapter>,
        asPdf: Boolean = false,
        oneFile: Boolean = false
    ) {
        if (chaptersToDownload.isEmpty()) return

        if (!asPdf) {
            // Standard per-chapter image download (readable offline). Delegates to
            // downloadChaptersInOrder so the queue ends up in the same order as the picked
            // range instead of racing on each chapter's page-list fetch time.
            downloadChaptersInOrder(chaptersToDownload, asPdf = false)
            return
        }

        // PDF download
        val parser = model.mangaReadSources?.get(media.selected!!.sourceIndex) as? DynamicMangaParser
            ?: return
        runWithDownloadPermissions {
            model.continueMedia = false
            if (oneFile) {
                // Combine every selected chapter's pages into a single PDF, inserting a
                // transition page between chapters (mirrors the continuous reader's
                // chapter dividers so chapters stay separated even in paged mode).
                CoroutineScope(Dispatchers.IO).launch {
                    val allImages = mutableListOf<ImageData>()
                    val transitions = mutableListOf<MangaDownloaderService.DownloadTask.PdfTransition>()
                    val skipped = mutableListOf<String>()
                    val included = mutableListOf<MangaChapter>()
                    var prev: MangaChapter? = null
                    for (chapter in chaptersToDownload) {
                        val ch = media.manga?.chapters?.get(chapter.uniqueNumber()) ?: continue
                        val images = parser.imageList(ch.sChapter)
                        // A chapter with no pages can't be included; record it as missing so
                        // the gap surfaces both in the PDF divider and the offline list.
                        if (images.isEmpty()) {
                            skipped.add(ch.title ?: ch.number)
                            continue
                        }
                        // A boundary precedes every chapter except the first included one.
                        if (prev != null) {
                            transitions.add(
                                MangaDownloaderService.DownloadTask.PdfTransition(
                                    beforePageIndex = allImages.size,
                                    prevTitle = prev.title ?: prev.number,
                                    nextTitle = ch.title ?: ch.number,
                                    missingChapters = chapterGap(prev, ch),
                                    prevScanlator = prev.scanlator ?: "Unknown",
                                    nextScanlator = ch.scanlator ?: "Unknown"
                                )
                            )
                        }
                        allImages.addAll(images)
                        included.add(ch)
                        prev = ch
                    }
                    if (allImages.isEmpty()) {
                        snackString(getString(R.string.source_not_found))
                        return@launch
                    }
                    if (skipped.isNotEmpty()) {
                        snackString(getString(R.string.download_skipped_chapters, skipped.size))
                    }
                    val first = chaptersToDownload.first()
                    val last = chaptersToDownload.last()
                    val rangeName =
                        "${first.title ?: first.number} - ${last.title ?: last.number}"
                    val task = MangaDownloaderService.DownloadTask(
                        title = media.mainName(),
                        chapter = rangeName,
                        scanlator = first.scanlator ?: "Unknown",
                        imageData = allImages,
                        sourceMedia = media,
                        retries = 25,
                        simultaneousDownloads = 2,
                        asPdf = true,
                        pdfTransitions = transitions
                    )
                    // Mark every bundled chapter as downloading, and remember them so the
                    // single "range" finish/failed broadcast updates them all at once.
                    val includedKeys = included.map { it.uniqueNumber() }
                    withContext(Dispatchers.Main) {
                        pendingOneFileBundles[task.uniqueName] = includedKeys
                        includedKeys.forEach {
                            bundledChapterFolders[it] = rangeName
                            chapterAdapter.startDownload(it)
                        }
                    }
                    enqueueMangaDownload(task)
                }
            } else {
                // One PDF per chapter — fetched and enqueued strictly one at a time (instead of
                // one independent coroutine per chapter) so the download queue ends up in the
                // same order as the picked range instead of racing on fetch time.
                chaptersToDownload.forEach { chapterAdapter.startDownload(it.uniqueNumber()) }
                CoroutineScope(Dispatchers.IO).launch {
                    for (chapter in chaptersToDownload) {
                        fetchAndEnqueueChapter(chapter, asPdf = true)
                    }
                }
            }
        }
    }

    /** Number of chapters missing between two consecutive downloaded chapters (0 if none). */
    private fun chapterGap(a: MangaChapter, b: MangaChapter): Int {
        val an = MediaNameAdapter.findChapterNumber(a.number) ?: return 0
        val bn = MediaNameAdapter.findChapterNumber(b.number) ?: return 0
        val diff = bn.toInt() - an.toInt() - 1
        return if (diff > 0) diff else 0
    }

    private fun updateChapters() {
        val loadedChapters = model.getMangaChapters().value
        if (loadedChapters != null) {
            val chapters = loadedChapters[media.selected!!.sourceIndex]
            if (chapters != null) {
                headerAdapter.options = getScanlators(chapters)
                val filteredChapters =
                    if (model.mangaReadSources?.get(media.selected!!.sourceIndex) is OfflineMangaParser) {
                        chapters
                    } else {
                        chapters.filterNot { (_, chapter) ->
                            chapter.scanlator in headerAdapter.hiddenScanlators
                        }
                    }

                media.manga?.chapters = filteredChapters.toMutableMap()

                //CHIP GROUP
                val total = filteredChapters.size
                val divisions = total.toDouble() / 10
                start = 0
                end = null
                val limit = when {
                    (divisions < 25) -> 25
                    (divisions < 50) -> 50
                    else -> 100
                }
                headerAdapter.clearChips()
                if (total > limit) {
                    val arr = filteredChapters.keys.toTypedArray()
                    val stored = ceil((total).toDouble() / limit).toInt()
                    val position = clamp(media.selected!!.chip, 0, stored - 1)
                    val last = if (position + 1 == stored) total else (limit * (position + 1))
                    start = limit * (position)
                    end = last - 1
                    headerAdapter.updateChips(
                        limit,
                        arr,
                        (1..stored).toList().toTypedArray(),
                        position
                    )
                }

                headerAdapter.subscribeButton(true)
                headerAdapter.refreshBrowserButton()
                reload()

                // Handoff auto-launch: open the sent chapter straight into the reader,
                // skipping the progress prompt (the decision rode along in the payload).
                if (handoffPending && filteredChapters.isNotEmpty()) {
                    val target = filteredChapters.values.firstOrNull { it.number == handoffNumber }
                    if (target != null) {
                        handoffPending = false
                        binding.root.post {
                            // The chapter loader's modal sheet takes over the blocking from here.
                            activity?.let {
                                HandoffLoadingOverlay.updateStatus(
                                    it, getString(R.string.handoff_opening_reader)
                                )
                                HandoffLoadingOverlay.hide(it)
                            }
                            onMangaChapterClick(target, skipProgressDialog = true)
                        }
                    }
                }
            }
        }
    }

    private fun getScanlators(chap: MutableMap<String, MangaChapter>?): List<String> {
        val scanlators = mutableListOf<String>()
        if (chap != null) {
            val chapters = chap.values
            for (chapter in chapters) {
                scanlators.add(chapter.scanlator ?: "Unknown")
            }
        }
        return scanlators.distinct()
    }

    fun onSourceChange(i: Int): MangaParser {
        media.manga?.chapters = null
        reload()
        val selected = model.loadSelected(media)
        model.mangaReadSources?.get(selected.sourceIndex)?.showUserTextListener = null
        selected.sourceIndex = i
        selected.server = null
        model.saveSelected(media.id, selected)
        model.saveSelectedSourceName(media.id, model.mangaReadSources?.names?.getOrNull(i))
        media.selected = selected
        return model.mangaReadSources?.get(i)!!
    }

    fun onLangChange(i: Int, saveName: String) {
        val selected = model.loadSelected(media)
        selected.langIndex = i
        model.saveSelected(media.id, selected)
        media.selected = selected
        // Don't remove the saved ShowResponse when changing language - only when changing source
        // The saved response is still valid for different languages of the same extension
    }

    fun onScanlatorChange(list: List<String>) {
        val selected = model.loadSelected(media)
        selected.scanlators = list
        model.saveSelected(media.id, selected)
        media.selected = selected
    }

    fun loadChapters(i: Int, invalidate: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) { model.loadMangaChapters(media, i, invalidate) }
    }

    fun onIconPressed(viewType: Int, rev: Boolean) {
        style = viewType
        reverse = rev
        media.selected!!.recyclerStyle = style
        media.selected!!.recyclerReversed = reverse
        model.saveSelected(media.id, media.selected!!)
        reload()
    }

    fun onChipClicked(i: Int, s: Int, e: Int) {
        media.selected!!.chip = i
        start = s
        end = e
        model.saveSelected(media.id, media.selected!!)
        reload()
    }

    var subscribed = false
    fun onNotificationPressed(subscribed: Boolean, source: String) {
        this.subscribed = subscribed
        saveSubscription(media, subscribed, source)
        snackString(
            if (subscribed) getString(R.string.subscribed_notification, source)
            else getString(R.string.unsubscribed_notification)
        )
    }

    fun openSettings(pkg: MangaExtension.Installed, selectedLangIndex: Int = 0) {
        val changeUIVisibility: (Boolean) -> Unit = { show ->
            val activity = activity
            val isKnownActivity = activity is MediaDetailsActivity ||
                activity is ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity
            if (isKnownActivity && isAdded) {
                activity!!.findViewById<AppBarLayout>(R.id.mediaAppBar).isVisible = show
                activity.findViewById<ViewPager2>(R.id.mediaViewPager).isVisible = show
                activity.findViewById<CardView>(R.id.mediaCover).isVisible = show
                activity.findViewById<CardView>(R.id.mediaClose).isVisible = show
                activity.findViewById<nl.joery.animatedbottombar.AnimatedBottomBar>(R.id.mediaBottomBar).isVisible = show
                activity.findViewById<FrameLayout>(R.id.fragmentExtensionsContainer).isGone = show
            }
        }
        val allSettings = pkg.sources.filterIsInstance<ConfigurableSource>()
        if (allSettings.isNotEmpty()) {
            val selectedSetting = allSettings.getOrElse(selectedLangIndex) { allSettings[0] }
            val fragment = MangaSourcePreferencesFragment().getInstance(selectedSetting.id) {
                changeUIVisibility(true)
                loadChapters(media.selected!!.sourceIndex, true)
            }
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_up, R.anim.slide_down)
                .replace(R.id.fragmentExtensionsContainer, fragment)
                .addToBackStack(null)
                .commit()
            changeUIVisibility(false)
        } else {
            Toast.makeText(requireContext(), "Source is not configurable", Toast.LENGTH_SHORT)
                .show()
        }
    }

    fun onMangaChapterClick(i: MangaChapter, skipProgressDialog: Boolean = false) {
        model.continueMedia = false

        // Premium (subscriber/purchase-only) chapters have no page content — show a dialog to
        // open them in the browser instead of opening the reader.
        if (i.isPremium()) {
            val parser = model.mangaReadSources?.get(media.selected!!.sourceIndex) as? ani.dantotsu.parsers.DynamicMangaParser
            val sourceName = parser?.name
                ?: (parser?.extension?.sources?.getOrNull(parser.sourceLanguage)?.name ?: getString(R.string.open_in_browser))

            requireContext().customAlertDialog().apply {
                setTitle(getString(R.string.premium_chapter_title))
                setMessage(getString(R.string.premium_chapter_message, sourceName))
                setPosButton(R.string.open_in_browser) {
                    openChapterInBrowser(i)
                }
                setNegButton(R.string.cancel)
                show()
            }
            return
        }

        media.manga?.chapters?.get(i.uniqueNumber())?.let {
            media.manga?.selectedChapter = i
            model.saveSelected(media.id, media.selected!!)
            val launch = {
                ChapterLoaderDialog.newInstance(it, true)
                    .show(requireActivity().supportFragmentManager, "dialog")
            }
            // Handoffs carry the sender's progress-tracking choice (already seeded), so skip
            // the "update progress?" prompt and open directly.
            if (skipProgressDialog) launch()
            else ChapterLoaderDialog.showProgressPopupIfNecessary(requireActivity(), media) { launch() }
        }
    }

    fun openChapterInBrowser(chapter: MangaChapter) {
        val parser = model.mangaReadSources?.get(media.selected!!.sourceIndex) as? DynamicMangaParser
        if (parser != null) {
            val httpSource = parser.extension.sources.getOrNull(parser.sourceLanguage) as? eu.kanade.tachiyomi.source.online.HttpSource
            if (httpSource != null) {
                try {
                    val url = httpSource.getChapterUrl(chapter.sChapter)
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Failed to open chapter link: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "Browser view not available for this source", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireContext(), "Browser view not available for this source", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Entry point for an explicit single-chapter download from the chapter list. Asks
     * whether to download as PDF (with a "never ask again" checkbox, mirroring the
     * update-progress prompt); once dismissed with "never ask again", the remembered
     * choice in [PrefName.MangaDownloadPdf] is used silently.
     */
    fun downloadChapterWithPdfPrompt(chapter: MangaChapter) {
        if (chapter.isPremium()) return
        if (!PrefManager.getVal<Boolean>(PrefName.AskDownloadPdf)) {
            onMangaChapterDownloadClick(chapter)
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.item_custom_dialog, null)
        val checkbox = dialogView.findViewById<CheckBox>(R.id.dialog_checkbox)
        checkbox.text = getString(R.string.never_ask_again)
        checkbox.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.AskDownloadPdf, !isChecked)
        }
        requireContext().customAlertDialog().apply {
            setTitle(R.string.download_as_pdf)
            setMessage(R.string.download_as_pdf_desc)
            setCustomView(dialogView)
            setPosButton(R.string.yes) {
                PrefManager.setVal(PrefName.MangaDownloadPdf, true)
                onMangaChapterDownloadClick(chapter, asPdf = true)
            }
            setNegButton(R.string.no) {
                PrefManager.setVal(PrefName.MangaDownloadPdf, false)
                onMangaChapterDownloadClick(chapter, asPdf = false)
            }
            show()
        }
    }

    fun onMangaChapterDownloadClick(
        i: MangaChapter,
        asPdf: Boolean = PrefManager.getVal(PrefName.MangaDownloadPdf)
    ) {
        // Premium chapters have no page content to fetch, so there's nothing to download.
        if (i.isPremium()) return
        runWithDownloadPermissions {
            model.continueMedia = false
            // Mark as downloading up front, before the (slow) page fetch and before the service
            // can broadcast completion, so the icon updates immediately and never races the
            // finish signal.
            chapterAdapter.startDownload(i.uniqueNumber())
            CoroutineScope(Dispatchers.IO).launch {
                fetchAndEnqueueChapter(i, asPdf)
            }
        }
    }

    /**
     * Downloads [chapters] in the given order (used by the chapter list's mass-download picker).
     * Each chapter's page list is a separate network fetch with its own latency, so firing them
     * all off independently would make the resulting download queue order depend on whichever
     * fetch happens to finish first rather than the chapter order — this fetches them strictly
     * one at a time instead, so the queue always ends up in the order the user picked.
     */
    fun downloadChaptersInOrder(
        chapters: List<MangaChapter>,
        asPdf: Boolean = PrefManager.getVal(PrefName.MangaDownloadPdf)
    ) {
        val toDownload = chapters.filterNot { it.isPremium() }
        if (toDownload.isEmpty()) return
        runWithDownloadPermissions {
            model.continueMedia = false
            // Show every chapter as downloading immediately, before any of the (slow) page-list
            // fetches begin, so the batch reads as "queued" right away.
            toDownload.forEach { chapterAdapter.startDownload(it.uniqueNumber()) }
            CoroutineScope(Dispatchers.IO).launch {
                for (chapter in toDownload) {
                    fetchAndEnqueueChapter(chapter, asPdf)
                }
            }
        }
    }

    /**
     * Fetches [chapter]'s page list and enqueues its download task. If the source has no pages
     * for it (e.g. a since-removed chapter), the "downloading" state is cleared instead of
     * enqueuing an empty task.
     */
    private suspend fun fetchAndEnqueueChapter(chapter: MangaChapter, asPdf: Boolean) {
        val mangaChapter = media.manga?.chapters?.get(chapter.uniqueNumber()) ?: return
        val parser =
            model.mangaReadSources?.get(media.selected!!.sourceIndex) as? DynamicMangaParser
                ?: return
        val images = parser.imageList(mangaChapter.sChapter)
        if (images.isEmpty()) {
            withContext(Dispatchers.Main) {
                chapterAdapter.purgeDownload(chapter.uniqueNumber())
            }
            return
        }
        val downloadTask = MangaDownloaderService.DownloadTask(
            title = media.mainName(),
            chapter = mangaChapter.title ?: mangaChapter.number,
            scanlator = mangaChapter.scanlator ?: "Unknown",
            imageData = images,
            sourceMedia = media,
            retries = 25,
            simultaneousDownloads = 2,
            asPdf = asPdf
        )
        enqueueMangaDownload(downloadTask)
    }

    /**
     * Runs [action] once metered-network, notification and storage-access
     * requirements are satisfied. If storage access still needs to be granted the
     * action runs after the user completes the access prompt.
     */
    private fun runWithDownloadPermissions(action: () -> Unit) {
        val activity = activity ?: return
        if (!PrefManager.getVal<Boolean>(PrefName.AllowMeteredDownloads) && isOnMeteredNetwork(
                requireContext()
            )
        ) {
            snackString(getString(R.string.download_blocked_metered_desc))
            return
        }
        if (!isNotificationPermissionGranted()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1
                )
            }
        }
        if (!hasDirAccess(activity)) {
            (activity as MediaDetailsActivity).accessAlertDialog(activity.launcher) { success ->
                if (success) {
                    action()
                } else {
                    snackString(getString(R.string.download_permission_required))
                }
            }
        } else {
            action()
        }
    }

    private suspend fun enqueueMangaDownload(task: MangaDownloaderService.DownloadTask) {
        MangaServiceDataSingleton.downloadQueue.offer(task)
        DownloadTracker.enqueue(
            DownloadItem(
                id = DownloadTracker.idOf(MediaType.MANGA, task.title, task.chapter),
                type = MediaType.MANGA,
                mediaId = media.id,
                serviceKey = task.chapter,
                title = task.title,
                coverUrl = media.cover,
                label = task.chapter
            )
        )
        // If the service is not already running, start it
        if (!MangaServiceDataSingleton.isServiceRunning) {
            withContext(Dispatchers.Main) {
                ContextCompat.startForegroundService(
                    requireContext(),
                    Intent(context, MangaDownloaderService::class.java)
                )
            }
            MangaServiceDataSingleton.isServiceRunning = true
        }
    }

    private fun isNotificationPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }


    fun onMangaChapterRemoveDownloadClick(i: MangaChapter) {
        // Chapters bundled inside a one-file PDF share a single download entry (named after
        // the range); deleting any of them removes the whole file, then reloads the list.
        if (PdfPageRenderer.isPdfChapter(i.link)) {
            val pdfUri = PdfPageRenderer.decodeChapter(i.link)?.first ?: return
            val entryName = DocumentFile.fromSingleUri(requireContext(), pdfUri)?.name
                ?.removeSuffix(".pdf") ?: return
            downloadManager.removeDownload(
                DownloadedType(media.mainName(), entryName, MediaType.MANGA)
            ) {
                loadChapters(media.selected!!.sourceIndex, true)
            }
            return
        }
        // Online view of a chapter that belongs to a one-file bundle: remove the whole
        // bundle and clear the downloaded mark from all of its chapters.
        bundledChapterFolders[i.uniqueNumber()]?.let { bundleName ->
            downloadManager.removeDownload(
                DownloadedType(media.mainName(), bundleName, MediaType.MANGA)
            ) {
                bundledChapterFolders.entries
                    .filter { it.value == bundleName }
                    .map { it.key }
                    .forEach { key ->
                        bundledChapterFolders.remove(key)
                        chapterAdapter.purgeDownload(key)
                    }
            }
            return
        }
        downloadManager.removeDownload(
            DownloadedType(
                media.mainName(),
                i.number,
                MediaType.MANGA
            )
        ) {
            chapterAdapter.deleteDownload(i)
        }
    }

    fun onMangaChapterStopDownloadClick(i: MangaChapter) {
        val cancelIntent = Intent().apply {
            action = MangaDownloaderService.ACTION_CANCEL_DOWNLOAD
            putExtra(MangaDownloaderService.EXTRA_CHAPTER, i.number)
        }
        requireContext().sendBroadcast(cancelIntent)

        // Remove the download from the manager and update the UI
        downloadManager.removeDownload(
            DownloadedType(
                media.mainName(),
                i.number,
                MediaType.MANGA
            )
        ) {
            chapterAdapter.purgeDownload(i.uniqueNumber())
        }
    }

    private val downloadStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!this@MangaReadFragment::chapterAdapter.isInitialized) return
            when (intent.action) {
                ACTION_DOWNLOAD_STARTED -> {
                    val chapterNumber = intent.getStringExtra(EXTRA_CHAPTER_NUMBER)
                    chapterNumber?.let { chapterAdapter.startDownload(it) }
                }

                ACTION_DOWNLOAD_FINISHED -> {
                    val chapterNumber = intent.getStringExtra(EXTRA_CHAPTER_NUMBER)
                    chapterNumber?.let { key ->
                        val bundle = pendingOneFileBundles.remove(key)
                        if (bundle != null) {
                            bundle.forEach { chapterAdapter.stopDownload(it) }
                        } else {
                            chapterAdapter.stopDownload(key)
                        }
                    }
                }

                ACTION_DOWNLOAD_FAILED -> {
                    val chapterNumber = intent.getStringExtra(EXTRA_CHAPTER_NUMBER)
                    chapterNumber?.let { key ->
                        val bundle = pendingOneFileBundles.remove(key)
                        if (bundle != null) {
                            bundle.forEach {
                                bundledChapterFolders.remove(it)
                                chapterAdapter.purgeDownload(it)
                            }
                        } else {
                            chapterAdapter.purgeDownload(key)
                        }
                    }
                }

                ACTION_DOWNLOAD_PROGRESS -> {
                    val chapterNumber = intent.getStringExtra(EXTRA_CHAPTER_NUMBER)
                    val progress = intent.getIntExtra("progress", 0)
                    val stats = downloadStats(
                        intent.getLongExtra("speed", 0),
                        intent.getLongExtra("eta", -1),
                        intent.getLongExtra("bytesDone", 0)
                    )
                    chapterNumber?.let {
                        chapterAdapter.updateDownloadProgress(it, progress, stats)
                    }
                }
            }
        }
    }


    @SuppressLint("NotifyDataSetChanged")
    private fun reload() {
        val selected = model.loadSelected(media)

        // Find latest chapter for subscription
        selected.latest =
            media.manga?.chapters?.values?.maxOfOrNull {
                MediaNameAdapter.findChapterNumber(it.number) ?: 0f
            } ?: 0f
        selected.latest =
            media.userProgress?.toFloat()?.takeIf { selected.latest < it } ?: selected.latest

        model.saveSelected(media.id, selected)
        headerAdapter.handleChapters()
        chapterAdapter.notifyItemRangeRemoved(0, chapterAdapter.arr.size)
        var chapList: ArrayList<MangaChapter> = arrayListOf()
        if (media.manga!!.chapters != null) {
            val end = if (end != null && end!! < media.manga!!.chapters!!.size) end else null
            chapList.addAll(
                media.manga!!.chapters!!.values.toList()
                    .slice(start..(end ?: (media.manga!!.chapters!!.size - 1)))
            )
            if (reverse)
                chapList = (chapList.reversed() as? ArrayList<MangaChapter>) ?: chapList
        }
        // Build display list with gap placeholders between non-consecutive chapters
        val isCompact = (style ?: PrefManager.getVal(PrefName.MangaDefaultView)) == 1
        val nonSequentialKeywords = listOf(
            "extra", "omake", "special", "side story", "prologue", "epilogue",
            "afterword", "author", "bonus", "cover story", "gaiden", "interlude"
        )
        fun resolveChapterNumber(chapter: MangaChapter): Float? {
            val parserNumber = chapter.sChapter.chapter_number
            return if (parserNumber > 0f) parserNumber else MediaNameAdapter.findChapterNumber(chapter.number)
        }
        val displayList = ArrayList<MangaChapterListItem>()
        for (i in chapList.indices) {
            displayList.add(MangaChapterListItem.Chapter(chapList[i]))
            if (i < chapList.size - 1) {
                val currentChapterNumber = chapList[i].number
                val nextChapterNumber = chapList[i + 1].number
                val isCurrentNonSequential =
                    nonSequentialKeywords.any { currentChapterNumber.lowercase().contains(it) }
                val isNextNonSequential =
                    nonSequentialKeywords.any { nextChapterNumber.lowercase().contains(it) }
                if (isCurrentNonSequential || isNextNonSequential) continue

                val currNum = resolveChapterNumber(chapList[i])
                val nextNum = resolveChapterNumber(chapList[i + 1])
                if (currNum != null && nextNum != null) {
                    val lo = minOf(currNum, nextNum)
                    val hi = maxOf(currNum, nextNum)
                    val missing = hi.toInt() - lo.toInt() - 1
                    if (missing > 0) {
                        if (isCompact) {
                            // One placeholder per missing chapter, each carries its chapter number
                            for (n in 1..missing) {
                                val chNum = lo.toInt() + n
                                displayList.add(MangaChapterListItem.Gap(chNum.toFloat(), chNum.toFloat(), 1))
                            }
                        } else {
                            displayList.add(MangaChapterListItem.Gap(lo, hi, missing))
                        }
                    }
                }
            }
        }
        // Add gap placeholders for chapters missing before the first available chapter
        // (e.g. source starts at Ch2 but Ch1 is missing). Only on the first tab — later
        // tabs naturally start mid-list and aren't missing the earlier chapters.
        val sequentialNumbers = chapList
            .filter { chapter -> !nonSequentialKeywords.any { chapter.number.lowercase().contains(it) } }
            .mapNotNull { resolveChapterNumber(it) }
        val minSequentialNumber = sequentialNumbers.minOrNull()
        val missing = (minSequentialNumber?.toInt() ?: 1) - 1
        if (start == 0 && missing > 0) {
            val gaps = ArrayList<MangaChapterListItem>()
            if (isCompact) {
                for (n in 1..missing) {
                    gaps.add(MangaChapterListItem.Gap(n.toFloat(), n.toFloat(), 1))
                }
            } else {
                gaps.add(MangaChapterListItem.Gap(0f, minSequentialNumber ?: 0f, missing))
            }
            // Detect list order: if the first sequential chapter is the minimum, list is ascending
            val firstSeqNum = chapList.firstOrNull { chapter ->
                !nonSequentialKeywords.any { chapter.number.lowercase().contains(it) } &&
                resolveChapterNumber(chapter) != null
            }?.let { resolveChapterNumber(it) }
            if (firstSeqNum == minSequentialNumber) {
                displayList.addAll(0, gaps)
            } else {
                displayList.addAll(gaps)
            }
        }

        chapterAdapter.offlineMode =
            model.mangaReadSources?.get(media.selected!!.sourceIndex) is OfflineMangaParser
        chapterAdapter.arr = displayList
        chapterAdapter.updateType(style ?: PrefManager.getVal(PrefName.MangaDefaultView))
        chapterAdapter.notifyItemRangeInserted(0, displayList.size)

        // Mark chapters that are downloaded as part of a one-file PDF bundle (they have no
        // per-chapter download record, so the standard marking above misses them).
        if (!chapterAdapter.offlineMode) {
            lifecycleScope.launch(Dispatchers.IO) {
                val bundled = loadBundledChapters()
                withContext(Dispatchers.Main) {
                    bundledChapterFolders.putAll(bundled)
                    bundled.keys.forEach { chapterAdapter.markDownloaded(it) }
                }
            }
        } else {
            computeOfflineSizes()
        }
    }

    /**
     * When viewing the "Downloaded" source, show each chapter's on-disk size. Own-folder
     * chapters use the exact folder size; bundled chapters (sharing one PDF) get a
     * page-proportional estimate since per-chapter bytes don't exist on disk.
     */
    private fun computeOfflineSizes() {
        val title = media.mainName()
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            chapterAdapter.arr.forEach { item ->
                if (item !is MangaChapterListItem.Chapter) return@forEach
                val ch = item.chapter
                val size = if (PdfPageRenderer.isPdfChapter(ch.link)) {
                    val decoded = PdfPageRenderer.decodeChapter(ch.link)
                    if (decoded != null) {
                        val (uri, pages) = decoded
                        val total = PdfPageRenderer.pageCount(ctx, uri).coerceAtLeast(1)
                        val pdfLen = DocumentFile.fromSingleUri(ctx, uri)?.length() ?: 0L
                        pdfLen * pages.size / total
                    } else 0L
                } else {
                    DownloadsManager.getDirSize(ctx, MediaType.MANGA, title, ch.number)
                }
                ch.progress = formatBytes(size)
            }
            withContext(Dispatchers.Main) {
                if (this@MangaReadFragment::chapterAdapter.isInitialized) {
                    @Suppress("NotifyDataSetChanged")
                    chapterAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    /**
     * Scans this media's one-file PDF downloads and returns a map of each bundled chapter's
     * unique number to the bundle (range) folder that stores it, read from the bundle's
     * [PDF_CHAPTERS_FILE] sidecar. Used to show/delete bundled chapters in the online list.
     */
    private fun loadBundledChapters(): Map<String, String> {
        val result = HashMap<String, String>()
        val gson = Gson()
        downloadManager.mangaDownloadedTypes
            .filter { media.compareName(it.titleName) }
            // Bundle entries are named after a range ("X - Y"); skip the file I/O for the
            // (typically many) single-chapter folders that can't be bundles.
            .filter { it.chapterName.contains(" - ") }
            .forEach { dl ->
                val folder = DownloadsManager.getSubDirectory(
                    requireContext(), MediaType.MANGA, false, dl.titleName, dl.chapterName
                ) ?: return@forEach
                val metaFile = folder.listFiles().firstOrNull {
                    it.isFile && it.name == PDF_CHAPTERS_FILE
                } ?: return@forEach
                try {
                    val text = requireContext().contentResolver.openInputStream(metaFile.uri)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@forEach
                    val meta = gson.fromJson(text, PdfChapterMetadata::class.java) ?: return@forEach
                    meta.chapters.forEach { entry ->
                        // Key matches MangaChapter.uniqueNumber() for these chapters; using the
                        // per-chapter scanlator keeps same-numbered chapters distinct.
                        result["${entry.title}-${entry.scanlator}"] = dl.chapterName
                    }
                } catch (e: Exception) {
                    Logger.log("Failed to read bundle metadata: ${e.message}")
                }
            }
        return result
    }

    override fun onDestroy() {
        model.mangaReadSources?.flushText()
        super.onDestroy()
        requireContext().unregisterReceiver(downloadStatusReceiver)
    }

    private var state: Parcelable? = null
    override fun onResume() {
        super.onResume()
        binding.mediaInfoProgressBar.visibility = progress
        binding.mediaSourceRecycler.layoutManager?.onRestoreInstanceState(state)

        // Refresh source list in case new extensions were installed
        if (::headerAdapter.isInitialized) {
            headerAdapter.refreshSourceList()
        }

        requireActivity().setNavigationTheme()
    }

    override fun onPause() {
        super.onPause()
        state = binding.mediaSourceRecycler.layoutManager?.onSaveInstanceState()
    }

    companion object {
        const val ACTION_DOWNLOAD_STARTED = "ani.dantotsu.ACTION_DOWNLOAD_STARTED"
        const val ACTION_DOWNLOAD_FINISHED = "ani.dantotsu.ACTION_DOWNLOAD_FINISHED"
        const val ACTION_DOWNLOAD_FAILED = "ani.dantotsu.ACTION_DOWNLOAD_FAILED"
        const val ACTION_DOWNLOAD_PROGRESS = "ani.dantotsu.ACTION_DOWNLOAD_PROGRESS"
        const val EXTRA_CHAPTER_NUMBER = "extra_chapter_number"
    }
}