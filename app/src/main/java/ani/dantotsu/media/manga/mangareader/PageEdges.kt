package ani.dantotsu.media.manga.mangareader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import ani.dantotsu.settings.CurrentReaderSettings.Directions
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What a page looks like along its four edges, and the "smart spacing" decision built on it:
 * whether two consecutive pages in a scrolling layout should have a gap between them.
 *
 * A fixed gap is wrong in both directions. Sources slice pages however they like — a standard
 * manga page cut into three strips, a webtoon cut into screen-sized chunks, a long strip uploaded
 * as one image — and a gap through the middle of a panel is far worse than no gap at all. On the
 * other hand real pages, with their own margins, read better with one. The country of origin was
 * the only signal before, and it says nothing about how a particular source uploaded a chapter.
 *
 * So each decoded page is profiled once (a few pixel rows per edge, cheap) and the boundary
 * between two pages is judged on the pixels that would meet there:
 *  - a very tall page is a strip, and strips are joined;
 *  - artwork running across the cut is a slice of one image, joined. Both edges must be
 *    detailed — two scanned margins look alike too, only because both are near-white with a
 *    little noise, and those are real page edges — and the jump across the cut must be about
 *    what the image changes by from one row to the next anyway. A cut through a panel border or
 *    along a JPEG block edge jumps a lot in absolute terms, but no more than its own rows do;
 *  - two equally wide images that are both shorter than they are wide, meeting on a margin, are
 *    pieces of a page cut horizontally at a gutter, joined. Two different pictures meeting are
 *    not, whatever their shape: a greyscale spread followed by its colour version is two images;
 *  - in a series known to be a long strip ([preferJoin]) everything else is joined too — a blank
 *    cut there is the gutter between panels — except two different pictures meeting head-on;
 *  - anything else keeps its gap: margins meeting are real page edges, and so are two different
 *    pictures meeting.
 *
 * Images can also be regular pages stitched end to end with nothing between them, then cut up
 * at some fixed height that has nothing to do with the pages. Those are found by their
 * regularity ([judgeRun]): pages are all one height, so the rows where the picture changes
 * abruptly line up on a fixed period when they are pages, and don't in a webtoon, whose panels
 * come in every size. [withGaps] redraws an image with a gap at each page break inside it, and a
 * break on an image's top edge puts a gap before it; [ChapterPages] turns the same breaks into
 * the chapter's real pages.
 *
 * Profiles are keyed by page url and survive the view, so the page before is usually already
 * known by the time the next one loads; [clear] drops them when the transforms (crop borders)
 * change what the edges look like.
 */
object PageEdges {

    /**
     * The outermost line's luminance, bucketed ([samples]); the line just inside it ([inner]) and
     * how much the two differ ([step]); and how much the whole band varies ([deviation]).
     */
    class Edge(
        val samples: FloatArray,
        val inner: FloatArray,
        val step: Double,
        val deviation: Double,
    ) {
        val detailed get() = deviation >= DETAILED_DEVIATION
    }

    class Profile(
        val width: Int,
        val height: Int,
        val top: Edge,
        val bottom: Edge,
        val left: Edge,
        val right: Edge,
        /** jumps[y]: how abruptly row y + 1 differs from row y. */
        val jumps: FloatArray,
        /**
         * jumps2[y]: the same between row y and row y + 2. A page break that a downscale blended
         * into an in-between row shows up here at full strength while [jumps] only sees half of it.
         */
        val jumps2: FloatArray,
        /** Each row's mean colour, as r, g, b triples. */
        val rowColors: FloatArray,
    ) {
        val ratio get() = height.toFloat() / width
    }

