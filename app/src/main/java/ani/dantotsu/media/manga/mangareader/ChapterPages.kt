package ani.dantotsu.media.manga.mangareader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.media.manga.mangareader.BaseImageAdapter.Companion.loadBitmap
import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.settings.CurrentReaderSettings.Directions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Rows [from] to [to] of the chapter's image [image], as fractions of its height. */
data class PageSlice(val image: Int, val from: Float, val to: Float) {
    val whole get() = from <= 0f && to >= 1f
}

/**
 * One real page of a chapter: the slices of images it is made of, top to bottom. [joinedToPrevious]
 * marks a page that only starts here because the one before grew too tall — consecutive pieces of a
 * webtoon — which a scrolling layout shows without a gap.
 */
class ChapterPage(val slices: List<PageSlice>, val joinedToPrevious: Boolean) {
    /** A plain page: exactly one image, whole. */
    val single get() = slices.size == 1 && slices[0].whole

    /** Identifies the page among the chapter's [urls] — what its profile and cache entries go by. */
    fun key(urls: List<String>): String =
        if (single) urls[slices[0].image]
        else "page|" + slices.joinToString(";") { "${urls[it.image]}#${it.from}-${it.to}" }
}

/**
 * A chapter's real pages, as opposed to its images. A source may upload one page per image, but
 * also several pages stitched into one image, a page cut across two, or both at once; this is
 * what the reader pages through and counts once it is known (see [ChapterPages]).
 */
class ChapterPageMap(val pages: List<ChapterPage>, val imageCount: Int) {
    /** One page per image, each whole: nothing for the reader to do differently. */
    val isIdentity = pages.size == imageCount &&
            pages.withIndex().all { (i, page) -> page.single && page.slices[0].image == i }

    /** The 0-based page showing the point [offset] (fraction of its height) down image [image]. */
    fun pageOf(image: Int, offset: Float): Int {
        var before = 0
        pages.forEachIndexed { i, page ->
            for (slice in page.slices) {
                if (slice.image == image && offset >= slice.from && offset < slice.to) return i
                if (slice.image < image || (slice.image == image && slice.from <= offset)) before = i
            }
        }
        return before
    }

    /** Where 0-based [page] starts: its first slice. */
    fun startOf(page: Int): PageSlice = pages[page.coerceIn(pages.indices)].slices.first()
}

/**
 * Works out a chapter's real pages, and draws one.
 *
 * Where the pages are can only be told from the pixels, so the whole chapter is decoded and
 * profiled ([scan]) before anything changes; until then the reader shows images as they come.
 */
object ChapterPages {

    /**
     * Pages a run of joined pieces may grow to, in page widths, before the next piece starts a new
     * page. Keeps a webtoon — every piece of which joins the next — from becoming one page, while
     * still putting a page cut in two or three back together.
     */
    private const val MAX_MERGED_RATIO = 2.0f

    /** Share of a stitched page's height a page must reach before a break may end it. */
    private const val MIN_PAGE_SHARE = 0.85f

