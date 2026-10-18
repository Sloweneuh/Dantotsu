package ani.dantotsu.connections.anilist

import android.os.SystemClock
import ani.dantotsu.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps AniList queries inside the per-minute budget instead of finding the edge by tripping it.
 *
 * [AnilistRateLimit] only reacts: a screen fires its whole burst, the request past the limit gets a
 * 429, and every query after it fails for a minute. At AniList's degraded 30 a minute one screen's
 * burst plus a widget refresh is enough to do that. Here a request that would overspend the window
 * waits for the oldest one to age out, so a burst is spread over time rather than half-refused.
 *
 * The budget is learned from `X-RateLimit-Limit`, so it follows AniList between 30 and 90 without a
 * release. `X-RateLimit-Remaining` corrects the count for what this object never saw — requests sent
 * outside [Anilist.executeQuery], or another device on the same IP.
 *
 * The wait is a cancellable [delay]: a screen closed while its queries are queued drops them without
 * spending budget the next screen needs.
 */
object AnilistPacer {

    private const val WINDOW_MS = 60_000L

    /** AniList's degraded limit; replaced by the header from the first response. */
    private const val DEFAULT_LIMIT = 30

    /** Left unspent for requests this object cannot count until the server reports them. */
    private const val HEADROOM = 2

    private val mutex = Mutex()

    /** When each request in the current window was let out, oldest first. */
    private val sent = ArrayDeque<Long>()

    @Volatile
    private var limit = DEFAULT_LIMIT

    private fun budget() = (limit - HEADROOM).coerceAtLeast(1)

    private fun prune(now: Long) {
        while (sent.isNotEmpty() && sent.first() <= now - WINDOW_MS) sent.removeFirst()
    }

    /** Suspends until a request fits in the window, then counts it as sent. */
    suspend fun acquire() {
        var logged = false
        while (true) {
            val wait = mutex.withLock {
                val now = SystemClock.elapsedRealtime()
                prune(now)
                if (sent.size < budget()) {
                    sent.addLast(now)
                    0L
                } else sent.first() + WINDOW_MS - now
            }
            if (wait <= 0) return
            if (!logged) {
                Logger.log("AnilistPacer: budget of ${budget()}/min spent, queueing for ${wait}ms")
                logged = true
            }
            delay(wait)
        }
    }

    /** Syncs with what AniList reported on a response. */
    suspend fun record(limitHeader: Int?, remaining: Int?) {
        limitHeader?.takeIf { it > 0 }?.let { limit = it }
        if (remaining == null || remaining < 0) return
        mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            prune(now)
            // Requests AniList counted that never went through here. Dated now, which may hold
            // them a little past their real expiry — waiting slightly long beats a 429.
            repeat(limit - remaining - sent.size) { sent.addLast(now) }
        }
    }
}
