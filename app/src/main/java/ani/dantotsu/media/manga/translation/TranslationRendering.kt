package ani.dantotsu.media.manga.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withTranslation
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A block as it should appear over the page: where, what colour, and what text if any. */
data class PaintedBlock(
    val id: Int,
    val rect: Rect,
    /** Null for a block that is only being covered, as furigana are. */
    val text: String?,
    val fill: Int,
)

/**
 * Works out where translations go, which is not simply where the text came from.
 */
object TranslationLayout {

    /** Width-to-height a vertical block's box is widened towards before text is set into it. */
    private const val VERTICAL_ASPECT = 1.4f

    /** Page pixels left between two widened boxes, so neighbours read as separate. */
    private const val GUTTER = 3

    /** Margin added when covering a block up, in glyph widths. */
    private const val ERASURE_PAD = 0.25f

    /** Most a box may grow by on either axis, as a multiple of its own size. */
    private const val MAX_GROWTH = 2.2f

    /**
     * Latin type that reads as comfortably as a CJK glyph of a given size, as a share of it.
     *
     * Latin needs less height for the same legibility — no dense strokes to resolve — so a bubble
     * whose Japanese ran at 30px wants its English at around 24, not at 30.
     */
    private const val LATIN_GLYPH_RATIO = 0.8f

    /** Area one set character occupies, as a multiple of the square of its type size. */
    private const val CHAR_AREA = 0.62f

    /** How flat a strip has to measure before a box is allowed to grow across it. */
    private const val SAFE_FLAT = 0.9f

    /**
     * How far from a ruby block its kanji may sit and still count as the thing it glosses, in ruby
     * glyph widths.
     *
     * Ruby is set hard against what it annotates — a gap of anything is already unusual — and its
     * glyphs are about half the size of that text, so three of them is a generous reach of roughly
     * a character and a half of body type.
     */
    private const val RUBY_REACH = 3f

    /** Attempts at a smaller growth before a side gives up entirely. */
    private const val SAFETY_STEPS = 3

    /**
     * The blocks to paint, in page coordinates.
     *
     * Only what should be covered is returned: artwork and text the recognizer does not believe
     * are left showing, since replacing them with a blank rectangle would delete the page and put
     * nothing in its place. Furigana are the one thing covered without being translated — the
     * kanji they gloss has just become English, and leaving the gloss strands Japanese ruby against
     * it — and only where that kanji is really there to be replaced. See [glosses].
     *
     * @param page the page itself, where the caller has it. Given one, a box is only allowed to
     *   grow across pixels that measure as the colour it is about to be painted — which is what
     *   separates growing into the rest of a bubble from growing over the drawing next to it.
     */
    fun paint(
        scored: List<ScoredBlock>,
        pageWidth: Int,
        pageHeight: Int,
        page: Bitmap? = null,
    ): List<PaintedBlock> {
        val expanded = expand(scored, pageWidth, pageHeight, page)
        return scored.mapNotNull { entry ->
            val translated = entry.block.translation.isNotBlank()
            if (!entry.verdict.covered) return@mapNotNull null
            // A block that should have words but has none is a translation that failed, and
            // painting a blank rectangle over the words it could not replace is worse than leaving
            // them. The cover-only verdicts are the ones with nothing to say by design.
            if (!translated && entry.verdict.translatable) return@mapNotNull null
            if (entry.verdict == BlockVerdict.FURIGANA && !glosses(entry, scored)) {
                return@mapNotNull null
            }
            PaintedBlock(
                id = entry.block.id,
                // Expanding buys room for text to be set into. A cover-up has no text, so expanding
                // it would only paint over more of the drawing than the gloss ever hid.
                rect = if (translated) {
                    expanded.getValue(entry.block.id)
                } else {
                    erasure(entry.block, pageWidth, pageHeight)
                },
                text = entry.block.translation.takeIf { it.isNotBlank() },
                fill = entry.ring.color,
            )
        }
    }

