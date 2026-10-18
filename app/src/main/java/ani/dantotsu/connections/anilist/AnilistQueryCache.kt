package ani.dantotsu.connections.anilist

import android.os.SystemClock
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Raw AniList responses, shared between identical queries.
 *
 * The `cacheTime` handed to NiceHttp never did anything here: OkHttp's cache only stores GETs, and
 * every AniList query is a POST. So two parts of a screen asking for the same media, or reopening a
 * page a moment later, each cost a request out of a budget of 30 a minute.
 *
 * Two layers:
 * - in flight: a query identical to one already on its way waits for that answer instead of sending
 *   its own. Applies even to `cache = 0` callers — the shared answer is exactly as fresh.
 * - stored: an answer is reused for [TTL_MS]. Short on purpose; the point is bursts and quick
 *   back-and-forth navigation, not offline data (that's [MediaListCache]).
 *
 * Any mutation [clear]s it, so the user's own edits are never answered from before they made them.
 */
object AnilistQueryCache {

    private const val TTL_MS = 2 * 60_000L

    /** In characters. A whole-library MediaListCollection can be a few MB on its own. */
    private const val MAX_CHARS = 4 * 1024 * 1024

    private class Entry(val text: String, val at: Long)

    private val lock = Any()
    private val stored = object : LruCache<String, Entry>(MAX_CHARS) {
        override fun sizeOf(key: String, value: Entry) = value.text.length.coerceAtLeast(1)
    }
    private val inFlight = HashMap<String, CompletableDeferred<String>>()

    /** Bumped by [clear], so a read that was in flight across a mutation isn't stored as fresh. */
    private var generation = 0

    fun key(query: String, variables: String, token: String?) =
        "${token?.hashCode()}\u0000$query\u0000$variables"

    suspend fun get(key: String, reuse: Boolean, fetch: suspend () -> String): String {
        while (true) {
            var leader = false
            val deferred = synchronized(lock) {
                if (reuse) stored.get(key)?.let { entry ->
                    if (SystemClock.elapsedRealtime() - entry.at < TTL_MS) return entry.text
                    stored.remove(key)
                }
                inFlight[key] ?: CompletableDeferred<String>().also {
                    inFlight[key] = it
                    leader = true
                }
            }

            if (!leader) {
                try {
                    return deferred.await()
                } catch (e: CancellationException) {
                    // The request we were waiting on belonged to a screen that closed. Unless we
                    // were cancelled too, send it ourselves.
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }

            val gen = synchronized(lock) { generation }
            try {
                val text = fetch()
                synchronized(lock) {
                    if (gen == generation) stored.put(key, Entry(text, SystemClock.elapsedRealtime()))
                    inFlight.remove(key)
                }
                deferred.complete(text)
                return text
            } catch (e: Throwable) {
                synchronized(lock) { inFlight.remove(key) }
                deferred.completeExceptionally(e)
                throw e
            }
        }
    }

    fun clear() = synchronized(lock) {
        generation++
        stored.evictAll()
    }
}
