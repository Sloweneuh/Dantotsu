package ani.dantotsu.media.discover

import android.content.Context
import android.content.Intent
import ani.dantotsu.R
import ani.dantotsu.connections.TrackerSessions
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.connections.mangabaka.MangaBakaSync
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.media.MangaBakaMediaActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * Manga queues from MangaBaka's For-You recommendations — the same feed, size and re-roll rules as
 * the site's queue: [QUEUE_SIZE] picks, page 1 for a first queue, a random page of the first
 * [JITTER_PAGES] for later ones, and the previous queue's skips sent as exclusions. Picks are shown
 * on [MangaBakaMediaActivity], which reloads in place between them.
 */
object MangaBakaDiscovery : DiscoverySource {
    override val id = "mangabaka"
    private const val QUEUE_SIZE = 12
    private const val JITTER_PAGES = 3

    override val store = DiscoveryQueueStore(id)
    override val icon = R.drawable.ic_manga_discover_24
    override val addLabel = R.string.discover_add
    override val needsLoginMessage = R.string.discover_needs_login
    override val helpBody = R.string.discover_reason_help_body
    override val failedBody = R.string.discover_failed_body

    override suspend fun accountId(): String? {
        TrackerSessions.await()
        return MangaBaka.userid?.takeIf { MangaBaka.token != null }
    }

    override suspend fun build(context: Context, jitter: Boolean, carrySkipped: List<Long>): DiscoveryBuildResult {
        val userId = accountId() ?: return DiscoveryBuildResult.Failed
        val excluded = store.excludedIds()
        // Skips go last so they survive the API's 100-id cap: they are what would otherwise come
        // straight back, while the oldest hides have usually fallen out of the top pages anyway.
        val exclude = (excluded - carrySkipped.toSet()) + carrySkipped
        val page = if (jitter) (1..JITTER_PAGES).random() else 1
        val response = MangaBakaSync.getRecommendations(
            page = page,
            limit = QUEUE_SIZE,
            excludeIds = exclude,
            allowAdult = PrefManager.getVal(PrefName.AdultOnly),
        ) ?: return DiscoveryBuildResult.Failed
        if (response.coldStart) {
            val count = MangaBakaSync.getRecommendationStatus()?.libraryCount ?: 0
            return DiscoveryBuildResult.ColdStart(
                R.string.discover_cold_start_title,
                context.getString(R.string.discover_cold_start_body, count),
            )
        }
        if (response.profileStale) return DiscoveryBuildResult.ProfileStale
        // Hides older than the cap weren't sent, so they are filtered out here instead.
        val excludedSet = excluded.toHashSet()
        val items = response.results
            .filter { it.id !in excludedSet }
            .distinctBy { it.id }
            .map { it.toItem(context) }
        if (items.isEmpty()) return DiscoveryBuildResult.Empty
        val queue = DiscoveryQueue(System.currentTimeMillis(), userId, items)
        store.save(queue)
        return DiscoveryBuildResult.Ready(queue)
    }

    override fun queuePageIntent(context: Context, item: DiscoveryQueue.Item): Intent =
        Intent(context, MangaBakaMediaActivity::class.java)
            .putExtra(MangaBakaMediaActivity.EXTRA_DISCOVER_QUEUE, true)

    override fun seedIntent(context: Context, seedId: Long): Intent =
        Intent(context, MangaBakaMediaActivity::class.java)
            .putExtra(MangaBakaMediaActivity.EXTRA_SERIES_ID, seedId)

    override fun seedVerb(state: String?) = when (state) {
        "completed" -> R.string.discover_seed_completed
        "reading", "rereading" -> R.string.discover_seed_reading
        else -> R.string.discover_seed_similar
    }

    override suspend fun add(item: DiscoveryQueue.Item) = DiscoverListAdd.addManga(item.id, item.title)

    /**
     * The same home-tracker order "Add" uses: the AniList entry when the series links to one,
     * otherwise its MangaUpdates entry.
     */
    override suspend fun isOnList(item: DiscoveryQueue.Item): Boolean {
        TrackerSessions.await()
        val source = MangaBakaApi.getSeries(item.id)?.source ?: return false
        val anilistId = source.anilist?.id?.takeIf { it > 0 }
        if (Anilist.token != null && anilistId != null) {
            return Anilist.query.getMedia(anilistId)?.userStatus != null
        }
        val muId = source.mangaUpdates?.toMuSeriesId()?.takeIf { it > 0 } ?: return false
        return MangaUpdates.isOnList(muId) == true
    }

    /** The recommendation route's seeds carry titles only; one batch lookup fills in the covers. */
    override suspend fun seedCovers(ids: List<Long>): Map<Long, String> =
        MangaBakaApi.getSeriesBatch(ids).mapNotNull { (id, series) -> series.cover?.thumbUrl()?.let { id to it } }.toMap()

    private fun MangaBakaSync.Recommendation.toItem(context: Context) = DiscoveryQueue.Item(
        id = id,
        title = displayTitle() ?: context.getString(R.string.unknown),
        cover = cover?.x350?.x2 ?: cover?.thumbUrl(),
        reason = reason?.let { r ->
            DiscoveryQueue.Reason(
                hiddenGem = r.reasonType == "hidden_gem",
                seeds = r.reasonSeeds.mapNotNull { seed ->
                    val seedId = seed.id ?: return@mapNotNull null
                    val title = seed.displayTitle() ?: return@mapNotNull null
                    DiscoveryQueue.Seed(seedId, title, seed.state)
                },
                positiveTags = r.topTags.toTags(),
                negativeTags = r.suppressedTags.toTags(),
            )
        },
    )

    private fun List<MangaBakaSync.ReasonTag>.toTags() =
        mapNotNull { tag -> tag.name?.takeIf(String::isNotBlank)?.let { DiscoveryQueue.Tag(it, tag.weight) } }
}