    /**
     * The real pages of the chapter whose images are [urls], from their profiles; null while any
     * image is still unprofiled. Stitched page breaks come from [PageEdges.judgeRun]; other images
     * join the page before when [PageEdges.shouldSpace] says the two are one picture cut apart.
     */
    fun build(urls: List<String>, preferJoin: Boolean): ChapterPageMap? {
        val profiles = urls.map { PageEdges.get(it) ?: return null }
        urls.indices.forEach { PageEdges.judgeRun(urls, it) }
        val breaks = urls.map { PageEdges.breaksFor(it) ?: return null }
        urls.forEachIndexed { i, u ->
            val p = profiles[i]; val b = breaks[i]
            android.util.Log.d("SmartPages", "image $i ${u.takeLast(30)} ${p.width}x${p.height} stitched=${b.stitched} atStart=${b.atStart} rows=${b.rows.toList()}")
        }

        val pages = mutableListOf<ChapterPage>()
        var slices = mutableListOf<PageSlice>()
        var height = 0f
        var joined = false
        fun close() {
            if (slices.isNotEmpty()) pages += ChapterPage(slices, joined)
            slices = mutableListOf()
            height = 0f
        }

        urls.indices.forEach { i ->
            val profile = profiles[i]
            // Each piece of the image: where it starts and ends, in rows. A break mid-row leaves
            // that row out of both pages, since it holds a little of each.
            val kept = breaks[i].positions.filter {
                !PageEdges.sameColouredBackdrop(profile, it.roundToInt())
            }
            val ends = kept.map { p ->
                val row = floor(p)
                val blended = p - row in 0.25f..0.75f
                if (blended) row to row + 1f else p.roundToInt().toFloat().let { it to it }
            }
            val starts = listOf(0f) + ends.map { it.second }
            val stops = ends.map { it.first } + profile.height.toFloat()
            val cuts = starts.indices.map { starts[it] / profile.height to stops[it] / profile.height }
            val firstHeight = (cuts[0].second - cuts[0].first) * profile.ratio
            if (i == 0) {
                joined = false
            } else {
                val previous = profiles[i - 1]
                val sameRun = breaks[i].stitched && breaks[i - 1].stitched &&
                        abs(previous.width - profile.width) <= profile.width * 0.02f
                when {
                    // A stitched page break right on the seam.
                    breaks[i].atStart -> { close(); joined = false }
                    // Stitched pages run on across a seam with no break on it.
                    sameRun -> Unit
                    PageEdges.shouldSpace(previous, profile, Directions.TOP_TO_BOTTOM, preferJoin) -> {
                        close(); joined = false
                    }
                    // One picture cut apart: the same page, unless it is getting too tall to be one.
                    height + firstHeight > MAX_MERGED_RATIO -> { close(); joined = true }
                }
            }
            // A stitched page is a whole page tall, and a chapter doesn't start mid-page: a break that
            // would leave less than most of a page above it is something else that happened to line
            // up — a line of large text on a credits page, say.
            val pageHeight = breaks[i].period / profile.width
            for (c in cuts.indices) {
                val (from, to) = cuts[c]
                val piece = (to - from) * profile.ratio
                if (c > 0 && height >= pageHeight * MIN_PAGE_SHARE) {
                    close(); joined = false
                    slices += PageSlice(i, from, to)
                } else if (c > 0) {
                    // Not a page break after all: the piece runs on in the same page.
                    val last = slices.removeAt(slices.size - 1)
                    slices += PageSlice(i, last.from, to)
                } else {
                    slices += PageSlice(i, from, to)
                }
                height += piece
            }
        }
        close()
        return ChapterPageMap(pages, urls.size)
    }

    /**
     * Decodes and profiles every image of a chapter, then hands its real pages to [onDone] — on the
     * main thread, and only if every image could be read. Two at a time, at the scrolling reader's
     * size whatever the layout: break detection is only as good as the rows it gets.
     */
    fun scan(
        activity: MangaReaderActivity,
        images: List<MangaImage>,
        preferJoin: Boolean,
        onDone: (ChapterPageMap) -> Unit,
    ): Job = activity.lifecycleScope.launch {
        android.util.Log.d("SmartPages", "scan start: ${images.size} images")
        val permits = Semaphore(2)
        val decoded = images.map { image ->
            async {
                permits.withPermit {
                    with(activity) {
                        loadBitmap(
                            image.url, activity.pageTransforms(image),
                            ContinuousChapterAdapter.MAX_PAGE_HEIGHT, wholeWidth = true
                        )
                    }.also {
                        android.util.Log.d("SmartPages", "decoded ${image.url.url.takeLast(40)} -> ${it?.width}x${it?.height}")
                    } != null
                }
            }
        }.awaitAll()
        if (!decoded.all { it }) {
            android.util.Log.d("SmartPages", "scan abandoned: ${decoded.count { !it }} images failed")
            return@launch
        }
        val map = withContext(Dispatchers.Default) {
            build(images.map { it.url.url }, preferJoin)
        }
        android.util.Log.d("SmartPages", "scan done: map=${map?.pages?.size} pages identity=${map?.isIdentity} " +
            "pages=${map?.pages?.joinToString(" ") { p -> p.slices.joinToString("+") { "${it.image}[${"%.2f".format(it.from)}-${"%.2f".format(it.to)}]" } }}")
        if (map == null) return@launch
        onDone(map)
    }

    /**
     * Draws [page]: its slices of [bitmaps] (one per slice, in order) stacked top to bottom at the
     * widest one's width. A plain page is its image as it is.
     */
    fun compose(page: ChapterPage, bitmaps: List<Bitmap>): Bitmap? {
        if (page.single) return bitmaps.firstOrNull()
        val width = bitmaps.maxOf { it.width }
        val parts = page.slices.zip(bitmaps).map { (slice, bitmap) ->
            val top = (slice.from * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
            val bottom = (slice.to * bitmap.height).roundToInt().coerceIn(top + 1, bitmap.height)
            Triple(bitmap, Rect(0, top, bitmap.width, bottom),
                ((bottom - top) * width.toFloat() / bitmap.width).roundToInt().coerceAtLeast(1))
        }
        return try {
            val out = Bitmap.createBitmap(width, parts.sumOf { it.third }, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            var y = 0
            for ((bitmap, source, height) in parts) {
                canvas.drawBitmap(bitmap, source, Rect(0, y, width, y + height), paint)
                y += height
            }
            out
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}