    /**
     * Where the stitched pages inside one image start, as [judgeRun] last found them: [rows] in
     * the pixels of the profile ([width] × [height]), and whether a page also starts right at its
     * top edge ([atStart]). [stitched] says the image belongs to a run of stitched pages at all —
     * in which case an edge with no break on it runs on into the neighbouring image.
     */
    class Breaks(
        val width: Int,
        val height: Int,
        val rows: IntArray,
        val atStart: Boolean,
        val stitched: Boolean,
        /**
         * [rows] exactly: a page edge that a downscale left in the middle of a row has that row
         * blending both pages, and [ChapterPages] leaves it out of either.
         */
        val positions: FloatArray = FloatArray(rows.size) { rows[it].toFloat() },
        /** The stitched pages' height, in the profile's rows; 0 when not [stitched]. */
        val period: Float = 0f,
    ) {
        fun sameAs(other: Breaks?) = other != null && atStart == other.atStart &&
                stitched == other.stitched && rows.contentEquals(other.rows)

        fun scaled(toWidth: Int): List<Int> {
            val scale = toWidth.toFloat() / width
            return rows.map { (it * scale).roundToInt() }
        }
    }

    private val profiles = ConcurrentHashMap<String, Profile>()
    private val breaks = ConcurrentHashMap<String, Breaks>()

    /** Told the url of every page profiled, so pages waiting on their neighbours can be judged again. */
    @Volatile
    var onProfiled: ((String) -> Unit)? = null

    fun get(url: String): Profile? = profiles[url]

    fun breaksFor(url: String): Breaks? = breaks[url]

    fun clear() {
        profiles.clear()
        breaks.clear()
    }

    /**
     * Profiles [bitmap] for [url] unless it already has been at this resolution or better. A
     * sharper decode replaces a softer one — the paged reader decodes pages smaller than the
     * scrolling one, and break detection is only as good as the rows it gets. Never throws.
     */
    fun record(url: String, bitmap: Bitmap) {
        if (url.isEmpty()) return
        val known = profiles[url]
        if (known != null && known.width >= bitmap.width) return
        val profile = profile(bitmap) ?: return
        profiles[url] = profile
        if (known != null) breaks.remove(url)
        onProfiled?.invoke(url)
    }

    private fun profile(bitmap: Bitmap): Profile? = runCatching {
        if (bitmap.isRecycled || bitmap.width < 4 || bitmap.height < 4) return null
        // Hardware bitmaps can't be read back; such a page just gets the default gap.
        if (bitmap.config == Bitmap.Config.HARDWARE) return null
        val w = bitmap.width
        val h = bitmap.height
        val rows = IntArray(w * BAND)
        val cols = IntArray(h * BAND)
        bitmap.getPixels(rows, 0, w, 0, 0, w, BAND)
        val top = edge(rows, w, BAND)
        bitmap.getPixels(rows, 0, w, 0, h - BAND, w, BAND)
        val bottom = edge(rows, w, BAND, reverse = true)
        bitmap.getPixels(cols, 0, BAND, 0, 0, BAND, h)
        val left = edge(transpose(cols, BAND, h), h, BAND)
        bitmap.getPixels(cols, 0, BAND, w - BAND, 0, BAND, h)
        val right = edge(transpose(cols, BAND, h), h, BAND, reverse = true)
        val rows2 = rowJumps(bitmap)
        Profile(w, h, top, bottom, left, right, rows2.jumps, rows2.jumps2, rows2.colors)
    }.getOrNull()

    private class Rows(val jumps: FloatArray, val jumps2: FloatArray, val colors: FloatArray)

    /**
     * How much each row differs from the next and from the one after, and each row's mean
     * colour. Read in bands.
     */
    private fun rowJumps(bitmap: Bitmap): Rows {
        val w = bitmap.width
        val h = bitmap.height
        val jumps = FloatArray(h - 1)
        val jumps2 = FloatArray(maxOf(0, h - 2))
        val colors = FloatArray(h * 3)
        val px = IntArray(w * READ_BAND)
        var older = FloatArray(SAMPLES)
        var previous = FloatArray(SAMPLES)
        var current = FloatArray(SAMPLES)
        var y0 = 0
        while (y0 < h) {
            val n = minOf(READ_BAND, h - y0)
            bitmap.getPixels(px, 0, w, 0, y0, w, n)
            for (r in 0 until n) {
                bucket(px, r * w, w, current)
                val y = y0 + r
                var red = 0L
                var green = 0L
                var blue = 0L
                for (i in r * w until (r + 1) * w) {
                    val c = px[i]
                    red += (c shr 16) and 0xFF
                    green += (c shr 8) and 0xFF
                    blue += c and 0xFF
                }
                colors[y * 3] = red.toFloat() / w
                colors[y * 3 + 1] = green.toFloat() / w
                colors[y * 3 + 2] = blue.toFloat() / w
                if (y > 0) jumps[y - 1] = difference(previous, current).toFloat()
                if (y > 1) jumps2[y - 2] = difference(older, current).toFloat()
                val recycled = older
                older = previous
                previous = current
                current = recycled
            }
            y0 += n
        }
        return Rows(jumps, jumps2, colors)
    }

