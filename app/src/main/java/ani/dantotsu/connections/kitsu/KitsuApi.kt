package ani.dantotsu.connections.kitsu

import ani.dantotsu.asyncMap
import ani.dantotsu.client
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kitsu id resolution — maps AniList / MyAnimeList ids to Kitsu media ids through Kitsu's own
 * `/mappings` route. No authentication required, so (like [ani.dantotsu.connections.mangabaka.MangaBakaApi])
 * these calls must never be gated behind [Kitsu.token].
 *
 * Results (hits and misses) are cached in [PrefManager] custom vals plus an in-memory negative set,
 * so a whole-list comparison doesn't re-query the same series.
 */
object KitsuApi {
    // v2: v1 could cache a wrong-type / over-broad mapping result; bump to drop those.
    private const val CACHE_PREFIX = "kitsu_media2_"

    private val negativeCache = HashSet<String>()

    /** Small shared limit so a whole-list comparison doesn't fire hundreds of parallel lookups. */
    private val rateLimiter = Semaphore(4)

    /** `externalSite` segment for a `/mappings` lookup. */
    private fun site(source: String, isAnime: Boolean): String =
        "$source/${if (isAnime) "anime" else "manga"}"

    /** The Kitsu resource type a mapping's `item` must be for this `externalSite` (null = any). */
    private fun expectedItemType(externalSite: String): String? = when {
        externalSite.endsWith("/anime") -> "anime"
        externalSite.endsWith("/manga") -> "manga"
        externalSite == "mangaupdates" -> "manga"
        else -> null
    }

    /**
     * Resolves a Kitsu media id from an AniList id, falling back to the MyAnimeList id.
     * Returns null when Kitsu has no mapping for either.
     */
    suspend fun resolveMediaId(isAnime: Boolean, anilistId: Int?, malId: Int?): String? {
        anilistId?.let { lookup(site("anilist", isAnime), it.toString())?.let { id -> return id } }
        malId?.let { lookup(site("myanimelist", isAnime), it.toString())?.let { id -> return id } }
        return null
    }

    /**
     * Batch-resolves many external ids in one pass — `/mappings` accepts a comma-separated
     * `filter[externalId]`. Turns a whole-list comparison's hundreds of single lookups into a
     * handful of requests. Returns `externalId → Kitsu media id` for the ones Kitsu knows; misses
     * are negative-cached. Cache hits are filled in without any request.
     *
     * @param source `"anilist"` or `"myanimelist"`.
     */
    suspend fun resolveMediaIdsBatch(
        isAnime: Boolean,
        source: String,
        ids: Collection<Int>,
    ): Map<Int, String> =
        batchLookup(site(source, isAnime), ids.map { it.toString() })
            .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap()

