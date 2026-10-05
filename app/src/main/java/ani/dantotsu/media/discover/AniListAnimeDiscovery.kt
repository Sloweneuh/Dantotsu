package ani.dantotsu.media.discover

import android.content.Context
import android.content.Intent
import ani.dantotsu.Mapper
import ani.dantotsu.R
import ani.dantotsu.connections.TrackerSessions
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ln

/**
 * Anime queues built from AniList alone, which has no personalised feed of its own:
 *
 *  1. **Seeds** — the user's best anime (completed, watching, rewatching or paused, ranked by their
 *     score), up to [MAX_SEEDS].
 *  2. **Candidates** — each seed's user recommendations ("if you liked X, try Y"), fetched
 *     [SEEDS_PER_QUERY] seeds to a request.
 *  3. **Taste** — the user's tag statistics: tags they score above their mean are liked, well below
 *     are disliked.
 *
 * A candidate scores the votes it got from each seed, weighted by how much the user liked that
 * seed, then nudged up or down by its liked/disliked tags (weighted by tag rank). No more than
 * [PER_SEED_CAP] picks share a strongest seed, so one show can't fill the queue.
 *
 * Picks open on the regular AniList page ([MediaDetailsActivity]) in queue mode.
 */
object AniListAnimeDiscovery : DiscoverySource {
    override val id = "anilist_anime"
    private const val QUEUE_SIZE = 12
    private const val MAX_SEEDS = 30
    private const val SEEDS_PER_QUERY = 10
    private const val RECS_PER_SEED = 10
    private const val MIN_SEEDS = 5
    private const val PER_SEED_CAP = 3
    /** Later queues draw from this many of the best candidates so back-to-back queues differ. */
    private const val JITTER_POOL = 40
    private const val TAG_MIN_COUNT = 3
    private const val TAG_MIN_RANK = 40

    override val store = DiscoveryQueueStore(id)
    override val icon = R.drawable.ic_anime_discover_24
    override val addLabel = R.string.discover_add_watch
    override val needsLoginMessage = R.string.discover_anime_needs_login
    override val helpBody = R.string.discover_anime_reason_help_body
    override val failedBody = R.string.discover_anime_failed_body

    override suspend fun accountId(): String? {
        TrackerSessions.await()
        return Anilist.userid?.takeIf { Anilist.token != null }?.toString()
    }

