package ani.dantotsu.connections.anilist

import ani.dantotsu.R
import ani.dantotsu.getAppString
import ani.dantotsu.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The AniList rate limit as something the app can show, rather than a bare timestamp.
 *
 * AniList answers a 429 with `Retry-After` and `X-RateLimit-Reset`, and counts every response down
 * in `X-RateLimit-Remaining` (https://docs.anilist.co/guide/considerations). All three were already
 * arriving; only the reset was kept, in a loose epoch-seconds field, and the user's half of it was a
 * toast per blocked query — a burst of identical toasts saying a number that was stale before it
 * finished rendering, and then silence for the rest of the wait.
 *
 * Keeping it as a window with both ends known is what makes it displayable: how much of the wait is
 * left, out of how long it was, is a progress ring. [announced] separates a countdown AniList
 * actually gave from one this object had to assume, so the ring can decline to draw a precise
 * fraction of a number nobody promised.
 *
 * Distinct from [AnilistApiStatus] on purpose, though both pause queries. A rate limit is the API
 * working correctly and telling the client to slow down, and it ends at a time the server named; an
 * outage is the API not working, ending whenever it ends. They deserve different words and a
 * different amount of the user's attention.
 */
object AnilistRateLimit {

    /** AniList's window is a minute, which is the only sane guess when no header names one. */
    private const val ASSUMED_WINDOW_MS = 60_000L

    /**
     * @param startedAt when the limit was noticed, so the wait can be drawn as a fraction served.
     * @param endsAt when AniList said requests may resume.
     * @param announced whether [endsAt] came from a header rather than [ASSUMED_WINDOW_MS].
     */
    data class Window(val startedAt: Long, val endsAt: Long, val announced: Boolean)

    private val _window = MutableStateFlow<Window?>(null)

    /** Observed by the badge; null whenever nothing is being waited out. */
    val window: StateFlow<Window?> = _window.asStateFlow()

    fun isLimited(): Boolean = remainingMillis() > 0

    fun remainingMillis(): Long =
        (_window.value?.endsAt ?: 0L) - System.currentTimeMillis()

    fun remainingSeconds(): Long = ((remainingMillis() + 999) / 1000).coerceAtLeast(0)

    /**
     * How much of the wait is still to come, 0-100 — a ring that empties as it passes. Meaningless
     * for an assumed window, whose caller should show something indeterminate instead.
     */
    fun remainingPercent(): Int {
        val w = _window.value ?: return 0
        val total = (w.endsAt - w.startedAt).coerceAtLeast(1L)
        return ((remainingMillis().toDouble() / total) * 100).roundToInt().coerceIn(0, 100)
    }

    fun isAnnounced(): Boolean = _window.value?.announced == true

    /** What a query refused by the limit reports to its caller. */
    fun waitMessage(): String = "${getAppString(R.string.anilist_rate_limited)} · " +
            String.format(Locale.US, getAppString(R.string.anilist_api_retry_in), remainingSeconds())

    /**
     * Opens (or extends) a wait from whatever AniList sent.
     *
     * Either header alone is enough: `X-RateLimit-Reset` is an absolute epoch second and
     * `Retry-After` a relative one, so the later of the two is the safe end. Requiring both — and
     * keeping the reset even when it was absent, i.e. `0` — is how the previous code left the app
     * hammering an API that had just asked it to stop, which is what earns a manual IP block.
     *
     * Never shortens a window already running: a later 429 answered mid-wait says the same limit is
     * still in force, and taking its (smaller) countdown would let queries out early.
     */
    fun limit(retryAfterSeconds: Int?, resetEpochSeconds: Long?) {
        val now = System.currentTimeMillis()
        val fromRetry = retryAfterSeconds?.takeIf { it > 0 }?.let { now + it * 1000L } ?: 0L
        val fromReset = resetEpochSeconds?.takeIf { it > 0 }?.let { it * 1000L } ?: 0L
        val announced = fromRetry > 0 || fromReset > 0
        val endsAt = maxOf(fromRetry, fromReset, if (announced) 0L else now + ASSUMED_WINDOW_MS)
        val current = _window.value
        if (current != null && current.endsAt >= endsAt && current.announced == announced) return
        // An extension keeps the original start, so the ring carries on draining from where it was
        // instead of jumping back to full.
        val startedAt = current?.startedAt?.takeIf { it < now } ?: now
        _window.value = Window(startedAt, maxOf(endsAt, current?.endsAt ?: 0L), announced)
        Logger.log(
            "AnilistRateLimit: holding queries for ${remainingSeconds()}s" +
                    if (announced) "" else " (no countdown sent, assuming a minute)"
        )
    }

    /**
     * Takes the limit down once it has run out. Called by the badge as it ticks, since something
     * has to notice that a purely time-based state has expired — nothing arrives from AniList to
     * say so.
     */
    fun clear() {
        if (_window.value != null) _window.value = null
    }
}
