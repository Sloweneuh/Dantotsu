package ani.dantotsu.media.novel.novelreader

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import ani.dantotsu.GesturesListener
import ani.dantotsu.NoPaddingArrayAdapter
import ani.dantotsu.R
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.handoff.HandoffBottomSheet
import ani.dantotsu.connections.handoff.HandoffPayload
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.media.screenshot.ScreenshotDialogFragment
import ani.dantotsu.media.screenshot.ScreenshotUtil
import ani.dantotsu.connections.updateProgress
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.util.customAlertDialog
import ani.dantotsu.parsers.novel.lnreader.LNReaderBook
import ani.dantotsu.parsers.novel.lnreader.LNReaderReadState
import ani.dantotsu.parsers.novel.lnreader.LNReaderSession
import ani.dantotsu.util.Logger
import ani.dantotsu.currContext
import ani.dantotsu.databinding.ActivityNovelReaderBinding
import ani.dantotsu.hideSystemBars
import ani.dantotsu.showSystemBars
import ani.dantotsu.others.ImageViewDialog
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.CurrentNovelReaderSettings
import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.tryWith
import com.google.android.material.slider.Slider
import com.vipulog.ebookreader.Book
import com.vipulog.ebookreader.EbookReaderEventListener
import com.vipulog.ebookreader.EbookReaderView
import com.vipulog.ebookreader.ReaderError
import com.vipulog.ebookreader.ReaderFlow
import com.vipulog.ebookreader.ReaderTheme
import com.vipulog.ebookreader.RelocationInfo
import com.vipulog.ebookreader.TocItem
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.Timer
import java.util.TimerTask
import kotlin.math.roundToInt
import kotlin.math.min
import kotlin.properties.Delegates


class NovelReaderActivity : AppCompatActivity(), EbookReaderEventListener {
    private lateinit var binding: ActivityNovelReaderBinding
    private val scope = lifecycleScope

    private var notchHeight: Int? = null

    var loaded = false

    private lateinit var book: Book
    private lateinit var sanitizedBookId: String

    /**
     * What reader settings are filed under.
     *
     * The same as the book id for an ordinary book, but a chapter session opens a *new* one-chapter
     * book on every page turn, and keying settings by book id there resets the theme, font and
     * margins each time. Settings belong to the novel, so a session keys them by that; reading
     * position stays per book, which is what a position means.
     */
    private lateinit var settingsId: String

    /** Where this book's reading position is stored; see [onBookLoaded]. */
    private lateinit var positionKey: String
    private lateinit var toc: List<TocItem>
    private var currentTheme: ReaderTheme? = null
    private var currentCfi: String? = null

    val themes = ArrayList<ReaderTheme>()

    var defaultSettings = CurrentNovelReaderSettings()

    companion object {
        /**
         * Set by a launcher that has populated [LNReaderSession]. Without it the reader assumes a
         * plain book and clears any session left over from a previous read.
         */
        const val EXTRA_LN_SESSION = "lnreader_session"

        /** Width of each page-turning strip, as a percentage of the reader's width. */
        private const val PAGE_TAP_EDGE = 15

        /** How much of the top and bottom is left alone, as a percentage of the reader's height. */
        private const val PAGE_TAP_VERTICAL_MARGIN = 20

        /** How far through counts as having read the chapter, matching the reader's own rounding. */
        private const val FINISHED_FRACTION = 0.99

        /** How long a forward request is given to move before it counts as having hit the end. */
        private const val END_PROBE_MS = 350L

        /** How long to wait for the reader library to report a book open, or fail it. */
        private const val LOAD_TIMEOUT_MS = 25_000L

        /**
         * The themes that exist before a book is open.
         *
         * The reader's own list is these plus whatever the loaded book contributes, which is named
         * "Default". The settings screen has no book, so it offers this list — kept here so the two
         * cannot drift apart.
         */
        val THEME_NAMES = listOf("Default", "Forest", "Ocean", "Sunset", "Desert", "Galaxy")
    }


    init {
        val forestTheme = ReaderTheme(
            name = "Forest",
            lightFg = Color.parseColor("#000000"),
            lightBg = Color.parseColor("#E7F6E7"),
            lightLink = Color.parseColor("#008000"),
            darkFg = Color.parseColor("#FFFFFF"),
            darkBg = Color.parseColor("#084D08"),
            darkLink = Color.parseColor("#00B200")
        )

        val oceanTheme = ReaderTheme(
            name = "Ocean",
            lightFg = Color.parseColor("#000000"),
            lightBg = Color.parseColor("#E4F0F9"),
            lightLink = Color.parseColor("#007BFF"),
            darkFg = Color.parseColor("#FFFFFF"),
            darkBg = Color.parseColor("#0A2E3E"),
            darkLink = Color.parseColor("#00A5E4")
        )

        val sunsetTheme = ReaderTheme(
            name = "Sunset",
            lightFg = Color.parseColor("#000000"),
            lightBg = Color.parseColor("#FDEDE6"),
            lightLink = Color.parseColor("#FF5733"),
            darkFg = Color.parseColor("#FFFFFF"),
            darkBg = Color.parseColor("#441517"),
            darkLink = Color.parseColor("#FF6B47")
        )

        val desertTheme = ReaderTheme(
            name = "Desert",
            lightFg = Color.parseColor("#000000"),
            lightBg = Color.parseColor("#FDF5E6"),
            lightLink = Color.parseColor("#FFA500"),
            darkFg = Color.parseColor("#FFFFFF"),
            darkBg = Color.parseColor("#523B19"),
            darkLink = Color.parseColor("#FFBF00")
        )

        val galaxyTheme = ReaderTheme(
            name = "Galaxy",
            lightFg = Color.parseColor("#000000"),
            lightBg = Color.parseColor("#F2F2F2"),
            lightLink = Color.parseColor("#800080"),
            darkFg = Color.parseColor("#FFFFFF"),
            darkBg = Color.parseColor("#000000"),
            darkLink = Color.parseColor("#B300B3")
        )

        themes.addAll(listOf(forestTheme, oceanTheme, sunsetTheme, desertTheme, galaxyTheme))
    }