    override suspend fun build(context: Context, jitter: Boolean, carrySkipped: List<Long>, filters: String?): DiscoveryBuildResult {
        val userId = accountId() ?: return DiscoveryBuildResult.Failed
        val profile = fetchProfile(userId.toInt()) ?: return DiscoveryBuildResult.Failed
        val stats = profile.viewer?.statistics?.anime
        val mean = stats?.meanScore?.takeIf { it > 0 } ?: 70.0

        val entries = profile.list?.lists.orEmpty().flatMap { it.entries.orEmpty() }.distinctBy { it.mediaId }
        val seeds = entries
            .filter { e -> e.score.orZero() == 0.0 || e.score.orZero() >= mean - 5 }
            .filter { e -> e.score.orZero() > 0 || e.status != "PAUSED" }
            .sortedByDescending { e -> e.score.orZero().takeIf { it > 0 } ?: (mean - 1) }
            .take(MAX_SEEDS)
        if (seeds.size < MIN_SEEDS) {
            return DiscoveryBuildResult.ColdStart(
                R.string.discover_anime_cold_start_title,
                context.getString(R.string.discover_anime_cold_start_body, seeds.size, MIN_SEEDS),
            )
        }

        val tagStats = stats?.tags.orEmpty().filter { (it.count ?: 0) >= TAG_MIN_COUNT }
        val liked = tagStats.filter { (it.meanScore ?: 0.0) >= mean + 3 }.mapNotNull { it.tag?.name }.toHashSet()
        val disliked = tagStats.filter { (it.meanScore ?: 101.0) <= mean - 7 }.mapNotNull { it.tag?.name }.toHashSet()

        val seedRecs = fetchRecommendations(seeds.map { it.mediaId }) ?: return DiscoveryBuildResult.Failed
        val allowAdult = PrefManager.getVal<Boolean>(PrefName.AdultOnly)
        val excluded = (store.excludedIds() + carrySkipped).toHashSet()
        val seedIds = entries.map { it.mediaId.toLong() }.toHashSet()

        val candidates = HashMap<Int, Candidate>()
        seeds.forEach { seed ->
            val score = seed.score.orZero()
            val weight = if (score > 0) (score / mean).coerceIn(0.5, 1.6) else 0.85
            seedRecs[seed.mediaId].orEmpty().forEach rec@{ node ->
                val media = node.mediaRecommendation ?: return@rec
                val rating = node.rating ?: 0
                if (rating <= 0 || media.type != "ANIME") return@rec
                if (media.mediaListEntry != null || media.id.toLong() in seedIds || media.id.toLong() in excluded) return@rec
                if (media.isAdult == true && !allowAdult) return@rec
                if (media.format == "MUSIC" || media.status == "NOT_YET_RELEASED") return@rec
                candidates.getOrPut(media.id) { Candidate(media) }.contributions +=
                    seed to weight * ln(1.0 + rating)
            }
        }
        if (candidates.isEmpty()) return DiscoveryBuildResult.Empty

        candidates.values.forEach { c ->
            val tags = c.media.tags.orEmpty()
                .filter { it.isGeneralSpoiler != true && it.isMediaSpoiler != true && (it.rank ?: 0) >= TAG_MIN_RANK }
            c.positive = tags.filter { it.name in liked }.sortedByDescending { it.rank }
            c.negative = tags.filter { it.name in disliked }.sortedByDescending { it.rank }
            val factor = 1.0 + 0.08 * c.positive.sumOf { (it.rank ?: 0) / 100.0 } -
                0.12 * c.negative.sumOf { (it.rank ?: 0) / 100.0 }
            c.total = c.contributions.sumOf { it.second } * factor.coerceIn(0.5, 1.6)
        }

        // Best first, at most PER_SEED_CAP per strongest seed.
        val perSeed = HashMap<Int, Int>()
        val ranked = candidates.values.sortedByDescending { it.total }.filter { c ->
            val top = c.contributions.maxBy { it.second }.first.mediaId
            val n = perSeed.getOrDefault(top, 0)
            if (n >= PER_SEED_CAP) false else { perSeed[top] = n + 1; true }
        }
        val picks = if (jitter) weightedSample(ranked.take(JITTER_POOL), QUEUE_SIZE) else ranked.take(QUEUE_SIZE)
        if (picks.isEmpty()) return DiscoveryBuildResult.Empty

        val queue = DiscoveryQueue(System.currentTimeMillis(), userId, picks.map { it.toItem(context) })
        store.save(queue)
        return DiscoveryBuildResult.Ready(queue)
    }

    /** Draws [count] without replacement, each with odds proportional to its score. */
    private fun weightedSample(pool: List<Candidate>, count: Int): List<Candidate> {
        val remaining = pool.toMutableList()
        val out = ArrayList<Candidate>()
        while (out.size < count && remaining.isNotEmpty()) {
            var roll = Math.random() * remaining.sumOf { it.total }
            val pick = remaining.firstOrNull { roll -= it.total; roll <= 0 } ?: remaining.last()
            remaining.remove(pick)
            out += pick
        }
        return out.sortedByDescending { it.total }
    }