    /**
     * Whether rows either side of [row] of [profile] sit on one and the same strongly coloured
     * backdrop — a line of text on a credits page, say, rather than two pages meeting. Manga pages
     * are mostly grey, and two coloured ones that meet are rarely the same colour.
     */
    fun sameColouredBackdrop(profile: Profile, row: Int): Boolean {
        fun band(from: Int, to: Int): FloatArray? {
            val a = from.coerceIn(0, profile.height)
            val b = to.coerceIn(0, profile.height)
            if (b - a < 4) return null
            val mean = FloatArray(3)
            for (y in a until b) for (c in 0..2) mean[c] += profile.rowColors[y * 3 + c]
            for (c in 0..2) mean[c] /= (b - a)
            return mean
        }
        val above = band(row - BACKDROP_BAND, row - 2) ?: return false
        val below = band(row + 2, row + BACKDROP_BAND) ?: return false
        fun saturation(c: FloatArray) = c.max() - c.min()
        if (saturation(above) < BACKDROP_SATURATION || saturation(below) < BACKDROP_SATURATION) return false
        // Hue rather than colour: the text on the backdrop changes its brightness from one line to
        // the next (white, then yellow), not what colour it is.
        val apart = abs(hue(above) - hue(below)).let { minOf(it, 360f - it) }
        return apart < BACKDROP_HUE
    }

    /** The hue of an r, g, b colour, in degrees. */
    private fun hue(c: FloatArray): Float {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(c[0].toInt(), c[1].toInt(), c[2].toInt(), hsv)
        return hsv[0]
    }

    /**
     * Whether the image at [index] of [urls] could be part of stitched pages at all: a strip, or
     * joined to a neighbour the way a cut-up picture is. A page with ordinary page edges on both
     * sides is a page, and searching it for a period would only cost time.
     */
    private fun worthJudging(urls: List<String?>, index: Int, self: Profile): Boolean {
        if (self.ratio > MAX_PAGE_RATIO) return true
        val before = urls.getOrNull(index - 1)?.let { profiles[it] }
        val after = urls.getOrNull(index + 1)?.let { profiles[it] }
        return (before != null && !shouldSpace(before, self, Directions.TOP_TO_BOTTOM, false)) ||
                (after != null && !shouldSpace(self, after, Directions.TOP_TO_BOTTOM, false))
    }

    /**
     * Judges where stitched pages start in the image at [index] of [urls] — the chapter's images in
     * reading order around it, null where a position holds something else — and remembers it for
     * [breaksFor]. True when that changed what was known before.
     *
     * Sources cut stitched pages wherever they like: one image per 10000 rows, or chunks barely
     * taller than a page. So the image is judged together with the profiled neighbours of the same
     * width on either side, run together as one strip, rather than alone — one chunk may not even
     * hold a whole page. Heavy enough to keep off the main thread.
     */
    fun judgeRun(urls: List<String?>, index: Int): Boolean {
        val url = urls.getOrNull(index) ?: return false
        val self = profiles[url] ?: return false
        val result = if (!worthJudging(urls, index, self)) {
            Breaks(self.width, self.height, IntArray(0), atStart = false, stitched = false)
        } else judge(urls, index, self)
        val changed = !result.sameAs(breaks[url])
        breaks[url] = result
        return changed
    }

