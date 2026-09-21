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
    chapter: MangaChapter
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val settings get() = activity.defaultSettings
    private val chapterImages = chapter.images()
    var images = chapterImages

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        images = if (settings.layout == CurrentReaderSettings.Layouts.PAGED
            && settings.direction == CurrentReaderSettings.Directions.BOTTOM_TO_TOP
        ) {
            chapterImages.reversed()
        } else {
            chapterImages
        }
        super.onAttachedToRecyclerView(recyclerView)
    }

    /** The page shown at [position], used to tie loads to pages rather than to positions. */
    open fun pageKey(position: Int): Any? = images.getOrNull(position)

    /** Everything [position] displays — what [PagePrefetcher] must warm to make it instant. */
    open fun pagesAt(position: Int): List<MangaImage> = listOfNotNull(images.getOrNull(position))

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
                when (settings.direction) {
                    CurrentReaderSettings.Directions.TOP_TO_BOTTOM -> view.setPadding(
                        0,
                        0,
                        0,
                        16f.px
                    )

                    CurrentReaderSettings.Directions.LEFT_TO_RIGHT -> view.setPadding(
                        0,
                        0,
                        16f.px,
                        0
                    )

                    CurrentReaderSettings.Directions.BOTTOM_TO_TOP -> view.setPadding(
                        0,
                        16f.px,
                        0,
                        0
                    )

                    CurrentReaderSettings.Directions.RIGHT_TO_LEFT -> view.setPadding(
                        16f.px,
                        0,
                        0,
                        0
                    )
                }
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
                    val image = images.getOrNull(pos) ?: return@setOnLongClickListener false
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

    abstract suspend fun loadImage(position: Int, parent: View): Boolean

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

        suspend fun Context.loadBitmap(
            link: FileUrl,
            transforms: List<BitmapTransformation>,
            maxHeightOverride: Int? = null,
            zoom: Float = 1f
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
                    if (reader?.defaultSettings?.layout == CurrentReaderSettings.Layouts.CONTINUOUS) null
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
                        return@withContext downsampleBitmap(
                            Glide.with(this@loadBitmap)
                                .asBitmap()
                                .load(localFile.absoluteFile)
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
                        val cacheKey = buildBitmapCacheKey(link.url, transforms)
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