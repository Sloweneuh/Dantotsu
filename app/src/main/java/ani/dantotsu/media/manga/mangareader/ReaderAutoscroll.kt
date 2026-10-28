package ani.dantotsu.media.manga.mangareader

import android.view.Choreographer
import ani.dantotsu.util.Logger
import ani.dantotsu.R
import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import java.util.Timer
import java.util.TimerTask

/**
 * The reader's autoscroll: a smooth scroll tied to the display's frames in the continuous layout,
 * a page turned at an interval in the paged one, at the AutoScrollSpeed preference.
 */
class ReaderAutoscroll(private val activity: MangaReaderActivity) {

    var isOn = false
        private set

    private var timer: Timer? = null
    private var lastFrameNanos = 0L
    private var accumulatedPx = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isOn) return
            if (lastFrameNanos != 0L) {
                // Cap elapsed to 50 ms to avoid a large jump after a frame drop or resume
                val elapsedSec = minOf((frameTimeNanos - lastFrameNanos) / 1_000_000_000f, 0.05f)
                val speed = PrefManager.getVal<Float>(PrefName.AutoScrollSpeed)
                accumulatedPx += speed * 240f * elapsedSec
                val scroll = accumulatedPx.toInt()
                if (scroll > 0) {
                    accumulatedPx -= scroll
                    activity.binding.mangaReaderRecycler.scrollBy(0, scroll)
                }
            }
            lastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun toggle() {
        // Only allow autoscroll when using Continuous layout
        if (activity.defaultSettings.layout != CurrentReaderSettings.Layouts.CONTINUOUS) {
            // Inform user and do not toggle
            try { snackString(activity.getString(R.string.autoscroll_only_continuous)) } catch (e: Exception) { Logger.log(e) }
            return
        }
        if (isOn) stop() else start()
    }

    fun start() {
        isOn = true
        val binding = activity.binding
        binding.mangaReaderAutoscroll.setImageResource(R.drawable.ic_round_pause_24)
        timer?.cancel()
        val speed = PrefManager.getVal<Float>(PrefName.AutoScrollSpeed)
        if (activity.defaultSettings.layout == CurrentReaderSettings.Layouts.PAGED) {
            // Higher `speed` -> faster. Use a tighter base so max values are noticeably faster.
            val interval = (1000L / maxOf(0.1f, speed)).toLong()
            timer = Timer().apply {
                scheduleAtFixedRate(object : TimerTask() {
                    override fun run() {
                        val pager = binding.mangaReaderPager
                        pager.post {
                            try {
                                if (activity.directionRLBT) {
                                    if (pager.currentItem > 0) pager.currentItem = pager.currentItem - 1
                                } else {
                                    if (pager.currentItem < (pager.adapter?.itemCount?.minus(1) ?: 0)) {
                                        pager.currentItem = pager.currentItem + 1
                                    }
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }, interval, interval)
            }
        } else {
            // Continuous: vsync-tied smooth scroll via Choreographer
            lastFrameNanos = 0L
            accumulatedPx = 0f
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    fun stop() {
        isOn = false
        release()
        activity.binding.mangaReaderAutoscroll.setImageResource(R.drawable.ic_round_play_arrow_24)
    }

    fun updateSpeed(newSpeed: Float) {
        PrefManager.setVal(PrefName.AutoScrollSpeed, newSpeed)
        // Paged mode uses a timer interval, so must restart; continuous reads the speed every frame.
        if (isOn && activity.defaultSettings.layout == CurrentReaderSettings.Layouts.PAGED) {
            timer?.cancel()
            start()
        }
    }

    /** Stops the timer and the frame callback, leaving the button alone; for the reader closing. */
    fun release() {
        timer?.cancel()
        timer = null
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        lastFrameNanos = 0L
        accumulatedPx = 0f
    }
}
