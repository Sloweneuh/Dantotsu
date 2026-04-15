package ani.dantotsu.connections.mangaupdates

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import java.util.Collections
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
    )

    private val cache = ConcurrentHashMap<Long, Detail>()
    private val fetching: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

    fun get(id: Long): Detail? = cache[id]

    /**
     * Kick off background fetches for all [ids] not already in the cache.
     * Safe to call multiple times with overlapping id sets — duplicate fetches are suppressed.
     * [onUpdated] is called on the main thread after each id's data is stored.
     * 
     * Uses rate-limited concurrency (max 5 concurrent requests) to prevent API throttling.
     */
    fun prefetch(
        scope: CoroutineScope,
        ids: Collection<Long>,
        onUpdated: ((id: Long) -> Unit)? = null
    ) {
        val missing = ids.filter { !cache.containsKey(it) && fetching.add(it) }
        if (missing.isEmpty()) return
        
        // Rate-limit concurrent requests to avoid API throttling (max 5 concurrent)
        val maxConcurrent = 5
        val semaphore = Semaphore(maxConcurrent)
        
        missing.forEach { id ->
            scope.launch(Dispatchers.IO) {
                try {
                    // Acquire semaphore slot (limits concurrent requests)
                    semaphore.acquire()
                    try {
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
                        )
                    } finally {
                        semaphore.release()
                    }
                } catch (e: Exception) {
                    // Fail silently, log and store null marker to prevent re-fetching
                    ani.dantotsu.util.Logger.log("MUDetailsCache: Failed to fetch details for $id: ${e.message}")
                } finally {
                    fetching.remove(id)
                }
                if (onUpdated != null) withContext(Dispatchers.Main) { onUpdated(id) }
            }
        }
    }
}
