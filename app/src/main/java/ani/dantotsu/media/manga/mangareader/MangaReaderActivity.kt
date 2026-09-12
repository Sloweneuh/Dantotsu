package ani.dantotsu.media.manga.mangareader

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_PAGE_DOWN
import android.view.KeyEvent.KEYCODE_PAGE_UP
import android.view.KeyEvent.KEYCODE_VOLUME_DOWN
import android.view.KeyEvent.KEYCODE_VOLUME_UP
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.PopupMenu
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.math.MathUtils.clamp
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.GesturesListener
import ani.dantotsu.NoPaddingArrayAdapter
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.connections.handoff.HandoffBottomSheet
import ani.dantotsu.connections.handoff.HandoffPayload
import ani.dantotsu.media.screenshot.ScreenshotDialogFragment
import ani.dantotsu.media.screenshot.ScreenshotUtil
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.discord.RPCManager
import ani.dantotsu.connections.discord.RPC
import ani.dantotsu.connections.updateProgress
import ani.dantotsu.currContext
import ani.dantotsu.databinding.ActivityMangaReaderBinding
import ani.dantotsu.dp
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.hideSystemBars
import ani.dantotsu.showSystemBars
import ani.dantotsu.isOnline
import ani.dantotsu.logError
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.MediaSingleton
import ani.dantotsu.media.manga.MangaCache
import ani.dantotsu.media.manga.mangareader.BaseImageAdapter.Companion.loadBitmap
import ani.dantotsu.util.Logger
import ani.dantotsu.media.manga.translation.AutoTranslator
import ani.dantotsu.media.manga.translation.BlockEditorView
import ani.dantotsu.media.manga.translation.DrawnRegions
import ani.dantotsu.media.manga.translation.MtlSettings
import ani.dantotsu.media.manga.translation.PageTextDetector
import ani.dantotsu.media.manga.translation.ScoredBlock
import ani.dantotsu.media.manga.translation.BlockVerdict
import ani.dantotsu.databinding.DialogDrawBoxesBinding
import ani.dantotsu.media.manga.translation.PageTranslationPipeline
import ani.dantotsu.media.manga.translation.SourceScript
import ani.dantotsu.media.manga.translation.TextScript
import ani.dantotsu.media.manga.translation.TranslatedPage
import ani.dantotsu.media.manga.translation.TranslatedPages
import ani.dantotsu.media.manga.translation.TranslationOverlayView
import ani.dantotsu.parsers.DynamicMangaParser
import ani.dantotsu.parsers.OfflineMangaParser
import ani.dantotsu.util.choiceBottomSheet
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.others.ImageViewDialog
import ani.dantotsu.parsers.HMangaSources
import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.px
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.settings.CurrentReaderSettings.Companion.applyWebtoon
import ani.dantotsu.settings.CurrentReaderSettings.Directions.BOTTOM_TO_TOP
import ani.dantotsu.settings.CurrentReaderSettings.Directions.LEFT_TO_RIGHT
import ani.dantotsu.settings.CurrentReaderSettings.Directions.RIGHT_TO_LEFT
import ani.dantotsu.settings.CurrentReaderSettings.Directions.TOP_TO_BOTTOM
import ani.dantotsu.settings.CurrentReaderSettings.DualPageModes.Automatic
import ani.dantotsu.settings.CurrentReaderSettings.DualPageModes.Force
import ani.dantotsu.settings.CurrentReaderSettings.DualPageModes.No
import ani.dantotsu.settings.CurrentReaderSettings.Layouts.CONTINUOUS_PAGED
import ani.dantotsu.settings.CurrentReaderSettings.Layouts.PAGED
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.showSystemBarsRetractView
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.tryWith
import ani.dantotsu.util.customAlertDialog
import com.alexvasilkov.gestures.views.GestureFrameLayout
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileInputStream
import java.io.InvalidClassException
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.properties.Delegates

/**
 * Pages past the last visible one that automatic translation works ahead on.
 *
 * Small on purpose. Every page ahead is a request that may never be read, and the wait for the page
 * actually on screen is what the reader notices — a deep lookahead makes that wait longer, not
 * shorter, because the queue in front of it is longer.
 */
private const val AUTO_LOOKAHEAD = 2

/** Failures in a row that end an automatic run. See [MangaReaderActivity.autoFailures]. */
private const val AUTO_FAILURE_LIMIT = 3

/** Outline of a box the reader drew by hand. See [MangaReaderActivity.drawBoxes]. */
private const val DRAWN_BOX_COLOR = 0xFF2196F3.toInt()

class MangaReaderActivity : AppCompatActivity() {
    private val mangaCache = Injekt.get<MangaCache>()

    private lateinit var binding: ActivityMangaReaderBinding
    private val model: MediaDetailsViewModel by viewModels()
    private val scope = lifecycleScope

    var defaultSettings = CurrentReaderSettings()

    private lateinit var media: Media
    private lateinit var chapter: MangaChapter
    private lateinit var chapters: MutableMap<String, MangaChapter>
    private lateinit var chaptersArr: List<String>
    private lateinit var chaptersTitleArr: ArrayList<String>
    private var currentChapterIndex = 0

    private var isContVisible = false
    private var showProgressDialog = true

    private var maxChapterPage = 0L
    private var currentChapterPage = 0L

    private var notchHeight: Int? = null

    private var imageAdapter: BaseImageAdapter? = null
    private var continuousAdapter: ContinuousChapterAdapter? = null

    /**
     * Loads pages ahead of the viewport for the scrolling reader; see [PagePrefetcher] for why the
     * layout manager's own prefetch isn't enough. Null in paged mode, where ViewPager2 does it.
     */
    private var prefetcher: PagePrefetcher? = null
    private val isContinuousMultiChapter: Boolean
        get() = PrefManager.getVal(PrefName.ContinuousMultiChapter)

