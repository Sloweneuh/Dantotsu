package ani.dantotsu.media.manga.translation

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
import kotlin.math.roundToInt

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
    private const val PREVIEW_ASPECT = 1.4f

    /** Page pixels left between two widened boxes, so neighbours read as separate. */
    private const val GUTTER = 3

    /** Margin added when covering a block up, in glyph widths. */
    private const val ERASURE_PAD = 0.25f

    /**
     * The blocks to paint, in page coordinates.
     *
     * Only what should be covered is returned: artwork and text the recognizer does not believe
     * are left showing, since replacing them with a blank rectangle would delete the page and put
     * nothing in its place. Furigana are the one thing covered without being translated — the
     * kanji they gloss has just become English, and leaving the gloss strands Japanese ruby against
     * it.
     */
    fun paint(scored: List<ScoredBlock>, pageWidth: Int, pageHeight: Int): List<PaintedBlock> {
        val widened = widen(scored, pageWidth)
        return scored.mapNotNull { entry ->
            val translated = entry.block.translation.isNotBlank()
            if (!entry.verdict.covered) return@mapNotNull null
            if (!translated && entry.verdict != BlockVerdict.FURIGANA) return@mapNotNull null
            PaintedBlock(
                id = entry.block.id,
                // Widening buys room for text to be set into. A cover-up has no text, so widening
                // it would only paint over more of the drawing than the gloss ever hid.
                rect = if (translated) {
                    widened.getValue(entry.block.id)
                } else {
                    erasure(entry.block, pageWidth, pageHeight)
                },
                text = entry.block.translation.takeIf { it.isNotBlank() },
                fill = grey(entry.ring.median),
            )
        }
    }

    /**
     * Widens vertical blocks so translated text has somewhere to go, without letting them collide.
     *
     * A vertical Japanese column is tall and narrow — one measured block was 40x200 — and English
     * set into that shape wraps to roughly one character per line. Each block therefore grows
     * about its own centre, but only into space nothing else claims: each takes half the gap to its
     * nearest neighbour that shares any of its vertical span, which is what stops two boxes both
     * claiming the same gap and painting over each other.
     *
     * The cost is real and worth remembering: a widened box covers more of the drawing than the
     * text ever did. The bubble's own outline would be the right shape here, and getting it needs
     * segmentation.
     */
    private fun widen(scored: List<ScoredBlock>, pageWidth: Int): Map<Int, Rect> {
        val original = scored.associate { it.block.id to it.block.box }
        return scored.associate { entry ->
            val block = entry.block
            val own = block.box
            if (!block.vertical) return@associate block.id to own

            val wanted = (own.height() * PREVIEW_ASPECT).toInt()
            if (own.width() >= wanted) return@associate block.id to own

            var left = 0
            var right = pageWidth
            original.forEach { (id, other) ->
                if (id == block.id) return@forEach
                // Only a block sharing some of its vertical span can be in the way; one further up
                // the page cannot be.
                if (other.top >= own.bottom || other.bottom <= own.top) return@forEach
                if (other.right <= own.left) left = max(left, (other.right + own.left) / 2)
                if (other.left >= own.right) right = min(right, (own.right + other.left) / 2)
            }
            left += GUTTER
            right -= GUTTER

            val grow = (wanted - own.width()) / 2
            block.id to Rect(
                // Never past the block's own edge: a neighbour that already overlaps horizontally
                // would otherwise push a limit inside this block and crop the very text the
                // widening exists for.
                (own.left - grow).coerceAtLeast(min(left, own.left)),
                own.top,
                (own.right + grow).coerceAtMost(max(right, own.right)),
                own.bottom,
            )
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

    /**
     * The colour a block is painted with, from its ring's median luminance.
     *
     * Grey built from luminance rather than the ring's actual colour, because that is all the ring
     * measures — right for the white and inverted-black bubbles that dominate, visibly wrong on a
     * coloured one. Sampling the median colour is the fix if that shows up on real pages.
     */
    private fun grey(median: Float): Int =
        median.roundToInt().coerceIn(0, 255).let { Color.rgb(it, it, it) }
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
            val width = (block.rect.width() * scale).toInt() - inset * 2
            val height = (block.rect.height() * scale).toInt() - inset * 2

            fillPaint.color = block.fill
            canvas.drawRect(
                left,
                top,
                block.rect.right * scale,
                block.rect.bottom * scale,
                fillPaint,
            )

            val text = block.text
            if (text.isNullOrBlank() || width <= 0 || height <= 0) return@forEach

            // Ink chosen from the fill rather than fixed, which is what makes a white-on-black
            // bubble come out white-on-black instead of unreadable.
            val ink = if (isDark(block.fill)) Color.WHITE else Color.BLACK
            val layout = layouts.getOrPut(block.id) { fit(text, width, height, ink) }

            canvas.withTranslation(
                left + inset,
                top + inset + max(0f, (height - layout.height) / 2f),
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
        const val MIN_TEXT_SP = 4f
        const val MAX_TEXT_SP = 96f

        /** Bisection steps; twelve resolves the size to well under a pixel. */
        const val BISECTION_STEPS = 12
    }
}
