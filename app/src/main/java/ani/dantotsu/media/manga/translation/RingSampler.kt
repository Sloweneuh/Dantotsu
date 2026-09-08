package ani.dantotsu.media.manga.translation

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.get
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Measures what surrounds a block, which is how a speech bubble is told from artwork.
 *
 * The ring rather than the interior: inside a block the text strokes guarantee high variance
 * whether or not it sits in a bubble, so the interior carries no signal. What separates them is
 * what is around the text — a bubble is a uniform field, artwork is not.
 *
 * Uniformity alone, not brightness. Requiring a *bright* ring rejected white-on-black dialogue,
 * which measured a median of 2 and was the most uniform region on its page. Since the question
 * being asked is "can this be painted over without destroying artwork", a uniform dark field
 * qualifies exactly as much as a light one.
 *
 * Colour is measured alongside luminance for two reasons that turned out to be the same reason.
 * Lettering placed straight onto a flat sky or a screentone — no bubble at all — is safe to cover
 * for exactly as long as the thing being covered is one colour, and painting it needs that colour
 * rather than a grey built from its brightness. See [Ring.color] and [Ring.flatShare].
 */
object RingSampler {

    /** Grid resolution of the sample, per axis. */
    private const val SAMPLES = 40

    /**
     * How far a sample may sit from the ring's median colour, per channel, and still count as the
     * same colour.
     *
     * Loose enough to absorb JPEG ringing and the gradient in a screentone, tight enough that a
     * drawing does not read as one colour: on measured pages a flat background held 96-99% of its
     * samples inside this band while artwork managed under 60%.
     */
    private const val FLAT_TOLERANCE = 24

    /**
     * @param pad      ring thickness in glyph widths. Padding by a fraction of the *block* was the
     *                 first attempt and misclassified over half a page of plain dialogue: a tall
     *                 vertical column padded by a quarter of its own height reaches well past its
     *                 bubble and measures the artwork beyond.
     * @param exclude  regions to leave out, used when ringing a single line — its neighbouring
     *                 columns sit exactly where the ring falls, and including them makes healthy
     *                 text read as artwork.
     */
    fun sample(
        bitmap: Bitmap,
        box: Rect,
        glyphPx: Float,
        pad: Float,
        exclude: List<Rect> = emptyList(),
    ): Ring {
        val padPx = (glyphPx * pad).toInt().coerceAtLeast(2)
        val outer = Rect(box.left - padPx, box.top - padPx, box.right + padPx, box.bottom + padPx)
        return measure(bitmap, outer, exclude, skip = box)
    }

    /**
     * The same measurement over a region taken as a whole, with no hole punched in it.
     *
     * Used to ask whether somewhere a block is about to grow *into* is the flat colour it is being
     * painted with — a question about the region itself rather than about a ring around anything.
     */
    fun region(bitmap: Bitmap, area: Rect, exclude: List<Rect> = emptyList()): Ring =
        measure(bitmap, area, exclude, skip = null)

    private fun measure(bitmap: Bitmap, outer: Rect, exclude: List<Rect>, skip: Rect?): Ring {
        val stepX = (outer.width() / SAMPLES).coerceAtLeast(1)
        val stepY = (outer.height() / SAMPLES).coerceAtLeast(1)

        val capacity = (SAMPLES + 1) * (SAMPLES + 1)
        val samples = ArrayList<Float>(capacity)
        val reds = ArrayList<Int>(capacity)
        val greens = ArrayList<Int>(capacity)
        val blues = ArrayList<Int>(capacity)
        var sum = 0.0
        var sumOfSquares = 0.0
        var y = outer.top
        while (y < outer.bottom) {
            var x = outer.left
            while (x < outer.right) {
                if (skip?.contains(x, y) != true && x in 0 until bitmap.width &&
                    y in 0 until bitmap.height && exclude.none { it.contains(x, y) }
                ) {
                    val pixel = bitmap[x, y]
                    val red = Color.red(pixel)
                    val green = Color.green(pixel)
                    val blue = Color.blue(pixel)
                    val luminance = (0.299 * red + 0.587 * green + 0.114 * blue).toFloat()
                    samples.add(luminance)
                    reds.add(red)
                    greens.add(green)
                    blues.add(blue)
                    sum += luminance
                    sumOfSquares += luminance * luminance
                }
                x += stepX
            }
            y += stepY
        }
        if (samples.isEmpty()) return Ring(0f, 0f, 0f, 0f, Color.WHITE, 0f)

        // Component-wise medians, which is a real colour off the page for anything with a dominant
        // one and a robust compromise for anything without. Taken before `samples` is sorted, since
        // the flat share below pairs each channel list with its own order and not with luminance.
        val color = Color.rgb(median(reds), median(greens), median(blues))
        var flat = 0
        for (i in reds.indices) {
            val near = abs(reds[i] - Color.red(color)) <= FLAT_TOLERANCE &&
                abs(greens[i] - Color.green(color)) <= FLAT_TOLERANCE &&
                abs(blues[i] - Color.blue(color)) <= FLAT_TOLERANCE
            if (near) flat++
        }

        samples.sort()
        val mean = sum / samples.size
        val variance = (sumOfSquares / samples.size - mean * mean).coerceAtLeast(0.0)
        return Ring(
            median = samples[samples.size / 2],
            // Interquartile range, not standard deviation. A ring is mostly bubble interior with a
            // minority of ink from the bubble's own outline, and a deviation is at the mercy of
            // that minority: rings at 208-244 mean on a page of ordinary dialogue reported
            // deviations of 43-74, high enough to read as artwork, while their quartile ranges sat
            // at 0-5.
            iqr = samples[samples.size * 3 / 4] - samples[samples.size / 4],
            mean = mean.toFloat(),
            stdDev = sqrt(variance).toFloat(),
            color = color,
            flatShare = flat.toFloat() / samples.size,
        )
    }

    /** Sorts in place — the caller has no further use for the channel's order. */
    private fun median(channel: ArrayList<Int>): Int {
        channel.sort()
        return channel[channel.size / 2]
    }

    /** How far two colours sit apart, as the largest of their per-channel differences. */
    fun distance(a: Int, b: Int): Int = max(
        abs(Color.red(a) - Color.red(b)),
        max(abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b))),
    )

    /** Whether two colours are near enough to be treated as the same field. */
    fun sameColor(a: Int, b: Int): Boolean = distance(a, b) <= FLAT_TOLERANCE
}