    var sliding = false
    var isAnimating = false
    private var autoscrollTimer: Timer? = null
    var autoscrollOn = false
    private var autoscrollLastFrameNanos = 0L
    private var autoscrollAccumulatedPx = 0f
    private val autoscrollFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!autoscrollOn) return
            if (autoscrollLastFrameNanos != 0L) {
                // Cap elapsed to 50 ms to avoid a large jump after a frame drop or resume
                val elapsedSec = minOf(
                    (frameTimeNanos - autoscrollLastFrameNanos) / 1_000_000_000f,
                    0.05f
                )
                val speed = PrefManager.getVal<Float>(PrefName.AutoScrollSpeed)
                autoscrollAccumulatedPx += speed * 240f * elapsedSec
                val scroll = autoscrollAccumulatedPx.toInt()
                if (scroll > 0) {
                    autoscrollAccumulatedPx -= scroll
                    binding.mangaReaderRecycler.scrollBy(0, scroll)
                }
            }
            autoscrollLastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val directionRLBT
        get() = defaultSettings.direction == RIGHT_TO_LEFT
                || defaultSettings.direction == BOTTOM_TO_TOP
    private val directionPagedBT
        get() = defaultSettings.layout == CurrentReaderSettings.Layouts.PAGED
                && defaultSettings.direction == CurrentReaderSettings.Directions.BOTTOM_TO_TOP

    override fun onAttachedToWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !PrefManager.getVal<Boolean>(PrefName.ShowSystemBars)) {
            val displayCutout = window.decorView.rootWindowInsets.displayCutout
            if (displayCutout != null) {
                if (displayCutout.boundingRects.size > 0) {
                    notchHeight = min(
                        displayCutout.boundingRects[0].width(),
                        displayCutout.boundingRects[0].height()
                    )
                    checkNotch()
                }
            }
        }
        super.onAttachedToWindow()
    }
    
    override fun onResume() {
        super.onResume()
        // Re-apply immersive mode and notch padding when returning to foreground
        if (!PrefManager.getVal<Boolean>(PrefName.ShowSystemBars)) this.hideSystemBars() else this.showSystemBars()
        checkNotch()
        // Force a layout pass on pager/recycler to recover from blank/damaged rendering
        tryWith {
            binding.mangaReaderPager.post { binding.mangaReaderPager.requestLayout(); binding.mangaReaderPager.invalidate() }
            binding.mangaReaderRecycler.post { binding.mangaReaderRecycler.requestLayout(); binding.mangaReaderRecycler.invalidate() }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (!PrefManager.getVal<Boolean>(PrefName.ShowSystemBars)) this.hideSystemBars() else this.showSystemBars()
            checkNotch()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // These config changes (uiMode/density/etc.) are now handled in-place via the
        // manifest's android:configChanges instead of recreating the activity. Recreation on
        // resume was leaving the reader blank with broken status/navigation-bar insets, so we
        // simply re-assert the immersive/inset state here rather than tearing everything down.
        if (!PrefManager.getVal<Boolean>(PrefName.ShowSystemBars)) this.hideSystemBars() else this.showSystemBars()
        checkNotch()
        tryWith {
            binding.mangaReaderPager.post { binding.mangaReaderPager.requestLayout(); binding.mangaReaderPager.invalidate() }
            binding.mangaReaderRecycler.post { binding.mangaReaderRecycler.requestLayout(); binding.mangaReaderRecycler.invalidate() }
        }
    }

    private fun checkNotch() {
        binding.mangaReaderTopLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = notchHeight ?: return
        }
    }

    private fun hideSystemBars() {
        if (PrefManager.getVal(PrefName.ShowSystemBars))
            showSystemBarsRetractView()
        else
            hideSystemBarsExtendView()
    }

    private fun toggleAutoscroll() {
        // Only allow autoscroll when using Continuous layout
        if (defaultSettings.layout != CurrentReaderSettings.Layouts.CONTINUOUS) {
            // Inform user and do not toggle
            try { snackString(getString(R.string.autoscroll_only_continuous)) } catch (_: Exception) {}
            return
        }
        if (autoscrollOn) stopAutoscroll() else startAutoscroll()
    }

    fun startAutoscroll() {
        autoscrollOn = true
        binding.mangaReaderAutoscroll.setImageResource(R.drawable.ic_round_pause_24)
        autoscrollTimer?.cancel()
        val speed = PrefManager.getVal<Float>(PrefName.AutoScrollSpeed)
        if (defaultSettings.layout == CurrentReaderSettings.Layouts.PAGED) {
            // Higher `speed` -> faster. Use a tighter base so max values are noticeably faster.
            val interval = (1000L / maxOf(0.1f, speed)).toLong()
            autoscrollTimer = Timer()
            autoscrollTimer?.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    binding.mangaReaderPager.post {
                        try {
                            if (directionRLBT) {
                                if (binding.mangaReaderPager.currentItem > 0) binding.mangaReaderPager.currentItem = binding.mangaReaderPager.currentItem - 1
                            } else {
                                if (binding.mangaReaderPager.currentItem < binding.mangaReaderPager.adapter?.itemCount?.minus(1) ?: 0) binding.mangaReaderPager.currentItem = binding.mangaReaderPager.currentItem + 1
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            }, interval, interval)
        } else {
            // Continuous: vsync-tied smooth scroll via Choreographer
            autoscrollLastFrameNanos = 0L
            autoscrollAccumulatedPx = 0f
            Choreographer.getInstance().postFrameCallback(autoscrollFrameCallback)
        }
    }

    fun stopAutoscroll() {
        autoscrollOn = false
        autoscrollTimer?.cancel()
        autoscrollTimer = null
        Choreographer.getInstance().removeFrameCallback(autoscrollFrameCallback)
        autoscrollLastFrameNanos = 0L
        autoscrollAccumulatedPx = 0f
        binding.mangaReaderAutoscroll.setImageResource(R.drawable.ic_round_play_arrow_24)
    }

    fun updateAutoscrollSpeed(newSpeed: Float) {
        PrefManager.setVal(PrefName.AutoScrollSpeed, newSpeed)
        if (autoscrollOn) {
            if (defaultSettings.layout == CurrentReaderSettings.Layouts.PAGED) {
                // Paged mode uses a timer interval, so must restart
                autoscrollTimer?.cancel()
                startAutoscroll()
            }
            // Continuous mode: Choreographer reads speed on every frame, no restart needed
        }
    }

    override fun onDestroy() {
        // Diagnostic: isChangingConfigurations=true => torn down for a config change (will be
        // recreated); isFinishing=true => we (or back) closed it. Neither => process is going away.
        ani.dantotsu.util.Logger.log("MangaReaderActivity.onDestroy (isChangingConfigurations=$isChangingConfigurations, isFinishing=$isFinishing, cacheSize=${mangaCache.size()})")
        autoscrollTimer?.cancel()
        prefetcher?.cancel()
        prefetcher = null
        // The reader is the only thing that fills MangaCache, but the cache outlives it: it is an
        // application-scoped singleton budgeted at a quarter of the heap, so left alone it holds that
        // quarter until the process dies and the OOM lands on whatever the user browses next.
        // Pixels only, and only on a real finish - on a config change the activity is coming straight
        // back and would only redecode, and the ImageData half is what the reload path the old
        // mangaCache.clear() here was removed to protect actually reads.
        if (isFinishing) mangaCache.evictBitmaps()
        RPCManager.clearPresence(this)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Diagnostic: reveals whether the activity is being recreated (savedInstanceState != null
        // means the system tore it down and is rebuilding it) vs a fresh open. Pair with the
        // onDestroy log below to tell config-change recreation from process death / finish.
        ani.dantotsu.util.Logger.log("MangaReaderActivity.onCreate (recreated=${savedInstanceState != null})")
        ThemeManager(this).applyTheme()
        binding = ActivityMangaReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.mangaReaderBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }



        defaultSettings = loadReaderSettings("reader_settings") ?: defaultSettings

        onBackPressedDispatcher.addCallback(this) {
            if (!::media.isInitialized) {
                finish()
                return@addCallback
            }
            val chapter =
                (MediaNameAdapter.findChapterNumber(media.manga!!.selectedChapter!!.number)
                    ?.minus(1L) ?: 0).toString()
            if (chapter == "0.0" && PrefManager.getVal(PrefName.ChapterZeroReader)
                // Not asking individually or incognito
                && !showProgressDialog && !PrefManager.getVal<Boolean>(PrefName.Incognito)
                // A tracking choice is actually on record (or "ask individually" is off)
                && mayTrackProgressSilently()
                // Not ...opted out ...already? Somehow?
                && PrefManager.getCustomVal("${media.id}_save_progress", true)
                //  Allowing adult (Hentai) updates, or not an adult title
                && if (media.isAdult) PrefManager.getVal(PrefName.UpdateForHReader) else true
            ) {
                updateProgress(media, chapter)
                finish()
            } else {
                progress { finish() }
            }
        }

        controllerDuration = (PrefManager.getVal<Float>(PrefName.AnimationSpeed) * 200).toLong()

        hideSystemBars()

        var pageSliderTimer = Timer()
        fun pageSliderHide() {
            pageSliderTimer.cancel()
            pageSliderTimer.purge()
            val timerTask: TimerTask = object : TimerTask() {
                override fun run() {
                    binding.mangaReaderCont.post {
                        sliding = false
                        handleController(false)
                    }
                }
            }
            pageSliderTimer = Timer()
            pageSliderTimer.schedule(timerTask, 3000)
        }

        binding.mangaReaderSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                sliding = true
                val pageOffset = if (defaultSettings.direction == CurrentReaderSettings.Directions.BOTTOM_TO_TOP) {
                    (maxChapterPage.toInt() - value.toInt()) / (dualPage { 2 } ?: 1)
                } else {
                    (value.toInt() - 1) / (dualPage { 2 } ?: 1)
                }
                
                val startPos = if (isContinuousMultiChapter) {
                    continuousAdapter?.getChapterStartPosition(currentChapterIndex) ?: 0
                } else 0
                val targetPos = startPos + pageOffset

                if (defaultSettings.layout != PAGED) {
                    binding.mangaReaderRecycler.scrollToPosition(targetPos)
                } else {
                    binding.mangaReaderPager.currentItem = targetPos
                }
                pageSliderHide()
            }
        }

        // The reader's media lives only in volatile in-memory state (the ViewModel and
        // MediaSingleton). After the OS kills the app process (common on WSA / occasional
        // background kill on phones) Android recreates this activity with both of them empty.
        // Rather than leaving a half-inflated, content-less, inset-less "zombie" reader that
        // the user can only escape with the back button, close cleanly and let the restored
        // MediaDetailsActivity below us take over. The per-chapter page position is already
        // persisted to PrefManager, so reopening the chapter resumes where they left off.
        fun bailStaleReader(reason: String) {
            ani.dantotsu.util.Logger.log(
                "MangaReaderActivity: $reason — closing reader (likely process death / state loss)"
            )
            finish()
        }

        media = if (model.getMedia().value == null)
            try {
                //(intent.getSerialized("media")) ?: return
                MediaSingleton.media ?: return bailStaleReader("no media in singleton")
            } catch (e: Exception) {
                logError(e)
                return bailStaleReader("exception resolving media")
            } finally {
                MediaSingleton.media = null
            }
        else model.getMedia().value ?: return bailStaleReader("viewModel media is null")
        model.setMedia(media)
        @Suppress("UNCHECKED_CAST")
        val list = (PrefManager.getNullableCustomVal(
            "continueMangaList",
            listOf<Int>(),
            List::class.java
        ) as List<Int>).toMutableList()
        if (list.contains(media.id)) list.remove(media.id)
        list.add(media.id)

        PrefManager.setCustomVal("continueMangaList", list)
        // Same event, recorded once more where anime and manga share an ordering — see ContinueHistory.
        // muSeriesId matters: for a MangaUpdates series media.id is only a truncated key, not an
        // AniList id, and anything that looks it up on AniList would resolve the wrong series or none.
        ani.dantotsu.widgets.ContinueHistory.record(
            media.id, isAnime = false, muSeriesId = media.muSeriesId
        )
        ani.dantotsu.widgets.WidgetRefresh.onContinueChanged(this)
        if (PrefManager.getVal(PrefName.AutoDetectWebtoon) && media.countryOfOrigin != "JP") applyWebtoon(
            defaultSettings
        )
        defaultSettings = loadReaderSettings("${media.id}_current_settings") ?: defaultSettings

        chapters = media.manga?.chapters ?: return bailStaleReader("media has no chapters")
        chapter = media.manga?.selectedChapter?.uniqueNumber()?.let { chapters[it] }
            ?: return bailStaleReader("selected chapter missing after restore")

        val extParser = if (media.id < 0) MediaSingleton.extensionParser else null
        if (extParser != null) {
            model.mangaReadSources = object : ani.dantotsu.parsers.MangaReadSources() {
                override val list: List<ani.dantotsu.Lazier<ani.dantotsu.parsers.BaseParser>> =
                    listOf(ani.dantotsu.Lazier({ extParser }, extParser.name))
            }
            media.selected!!.sourceIndex = 0
        } else {
            model.mangaReadSources = if (media.isAdult) HMangaSources else MangaSources
            if (model.mangaReadSources!!.names.isEmpty()) {
                //try to reload sources
                try {
                    val mangaSources = MangaSources
                    val scope = lifecycleScope
                    scope.launch(Dispatchers.IO) {
                        mangaSources.init(
                            Injekt.get<MangaExtensionManager>().installedExtensionsFlow
                        )
                    }
                    model.mangaReadSources = mangaSources
                } catch (e: Exception) {
                    Injekt.get<CrashlyticsInterface>().logException(e)
                    logError(e)
                }
            }
            //check that index is not out of bounds (crash fix)
            if (media.selected!!.sourceIndex >= model.mangaReadSources!!.names.size) {
                media.selected!!.sourceIndex = 0
            }
        }
        binding.mangaReaderSource.isVisible = PrefManager.getVal(PrefName.ShowSource)
        binding.mangaReaderSource.text =
            model.mangaReadSources!!.names[media.selected!!.sourceIndex]

        binding.mangaReaderTitle.text = media.userPreferredName

        chaptersArr = chapters.keys.toList()
        currentChapterIndex = chaptersArr.indexOf(media.manga!!.selectedChapter!!.uniqueNumber())

        chaptersTitleArr = arrayListOf()
        chapters.forEach {
            val chapter = it.value
            chaptersTitleArr.add("${chapter.number}${if (!chapter.title.isNullOrEmpty() && chapter.title != "null") " : " + chapter.title else ""}")
        }

        showProgressDialog =
            if (PrefManager.getVal(PrefName.AskIndividualReader)) PrefManager.getCustomVal(
                "${media.id}_progressDialog",
                true
            ) else false

        //Chapter Change

    
        fun change(index: Int) {
            ani.dantotsu.util.Logger.log("Chapter change to index $index (cache size: ${mangaCache.size()})")
            // Don't clear cache - let LRU manage memory and allow extension client to work on reloads
            // mangaCache.clear()
            if (media.id >= 0) PrefManager.setCustomVal(
                "${media.id}_${chaptersArr[currentChapterIndex]}",
                currentChapterPage
            )
            ChapterLoaderDialog.newInstance(chapters[chaptersArr[index]]!!)
                .show(supportFragmentManager, "dialog")
        }

        //ChapterSelector
        binding.mangaReaderChapterSelect.adapter =
            NoPaddingArrayAdapter(this, R.layout.item_dropdown, chaptersTitleArr)
        binding.mangaReaderChapterSelect.setSelection(currentChapterIndex)
        binding.mangaReaderChapterSelect.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?,
                    p1: View?,
                    position: Int,
                    p3: Long
                ) {
                    if (position != currentChapterIndex) change(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>) {}
            }

        binding.mangaReaderSettings.setSafeOnClickListener {
            ReaderSettingsDialogFragment.newInstance().show(supportFragmentManager, "settings")
        }

        // Screenshot of the current page(s). The reader chrome is a sibling overlay, so drawing
        // the page container captures the pages (at their current zoom/pan) without any buttons.
        fun takeScreenshot() {
            val bitmap = ScreenshotUtil.captureView(binding.mangaReaderSwipy)
            if (bitmap == null) {
                snackString(getString(R.string.screenshot_failed))
                return
            }
            val sourceName = model.mangaReadSources?.names?.getOrNull(media.selected!!.sourceIndex)
            val sourceLabel = listOfNotNull(
                sourceName,
                chapter.scanlator?.takeIf { it.isNotBlank() }
            ).joinToString(" · ").ifBlank { null }
            ScreenshotDialogFragment.newInstance(
                screenshot = bitmap,
                title = media.userPreferredName,
                titleOptions = media.mainTitleOptions(),
                coverUrl = media.cover,
                numberLabel = chapter.number,
                progressLabel = getString(R.string.handoff_page_label, "$currentChapterPage/$maxChapterPage"),
                sourceLabel = sourceLabel,
                isAnime = false,
            ).show(supportFragmentManager, "screenshot")
        }

        // "Continue on another device" — extension-only media (id < 0) isn't linked to AniList/
        // MangaUpdates, so it can't be re-fetched elsewhere; the menu item is hidden for it.
        fun sendHandoff() {
            scope.launch {
                // The matched extension entry rides along so the receiver loads the chapter list
                // directly instead of re-searching the source by title (which can come back empty).
                val sourceMedia = withContext(Dispatchers.IO) {
                    runCatching {
                        model.mangaReadSources?.get(media.selected!!.sourceIndex)
                            ?.loadSavedShowResponse(media.id)
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
                        sourceName = model.mangaReadSources?.names?.getOrNull(media.selected!!.sourceIndex),
                        number = chapter.number,
                        page = currentChapterPage,
                        trackProgress = PrefManager.getCustomVal("${media.id}_save_progress", true),
                        muSeriesId = media.muSeriesId,
                        sourceMedia = HandoffPayload.encodeShowResponse(sourceMedia),
                    )
                ).show(supportFragmentManager, "handoff")
            }
        }

        // Portrait has no room for a separate screenshot button, so both actions live in a "more"
        // dropdown that replaces the old cast button.
        binding.mangaReaderMore.setSafeOnClickListener {
            val popup = PopupMenu(this, binding.mangaReaderMore)
            popup.menuInflater.inflate(R.menu.manga_reader_more, popup.menu)
            popup.menu.findItem(R.id.action_screenshot)
                .setIcon(ScreenshotUtil.screenshotIcon(this))
            popup.menu.findItem(R.id.action_handoff).isVisible = media.id >= 0
            val trackProgressItem = popup.menu.findItem(R.id.action_track_progress)
            trackProgressItem.isVisible = media.id >= 0
            trackProgressItem.isChecked =
                PrefManager.getCustomVal("${media.id}_save_progress", true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) popup.setForceShowIcon(true)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_screenshot -> { takeScreenshot(); true }
                    R.id.action_handoff -> { sendHandoff(); true }
                    R.id.action_track_progress -> {
                        val enabled = !PrefManager.getCustomVal("${media.id}_save_progress", true)
                        PrefManager.setCustomVal("${media.id}_save_progress", enabled)
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

        binding.mangaReaderAutoscroll.setOnClickListener {
            toggleAutoscroll()
        }

        // Start autoscroll if enabled in preferences and layout allows it
        if (PrefManager.getVal(PrefName.AutoScrollEnabled)) {
            if (defaultSettings.layout == CurrentReaderSettings.Layouts.CONTINUOUS) {
                startAutoscroll()
            } else {
                // disable the stored preference if layout doesn't support autoscroll
                PrefManager.setVal(PrefName.AutoScrollEnabled, false)
            }
        }

        //Next Chapter
        binding.mangaReaderNextChap.setOnClickListener {
            binding.mangaReaderNextChapter.performClick()
        }
        fun showGapWarningThenRun(targetIndex: Int, action: () -> Unit) {
            val missing = countMissingChapters(currentChapterIndex, targetIndex)
            val isNext = targetIndex > currentChapterIndex
            if (missing > 0 && isNext) {
                val title = if (missing == 1) getString(R.string.chapter_gap_warning_title)
                            else getString(R.string.chapter_gap_warning_title_plural)
                val message = if (missing == 1) getString(R.string.chapter_gap_warning_message_single)
                              else getString(R.string.chapter_gap_warning_message, missing)
                customAlertDialog().apply {
                    setTitle(title)
                    setMessage(message)
                    setPosButton(R.string.ok) { action() }
                    setNegButton(R.string.cancel)
                    show()
                }
            } else {
                action()
            }
        }

        binding.mangaReaderNextChapter.setOnClickListener {
            if (directionRLBT) {
                if (currentChapterIndex > 0) showGapWarningThenRun(currentChapterIndex - 1) {
                    change(currentChapterIndex - 1)
                }
                else snackString(getString(R.string.first_chapter))
            } else {
                if (chaptersArr.size > currentChapterIndex + 1) showGapWarningThenRun(currentChapterIndex + 1) {
                    progress { change(currentChapterIndex + 1) }
                }
                else snackString(getString(R.string.next_chapter_not_found))
            }
        }
        //Prev Chapter
        binding.mangaReaderPrevChap.setOnClickListener {
            binding.mangaReaderPreviousChapter.performClick()
        }
        binding.mangaReaderPreviousChapter.setOnClickListener {
            if (directionRLBT) {
                if (chaptersArr.size > currentChapterIndex + 1) showGapWarningThenRun(currentChapterIndex + 1) {
                    progress { change(currentChapterIndex + 1) }
                }
                else snackString(getString(R.string.next_chapter_not_found))
            } else {
                if (currentChapterIndex > 0) showGapWarningThenRun(currentChapterIndex - 1) {
                    change(currentChapterIndex - 1)
                }
                else snackString(getString(R.string.first_chapter))
            }
        }

        model.getMangaChapter().observe(this) { chap ->
            if (chap != null) {
                chapter = chap
                media.manga!!.selectedChapter = chapter
                media.selected = model.loadSelected(media)
                PrefManager.setCustomVal("${media.id}_current_chp", chap.number)
                currentChapterIndex = chaptersArr.indexOf(chap.uniqueNumber())
                binding.mangaReaderChapterSelect.setSelection(currentChapterIndex)
                updateChapterNavigationText()
                applySettings()
                val context = this
                val offline: Boolean = PrefManager.getVal(PrefName.OfflineMode)
                val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
                val rpcenabled: Boolean = PrefManager.getVal(PrefName.rpcEnabled)
                if ((isOnline(context) && !offline) && Discord.token != null && !incognito && rpcenabled) {
                    lifecycleScope.launch {
                        val isExtension = media.id < 0
                        val buttons = mutableListOf<RPC.Link>()
                        if (!isExtension) {
                            val muUrl = if (media.muSeriesId != null) {
                                media.shareLink?.takeIf { it.contains("mangaupdates") }
                                    ?: "https://www.mangaupdates.com/series/${media.muSeriesId!!.toString(36)}"
                            } else null
                            muUrl?.let { buttons.add(RPC.Link("View on MangaUpdates", it)) }
                            buttons.add(RPC.Link("View Manga", "https://anilist.co/manga/${media.id}/"))
                            media.idMAL?.let {
                                buttons.add(RPC.Link("View on MyAnimeList", "https://myanimelist.net/manga/$it"))
                            }
                        }
                        val rpcData = RPC.Companion.RPCData(
                            applicationId = Discord.application_Id,
                            type = RPC.Type.WATCHING,
                            activityName = media.userPreferredName,
                            details = chap.title?.takeIf { it.isNotEmpty() } ?: chap.number,
                            state = "Chapter ${chap.number}/${media.manga?.totalChapters ?: "??"}",
                            largeImage = media.cover?.let { cover ->
                                RPC.Link(media.userPreferredName, cover)
                            },
                            smallImage = null,
                            buttons = buttons
                        )
                        RPCManager.setPresence(context, rpcData)
                    }
                }
            }
        }



        scope.launch(Dispatchers.IO) {
            model.loadMangaChapterImages(
                chapter,
                media.selected!!
            )
        }
    }

    private val snapHelper = PagerSnapHelper()

    fun <T> dualPage(callback: () -> T): T? {
        return when (defaultSettings.dualPageMode) {
            No -> null
            Automatic -> {
                val orientation = resources.configuration.orientation
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) callback.invoke()
                else null
            }

            Force -> callback.invoke()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun applySettings() {

        saveReaderSettings("${media.id}_current_settings", defaultSettings)
        hideSystemBars()

        // Reset multi-chapter tracking state
        lastTrackedChapterIndex = -1
        multiChapterLoading = false

        // Show autoscroll control only for Continuous layout
        binding.mangaReaderAutoscroll.visibility = if (defaultSettings.layout == CurrentReaderSettings.Layouts.CONTINUOUS) View.VISIBLE else View.GONE
        if (autoscrollOn && defaultSettings.layout != CurrentReaderSettings.Layouts.CONTINUOUS) {
            // stop and clear preference when switching away from continuous
            stopAutoscroll()
            PrefManager.setVal(PrefName.AutoScrollEnabled, false)
        }

        //true colors
        SubsamplingScaleImageView.setPreferredBitmapConfig(
            if (defaultSettings.trueColors) Bitmap.Config.ARGB_8888
            else Bitmap.Config.RGB_565
        )

        //keep screen On
        if (defaultSettings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        //lock screen rotation
        requestedOrientation = if (defaultSettings.lockRotation)
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        else
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        binding.mangaReaderPager.unregisterOnPageChangeCallback(pageChangeCallback)

        currentChapterPage = if (media.id >= 0) PrefManager.getCustomVal("${media.id}_${chapter.number}", 1L) else 1L

        val chapImages = if (directionPagedBT) {
            chapter.images().reversed()
        } else {
            chapter.images()
        }

        maxChapterPage = 0
        if (chapImages.isNotEmpty()) {
            maxChapterPage = chapImages.size.toLong()
            if (media.id >= 0) PrefManager.setCustomVal("${media.id}_${chapter.number}_max", maxChapterPage)

            imageAdapter =
                dualPage { DualPageAdapter(this, chapter) } ?: ImageAdapter(this, chapter)

            if (chapImages.size > 1) {
                binding.mangaReaderSlider.apply {
                    visibility = View.VISIBLE
                    valueTo = maxChapterPage.toFloat()
                    value = clamp(currentChapterPage.toFloat(), 1f, valueTo)
                }
            } else {
                binding.mangaReaderSlider.visibility = View.GONE
            }
            binding.mangaReaderPageNumber.text =
                if (defaultSettings.hidePageNumbers) "" else "${currentChapterPage}/$maxChapterPage"

        }

        val currentPage = if (directionPagedBT) {
            maxChapterPage - currentChapterPage + 1
        } else {
            currentChapterPage
        }.toInt()

        if ((defaultSettings.direction == TOP_TO_BOTTOM || defaultSettings.direction == BOTTOM_TO_TOP)) {
            binding.mangaReaderSwipy.vertical = true
            if (defaultSettings.direction == TOP_TO_BOTTOM) {
                binding.mangaReaderNextChap.text =
                    chaptersTitleArr.getOrNull(currentChapterIndex + 1) ?: ""
                binding.mangaReaderPrevChap.text =
                    chaptersTitleArr.getOrNull(currentChapterIndex - 1) ?: ""
                binding.BottomSwipeText.text = chaptersTitleArr.getOrNull(currentChapterIndex + 1)
                    ?: getString(R.string.no_chapter)
                binding.TopSwipeText.text = chaptersTitleArr.getOrNull(currentChapterIndex - 1)
                    ?: getString(R.string.no_chapter)
                binding.mangaReaderSwipy.onTopSwiped = {
                    binding.mangaReaderPreviousChapter.performClick()
                }
                binding.mangaReaderSwipy.onBottomSwiped = {
                    binding.mangaReaderNextChapter.performClick()
                }
            } else {
                binding.mangaReaderNextChap.text =
                    chaptersTitleArr.getOrNull(currentChapterIndex - 1) ?: ""
                binding.mangaReaderPrevChap.text =
                    chaptersTitleArr.getOrNull(currentChapterIndex + 1) ?: ""
                binding.BottomSwipeText.text = chaptersTitleArr.getOrNull(currentChapterIndex - 1)
                    ?: getString(R.string.no_chapter)
                binding.TopSwipeText.text = chaptersTitleArr.getOrNull(currentChapterIndex + 1)
                    ?: getString(R.string.no_chapter)
                binding.mangaReaderSwipy.onTopSwiped = {
                    binding.mangaReaderPreviousChapter.performClick()
                }
                binding.mangaReaderSwipy.onBottomSwiped = {
                    binding.mangaReaderNextChapter.performClick()
                }
            }
            binding.mangaReaderSwipy.topBeingSwiped = { value ->
                binding.TopSwipeContainer.apply {
                    alpha = value
                    translationY = -height.dp * (1 - min(value, 1f))
                }
            }
            binding.mangaReaderSwipy.bottomBeingSwiped = { value ->
                binding.BottomSwipeContainer.apply {
                    alpha = value
                    translationY = height.dp * (1 - min(value, 1f))
                }
            }
        } else {
            binding.mangaReaderSwipy.vertical = false
            if (defaultSettings.direction == RIGHT_TO_LEFT) {
                binding.mangaReaderNextChap.text =
                    chaptersTitleArr.getOrNull(currentChapterIndex - 1) ?: ""
                binding.mangaReaderPrevChap.text =
                    chaptersTitleArr.getOrNull(currentChapterIndex + 1) ?: ""
                binding.LeftSwipeText.text = chaptersTitleArr.getOrNull(currentChapterIndex + 1)
                    ?: getString(R.string.no_chapter)
                binding.RightSwipeText.text = chaptersTitleArr.getOrNull(currentChapterIndex - 1)
                    ?: getString(R.string.no_chapter)
            } else {
                binding.mangaReaderNextChap.text =
                    chaptersTitleArr.getOrNull(currentChapterIndex + 1) ?: ""
                binding.mangaReaderPrevChap.text =
                    chaptersTitleArr.getOrNull(currentChapterIndex - 1) ?: ""
                binding.LeftSwipeText.text = chaptersTitleArr.getOrNull(currentChapterIndex - 1)
                    ?: getString(R.string.no_chapter)
                binding.RightSwipeText.text = chaptersTitleArr.getOrNull(currentChapterIndex + 1)
                    ?: getString(R.string.no_chapter)
            }
            binding.mangaReaderSwipy.onLeftSwiped = {
                binding.mangaReaderPreviousChapter.performClick()
            }
            binding.mangaReaderSwipy.leftBeingSwiped = { value ->
                binding.LeftSwipeContainer.apply {
                    alpha = value
                    translationX = -width.dp * (1 - min(value, 1f))
                }
            }
            binding.mangaReaderSwipy.onRightSwiped = {
                binding.mangaReaderNextChapter.performClick()
            }
            binding.mangaReaderSwipy.rightBeingSwiped = { value ->
                binding.RightSwipeContainer.apply {
                    alpha = value
                    translationX = width.dp * (1 - min(value, 1f))
                }
            }
        }

        // In continuous multi-chapter mode, disable swipy chapter changing — chapters are loaded by scrolling
        binding.mangaReaderSwipy.isEnabled = !isContinuousMultiChapter

        if (defaultSettings.layout != PAGED) {

            binding.mangaReaderRecyclerContainer.visibility = View.VISIBLE
            binding.mangaReaderRecyclerContainer.controller.settings.isRotationEnabled =
                defaultSettings.rotation

            val detector = GestureDetectorCompat(this, object : GesturesListener() {
                override fun onLongPress(e: MotionEvent) {
                    if (binding.mangaReaderRecycler.findChildViewUnder(e.x, e.y).let { child ->
                            child ?: return@let false
                            val pos = binding.mangaReaderRecycler.getChildAdapterPosition(child)
                            val callback: (ImageViewDialog) -> Unit = { dialog ->
                                lifecycleScope.launch {
                                    if (isContinuousMultiChapter) {
                                        continuousAdapter?.loadImage(pos, child as GestureFrameLayout)
                                    } else {
                                        imageAdapter?.loadImage(
                                            pos,
                                            child as GestureFrameLayout
                                        )
                                    }
                                }
                                binding.mangaReaderRecycler.performHapticFeedback(
                                    HapticFeedbackConstants.LONG_PRESS
                                )
                                dialog.dismiss()
                            }
                            if (!isContinuousMultiChapter) {
                                dualPage {
                                    val page =
                                        chapter.dualPages().getOrNull(pos) ?: return@dualPage false
                                    val nextPage = page.second
                                    if (defaultSettings.direction != LEFT_TO_RIGHT && nextPage != null)
                                        onImageLongClicked(pos * 2, nextPage, page.first, callback)
                                    else
                                        onImageLongClicked(pos * 2, page.first, nextPage, callback)
                                } ?: onImageLongClicked(
                                    pos,
                                    chapImages.getOrNull(pos) ?: return@let false,
                                    null,
                                    callback
                                )
                            } else {
                                val item = continuousAdapter?.items?.getOrNull(pos)
                                if (item is ContinuousChapterAdapter.ReaderItem.Image) {
                                    onImageLongClicked(pos, item.image, null, callback)
                                } else false
                            }
                        }
                    ) binding.mangaReaderRecycler.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    super.onLongPress(e)
                }

                override fun onSingleClick(event: MotionEvent) {
                    handleController()
                }
            })

            val manager = PreloadLinearLayoutManager(
                this,
                if (defaultSettings.direction == TOP_TO_BOTTOM || defaultSettings.direction == BOTTOM_TO_TOP)
                    RecyclerView.VERTICAL
                else
                    RecyclerView.HORIZONTAL,
                directionRLBT
            )
            val preloadAmount = defaultSettings.preloadAmount.coerceIn(4, 20)
            manager.preloadItemCount = preloadAmount

            binding.mangaReaderPager.visibility = View.GONE

            binding.mangaReaderRecycler.apply {
                clearOnScrollListeners()
                binding.mangaReaderSwipy.child = this

                if (isContinuousMultiChapter) {
                    continuousAdapter = ContinuousChapterAdapter(
                        this@MangaReaderActivity,
                        chapter,
                        currentChapterIndex,
                        chaptersTitleArr
                    )
                    adapter = continuousAdapter
                } else {
                    continuousAdapter = null
                    adapter = imageAdapter
                }

                prefetcher?.cancel()
                prefetcher = if (isContinuousMultiChapter) {
                    PagePrefetcher(
                        this@MangaReaderActivity,
                        ContinuousChapterAdapter.MAX_PAGE_HEIGHT
                    ) { position ->
                        listOfNotNull(
                            (continuousAdapter?.items?.getOrNull(position)
                                    as? ContinuousChapterAdapter.ReaderItem.Image)?.image
                        )
                    }
                } else {
                    PagePrefetcher(this@MangaReaderActivity, null) { position ->
                        imageAdapter?.pagesAt(position).orEmpty()
                    }
                }

                layoutManager = manager
                setOnTouchListener { _, event ->
                    if (event != null)
                        tryWith { detector.onTouchEvent(event) } ?: false
                    else false
                }

                manager.setStackFromEnd(defaultSettings.direction == BOTTOM_TO_TOP)

                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(v: RecyclerView, dx: Int, dy: Int) {
                        prefetcher?.warmAfter(mostVisibleItemPosition(v, manager), preloadAmount)
                        if (isContinuousMultiChapter) {
                            handleContinuousMultiChapterScroll(v, manager)
                        } else {
                            defaultSettings.apply {
                                if (
                                    ((direction == TOP_TO_BOTTOM || direction == BOTTOM_TO_TOP)
                                            && (!v.canScrollVertically(-1) || !v.canScrollVertically(1)))
                                    ||
                                    ((direction == LEFT_TO_RIGHT || direction == RIGHT_TO_LEFT)
                                            && (!v.canScrollHorizontally(-1) || !v.canScrollHorizontally(
                                        1
                                    )))
                                ) {
                                    handleController(true)
                                } else if (!autoscrollOn) handleController(false)
                            }
                            updatePageNumber(
                                mostVisibleItemPosition(v, manager).toLong() * (dualPage { 2 }
                                    ?: 1) + 1)
                        }
                        scheduleAutoTranslation()
                        super.onScrolled(v, dx, dy)
                    }
                })
                if ((defaultSettings.direction == TOP_TO_BOTTOM || defaultSettings.direction == BOTTOM_TO_TOP))
                    updatePadding(0, 128f.px, 0, 128f.px)
                else
                    updatePadding(128f.px, 0, 128f.px, 0)

                snapHelper.attachToRecyclerView(
                    if (defaultSettings.layout == CONTINUOUS_PAGED) this
                    else null
                )

                onVolumeUp = {
                    if ((defaultSettings.direction == TOP_TO_BOTTOM || defaultSettings.direction == BOTTOM_TO_TOP))
                        smoothScrollBy(0, -500)
                    else
                        smoothScrollBy(-500, 0)
                }

                onVolumeDown = {
                    if ((defaultSettings.direction == TOP_TO_BOTTOM || defaultSettings.direction == BOTTOM_TO_TOP))
                        smoothScrollBy(0, 500)
                    else
                        smoothScrollBy(500, 0)
                }

                val startPosition = if (!isContinuousMultiChapter) {
                    currentPage / (dualPage { 2 } ?: 1) - 1
                } else {
                    val startOfChapter = continuousAdapter?.getChapterStartPosition(currentChapterIndex) ?: 0
                    (startOfChapter + (currentChapterPage.toInt() - 1)).coerceAtLeast(0)
                }
                scrollToPosition(startPosition)
                // Nothing has scrolled yet, so this is the only thing that gets the pages below the
                // opening one moving; from here on the scroll listener keeps the window updated.
                prefetcher?.warmAfter(startPosition, preloadAmount)
            }
        } else {
            binding.mangaReaderRecyclerContainer.visibility = View.GONE
            // ViewPager2 keeps its neighbours bound on its own (offscreenPageLimit below), so the
            // prefetcher has no job here — and a leftover one would keep fetching for a recycler
            // that is no longer on screen.
            prefetcher?.cancel()
            prefetcher = null
            binding.mangaReaderPager.apply {
                binding.mangaReaderSwipy.child = this
                visibility = View.VISIBLE

                if (isContinuousMultiChapter) {
                    continuousAdapter = ContinuousChapterAdapter(
                        this@MangaReaderActivity,
                        chapter,
                        currentChapterIndex,
                        chaptersTitleArr
                    )
                    adapter = continuousAdapter
                } else {
                    continuousAdapter = null
                    adapter = imageAdapter
                }

                layoutDirection =
                    if (directionRLBT) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                orientation =
                    if (defaultSettings.direction == LEFT_TO_RIGHT || defaultSettings.direction == RIGHT_TO_LEFT)
                        ViewPager2.ORIENTATION_HORIZONTAL
                    else ViewPager2.ORIENTATION_VERTICAL
                registerOnPageChangeCallback(pageChangeCallback)
                // Pages either side of the current one that ViewPager2 keeps bound, and therefore
                // decoded and held. This was 5 — eleven full-resolution pages resident at once,
                // which on a 1080p screen is up to 41 MB each and on its own enough to exhaust the
                // heap before any cache or prefetch window is counted. Two keeps the neighbours a
                // swipe can reach ready without that: a page a little further out is in MangaCache
                // or Glide's memory cache by the time it is bound, so re-binding it is a cache read
                // rather than a fetch, and the bitmap is only held for as long as it is near.
                offscreenPageLimit = 2

                if (!isContinuousMultiChapter) {
                    setCurrentItem(currentPage / (dualPage { 2 } ?: 1) - 1, false)
                } else {
                    val startOfChapter = continuousAdapter?.getChapterStartPosition(currentChapterIndex)
                        ?: continuousAdapter?.firstImagePosition() ?: 0
                    val target = (startOfChapter + (currentChapterPage.toInt() - 1)).coerceAtLeast(0)
                    setCurrentItem(target, false)
                }
            }
            onVolumeUp = {
                binding.mangaReaderPager.currentItem -= 1
            }
            onVolumeDown = {
                binding.mangaReaderPager.currentItem += 1
            }
        }
    }

    private var onVolumeUp: (() -> Unit)? = null
    private var onVolumeDown: (() -> Unit)? = null
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KEYCODE_VOLUME_UP, KEYCODE_DPAD_UP, KEYCODE_PAGE_UP -> {
                if (event.keyCode == KEYCODE_VOLUME_UP)
                    if (!defaultSettings.volumeButtons)
                        return false
                if (event.action == ACTION_DOWN) {
                    onVolumeUp?.invoke()
                    true
                } else false
            }

            KEYCODE_VOLUME_DOWN, KEYCODE_DPAD_DOWN, KEYCODE_PAGE_DOWN -> {
                if (event.keyCode == KEYCODE_VOLUME_DOWN)
                    if (!defaultSettings.volumeButtons)
                        return false
                if (event.action == ACTION_DOWN) {
                    onVolumeDown?.invoke()
                    true
                } else false
            }

            else -> {
                super.dispatchKeyEvent(event)
            }
        }
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (isContinuousMultiChapter) {
                handlePagedMultiChapterPage(position)
            } else {
                updatePageNumber(position.toLong() * (dualPage { 2 } ?: 1) + 1)
                handleController(position == 0 || position + 1 >= maxChapterPage)
            }
            // The paged layout has no scroll listener, so this is where automatic translation
            // learns that the page has changed.
            scheduleAutoTranslation()
            super.onPageSelected(position)
        }
    }

    private val overshoot = OvershootInterpolator(1.4f)
    private var controllerDuration by Delegates.notNull<Long>()
    private var goneTimer = Timer()
    fun gone() {
        goneTimer.cancel()
        goneTimer.purge()
        val timerTask: TimerTask = object : TimerTask() {
            override fun run() {
                if (!isContVisible) binding.mangaReaderCont.post {
                    binding.mangaReaderCont.visibility = View.GONE
                    isAnimating = false
                }
            }
        }
        goneTimer = Timer()
        goneTimer.schedule(timerTask, controllerDuration)
    }

    enum class PressPos {
        LEFT, RIGHT, CENTER
    }

    fun handleController(shouldShow: Boolean? = null, event: MotionEvent? = null) {
        var pressLocation = PressPos.CENTER
        if (!sliding) {
            if (event != null && defaultSettings.layout == PAGED) {
                if (event.action != MotionEvent.ACTION_UP) return
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()
                val screenWidth = Resources.getSystem().displayMetrics.widthPixels
                //if in the 1st 1/5th of the screen width, left and lower than 1/5th of the screen height, left
                if (screenWidth / 5 in x + 1..<y) {
                    pressLocation = if (defaultSettings.direction == RIGHT_TO_LEFT) {
                        PressPos.RIGHT
                    } else {
                        PressPos.LEFT
                    }
                }
                //if in the last 1/5th of the screen width, right and lower than 1/5th of the screen height, right
                else if (x > screenWidth - screenWidth / 5 && y > screenWidth / 5) {
                    pressLocation = if (defaultSettings.direction == RIGHT_TO_LEFT) {
                        PressPos.LEFT
                    } else {
                        PressPos.RIGHT
                    }
                }
            }

            // if pressLocation is left or right go to previous or next page (paged mode only)
            if (pressLocation == PressPos.LEFT) {

                if (binding.mangaReaderPager.currentItem > 0) {
                    //if  the current images zoomed in, go back to normal before going to previous page
                    if (imageAdapter?.isZoomed() == true) {
                        imageAdapter?.setZoom(1f)
                    }
                    binding.mangaReaderPager.currentItem -= 1
                    return
                }

            } else if (pressLocation == PressPos.RIGHT) {
                val maxItems = binding.mangaReaderPager.adapter?.itemCount ?: 1
                if (binding.mangaReaderPager.currentItem < maxItems - 1) {
                    //if  the current images zoomed in, go back to normal before going to next page
                    if (imageAdapter?.isZoomed() == true) {
                        imageAdapter?.setZoom(1f)
                    }
                    //if right to left, go to previous page
                    binding.mangaReaderPager.currentItem += 1
                    return
                }
            }

            if (!PrefManager.getVal<Boolean>(PrefName.ShowSystemBars)) {
                hideSystemBars()
                checkNotch()
            }
            // Hide the scrollbar completely
            if (defaultSettings.hideScrollBar) {
                binding.mangaReaderSliderContainer.visibility = View.GONE
            } else {
                if (defaultSettings.horizontalScrollBar) {
                    binding.mangaReaderSliderContainer.updateLayoutParams {
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                        width = ViewGroup.LayoutParams.WRAP_CONTENT
                    }

                    binding.mangaReaderSlider.apply {
                        updateLayoutParams<ViewGroup.MarginLayoutParams> {
                            width = ViewGroup.LayoutParams.MATCH_PARENT
                        }
                        rotation = 0f
                    }

                } else {
                    binding.mangaReaderSliderContainer.updateLayoutParams {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        width = 48f.px
                    }

                    binding.mangaReaderSlider.apply {
                        updateLayoutParams {
                            width = binding.mangaReaderSliderContainer.height - 16f.px
                        }
                        rotation = 90f
                    }
                }
                binding.mangaReaderSliderContainer.visibility = View.VISIBLE
            }
            //horizontal scrollbar
            if (defaultSettings.horizontalScrollBar) {
                binding.mangaReaderSliderContainer.updateLayoutParams {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    width = ViewGroup.LayoutParams.WRAP_CONTENT
                }

                binding.mangaReaderSlider.apply {
                    updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    rotation = 0f
                }

            } else {
                binding.mangaReaderSliderContainer.updateLayoutParams {
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    width = 48f.px
                }

                binding.mangaReaderSlider.apply {
                    updateLayoutParams {
                        width = binding.mangaReaderSliderContainer.height - 16f.px
                    }
                    rotation = 90f
                }
            }
            binding.mangaReaderSlider.layoutDirection =
                if (directionRLBT)
                    View.LAYOUT_DIRECTION_RTL
                else
                    View.LAYOUT_DIRECTION_LTR
            if (shouldShow != null && shouldShow == isContVisible) return
            shouldShow?.apply { isContVisible = !this }
            if (isContVisible) {
                isContVisible = false
                if (!isAnimating) {
                    isAnimating = true
                    ObjectAnimator.ofFloat(binding.mangaReaderCont, "alpha", 1f, 0f)
                        .setDuration(controllerDuration).start()
                    ObjectAnimator.ofFloat(
                        binding.mangaReaderBottomLayout,
                        "translationY",
                        0f,
                        128f
                    )
                        .apply { interpolator = overshoot;duration = controllerDuration;start() }
                    ObjectAnimator.ofFloat(binding.mangaReaderTopLayout, "translationY", 0f, -128f)
                        .apply { interpolator = overshoot;duration = controllerDuration;start() }
                }
                gone()
            } else {
                isContVisible = true
                binding.mangaReaderCont.visibility = View.VISIBLE
                ObjectAnimator.ofFloat(binding.mangaReaderCont, "alpha", 0f, 1f)
                    .setDuration(controllerDuration).start()
                ObjectAnimator.ofFloat(binding.mangaReaderTopLayout, "translationY", -128f, 0f)
                    .apply { interpolator = overshoot;duration = controllerDuration;start() }
                ObjectAnimator.ofFloat(binding.mangaReaderBottomLayout, "translationY", 128f, 0f)
                    .apply { interpolator = overshoot;duration = controllerDuration;start() }
            }
        }
    }

    /**
     * The item occupying the largest visible span along the scroll axis — a better proxy for
     * "the page being read" than [PreloadLinearLayoutManager.findLastVisibleItemPosition], which
     * returns whichever item has even a single pixel peeking in at the trailing edge.
     */
    private fun mostVisibleItemPosition(
        recyclerView: RecyclerView,
        manager: PreloadLinearLayoutManager
    ): Int {
        val first = manager.findFirstVisibleItemPosition()
        val last = manager.findLastVisibleItemPosition()
        if (first < 0 || last < 0) return last
        if (first == last) return first

        val vertical = manager.orientation == RecyclerView.VERTICAL
        val viewportStart = if (vertical) recyclerView.paddingTop else recyclerView.paddingLeft
        val viewportEnd = if (vertical)
            recyclerView.height - recyclerView.paddingBottom
        else
            recyclerView.width - recyclerView.paddingRight

        var bestPosition = first
        var bestVisibleSpan = -1
        for (pos in first..last) {
            val child = manager.findViewByPosition(pos) ?: continue
            val childStart = if (vertical) child.top else child.left
            val childEnd = if (vertical) child.bottom else child.right
            val visibleSpan = min(childEnd, viewportEnd) - max(childStart, viewportStart)
            if (visibleSpan > bestVisibleSpan) {
                bestVisibleSpan = visibleSpan
                bestPosition = pos
            }
        }
        return bestPosition
    }

    private var loading = false
    fun updatePageNumber(pageNumber: Long) {
        var page = pageNumber
        if (directionPagedBT) {
            page = maxChapterPage - pageNumber + 1
        }
        if (currentChapterPage != page) {
            currentChapterPage = page
            if (media.id >= 0) PrefManager.setCustomVal("${media.id}_${chapter.number}", page)
            binding.mangaReaderPageNumber.text =
                if (defaultSettings.hidePageNumbers) "" else "${currentChapterPage}/$maxChapterPage"
            if (!sliding) binding.mangaReaderSlider.apply {
                value = clamp(currentChapterPage.toFloat(), 1f, valueTo)
            }
        }
        if (maxChapterPage - currentChapterPage <= 1 && !loading && !isContinuousMultiChapter)
            scope.launch(Dispatchers.IO) {
                loading = true
                val nextKey = chaptersArr.getOrNull(currentChapterIndex + 1) ?: return@launch
                val nextChapter = chapters[nextKey] ?: return@launch
                val isPremium = (nextChapter.title?.contains("🔒") == true) || nextChapter.number.contains("🔒")
                if (!isPremium) {
                    model.loadMangaChapterImages(nextChapter, media.selected!!, false)
                }
                loading = false
            }
    }

    // ---- Continuous Multi-Chapter Mode ----

    private var multiChapterLoading = false
    private var lastTrackedChapterIndex = -1

    /**
     * Chapter indices whose progress has already been pushed during this reader session.
     * Resting on a transition leaves the tracked chapter oscillating across the divider — the
     * item below is only borderline visible, so it drops in and out of the visible range as the
     * images under it settle from their placeholder height into their real one. Each forward flip
     * looks like a fresh chapter boundary, so without this the same update (and its toast) fires
     * over and over while the reader sits still.
     */
    private val progressedChapterIndices = mutableSetOf<Int>()

    private fun handleContinuousMultiChapterScroll(
        v: RecyclerView,
        manager: PreloadLinearLayoutManager
    ) {
        val adapter = continuousAdapter ?: return
        val lastVisible = manager.findLastVisibleItemPosition()
        val firstVisible = manager.findFirstVisibleItemPosition()
        if (lastVisible < 0) return

        // Show controller at boundaries
        defaultSettings.apply {
            if (
                ((direction == TOP_TO_BOTTOM || direction == BOTTOM_TO_TOP)
                        && (!v.canScrollVertically(-1) || !v.canScrollVertically(1)))
                ||
                ((direction == LEFT_TO_RIGHT || direction == RIGHT_TO_LEFT)
                        && (!v.canScrollHorizontally(-1) || !v.canScrollHorizontally(1)))
            ) {
                // Show boundary messages
                if (!v.canScrollVertically(-1) || !v.canScrollHorizontally(-1)) {
                    // At the very top/left — check if there's a previous chapter to load
                    val firstChapterIdx = adapter.items.filterIsInstance<ContinuousChapterAdapter.ReaderItem.Image>()
                        .firstOrNull()?.chapterIndex ?: currentChapterIndex
                    if (firstChapterIdx <= 0 || chaptersArr.getOrNull(firstChapterIdx - 1) == null) {
                        if (!multiChapterLoading) {
                            adapter.addStartBoundary(getString(R.string.no_previous_chapter))
                        }
                    } else if (!multiChapterLoading) {
                        loadPreviousChapterInContinuous(firstChapterIdx - 1)
                    }
                }
                if (!v.canScrollVertically(1) || !v.canScrollHorizontally(1)) {
                    val lastChapterIdx = adapter.items.filterIsInstance<ContinuousChapterAdapter.ReaderItem.Image>()
                        .lastOrNull()?.chapterIndex ?: currentChapterIndex
                    if (lastChapterIdx >= chaptersArr.size - 1 || chaptersArr.getOrNull(lastChapterIdx + 1) == null) {
                        if (!multiChapterLoading) {
                            adapter.addEndBoundary(getString(R.string.no_next_chapter))
                        }
                    }
                }
                handleController(true)
            } else if (!autoscrollOn) handleController(false)
        }

        // Track current chapter/page based on whichever Image occupies the most of the screen
        // (skip Transition/Boundary so progress keeps tracking the chapter when those non-image
        // items are on screen). Using the most-visible item rather than the last-visible one
        // avoids flipping the indicator onto the next page/chapter while it's still just a sliver
        // peeking in at the trailing edge.
        val mostVisible = mostVisibleItemPosition(v, manager)
        val refPos = adapter.lastImagePositionAtOrBefore(mostVisible)
        val visibleChapterIdx = if (refPos >= 0) adapter.getChapterIndexAt(refPos) else null
        if (visibleChapterIdx != null && visibleChapterIdx != lastTrackedChapterIndex) {
            // Chapter changed — update progress for the previous chapter only when moving forward
            if (lastTrackedChapterIndex >= 0 && visibleChapterIdx > lastTrackedChapterIndex) {
                val prevKey = chaptersArr.getOrNull(lastTrackedChapterIndex)
                val prevChap = if (prevKey != null) chapters[prevKey] else null
                if (prevChap != null) {
                    updateMultiChapterProgressSilently(prevChap, lastTrackedChapterIndex)
                }
            }
            lastTrackedChapterIndex = visibleChapterIdx

            // Update currentChapterIndex and related UI
            currentChapterIndex = visibleChapterIdx
            val key = chaptersArr.getOrNull(visibleChapterIdx)
            val currentChap = if (key != null) chapters[key] else null
            if (currentChap != null) {
                chapter = currentChap
                media.manga!!.selectedChapter = chapter
                if (media.id >= 0) PrefManager.setCustomVal("${media.id}_current_chp", chapter.number)
                val newTotalPages = adapter.getPageCountForChapter(visibleChapterIdx)
                if (newTotalPages > 0 && media.id >= 0) {
                    PrefManager.setCustomVal("${media.id}_${chapter.number}_max", newTotalPages.toLong())
                }
                binding.mangaReaderChapterSelect.setSelection(currentChapterIndex)
                updateChapterNavigationText()
                updateDiscordRPC(currentChap)
            }
        }

        // Update page number within current chapter (use the last image, not boundary/transition)
        val pageInChapter = if (refPos >= 0) adapter.getPageInChapter(refPos) else 0
        val totalPages = adapter.getPageCountForChapter(visibleChapterIdx ?: currentChapterIndex)
        if (totalPages > 0) {
            maxChapterPage = totalPages.toLong()
            val newPage = pageInChapter.toLong()
            if (currentChapterPage != newPage && newPage > 0) {
                currentChapterPage = newPage
                if (media.id >= 0) PrefManager.setCustomVal("${media.id}_${chapter.number}", currentChapterPage)
            }
            binding.mangaReaderPageNumber.text =
                if (defaultSettings.hidePageNumbers) "" else "$pageInChapter/$totalPages"
            if (!sliding && totalPages > 1) {
                binding.mangaReaderSlider.apply {
                    visibility = View.VISIBLE
                    valueTo = totalPages.toFloat()
                    value = androidx.core.math.MathUtils.clamp(pageInChapter.toFloat(), 1f, valueTo)
                }
            }
        }

        // Preload next chapter when near the end
        val itemsRemaining = adapter.itemCount - lastVisible
        if (itemsRemaining <= 5 && !multiChapterLoading) {
            val lastLoadedChapterIdx = adapter.lastLoadedChapterIdx
            val nextIdx = lastLoadedChapterIdx + 1
            val nextKey = chaptersArr.getOrNull(nextIdx)
            if (nextIdx < chaptersArr.size && nextKey != null && !adapter.isChapterLoaded(nextIdx) && !adapter.isChapterLoaded(nextKey)) {
                loadNextChapterInContinuous(nextIdx)
            } else if (nextIdx >= chaptersArr.size) {
                adapter.addEndBoundary(getString(R.string.no_next_chapter))
            }
        }

        // Preload previous chapter when near the beginning
        if (firstVisible <= 3 && !multiChapterLoading) {
            val firstLoadedChapterIdx = adapter.firstLoadedChapterIdx
            val prevIdx = firstLoadedChapterIdx - 1
            val prevKey = chaptersArr.getOrNull(prevIdx)
            if (prevIdx >= 0 && prevKey != null && !adapter.isChapterLoaded(prevIdx) && !adapter.isChapterLoaded(prevKey)) {
                loadPreviousChapterInContinuous(prevIdx)
            }
        }
    }

    /**
     * Handles multi-chapter tracking for PAGED and CONTINUOUS_PAGED layouts (ViewPager2).
     * Called from pageChangeCallback when multi-chapter mode is active.
     */
    private fun handlePagedMultiChapterPage(position: Int) {
        val adapter = continuousAdapter ?: return

        // Track current chapter based on the last image at-or-before the visible page so
        // boundary/transition pages still resolve to the active chapter.
        val refPos = adapter.lastImagePositionAtOrBefore(position)
        val visibleChapterIdx = if (refPos >= 0) adapter.getChapterIndexAt(refPos) else null
        if (visibleChapterIdx != null && visibleChapterIdx != lastTrackedChapterIndex) {
            if (lastTrackedChapterIndex >= 0 && visibleChapterIdx > lastTrackedChapterIndex) {
                val prevKey = chaptersArr.getOrNull(lastTrackedChapterIndex)
                val prevChap = if (prevKey != null) chapters[prevKey] else null
                if (prevChap != null) {
                    updateMultiChapterProgressSilently(prevChap, lastTrackedChapterIndex)
                }
            }
            lastTrackedChapterIndex = visibleChapterIdx

            currentChapterIndex = visibleChapterIdx
            val key = chaptersArr.getOrNull(visibleChapterIdx)
            val currentChap = if (key != null) chapters[key] else null
            if (currentChap != null) {
                chapter = currentChap
                media.manga!!.selectedChapter = chapter
                if (media.id >= 0) PrefManager.setCustomVal("${media.id}_current_chp", chapter.number)
                val newTotalPages = adapter.getPageCountForChapter(visibleChapterIdx)
                if (newTotalPages > 0 && media.id >= 0) {
                    PrefManager.setCustomVal("${media.id}_${chapter.number}_max", newTotalPages.toLong())
                }
                binding.mangaReaderChapterSelect.setSelection(currentChapterIndex)
                updateChapterNavigationText()
                updateDiscordRPC(currentChap)
            }
        }

        // Update page number within current chapter (use the last image, not boundary/transition)
        val pageInChapter = if (refPos >= 0) adapter.getPageInChapter(refPos) else 0
        val totalPages = adapter.getPageCountForChapter(visibleChapterIdx ?: currentChapterIndex)
        if (totalPages > 0) {
            maxChapterPage = totalPages.toLong()
            val newPage = pageInChapter.toLong()
            if (currentChapterPage != newPage && newPage > 0) {
                currentChapterPage = newPage
                if (media.id >= 0) PrefManager.setCustomVal("${media.id}_${chapter.number}", currentChapterPage)
            }
            binding.mangaReaderPageNumber.text =
                if (defaultSettings.hidePageNumbers) "" else "$pageInChapter/$totalPages"
            if (!sliding && totalPages > 1) {
                binding.mangaReaderSlider.apply {
                    visibility = View.VISIBLE
                    valueTo = totalPages.toFloat()
                    value = androidx.core.math.MathUtils.clamp(pageInChapter.toFloat(), 1f, valueTo)
                }
            }
        }

        // Show controller at first/last pages
        handleController(position == 0 || position + 1 >= adapter.itemCount)

        // Add boundary items at edges
        if (position == 0) {
            val firstChapterIdx = adapter.items.filterIsInstance<ContinuousChapterAdapter.ReaderItem.Image>()
                .firstOrNull()?.chapterIndex ?: currentChapterIndex
            if (firstChapterIdx <= 0 || chaptersArr.getOrNull(firstChapterIdx - 1) == null) {
                if (!multiChapterLoading) {
                    adapter.addStartBoundary(getString(R.string.no_previous_chapter))
                }
            } else if (!multiChapterLoading) {
                loadPreviousChapterInContinuous(firstChapterIdx - 1)
            }
        }

        // Preload next chapter when near the end
        val itemsRemaining = adapter.itemCount - position
        if (itemsRemaining <= 3 && !multiChapterLoading) {
            val lastLoadedChapterIdx = adapter.lastLoadedChapterIdx
            val nextIdx = lastLoadedChapterIdx + 1
            val nextKey = chaptersArr.getOrNull(nextIdx)
            if (nextIdx < chaptersArr.size && nextKey != null && !adapter.isChapterLoaded(nextIdx) && !adapter.isChapterLoaded(nextKey)) {
                loadNextChapterInContinuous(nextIdx)
            } else if (nextIdx >= chaptersArr.size) {
                adapter.addEndBoundary(getString(R.string.no_next_chapter))
            }
        }

        // Preload previous chapter when near the beginning
        if (position <= 2 && !multiChapterLoading) {
            val firstLoadedChapterIdx = adapter.firstLoadedChapterIdx
            val prevIdx = firstLoadedChapterIdx - 1
            val prevKey = chaptersArr.getOrNull(prevIdx)
            if (prevIdx >= 0 && prevKey != null && !adapter.isChapterLoaded(prevIdx) && !adapter.isChapterLoaded(prevKey)) {
                loadPreviousChapterInContinuous(prevIdx)
            }
        }
    }

    private fun updateDiscordRPC(chap: ani.dantotsu.media.manga.MangaChapter) {
        val context = this
        val offline: Boolean = PrefManager.getVal(PrefName.OfflineMode)
        val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
        val rpcEnabled: Boolean = PrefManager.getVal(PrefName.rpcEnabled)
        if (!isOnline(context) || offline || Discord.token == null || incognito || !rpcEnabled) return
        lifecycleScope.launch {
            val isExtension = media.id < 0
            val buttons = mutableListOf<RPC.Link>()
            if (!isExtension) {
                val muUrl = if (media.muSeriesId != null) {
                    media.shareLink?.takeIf { it.contains("mangaupdates") }
                        ?: "https://www.mangaupdates.com/series/${media.muSeriesId!!.toString(36)}"
                } else null
                muUrl?.let { buttons.add(RPC.Link("View on MangaUpdates", it)) }
                buttons.add(RPC.Link("View Manga", "https://anilist.co/manga/${media.id}/"))
                media.idMAL?.let {
                    buttons.add(RPC.Link("View on MyAnimeList", "https://myanimelist.net/manga/$it"))
                }
            }
            val rpcData = RPC.Companion.RPCData(
                applicationId = Discord.application_Id,
                type = RPC.Type.WATCHING,
                activityName = media.userPreferredName,
                details = chap.title?.takeIf { it.isNotEmpty() } ?: chap.number,
                state = "Chapter ${chap.number}/${media.manga?.totalChapters ?: "??"}",
                largeImage = media.cover?.let { cover ->
                    RPC.Link(media.userPreferredName, cover)
                },
                smallImage = null,
                buttons = buttons
            )
            RPCManager.setPresence(context, rpcData)
        }
    }

    private fun loadNextChapterInContinuous(nextIdx: Int) {
        val adapter = continuousAdapter ?: return
        val nextKey = chaptersArr.getOrNull(nextIdx) ?: return
        val nextChapter = chapters[nextKey] ?: return
        val isPremium = (nextChapter.title?.contains("🔒") == true) || nextChapter.number.contains("🔒")
        if (isPremium) return

        multiChapterLoading = true
        scope.launch(Dispatchers.IO) {
            val loaded = model.loadMangaChapterImages(nextChapter, media.selected!!, false)
            if (loaded) {
                val missing = countMissingChapters(nextIdx - 1, nextIdx)
                scope.launch(Dispatchers.Main) {
                    adapter.appendChapter(nextChapter, nextIdx, missing)
                    multiChapterLoading = false
                }
            } else {
                multiChapterLoading = false
            }
        }
    }

    private fun loadPreviousChapterInContinuous(prevIdx: Int) {
        val adapter = continuousAdapter ?: return
        val prevKey = chaptersArr.getOrNull(prevIdx) ?: return
        val prevChapter = chapters[prevKey] ?: return
        val isPremium = (prevChapter.title?.contains("🔒") == true) || prevChapter.number.contains("🔒")
        if (isPremium) return

        multiChapterLoading = true
        scope.launch(Dispatchers.IO) {
            val loaded = model.loadMangaChapterImages(prevChapter, media.selected!!, false)
            if (loaded) {
                val missing = countMissingChapters(prevIdx, prevIdx + 1)
                scope.launch(Dispatchers.Main) {
                    val insertedCount = prevChapter.images().size + 1 // images + transition

                    if (defaultSettings.layout != CurrentReaderSettings.Layouts.PAGED) {
                        val layoutManager = binding.mangaReaderRecycler.layoutManager as? PreloadLinearLayoutManager
                        val firstVisiblePos = layoutManager?.findFirstVisibleItemPosition() ?: 0
                        val firstVisibleView = layoutManager?.findViewByPosition(firstVisiblePos)
                        // view.top/left are RecyclerView-relative coordinates that include padding.
                        // scrollToPositionWithOffset adds paddingStart internally, so subtract it
                        // here to avoid double-counting (which would push the image below the
                        // transition and make it appear off-centre).
                        val isVertical = defaultSettings.direction == TOP_TO_BOTTOM ||
                                         defaultSettings.direction == BOTTOM_TO_TOP
                        val rawEdge = if (isVertical) firstVisibleView?.top ?: 0
                                      else firstVisibleView?.left ?: 0
                        val paddingStart = if (isVertical) binding.mangaReaderRecycler.paddingTop
                                           else binding.mangaReaderRecycler.paddingLeft
                        val offset = rawEdge - paddingStart

                        adapter.prependChapter(prevChapter, prevIdx, missing)
                        layoutManager?.scrollToPositionWithOffset(firstVisiblePos + insertedCount, offset)
                    } else {
                        // ViewPager2: integer item index, no pixel offset needed
                        val currentItem = binding.mangaReaderPager.currentItem
                        adapter.prependChapter(prevChapter, prevIdx, missing)
                        binding.mangaReaderPager.setCurrentItem(currentItem + insertedCount, false)
                    }
                    // Every position just moved down by a whole chapter. Automatic translation
                    // reads its direction from how the visible position changes, and that jump
                    // looks exactly like a fast scroll *forwards* — which is the opposite of what
                    // the reader just did to cause it. Dropping the anchor keeps the direction it
                    // had and re-measures from where things now are.
                    autoAnchor = RecyclerView.NO_POSITION
                    multiChapterLoading = false
                }
            } else {
                multiChapterLoading = false
            }
        }
    }
    
    fun getChapterTitle(index: Int): String {
        val key = chaptersArr.getOrNull(index) ?: return ""
        val chap = chapters[key] ?: return ""
        return "${chap.number}${if (!chap.title.isNullOrEmpty() && chap.title != "null") " : " + chap.title else ""}"
    }

    private fun countMissingChapters(fromIndex: Int, toIndex: Int): Int {
        val fromChapter = chapters[chaptersArr.getOrNull(fromIndex)]
        val toChapter = chapters[chaptersArr.getOrNull(toIndex)]
        if (fromChapter == null || toChapter == null) return 0
        
        val fromChapterName = fromChapter.number
        val toChapterName = toChapter.number
        
        val fromNum = fromChapter.sChapter.chapter_number.takeIf { it >= 0f }
            ?: MediaNameAdapter.findChapterNumber(fromChapterName)
            ?: return 0
        val toNum = toChapter.sChapter.chapter_number.takeIf { it >= 0f }
            ?: MediaNameAdapter.findChapterNumber(toChapterName)
            ?: return 0
        
        // Skip gap warning for non-sequential chapter types (Extra Story, Omake, Special, etc.)
        val nonSequentialKeywords = listOf(
            "extra", "omake", "special", "side story", "prologue", "epilogue",
            "afterword", "author", "bonus", "cover story", "gaiden", "interlude"
        )
        val isFromNonSequential = nonSequentialKeywords.any { fromChapterName.lowercase().contains(it) }
        val isToNonSequential = nonSequentialKeywords.any { toChapterName.lowercase().contains(it) }
        
        if (isFromNonSequential || isToNonSequential) return 0
        
        val diff = abs(toNum - fromNum)
        // If the difference is > 1.1 (e.g. 5 to 7), we have at least one missing chapter.
        // Using 1.1 to avoid issues with 5.1, 5.2, etc.
        return if (diff > 1.1f) (diff - 0.99f).toInt() else 0
    }

    /**
     * Whether progress may be written for this media without a prompt.
     *
     * `AskIndividualReader` off means the setting itself has opted into always auto-updating.
     * Otherwise the user must have actually answered the "update progress?" question at some point
     * — both Yes and No write `_save_progress`, so the key existing is the record of a choice.
     * When asking is on and that key is absent the prompt was skipped rather than answered (an
     * adult title with H-updates off, a stale "don't ask again" flag, an entry path that bypassed
     * [ChapterLoaderDialog.showProgressPopupIfNecessary]); silently tracking there is exactly the
     * "never asked but tracked anyway" case, so don't.
     */
    private fun mayTrackProgressSilently(): Boolean {
        if (!PrefManager.getVal<Boolean>(PrefName.AskIndividualReader)) return true
        return PrefManager.customValExists("${media.id}_save_progress")
    }

    private fun progress(runnable: Runnable) {
        if (media.id < 0) { runnable.run(); return }
        if (maxChapterPage - currentChapterPage <= 1 && Anilist.userid != null) {
            showProgressDialog =
                if (PrefManager.getVal(PrefName.AskIndividualReader)) PrefManager.getCustomVal(
                    "${media.id}_progressDialog",
                    true
                )
                else false
            val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
            val isContinuousMultiChapter = PrefManager.getVal<Boolean>(PrefName.ContinuousMultiChapter)
            if (showProgressDialog && !incognito && !isContinuousMultiChapter) {

                val dialogView = layoutInflater.inflate(R.layout.item_custom_dialog, null)
                val checkbox = dialogView.findViewById<CheckBox>(R.id.dialog_checkbox)
                checkbox.text = getString(R.string.dont_ask_again, media.userPreferredName)
                checkbox.setOnCheckedChangeListener { _, isChecked ->
                    PrefManager.setCustomVal("${media.id}_progressDialog", !isChecked)
                    showProgressDialog = !isChecked
                }
                customAlertDialog().apply {
                    setTitle(R.string.title_update_progress)
                    setCustomView(dialogView)
                    // Not dismissable, deliberately: the two buttons are the only answers and one
                    // of them is needed. The checkbox beside them writes "don't ask again" the
                    // moment it's ticked, so a dismissal would suppress the dialog for good while
                    // leaving the answer it suppresses unset — which then falls back to the
                    // default, silently auto-updating progress from then on. That is the opposite
                    // of what walking away from the question implies.
                    setCancelable(false)
                    setPosButton(R.string.yes) {
                        PrefManager.setCustomVal("${media.id}_save_progress", true)
                        updateProgress(
                            media,
                            MediaNameAdapter.findChapterNumber(media.manga!!.selectedChapter!!.number)
                                .toString()
                        )
                        runnable.run()
                    }
                    setNegButton(R.string.no) {
                        PrefManager.setCustomVal("${media.id}_save_progress", false)
                        runnable.run()
                    }
                    show()

                }
            } else {
                if (!incognito && mayTrackProgressSilently() && PrefManager.getCustomVal(
                        "${media.id}_save_progress",
                        true
                    ) && if (media.isAdult) PrefManager.getVal(PrefName.UpdateForHReader) else true
                )
                    updateProgress(
                        media,
                        MediaNameAdapter.findChapterNumber(media.manga!!.selectedChapter!!.number)
                            .toString()
                    )
                runnable.run()
            }
        } else {
            runnable.run()
        }
    }


    /**
     * Silently updates progress when crossing a chapter boundary in continuous multi-chapter
     * mode. The user's choice is collected once up front by
     * [ChapterLoaderDialog.showProgressPopupIfNecessary] when a chapter is tapped in the list,
     * so each transition only consults the stored `_save_progress` decision — and only when a
     * decision is actually on record ([mayTrackProgressSilently]), so a prompt that was skipped
     * rather than answered doesn't turn into silent tracking. Fires at most once per chapter per
     * session — see [progressedChapterIndices].
     */
    private fun updateMultiChapterProgressSilently(completedChapter: MangaChapter, chapterIndex: Int) {
        if (media.id < 0) return
        if (Anilist.userid == null) return
        if (!progressedChapterIndices.add(chapterIndex)) return
        val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
        if (incognito) return
        if (media.isAdult && !PrefManager.getVal<Boolean>(PrefName.UpdateForHReader)) return

        val chapterNum = MediaNameAdapter.findChapterNumber(completedChapter.number)?.toString() ?: return

        if (mayTrackProgressSilently() && PrefManager.getCustomVal("${media.id}_save_progress", true)) {
            updateProgress(media, chapterNum)
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
            // A settings class that has grown a field since the file was written is not corruption,
            // just a file from an older build: it is replaced from the defaults without a word.
            if (toast && e !is InvalidClassException) {
                snackString(a?.getString(R.string.error_loading_data, fileName))
            }
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

    fun getTransformation(mangaImage: MangaImage): BitmapTransformation? {
        return model.loadTransformation(mangaImage, media.selected!!.sourceIndex)
    }

    /**
     * The transforms a page is decoded with. They form part of the key its bitmap is cached under,
     * so anything hoping to find a page already decoded — [PagePrefetcher] above all — has to build
     * the list exactly the way the on-screen load does; keeping that in one place is what stops the
     * two drifting apart into a prefetch that warms a key nothing ever looks up.
     */
    fun pageTransforms(mangaImage: MangaImage): List<BitmapTransformation> = buildList {
        getTransformation(mangaImage)?.let { add(it) }
        if (defaultSettings.cropBorders) {
            add(RemoveBordersTransformation(true, defaultSettings.cropBorderThreshold))
            add(RemoveBordersTransformation(false, defaultSettings.cropBorderThreshold))
        }
    }

    fun onImageLongClicked(
        pos: Int,
        img1: MangaImage,
        img2: MangaImage?,
        callback: ((ImageViewDialog) -> Unit)? = null
    ): Boolean {
        if (!defaultSettings.longClickImage) return false
        // With translation on, a long press is no longer only "show me this image" — so it asks
        // rather than assuming, and only when there is a second thing worth offering.
        if (PageTranslationPipeline.enabled()) {
            showPageActions(pos, img1, img2, callback)
            return true
        }
        showImageDialog(pos, img1, img2, callback)
        return true
    }

    /**
     * The long-press menu, once translation gives a page more than one thing to do to it.
     *
     * Re-translating is offered for a page already done because the first answer is not always the
     * right one, and it is the cheapest correction available before any editing UI exists.
     */
    private fun showPageActions(
        pos: Int,
        img1: MangaImage,
        img2: MangaImage?,
        callback: ((ImageViewDialog) -> Unit)?,
    ) {
        val done = TranslatedPages[img1.url.url] != null
        val actions = listOfNotNull(
            getString(
                if (done) R.string.mtl_retranslate_page else R.string.mtl_translate_page,
            ) to { translatePage(pos, img1) },
            (getString(R.string.mtl_hide_translation) to { hideTranslation(img1) }).takeIf { done },
            getString(R.string.mtl_draw_boxes) to { drawBoxes(pos, img1) },
            getString(R.string.view_image) to { showImageDialog(pos, img1, img2, callback) },
        )
        choiceBottomSheet(
            getString(R.string.mtl_page_actions, pos + 1),
            actions.map { it.first },
            selectedIndex = -1,
        ) { index -> actions[index].second() }
    }

    private fun hideTranslation(image: MangaImage) {
        TranslatedPages.remove(image.url.url)
        refreshTranslationOverlays()
    }

    /**
     * Lets the reader draw boxes over text the page pass missed, then translates the page with
     * them.
     *
     * The page pass drops whole columns often enough to need this — a caption in display type, one
     * column of a two-column bubble — and the same text nearly always reads from a crop, so a box
     * is all that is needed to get it. What the page pass *did* find is shown first, outlined in
     * the colour of its verdict, so the missing text is obvious and a bubble it split or framed
     * wrong can be redrawn: a detected box dragged or resized becomes a drawn one and supersedes
     * it. Every box drawn or edited is read again at once and the result shown, so a box is judged
     * before it costs a translation. Boxes are kept per page (see [DrawnRegions]) and shown again
     * here, so a second visit edits rather than starts over.
     */
    private fun drawBoxes(pos: Int, image: MangaImage) {
        if (!mtlSettings().ready()) {
            snackString(getString(R.string.mtl_needs_key))
            return
        }
        lifecycleScope.launch {
            val bitmap = loadBitmap(image.url, pageTransforms(image))
            if (bitmap == null) {
                snackString(getString(R.string.mtl_page_unavailable))
                return@launch
            }
            val key = image.url.url
            val detector = PageTextDetector(activeScript())
            val binding = DialogDrawBoxesBinding.inflate(layoutInflater)
            val editor = binding.drawBoxesPage.apply { setPage(bitmap) }

            // Detected blocks keep their ids from the page pass; drawn ones count on from there.
            // Text is what each box was read as — blank for a box that read as nothing, absent
            // while a read is still in flight.
            val detected = LinkedHashMap<Int, ScoredBlock>()
            val drawn = LinkedHashMap<Int, Rect>()
            val text = HashMap<Int, String>()
            var nextId = 1
            var open = true

            fun status(id: Int?) {
                binding.drawBoxesStatus.text = when {
                    id == null -> getString(R.string.mtl_draw_boxes_hint)
                    id !in text -> getString(R.string.mtl_draw_boxes_reading, id)
                    text.getValue(id).isBlank() ->
                        getString(R.string.mtl_draw_boxes_read_nothing, id)

                    else -> getString(R.string.mtl_draw_boxes_read, id, text.getValue(id))
                }
            }
            fun render() {
                editor.setBoxes(
                    detected.map { (id, entry) ->
                        BlockEditorView.Box(id, entry.block.box, entry.verdict.color)
                    } + drawn.map { (id, rect) ->
                        val color = when {
                            id !in text -> DRAWN_BOX_COLOR
                            text.getValue(id).isBlank() -> BlockVerdict.LOW_CONF.color
                            else -> BlockVerdict.DIALOGUE.color
                        }
                        BlockEditorView.Box(id, rect, color)
                    },
                )
            }
            fun read(id: Int) {
                val rect = drawn[id] ?: return
                text.remove(id)
                status(id)
                lifecycleScope.launch {
                    val (lines, glyphScale) = detector.readRegion(bitmap, rect)
                    // The box may have moved or gone while the recognizer was busy.
                    if (!open || drawn[id] !== rect) return@launch
                    text[id] = detector
                        .toBlock(id, lines, rect, synthetic = true, glyphScale = glyphScale)
                        .text
                    render()
                    if (editor.selectedId == id || editor.selectedId == null) status(id)
                }
            }
            fun remove() {
                val id = editor.selectedId?.takeIf { it in drawn } ?: drawn.keys.lastOrNull()
                if (id == null) return
                drawn.remove(id)
                text.remove(id)
                editor.select(null)
                render()
                status(null)
            }

            editor.onBoxAdded = { rect ->
                val id = nextId++
                drawn[id] = rect
                editor.select(id)
                render()
                read(id)
            }
            editor.onBoxChanged = { id, rect ->
                // Touching a detected box makes it the reader's own: from here it is read from
                // where they put it, and whatever the page pass found there gives way to it.
                detected.remove(id)
                drawn[id] = rect
                render()
            }
            editor.onBoxEditEnded = { id -> read(id) }
            editor.onSelectionChanged = { id -> status(id) }

            binding.drawBoxesStatus.setText(R.string.mtl_draw_boxes_reading_page)
            customAlertDialog().apply {
                setTitle(R.string.mtl_draw_boxes_title, pos + 1)
                setCustomView(binding.root)
                setPosButton(R.string.mtl_draw_boxes_translate) {
                    DrawnRegions.put(key, drawn.values.toList())
                    translatePage(pos, image)
                }
                setNeutralButton(R.string.mtl_draw_boxes_remove)
                setNegButton(R.string.cancel)
                onDismiss { open = false }
                // Removing is one step of editing, not the end of it, so the button is rewired
                // once the dialog exists to keep it open — the builder dismisses on every button.
                // Only drawn boxes go: a detected one belongs to the page pass, and the way to
                // overrule it is to draw over it. What is selected goes, else the last box drawn,
                // so a slip is one tap to undo.
                var dialog: AlertDialog? = null
                attach { dialog = it }
                // The builder installs its own show listener after attach, so the rewiring has
                // to go through the builder's hook rather than the dialog's.
                setOnShowListener {
                    dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { remove() }
                }
                show()
            }

            // What the page pass finds, shown before anything is drawn — minus what the boxes
            // already drawn on this page have superseded. Drawing is armed only once this is in,
            // since ids are handed out from where the detected ones stop.
            val kept = DrawnRegions[key]
            val found = withContext(Dispatchers.Default) {
                PageTranslationPipeline.detect(bitmap, activeScript(), kept)
            }
            if (!open) return@launch
            found.forEach { entry ->
                detected[entry.block.id] = entry
                text[entry.block.id] = entry.block.text
            }
            nextId = (found.maxOfOrNull { it.block.id } ?: 0) + 1
            kept.forEach { rect ->
                val id = nextId++
                drawn[id] = Rect(rect)
                read(id)
            }
            render()
            editor.addMode = true
            status(null)
        }
    }

    /**
     * The language code the extension is serving these chapters in, where it names one.
     *
     * The source knows what is actually printed on the pages; the media's country of origin only
     * knows what the work was written in, which is a different thing the moment anybody translates
     * it. See [SourceScript].
     */
    fun sourceLanguageCode(): String? {
        val index = media.selected?.sourceIndex ?: return null
        return when (val parser = model.mangaReadSources?.get(index)) {
            is DynamicMangaParser -> parser.extension.sources.getOrNull(parser.sourceLanguage)?.lang
            // A download has no extension left to ask, so it answers with what was recorded when
            // it was made. See [OfflineMangaParser.languageFor].
            is OfflineMangaParser -> parser.languageFor(media.mainName())
            else -> null
        }
    }

    /** What automatic resolves to for this media, which the settings rows name. */
    fun detectedScript(): TextScript =
        SourceScript.detect(sourceLanguageCode(), media.countryOfOrigin)

    /** The recognizer to actually use: the user's choice where there is one, otherwise the above. */
    private fun activeScript(): TextScript =
        SourceScript.resolve(mtlSettings().script, sourceLanguageCode(), media.countryOfOrigin)

    /**
     * This manga's translation choices — part of its reader settings, like its layout, and saved
     * in the same file. The preferences only seed the first copy; see [MtlSettings].
     */
    fun mtlSettings(): MtlSettings = MtlSettings.from(defaultSettings)

    /**
     * Stores a change from the settings sheet and reopens what automatic translation had given up
     * on, since whatever failed under the old settings is worth trying again under the new ones.
     *
     * Saved without [applySettings], which rebuilds the page adapters: nothing about how the pages
     * are laid out has changed, and re-binding them for an engine switch would drop every decoded
     * bitmap on screen for no reason.
     */
    fun updateMtlSettings(settings: MtlSettings) {
        settings.applyTo(defaultSettings)
        saveReaderSettings("${media.id}_current_settings", defaultSettings)
        onMtlSettingsChanged()
    }

    /** Whether the feature is on and this manga asks for pages to translate as they appear. */
    private fun autoTranslating(): Boolean =
        PageTranslationPipeline.enabled() && mtlSettings().auto

    /**
     * Translates one page and paints it.
     *
     * The bitmap comes back through the same [loadBitmap] the adapters use, so a page on screen is
     * already in [ani.dantotsu.media.manga.MangaCache] and this costs a lookup rather than a
     * re-fetch — and the recognizer sees exactly the pixels the reader is showing, transforms and
     * all, rather than a differently-processed copy.
     */
    private fun translatePage(pos: Int, image: MangaImage) {
        if (!mtlSettings().ready()) {
            snackString(getString(R.string.mtl_needs_key))
            return
        }
        snackString(getString(R.string.mtl_translating, pos + 1))
        lifecycleScope.launch {
            runTranslation(image)
                .onSuccess { page ->
                    if (page == null) {
                        snackString(getString(R.string.mtl_nothing_found))
                        return@onSuccess
                    }
                    TranslatedPages.put(image.url.url, page)
                    refreshTranslationOverlays()
                }
                .onFailure {
                    Logger.log("MTL page translation failed: ${it.stackTraceToString()}")
                    snackString(it.message ?: getString(R.string.mtl_failed))
                }
        }
    }

    /** One page through the pipeline, saying nothing about it — shared by the menu and by auto. */
    private suspend fun runTranslation(image: MangaImage): Result<TranslatedPage?> = runCatching {
        val bitmap = loadBitmap(image.url, pageTransforms(image))
            ?: error(getString(R.string.mtl_page_unavailable))
        val (before, after) = seamNeighbours(image)
        withContext(Dispatchers.Default) {
            PageTranslationPipeline.run(
                bitmap, activeScript(), sourceLanguageCode(), mtlSettings(), before, after,
                regions = DrawnRegions[image.url.url],
            )
        }
    }

    /**
     * The images either side of this one, for reading across the seam between them.
     *
     * Only in a continuous layout, because only there are two images two slices of one drawing. In
     * a paged layout they are two pages: no bubble crosses between them, and handing the recognizer
     * the neighbour's edge would only give the merger a chance to join text that has nothing to do
     * with itself.
     */
    private suspend fun seamNeighbours(image: MangaImage): Pair<Bitmap?, Bitmap?> {
        if (!mtlSettings().stitch) return null to null
        if (defaultSettings.layout != CurrentReaderSettings.Layouts.CONTINUOUS) return null to null
        val pages = readerPages()
        val index = pages.indexOfFirst { it.url.url == image.url.url }
        if (index < 0) return null to null
        suspend fun at(position: Int): Bitmap? = pages.getOrNull(position)
            ?.let { loadBitmap(it.url, pageTransforms(it)) }
        return at(index - 1) to at(index + 1)
    }

    /**
     * Which of the two page views is in use.
     *
     * The reader has both a RecyclerView and a ViewPager2 and shows one of them: everything but the
     * paged layout goes through the recycler, and the paged layout through the pager. Only one of
     * them carries an adapter at a time, so anything asking what is on screen has to ask the right
     * one — reading the recycler in paged mode gets a null adapter and an empty answer, which is
     * exactly what it looks like when a feature silently does nothing.
     */
    private val usingPager
        get() = defaultSettings.layout == CurrentReaderSettings.Layouts.PAGED

    private val pageHost: View
        get() = if (usingPager) binding.mangaReaderPager else binding.mangaReaderRecycler

    private fun readerAdapter(): RecyclerView.Adapter<*>? =
        if (usingPager) binding.mangaReaderPager.adapter else binding.mangaReaderRecycler.adapter

    /** Every page the reader currently has in its adapter, in reading order. */
    private fun readerPages(): List<MangaImage> =
        when (val adapter = readerAdapter()) {
            is ContinuousChapterAdapter -> adapter.items.mapNotNull {
                (it as? ContinuousChapterAdapter.ReaderItem.Image)?.image
            }

            is BaseImageAdapter -> adapter.images
            else -> emptyList()
        }

    /** The page or pages one adapter position is showing. */
    private fun pagesAtPosition(position: Int): List<MangaImage> =
        when (val adapter = readerAdapter()) {
            is BaseImageAdapter -> adapter.pagesAt(position)
            is ContinuousChapterAdapter -> listOfNotNull(
                (adapter.items.getOrNull(position) as? ContinuousChapterAdapter.ReaderItem.Image)
                    ?.image,
            )

            else -> emptyList()
        }

    /**
     * How many failures in a row end an automatic run.
     *
     * A dead key or a retired model fails identically on every page, and without a limit that is
     * one doomed request per page for the rest of the chapter. One transient network error is not
     * that, which is why it is a run of them rather than the first.
     */
    private var autoFailures = 0

    /**
     * Translates pages as they come into view, when that is switched on.
     *
     * The queue is rebuilt from what is on screen on every scroll rather than added to, so the page
     * being looked at is always next — see [AutoTranslator].
     */
    private val autoTranslator by lazy {
        AutoTranslator(lifecycleScope, active = { autoTranslating() && mtlSettings().ready() }) { image ->
            if (!mtlSettings().ready()) {
                snackString(getString(R.string.mtl_needs_key))
                return@AutoTranslator false
            }
            runTranslation(image)
                .onSuccess { page ->
                    autoFailures = 0
                    if (page != null) {
                        TranslatedPages.put(image.url.url, page)
                        refreshTranslationOverlays()
                    }
                }
                .onFailure {
                    autoFailures++
                    Logger.log("MTL auto translation failed: ${it.stackTraceToString()}")
                    // Said out loud, because automatic translation has no other voice. Nobody
                    // pressed anything, so a page that fails silently is indistinguishable from
                    // the feature not being switched on — which is how a dead key, an unloaded
                    // page and an out-of-memory composite all looked the same from the outside.
                    // Once per run: the first message is the diagnosis and the rest are noise.
                    if (autoFailures == 1) {
                        snackString(it.message ?: getString(R.string.mtl_failed))
                    }
                }
            val keepGoing = autoFailures < AUTO_FAILURE_LIMIT
            if (!keepGoing) snackString(getString(R.string.mtl_auto_stopped))
            keepGoing
        }
    }

    private var autoQueuePosted = false

    /**
     * Asks for the visible pages to be queued, once, after the current layout pass.
     *
     * Called from the scroll listener, the pager's page change and every overlay binding, all of
     * which fire far more often than the queue can change; posting once and reading the positions
     * afterwards is what keeps that from being a cost on every scrolled pixel.
     */
    fun scheduleAutoTranslation() {
        if (!autoTranslating() || autoQueuePosted) return
        autoQueuePosted = true
        pageHost.post {
            autoQueuePosted = false
            queueAutoTranslation()
        }
    }

    /**
     * Which way through the chapter the reader is moving, as a step in adapter positions.
     *
     * Taken from the positions rather than from the scroll's sign, which would have to be read
     * differently for each of the four reading directions — right-to-left and bottom-to-top both
     * make a *negative* delta mean forwards. Positions only ever count one way.
     */
    private var autoDirection = 1
    private var autoAnchor = RecyclerView.NO_POSITION

    private fun queueAutoTranslation() {
        if (!autoTranslating()) return
        val (first, last) = visiblePositions() ?: return

        if (autoAnchor != RecyclerView.NO_POSITION && first != autoAnchor) {
            autoDirection = if (first > autoAnchor) 1 else -1
        }
        autoAnchor = first

        // The lookahead follows the reader instead of always pointing down the chapter. Fixed
        // forwards, scrolling back up queued nothing that was not already on screen: a page only
        // began translating once it was fully in view and took several seconds to arrive, so going
        // up looked like the feature was off while going down looked like it worked. Which way
        // "ahead" is depends on where the reader is going, and that is the only thing that decides
        // which page is worth spending a request on before it is needed.
        val ahead = if (autoDirection >= 0) {
            ((last + 1)..(last + AUTO_LOOKAHEAD)).toList()
        } else {
            ((first - 1) downTo (first - AUTO_LOOKAHEAD)).toList()
        }
        autoTranslator.onVisible(
            // What is on screen first, then what is about to be. Out-of-range positions resolve to
            // no pages, so the ends of the chapter need no special case.
            ((first..last).toList() + ahead).flatMap { pagesAtPosition(it) },
        )
    }

    /**
     * The first and last adapter positions on screen, from whichever view is showing them.
     *
     * The pager shows exactly one item, so its current page is both ends of the range — the
     * lookahead is what gets the next one translated before it is swiped to.
     */
    private fun visiblePositions(): Pair<Int, Int>? {
        if (usingPager) {
            val current = binding.mangaReaderPager.currentItem
            return current to current
        }
        val manager = binding.mangaReaderRecycler.layoutManager as? LinearLayoutManager ?: return null
        val first = manager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return null
        return first to manager.findLastVisibleItemPosition().coerceAtLeast(first)
    }

    /**
     * Called when the engine, model, script or target changes.
     *
     * A page already translated keeps its words — redoing it would spend quota to say the same
     * thing differently — but pages that failed under the old settings are worth another go, which
     * is the case the reader is usually in when they change any of this.
     */
    fun onMtlSettingsChanged() {
        autoFailures = 0
        autoTranslator.reset()
        scheduleAutoTranslation()
    }

    /**
     * Re-applies overlays to whatever is on screen.
     *
     * Cheaper and less disruptive than telling the adapter a page changed: rebinding would drop the
     * decoded bitmap and re-run the load for a change that only concerns a sibling view.
     */
    fun refreshTranslationOverlays() {
        val recycler = binding.mangaReaderRecycler
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i) ?: continue
            val holder = recycler.getChildViewHolder(child) ?: continue
            applyTranslationOverlay(child, holder.bindingAdapterPosition)
        }
    }

    /** Puts the stored translation for whatever page [itemView] is showing onto its overlay. */
    fun applyTranslationOverlay(itemView: View, position: Int) {
        val overlay = itemView.findViewById<TranslationOverlayView>(R.id.imgProgTranslation)
            ?: return
        val image = pagesAtPosition(position).firstOrNull()
        val page = image?.let { TranslatedPages[it.url.url] }
        if (page == null) overlay.clear() else overlay.setBlocks(page.blocks, page.pageWidth)
        // A page arriving on screen is the event automatic translation waits for, and this runs for
        // every one of them whether or not it has a translation yet.
        scheduleAutoTranslation()
    }

    private fun showImageDialog(
        pos: Int,
        img1: MangaImage,
        img2: MangaImage?,
        callback: ((ImageViewDialog) -> Unit)?,
    ) {
        val title = "(Page ${pos + 1}${if (img2 != null) "-${pos + 2}" else ""}) ${
            chaptersTitleArr.getOrNull(currentChapterIndex)?.replace(" : ", " - ") ?: ""
        } [${media.userPreferredName}]"

        ImageViewDialog.newInstance(title, img1.url, true, img2?.url).apply {
            val transforms1 = mutableListOf<BitmapTransformation>()
            val parserTransformation1 = getTransformation(img1)
            if (parserTransformation1 != null) transforms1.add(parserTransformation1)
            val transforms2 = mutableListOf<BitmapTransformation>()
            if (img2 != null) {
                val parserTransformation2 = getTransformation(img2)
                if (parserTransformation2 != null) transforms2.add(parserTransformation2)
            }
            val threshold = defaultSettings.cropBorderThreshold
            if (defaultSettings.cropBorders) {
                transforms1.add(RemoveBordersTransformation(true, threshold))
                transforms1.add(RemoveBordersTransformation(false, threshold))
                if (img2 != null) {
                    transforms2.add(RemoveBordersTransformation(true, threshold))
                    transforms2.add(RemoveBordersTransformation(false, threshold))
                }
            }
            trans1 = transforms1.ifEmpty { null }
            trans2 = transforms2.ifEmpty { null }
            onReloadPressed = callback
            show(supportFragmentManager, "image")
        }
    }

    fun updateMaxChapterPage(max: Long) {
        maxChapterPage = max
        binding.mangaReaderSlider.apply {
            valueTo = max.toFloat().coerceAtLeast(1f)
        }
    }

    private fun updateChapterNavigationText() {
        if (directionRLBT) {
            binding.mangaReaderNextChap.text =
                chaptersTitleArr.getOrNull(currentChapterIndex - 1) ?: ""
            binding.mangaReaderPrevChap.text =
                chaptersTitleArr.getOrNull(currentChapterIndex + 1) ?: ""
        } else {
            binding.mangaReaderNextChap.text =
                chaptersTitleArr.getOrNull(currentChapterIndex + 1) ?: ""
            binding.mangaReaderPrevChap.text =
                chaptersTitleArr.getOrNull(currentChapterIndex - 1) ?: ""
        }
    }
}