    /**
     * Grows translated blocks so their text has somewhere to go, without letting them collide.
     *
     * Two separate problems, one answer. A vertical Japanese column is tall and narrow — one
     * measured block was 40x200 — and English set into that shape wraps to roughly one character
     * per line. And a bubble of any orientation holds far more Latin characters than the CJK it
     * replaces: a four-character line comes back as thirty, and fitted to the original box that is
     * type nobody can read. So each block asks how much area its translation actually needs at a
     * size matched to the glyphs it is replacing, and grows about its own centre towards it.
     *
     * Growth is bounded three times over, because a box that grows too far paints over the drawing:
     * by half the gap to the nearest neighbour sharing its span, which is what stops two boxes both
     * claiming the same gap; by [MAX_GROWTH]; and, when the page is at hand, by what is actually
     * underneath — a side that would cross something other than the fill colour keeps trying
     * smaller extents and then gives up. The bubble's own outline would be the right shape here,
     * and getting it needs segmentation; measuring the pixels is the cheap approximation to it.
     */
    private fun expand(
        scored: List<ScoredBlock>,
        pageWidth: Int,
        pageHeight: Int,
        page: Bitmap?,
    ): Map<Int, Rect> {
        val original = scored.associate { it.block.id to it.block.box }
        return scored.associate { entry ->
            val block = entry.block
            val own = block.box
            if (block.translation.isBlank()) return@associate block.id to own

            val (wantedWidth, wantedHeight) = wanted(block)
            if (wantedWidth <= own.width() && wantedHeight <= own.height()) {
                return@associate block.id to own
            }

            // Half the gap to every neighbour that could be in the way. Only a block sharing some
            // of an axis' span can be; one further up the page cannot.
            var left = 0
            var right = pageWidth
            var top = 0
            var bottom = pageHeight
            original.forEach { (id, other) ->
                if (id == block.id) return@forEach
                if (other.top < own.bottom && other.bottom > own.top) {
                    if (other.right <= own.left) left = max(left, (other.right + own.left) / 2)
                    if (other.left >= own.right) right = min(right, (own.right + other.left) / 2)
                }
                if (other.left < own.right && other.right > own.left) {
                    if (other.bottom <= own.top) top = max(top, (other.bottom + own.top) / 2)
                    if (other.top >= own.bottom) bottom = min(bottom, (own.bottom + other.top) / 2)
                }
            }

            val fill = entry.ring.color
            val halfWidth = max(0, wantedWidth - own.width()) / 2
            val halfHeight = max(0, wantedHeight - own.height()) / 2
            // Never past the block's own edge: a neighbour that already overlaps would otherwise
            // push a limit inside this block and crop the very text the growth exists for.
            val roomLeft = own.left - min(left + GUTTER, own.left)
            val roomRight = max(right - GUTTER, own.right) - own.right
            val roomTop = own.top - min(top + GUTTER, own.top)
            val roomBottom = max(bottom - GUTTER, own.bottom) - own.bottom

            block.id to Rect(
                own.left - extend(page, fill, Side.LEFT, own, min(halfWidth, roomLeft)),
                own.top - extend(page, fill, Side.TOP, own, min(halfHeight, roomTop)),
                own.right + extend(page, fill, Side.RIGHT, own, min(halfWidth, roomRight)),
                own.bottom + extend(page, fill, Side.BOTTOM, own, min(halfHeight, roomBottom)),
            )
        }
    }

