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
import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.px
import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.tryWithSuspend
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
        holder.itemView.tag = null
    }

    abstract fun isZoomed(): Boolean
    abstract fun setZoom(zoom: Float)

    abstract suspend fun loadImage(position: Int, parent: View): Boolean

    companion object {
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
            maxHeightOverride: Int? = null
        ): Bitmap? {
            return tryWithSuspend {
                val mangaCache = uy.kohesive.injekt.Injekt.get<MangaCache>()
                val dm = resources.displayMetrics
                val maxW = dm.widthPixels * 2
                val maxH = maxHeightOverride ?: (dm.heightPixels * 2)
                withContext(Dispatchers.IO) {
                    // Downloaded PDF chapters: render the requested page on demand.
                    if (PdfPageRenderer.isPdfPage(link.url)) {
                        return@withContext PdfPageRenderer.render(this@loadBitmap, link.url, maxW)
                    }

                    val localFile = File(link.url)
                    if (localFile.exists()) {
                        return@withContext Glide.with(this@loadBitmap)
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
                            .get()
                    }

                    if (link.url.startsWith("content://")) {
                        return@withContext Glide.with(this@loadBitmap)
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
                            .get()
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
                            imageData.fetchAndProcessImage(imageData.page, imageData.source, maxW, maxH)
                                ?: return@withContext null

                        // Downsample before transforms so we never hold a full-res bitmap in memory.
                        val downsampledBitmap = downsampleBitmap(rawBitmap, maxW, maxH)
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
                    return@withContext Glide.with(this@loadBitmap)
                        .asBitmap()
                        .load(GlideUrl(link.url) { link.headers })
                        .override(maxW, maxH)
                        .downsample(DownsampleStrategy.AT_MOST)
                        .let {
                            if (transforms.isNotEmpty()) it.transform(*transforms.toTypedArray())
                            else it
                        }
                        .submit()
                        .get()
                }
            }
        }

        private fun downsampleBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
            // Only constrain by width on its own — tall images (e.g. long-strip pages) must not
            // have their width crushed just because they're tall, as SSIV handles scrolling within
            // the page. The total pixel count is still bounded against the same maxWidth ×
            // maxHeight budget, which is what actually caps memory (bytes ≈ width × height × 4)
            // regardless of aspect ratio — a plain width-or-height box would let an extremely tall
            // narrow strip through unconstrained. In practice ImageData.decodeImage already
            // presamples close to this budget, so this is mostly a defensive fallback for a bitmap
            // that reaches here some other way, already at full resolution.
            val maxPixels = maxWidth.toLong() * maxHeight.toLong()
            val widthScale = maxWidth.toFloat() / bitmap.width
            val pixelScale = sqrt(maxPixels.toFloat() / (bitmap.width.toFloat() * bitmap.height.toFloat()))
            val scale = minOf(1f, widthScale, pixelScale)
            if (scale >= 1f) return bitmap
            return Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }

        private fun buildBitmapCacheKey(url: String, transforms: List<BitmapTransformation>): String {
            if (transforms.isEmpty()) return url
            val md = MessageDigest.getInstance("MD5")
            md.update(url.toByteArray())
            transforms.forEach { it.updateDiskCacheKey(md) }
            return url + "|" + md.digest().joinToString("") { "%02x".format(it) }
        }

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