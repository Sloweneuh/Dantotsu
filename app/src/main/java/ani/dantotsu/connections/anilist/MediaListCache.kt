package ani.dantotsu.connections.anilist

import android.content.Context
import ani.dantotsu.util.Logger
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The last response to the user's own list query, kept so the list screen has something to show
 * while it asks again.
 *
 * Opening the list is one request, and measurement put essentially all of the wait in it: ~1.15s
 * waiting for AniList to assemble a thousand-entry collection and ~0.5s pulling the 1.7MB answer
 * down, against 55ms to deserialise it and 6ms to turn it into [ani.dantotsu.media.Media] objects
 * and sort them. Nothing about that gets much better by asking more cleverly — but none of it has to
 * be waited through twice, because the same answer was already fetched the last time the screen was
 * opened.
 *
 * Those numbers are also why this stores the raw response rather than anything derived. Re-reading
 * it costs ~60ms end to end, so there is nothing for a cleverer format to win, and raw JSON needs no
 * schema of its own: the model can grow a field without any stored copy becoming unreadable, and the
 * sort order is applied when the response is turned into lists, so a cached response honours
 * whatever sort is current rather than the one it was fetched under.
 *
 * Deliberately *not* a freshness cache. There is no expiry and nothing consults an age: what is
 * stored is shown immediately and the network request goes out regardless, every time. The cache can
 * only ever change how soon the screen has something on it, never what it eventually settles on —
 * which is the property worth having, given that the app's other unread cache earned its distrust by
 * letting a stale write decide what was displayed.
 *
 * Only the signed-in user's own data is stored, since another profile's list is a screen you visit
 * once. Gzipped, which takes a few megabytes down to a few hundred kilobytes for ~10ms on read.
 * Lives in `cacheDir`, because every byte is re-obtainable from its service and losing it costs one
 * ordinary load.
 *
 * The same store backs the home screen, whose own load divides the same way — a combined AniList
 * query and a set of MangaUpdates list requests, both of them round trips whose answers were already
 * fetched the last time the app was opened.
 */
object MediaListCache {

    private const val DIR_NAME = "anilist-lists"

    private var dir: File? = null

    /** Call once at startup; no disk access happens until a list screen actually asks. */
    fun init(context: Context) {
        dir = File(context.cacheDir, DIR_NAME)
    }

    /** The user's own lists for one media type. */
    fun listKey(userId: Int, anime: Boolean) = "list-$userId-${if (anime) "anime" else "manga"}"

    /** The home screen's combined AniList query. */
    fun homeKey(userId: Int) = "home-$userId"

    /** The MangaUpdates buckets the home screen draws from. */
    const val MU_HOME_KEY = "mu-home"

    private fun fileFor(key: String): File? {
        val base = dir ?: return null
        return File(base, "$key.json.gz")
    }

    /** The stored response body, or null when there isn't one (or it can't be read). */
    fun read(key: String): String? {
        val file = fileFor(key) ?: return null
        if (!file.exists()) return null
        return try {
            GZIPInputStream(file.inputStream().buffered()).bufferedReader().use { it.readText() }
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            // A half-written or corrupt file is worth exactly one ordinary load, so drop it and
            // carry on rather than letting it fail the screen every time from here on.
            Logger.log("MediaListCache: failed to read ${file.name}: ${e.message}")
            runCatching { file.delete() }
            null
        }
    }

    /**
     * Stores [body] for next time.
     *
     * Written to a temporary file and moved into place, so a process that dies mid-write leaves the
     * previous response intact instead of a truncated one that parses into a half-empty library.
     */
    fun write(key: String, body: String) {
        val file = fileFor(key) ?: return
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            GZIPOutputStream(temp.outputStream().buffered()).bufferedWriter().use { it.write(body) }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            Logger.log("MediaListCache: failed to write ${file.name}: ${e.message}")
        }
    }

    /** Drops one stored response, for signing out of the service that produced it. */
    fun remove(key: String) {
        try {
            fileFor(key)?.delete()
        } catch (e: Exception) {
            Logger.log("MediaListCache: failed to remove $key: ${e.message}")
        }
    }

    /** Drops everything stored — for signing out, where another account's library must not appear. */
    fun clear() {
        try {
            dir?.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Logger.log("MediaListCache: failed to clear: ${e.message}")
        }
    }
}
