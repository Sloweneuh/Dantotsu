package ani.dantotsu.media.manga.translation

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.get
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
 */
object RingSampler {

    /** Grid resolution of the sample, per axis. */
    private const val SAMPLES = 40

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
        val stepX = (outer.width() / SAMPLES).coerceAtLeast(1)
        val stepY = (outer.height() / SAMPLES).coerceAtLeast(1)

        val samples = ArrayList<Float>(SAMPLES * SAMPLES)
        var sum = 0.0
        var sumOfSquares = 0.0
        var y = outer.top
        while (y < outer.bottom) {
            var x = outer.left
            while (x < outer.right) {
                if (!box.contains(x, y) && x in 0 until bitmap.width && y in 0 until bitmap.height &&
                    exclude.none { it.contains(x, y) }
                ) {
                    val pixel = bitmap[x, y]
                    val luminance = (
                        0.299 * Color.red(pixel) +
                            0.587 * Color.green(pixel) +
                            0.114 * Color.blue(pixel)
                        ).toFloat()
                    samples.add(luminance)
                    sum += luminance
                    sumOfSquares += luminance * luminance
                }
                x += stepX
            }
            y += stepY
        }
        if (samples.isEmpty()) return Ring(0f, 0f, 0f, 0f)

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
        )
    }
}