    private fun Candidate.toItem(context: Context) = DiscoveryQueue.Item(
        id = media.id.toLong(),
        title = media.title?.userPreferred ?: context.getString(R.string.unknown),
        cover = media.coverImage?.large,
        reason = DiscoveryQueue.Reason(
            hiddenGem = (media.averageScore ?: 0) >= 75 && (media.popularity ?: Int.MAX_VALUE) < 20_000,
            seeds = contributions.sortedByDescending { it.second }.map { (seed, _) ->
                DiscoveryQueue.Seed(
                    seed.mediaId.toLong(),
                    seed.media?.title?.userPreferred ?: context.getString(R.string.unknown),
                    when (seed.status) {
                        "COMPLETED" -> "completed"
                        "CURRENT", "REPEATING" -> "watching"
                        else -> null
                    },
                    seed.media?.coverImage?.medium,
                )
            },
            positiveTags = positive.take(8).map { it.toTag() },
            negativeTags = negative.take(8).map { it.toTag() },
        ),
    )

    /** AniList's tag rank is how central the tag is; 60%+ reads as "defining", like MangaBaka's weights. */
    private fun MediaTag.toTag() = DiscoveryQueue.Tag(name.orEmpty(), if ((rank ?: 0) >= 60) "defining" else "recurrent")

    override fun queuePageIntent(context: Context, item: DiscoveryQueue.Item): Intent =
        Intent(context, MediaDetailsActivity::class.java)
            .putExtra("mediaId", item.id.toInt())
            .putExtra(DiscoverySource.EXTRA_SOURCE, id)

    override fun seedIntent(context: Context, seedId: Long): Intent =
        Intent(context, MediaDetailsActivity::class.java).putExtra("mediaId", seedId.toInt())

    override fun seedVerb(state: String?) = when (state) {
        "completed" -> R.string.discover_seed_watched
        "watching" -> R.string.discover_seed_watching
        else -> R.string.discover_seed_similar
    }

    override suspend fun add(item: DiscoveryQueue.Item) = DiscoverListAdd.addAnime(item.id.toInt())

    /** Seeds normally arrive with their covers; this covers queues saved before they did. */
    override suspend fun seedCovers(ids: List<Long>): Map<Long, String> {
        val idList = ids.take(50).joinToString(",")
        val raw = rawQuery("{ Page(perPage: 50) { media(id_in: [$idList]) { id coverImage { medium } } } }") ?: return emptyMap()
        return runCatching { Mapper.json.decodeFromString<CoverPageResponse>(raw) }.getOrNull()
            ?.data?.page?.media.orEmpty()
            .mapNotNull { m -> m.coverImage?.medium?.let { m.id.toLong() to it } }.toMap()
    }

    override suspend fun isOnList(item: DiscoveryQueue.Item): Boolean =
        Anilist.query.getMedia(item.id.toInt())?.userStatus != null

    // ---- AniList requests ----

    private class Candidate(val media: CandidateMedia) {
        val contributions = ArrayList<Pair<ListEntry, Double>>()
        var positive: List<MediaTag> = emptyList()
        var negative: List<MediaTag> = emptyList()
        var total = 0.0
    }

    private fun Double?.orZero() = this ?: 0.0

    /** Raw-body query: the response is decoded here into the small models below. */
    private suspend fun rawQuery(query: String, variables: Map<String, Any?> = emptyMap()): String? {
        var raw: String? = null
        Anilist.executeQuery<JsonObject>(
            query, Gson().toJson(variables), cache = 0, onRawResponse = { raw = it },
        )
        return raw
    }

    private suspend fun fetchProfile(userId: Int): ProfileData? {
        val query = """
            query (${'$'}userId: Int) {
              list: MediaListCollection(userId: ${'$'}userId, type: ANIME, status_in: [COMPLETED, CURRENT, REPEATING, PAUSED]) {
                lists { entries { mediaId status score(format: POINT_100) media { title { userPreferred } coverImage { medium } } } }
              }
              viewer: Viewer { statistics { anime { meanScore tags(limit: 100, sort: COUNT_DESC) { count meanScore tag { name } } } } }
            }
        """.trimIndent()
        val raw = rawQuery(query, mapOf("userId" to userId)) ?: return null
        return runCatching { Mapper.json.decodeFromString<ProfileResponse>(raw).data }
            .onFailure { Logger.log("Anime discovery profile: ${it.message}") }.getOrNull()
    }

