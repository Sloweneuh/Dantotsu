package ani.dantotsu.media.anime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * The player's sleep timer: pauses after a number of minutes, or when the episode playing ends
 * (and then no next episode autoplays). Lives as long as the player, so it carries across the
 * episodes watched in one sitting. [onExpire] pauses; [onChange] runs whenever it starts or stops.
 */
class PlayerSleepTimer(
    private val onExpire: () -> Unit,
    private val onChange: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable {
        stopAt = 0L
        onExpire()
        onChange()
    }

    /** When the timer pauses, as elapsed-realtime millis; 0 when it isn't counting down. */
    private var stopAt = 0L

    var endOfEpisode = false
        private set

    val isActive get() = stopAt > 0L || endOfEpisode

    /** Whole minutes left, rounded up so the last one doesn't read 0; 0 when not counting down. */
    val minutesLeft: Int
        get() = if (stopAt == 0L) 0
        else ((stopAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L) / 60_000L + 1).toInt()

    fun setMinutes(minutes: Int) {
        clear()
        if (minutes > 0) {
            stopAt = SystemClock.elapsedRealtime() + minutes * 60_000L
            handler.postDelayed(expire, minutes * 60_000L)
        }
        onChange()
    }

    fun setEndOfEpisode() {
        clear()
        endOfEpisode = true
        onChange()
    }

    fun cancel() {
        clear()
        onChange()
    }

    /** At an episode's end: true when the timer was waiting for it, and has now gone off. */
    fun onEpisodeEnded(): Boolean {
        if (!endOfEpisode) return false
        endOfEpisode = false
        onExpire()
        onChange()
        return true
    }

    private fun clear() {
        handler.removeCallbacks(expire)
        stopAt = 0L
        endOfEpisode = false
    }
}
