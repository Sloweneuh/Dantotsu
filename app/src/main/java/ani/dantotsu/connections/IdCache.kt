package ani.dantotsu.connections

import android.content.Context
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * The trackers' id-resolution caches: which Kitsu media an AniList id maps to, how many episodes
 * Simkl thinks a show has, which MangaBaka series a MangaUpdates id belongs to, and so on for every
 * service the app cross-references ids between.
 *
 * All of it used to live in [ani.dantotsu.settings.saving.PrefManager]'s Irrelevant
 * SharedPreferences, one key per media per service, and that was the wrong home for it three times
 * over:
 *
 *  - **Nothing bounded it.** One key per media, never expiring, so the file only ever grew. On a
 *    device that had compared its lists a few times it reached tens of thousands of entries.
 *  - **SharedPreferences pays for its whole map on every write.** The map is resident for the life
 *    of the process, `apply()` re-serialises the entire file as XML, and — whenever a write is
 *    already in flight — first clones the entire map so the writer keeps the copy it owns. A list
 *    comparison seeding a library therefore queued one multi-MB clone per entry, and the backlog
 *    of them is what exhausted the heap in the sync-compare screen.
 *  - **Everything that walks the preferences pays too.** Backups package every key
 *    ([ani.dantotsu.settings.saving.internal.PreferencePackager]), and cloud progress sync scans
 *    the lot looking for per-media keys ([ani.dantotsu.connections.sync.ProgressSync]). Both were
 *    carrying tens of thousands of entries of pure derived data.
 *
 * Derived is the important word: every entry here is re-obtainable from the service that produced
 * it, so losing one costs a single lookup and nothing else. That makes a bounded cache the right
 * shape — hold the mappings worth holding, drop the tail — and it is why this is kept in `cacheDir`
 * and why a flush that fails is logged rather than raised.
 *
 * Bounded is also what makes the file affordable: the whole thing is rewritten on flush, but with a
 * ceiling on entries that is a sub-megabyte sequential write at a batch boundary, against one
 * whole-file XML rewrite *per key* before.
 *
 * Values are stored as text and read back through [getInt] / [getLong]; the callers know their own
 * types. Reads and writes are safe from any thread. [load] touches the disk on first use, so keep
 * first contact off the main thread — every caller is a suspending tracker lookup already.
 */
object IdCache {

    /**
     * Entry ceiling. Each entry is a short key and a numeric value, so this is a file well under a
     * megabyte and a few MB of heap — room for a very large library across every tracker at once.
     * Eviction is by least *recently used*, so an actively compared library stays resident and the
     * long tail of one-off lookups is what goes.
     */
    private const val MAX_ENTRIES = 20_000

    /** Writes buffered before an automatic flush; only bounds how much an abrupt kill can lose. */
    private const val FLUSH_EVERY = 256

    private const val FILE_NAME = "tracker-ids.tsv"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()
    private var file: File? = null
    private var loaded = false
    private var pending = 0

    private val entries = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > MAX_ENTRIES
    }

    /** Call once at startup. Nothing is read from disk until the first lookup. */
    fun init(context: Context) {
        synchronized(lock) { file = File(context.cacheDir, FILE_NAME) }
    }

    operator fun get(key: String): String? = synchronized(lock) {
        load()
        entries[key]
    }

    fun getInt(key: String): Int? = get(key)?.toIntOrNull()

    fun getLong(key: String): Long? = get(key)?.toLongOrNull()

    fun put(key: String, value: Any) = putAll(mapOf(key to value))

    /** Stores every entry, flushing once if enough has built up since the last write. */
    fun putAll(values: Map<String, Any>) {
        if (values.isEmpty()) return
        synchronized(lock) {
            load()
            var stored = 0
            values.forEach { (key, value) ->
                val text = value.toString()
                // The file is one `key<tab>value` line per entry, so anything carrying a tab or a
                // newline would not survive the round trip. No caller produces such a key — they
                // are all a prefix, a service name and a numeric id — but a value that silently
                // came back as a different one on the next run would be worse than a miss.
                if (key.isEmpty() || key.hasSeparator() || text.hasSeparator()) return@forEach
                entries[key] = text
                stored++
            }
            pending += stored
            if (pending >= FLUSH_EVERY) writeLocked()
        }
    }

    /** Writes anything buffered. Cheap, and safe to call at any batch boundary. */
    fun flush() = synchronized(lock) { if (pending > 0) writeLocked() }

    /**
     * [flush] off the calling thread, for callers that are on the main one — the trim-memory
     * callback that catches the app being backgrounded, in particular. Nothing waits on it, and
     * losing the write to a kill that arrives first costs a re-lookup like any other miss.
     */
    fun flushAsync() {
        scope.launch { flush() }
    }

    private fun String.hasSeparator() = any { it == '\t' || it == '\n' || it == '\r' }

    private fun load() {
        if (loaded) return
        loaded = true
        val source = file ?: return
        if (!source.exists()) return
        runCatching {
            source.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) entries[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }.onFailure { Logger.log("IdCache: could not read $source — ${it.message}") }
    }

    private fun writeLocked() {
        pending = 0
        val target = file ?: return
        runCatching {
            // Through a temporary file, so a kill part-way through leaves the previous cache intact
            // rather than a half-written one that loads as a set of wrong mappings.
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.bufferedWriter().use { out ->
                entries.forEach { (key, value) ->
                    out.append(key).append('\t').append(value).append('\n')
                }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }.onFailure { Logger.log("IdCache: could not write $target — ${it.message}") }
    }
}
