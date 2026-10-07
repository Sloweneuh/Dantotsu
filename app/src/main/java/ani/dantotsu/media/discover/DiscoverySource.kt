package ani.dantotsu.media.discover

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * Where a discovery queue comes from and what its verdicts do. The queue rules (size, re-rolls,
 * skips carried over, local exclusions) live in [DiscoveryQueue]/[DiscoverActivity]/
 * [DiscoverQueueSheet]; a source supplies the picks, the page each pick is shown on, and the
 * tracker writes behind "Add".
 */
interface DiscoverySource {
    /** Stable id, used in intents and as the storage key. */
    val id: String
    val store: DiscoveryQueueStore

    /** The signed-in account the queue belongs to, or null when the source can't be used. */
    suspend fun accountId(): String?

    /** The sheet header's icon. */
    @get:DrawableRes val icon: Int
    /** "Plan to read" / "Plan to watch". */
    @get:StringRes val addLabel: Int
    @get:StringRes val needsLoginMessage: Int
    @get:StringRes val helpBody: Int
    @get:StringRes val failedBody: Int

    /** Builds and saves a new queue, narrowed by [filters] (see [supportsFilters]) when given. */
    suspend fun build(
        context: Context,
        jitter: Boolean,
        carrySkipped: List<Long>,
        filters: String? = null,
    ): DiscoveryBuildResult

    /** Whether queues can be narrowed with filters before they are built (MangaBaka only). */
    val supportsFilters: Boolean get() = false

    /** [filters] as chip labels for the screens around the queue; empty when unfiltered. */
    fun describeFilters(filters: String?): List<FilterLabel> = emptyList()

    /**
     * [filters] without the one [describeFilters] lists at [index]; null once nothing is left.
     * What a filter chip's close icon does.
     */
    fun removeFilter(filters: String, index: Int): String? = filters

    /** One filter value, bare (no "Format:" prefix); [excluded] is drawn as an exclusion. */
    data class FilterLabel(val text: String, val excluded: Boolean = false)

    /** The first page to open in queue mode, showing [item]. */
    fun queuePageIntent(context: Context, item: DiscoveryQueue.Item): Intent

    /** A seed series, opened as a plain page on top of the queue. */
    fun seedIntent(context: Context, seedId: Long): Intent

    /** "You read X" / "You watched X": the seed sentence for a seed's list [state]. */
    @StringRes fun seedVerb(state: String?): Int

    /** Puts [item] on the user's planning list. */
    suspend fun add(item: DiscoveryQueue.Item): DiscoverListAdd.Result

    /** Whether [item] is now on one of the user's lists — decided away from the queue's buttons. */
    suspend fun isOnList(item: DiscoveryQueue.Item): Boolean

    /** Covers for seeds whose source didn't send one, keyed by seed id. */
    suspend fun seedCovers(ids: List<Long>): Map<Long, String> = emptyMap()

    fun load(accountId: String): DiscoveryQueue? = store.load(accountId)

    companion object {
        const val EXTRA_SOURCE = "discover_source"

        fun byId(id: String?): DiscoverySource? = when (id) {
            MangaBakaDiscovery.id -> MangaBakaDiscovery
            AniListAnimeDiscovery.id -> AniListAnimeDiscovery
            else -> null
        }
    }
}

sealed interface DiscoveryBuildResult {
    data class Ready(val queue: DiscoveryQueue) : DiscoveryBuildResult
    /** Too little history to recommend from; [body] says how much there is and how much is needed. */
    data class ColdStart(@StringRes val title: Int, val body: String) : DiscoveryBuildResult
    /** The profile exists but is still being computed (MangaBaka only). */
    data object ProfileStale : DiscoveryBuildResult
    data object Empty : DiscoveryBuildResult
    data object Failed : DiscoveryBuildResult
}
