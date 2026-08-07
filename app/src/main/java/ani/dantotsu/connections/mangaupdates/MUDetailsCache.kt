package ani.dantotsu.connections.mangaupdates

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * App-wide cache for MangaUpdates series details (cover URL and description).
 *
 * Call [prefetch] once when a list of MU items becomes available.  Each item's details are
 * fetched concurrently in the background; [onUpdated] is invoked on the main thread for every
 * id whose data arrives so the caller can refresh the relevant UI.
 *
 * Bind views synchronously via [get] — returns null until the fetch completes.
 */
object MUDetailsCache {
    data class Detail(
        val coverUrl: String?,
        val description: String?,
        val hasEnglishPublisher: Boolean? = null,
        val type: String? = null,
        val year: Int? = null,
        val genres: Set<String> = emptySet(),
        val categories: Set<String> = emptySet(),
        val completed: Boolean? = null,
        val latestChapter: Long? = null,
        /**
         * When the newest known chapter was released, epoch ms — the `release_date` of the first
         * entry in the series' release list. Only populated for callers that asked for it (see
         * `withReleases`), since it costs a second request per series.
         *
         * This is the series' own release date. The list endpoint's `last_updated` looks like the
         * same thing and is not: it moves when the *user's* list record changes, so ordering by it
         * sorted by when you last touched a series rather than when it last got a chapter.
         */
        val latestReleaseAt: Long? = null,
    )

    /** Concurrent requests allowed across the whole app, to avoid API throttling. */
    private const val MAX_CONCURRENT = 5

    private val cache = ConcurrentHashMap<Long, Detail>()

    /**
     * Who to tell when an in-flight id lands, keyed by that id — and, by existing, the record that
     * a fetch is already running for it.
     *
     * A plain "is it being fetched" set was not enough. A second caller asking for an id someone
     * else had already started was told nothing at all, so it never learned when the data arrived:
     * two lists showing the same series (the unread row and the full-screen view opened over it)
     * left the second one blank until something happened to rebind it.
     */
    private val waiting = HashMap<Long, MutableList<(Long) -> Unit>>()
    private val lock = Any()

    /**
     * Ids whose release date has been resolved, tracked apart from [cache] because it is optional:
     * an entry fetched for its cover alone is complete for that caller and must not stop a later
     * caller that does need the date from going and getting it.
     */
    private val releasesResolved: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * Fetches run here rather than on the caller's scope. A caller is a screen, and screens go
     * away mid-flight — with the fetch attached to one, cancelling it between "mark this id as
     * being fetched" and the body that clears that mark stranded the id as permanently
     * in-flight, so nothing would ever fetch it again for the life of the process.
     */
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(MAX_CONCURRENT)

    fun get(id: Long): Detail? = cache[id]

    /**
     * Whether [id]'s release date has been looked up — true even when the answer was "no date", so
     * a caller waiting on one isn't waiting forever for a series that has none.
     */
    fun hasRelease(id: Long): Boolean = id in releasesResolved

    /**
     * Kick off background fetches for all [ids] not already in the cache.
     * Safe to call multiple times with overlapping id sets — duplicate fetches are suppressed,
     * but every caller is still notified.
     * [onUpdated] is called on the main thread after each id's data is stored, on [scope], so a
     * caller that has gone away stops hearing about it.
     */
    fun prefetch(
        scope: CoroutineScope,
        ids: Collection<Long>,
        withReleases: Boolean = false,
        onUpdated: ((id: Long) -> Unit)? = null
    ) {
        ids.forEach { id ->
            val needDetail = !cache.containsKey(id)
            val needRelease = withReleases && id !in releasesResolved
            if (!needDetail && !needRelease) return@forEach
            val deliver: ((Long) -> Unit)? = onUpdated?.let {
                { finished -> scope.launch(Dispatchers.Main) { it(finished) } }
            }
            val startFetch = synchronized(lock) {
                val queue = waiting[id]
                if (queue == null) {
                    waiting[id] = mutableListOf<(Long) -> Unit>().apply { deliver?.let(::add) }
                    true
                } else {
                    deliver?.let(queue::add)
                    false
                }
            }
            if (startFetch) fetchScope.launch { fetch(id, needDetail, needRelease) }
        }
    }

    private suspend fun fetch(id: Long, wantDetail: Boolean, wantRelease: Boolean) {
        try {
            gate.withPermit {
                if (wantDetail) {
                    val record = MangaUpdates.getSeriesDetails(id)
                    cache[id] = Detail(
                        coverUrl = record?.image?.url?.run { original ?: thumb },
                        description = record?.description,
                        hasEnglishPublisher = record?.licensed,
                        type = record?.type,
                        year = record?.year?.toIntOrNull(),
                        genres = record?.genres
                            ?.mapNotNull { it.genre?.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?.toSet()
                            ?: emptySet(),
                        categories = record?.categories
                            ?.mapNotNull { it.category?.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?.toSet()
                            ?: emptySet(),
                        completed = record?.completed,
                        latestChapter = record?.latest_chapter,
                        // Carried over so a details refetch doesn't drop a date already resolved.
                        latestReleaseAt = cache[id]?.latestReleaseAt,
                    )
                }
                if (wantRelease) {
                    // The releases are a separate endpoint; the newest one is first in the list.
                    val newest = MangaUpdates.getSeriesGroups(id)?.releaseList?.firstOrNull()
                    val at = newest?.releaseDate?.let(::parseReleaseDate)
                    cache[id] = (cache[id] ?: Detail(null, null)).copy(latestReleaseAt = at)
                }
            }
        } catch (e: Exception) {
            // Fail silently; nothing is cached, so a later prefetch of this id tries again.
            ani.dantotsu.util.Logger.log("MUDetailsCache: Failed to fetch details for $id: ${e.message}")
        } finally {
            // Resolved even if the request failed or the series has no dated release. Callers hold
            // the list back until every entry is answered, so an id that could come back "not yet"
            // for ever would keep the section on its spinner and re-request on every draw. The set
            // is in-memory, so a restart is the retry boundary.
            if (wantRelease) releasesResolved += id
            // Always, and always before notifying: a waiter reads the cache the moment it hears,
            // and one left in the map would block every future fetch of this id.
            val callbacks = synchronized(lock) { waiting.remove(id) }.orEmpty()
            callbacks.forEach { it(id) }
        }
    }

    /** `release_date` is a plain `yyyy-MM-dd`; anything else is treated as no date at all. */
    private fun parseReleaseDate(raw: String): Long? = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(raw)?.time
    }.getOrNull()
}