    private fun judge(urls: List<String?>, index: Int, self: Profile): Breaks {
        fun member(i: Int): Profile? = urls.getOrNull(i)?.let { profiles[it] }?.takeIf {
            abs(it.width - self.width) <= self.width * SAME_BREADTH
        }

        var first = index
        while (first > index - RUN_REACH && member(first - 1) != null) first--
        var last = index
        while (last < index + RUN_REACH && member(last + 1) != null) last++
        val run = (first..last).map { member(it)!! }

        val total = run.sumOf { it.height }
        val jumps = FloatArray(total - 1)
        val jumps2 = FloatArray(total - 1)
        var at = 0
        var start = 0
        run.forEachIndexed { i, profile ->
            if (i > 0) {
                // The seam, measured the same way as the rows inside the images.
                val above = run[i - 1].bottom
                jumps[at - 1] = difference(above.samples, profile.top.samples).toFloat()
                jumps2[at - 1] = difference(above.samples, profile.top.inner).toFloat()
                if (at >= 2) jumps2[at - 2] = difference(above.inner, profile.top.samples).toFloat()
            }
            if (first + i == index) start = at
            profile.jumps.copyInto(jumps, at)
            profile.jumps2.copyInto(jumps2, at)
            at += profile.height
        }

        // A handful of pages either side is plenty to find the period, and keeps the search cheap
        // however long the run is.
        val reach = (self.width * MAX_PAGE_RATIO * SEARCH_PAGES).toInt()
        val from = maxOf(0, start - reach)
        val to = minOf(jumps.size, start + self.height + reach)
        val found = pageBreaks(
            jumps.copyOfRange(from, to), jumps2.copyOfRange(from, to), self.width
        )
        val local = found?.positions?.map { it + from - start }.orEmpty()
        val inside = local.filter { it > SEAM_TOLERANCE && it < self.height - SEAM_TOLERANCE }
        return Breaks(
            self.width, self.height,
            inside.map { it.roundToInt() }.toIntArray(),
            atStart = local.any { abs(it) <= SEAM_TOLERANCE },
            stitched = found != null,
            period = found?.period ?: 0f,
            positions = inside.toFloatArray(),
        )
    }