    /** How wide and tall a block's translation wants its box to be. */
    private fun wanted(block: TextBlock): Pair<Int, Int> {
        val own = block.box
        val glyph = block.glyphPx.takeIf { it > 1f }
            ?: (min(own.width(), own.height()) / 3f).coerceAtLeast(4f)
        val size = glyph * LATIN_GLYPH_RATIO
        val needed = block.translation.length * CHAR_AREA * size * size
        val have = (own.width().toFloat() * own.height()).coerceAtLeast(1f)
        val factor = sqrt(needed / have).coerceIn(1f, MAX_GROWTH)

        // A vertical column is widened towards a readable shape whatever its text measures, since
        // even a short translation is unreadable set one character to a line.
        val width = max(
            (own.width() * factor).toInt(),
            if (block.vertical) (own.height() * VERTICAL_ASPECT).toInt() else 0,
        )
        // Its height is left alone: it already spans its bubble, and the room it is short of is
        // sideways.
        val height = if (block.vertical) own.height() else (own.height() * factor).toInt()
        return width to height
    }

    private enum class Side { LEFT, RIGHT, TOP, BOTTOM }

    /**
     * How far a box may actually grow on one side, having looked at what is there.
     *
     * Smaller extents are tried before the side is given up on, so a box hemmed in by artwork a few
     * pixels away still takes the few pixels rather than nothing at all.
     */
    private fun extend(
        page: Bitmap?,
        fill: Int,
        side: Side,
        own: Rect,
        maxExtra: Int,
    ): Int {
        if (maxExtra <= 0) return 0
        if (page == null) return maxExtra
        var extra = maxExtra
        repeat(SAFETY_STEPS) {
            if (extra <= 0) return 0
            if (flat(page, strip(own, side, extra), fill)) return extra
            extra = extra * 2 / 3
        }
        return 0
    }

    /** The band a box would gain by growing [extra] pixels on [side]. */
    private fun strip(own: Rect, side: Side, extra: Int): Rect = when (side) {
        Side.LEFT -> Rect(own.left - extra, own.top, own.left, own.bottom)
        Side.RIGHT -> Rect(own.right, own.top, own.right + extra, own.bottom)
        Side.TOP -> Rect(own.left, own.top - extra, own.right, own.top)
        Side.BOTTOM -> Rect(own.left, own.bottom, own.right, own.bottom + extra)
    }

    /** Whether a strip is one colour, and that colour the one about to be painted over it. */
    private fun flat(page: Bitmap, strip: Rect, fill: Int): Boolean {
        if (strip.isEmpty) return true
        val ring = RingSampler.region(page, strip)
        return ring.flatShare >= SAFE_FLAT && RingSampler.sameColor(ring.color, fill)
    }

    /**
     * Whether a ruby block annotates something that is actually being replaced.
     *
     * Furigana is covered on one premise: the kanji beside it has just become English, so leaving
     * the gloss would strand Japanese ruby against it. Where that kanji is not there to be replaced
     * the premise fails, and covering the ruby alone erases half of a Japanese phrase and puts
     * nothing in its place — a page left worse than untouched. The page pass does exactly this on
     * captions set in large display type, reading their small plain ruby while missing the
     * characters it glosses entirely.
     */
    private fun glosses(ruby: ScoredBlock, scored: List<ScoredBlock>): Boolean {
        val reach = (ruby.block.glyphPx * RUBY_REACH).toInt().coerceAtLeast(2)
        val near = Rect(ruby.block.box).apply { inset(-reach, -reach) }
        return scored.any { other ->
            other.block.id != ruby.block.id &&
                other.block.translation.isNotBlank() &&
                Rect.intersects(near, other.block.box)
        }
    }

    /**
     * A block's bounds with a little margin, for painting it out rather than writing into it.
     *
     * The box hugs the glyphs exactly, which is right for placing text but leaves the outer edge of
     * a stroke showing when the same box is used to cover one up.
     */
    private fun erasure(block: TextBlock, pageWidth: Int, pageHeight: Int): Rect {
        val pad = (block.glyphPx * ERASURE_PAD).toInt().coerceAtLeast(1)
        return Rect(
            (block.box.left - pad).coerceAtLeast(0),
            (block.box.top - pad).coerceAtLeast(0),
            (block.box.right + pad).coerceAtMost(pageWidth),
            (block.box.bottom + pad).coerceAtMost(pageHeight),
        )
    }
}

