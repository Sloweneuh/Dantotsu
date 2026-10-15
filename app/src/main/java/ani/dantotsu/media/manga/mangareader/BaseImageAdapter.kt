package ani.dantotsu.media.manga.mangareader

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources.getSystem
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.FileUrl
import ani.dantotsu.GesturesListener
import ani.dantotsu.R
import ani.dantotsu.media.manga.MangaCache
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.media.manga.translation.TranslationOverlayView
import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.px
import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.tryWithSuspend
import com.alexvasilkov.gestures.GestureController
import com.alexvasilkov.gestures.views.GestureFrameLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import kotlin.math.sqrt

abstract class BaseImageAdapter(
    val activity: MangaReaderActivity,
    chapter: MangaChapter,
    /**
     * The chapter's real pages, when they differ from its images (see [ChapterPages]). Each
     * position is then one real page, drawn from whatever slices of images it is made of.
     */
    pageMap: ChapterPageMap? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val settings get() = activity.defaultSettings
    protected val chapterImages = chapter.images()
    var images = chapterImages
    private val chapterPages = pageMap?.pages
    protected var pages = chapterPages

    /** The images' urls, by chapter image index — what page keys are built from. */
    private val urls = chapterImages.map { it.url.url }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        val reversed = settings.layout == CurrentReaderSettings.Layouts.PAGED
                && settings.direction == CurrentReaderSettings.Directions.BOTTOM_TO_TOP
        images = if (reversed) chapterImages.reversed() else chapterImages
        pages = if (reversed) chapterPages?.reversed() else chapterPages
        recycler = recyclerView
        super.onAttachedToRecyclerView(recyclerView)
    }

    /** The real page at [position], when the adapter goes by real pages. */
    fun pageAt(position: Int): ChapterPage? = pages?.getOrNull(position)

    /** How many positions there are: real pages if known, images otherwise. */
    val pageCount get() = pages?.size ?: images.size

    /** The page shown at [position], used to tie loads to pages rather than to positions. */
    open fun pageKey(position: Int): Any? =
        if (pages != null) pageAt(position) else images.getOrNull(position)

    /** Everything [position] displays — what [PagePrefetcher] must warm to make it instant. */
    open fun pagesAt(position: Int): List<MangaImage> =
        if (pages != null) pageAt(position)?.slices.orEmpty()
            .mapNotNull { chapterImages.getOrNull(it.image) }.distinct()
        else listOfNotNull(images.getOrNull(position))

    /**
     * What the page at [position] is known by to [PageEdges]: its image's url for a plain page, a
     * key of its own for one drawn from slices. Null for a merged dual page.
     */
    open fun pageUrl(position: Int): String? =
        if (pages != null) pageAt(position)?.key(urls)
        else pagesAt(position).singleOrNull()?.url?.url

    /**
     * The images whose translations belong over [position]. A page drawn from slices has none: a
     * translation is laid out over the whole image it was read from.
     */
    open fun translatableAt(position: Int): List<MangaImage> =
        if (pages != null) listOfNotNull(plainImageAt(position)) else pagesAt(position)

    /** The image of a plain page at [position] — the only kind a translation can be laid over. */
    fun plainImageAt(position: Int): MangaImage? =
        if (pages != null) pageAt(position)?.takeIf { it.single }
            ?.let { chapterImages.getOrNull(it.slices[0].image) }
        else pagesAt(position).singleOrNull()

    /**
     * Draws the real [page] from its images, decoded the way a page of this layout is
     * ([maxHeightOverride], [zoom]). A plain page is just its image.
     */
    protected suspend fun composePage(
        page: ChapterPage,
        maxHeightOverride: Int?,
        zoom: Float
    ): Bitmap? {
        // A page cut from a strip is decoded with the strip, at the scrolling reader's budget: the
        // paged one is sized for a single page and would leave a strip's pages a fraction as wide.
        val budget = if (page.single) maxHeightOverride else ContinuousChapterAdapter.MAX_PAGE_HEIGHT
        val bitmaps = page.slices.map { slice ->
            val image = chapterImages.getOrNull(slice.image) ?: return null
            if (image.url.url.isEmpty()) return null
            activity.loadBitmap(
                image.url, activity.pageTransforms(image), budget, zoom, wholeWidth = !page.single
            ) ?: return null
        }
        if (page.single) return bitmaps.first()
        return withContext(Dispatchers.Default) { ChapterPages.compose(page, bitmaps) }
    }

    protected var recycler: RecyclerView? = null

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        recycler = null
    }

    /**
     * The gaps before and after the page at [position] in a scrolling layout with spacing on.
     * Without smart spacing every page keeps its trailing gap, as it always has. With it, the gap
     * between two pages sits before the later one and is only there when [PageEdges] says the
     * boundary is a real one; the last page keeps its trailing gap. A merged dual page is never
     * judged, there being no single page edge to judge it by.
     */
    fun pageGaps(position: Int): Pair<Int, Int> {
        val gap = 16f.px
        if (!activity.smartSpacing) return 0 to gap
        val trailing = if (position >= itemCount - 1) gap else 0
        if (position <= 0) return 0 to trailing
        val before = pageUrl(position - 1)
        val after = pageUrl(position)
        // Going by real pages, the boundaries are known: every one is a page edge, except where a
        // webtoon's pieces were only split up for size.
        if (after != null) pageAt(position)?.let {
            return (if (it.joinedToPrevious) 0 else gap) to trailing
        }
        val spaced = before == null || after == null || PageEdges.shouldSpace(
            PageEdges.get(before), PageEdges.get(after), settings.direction, activity.preferJoinedPages
        ) || PageEdges.breaksFor(after)?.atStart == true
        return (if (spaced) gap else 0) to trailing
    }

    /**
     * Has [PageEdges.judgeRun] look for stitched pages in the image at [position] together with
     * its neighbours. True when what it found changed.
     */
    suspend fun judgeStitched(position: Int): Boolean {
        // Real pages are already cut where the breaks are.
        if (!activity.smartSpacing || pages != null) return false
        val urls = (position - RUN_REACH..position + RUN_REACH).map { pageUrl(it) }
        return withContext(Dispatchers.Default) { PageEdges.judgeRun(urls, RUN_REACH) }
    }

    /**
     * Judges again the pages on screen, a neighbour having just been profiled: their gaps may
     * depend on it. A page whose stitched page breaks changed is redrawn in place.
     */
    fun reconsiderGaps() {
        if (!activity.smartSpacing || settings.layout == CurrentReaderSettings.Layouts.PAGED) return
        val recycler = recycler ?: return
        for (i in 0 until recycler.childCount) {
            val view = recycler.getChildAt(i)
            val position = recycler.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) continue
            if ((view.tag as? PageLoad)?.loaded != true) continue
            activity.lifecycleScope.launch {
                if (judgeStitched(position)) loadImage(position, view, quiet = true)
                else {
                    val (leading, trailing) = pageGaps(position)
                    setPageGaps(view, settings.direction, leading, trailing)
                }
            }
        }
    }

    /**
     * Re-judges the gap in front of the page after [position], now that this one's edges are
     * known. Reading forward that page has rarely loaded yet and will judge for itself; this is
     * for the one that got there first (a jump, or reading backwards).
     */
    protected fun refreshNextGap(position: Int) {
        if (!activity.smartSpacing || settings.layout == CurrentReaderSettings.Layouts.PAGED) return
        val next = recycler?.findViewHolderForAdapterPosition(position + 1)?.itemView ?: return
        val (leading, trailing) = pageGaps(position + 1)
        setPageGaps(next, settings.direction, leading, trailing)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val view = holder.itemView as GestureFrameLayout
        view.controller.also {
            if (settings.layout == CurrentReaderSettings.Layouts.PAGED) {
                it.settings.enableGestures()
            }
            it.settings.isRotationEnabled = settings.rotation
        }
        // A forced relayout (e.g. the blank-screen recovery in onResume/onConfigurationChanged)
        // can make RecyclerView rebind a view that is already showing this exact page. Binding it
        // again would shrink it back to the placeholder size and re-decode the image, so a page
        // that is already on screen is left alone until the view is genuinely recycled.
        val boundPosition = holder.bindingAdapterPosition
        val page = pageKey(boundPosition)
        val alreadyShown = page != null && view.isShowingPage(page)
        if (settings.layout != CurrentReaderSettings.Layouts.PAGED && !alreadyShown) {
            if (settings.padding) {
                val (leading, trailing) = pageGaps(boundPosition)
                setPageGaps(view, settings.direction, leading, trailing)
            } else {
                view.setPadding(0, 0, 0, 0)
            }
            view.updateLayoutParams {
                if (settings.direction != CurrentReaderSettings.Directions.LEFT_TO_RIGHT && settings.direction != CurrentReaderSettings.Directions.RIGHT_TO_LEFT) {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = 480f.px
                } else {
                    width = 480f.px
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
        }
        if (settings.layout == CurrentReaderSettings.Layouts.PAGED) {
            view.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val detector = GestureDetectorCompat(view.context, object : GesturesListener() {
                override fun onSingleClick(event: MotionEvent) =
                    activity.handleController(event = event)
            })
            view.findViewById<View>(R.id.imgProgCover).apply {
                setOnTouchListener { _, event ->
                    detector.onTouchEvent(event)
                    false
                }
                setOnLongClickListener {
                    val pos = holder.bindingAdapterPosition
                    val image = pagesAt(pos).firstOrNull() ?: return@setOnLongClickListener false
                    activity.onImageLongClicked(pos, image, null) { dialog ->
                        activity.lifecycleScope.launch {
                            loadImage(pos, view)
                        }
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        dialog.dismiss()
                    }
                }
            }
        }
        watchZoom(view) { activity.lifecycleScope.launch { loadImage(boundPosition, view) } }
        if (alreadyShown) return
        activity.lifecycleScope.launch { loadImage(boundPosition, view) }
    }


    /**
     * Hands the page's pixels back as soon as the view reaches the recycled pool.
     *
     * Nothing did this before: an item view only ever dropped its bitmap when [loadImage] ran on it
     * again, so a holder sitting in the pool went on holding a full-resolution page for as long as
     * it sat there. RecyclerView keeps up to five holders per view type, and a page here is tens of
     * megabytes, so reading through a chapter accumulated offscreen pages that nothing on screen
     * referenced and nothing would free until the pool happened to reuse them. The dual-page
     * adapter is the worst of it, since the merged bitmap it displays is the width of two pages and
     * is held by nothing but the view.
     *
     * The bitmap is not recycled, only released: it was handed over as `ImageSource.cachedBitmap`,
     * so [SubsamplingScaleImageView.recycle] drops the reference and leaves the pixels alone —
     * which is what we want, since a plain page's instance usually lives on in
     * [ani.dantotsu.media.manga.MangaCache] and one scrolled back into view should come from there
     * rather than be decoded again. Clearing the tag alongside it retires the [PageLoad]: a load
     * still in flight for this view now fails [stillOwns] and discards its result instead of
     * painting it onto a holder that has moved on to another page.
     */
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        holder.itemView.findViewById<SubsamplingScaleImageView>(R.id.imgProgImageNoGestures)
            ?.recycle()
        // Cleared with the pixels it belonged to. A holder returning to the pool still holding one
        // page's translation would flash it over whichever page reuses the view.
        holder.itemView.findViewById<TranslationOverlayView>(R.id.imgProgTranslation)?.clear()
        holder.itemView.tag = null
    }

    abstract fun isZoomed(): Boolean
    abstract fun setZoom(zoom: Float)

    /**
     * Loads the page at [position] into [parent]. [quiet] redraws a page already on screen in
     * place — no spinner, no fade — for when only its gaps changed.
     */
    abstract suspend fun loadImage(position: Int, parent: View, quiet: Boolean = false): Boolean

    companion object {
        /**
         * Re-renders a page once a zoom settles, so it is decoded for the size it is being shown at.
         *
         * The wrapper zooms by transforming the view, which leaves the GPU stretching whatever bitmap
         * the page was decoded into — detail the fitted render never had cannot appear that way. So
         * the zoom is watched, and when it stops changing the page is read again from its cached
         * bytes at the new size. Deliberately on settle rather than during the gesture: a decode per
         * frame of a pinch would cost far more than it showed.
         *
         * The page is claimed through the same [beginPageLoad] ownership as any other load, so a
         * re-render that finishes after the view has moved on is discarded rather than painted onto
         * whatever page is there now.
         */
        fun watchZoom(view: GestureFrameLayout, reload: () -> Unit) {
            (view.getTag(R.id.page_zoom_listener) as? GestureController.OnStateChangeListener)
                ?.let { view.controller.removeOnStateChangeListener(it) }

            val rerender = Runnable {
                val zoom = view.controller.state.zoom.takeIf { it > 0f } ?: return@Runnable
                val rendered = view.getTag(R.id.page_render_zoom) as? Float ?: 1f
                // Ignore the small drift a settle leaves behind; only a real change is worth a decode.
                if (zoom <= 1.05f && rendered <= 1f) return@Runnable
                if (kotlin.math.abs(zoom - rendered) < 0.25f) return@Runnable
                view.setTag(R.id.page_render_zoom, zoom)
                reload()
            }

            val listener = object : GestureController.OnStateChangeListener {
                override fun onStateChanged(state: com.alexvasilkov.gestures.State) {
                    view.removeCallbacks(rerender)
                    view.postDelayed(rerender, ZOOM_SETTLE_MS)
                }

                override fun onStateReset(
                    oldState: com.alexvasilkov.gestures.State,
                    newState: com.alexvasilkov.gestures.State
                ) = Unit
            }
            view.controller.addOnStateChangeListener(listener)
            view.setTag(R.id.page_zoom_listener, listener)
        }

        /**
         * Sets a scrolling page's gap before it ([leading], toward the previous page) and after it
         * ([trailing]) along [direction]. A page already on screen has been sized to include its
         * padding, so it grows or shrinks by the change and its image stays the size it was.
         */
        fun setPageGaps(
            view: View,
            direction: CurrentReaderSettings.Directions,
            leading: Int,
            trailing: Int
        ) {
            val vertical = direction == CurrentReaderSettings.Directions.TOP_TO_BOTTOM ||
                    direction == CurrentReaderSettings.Directions.BOTTOM_TO_TOP
            fun along() = if (vertical) view.paddingTop + view.paddingBottom
                          else view.paddingLeft + view.paddingRight
            val before = along()
            when (direction) {
                CurrentReaderSettings.Directions.TOP_TO_BOTTOM -> view.setPadding(0, leading, 0, trailing)
                CurrentReaderSettings.Directions.LEFT_TO_RIGHT -> view.setPadding(leading, 0, trailing, 0)
                CurrentReaderSettings.Directions.BOTTOM_TO_TOP -> view.setPadding(0, trailing, 0, leading)
                CurrentReaderSettings.Directions.RIGHT_TO_LEFT -> view.setPadding(trailing, 0, leading, 0)
            }
            val delta = along() - before
            if (delta == 0 || (view.tag as? PageLoad)?.loaded != true) return
            view.updateLayoutParams {
                if (vertical && height > 0) height += delta
                else if (!vertical && width > 0) width += delta
            }
        }

        /**
         * [bitmap] as it should be shown in [parent]: with a gap at each page break when it is a
         * strip of stitched pages and smart spacing is on (see [PageEdges.withGaps]). Only in a
         * vertical scrolling layout — the breaks are rows. The gap is the same 16dp as between
         * separate pages, converted to this bitmap's pixels, so a zoomed render gets it to scale.
         */
        fun withStripGaps(
            activity: MangaReaderActivity,
            url: String?,
            bitmap: Bitmap,
            parent: View
        ): Bitmap {
            if (url == null || !activity.smartSpacing) return bitmap
            val settings = activity.defaultSettings
            if (settings.layout == CurrentReaderSettings.Layouts.PAGED) return bitmap
            if (settings.direction != CurrentReaderSettings.Directions.TOP_TO_BOTTOM &&
                settings.direction != CurrentReaderSettings.Directions.BOTTOM_TO_TOP
            ) return bitmap
            val viewportWidth = pageViewport(parent).first.takeIf { it > 0 } ?: return bitmap
            val gap = (16f.px * bitmap.width / viewportWidth.toFloat()).toInt()
            return PageEdges.withGaps(url, bitmap, gap)
        }

        /** The zoom a page's current bitmap was decoded for; 1 means the plain fitted render. */
        fun renderZoomOf(parent: View): Float =
            (parent.getTag(R.id.page_render_zoom) as? Float) ?: 1f

        /**
         * The space actually available to a page: the RecyclerView the item sits in, minus every
         * padding between that and the image. The display metrics are only a fallback for a
         * viewport that isn't measured yet — they cover the whole screen, including the system
         * bars and the display cutout, and fitting a page to the screen rather than to the
         * viewport is what leaves it cropped at the top or the bottom.
         */
        fun pageViewport(parent: View): Pair<Int, Int> {
            val host = parent.parent as? View
            val metrics = getSystem().displayMetrics
            val hostWidth = host?.run { width - paddingLeft - paddingRight }?.takeIf { it > 0 }
                ?: metrics.widthPixels
            val hostHeight = host?.run { height - paddingTop - paddingBottom }?.takeIf { it > 0 }
                ?: metrics.heightPixels
            return (hostWidth - parent.paddingLeft - parent.paddingRight) to
                    (hostHeight - parent.paddingTop - parent.paddingBottom)
        }

        suspend fun Context.loadBitmapOld(
            link: FileUrl,
            transforms: List<BitmapTransformation>
        ): Bitmap? { //still used in some places
            return tryWithSuspend {
                val dm = resources.displayMetrics
                val maxW = dm.widthPixels * 2
                val maxH = dm.heightPixels * 2
                withContext(Dispatchers.IO) {
                    Glide.with(this@loadBitmapOld)
                        .asBitmap()
                        .let {
                            if (link.url.startsWith("file://")) {
                                it.load(link.url)
                                    .skipMemoryCache(true)
                                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                            } else {
                                it.load(GlideUrl(link.url) { link.headers })
                            }
                        }
                        .override(maxW, maxH)
                        .downsample(DownsampleStrategy.AT_MOST)
                        .let {
                            if (transforms.isNotEmpty()) {
                                it.transform(*transforms.toTypedArray())
                            } else {
                                it
                            }
                        }
                        .submit()
                        .get()
                }
            }
        }

        /**
         * Decodes the page at [link]. [wholeWidth] decodes it to be shown at the viewport's full
         * width whatever the layout — for an image that is cut into pages rather than shown whole,
         * which fitted into a paged viewport's height would be a sliver a strip's height wide.
         */
        suspend fun Context.loadBitmap(
            link: FileUrl,
            transforms: List<BitmapTransformation>,
            maxHeightOverride: Int? = null,
            zoom: Float = 1f,
            wholeWidth: Boolean = false,
        ): Bitmap? {
            return tryWithSuspend {
                val mangaCache = uy.kohesive.injekt.Injekt.get<MangaCache>()
                val dm = resources.displayMetrics
                val maxW = dm.widthPixels * 2
                val maxH = maxHeightOverride ?: (dm.heightPixels * 2)
                // What the page is really drawn into, kept apart from the decode budget above:
                // the budget bounds memory, this bounds resampling. The display metrics are the
                // wrong figure for it — in a window (split screen, desktop mode, or WSA, where
                // the display is the whole 1920x1080 desktop while the reader is a fraction of
                // it) they describe the screen and not the reader, and overstating the width that
                // far skips the downsample altogether. See [downsampleBitmap].
                val reader = this@loadBitmap as? MangaReaderActivity
                val decor = reader?.window?.decorView
                val viewportW = decor?.width?.takeIf { it > 0 } ?: dm.widthPixels
                // A continuous page fills the width and scrolls on past the bottom, so its height
                // must not constrain it. A paged one is fitted whole, and in a landscape window
                // it is the height that decides how wide it actually lands.
                val viewportH =
                    if (wholeWidth || reader?.defaultSettings?.layout == CurrentReaderSettings.Layouts.CONTINUOUS) null
                    else decor?.height?.takeIf { it > 0 } ?: dm.heightPixels
                withContext(Dispatchers.IO) {
                    // Zoomed in: the page is being drawn larger than it was decoded for, so
                    // re-read it from the bytes at the size actually on screen. Straight from the
                    // cache, no refetch, and filtered the same way — which is the whole reason
                    // this beats letting the GPU stretch what it already has.
                    if (zoom > 1f) {
                        renderZoomed(link.url, transforms, viewportW, viewportH, zoom)
                            ?.let { return@withContext it }
                    }
                    // Downloaded PDF chapters: render the requested page on demand.
                    if (PdfPageRenderer.isPdfPage(link.url)) {
                        return@withContext PdfPageRenderer.render(this@loadBitmap, link.url, maxW)
                    }

                    val localFile = File(link.url)
                    if (localFile.exists()) {
                        // Downsampled here rather than through Glide's own override: its sampling
                        // is by powers of two, so asking it for the display width directly
                        // undershoots to half of it and the page comes back soft. Let it decode
                        // to the budget and take the last step properly. Same below.
                        val (overrideW, overrideH) = pixelBudgetOverride(localFile, maxW, maxH)
                        return@withContext downsampleBitmap(
                            Glide.with(this@loadBitmap)
                                .asBitmap()
                                .load(localFile.absoluteFile)
                                .skipMemoryCache(true)
                                .diskCacheStrategy(DiskCacheStrategy.NONE)
                                .override(overrideW, overrideH)
                                .downsample(DownsampleStrategy.AT_MOST)
                                .let {
                                    if (transforms.isNotEmpty()) it.transform(*transforms.toTypedArray())
                                    else it
                                }
                                .submit()
                                .get(),
                            maxW, maxH, viewportW, viewportH
                        )
                    }

                    if (link.url.startsWith("content://")) {
                        return@withContext downsampleBitmap(
                            Glide.with(this@loadBitmap)
                                .asBitmap()
                                .load(Uri.parse(link.url))
                                .skipMemoryCache(true)
                                .diskCacheStrategy(DiskCacheStrategy.NONE)
                                .override(maxW, maxH)
                                .downsample(DownsampleStrategy.AT_MOST)
                                .let {
                                    if (transforms.isNotEmpty()) it.transform(*transforms.toTypedArray())
                                    else it
                                }
                                .submit()
                                .get(),
                            maxW, maxH, viewportW, viewportH
                        )
                    }

                    // For extension sources: check bitmap cache before any network work
                    val imageData = mangaCache.get(link.url)
                    ani.dantotsu.util.Logger.log("MangaCache GET: key='${link.url}', found=${imageData != null}")
                    if (imageData != null) {
                        // Keyed on the size it was decoded for as well: the window can change size
                        // without the page changing (resizing or maximizing it on WSA, a desktop
                        // mode, split screen), and a page decoded for the smaller window would
                        // otherwise be served from here and stretched up to the larger one.
                        val cacheKey = buildBitmapCacheKey(link.url, transforms) +
                            "|${viewportW}x${viewportH ?: 0}|${maxW}x$maxH"
                        mangaCache.getBitmap(cacheKey)?.takeIf { !it.isRecycled }
                            ?.let { return@withContext it }

                        ani.dantotsu.util.Logger.log("Using extension client for: ${link.url}")
                        val rawBitmap =
                            imageData.fetchAndProcessImage(
                                imageData.page, imageData.source, maxW, maxH, cacheKey = link.url
                            )
                                ?: return@withContext null

                        // Downsample before transforms so we never hold a full-res bitmap in memory.
                        val downsampledBitmap = downsampleBitmap(rawBitmap, maxW, maxH, viewportW, viewportH)
                        // A scaled copy leaves the full-resolution decode behind as garbage that
                        // the collector only gets to when it next runs — and with two prefetch
                        // workers decoding alongside the visible page, that is precisely when the
                        // heap is tightest. Nothing else can reach it: it came straight out of the
                        // decode above and was never handed to a cache or a view.
                        if (downsampledBitmap !== rawBitmap) rawBitmap.recycle()

                        // Apply transforms via a Glide in-memory request (no network I/O —
                        // bitmap is already decoded). Result is cached below so this only
                        // runs on the first load of each page.
                        val processed = if (transforms.isNotEmpty()) {
                            Glide.with(this@loadBitmap)
                                .asBitmap()
                                .load(downsampledBitmap)
                                .transform(*transforms.toTypedArray())
                                .submit()
                                .get()
                        } else downsampledBitmap

                        mangaCache.putBitmap(cacheKey, processed)
                        return@withContext processed
                    }

                    // Fallback to standard Glide for plain remote URLs
                    return@withContext downsampleBitmap(
                        Glide.with(this@loadBitmap)
                            .asBitmap()
                            .load(GlideUrl(link.url) { link.headers })
                            .override(maxW, maxH)
                            .downsample(DownsampleStrategy.AT_MOST)
                            .let {
                                if (transforms.isNotEmpty()) it.transform(*transforms.toTypedArray())
                                else it
                            }
                            .submit()
                            .get(),
                        maxW, maxH, viewportW, viewportH
                    )
                }.also { bitmap ->
                    // Profiled while it is at hand — the prefetcher's decodes included, so the
                    // page before is usually known by the time smart spacing needs it. A zoomed
                    // render is the same page, already profiled at its fitted size.
                    if (bitmap != null && zoom <= 1f) {
                        withContext(Dispatchers.Default) { PageEdges.record(link.url, bitmap) }
                    }
                }
            }
        }

        /**
         * The page re-read at the size a zoom is showing it at, or null when it cannot be.
         *
         * Only the encoded bytes make this worth doing: they still hold every pixel the source
         * had, so a zoom can be answered with real detail instead of a magnified copy of the
         * fitted bitmap. Nothing is refetched — a page whose bytes have been evicted simply
         * returns null and the caller carries on with the bitmap it already has.
         *
         * Capped at both the source's own resolution (past which there is nothing further to
         * show) and a pixel ceiling, so holding a deeply zoomed page cannot cost more than a
         * couple of ordinary ones.
         */
        private fun renderZoomed(
            url: String,
            transforms: List<BitmapTransformation>,
            viewportWidth: Int,
            viewportHeight: Int?,
            zoom: Float
        ): Bitmap? {
            if (transforms.isNotEmpty()) return null
            val cache = uy.kohesive.injekt.Injekt.get<MangaCache>()
            val bytes = cache.getPageBytes(url) ?: return null

            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val fitted = fittedWidth(bounds.outWidth, bounds.outHeight, viewportWidth, viewportHeight)
            val wanted = (fitted * zoom).toInt()
                .coerceAtMost(bounds.outWidth)
                .coerceAtMost(MAX_ZOOMED_WIDTH)
            // Nothing to gain over what the fitted render already produced.
            if (wanted <= fitted) return null

            val key = "$url|zoom$wanted"
            cache.getBitmap(key)?.takeIf { !it.isRecycled }?.let { return it }

            val options = android.graphics.BitmapFactory.Options().apply {
                // Only ever sampled down to the ceiling, and averaged the rest of the way by
                // downsampleBitmap — sampling is not a resize, see MangaCache.
                var sample = 1
                while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) >
                    MAX_ZOOMED_PIXELS
                ) sample *= 2
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = android.graphics.BitmapFactory
                .decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val scaled = downsampleBitmap(decoded, wanted, Int.MAX_VALUE / wanted, wanted, null)
            if (scaled !== decoded) decoded.recycle()
            cache.putBitmap(key, scaled)
            return scaled
        }

        /** The width a page lands at once fitted into the viewport, mirroring [downsampleBitmap]. */
        private fun fittedWidth(
            sourceWidth: Int,
            sourceHeight: Int,
            viewportWidth: Int,
            viewportHeight: Int?
        ): Int = if (viewportHeight != null && sourceHeight > 0) {
            minOf(
                viewportWidth.toLong(),
                viewportHeight.toLong() * sourceWidth / sourceHeight
            ).toInt().coerceAtLeast(1)
        } else viewportWidth

        /**
         * The size to ask Glide for when decoding [file] within a [maxWidth] × [maxHeight] pixel
         * budget. Glide fits an override in both dimensions, which on a tall strip is a height cap:
         * an 800x10000 strip against a 4-screen-tall budget came back at half its width. So the
         * override is the image's own shape scaled to twice the budget's pixel count — the same
         * headroom MangaCache's sampling gets — and [downsampleBitmap] takes it the rest of the
         * way, filtering.
         */
        private fun pixelBudgetOverride(file: File, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return maxWidth to maxHeight
            val budget = 2.0 * maxWidth * maxHeight
            val scale = minOf(1.0, sqrt(budget / (width.toDouble() * height)))
            return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
        }

        /**
         * Brings a decoded page down to the size it will actually be drawn at.
         *
         * The width it will be *drawn* at — not the memory budget — is what bounds it, and that
         * is the whole point. Manga greys are halftone screentones, and a texture sample minifying
         * a dot grid reads a 2×2 neighbourhood no matter how far it is shrinking, so past 2× it
         * misses most of the dots it should be averaging and they beat against the pixel grid as
         * visible moiré. Resolving the screentone to flat grey here, in steps that each average
         * their whole footprint, leaves the GPU drawing roughly 1:1 with nothing left to alias.
         *
         * [viewportHeight] is null for a continuous layout, where a page fills the width and
         * scrolls on past the bottom. Given one, the page is fitted whole and its drawn width is
         * whichever of the two bounds binds first — in a landscape window on a portrait page that
         * is the height, by a wide margin, and going by the width alone leaves the downsample
         * doing nothing at all.
         *
         * [maxWidth] × [maxHeight] stays the memory bound it always was and still applies on its
         * own, which is what keeps an extremely tall strip in hand. Width is otherwise held to the
         * drawn width alone, so a page already narrower than the viewport — a long-strip page
         * usually is — keeps its own resolution instead of being crushed to fit a pixel budget.
         */
        private fun downsampleBitmap(
            bitmap: Bitmap,
            maxWidth: Int,
            maxHeight: Int,
            viewportWidth: Int,
            viewportHeight: Int?
        ): Bitmap {
            val drawnWidth = if (viewportHeight != null && bitmap.height > 0) {
                minOf(
                    viewportWidth.toLong(),
                    viewportHeight.toLong() * bitmap.width / bitmap.height
                ).toInt().coerceAtLeast(1)
            } else viewportWidth
            val maxPixels = maxWidth.toLong() * maxHeight.toLong()
            val widthScale = drawnWidth.toFloat() / bitmap.width
            val pixelScale = sqrt(maxPixels.toFloat() / (bitmap.width.toFloat() * bitmap.height.toFloat()))
            val scale = minOf(1f, widthScale, pixelScale)
            if (scale >= 1f) return bitmap

            val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)

            // Halve while that still overshoots the target. A halving step samples exactly the 2×2
            // block it replaces, so it averages the full footprint; going straight to the target
            // in one jump does not, once the factor passes 2×. Stopping while the remainder is
            // under 2× leaves a final step bilinear can cover honestly.
            var current = bitmap
            while (current.width / 2 >= targetWidth && current.height / 2 >= targetHeight) {
                val halved = Bitmap.createScaledBitmap(
                    current, current.width / 2, current.height / 2, true
                )
                if (current !== bitmap) current.recycle()
                current = halved
            }
            if (current.width == targetWidth && current.height == targetHeight) return current
            val scaled = Bitmap.createScaledBitmap(current, targetWidth, targetHeight, true)
            // Only ever an intermediate of this function's own making — the caller still owns
            // [bitmap] and recycles it itself.
            if (current !== bitmap) current.recycle()
            return scaled
        }

        private fun buildBitmapCacheKey(url: String, transforms: List<BitmapTransformation>): String {
            if (transforms.isEmpty()) return url
            val md = MessageDigest.getInstance("MD5")
            md.update(url.toByteArray())
            transforms.forEach { it.updateDiskCacheKey(md) }
            return url + "|" + md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Images on either side judged together for stitched pages; see [PageEdges.judgeRun]. */
        const val RUN_REACH = 4

        /** How long a zoom must hold still before the page is decoded again for it. */
        private const val ZOOM_SETTLE_MS = 250L

        /** Ceiling on a zoomed render, in both width and total pixels. */
        private const val MAX_ZOOMED_WIDTH = 4096
        private const val MAX_ZOOMED_PIXELS = 12L * 1024 * 1024

        fun mergeBitmap(bitmap1: Bitmap, bitmap2: Bitmap, scale: Boolean = false): Bitmap {
            val height = if (bitmap1.height > bitmap2.height) bitmap1.height else bitmap2.height
            val (bit1, bit2) = if (!scale) bitmap1 to bitmap2 else {
                val width1 = bitmap1.width * height * 1f / bitmap1.height
                val width2 = bitmap2.width * height * 1f / bitmap2.height
                (Bitmap.createScaledBitmap(bitmap1, width1.toInt(), height, false)
                        to
                        Bitmap.createScaledBitmap(bitmap2, width2.toInt(), height, false))
            }
            val width = bit1.width + bit2.width
            val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(newBitmap)
            canvas.drawBitmap(bit1, 0f, (height * 1f - bit1.height) / 2, null)
            canvas.drawBitmap(bit2, bit1.width.toFloat(), (height * 1f - bit2.height) / 2, null)
            return newBitmap
        }
    }
}