    override fun onDestroy() {
        // The watchdog holds a reference to this activity through the runnable; a book that is
        // still loading when the reader is closed must not fire it at a dead window.
        loadWatchdog?.let { binding.bookReader.removeCallbacks(it) }
        loadWatchdog = null
        super.onDestroy()
    }

    override fun onAttachedToWindow() {
        checkNotch()
        super.onAttachedToWindow()
    }


    @SuppressLint("WebViewApiAvailability")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //check for supported webview
        val webViewVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()?.versionName
        } else {
            WebViewCompat.getCurrentWebViewPackage(this)?.versionName
        }
        val firstVersion = webViewVersion?.split(".")?.firstOrNull()?.toIntOrNull()
        if (webViewVersion == null || firstVersion == null || firstVersion < 87) {
            val text = if (webViewVersion == null) {
                "Could not find webView installed"
            } else if (firstVersion == null) {
                "Could not find WebView Version Number: $webViewVersion"
            } else if (firstVersion < 87) { //false positive?
                "Webview Versiom: $firstVersion. PLease update"
            } else {
                "Please update WebView from PlayStore"
            }
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            //open playstore
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data =
                Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview")
            startActivity(intent)
            //stop reader
            finish()
            return
        }


        ThemeManager(this).applyTheme()
        binding = ActivityNovelReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        controllerDuration = (PrefManager.getVal<Float>(PrefName.AnimationSpeed) * 200).toLong()

        setupViews()
        setupBackPressedHandler()
    }


    @SuppressLint("ClickableViewAccessibility")
    private fun setupViews() {
        // Any launcher that did not set up a chapter session gets a clean one. Opting in rather
        // than out means a downloaded book, or a volume from an extension source, can never
        // inherit the chapter list of whatever was streamed last.
        if (!intent.getBooleanExtra(EXTRA_LN_SESSION, false)) LNReaderSession.clear()

        binding.bookReader.useSafeScope(this)

        // Listener first: `openBook` starts the load, and a book that resolves quickly could
        // otherwise report back before there is anything registered to hear it.
        binding.bookReader.setEbookReaderListener(this)
        scope.launch {
            Logger.log("Novel reader: opening ${intent.data}")
            runCatching { binding.bookReader.openBook(intent.data!!) }
                .onFailure { failToLoad("openBook threw: ${it.message}", it) }
        }
        startLoadWatchdog()

        binding.novelReaderBack.setOnClickListener { finish() }
        binding.novelReaderSettings.setSafeOnClickListener {
            NovelReaderSettingsDialogFragment.newInstance()
                .show(supportFragmentManager, NovelReaderSettingsDialogFragment.TAG)
        }

        val gestureDetector = GestureDetectorCompat(this, object : GesturesListener() {
            override fun onSingleClick(event: MotionEvent) {
                handleTap(event)
            }
        })

        binding.bookReader.setOnTouchListener { _, event ->
            if (event != null) tryWith { gestureDetector.onTouchEvent(event) } ?: false
            else false
        }

        binding.novelReaderNextChap.setOnClickListener { binding.novelReaderNextChapter.performClick() }
        binding.novelReaderPrevChap.setOnClickListener { binding.novelReaderPreviousChapter.performClick() }

        // A streamed LNReader chapter is a one-chapter book, so paging past its end has nowhere to
        // go: these buttons fetch the neighbouring chapter instead. A downloaded book holds a whole
        // run in one spine, and there the reader's own paging is what the user wants.
        if (LNReaderSession.isActive) {
            binding.novelReaderNextChapter.setOnClickListener {
                changeChapter(LNReaderSession.currentIndex + 1)
            }
            binding.novelReaderPreviousChapter.setOnClickListener {
                changeChapter(LNReaderSession.currentIndex - 1)
            }
        } else {
            binding.novelReaderNextChapter.setOnClickListener { skipChapter(1) }
            binding.novelReaderPreviousChapter.setOnClickListener { skipChapter(-1) }
        }

        setUpMoreMenu()

        binding.novelReaderSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
            }

            override fun onStopTrackingTouch(slider: Slider) {
                binding.bookReader.gotoFraction(slider.value.toDouble())
            }
        })

        // Page, not skip. The bottom buttons now jump chapters in every mode, and routing the
        // volume keys through them would turn a page-forward key into a chapter-forward one.
        onVolumeUp = { pageForward() }

        onVolumeDown = { binding.bookReader.prev() }
    }

    /**
     * The overflow menu, holding what the header has no room for.
     *
     * The same three actions the manga reader offers, because they mean the same thing here:
     * capture what is on screen, hand the book to another device, and decide whether finishing a
     * chapter is allowed to move the tracker. The last one is the answer the progress dialog would
     * otherwise ask for, kept reachable so it can be changed without waiting to be asked again.
     */
    private fun setUpMoreMenu() {
        binding.novelReaderMore.setSafeOnClickListener {
            val media = LNReaderSession.media
            val trackable = media != null && media.id >= 0
            val popup = PopupMenu(this, binding.novelReaderMore)
            popup.menuInflater.inflate(R.menu.manga_reader_more, popup.menu)
            popup.menu.findItem(R.id.action_screenshot)
                .setIcon(ScreenshotUtil.screenshotIcon(this))
            popup.menu.findItem(R.id.action_handoff).isVisible = trackable
            val trackItem = popup.menu.findItem(R.id.action_track_progress)
            trackItem.isVisible = trackable
            if (trackable) {
                trackItem.isChecked = PrefManager.getCustomVal("${media!!.id}_save_progress", true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) popup.setForceShowIcon(true)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_screenshot -> { takeScreenshot(); true }
                    R.id.action_handoff -> { sendHandoff(); true }
                    R.id.action_track_progress -> {
                        val id = media?.id ?: return@setOnMenuItemClickListener true
                        val enabled = !PrefManager.getCustomVal("${id}_save_progress", true)
                        PrefManager.setCustomVal("${id}_save_progress", enabled)
                        snackString(
                            getString(
                                if (enabled) R.string.track_progress_enabled
                                else R.string.track_progress_disabled
                            )
                        )
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun takeScreenshot() {
        // The controls are drawn over the page, so they would end up in the capture.
        handleController(false)
        binding.bookReader.postDelayed({
            val bitmap = ScreenshotUtil.captureView(binding.bookReader)
            if (bitmap == null) {
                snackString(getString(R.string.screenshot_failed))
                return@postDelayed
            }
            val media = LNReaderSession.media
            val chapter = LNReaderSession.chapterAt(LNReaderSession.currentIndex)
            ScreenshotDialogFragment.newInstance(
                screenshot = bitmap,
                title = media?.userPreferredName ?: book.title.orEmpty(),
                titleOptions = media?.mainTitleOptions() ?: listOfNotNull(book.title),
                coverUrl = media?.cover,
                numberLabel = chapter?.name
                    ?: binding.novelReaderChapterSelect.selectedItem?.toString()
                    ?: book.title.orEmpty(),
                // The page counter as the reader shows it, which is the only progress a reflowable
                // book has — there is no fixed page to name.
                progressLabel = binding.novelReaderPageNumber.text?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { getString(R.string.handoff_page_label, it) }
                    .orEmpty(),
                sourceLabel = LNReaderSession.parser?.plugin?.name,
                isAnime = false,
            ).show(supportFragmentManager, "screenshot")
        }, 200)
    }

    /**
     * "Continue on another device".
     *
     * Only offered for a media the other device can look up — an entry read straight off a source
     * has no AniList or MangaUpdates id behind it, so there would be nothing to re-open there.
     */
    private fun sendHandoff() {
        val media = LNReaderSession.media ?: return
        val chapter = LNReaderSession.chapterAt(LNReaderSession.currentIndex)
        scope.launch {
            val sourceMedia = withContext(Dispatchers.IO) {
                runCatching {
                    LNReaderSession.parser?.loadSavedShowResponse(media.id)
                }.getOrNull()
            }
            HandoffBottomSheet.send(
                HandoffPayload(
                    mediaId = media.id,
                    isMAL = false,
                    isAnime = false,
                    mediaType = "MANGA",
                    title = media.userPreferredName,
                    cover = media.cover,
                    sourceName = LNReaderSession.parser?.plugin?.name,
                    number = chapter?.name,
                    trackProgress = PrefManager.getCustomVal("${media.id}_save_progress", true),
                    muSeriesId = media.muSeriesId,
                    sourceMedia = HandoffPayload.encodeShowResponse(sourceMedia),
                )
            ).show(supportFragmentManager, "handoff")
        }
    }

    /**
     * Moves a chapter within an already-open book, which is what a downloaded run is.
     *
     * These buttons are labelled with the neighbouring chapters and carry skip icons, so they jump
     * the spine rather than turning a page — the slider is what moves within a chapter. A book
     * whose table of contents is a single entry has no chapters to jump between, and there they
     * fall back to paging so the control still does something.
     */
    private fun skipChapter(offset: Int) {
        val entries = if (::toc.isInitialized) toc else emptyList()
        val target = binding.novelReaderChapterSelect.selectedItemPosition + offset
        if (entries.size > 1 && target in entries.indices) {
            binding.novelReaderChapterSelect.setSelection(target)
            binding.bookReader.goto(entries[target].href)
            updateChapterNavigationText()
        } else if (entries.size > 1) {
            snackString(
                if (offset < 0) getString(R.string.first_chapter)
                else getString(R.string.last_chapter)
            )
        } else {
            if (offset < 0) binding.bookReader.prev() else binding.bookReader.next()
        }
    }

    /**
     * Swaps the book for another chapter of the novel.
     *
     * A streamed book holds one chapter, so moving to another means fetching, packaging and
     * reopening. Reusing the open reader keeps the user's theme and settings applied; recreating
     * the activity per chapter would reload all of it.
     */
    private fun loadSessionChapter(index: Int) {
        val parser = LNReaderSession.parser ?: return
        val novel = LNReaderSession.novel ?: return
        if (LNReaderSession.chapterAt(index) == null) {
            snackString(
                if (index < 0) getString(R.string.first_chapter)
                else getString(R.string.last_chapter)
            )
            return
        }
        if (chapterLoading) return
        chapterLoading = true

        binding.progress.visibility = View.VISIBLE
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                LNReaderBook.build(this@NovelReaderActivity, parser, novel, index)
            }

            file.onSuccess { epub ->
                LNReaderSession.currentIndex = index
                loaded = false
                lastTocIndex = -1
                atEndOfBook = false
                reachedEnd = false
                val uri = FileProvider.getUriForFile(
                    this@NovelReaderActivity, "$packageName.provider", epub
                )
                binding.bookReader.openBook(uri)
                startLoadWatchdog()
                markSessionChapterRead()
                binding.novelReaderChapterSelect.setSelection(index, false)
                updateChapterNavigationText()
            }.onFailure {
                // Cleared here but not on success: the new book has not arrived yet, and letting
                // another load start on the progress events the outgoing one is still emitting is
                // exactly what would make this fetch forever. [onBookLoaded] clears it.
                chapterLoading = false
                binding.progress.visibility = View.GONE
                Logger.log("LNReader chapter switch failed: ${it.message}")
                snackString(it.message ?: getString(R.string.failed_to_load))
            }
        }
    }

    /**
     * Marks the chapter read on this device, which is what the chapter list draws from.
     *
     * Separate from the tracker on purpose, and recorded on opening rather than on finishing: a
     * source's numbering rarely lines up with a tracker's and plenty of chapters carry no number at
     * all, so "seen" and "counted towards progress" are different questions. The second one is
     * asked in [trackProgress] once the chapter has actually been read through.
     */
    private fun markSessionChapterRead() {
        val parser = LNReaderSession.parser ?: return
        val novel = LNReaderSession.novel ?: return
        val chapter = LNReaderSession.chapterAt(LNReaderSession.currentIndex) ?: return
        LNReaderReadState.markRead(
            LNReaderSession.media?.id, parser.plugin.id, novel.path, chapter.path
        )
    }

    private fun setupBackPressedHandler() {
        var lastBackPressedTime: Long = 0
        val doublePressInterval: Long = 2000

        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.bookReader.canGoBack()) {
                    binding.bookReader.goBack()
                } else {
                    if (lastBackPressedTime + doublePressInterval > System.currentTimeMillis()) {
                        finish()
                    } else {
                        snackString("Press back again to exit")
                        lastBackPressedTime = System.currentTimeMillis()
                    }
                }
            }
        })
    }


    override fun onBookLoadFailed(error: ReaderError) {
        Logger.log("Novel reader: load failed: ${error.message}")
        failToLoad(error.message, null)
    }

    /**
     * Ends the wait when the reader library never reports back at all.
     *
     * It answers on one of two callbacks, and until one arrives there is nothing on screen but a
     * spinner and no way out but the back button. A book that has taken this long is not coming,
     * so say so rather than spin forever — and leave a line in the log, which is the thing an
     * unreproducible hang has never provided.
     */
    private fun startLoadWatchdog() {
        loadWatchdog?.let { binding.bookReader.removeCallbacks(it) }
        val task = Runnable {
            if (loaded) return@Runnable
            failToLoad(getString(R.string.failed_to_load), null)
        }
        loadWatchdog = task
        binding.bookReader.postDelayed(task, LOAD_TIMEOUT_MS)
    }

    private fun failToLoad(message: String?, cause: Throwable?) {
        loadWatchdog?.let { binding.bookReader.removeCallbacks(it) }
        loadWatchdog = null
        cause?.let { Logger.log(it) }
        Logger.log("Novel reader: giving up on this book — $message")
        binding.progress.visibility = View.GONE
        chapterLoading = false
        snackString(message ?: getString(R.string.failed_to_load))
        finish()
    }


    override fun onBookLoaded(book: Book) {
        // Every line below runs from a JavaScript bridge callback, where a thrown exception goes
        // nowhere: the reader is left on its loading spinner with `loaded` still false, which also
        // stops the controls from opening — an app that appears to have hung, and says nothing
        // about why. Whatever happens here, the wait ends and the reason is reported.
        loadWatchdog?.let { binding.bookReader.removeCallbacks(it) }
        loadWatchdog = null
        runCatching { bindLoadedBook(book) }
            .onFailure {
                Logger.log("Novel reader: failed to set up the loaded book: ${it.message}")
                Logger.log(it)
                snackString(it.localizedMessage ?: getString(R.string.failed_to_load))
            }
        binding.progress.visibility = View.GONE
        loaded = true
        chapterLoading = false
    }

    private fun bindLoadedBook(book: Book) {
        this.book = book
        // Not `!!`: a book whose metadata carries no identifier is a book to open under a fallback
        // name, not a crash in the one callback that cannot report one.
        val bookId = book.identifier ?: book.title ?: intent.data?.lastPathSegment ?: "book"
        toc = book.toc

        val illegalCharsRegex = Regex("[^a-zA-Z0-9._-]")
        sanitizedBookId = bookId.replace(illegalCharsRegex, "_")
        settingsId = LNReaderSession.novel?.takeIf { LNReaderSession.isActive }?.let {
            "ln_${LNReaderSession.parser?.plugin?.id}_${it.path}".replace(illegalCharsRegex, "_")
        } ?: sanitizedBookId

        // Reading position, filed under the media when there is one so
        // [ani.dantotsu.connections.sync.ProgressSync] can shard it like any other per-media key —
        // where you are in a book belongs on every device, not just the one that read it.
        val mediaId = LNReaderSession.media?.id?.takeIf { it >= 0 }
        positionKey = if (mediaId != null) "lnreader_pos-$mediaId-$sanitizedBookId"
        else "${sanitizedBookId}_progress"

        // Same division of labour as the manga reader's header: the picker names the chapter, the
        // title under it names the work, and the line below names the source it came from. A
        // streamed chapter is its own one-chapter book, so `book.title` there is the chapter — which
        // the picker is already showing — and the novel's name has to come from the session.
        binding.novelReaderTitle.text = book.title
        binding.novelReaderSource.text = book.author?.joinToString(", ")

        // A streamed chapter's own TOC has exactly one entry, which makes the picker useless. When
        // a session is running it lists the novel's chapters instead, so the picker jumps between
        // them the way it jumps within a downloaded book.
        if (LNReaderSession.isActive) {
            val chapters = LNReaderSession.chapters
            binding.novelReaderTitle.text = LNReaderSession.media?.userPreferredName
                ?: LNReaderSession.novel?.name ?: book.title
            binding.novelReaderSource.text = LNReaderSession.parser?.plugin?.name.orEmpty()
            binding.novelReaderSource.isVisible = PrefManager.getVal(PrefName.ShowSource)
            binding.novelReaderChapterSelect.adapter =
                NoPaddingArrayAdapter(this, R.layout.item_dropdown, chapters.map { it.name })
            binding.novelReaderChapterSelect.setSelection(LNReaderSession.currentIndex, false)
            binding.novelReaderChapterSelect.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        // Fires on the programmatic selection above too, so only act on a change.
                        if (position != LNReaderSession.currentIndex) changeChapter(position)
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
        } else {
            val tocLabels = book.toc.map { it.label ?: "" }
            binding.novelReaderChapterSelect.adapter =
                NoPaddingArrayAdapter(this, R.layout.item_dropdown, tocLabels)
            binding.novelReaderChapterSelect.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        // A Spinner fires this for its own initial selection, after layout — which
                        // lands after the saved position is restored below and would throw the
                        // reader back to the first chapter every time a book is reopened.
                        if (!tocPickerReady) {
                            tocPickerReady = true
                            return
                        }
                        binding.bookReader.goto(book.toc[position].href)
                        updateChapterNavigationText()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
        }
        updateChapterNavigationText()

        binding.bookReader.getAppearance {
            currentTheme = it
            // The book's own theme goes in front of the built-in ones, but this runs on every
            // book that gets opened — and a chapter session opens one per chapter into the same
            // reader, so appending blindly grows the theme picker by one entry per chapter read.
            themes.removeAll { theme -> theme.name == it.name }
            themes.add(0, it)
            defaultSettings =
                loadReaderSettings("${settingsId}_current_settings") ?: defaultSettings
            applySettings()
        }

        // The old, device-local key is still read as a fallback, so a book already in progress
        // does not restart at the top the first time it is opened after this became syncable.
        val cfi = PrefManager.getNullableCustomVal(positionKey, null, String::class.java)
            ?: PrefManager.getNullableCustomVal("${sanitizedBookId}_progress", null, String::class.java)

        cfi?.let { binding.bookReader.goto(it) }
        recordLocalProgress()
        Logger.log("Novel reader: loaded '${book.title}' (${toc.size} spine entries)")
    }


    override fun onProgressChanged(info: RelocationInfo) {
        // Anything that actually moved means the end is no longer where it was thought to be.
        if (info.cfi != currentCfi) reachedEnd = false
        currentCfi = info.cfi
        binding.novelReaderSlider.value = info.fraction.toFloat()
        updatePageNumber(info)
        // During a session the picker tracks the novel's chapters, not this book's single-entry
        // TOC, so following the TOC here would drag it back to position zero on every scroll.
        if (!LNReaderSession.isActive) {
            val pos = tocIndexOf(info)
            if (pos != null) {
                binding.novelReaderChapterSelect.setSelection(pos)
                updateChapterNavigationText()
            }
        }
        // Where in the book the reader is. What chapter has been *finished* is the tracker's
        // business and goes out separately, below.
        PrefManager.setCustomVal(positionKey, info.cfi)
        trackProgress(info)
    }

    // region Progress

    /** Chapters already reported this sitting, so paging back over one does not ask again. */
    private val reportedChapters = HashSet<String>()
    private var lastTocIndex = -1

    /** False until the chapter picker's own opening callback has been absorbed. */
    private var tocPickerReady = false

    /** Guards against a second chapter load while one is in flight. */
    private var chapterLoading = false

    /** Pending timeout on the reader library answering; see [startLoadWatchdog]. */
    private var loadWatchdog: Runnable? = null

    /** Whether the reader is sitting at the very end of the open book. */
    private var atEndOfBook = false

    /** Set once a forward request has been seen to move nothing; see [pageForward]. */
    private var reachedEnd = false

    /**
     * Whether there is nothing further to read in this book.
     *
     * Paginated, the last page reports a fraction of 1.0 and that settles it. Scrolled, it never
     * does: the fraction is the scroll offset over the content height, so its ceiling is
     * `1 - viewport/content` — a four-screen chapter stops near 0.75 with every word already on
     * screen. Two attempts to recover the missing viewport from the reported locations both came
     * out short, because those locations do not describe the visible span.
     *
     * So it is no longer computed. [pageForward] asks the reader to move and watches whether it
     * did; a request that changes nothing means there was nothing left, which holds in either
     * layout and needs no arithmetic at all.
     */
    private fun isAtEnd(info: RelocationInfo): Boolean =
        reachedEnd || info.fraction >= FINISHED_FRACTION

    /**
     * Moves forward, or discovers that it cannot.
     *
     * A forward request at the true end of a chapter produces no progress event — that silence is
     * the signal, and the next forward request moves on to the following chapter. What tells the
     * reader a chapter has ended is the block closing its text, not this.
     */
    private fun pageForward() {
        if (advanceIfAtEnd()) return
        val before = currentCfi
        binding.bookReader.next()
        binding.bookReader.postDelayed({
            if (!loaded || chapterLoading || currentCfi != before) return@postDelayed
            reachedEnd = true
            atEndOfBook = true
        }, END_PROBE_MS)
    }

    /** Where in the open book's own table of contents the reader currently is. */
    private fun tocIndexOf(info: RelocationInfo): Int? {
        if (!::toc.isInitialized) return null
        return info.tocItem?.let { item -> toc.indexOfFirst { it == item } }?.takeIf { it >= 0 }
    }

    /**
     * Follows where the reader is.
     *
     * Reaching the end of a chapter is deliberately *not* when progress is reported: reopening a
     * finished chapter lands on its last page, and asking there means being asked about a chapter
     * before reading a word of it. Leaving a chapter is the moment that means something, so the
     * question lives in [changeChapter] — which is where the manga reader puts it too.
     */
    private fun trackProgress(info: RelocationInfo) {
        atEndOfBook = isAtEnd(info)
        recordLocalProgress(info.fraction)
        if (!loaded || chapterLoading) return
        val media = LNReaderSession.media ?: return
        if (media.id < 0) return

        // Except in a downloaded run, where many chapters share one spine and there is no chapter
        // change to hang it on: crossing into the next one is the only moment available.
        if (LNReaderSession.isActive || !::toc.isInitialized || toc.size <= 1) return
        val index = tocIndexOf(info) ?: return
        if (lastTocIndex in toc.indices && index > lastTocIndex) reportChapterAt(media, lastTocIndex)
        lastTocIndex = index
    }

    /**
     * Moves to another chapter of the novel, offering to move the tracker with it.
     *
     * Only going forwards: paging back to re-read something is not progress, and pushing it would
     * be a claim the user never made.
     */
    private fun changeChapter(index: Int) {
        val media = LNReaderSession.media
        val forward = index > LNReaderSession.currentIndex
        if (media == null || media.id < 0 || !forward || !LNReaderSession.isActive) {
            loadSessionChapter(index)
            return
        }
        reportChapterAt(media, 0) { loadSessionChapter(index) }
    }

    /**
     * The current chapter's number, as manga keys its per-chapter state.
     *
     * Null when the chapter carries none — the plugin gave no number and the title has none to
     * read — in which case there is no key to file progress under, exactly as for a manga chapter
     * whose number cannot be parsed.
     */
    private fun sessionChapterNumber(): String? {
        val chapter = LNReaderSession.chapterAt(LNReaderSession.currentIndex) ?: return null
        val number = chapter.chapterNumber?.toFloat()
            ?: MediaNameAdapter.findChapterNumber(chapter.name)
        return number?.takeIf { it > 0f }?.toInt()?.toString()
    }

    /**
     * Records where the app has got to, in the shape the manga tab reads.
     *
     * `<id>_current_chp` is the chapter last opened and `<id>_<n>` / `<id>_<n>_max` how far into it
     * the reader is — the same three keys, so the continue card's rule and its progress bar work
     * for a novel without knowing it is one. A novel's position within a chapter is a CFI rather
     * than a page, so the pair here is a percentage: the bar only ever draws their ratio.
     */
    private fun recordLocalProgress(fraction: Double? = null) {
        val media = LNReaderSession.media ?: return
        if (media.id < 0 || !LNReaderSession.isActive) return
        val number = sessionChapterNumber() ?: return
        PrefManager.setCustomVal("${media.id}_current_chp", number)
        if (fraction == null) return
        PrefManager.setCustomVal(
            "${media.id}_$number", (fraction * 100).roundToInt().coerceIn(0, 100).toLong()
        )
        PrefManager.setCustomVal("${media.id}_${number}_max", 100L)
    }

    /**
     * Turns a forward tap at the very end of a chapter into the next chapter.
     *
     * The chapter's own last lines name what is coming next, so a forward request from there is
     * the reader asking to go on rather than something to do on its behalf.
     */
    private fun advanceIfAtEnd(): Boolean {
        if (!atEndOfBook || !LNReaderBook.continuous()) return false
        if (!LNReaderSession.isActive || !LNReaderSession.hasNext()) return false
        changeChapter(LNReaderSession.currentIndex + 1)
        return true
    }

    /**
     * Reports the chapter at spine position [position].
     *
     * During a session the book is one chapter of the novel, which carries the plugin's own number;
     * in a downloaded book there is only the table of contents entry to read one out of.
     */
    private fun reportChapterAt(media: Media, position: Int, onDone: () -> Unit = {}) {
        val sessionChapter = LNReaderSession.chapterAt(LNReaderSession.currentIndex)
            ?.takeIf { LNReaderSession.isActive }
        if (sessionChapter != null) {
            // A plugin's own chapter number when it gives one, since it is more reliable than what
            // can be read out of a title like "Volume 3 Chapter 12".
            val number = sessionChapter.chapterNumber?.toFloat()
                ?: MediaNameAdapter.findChapterNumber(sessionChapter.name)
            report(media, sessionChapter.path, number, onDone)
            return
        }
        val label = toc.getOrNull(position)?.label.orEmpty()
        report(media, "toc:$position", MediaNameAdapter.findChapterNumber(label), onDone)
    }

    /**
     * [onDone] runs once the chapter has been dealt with, whether that meant asking, updating
     * silently, or nothing at all — a chapter with no number to report is still a chapter read.
     */
    private fun report(media: Media, key: String, number: Float?, onDone: () -> Unit = {}) {
        val value = number?.takeIf { it > 0f }
        if (value == null || !reportedChapters.add(key)) {
            onDone()
            return
        }
        val text = if (value == value.toLong().toFloat()) value.toLong().toString()
        else value.toString()
        askThenUpdateProgress(media, text, onDone)
    }

    /**
     * The same question the manga reader asks, and the same answers.
     *
     * Global progress belongs to AniList or MangaUpdates, and [updateProgress] picks whichever the
     * media came from. Nothing goes out in incognito, nothing goes out for an adult title unless
     * that has been allowed, and "don't ask again" is remembered per media so a title being read
     * straight through only interrupts once.
     */
    private fun askThenUpdateProgress(media: Media, number: String, onDone: () -> Unit = {}) {
        if (PrefManager.getVal<Boolean>(PrefName.Incognito) ||
            (media.isAdult && !PrefManager.getVal<Boolean>(PrefName.UpdateForHReader)) ||
            // Nowhere to report to otherwise, and asking would only produce a login prompt.
            (Anilist.userid == null && media.muSeriesId == null)
        ) {
            onDone()
            return
        }

        // Not in continuous mode: there the question was already put before the reader opened,
        // by [ani.dantotsu.media.novel.NovelChapterOpener], and asking again at every chapter
        // boundary is exactly the interruption that mode exists to avoid. Manga draws the same line.
        val ask = !LNReaderBook.continuous() &&
                PrefManager.getVal<Boolean>(PrefName.AskIndividualReader) &&
                PrefManager.getCustomVal("${media.id}_progressDialog", true)
        if (!ask) {
            if (PrefManager.getCustomVal("${media.id}_save_progress", true)) {
                updateProgress(media, number)
            }
            onDone()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.item_custom_dialog, null)
        val checkbox = dialogView.findViewById<CheckBox>(R.id.dialog_checkbox)
        checkbox.text = getString(R.string.dont_ask_again, media.userPreferredName)
        checkbox.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setCustomVal("${media.id}_progressDialog", !isChecked)
        }
        customAlertDialog().apply {
            setTitle(R.string.title_update_progress)
            setCustomView(dialogView)
            // Not dismissable, deliberately: the checkbox writes "don't ask again" the moment it is
            // ticked, so walking away would suppress the question for good while leaving this
            // answer unset — which then falls back to updating silently from then on.
            setCancelable(false)
            setPosButton(R.string.yes) {
                PrefManager.setCustomVal("${media.id}_save_progress", true)
                updateProgress(media, number)
                onDone()
            }
            setNegButton(R.string.no) {
                PrefManager.setCustomVal("${media.id}_save_progress", false)
                onDone()
            }
            show()
        }
    }

    // endregion Progress

    /**
     * The page counter, from the reader's own pagination.
     *
     * A reflowable book has no fixed pages, so the library reports "locations" — the count it
     * derives from the current layout and settings, which is what changing the font size moves.
     * They are zero-based, hence the +1, and a book still laying out reports none at all.
     */
    /**
     * How far through the book the reader is, as a percentage.
     *
     * Not a page count, because there is no page count to be had. What the reader library reports
     * are *locations* — fixed-size runs of characters, the unit a CFI is measured in — and they are
     * neither pages nor a fixed ratio to pages, which is why every attempt to present them as
     * "page N of M" was wrong. A reflowable book has no inherent pages anyway: change the font size
     * and the count changes with it.
     *
     * The fraction is exactly the progress the slider beneath it already draws, so the two agree.
     */
    private fun updatePageNumber(info: RelocationInfo) {
        binding.novelReaderPageNumber.text =
            if (defaultSettings.hidePageNumbers) ""
            else "${(info.fraction * 100).roundToInt().coerceIn(0, 100)}%"
    }

    /**
     * Names the chapters the bottom buttons would move to.
     *
     * During a session that is the novel's chapter list; in a downloaded book it is the spine's own
     * table of contents, which is what those buttons page through there. Either way an end of the
     * list leaves its side blank rather than showing a button that goes nowhere.
     */
    private fun updateChapterNavigationText() {
        val labels: List<String>
        val index: Int
        if (LNReaderSession.isActive) {
            labels = LNReaderSession.chapters.map { it.name }
            index = LNReaderSession.currentIndex
        } else {
            labels = toc.map { it.label.orEmpty() }
            index = binding.novelReaderChapterSelect.selectedItemPosition
        }
        binding.novelReaderPrevChap.text = labels.getOrNull(index - 1).orEmpty()
        binding.novelReaderNextChap.text = labels.getOrNull(index + 1).orEmpty()
    }


    override fun onImageSelected(base64String: String) {
        scope.launch(Dispatchers.IO) {
            val base64Data = base64String.substringAfter(",")
            val imageBytes: ByteArray = Base64.decode(base64Data, Base64.DEFAULT)
            val imageFile = File(cacheDir, "/images/ln.jpg")

            imageFile.parentFile?.mkdirs()
            imageFile.createNewFile()

            FileOutputStream(imageFile).use { outputStream -> outputStream.write(imageBytes) }

            ImageViewDialog.newInstance(
                this@NovelReaderActivity,
                book.title,
                imageFile.toUri().toString()
            )
        }
    }


    override fun onTextSelectionModeChange(mode: Boolean) {
        // TODO: Show ui for adding annotations and notes
    }


    private var onVolumeUp: (() -> Unit)? = null
    private var onVolumeDown: (() -> Unit)? = null
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                    if (!defaultSettings.volumeButtons)
                        return false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    onVolumeUp?.invoke()
                    true
                } else false
            }

            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                    if (!defaultSettings.volumeButtons)
                        return false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    onVolumeDown?.invoke()
                    true
                } else false
            }

            else -> {
                super.dispatchKeyEvent(event)
            }
        }
    }


    fun applySettings() {
        saveReaderSettings("${settingsId}_current_settings", defaultSettings)
        hideBars()

        if (defaultSettings.useOledTheme) {
            themes.forEach { theme ->
                theme.darkBg = Color.parseColor("#000000")
            }
        }
        // A saved name that is not in the list is a real possibility — the book contributes its own
        // theme, so the list differs between books — and `first` would throw on it rather than fall
        // back. The name is corrected too, so the picker and the applied theme agree.
        currentTheme = themes
            .firstOrNull { it.name.equals(defaultSettings.currentThemeName, ignoreCase = true) }
            ?: themes.firstOrNull()?.also { defaultSettings.currentThemeName = it.name }

        when (defaultSettings.layout) {
            CurrentNovelReaderSettings.Layouts.PAGED -> {
                currentTheme?.flow = ReaderFlow.PAGINATED
            }

            CurrentNovelReaderSettings.Layouts.SCROLLED -> {
                currentTheme?.flow = ReaderFlow.SCROLLED
            }
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
        when (defaultSettings.dualPageMode) {
            CurrentReaderSettings.DualPageModes.No -> currentTheme?.maxColumnCount = 1
            CurrentReaderSettings.DualPageModes.Automatic -> currentTheme?.maxColumnCount = 2
            CurrentReaderSettings.DualPageModes.Force -> requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        currentTheme?.lineHeight = defaultSettings.lineHeight
        currentTheme?.gap = defaultSettings.margin
        currentTheme?.maxInlineSize = defaultSettings.maxInlineSize
        currentTheme?.maxBlockSize = defaultSettings.maxBlockSize
        currentTheme?.useDark = defaultSettings.useDarkTheme
        currentTheme?.justify = defaultSettings.justify
        currentTheme?.hyphenate = defaultSettings.hyphenation

        currentTheme?.let { binding.bookReader.setAppearance(it) }

        if (defaultSettings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // After the dual-page block above, which forces landscape when it is set to Force — a lock
        // applied before that would be overwritten by it, and one applied over it would fight it.
        if (defaultSettings.lockRotation &&
            defaultSettings.dualPageMode != CurrentReaderSettings.DualPageModes.Force
        ) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }


    // region Handle Controls
    private var isContVisible = false
    private var isAnimating = false
    private var goneTimer = Timer()
    private var controllerDuration by Delegates.notNull<Long>()
    private val overshoot = OvershootInterpolator(1.4f)

    fun gone() {
        goneTimer.cancel()
        goneTimer.purge()
        val timerTask: TimerTask = object : TimerTask() {
            override fun run() {
                if (!isContVisible) binding.novelReaderCont.post {
                    binding.novelReaderCont.visibility = View.GONE
                    isAnimating = false
                }
            }
        }
        goneTimer = Timer()
        goneTimer.schedule(timerTask, controllerDuration)
    }

    /**
     * What a single tap on the page means.
     *
     * Two narrow strips down the left and right edges turn pages; everything else brings the
     * controls up. The strips are kept deliberately small, and stop short of the top and bottom of
     * the screen, because reaching the controls is the more common thing to want and the bars are
     * what a tap up there or down there is aiming for.
     *
     * Measured against the reader view rather than the display: the touch arrives in the view's own
     * coordinates, so anything else can put the boundary in the wrong place — and does, on any
     * window that is not exactly the size of the screen.
     *
     * Paged layout only. A scrolled book has no pages to turn, so there every tap is for the
     * controls.
     */
    private fun handleTap(event: MotionEvent) {
        // With the controls already up, a tap puts them away rather than turning a page. Otherwise
        // reaching for a bar and missing it moves the page out from under you.
        if (isContVisible) {
            handleController()
            return
        }

        val width = binding.bookReader.width
        val height = binding.bookReader.height
        val edge = width * PAGE_TAP_EDGE / 100
        val margin = height * PAGE_TAP_VERTICAL_MARGIN / 100
        val inStrip = event.y > margin && event.y < height - margin
        val paged = defaultSettings.layout == CurrentNovelReaderSettings.Layouts.PAGED
        when {
            !inStrip -> handleController()
            // Moving to the next chapter is not paging, so it belongs to both layouts: a scrolled
            // chapter has nothing left to scroll at its end but the novel still goes on.
            // Forward belongs to both layouts: paged it turns a page, scrolled it scrolls on, and
            // at the end of either it moves to the next chapter.
            event.x > width - edge -> pageForward()
            !paged -> handleController()
            event.x < edge -> binding.bookReader.prev()
            else -> handleController()
        }
    }

    fun handleController(shouldShow: Boolean? = null) {
        if (!loaded) return

        if (!PrefManager.getVal<Boolean>(PrefName.ShowSystemBars)) {
            hideBars()
            applyNotchMargin()
        }

        shouldShow?.apply { isContVisible = !this }
        if (isContVisible) {
            isContVisible = false
            if (!isAnimating) {
                isAnimating = true
                ObjectAnimator.ofFloat(binding.novelReaderCont, "alpha", 1f, 0f)
                    .setDuration(controllerDuration).start()
                ObjectAnimator.ofFloat(binding.novelReaderBottomCont, "translationY", 0f, 128f)
                    .apply { interpolator = overshoot;duration = controllerDuration;start() }
                ObjectAnimator.ofFloat(binding.novelReaderTopLayout, "translationY", 0f, -128f)
                    .apply { interpolator = overshoot;duration = controllerDuration;start() }
            }
            gone()
        } else {
            isContVisible = true
            binding.novelReaderCont.visibility = View.VISIBLE
            ObjectAnimator.ofFloat(binding.novelReaderCont, "alpha", 0f, 1f)
                .setDuration(controllerDuration).start()
            ObjectAnimator.ofFloat(binding.novelReaderTopLayout, "translationY", -128f, 0f)
                .apply { interpolator = overshoot;duration = controllerDuration;start() }
            ObjectAnimator.ofFloat(binding.novelReaderBottomCont, "translationY", 128f, 0f)
                .apply { interpolator = overshoot;duration = controllerDuration;start() }
        }
    }
    // endregion Handle Controls


    private fun checkNotch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !PrefManager.getVal<Boolean>(PrefName.ShowSystemBars)) {
            val displayCutout = window.decorView.rootWindowInsets.displayCutout
            if (displayCutout != null) {
                if (displayCutout.boundingRects.size > 0) {
                    notchHeight = min(
                        displayCutout.boundingRects[0].width(),
                        displayCutout.boundingRects[0].height()
                    )
                    applyNotchMargin()
                }
            }
        }
    }


    private fun applyNotchMargin() {
        binding.novelReaderTopLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = notchHeight ?: return
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> loadReaderSettings(
        fileName: String,
        context: Context? = null,
        toast: Boolean = true
    ): T? {
        val a = context ?: currContext()
        try {
            if (a?.fileList() != null)
                if (fileName in a.fileList()) {
                    val fileIS: FileInputStream = a.openFileInput(fileName)
                    val objIS = ObjectInputStream(fileIS)
                    val data = objIS.readObject() as T
                    objIS.close()
                    fileIS.close()
                    return data
                }
        } catch (e: Exception) {
            if (toast) snackString(a?.getString(R.string.error_loading_data, fileName))
            //try to delete the file
            try {
                a?.deleteFile(fileName)
            } catch (e: Exception) {
                Injekt.get<CrashlyticsInterface>().log("Failed to delete file $fileName")
                Injekt.get<CrashlyticsInterface>().logException(e)
            }
            e.printStackTrace()
        }
        return null
    }

    private fun saveReaderSettings(fileName: String, data: Any?, context: Context? = null) {
        tryWith {
            val a = context ?: currContext()
            if (a != null) {
                val fos: FileOutputStream = a.openFileOutput(fileName, Context.MODE_PRIVATE)
                val os = ObjectOutputStream(fos)
                os.writeObject(data)
                os.close()
                fos.close()
            }
        }
    }

    private fun hideBars() {
        if (!PrefManager.getVal<Boolean>(PrefName.ShowSystemBars)) {
            hideSystemBars()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-apply immersive mode and notch padding when returning to foreground
        if (!PrefManager.getVal<Boolean>(PrefName.ShowSystemBars)) {
            this.hideSystemBars()
        } else {
            this.showSystemBars()
        }
        applyNotchMargin()
        // Force a layout pass on the reader view to recover from blank/damaged rendering
        tryWith { binding.bookReader.post { binding.bookReader.requestLayout(); binding.bookReader.invalidate() } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (!PrefManager.getVal<Boolean>(PrefName.ShowSystemBars)) this.hideSystemBars() else this.showSystemBars()
            applyNotchMargin()
        }
    }
}


/**
 * ⚠️ TEMPORARY HOTFIX ⚠️
 *
 * This is a hacky workaround to handle crashes in the deprecated ebookreader library.
 *
 * Current implementation:
 * - Uses reflection to access the private `scope` field in `EbookReaderView`.
 * - Replaces the existing `CoroutineScope` with a new one that includes a
 *   `CoroutineExceptionHandler`.
 * - Ensures that uncaught exceptions in coroutines are handled gracefully by showing a snackbar
 *   with error details.
 *
 * TODO:
 * - This is NOT a long-term solution
 * - The underlying library is archived and unmaintained
 * - Schedule migration to an actively maintained library
 * - Consider alternatives like https://github.com/readium/kotlin-toolkit
 */
fun EbookReaderView.useSafeScope(activity: Activity) {
    runCatching {
        val scopeField = javaClass.getDeclaredField("scope").apply { isAccessible = true }
        val currentScope = scopeField.get(this) as CoroutineScope
        val safeScope = CoroutineScope(
            SupervisorJob() +
                    currentScope.coroutineContext.minusKey(Job) +
                    scopeExceptionHandler(activity)
        )
        scopeField.set(this, safeScope)
    }.onFailure { e ->
        snackString(e.localizedMessage, activity, e.stackTraceToString())
    }
}

private fun scopeExceptionHandler(activity: Activity) = CoroutineExceptionHandler { _, e ->
    snackString(e.localizedMessage, activity, e.stackTraceToString())
}
