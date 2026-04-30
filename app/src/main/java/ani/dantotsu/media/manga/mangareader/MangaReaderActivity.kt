package ani.dantotsu.media.manga.mangareader

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.AdapterView
import android.widget.CheckBox
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.math.MathUtils.clamp
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.GesturesListener
import ani.dantotsu.NoPaddingArrayAdapter
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs
import kotlin.math.min
import kotlin.properties.Delegates

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
    private val isContinuousMultiChapter: Boolean
        get() = PrefManager.getVal(PrefName.ContinuousMultiChapter)

    var sliding = false
    var isAnimating = false
    private var autoscrollTimer: Timer? = null
    var autoscrollOn = false

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
            // Continuous: scroll by more pixels per tick for higher speed
            val pxPerTick = (12 * PrefManager.getVal<Float>(PrefName.AutoScrollSpeed)).toInt()
            val interval = 50L
            autoscrollTimer = Timer()
            autoscrollTimer?.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    binding.mangaReaderRecycler.post {
                        try {
                            binding.mangaReaderRecycler.smoothScrollBy(0, pxPerTick)
                        } catch (e: Exception) {
                        }
                    }
                }
            }, interval, interval)
        }
    }

    fun stopAutoscroll() {
        autoscrollOn = false
        autoscrollTimer?.cancel()
        autoscrollTimer = null
        binding.mangaReaderAutoscroll.setImageResource(R.drawable.ic_round_play_arrow_24)
    }

    fun updateAutoscrollSpeed(newSpeed: Float) {
        PrefManager.setVal(PrefName.AutoScrollSpeed, newSpeed)
        if (autoscrollOn) {
            // restart with new speed
            autoscrollTimer?.cancel()
            startAutoscroll()
        }
    }

    override fun onDestroy() {
        ani.dantotsu.util.Logger.log("MangaReaderActivity.onDestroy: clearing cache (size: ${mangaCache.size()})")
        autoscrollTimer?.cancel()
        // Don't clear cache on destroy - let LRU manage it and preserve cache for reloads
        // mangaCache.clear()
        RPCManager.clearPresence(this)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                // Not ...opted out ...already? Somehow?
                && PrefManager.getCustomVal("${media.id}_save_progress", true)
                //  Allowing Doujin updates or not one
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

        media = if (model.getMedia().value == null)
            try {
                //(intent.getSerialized("media")) ?: return
                MediaSingleton.media ?: return
            } catch (e: Exception) {
                logError(e)
                return
            } finally {
                MediaSingleton.media = null
            }
        else model.getMedia().value ?: return
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
        if (PrefManager.getVal(PrefName.AutoDetectWebtoon) && media.countryOfOrigin != "JP") applyWebtoon(
            defaultSettings
        )
        defaultSettings = loadReaderSettings("${media.id}_current_settings") ?: defaultSettings

        chapters = media.manga?.chapters ?: return
        chapter = chapters[media.manga!!.selectedChapter!!.uniqueNumber()] ?: return

        model.mangaReadSources = if (media.isAdult) HMangaSources else MangaSources
        binding.mangaReaderSource.isVisible = PrefManager.getVal(PrefName.ShowSource)
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
            PrefManager.setCustomVal(
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
                        val buttons = mutableListOf<RPC.Link>()
                        // Prefer the MangaUpdates sharing link for MU-sourced media; fallback to computed URL only if shareLink is missing
                        val muUrl = if (media.muSeriesId != null) {
                            media.shareLink?.takeIf { it.contains("mangaupdates") }
                                ?: "https://www.mangaupdates.com/series/${media.muSeriesId!!.toString(36)}"
                        } else null
                        muUrl?.let { buttons.add(RPC.Link("View on MangaUpdates", it)) }

                        // Fallback / additional trackers
                        buttons.add(RPC.Link("View Manga", "https://anilist.co/manga/${media.id}/"))
                        media.idMAL?.let {
                            buttons.add(RPC.Link("View on MyAnimeList", "https://myanimelist.net/manga/$it"))
                        }
                        val rpcData = RPC.Companion.RPCData(
                            applicationId = Discord.application_Id,
                            type = RPC.Type.WATCHING,
                            activityName = media.userPreferredName,
                            details = chap.title?.takeIf { it.isNotEmpty() }
                                ?: getString(R.string.chapter_num, chap.number),
                            state = "Chapter : ${chap.number}/${media.manga?.totalChapters ?: "??"}",
                            largeImage = media.cover?.let { cover ->
                                RPC.Link(
                                    media.userPreferredName,
                                    cover
                                )
                            },
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

        binding.mangaReaderPager.unregisterOnPageChangeCallback(pageChangeCallback)

        currentChapterPage = PrefManager.getCustomVal("${media.id}_${chapter.number}", 1L)

        val chapImages = if (directionPagedBT) {
            chapter.images().reversed()
        } else {
            chapter.images()
        }

        maxChapterPage = 0
        if (chapImages.isNotEmpty()) {
            maxChapterPage = chapImages.size.toLong()
            PrefManager.setCustomVal("${media.id}_${chapter.number}_max", maxChapterPage)

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
            manager.preloadItemCount = 5

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

                layoutManager = manager
                setOnTouchListener { _, event ->
                    if (event != null)
                        tryWith { detector.onTouchEvent(event) } ?: false
                    else false
                }

                manager.setStackFromEnd(defaultSettings.direction == BOTTOM_TO_TOP)

                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(v: RecyclerView, dx: Int, dy: Int) {
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
                                } else handleController(false)
                            }
                            updatePageNumber(
                                manager.findLastVisibleItemPosition().toLong() * (dualPage { 2 }
                                    ?: 1) + 1)
                        }
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

                if (!isContinuousMultiChapter) {
                    scrollToPosition(currentPage / (dualPage { 2 } ?: 1) - 1)
                } else {
                    val startOfChapter = continuousAdapter?.getChapterStartPosition(currentChapterIndex) ?: 0
                    val target = (startOfChapter + (currentChapterPage.toInt() - 1)).coerceAtLeast(0)
                    scrollToPosition(target)
                }
            }
        } else {
            binding.mangaReaderRecyclerContainer.visibility = View.GONE
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
                offscreenPageLimit = 5

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

    private var loading = false
    fun updatePageNumber(pageNumber: Long) {
        var page = pageNumber
        if (directionPagedBT) {
            page = maxChapterPage - pageNumber + 1
        }
        if (currentChapterPage != page) {
            currentChapterPage = page
            PrefManager.setCustomVal("${media.id}_${chapter.number}", page)
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
            } else handleController(false)
        }

        // Track current chapter based on the last visible Image (skip Transition/Boundary
        // so progress keeps tracking the chapter when those non-image items are on screen).
        val refPos = adapter.lastImagePositionAtOrBefore(lastVisible)
        val visibleChapterIdx = if (refPos >= 0) adapter.getChapterIndexAt(refPos) else null
        if (visibleChapterIdx != null && visibleChapterIdx != lastTrackedChapterIndex) {
            // Chapter changed — update progress for the previous chapter
            if (lastTrackedChapterIndex >= 0 && lastTrackedChapterIndex != visibleChapterIdx) {
                val prevKey = chaptersArr.getOrNull(lastTrackedChapterIndex)
                val prevChap = if (prevKey != null) chapters[prevKey] else null
                if (prevChap != null) {
                    updateMultiChapterProgressSilently(prevChap)
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
                PrefManager.setCustomVal("${media.id}_current_chp", chapter.number)
                binding.mangaReaderChapterSelect.setSelection(currentChapterIndex)
                updateChapterNavigationText()
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
                PrefManager.setCustomVal("${media.id}_${chapter.number}", currentChapterPage)
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
            if (lastTrackedChapterIndex >= 0 && lastTrackedChapterIndex != visibleChapterIdx) {
                val prevKey = chaptersArr.getOrNull(lastTrackedChapterIndex)
                val prevChap = if (prevKey != null) chapters[prevKey] else null
                if (prevChap != null) {
                    updateMultiChapterProgressSilently(prevChap)
                }
            }
            lastTrackedChapterIndex = visibleChapterIdx

            currentChapterIndex = visibleChapterIdx
            val key = chaptersArr.getOrNull(visibleChapterIdx)
            val currentChap = if (key != null) chapters[key] else null
            if (currentChap != null) {
                chapter = currentChap
                media.manga!!.selectedChapter = chapter
                PrefManager.setCustomVal("${media.id}_current_chp", chapter.number)
                binding.mangaReaderChapterSelect.setSelection(currentChapterIndex)
                updateChapterNavigationText()
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
                PrefManager.setCustomVal("${media.id}_${chapter.number}", currentChapterPage)
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
                        // RecyclerView: remember and restore scroll position
                        val layoutManager = binding.mangaReaderRecycler.layoutManager as? PreloadLinearLayoutManager
                        val firstVisiblePos = layoutManager?.findFirstVisibleItemPosition() ?: 0
                        val firstVisibleView = layoutManager?.findViewByPosition(firstVisiblePos)
                        val offset = firstVisibleView?.top ?: 0

                        adapter.prependChapter(prevChapter, prevIdx, missing)
                        layoutManager?.scrollToPositionWithOffset(firstVisiblePos + insertedCount, offset)
                    } else {
                        // ViewPager2: remember current item and offset by inserted count
                        val currentItem = binding.mangaReaderPager.currentItem
                        adapter.prependChapter(prevChapter, prevIdx, missing)
                        binding.mangaReaderPager.setCurrentItem(currentItem + insertedCount, false)
                    }
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

    private fun progress(runnable: Runnable) {
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
                    setOnCancelListener { hideSystemBars() }
                    show()

                }
            } else {
                if (!incognito && PrefManager.getCustomVal(
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
     * Silently updates progress when crossing a chapter boundary
     * in continuous multi-chapter mode, based on the user's preference established on open.
     */
    private fun updateMultiChapterProgressSilently(completedChapter: MangaChapter) {
        if (Anilist.userid == null) return
        val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
        if (incognito) return
        if (media.isAdult && !PrefManager.getVal<Boolean>(PrefName.UpdateForHReader)) return

        val chapterNum = MediaNameAdapter.findChapterNumber(completedChapter.number)?.toString() ?: return

        if (PrefManager.getCustomVal("${media.id}_save_progress", true)) {
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

    fun getTransformation(mangaImage: MangaImage): BitmapTransformation? {
        return model.loadTransformation(mangaImage, media.selected!!.sourceIndex)
    }

    fun onImageLongClicked(
        pos: Int,
        img1: MangaImage,
        img2: MangaImage?,
        callback: ((ImageViewDialog) -> Unit)? = null
    ): Boolean {
        if (!defaultSettings.longClickImage) return false
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
        return true
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
