package ani.dantotsu.media.discover

import android.content.Context
import android.content.Intent
import ani.dantotsu.Mapper
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.MangaBakaSearchResults
import ani.dantotsu.connections.TrackerSessions
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.connections.mangabaka.MangaBakaSync
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.media.MangaBakaMediaActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Manga queues from MangaBaka's For-You recommendations — the same feed, size and re-roll rules as
 * the site's queue: [QUEUE_SIZE] picks, page 1 for a first queue, a random page of the first
 * [JITTER_PAGES] for later ones, and the previous queue's skips sent as exclusions. A queue can be
 * narrowed with the search filter set first, as the site's "Start filtered queue". Picks are shown
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

    override val supportsFilters = true

    override suspend fun build(
        context: Context,
        jitter: Boolean,
        carrySkipped: List<Long>,
        filters: String?,
    ): DiscoveryBuildResult {
        val userId = accountId() ?: return DiscoveryBuildResult.Failed
        val excluded = store.excludedIds()
        // Skips go last so they survive the API's 100-id cap: they are what would otherwise come
        // straight back, while the oldest hides have usually fallen out of the top pages anyway.
        val exclude = (excluded - carrySkipped.toSet()) + carrySkipped
        val page = if (jitter) (1..JITTER_PAGES).random() else 1
        val filterState = filters?.let(::decodeFilters)
        suspend fun fetch(page: Int) = MangaBakaSync.getRecommendations(
            page = page,
            limit = QUEUE_SIZE,
            excludeIds = exclude,
            allowAdult = PrefManager.getVal(PrefName.AdultOnly),
            filters = filterState,
        )
        var response = fetch(page) ?: return DiscoveryBuildResult.Failed
        // A narrow filter can run out before the re-roll's page; its first page may still have picks.
        if (page > 1 && response.results.isEmpty() && !response.coldStart && !response.profileStale) {
            response = fetch(1) ?: return DiscoveryBuildResult.Failed
        }
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
        val queue = DiscoveryQueue(System.currentTimeMillis(), userId, items, filters)
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

    // ---- Filters ----

    /**
     * The search filter set, which is also what the recommendations route takes — the site's
     * filtered queue is the recommendations page's filters spread into the same call.
     */
    @Serializable
    private data class Filters(
        val genres: List<String>? = null,
        val excludedGenres: List<String>? = null,
        val tags: List<String>? = null,
        val excludedTags: List<String>? = null,
        val types: List<String>? = null,
        val excludedTypes: List<String>? = null,
        val statuses: List<String>? = null,
        val excludedStatuses: List<String>? = null,
        val contentRatings: List<String>? = null,
        val excludedContentRatings: List<String>? = null,
        val hasAnime: Boolean? = null,
        val fromYear: Int? = null,
        val toYear: Int? = null,
        val sort: String? = null,
    )

    /** The filter sheet's state, encoded for the queue; null when nothing is set. */
    fun encodeFilters(state: MangaBakaSearchResults): String? {
        if (state.toChipList().isEmpty() && state.sort.isNullOrBlank()) return null
        return Mapper.json.encodeToString(
            Filters(
                state.genres, state.excludedGenres, state.tags, state.excludedTags,
                state.types, state.excludedTypes, state.statuses, state.excludedStatuses,
                state.contentRatings, state.excludedContentRatings, state.hasAnime,
                state.fromYear, state.toYear, state.sort,
            )
        )
    }

    /** A filter sheet state holding [filters] (empty for null or unreadable ones). */
    fun decodeFilters(filters: String?): MangaBakaSearchResults {
        val f = filters?.let { runCatching { Mapper.json.decodeFromString<Filters>(it) }.getOrNull() }
        return MangaBakaSearchResults(
            search = null, results = mutableListOf(), hasNextPage = false,
            genres = f?.genres?.toMutableList(), excludedGenres = f?.excludedGenres?.toMutableList(),
            tags = f?.tags?.toMutableList(), excludedTags = f?.excludedTags?.toMutableList(),
            types = f?.types?.toMutableList(), excludedTypes = f?.excludedTypes?.toMutableList(),
            statuses = f?.statuses?.toMutableList(), excludedStatuses = f?.excludedStatuses?.toMutableList(),
            contentRatings = f?.contentRatings?.toMutableList(),
            excludedContentRatings = f?.excludedContentRatings?.toMutableList(),
            hasAnime = f?.hasAnime,
            fromYear = f?.fromYear, toYear = f?.toYear, sort = f?.sort,
        )
    }

    override fun describeFilters(filters: String?): List<DiscoverySource.FilterLabel> =
        filters?.let { labelled(decodeFilters(it)).map { (label, _) -> label } }.orEmpty()

    override fun removeFilter(filters: String, index: Int): String? {
        val f = decodeFilters(filters)
        labelled(f).getOrNull(index)?.second?.invoke()
        return encodeFilters(f)
    }

    /** Each filter set in [f] as its chip label, beside what takes it back out of [f]. */
    private fun labelled(f: MangaBakaSearchResults): List<Pair<DiscoverySource.FilterLabel, () -> Unit>> {
        fun labels(included: MutableList<String>?, excluded: MutableList<String>?, label: (String) -> String) =
            included.orEmpty().map { v -> DiscoverySource.FilterLabel(label(v)) to { included!!.remove(v); Unit } } +
                excluded.orEmpty().map { v ->
                    DiscoverySource.FilterLabel(label(v), excluded = true) to { excluded!!.remove(v); Unit }
                }
        val years = if (f.fromYear != null || f.toYear != null) listOf(
            DiscoverySource.FilterLabel("${f.fromYear ?: "…"}–${f.toYear ?: "…"}") to { f.fromYear = null; f.toYear = null }
        ) else emptyList()
        return labels(f.types, f.excludedTypes, f::labelForType) +
            labels(f.statuses, f.excludedStatuses, f::labelForStatus) +
            labels(f.contentRatings, f.excludedContentRatings, f::titleCase) +
            listOfNotNull(f.hasAnime?.let { DiscoverySource.FilterLabel(f.hasAnimeLabel()) to { f.hasAnime = null } }) +
            labels(f.genres, f.excludedGenres, MangaBakaApi::resolveGenreName) +
            labels(f.tags, f.excludedTags) { it } +
            years
    }

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
