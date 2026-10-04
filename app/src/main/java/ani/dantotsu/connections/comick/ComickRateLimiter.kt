package ani.dantotsu.connections.comick

import ani.dantotsu.util.Logger
import okhttp3.Interceptor
import okhttp3.Response
import kotlin.math.max
import kotlin.math.min

/**
 * One request budget for everything sent to `api.comick.dev` — the public catalog ([ComickApi])
 * and the connected library ([Comick]) alike, since Comick counts them together: 200 requests a
 * minute per IP, a 429 with `Retry-After` past that.
 *
 * A token bucket a little under that rate, with room for a short burst so a screen firing a few
 * requests at once isn't held back. A bulk job — "Sync all", the new-chapter check — settles into
 * the steady rate instead of running into the wall and failing whatever was in flight.
 *
 * On a 429 every Comick request waits out the `Retry-After`, not only the one that got it: the
 * others would only collect 429s of their own. A short wait is then retried once; a longer one is
 * passed through as the failure it is rather than stalling the caller.
 *
 * Blocks the calling thread, which is an OkHttp `execute()` already running off the main thread.
 */
internal object ComickRateLimiter : Interceptor {
    private const val HOST = "api.comick.dev"

    /** Requests a second, held steady: 180 a minute, under the 200 Comick allows. */
    private const val PER_SECOND = 3.0

    /** How many can go at once after a quiet spell. */
    private const val BURST = 15.0

    /** Longest `Retry-After` worth sleeping through on a 429 before giving up on the request. */
    private const val MAX_RETRY_WAIT_SECONDS = 10L

    /** Assumed when a 429 names no wait. */
    private const val DEFAULT_BACKOFF_SECONDS = 5L

    private var tokens = BURST
    private var refilledAt = System.nanoTime()
    private var blockedUntil = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != HOST) return chain.proceed(request)
        acquire()
        val response = chain.proceed(request)
        if (response.code != 429) return response

        val wait = response.header("Retry-After")?.trim()?.toLongOrNull()
        backOff(wait ?: DEFAULT_BACKOFF_SECONDS)
        if (wait == null || wait > MAX_RETRY_WAIT_SECONDS) {
            Logger.log("Comick: rate limited (Retry-After: ${wait ?: "none"}) for ${request.url}")
            return response
        }
        Logger.log("Comick: rate limited, retrying in ${wait}s for ${request.url}")
        response.close()
        acquire()
        return chain.proceed(request)
    }

    /** Takes one request's worth of budget, sleeping until there is one. */
    private fun acquire() {
        while (true) {
            val sleepMs = synchronized(this) {
                val now = System.nanoTime()
                if (now < blockedUntil) {
                    (blockedUntil - now) / 1_000_000 + 1
                } else {
                    tokens = min(BURST, tokens + (now - refilledAt) / 1e9 * PER_SECOND)
                    refilledAt = now
                    if (tokens >= 1.0) {
                        tokens -= 1.0
                        return
                    }
                    ((1.0 - tokens) / PER_SECOND * 1000).toLong() + 1
                }
            }
            Thread.sleep(sleepMs)
        }
    }

    /** Holds every request back [seconds], and starts the budget again from empty after. */
    private fun backOff(seconds: Long) = synchronized(this) {
        val until = System.nanoTime() + max(seconds, 1) * 1_000_000_000
        blockedUntil = max(blockedUntil, until)
        tokens = 0.0
        refilledAt = blockedUntil
    }
}
