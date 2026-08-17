package ani.dantotsu.media.novel

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.DialogMultiDownloadBinding
import ani.dantotsu.databinding.FragmentMediaSourceBinding
import androidx.core.content.ContextCompat
import ani.dantotsu.download.DownloadItem
import ani.dantotsu.download.DownloadTracker
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.novel.NovelDownloaderService
import ani.dantotsu.download.novel.NovelServiceDataSingleton
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.novel.novelreader.NovelReaderActivity
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.connections.updateProgress
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.parsers.NovelSources
import ani.dantotsu.parsers.SavedShowResponse
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.parsers.novel.lnreader.LNReaderChapter
import ani.dantotsu.parsers.novel.lnreader.LNReaderDownloader
import ani.dantotsu.parsers.novel.lnreader.LNReaderNovel
import ani.dantotsu.parsers.novel.lnreader.LNReaderParser
import ani.dantotsu.parsers.novel.lnreader.LNReaderReadState
import ani.dantotsu.parsers.novel.lnreader.LNReaderSession
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.ceil

/**
 * The novel tab of a media page.
 *
 * Modelled on the manga tab rather than on a search screen: the entry a novel matches on a source
 * is resolved once and remembered, so opening the media goes straight to its chapter list with a
 * continue card, range chips and a download button. Picking a different entry is a deliberate act
 * behind the search button, the same way the manga page treats a wrong title.
 */
class NovelReadFragment : Fragment() {

    private companion object {
        const val DOWNLOADED_SOURCE = "Downloaded"
    }

    private var _binding: FragmentMediaSourceBinding? = null
    private val binding get() = _binding!!
    private val model: MediaDetailsViewModel by activityViewModels()

    lateinit var media: Media
    var source = 0

    private lateinit var headerAdapter: NovelReadAdapter
    private lateinit var chapterAdapter: NovelChapterAdapter

    private var parser: LNReaderParser? = null
    private var entry: ShowResponse? = null
    private var novel: LNReaderNovel? = null

    /** Bounds of the chapter range the chips currently have selected. */
    private var start = 0
    private var end: Int? = null
    private var reversed = false

    var loaded = false
    private var progressShown = true
    private var state: Parcelable? = null

    /** True when the Downloaded source is selected, which lists saved runs rather than chapters. */
    private var downloadedMode = false
    private var downloadedEntries: List<Pair<String, androidx.documentfile.provider.DocumentFile>> =
        emptyList()

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
        binding.mediaSourceRecycler.updatePadding(
            bottom = binding.mediaSourceRecycler.paddingBottom + navBarHeight
        )
        binding.mediaSourceRecycler.layoutManager = LinearLayoutManager(requireContext())
        model.scrolledToTop.observe(viewLifecycleOwner) {
            if (it) binding.mediaSourceRecycler.scrollToPosition(0)
        }

        model.getMedia().observe(viewLifecycleOwner) {
            if (it != null && !loaded) {
                media = it
                loaded = true
                source = media.selected?.sourceIndex ?: 0
                reversed = PrefManager.getCustomVal("${media.id}_novel_reversed", false)

                headerAdapter = NovelReadAdapter(media, this, model.novelSources)
                chapterAdapter = NovelChapterAdapter(
                    ::onChapterClick, ::markProgressUpTo,
                    ::openChapterInBrowser, ::downloadChapter,
                )
                binding.mediaSourceRecycler.adapter = ConcatAdapter(headerAdapter, chapterAdapter)

                resolveAndLoad()
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // Resolving which entry on the source this media is
    // -----------------------------------------------------------------------------------

    /**
     * Loads the chapter list, matching the media to an entry first if that has not been done.
     *
     * The match is saved, so a search only runs the first time a source is used for a media. That
     * is what lets the page open on chapters instead of a list of search results.
     */
    private fun resolveAndLoad() {
        val current = NovelSources.lnReaderAt(source)
        parser = current
        if (current == null) {
            // The Downloaded source is not a plugin and has no chapters to fetch — what it offers
            // is the runs already saved for this novel, which still have to be readable offline.
            if (model.novelSources.list.getOrNull(source)?.name == DOWNLOADED_SOURCE) {
                loadDownloaded()
            } else {
                showProgress(false)
                headerAdapter.setEntryTitle(null)
                headerAdapter.setFound(false)
            }
            return
        }
        downloadedMode = false

        showProgress(true)
        current.setUserText(getString(R.string.searching))
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { SavedShowResponse.load(media.id, current.saveName) }.getOrNull()
            }
            val matched = saved ?: withContext(Dispatchers.IO) {
                runCatching { current.sortedSearch(media).firstOrNull() }.getOrNull()
            }
            if (_binding == null) return@launch

            if (matched == null) {
                showProgress(false)
                headerAdapter.setEntryTitle(null)
                headerAdapter.setFound(false)
                return@launch
            }
            entry = matched
            // Saves the match and sets the header's "Found : x" line in one step, the same way
            // the anime and manga pages report which entry they resolved to.
            withContext(Dispatchers.IO) {
                runCatching { current.saveShowResponse(media.id, matched, selected = saved != null) }
            }
            headerAdapter.setEntryTitle(matched.name)
            loadChapters(current, matched)
        }
    }