/**
 * Draws painted blocks onto a canvas at a given scale.
 *
 * Shared by the reader's overlay and the calibration screen's preview so the two cannot drift:
 * calibrating against a render that differs from the reader's would be calibrating against nothing.
 */
class BlockPainter(private val density: Float) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val basePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }

    /** Fitted layouts, keyed by block. Cleared whenever the blocks or the scale change. */
    private val layouts = mutableMapOf<Int, StaticLayout>()

    private val inset = (3f * density).toInt()

    fun reset() = layouts.clear()

    fun draw(canvas: Canvas, blocks: List<PaintedBlock>, scale: Float) {
        blocks.forEach { block ->
            val left = block.rect.left * scale
            val top = block.rect.top * scale
            val right = block.rect.right * scale
            val bottom = block.rect.bottom * scale
            val width = (block.rect.width() * scale).toInt() - inset * 2
            val height = (block.rect.height() * scale).toInt() - inset * 2

            fillPaint.color = block.fill

            val text = block.text
            if (text.isNullOrBlank() || width <= 0 || height <= 0) {
                canvas.drawRect(left, top, right, bottom, fillPaint)
                return@forEach
            }

            // Ink chosen from the fill rather than fixed, which is what makes a white-on-black
            // bubble come out white-on-black instead of unreadable.
            val ink = if (isDark(block.fill)) Color.WHITE else Color.BLACK
            val layout = layouts.getOrPut(block.id) { fit(text, width, height, ink) }

            // Text that would not fit even at the smallest size this is willing to draw takes the
            // fill with it rather than spilling over the artwork. Shrinking further instead is what
            // the floor exists to prevent: type nobody can read is not a translation.
            val bleed = max(0, layout.height - height) / 2f
            canvas.drawRect(left, top - bleed, right, bottom + bleed, fillPaint)

            canvas.withTranslation(
                left + inset,
                top + inset - bleed + max(0f, (height - layout.height) / 2f),
            ) {
                layout.draw(this)
            }
        }
    }

    /**
     * Bisects to the largest type size whose wrapped text still fits.
     *
     * Wrapping is not a function you can invert — how tall a string lays out depends on where the
     * breaks happen to fall — so the only reliable question is "does this size fit", asked
     * repeatedly. The layout gets a paint of its own: a [StaticLayout] reads its paint again at
     * draw time, so cached layouts sharing one would every one of them draw at whichever size was
     * set last, with breaks computed for a different size entirely.
     */
    private fun fit(text: String, width: Int, height: Int, ink: Int): StaticLayout {
        val paint = TextPaint(basePaint).apply { color = ink }
        var low = MIN_TEXT_SP * density
        var high = MAX_TEXT_SP * density
        var best = low
        repeat(BISECTION_STEPS) {
            val mid = (low + high) / 2f
            paint.textSize = mid
            if (layoutOf(text, width, paint).height <= height) {
                best = mid
                low = mid
            } else {
                high = mid
            }
        }
        paint.textSize = best
        return layoutOf(text, width, paint)
    }

    private fun layoutOf(text: String, width: Int, paint: TextPaint): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

    private fun isDark(color: Int): Boolean =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) < 128

    private companion object {
        /**
         * The smallest type this will draw, rather than the smallest that fits.
         *
         * It used to be 4sp, on the reasoning that something is better than nothing. It is not: a
         * long English sentence squeezed into the box a four-character Japanese line came out of is
         * unreadable at any distance, and unreadable text over a covered bubble is strictly worse
         * than the Japanese it replaced. Below this the fill grows to hold the text instead.
         */
        const val MIN_TEXT_SP = 9f
        const val MAX_TEXT_SP = 96f

        /** Bisection steps; twelve resolves the size to well under a pixel. */
        const val BISECTION_STEPS = 12
    }
}