    /** Batch MangaUpdates-slug (numeric id) → Kitsu manga id. */
    suspend fun resolveMangaUpdatesBatch(muIds: Collection<Long>): Map<Long, String> =
        batchLookup("mangaupdates", muIds.map { it.toString() })
            .mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }.toMap()

    private suspend fun batchLookup(externalSite: String, ids: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        val toFetch = ids.distinct().filter { id ->
            val key = "$CACHE_PREFIX${externalSite}_$id"
            val cached = PrefManager.getCustomVal(key, "")
            when {
                cached.isNotBlank() -> { out[id] = cached; false }
                key in negativeCache -> false
                else -> true
            }
        }
        if (toFetch.isEmpty()) return out
        val want = expectedItemType(externalSite)
        val requested = toFetch.toHashSet()

        // Kitsu caps page size at 20; keep the chunk under that so a title with a couple of
        // mappings for the same site can't push wanted rows off the page.
        toFetch.chunked(15).asyncMap { chunk ->
            tryWithSuspend {
                rateLimiter.withPermit {
                    val url = "${Kitsu.API_URL}/mappings" +
                        "?filter%5BexternalSite%5D=$externalSite" +
                        "&filter%5BexternalId%5D=${chunk.joinToString(",")}" +
                        "&include=item&page%5Blimit%5D=20"
                    client.get(url, mapOf("Accept" to "application/vnd.api+json"))
                        .parsed<MappingsResponse>().data.orEmpty()
                }
            }.orEmpty().forEach { m ->
                val ext = m.attributes?.externalId ?: return@forEach
                val type = m.relationships?.item?.data?.type
                val mediaId = m.relationships?.item?.data?.id ?: return@forEach
                // Only trust a row whose id we actually asked for and whose item is the right kind —
                // guards against a filter that came back over-broad.
                if (ext !in requested || (want != null && type != want)) return@forEach
                synchronized(out) { out[ext] = mediaId }
                seedMediaId(externalSite, ext, mediaId)
            }
        }
        toFetch.filter { it !in out }.forEach { negativeCache.add("$CACHE_PREFIX${externalSite}_$it") }
        return out
    }

    /**
     * Resolves a Kitsu **manga** id from a MangaUpdates series id. Kitsu's own `/mappings` carry a
     * `mangaupdates` external site, so this is tried directly first; the caller falls back to
     * resolving through MangaBaka's AniList/MAL cross-ids when Kitsu has no direct mapping.
     */
    suspend fun resolveMangaFromMangaUpdates(muSeriesId: Long): String? =
        lookup("mangaupdates", muSeriesId.toString())

    private const val TOTAL_CACHE_PREFIX = "kitsu_total2_"

    /** `externalSite` string ("anilist"/"myanimelist"/"mangaupdates") → the segment [lookup] uses. */
    fun siteFor(source: String, isAnime: Boolean): String =
        if (source == "mangaupdates") source else site(source, isAnime)

    /**
     * Pre-seeds the id + total caches from the user's library (see [KitsuSync.getLibrarySnapshot]),
     * so a whole-list comparison resolves everything already in the library with zero network calls
     * and only looks up the media that are genuinely missing.
     */
    fun seedMediaId(externalSite: String, externalId: String, mediaId: String) {
        PrefManager.setCustomVal("$CACHE_PREFIX${externalSite}_$externalId", mediaId)
    }

    fun seedTotal(isAnime: Boolean, mediaId: String, total: Int) {
        if (total > 0) {
            PrefManager.setCustomVal("$TOTAL_CACHE_PREFIX${if (isAnime) "anime" else "manga"}_$mediaId", total)
        }
    }

    /** Kitsu's own episode/chapter count for a media (for clamping a push). Null when unknown. */
    suspend fun mediaTotal(isAnime: Boolean, mediaId: String): Int? {
        val kind = if (isAnime) "anime" else "manga"
        val field = if (isAnime) "episodeCount" else "chapterCount"
        val cacheKey = "$TOTAL_CACHE_PREFIX${kind}_$mediaId"
        val cached = PrefManager.getCustomVal(cacheKey, 0)
        if (cached > 0) return cached
        val total = tryWithSuspend {
            rateLimiter.withPermit {
                client.get(
                    "${Kitsu.API_URL}/$kind/$mediaId?fields%5B$kind%5D=$field",
                    mapOf("Accept" to "application/vnd.api+json"),
                ).parsed<MediaTotalResponse>().data?.attributes?.let {
                    if (isAnime) it.episodeCount else it.chapterCount
                }
            }
        }?.takeIf { it > 0 }
        if (total != null) PrefManager.setCustomVal(cacheKey, total)
        return total
    }

    @Serializable
    private data class MediaTotalResponse(val data: MediaTotalData? = null)

    @Serializable
    private data class MediaTotalData(val attributes: MediaTotalAttributes? = null)

    @Serializable
    private data class MediaTotalAttributes(
        val episodeCount: Int? = null,
        val chapterCount: Int? = null,
    )

    private suspend fun lookup(externalSite: String, externalId: String): String? {
        val cacheKey = "$CACHE_PREFIX${externalSite}_$externalId"
        val cached = PrefManager.getCustomVal(cacheKey, "")
        if (cached.isNotBlank()) return cached
        if (cacheKey in negativeCache) return null

        val want = expectedItemType(externalSite)
        val resolved = tryWithSuspend {
            rateLimiter.withPermit {
                val url = "${Kitsu.API_URL}/mappings" +
                    "?filter%5BexternalSite%5D=$externalSite" +
                    "&filter%5BexternalId%5D=$externalId" +
                    "&include=item"
                client.get(url, mapOf("Accept" to "application/vnd.api+json"))
                    .parsed<MappingsResponse>()
                    .data.orEmpty()
                    .firstOrNull { m ->
                        m.attributes?.externalId == externalId &&
                            (want == null || m.relationships?.item?.data?.type == want)
                    }
                    ?.relationships?.item?.data?.id
            }
        }

        if (resolved != null) {
            PrefManager.setCustomVal(cacheKey, resolved)
        } else {
            Logger.log("Kitsu mapping miss: $externalSite/$externalId")
            negativeCache.add(cacheKey)
        }
        return resolved
    }

    @Serializable
    data class MappingsResponse(val data: List<Mapping>? = null)

    @Serializable
    data class Mapping(
        val id: String,
        val attributes: MappingAttributes? = null,
        val relationships: MappingRelationships? = null,
    )

    @Serializable
    data class MappingAttributes(
        @SerialName("externalSite") val externalSite: String? = null,
        @SerialName("externalId") val externalId: String? = null,
    )

    @Serializable
    data class MappingRelationships(val item: RelationshipRef? = null)

    @Serializable
    data class RelationshipRef(val data: ResourceRef? = null)

    @Serializable
    data class ResourceRef(val type: String? = null, val id: String? = null)
}