    /**
     * Lists the runs already saved for this novel.
     *
     * A saved run is a whole book rather than a chapter to fetch, so it opens straight in the
     * reader with no session attached — paging between its chapters is the book's own spine.
     *
     * Only the folder is kept here, not a file: a run saved as HTML has to be repackaged before it
     * can be opened, and doing that for every listed run would mean rebuilding books nobody asked
     * for. The work happens on the tap instead.
     */
    private fun loadDownloaded() {
        downloadedMode = true
        showProgress(true)
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                val manager = Injekt.get<DownloadsManager>()
                manager.novelDownloadedTypes
                    .filter { it.titleName == media.mainName() }
                    .mapNotNull { downloaded ->
                        val dir = DownloadsManager.getSubDirectory(
                            requireContext(), MediaType.NOVEL, false,
                            downloaded.titleName, downloaded.chapterName
                        )
                        if (LNReaderDownloader.isRun(dir)) downloaded.chapterName to dir!! else null
                    }
            }
            showProgress(false)
            if (_binding == null) return@launch

            downloadedEntries = entries
            headerAdapter.setEntryTitle(media.mainName())
            headerAdapter.setFound(entries.isNotEmpty())
            headerAdapter.clearChips()
            headerAdapter.setContinue(null, -1, null)
            chapterAdapter.submit(
                entries.mapIndexed { index, (name, _) ->
                    NovelChapterAdapter.Row(
                        chapter = LNReaderChapter(name, name, null, null),
                        indexInNovel = index,
                        tracked = false,
                        downloaded = true,
                    )
                }
            )
        }
    }

    private fun loadChapters(current: LNReaderParser, matched: ShowResponse) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { current.loadNovel(matched.link) }
            }
            // Resolved on the same background hop, so the header's browser button has an answer
            // ready without ever touching the plugin's JavaScript from the main thread.
            val url = withContext(Dispatchers.IO) {
                runCatching { current.resolveUrl(matched.link, isNovel = true) }.getOrNull()
            }
            showProgress(false)
            if (_binding == null) return@launch
            entryUrl = url

            result.onSuccess { details ->
                novel = details
                headerAdapter.setFound(details.chapters.isNotEmpty())
                buildChips(details.chapters.size)
                refreshList()
                refreshContinue()
            }.onFailure {
                Logger.log("Novel chapters failed: ${it.message}")
                headerAdapter.setFound(false)
                snackString(it.message ?: getString(R.string.failed_to_load))
            }
        }
    }

    /** Mirrors the manga page's ranges: 25/50/100 per chip depending on how long the novel is. */
    private fun buildChips(total: Int) {
        headerAdapter.clearChips()
        start = 0
        end = null
        if (total == 0) return

        val divisions = total.toDouble() / 10
        val limit = when {
            divisions < 25 -> 25
            divisions < 50 -> 50
            else -> 100
        }
        if (total <= limit) return

        val names = orderedChapters().map { it.name }.toTypedArray()
        val stored = ceil(total.toDouble() / limit).toInt()
        val position = (media.selected?.chip ?: 0).coerceIn(0, stored - 1)
        val last = if (position + 1 == stored) total else limit * (position + 1)
        start = limit * position
        end = last - 1
        headerAdapter.updateChips(limit, names, (1..stored).toList().toTypedArray(), position)
    }

    /** Reading order, honouring the reverse toggle. Sources list newest first. */
    private fun orderedChapters(): List<LNReaderChapter> {
        val chapters = novel?.chapters.orEmpty()
        return if (reversed) chapters.asReversed() else chapters
    }

    /**
     * A chapter's number for comparing against list progress.
     *
     * The plugin's own number when it gives one, else whatever can be read out of the title. A
     * chapter with neither gets a number no list will ever reach, so it is never shown as tracked —
     * the same guard the manga list uses, and it matters more here: novels are full of interludes
     * and afterwords that carry no number at all.
     */
    private fun chapterNumberOf(chapter: LNReaderChapter): Float =
        chapter.chapterNumber?.toFloat()
            ?: MediaNameAdapter.findChapterNumber(chapter.name)
            ?: 9999f

    private fun refreshList() {
        val current = parser ?: return
        val details = novel ?: return
        val ordered = orderedChapters()
        if (ordered.isEmpty()) {
            chapterAdapter.submit(emptyList())
            return
        }
        val from = start.coerceIn(0, ordered.lastIndex)
        val to = (end ?: ordered.lastIndex).coerceIn(from, ordered.lastIndex)

        val progress = media.userProgress?.toFloat()
        val rows = ordered.slice(from..to).map { chapter ->
            NovelChapterAdapter.Row(
                chapter = chapter,
                indexInNovel = details.chapters.indexOf(chapter),
                tracked = progress != null && chapterNumberOf(chapter) <= progress,
                downloaded = false,
            )
        }
        chapterAdapter.submit(rows)
    }

    /** The first chapter not yet marked read, in reading order. */
    /**
     * Opens the chapter's own page on the source.
     *
     * The URL is the plugin's to build — see [LNReaderParser.resolveUrl] — and building it means
     * evaluating JavaScript, so it happens off the main thread even though the answer is cached.
     */
    private fun openChapterInBrowser(index: Int) {
        val current = parser ?: return
        val chapter = novel?.chapters?.getOrNull(index) ?: return
        lifecycleScope.launch {
            val url = withContext(Dispatchers.IO) {
                runCatching { current.resolveUrl(chapter.path, isNovel = false) }.getOrNull()
            }
            if (_binding == null) return@launch
            if (url == null) snackString(getString(R.string.failed_to_load))
            else openLinkInBrowser(url)
        }
    }

    /**
     * Saves this one chapter, asking which format first.
     *
     * The same prompt the manga tab puts in front of a single-chapter download, down to the "never
     * ask again" checkbox: once that is ticked the remembered choice in [PrefName.NovelDownloadEpub]
     * is used silently, and the range dialog on the header button is where it can be changed again.
     */
    private fun downloadChapter(index: Int) {
        val current = parser ?: return
        val details = novel ?: return
        val chapter = details.chapters.getOrNull(index) ?: return

        fun save(asEpub: Boolean) =
            startDownload(current, details, listOf(chapter), asEpub, oneFile = false)

        if (!PrefManager.getVal<Boolean>(PrefName.AskDownloadEpub)) {
            save(PrefManager.getVal(PrefName.NovelDownloadEpub))
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.item_custom_dialog, null)
        val checkbox = dialogView.findViewById<android.widget.CheckBox>(R.id.dialog_checkbox)
        checkbox.text = getString(R.string.never_ask_again)
        checkbox.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.AskDownloadEpub, !isChecked)
        }
        requireContext().customAlertDialog().apply {
            setTitle(R.string.download_as_epub)
            setMessage(R.string.download_as_epub_desc)
            setCustomView(dialogView)
            setPosButton(R.string.yes) {
                PrefManager.setVal(PrefName.NovelDownloadEpub, true)
                save(true)
            }
            setNegButton(R.string.no) {
                PrefManager.setVal(PrefName.NovelDownloadEpub, false)
                save(false)
            }
            show()
        }
    }

    // -----------------------------------------------------------------------------------
    // Header callbacks
    // -----------------------------------------------------------------------------------

    fun matchedEntryName(): String? = entry?.name

    /**
     * The novel's page on the source, as the plugin builds it.
     *
     * Resolved once when the chapter list loads and kept, because the header asks for it on every
     * rebind and the answer comes from the plugin's own JavaScript.
     */
    private var entryUrl: String? = null

    fun entryUrl(): String? = entryUrl

    fun onSourceChange(i: Int) {
        val selected = model.loadSelected(media)
        selected.sourceIndex = i
        selected.chip = 0
        source = i
        model.saveSelected(media.id, selected)
        model.saveSelectedSourceName(media.id, model.novelSources.names.getOrNull(i))
        media.selected = selected

        // A different source means a different entry; nothing carries over.
        entry = null
        entryUrl = null
        novel = null
        chapterAdapter.submit(emptyList())
        headerAdapter.clearChips()
        resolveAndLoad()
    }

    /** Re-matches this media to a different entry on the current source. */
    fun openEntryPicker() {
        val query = entry?.name ?: media.mainName()
        NovelSourceSearchDialog.newInstance(source, query).apply {
            onPicked = { picked ->
                parser?.let { current ->
                    entry = picked
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { current.saveShowResponse(media.id, picked, selected = true) }
                    }
                    headerAdapter.setEntryTitle(picked.name)
                    chapterAdapter.submit(emptyList())
                    headerAdapter.clearChips()
                    showProgress(true)
                    loadChapters(current, picked)
                }
            }
        }.show(parentFragmentManager, "novel_entry_picker")
    }

    fun onChipClicked(position: Int, s: Int, e: Int) {
        val selected = model.loadSelected(media)
        selected.chip = position
        model.saveSelected(media.id, selected)
        media.selected = selected
        start = s
        end = e
        refreshList()
    }

    fun toggleReverse() {
        reversed = !reversed
        PrefManager.setCustomVal("${media.id}_novel_reversed", reversed)
        buildChips(novel?.chapters?.size ?: 0)
        refreshList()
        refreshContinue()
        snackString(
            if (reversed) getString(R.string.oldest) else getString(R.string.newest)
        )
    }

    fun onChapterClick(index: Int) {
        if (downloadedMode) {
            val (name, dir) = downloadedEntries.getOrNull(index) ?: return
            // No chapter list: a saved run is already a complete book, so the reader's own paging
            // covers its chapters and there is nothing to fetch alongside. The media still goes
            // over, so finishing a chapter in there can update the tracker like any other read.
            LNReaderSession.startDownloaded(media)
            showProgress(true)
            lifecycleScope.launch {
                val uri = withContext(Dispatchers.IO) {
                    LNReaderDownloader.readableUri(requireContext().applicationContext, dir, name)
                }
                if (_binding == null) return@launch
                showProgress(false)
                if (uri == null) {
                    snackString(getString(R.string.failed_to_load))
                    return@launch
                }
                startActivity(
                    Intent(requireContext(), NovelReaderActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        setDataAndType(uri, "application/epub+zip")
                        putExtra(NovelReaderActivity.EXTRA_LN_SESSION, true)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                )
                Logger.log("Opening downloaded novel run: $name")
            }
            return
        }
        val current = parser ?: return
        val details = novel ?: return
        NovelChapterOpener.open(requireActivity(), current, details, index, media)
    }

    /**
     * Which chapter the continue card offers.
     *
     * The manga tab's rule, key for key: the chapter after whatever the list counts or after the
     * one the app last opened, whichever is further along, and if that chapter has already been
     * read to the end here then the one after it. Progress is filed under the chapter's number, so
     * this matches on numbers too.
     *
     * The one addition is the fallback. A manga chapter list is numbered and manga simply hides the
     * card when nothing matches; novel sources are full of interludes and afterwords carrying no
     * number at all, and a source numbering none of its chapters would never show a card. Where
     * there are no numbers to match on, the card falls back to the chapter after the last one
     * opened here.
     */
    private fun refreshContinue() {
        val current = parser ?: return
        val details = novel ?: return

        fun numberOf(chapter: LNReaderChapter): Int? =
            chapterNumberOf(chapter).takeIf { it > 0f && it < 9999f }?.toInt()

        if (details.chapters.none { numberOf(it) != null }) {
            val opened = LNReaderReadState.readPaths(media.id, current.plugin.id, details.path)
            val index = details.chapters.indexOfLast { it.path in opened } + 1
            if (index in details.chapters.indices) {
                headerAdapter.setContinue(details.chapters[index], index, null)
            } else headerAdapter.setContinue(null, -1, null)
            return
        }

        val trackerNext = (media.userProgress ?: 0) + 1
        val appNext = PrefManager
            .getNullableCustomVal("${media.id}_current_chp", null, String::class.java)
            ?.toIntOrNull() ?: 1
        var number = maxOf(trackerNext, appNext)
        var index = details.chapters.indexOfFirst { numberOf(it) == number }
        if (index < 0) {
            headerAdapter.setContinue(null, -1, null)
            return
        }

        // Read to the end already: offer the next one instead, which is what the manga card does
        // rather than sending the user back to a chapter they have finished.
        val position = PrefManager.getNullableCustomVal("${media.id}_$number", null, Long::class.java)
        val total = PrefManager.getNullableCustomVal("${media.id}_${number}_max", null, Long::class.java)
        if (position != null && total != null && total > 0 && position >= total - 1) {
            val next = details.chapters.indexOfFirst { numberOf(it) == number + 1 }
            if (next >= 0) {
                index = next
                number += 1
            }
        }
        headerAdapter.setContinue(details.chapters[index], index, number.toString())
    }

    /**
     * Long press: move the list on to this chapter, which is what it does on the manga tab.
     *
     * A chapter the tracker cannot name — no number in the plugin's data and none in its title — is
     * left alone rather than pushing a meaningless value, the same as manga.
     */
    private fun markProgressUpTo(index: Int) {
        val details = novel ?: return
        val chapter = details.chapters.getOrNull(index) ?: return
        val number = chapterNumberOf(chapter).takeIf { it > 0f && it < 9999f } ?: run {
            snackString(getString(R.string.no_chapter))
            return
        }
        val text = if (number == number.toLong().toFloat()) number.toLong().toString()
        else number.toString()
        updateProgress(media, text)
        refreshList()
        refreshContinue()
    }

    /**
     * Picks a range of chapters and a format to save them in.
     *
     * The same dialog the manga tab uses, down to the layout: a from/to pair over the chapter list
     * and a sticky format switch. Where manga chooses between images and PDF, a novel chooses
     * between EPUB — which the reader opens directly — and HTML, which everything else does.
     */
    fun promptMultiDownload() {
        val current = parser ?: return
        val details = novel ?: return
        if (details.chapters.isEmpty()) return

        val ordered = orderedChapters()
        val names = ordered.map { it.name }.toTypedArray()
        val pickerBinding = DialogMultiDownloadBinding.inflate(layoutInflater)
        var startIndex = start.coerceIn(0, ordered.lastIndex)
        var endIndex = ordered.lastIndex
        pickerBinding.downloadRangeStart.apply {
            setSimpleItems(names)
            setText(names[startIndex], false)
            setOnItemClickListener { _, _, position, _ -> startIndex = position }
        }
        pickerBinding.downloadRangeEnd.apply {
            setSimpleItems(names)
            setText(names[endIndex], false)
            setOnItemClickListener { _, _, position, _ -> endIndex = position }
        }
        pickerBinding.downloadPdf.setText(R.string.download_as_epub)
        pickerBinding.downloadOneFile.setText(R.string.one_file_download_novel)
        pickerBinding.downloadPdf.isChecked = PrefManager.getVal(PrefName.NovelDownloadEpub)
        // An EPUB run is one book by construction, so the choice only means anything for HTML.
        pickerBinding.downloadOneFile.isEnabled = !pickerBinding.downloadPdf.isChecked
        pickerBinding.downloadOneFile.isChecked = pickerBinding.downloadOneFile.isEnabled &&
                PrefManager.getVal(PrefName.NovelDownloadOneFile)
        pickerBinding.downloadPdf.setOnCheckedChangeListener { _, isChecked ->
            pickerBinding.downloadOneFile.isEnabled = !isChecked
            if (isChecked) pickerBinding.downloadOneFile.isChecked = false
        }

        requireContext().customAlertDialog().apply {
            setTitle(getString(R.string.multi_chapter_downloader))
            setCustomView(pickerBinding.root)
            setPosButton(R.string.ok) {
                val from = minOf(startIndex, endIndex)
                val to = maxOf(startIndex, endIndex)
                val asEpub = pickerBinding.downloadPdf.isChecked
                val oneFile = !asEpub && pickerBinding.downloadOneFile.isChecked
                PrefManager.setVal(PrefName.NovelDownloadEpub, asEpub)
                PrefManager.setVal(PrefName.NovelDownloadOneFile, oneFile)
                startDownload(current, details, ordered.subList(from, to + 1), asEpub, oneFile)
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    /**
     * Hands the run to the download service rather than fetching it here.
     *
     * A hundred chapters is a hundred requests; run in the fragment's scope it would die the
     * moment the user left the page. The service keeps a foreground notification alive and shares
     * the queue with every other novel download.
     */
    private fun startDownload(
        current: LNReaderParser,
        details: LNReaderNovel,
        chapters: List<LNReaderChapter>,
        asEpub: Boolean,
        oneFile: Boolean,
    ) {
        val entryName = LNReaderDownloader.entryNameFor(chapters)
        val task = NovelDownloaderService.DownloadTask(
            title = media.mainName(),
            chapter = entryName,
            downloadLink = "",
            originalLink = details.path,
            sourceMedia = media,
            coverUrl = details.cover ?: media.cover,
            lnReader = NovelDownloaderService.LNReaderRun(
                pluginId = current.plugin.id,
                novelName = details.name,
                novelPath = details.path,
                author = details.author,
                chapterNames = chapters.map { it.name },
                chapterPaths = chapters.map { it.path },
                asEpub = asEpub,
                oneFile = oneFile,
                language = LanguageMapper.getLanguageCode(current.language)
                    .takeIf { it != "all" } ?: "en",
            ),
        )

        NovelServiceDataSingleton.downloadQueue.offer(task)
        DownloadTracker.enqueue(
            DownloadItem(
                id = DownloadTracker.idOf(MediaType.NOVEL, task.title, task.chapter),
                type = MediaType.NOVEL,
                mediaId = media.id,
                serviceKey = task.chapter,
                title = task.title,
                coverUrl = task.coverUrl,
                label = task.chapter,
            )
        )
        if (!NovelServiceDataSingleton.isServiceRunning) {
            ContextCompat.startForegroundService(
                requireContext(), Intent(requireContext(), NovelDownloaderService::class.java)
            )
            NovelServiceDataSingleton.isServiceRunning = true
        }
        snackString(getString(R.string.download_started))
    }

    /**
     * Shows loading where the chapters are about to appear.
     *
     * The header's own bar sits below the source row and above the list, which is where the manga
     * page reports the same thing. The fragment's centred bar reads as the whole tab loading and
     * lands over the source selector instead.
     */
    private fun showProgress(show: Boolean) {
        progressShown = show
        _binding?.mediaInfoProgressBar?.visibility = View.GONE
        if (::headerAdapter.isInitialized) headerAdapter.setLoading(show)
    }

    override fun onResume() {
        super.onResume()
        _binding?.mediaInfoProgressBar?.visibility = View.GONE
        _binding?.mediaSourceRecycler?.layoutManager?.onRestoreInstanceState(state)
        // Coming back from the reader, a chapter has usually just been marked read.
        if (novel != null) {
            refreshList()
            refreshContinue()
        }
    }

    override fun onPause() {
        super.onPause()
        state = _binding?.mediaSourceRecycler?.layoutManager?.onSaveInstanceState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Parsers outlive this screen, so a listener left pointing at the header keeps the whole
        // fragment alive with it.
        model.novelSources[source]?.showUserTextListener = null
        _binding = null
    }
}
