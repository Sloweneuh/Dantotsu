package ani.dantotsu.media.screenshot

import android.graphics.Bitmap

/**
 * A sparse strip of small frames across the captured window, so dragging a trim handle can show the
 * frame it is over without waiting for anything.
 *
 * This is the idea behind a video site's scrubber preview: never decode in response to a drag, look
 * the frame up instead. What it deliberately does *not* do is decode the window separately to build
 * itself — an earlier version re-exported the window through [ClipExporter] at thumbnail size,
 * which needs a second decoder and an encoder, and on a device without hardware codecs took long
 * enough that the strip was never ready when it was actually wanted.
 *
 * Instead it is filled from frames the preview player has already put on screen: a quick pass over
 * the window when the sheet opens, and then continuously as the preview plays. Frames therefore
 * cost nothing beyond the decode that was happening anyway, and the strip only gets denser.
 */
class ClipStoryboard(windowDurationMs: Long) {

    /** Milliseconds each slot covers. */
    private val slotMs: Long =
        (windowDurationMs / MAX_SLOTS).coerceAtLeast(MIN_SLOT_MS)

    private val frames = arrayOfNulls<Bitmap>(
        ((windowDurationMs / slotMs) + 1).toInt().coerceIn(1, MAX_SLOTS + 1)
    )

    private fun slotOf(positionMs: Long) =
        (positionMs / slotMs).toInt().coerceIn(0, frames.size - 1)

    /** Files [bitmap] under [positionMs], replacing whatever was there. */
    @Synchronized
    fun put(positionMs: Long, bitmap: Bitmap) {
        val slot = slotOf(positionMs)
        frames[slot]?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
        frames[slot] = bitmap
    }

    /** True when [positionMs] already has a frame, i.e. capturing one again would be wasted. */
    @Synchronized
    fun has(positionMs: Long) = frames[slotOf(positionMs)]?.isRecycled == false

    /**
     * The closest frame to [positionMs], searching outwards. Returning a neighbour rather than
     * nothing is what keeps scrubbing continuous while the strip is still filling in.
     */
    @Synchronized
    fun nearest(positionMs: Long): Bitmap? {
        val slot = slotOf(positionMs)
        for (distance in 0 until frames.size) {
            frames.getOrNull(slot - distance)?.takeIf { !it.isRecycled }?.let { return it }
            frames.getOrNull(slot + distance)?.takeIf { !it.isRecycled }?.let { return it }
        }
        return null
    }

    /** Evenly spaced positions to fill, nearest to [aroundMs] first. */
    fun fillPlan(aroundMs: Long): List<Long> =
        frames.indices.map { it * slotMs }.sortedBy { kotlin.math.abs(it - aroundMs) }

    @Synchronized
    fun recycle() {
        frames.indices.forEach {
            frames[it]?.takeIf { frame -> !frame.isRecycled }?.recycle()
            frames[it] = null
        }
    }

    companion object {
        private const val MAX_SLOTS = 48
        private const val MIN_SLOT_MS = 400L
    }
}