    /** Seed id → its recommendation nodes. Null when every request failed. */
    private suspend fun fetchRecommendations(seedIds: List<Int>): Map<Int, List<RecNode>>? {
        val result = HashMap<Int, List<RecNode>>()
        var anyOk = false
        seedIds.chunked(SEEDS_PER_QUERY).forEach { chunk ->
            val query = buildString {
                append("{")
                chunk.forEachIndexed { i, id -> append("s$i: Media(id: $id) { ...R } ") }
                append("} fragment R on Media { recommendations(sort: RATING_DESC, perPage: $RECS_PER_SEED) { nodes { rating ")
                append("mediaRecommendation { id type format status isAdult popularity averageScore title { userPreferred } ")
                append("coverImage { large } tags { name rank isGeneralSpoiler isMediaSpoiler } mediaListEntry { status } } } } }")
            }
            val raw = rawQuery(query) ?: return@forEach
            val data = runCatching { Mapper.json.decodeFromString<RecsResponse>(raw).data }
                .onFailure { Logger.log("Anime discovery recs: ${it.message}") }.getOrNull() ?: return@forEach
            anyOk = true
            chunk.forEachIndexed { i, id ->
                result[id] = data["s$i"]?.recommendations?.nodes.orEmpty().filterNotNull()
            }
        }
        return if (anyOk) result else null
    }

    @Serializable private data class ProfileResponse(val data: ProfileData? = null)
    @Serializable private data class ProfileData(val list: ListCollection? = null, val viewer: Viewer? = null)
    @Serializable private data class ListCollection(val lists: List<ListGroup>? = null)
    @Serializable private data class ListGroup(val entries: List<ListEntry>? = null)
    @Serializable private data class ListEntry(
        val mediaId: Int,
        val status: String? = null,
        val score: Double? = null,
        val media: TitledMedia? = null,
    )
    @Serializable private data class TitledMedia(val title: Title? = null, val coverImage: Cover? = null)
    @Serializable private data class Title(val userPreferred: String? = null)
    @Serializable private data class Viewer(val statistics: Statistics? = null)
    @Serializable private data class Statistics(val anime: AnimeStatistics? = null)
    @Serializable private data class AnimeStatistics(val meanScore: Double? = null, val tags: List<TagStatistic>? = null)
    @Serializable private data class TagStatistic(val count: Int? = null, val meanScore: Double? = null, val tag: TagName? = null)
    @Serializable private data class TagName(val name: String? = null)

    @Serializable private data class RecsResponse(val data: Map<String, SeedRecs?>? = null)
    @Serializable private data class SeedRecs(val recommendations: RecConnection? = null)
    @Serializable private data class RecConnection(val nodes: List<RecNode?>? = null)
    @Serializable private data class RecNode(val rating: Int? = null, val mediaRecommendation: CandidateMedia? = null)
    @Serializable private data class CandidateMedia(
        val id: Int,
        val type: String? = null,
        val format: String? = null,
        val status: String? = null,
        val isAdult: Boolean? = null,
        val popularity: Int? = null,
        val averageScore: Int? = null,
        val title: Title? = null,
        val coverImage: Cover? = null,
        val tags: List<MediaTag>? = null,
        val mediaListEntry: ListStatus? = null,
    )
    @Serializable private data class Cover(val large: String? = null, val medium: String? = null)
    @Serializable private data class MediaTag(
        val name: String? = null,
        val rank: Int? = null,
        val isGeneralSpoiler: Boolean? = null,
        val isMediaSpoiler: Boolean? = null,
    )
    @Serializable private data class ListStatus(val status: String? = null)

    @Serializable private data class CoverPageResponse(val data: CoverPageData? = null)
    @Serializable private data class CoverPageData(@SerialName("Page") val page: CoverPage? = null)
    @Serializable private data class CoverPage(val media: List<CoverMedia>? = null)
    @Serializable private data class CoverMedia(val id: Int, val coverImage: Cover? = null)
}