    /**
     * The rows on which pages start in a strip of regular pages stitched together, given how
     * abruptly each of its rows changes into the next ([jump]) and the one after ([jump2]); null
     * when it isn't one.
     *
     * Within a page the picture changes abruptly at panel borders too, so no single row means
     * anything; what gives pages away is that they are all the same height. Every page height a
     * manga page could plausibly have at this [width] is tried — in quarter rows, since a page
     * scaled down with the image is rarely a whole number of rows tall — anchored on each abrupt
     * row in turn, and the one whose predicted breaks land on abrupt rows most often wins, provided
     * it is right nearly every time. Once it is trusted, every break between the first and last it
     * confirmed counts: a boundary where two blank margins meet is invisible, but it is still a
     * page boundary. Measured on real chapters, at full size, scaled and re-encoded, and halved,
     * every page break was found; webtoons cropped to random panel heights were never split.
     */
    private fun pageBreaks(jump: FloatArray, jump2: FloatArray, width: Int): Detected? {
        val n = jump.size
        val minPeriod = width * MIN_PAGE_RATIO
        val maxPeriod = width * MAX_PAGE_RATIO
        if (n < minPeriod * MIN_BREAKS) return null

        val near1 = slackMax(jump)
        val near2 = slackMax(jump2)
        val threshold1 = maxOf(percentile(near1), MIN_BREAK_JUMP)
        val threshold2 = maxOf(percentile(near2), MIN_BREAK_JUMP)
        val hit = BooleanArray(n) { near1[it] >= threshold1 || near2[it] >= threshold2 }
        val score = FloatArray(n) { near1[it] / threshold1 + near2[it] / threshold2 }

        // The rows a page break could be on; the right period has its breaks among them, so they
        // are the only offsets worth trying. All of them: over a long strip the most abrupt rows
        // are panel borders, and keeping only those would leave no page break to anchor on.
        val anchors = (EDGE_MARGIN + 1 until n - EDGE_MARGIN)
            .filter { y ->
                hit[y] && (maxOf(0, y - BREAK_SLACK)..minOf(n - 1, y + BREAK_SLACK))
                    .all { score[it] <= score[y] }
            }

        var bestRate = -1f
        var bestMean = 0f
        var bestPeriod = 0f
        var bestOffset = 0f
        var period = minPeriod
        while (period <= maxPeriod && n >= period * MIN_BREAKS) {
            val tried = HashSet<Int>()
            for (anchor in anchors) {
                val offset = anchor - floor(anchor / period) * period
                if (!tried.add((offset * 2).roundToInt())) continue
                var teeth = 0
                var hits = 0
                var sum = 0f
                var k = 0
                while (true) {
                    val y = (offset + k * period).roundToInt()
                    if (y >= n) break
                    if (y > EDGE_MARGIN && y < n - EDGE_MARGIN) {
                        teeth++
                        if (hit[y]) hits++
                        sum += score[y]
                    }
                    k++
                }
                if (teeth < MIN_BREAKS) continue
                val rate = hits.toFloat() / teeth
                val mean = sum / teeth
                if (rate > bestRate || (rate == bestRate && mean > bestMean)) {
                    bestRate = rate
                    bestMean = mean
                    bestPeriod = period
                    bestOffset = offset
                }
            }
            period += PERIOD_STEP
        }
        android.util.Log.d("SmartPages", "period search n=$n width=$width best rate=$bestRate period=$bestPeriod anchors=${anchors.size}")
        if (bestRate < MIN_HIT_RATE) return null

        // Teeth as (k, predicted row), k counting periods from the offset.
        val teeth = generateSequence(0) { it + 1 }
            .map { it to (bestOffset + it * bestPeriod).roundToInt() }
            .takeWhile { it.second < n }
            .filter { it.second > EDGE_MARGIN && it.second < n - EDGE_MARGIN }
            .toList()
        val firstHit = teeth.indexOfFirst { hit[it.second] }
        val lastHit = teeth.indexOfLast { hit[it.second] }
        if (firstHit < 0) return null
        val span = teeth.subList(firstHit, lastHit + 1)

        // Where each confirmed break really is: the most abrupt row near its prediction.
        val observed = span.filter { hit[it.second] }.map { (k, y) ->
            var best = y
            for (j in maxOf(0, y - SNAP)..minOf(n - 1, y + SNAP)) if (jump[j] > jump[best]) best = j
            k.toFloat() to (best + 1).toFloat()
        }
        // Stitched pages are all exactly one height, so a line through the breaks places each one
        // better than its own most abrupt row: where a blank margin meets a blank margin, that row
        // is where the artwork starts, a few rows off the page edge. The slope is a least-squares
        // fit; the intercept is the median, which those margin-shifted breaks can't drag along.
        val (fitPeriod, fitOffset) = if (observed.size >= 3) {
            val meanK = observed.map { it.first }.average()
            val meanY = observed.map { it.second }.average()
            val slope = observed.sumOf { (it.first - meanK) * (it.second - meanY) } /
                    observed.sumOf { (it.first - meanK) * (it.first - meanK) }
            val intercept = observed.map { it.second - slope * it.first }.sorted()
                .let { it[it.size / 2] }
            slope to intercept
        } else {
            bestPeriod.toDouble() to (bestOffset + 1.0)
        }
        // Outside the confirmed span a miss is a page of another height (credits, a cover).
        return Detected(
            fitPeriod.toFloat(),
            span.map { (k, _) -> (fitOffset + k * fitPeriod).toFloat().coerceIn(1f, n.toFloat()) }
        )
    }

    /** Stitched pages found in a strip: their [period] and where each starts, in rows. */
    private class Detected(val period: Float, val positions: List<Float>)

    /** Each value raised to the largest within [BREAK_SLACK] of it. */
    private fun slackMax(values: FloatArray) = FloatArray(values.size) { i ->
        var m = 0f
        for (k in maxOf(0, i - BREAK_SLACK)..minOf(values.size - 1, i + BREAK_SLACK)) {
            if (values[k] > m) m = values[k]
        }
        m
    }

    private fun percentile(values: FloatArray): Float =
        values.sortedArray()[(values.size * BREAK_PERCENTILE).toInt().coerceAtMost(values.size - 1)]

