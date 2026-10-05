package ani.dantotsu.media.discover

import ani.dantotsu.Mapper
import ani.dantotsu.settings.saving.PrefManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * A Steam-style discovery queue: a short batch of personalised picks the user steps through one at a
 * time, giving each a verdict ([Action]) before moving on.
 *
 * Modelled on mangabaka.org's `/my/discover`, which builds the queue entirely client-side from one
 * page of For-You recommendations — there is no queue on the server. Kept free of MangaBaka types
 * (the source fills in display fields up front) so an anime source can reuse the same screen.
 */
@Serializable
data class DiscoveryQueue(
    val createdAt: Long,
    /** The account the queue was built for; a queue for another account is discarded on load. */
    val userId: String,
    val items: List<Item>,
    /**
     * The filters the queue was built with, as the source encodes them (the site keeps the
     * recommendations page's query string); null for an unfiltered queue. "Start another queue"
     * reuses them.
     */
    val filters: String? = null,
) {
    @Serializable
    data class Item(
        val id: Long,
        val title: String,
        val cover: String? = null,
        val reason: Reason? = null,
        val action: Action? = null,
    )

    /**
     * Why the item was picked, in the shape the site's queue bar shows it: the library series it
     * was matched from, and the taste-profile tags that raised or lowered its score.
     */
    @Serializable
    data class Reason(
        val hiddenGem: Boolean = false,
        val seeds: List<Seed> = emptyList(),
        val positiveTags: List<Tag> = emptyList(),
        val negativeTags: List<Tag> = emptyList(),
    )

    /** [state] is the seed's library state (`completed`, `reading`, …), which picks the verb. */
    @Serializable
    data class Seed(val id: Long, val title: String, val state: String? = null, val cover: String? = null)

    /** [weight] is how central the tag is to the series: `core`, `defining`, `recurrent`, … */
    @Serializable
    data class Tag(val name: String, val weight: String? = null) {
        /** Core and defining tags are what the series is about; the rest are side themes. */
        val isDefining: Boolean get() = weight == "core" || weight == "defining"
    }

    enum class Action { ADDED, HIDDEN, SKIPPED }

    /** The next unanswered item after [afterId], wrapping round; null once every item has a verdict. */
    fun next(afterId: Long? = null): Item? {
        val start = afterId?.let { id -> items.indexOfFirst { it.id == id } + 1 } ?: 0
        return (items.drop(start) + items.take(start)).firstOrNull { it.action == null }
    }

    fun position(id: Long): Int = items.indexOfFirst { it.id == id } + 1

    val isComplete: Boolean get() = items.isNotEmpty() && items.all { it.action != null }

    fun count(action: Action): Int = items.count { it.action == action }

    fun withAction(id: Long, action: Action?): DiscoveryQueue =
        copy(items = items.map { if (it.id == id) it.copy(action = action) else it })

    /** Skipped picks are carried into the next queue's exclusions so they don't come straight back. */
    fun skippedIds(): List<Long> = items.filter { it.action == Action.SKIPPED }.map { it.id }
}

/**
 * Persists one source's queue and its long-lived exclusions. Unlike the site's sessionStorage copy,
 * the queue survives the app being killed mid-way — on Android that is the normal case, not the edge.
 */
class DiscoveryQueueStore(private val source: String) {
    private val queueKey get() = "discover_queue_$source"
    private val excludedKey get() = "discover_excluded_$source"

    fun load(userId: String): DiscoveryQueue? {
        val raw = PrefManager.getNullableCustomVal(queueKey, null, String::class.java) ?: return null
        val queue = runCatching { Mapper.json.decodeFromString<DiscoveryQueue>(raw) }.getOrNull()
        if (queue == null || queue.userId != userId) {
            PrefManager.removeCustomVal(queueKey)
            return null
        }
        return queue
    }

    fun save(queue: DiscoveryQueue) = PrefManager.setCustomVal(queueKey, Mapper.json.encodeToString(queue))

    fun clear() = PrefManager.removeCustomVal(queueKey)

    /**
     * Series hidden ("not interested") or added from a queue, oldest first. Added ones are kept too:
     * with MangaBaka list sync switched off, an AniList add never reaches the MangaBaka library, and
     * the recommender would offer the same series again next time.
     */
    fun excludedIds(): List<Long> {
        val raw = PrefManager.getNullableCustomVal(excludedKey, null, String::class.java) ?: return emptyList()
        return runCatching { Mapper.json.decodeFromString<List<Long>>(raw) }.getOrDefault(emptyList())
    }

    fun exclude(id: Long) {
        val updated = (excludedIds() - id + id).takeLast(MAX_EXCLUDED)
        PrefManager.setCustomVal(excludedKey, Mapper.json.encodeToString(updated))
    }

    fun unexclude(id: Long) {
        PrefManager.setCustomVal(excludedKey, Mapper.json.encodeToString(excludedIds() - id))
    }

    private companion object {
        /** Bounds the pref; only the newest [MangaBakaSync.RECOMMENDATION_EXCLUDE_LIMIT] reach the API anyway. */
        const val MAX_EXCLUDED = 2000
    }
}