    /**
     * [bitmap] — the page [url] was profiled from, or a zoomed render of it — redrawn with a
     * transparent [gap] (in this bitmap's pixels) at each page break inside it, or the bitmap
     * itself when it has none. Transparent so the reader's own background shows through, exactly
     * as it does between separate pages.
     */
    fun withGaps(url: String, bitmap: Bitmap, gap: Int): Bitmap {
        val rows = breaks[url]?.scaled(bitmap.width)?.filter { it in 1 until bitmap.height }
        if (rows.isNullOrEmpty() || gap <= 0) return bitmap
        return try {
            val out = Bitmap.createBitmap(
                bitmap.width, bitmap.height + gap * rows.size, Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(out)
            var from = 0
            var to = 0
            for (row in rows + bitmap.height) {
                canvas.drawBitmap(
                    bitmap,
                    Rect(0, from, bitmap.width, row),
                    Rect(0, to, bitmap.width, to + row - from),
                    null
                )
                to += row - from + gap
                from = row
            }
            out
        } catch (e: OutOfMemoryError) {
            bitmap
        }
    }

    /**
     * How far down a point at [y] on page [url] is pushed by the gaps [withGaps] put above it —
     * with [y], [gap] and the result all in the pixels of a page [width] wide.
     */
    fun gapShift(url: String, y: Int, width: Int, gap: Int): Int =
        gap * (breaks[url]?.scaled(width)?.count { it <= y } ?: 0)

    /** [BAND]-wide columns read row by row, turned into [BAND] rows of length [length]. */
    private fun transpose(src: IntArray, band: Int, length: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until length) for (x in 0 until band) out[x * length + y] = src[y * band + x]
        return out
    }

    /** Folds the [length] pixels at [offset] into [out]'s averaged luminance buckets. */
    private fun bucket(px: IntArray, offset: Int, length: Int, out: FloatArray) {
        for (s in out.indices) {
            val from = s * length / out.size
            val to = maxOf(from + 1, (s + 1) * length / out.size)
            var acc = 0f
            for (i in from until to) acc += luma(px[offset + i])
            out[s] = acc / (to - from)
        }
    }

    /**
     * Folds [lines] rows of [length] pixels into [SAMPLES] averaged luminance buckets. Only the
     * outermost row ([reverse] makes the last one outermost) is compared with the neighbour, but
     * the deviation covers the whole band.
     */
    private fun edge(px: IntArray, length: Int, lines: Int, reverse: Boolean = false): Edge {
        val outer = if (reverse) lines - 1 else 0
        val inner = if (reverse) lines - 2 else 1
        val samples = FloatArray(SAMPLES)
        val inside = FloatArray(SAMPLES)
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (line in 0 until lines) {
            for (s in 0 until SAMPLES) {
                val from = s * length / SAMPLES
                val to = maxOf(from + 1, (s + 1) * length / SAMPLES)
                var acc = 0f
                for (i in from until to) acc += luma(px[line * length + i])
                val v = acc / (to - from)
                if (line == outer) samples[s] = v
                if (line == inner) inside[s] = v
                sum += v
                sumSq += v * v
                n++
            }
        }
        val mean = sum / n
        val deviation = sqrt(maxOf(0.0, sumSq / n - mean * mean))
        return Edge(samples, inside, difference(samples, inside), deviation)
    }

    private fun difference(a: FloatArray, b: FloatArray): Double =
        a.indices.sumOf { abs(a[it] - b[it]).toDouble() } / a.size

    private fun luma(c: Int): Float =
        0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)

    /**
     * Whether a gap belongs between [before] and [after] — consecutive pages, in reading order,
     * laid out along [direction]. Unknown profiles keep the gap, which is the old behaviour.
     */
    fun shouldSpace(
        before: Profile?,
        after: Profile?,
        direction: Directions,
        preferJoin: Boolean,
    ): Boolean {
        if (before == null || after == null) return true
        val vertical = direction == Directions.TOP_TO_BOTTOM || direction == Directions.BOTTOM_TO_TOP
        // Each page's extent along the reading axis over its extent across it.
        fun Profile.length() = if (vertical) height else width
        fun Profile.breadth() = if (vertical) width else height
        fun Profile.ratio() = length().toFloat() / breadth()

        // A page break that happens to fall on a strip's seam is [judgeRun]'s to find.
        if (before.ratio() > STRIP_RATIO || after.ratio() > STRIP_RATIO) return false

        val (trailing, leading) = when (direction) {
            Directions.TOP_TO_BOTTOM -> before.bottom to after.top
            Directions.BOTTOM_TO_TOP -> before.top to after.bottom
            Directions.LEFT_TO_RIGHT -> before.right to after.left
            Directions.RIGHT_TO_LEFT -> before.left to after.right
        }
        val bothDetailed = trailing.detailed && leading.detailed
        val jump = difference(trailing.samples, leading.samples)
        val step = (trailing.step + leading.step) / 2
        val continuous = bothDetailed &&
                (jump < CONTINUOUS_DIFFERENCE || jump < step * CONTINUOUS_STEP_RATIO)
        if (continuous) return false

        val sameBreadth = abs(before.breadth() - after.breadth()) <=
                maxOf(before.breadth(), after.breadth()) * SAME_BREADTH
        if (sameBreadth && before.ratio() < 1f && after.ratio() < 1f && !bothDetailed) return false

        if (preferJoin) return bothDetailed
        return true
    }

    /** Pixel lines per edge band. */
    private const val BAND = 3

    /** Buckets an edge is folded into; the two pages may differ in resolution. */
    private const val SAMPLES = 48

    /**
     * Luminance spread (0–255) from which an edge band holds artwork. Below it is a margin: blank,
     * or near-blank with scan noise, a page number or a faint gradient.
     */
    private const val DETAILED_DEVIATION = 20.0

    /** Mean per-bucket luminance difference under which two edges are one picture cut in two. */
    private const val CONTINUOUS_DIFFERENCE = 14.0

    /**
     * How many times its own row-to-row change a jump across the cut may be and still count as
     * one picture. Measured on a sliced chapter: real cuts reached 1.3, page boundaries with
     * detail on both sides started at 2.4.
     */
    private const val CONTINUOUS_STEP_RATIO = 1.6

    /** Length/breadth past which a page is a long strip rather than a page. */
    private const val STRIP_RATIO = 2.2f

    /** Page height / width range searched for stitched pages; manga pages are ~1.4–1.5. */
    const val MIN_PAGE_RATIO = 1.2f
    const val MAX_PAGE_RATIO = 1.7f

    /** Page heights are tried in steps this fine, in rows. */
    private const val PERIOD_STEP = 0.25f

    /** Pages either side of an image searched for the period. */
    private const val SEARCH_PAGES = 8

    /** Neighbours on either side an image is judged together with for stitched pages. */
    private const val RUN_REACH = 4

    /** Fewest predicted page breaks a strip must have for its period to be trusted. */
    private const val MIN_BREAKS = 4

    /** Share of predicted page breaks that must land on an abrupt row. */
    private const val MIN_HIT_RATE = 0.8f

    /** Rows either side of a break compared for a shared coloured backdrop. */
    private const val BACKDROP_BAND = 24

    /** Colour spread (max − min channel) from which a band counts as coloured. */
    private const val BACKDROP_SATURATION = 40f

    /** Hue difference, in degrees, under which two coloured bands are the same backdrop. */
    private const val BACKDROP_HUE = 30f

    /** Rows either side of a predicted break searched for where it really is. */
    private const val SNAP = 4

    /** Rows a page break may sit off its predicted position. */
    private const val BREAK_SLACK = 2

    /**
     * A row counts as abrupt from this percentile of the strip's rows... Page breaks in pages a
     * site has scaled down and re-encoded come out that low.
     */
    private const val BREAK_PERCENTILE = 0.93

    /** ...and never below this jump, so a mostly blank strip can't make noise count. */
    private const val MIN_BREAK_JUMP = 20f

    /** Rows at either end ignored: a break there is the strip's own edge. */
    private const val EDGE_MARGIN = 20

    /** Rows a page break may miss an image's edge by and still count as falling on it. */
    private const val SEAM_TOLERANCE = 3

    /** Rows read from a strip at a time. */
    private const val READ_BAND = 256

    /** Relative breadth difference still counted as the same width. */
    private const val SAME_BREADTH = 0.02f
}
