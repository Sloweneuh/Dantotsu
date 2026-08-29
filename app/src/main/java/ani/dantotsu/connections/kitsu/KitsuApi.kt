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
    // v3: v2 could cache whichever of several duplicate mapping rows (e.g. a companion "Break Time"
    // entry sharing the real season's AniList id) happened to be seen last; bump to drop those —
    // see the createdAt tie-break in lookup()/batchLookup().
    private const val CACHE_PREFIX = "kitsu_media3_"

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
        // Same "prefer the oldest mapping row" rule as [lookup], applied per external id: a chunk
        // can carry more than one row for the same id when Kitsu has a duplicate/erroneous mapping
        // (e.g. a companion "Break Time" entry mapped onto the real season's AniList id).
        val bestCreatedAt = HashMap<String, String>()

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
                val createdAt = m.attributes?.createdAt ?: ""
                synchronized(out) {
                    if (ext !in bestCreatedAt || createdAt < bestCreatedAt.getValue(ext)) {
                        bestCreatedAt[ext] = createdAt
                        out[ext] = mediaId
                        seedMediaId(externalSite, ext, mediaId)
                    }
                }
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
                    .filter { m ->
                        m.attributes?.externalId == externalId &&
                            (want == null || m.relationships?.item?.data?.type == want)
                    }
                    // More than one row can legitimately match (see MappingAttributes.createdAt) —
                    // the oldest mapping is the one to trust.
                    .minByOrNull { it.attributes?.createdAt ?: "" }
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
        // Kitsu occasionally carries more than one mapping row for the same external id — e.g. a
        // companion/recap "Break Time" entry gets mapped to the same AniList id as the actual
        // season. Those spinoff entries are near-always mapped *after* the real one already has a
        // well-established mapping, so preferring the oldest mapping row (see [lookup]) reliably
        // picks the real show over a newer duplicate/erroneous mapping.
        @SerialName("createdAt") val createdAt: String? = null,
    )

    @Serializable
    data class MappingRelationships(val item: RelationshipRef? = null)

    @Serializable
    data class RelationshipRef(val data: ResourceRef? = null)

    @Serializable
    data class ResourceRef(val type: String? = null, val id: String? = null)

    // =============================================================================================
    // Search + media detail  (public routes, no auth)
    // =============================================================================================

    const val WEB_URL = "https://kitsu.io"

    private val ACCEPT = mapOf("Accept" to "application/vnd.api+json")

    /** All the media attributes the search results and the media page read. */
    @Serializable
    data class KitsuMedia(
        val slug: String? = null,
        val canonicalTitle: String? = null,
        val titles: Map<String, String?>? = null,
        val abbreviatedTitles: List<String>? = null,
        val synopsis: String? = null,
        val description: String? = null,
        val posterImage: KitsuSync.PosterImage? = null,
        val coverImage: KitsuSync.PosterImage? = null,
        val subtype: String? = null,
        val status: String? = null,
        val tba: String? = null,
        val startDate: String? = null,
        val endDate: String? = null,
        val nextRelease: String? = null,
        val season: String? = null,
        val ageRating: String? = null,
        val ageRatingGuide: String? = null,
        val nsfw: Boolean? = null,
        val averageRating: String? = null,
        val userCount: Int? = null,
        val favoritesCount: Int? = null,
        val popularityRank: Int? = null,
        val ratingRank: Int? = null,
        val youtubeVideoId: String? = null,
        // anime
        val episodeCount: Int? = null,
        val episodeLength: Int? = null,
        val totalLength: Int? = null,
        val showType: String? = null,
        // manga
        val chapterCount: Int? = null,
        val volumeCount: Int? = null,
        val serialization: String? = null,
        val mangaType: String? = null,
    ) : java.io.Serializable

    @Serializable
    data class MediaResource(val id: String, val type: String? = null, val attributes: KitsuMedia? = null)

    @Serializable
    data class MediaResponse(val data: MediaResource? = null)

    @Serializable
    data class ListResponse(val data: List<MediaResource>? = null, val links: Links? = null)

    @Serializable
    data class Links(val next: String? = null)

    /** One search-result / related-media item: the media plus the id needed to open its page. */
    data class Item(val id: String, val media: KitsuMedia) : java.io.Serializable

    data class SearchPage(val results: List<Item>, val hasNextPage: Boolean)

    private const val PAGE = 20

    suspend fun search(
        isAnime: Boolean,
        query: String?,
        page: Int,
        categories: List<String>? = null,
        subtypes: List<String>? = null,
        statuses: List<String>? = null,
        ageRatings: List<String>? = null,
        season: String? = null,
        yearFrom: Int? = null,
        yearTo: Int? = null,
        sort: String? = null,
    ): SearchPage? = tryWithSuspend {
        val kind = if (isAnime) "anime" else "manga"
        val url = StringBuilder("${Kitsu.API_URL}/$kind?page%5Blimit%5D=$PAGE&page%5Boffset%5D=${(page - 1) * PAGE}")
        query?.trim()?.takeIf { it.isNotBlank() }?.let { url.append("&filter%5Btext%5D=").append(enc(it)) }
        categories?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?.let { url.append("&filter%5Bcategories%5D=").append(enc(it.joinToString(","))) }
        subtypes?.takeIf { it.isNotEmpty() }?.let { url.append("&filter%5Bsubtype%5D=").append(enc(it.joinToString(","))) }
        statuses?.takeIf { it.isNotEmpty() }?.let { url.append("&filter%5Bstatus%5D=").append(enc(it.joinToString(","))) }
        ageRatings?.takeIf { it.isNotEmpty() }?.let { url.append("&filter%5BageRating%5D=").append(enc(it.joinToString(","))) }
        season?.takeIf { it.isNotBlank() }?.let { url.append("&filter%5Bseason%5D=").append(it) }
        if (yearFrom != null || yearTo != null) {
            url.append("&filter%5Byear%5D=").append(enc("${yearFrom ?: 1900}..${yearTo ?: 2100}"))
        }
        sort?.takeIf { it.isNotBlank() }?.let { url.append("&sort=").append(enc(it)) }

        val res = rateLimiter.withPermit {
            KitsuSync.json.decodeFromString<ListResponse>(client.get(url.toString(), ACCEPT).text)
        }
        SearchPage(
            res.data.orEmpty().mapNotNull { r -> r.attributes?.let { Item(r.id, it) } },
            res.links?.next != null,
        )
    }

    /** One related media entry (sequel, adaptation, side story, …). */
    data class Relation(
        val role: String,
        val id: String,
        val isAnime: Boolean,
        val media: KitsuMedia,
    ) : java.io.Serializable

    /** The media plus everything the detail page shows. */
    data class KitsuMediaFull(
        val id: String,
        val media: KitsuMedia,
        val categories: List<Pair<String, String>>,   // slug to title
        val anilistId: Int?,
        val malId: Int?,
        val muId: String?,                            // MangaUpdates series id (manga only)
        val relations: List<Relation>,
        val streamers: List<Pair<String, String>>,    // name to url
    )

    /** External ids Kitsu holds for a media, from its own `/mappings`. */
    data class CrossIds(val anilistId: Int?, val malId: Int?, val muId: String?)

    suspend fun getMediaFull(isAnime: Boolean, id: String): KitsuMediaFull? {
        val kind = if (isAnime) "anime" else "manga"
        val mediaAsync = tryWithSuspend {
            rateLimiter.withPermit {
                KitsuSync.json.decodeFromString<MediaResponse>(
                    client.get("${Kitsu.API_URL}/$kind/$id?include=categories", ACCEPT).text
                )
            }
        }
        val media = mediaAsync?.data?.attributes ?: return null
        val cats = tryWithSuspend {
            rateLimiter.withPermit {
                KitsuSync.json.decodeFromString<CategoriesResponse>(
                    client.get("${Kitsu.API_URL}/$kind/$id/categories?page%5Blimit%5D=20", ACCEPT).text
                ).data.orEmpty().mapNotNull { c ->
                    val s = c.attributes?.slug; val t = c.attributes?.title
                    if (s != null && t != null) s to t else null
                }
            }
        }.orEmpty()
        val ids = crossIds(isAnime, id)
        val relations = tryWithSuspend {
            rateLimiter.withPermit {
                val r = KitsuSync.json.decodeFromString<RelationshipsResponse>(
                    client.get(
                        "${Kitsu.API_URL}/$kind/$id/media-relationships?include=destination&page%5Blimit%5D=20",
                        ACCEPT,
                    ).text
                )
                val byId = r.included.orEmpty().associateBy { it.id }
                r.data.orEmpty().mapNotNull { row ->
                    val role = row.attributes?.role ?: return@mapNotNull null
                    val destId = row.relationships?.destination?.data?.id ?: return@mapNotNull null
                    val dest = byId[destId] ?: return@mapNotNull null
                    dest.attributes?.let { Relation(role, destId, dest.type == "anime", it) }
                }
            }
        }.orEmpty()
        val streamers = if (!isAnime) emptyList() else tryWithSuspend {
            rateLimiter.withPermit {
                val r = KitsuSync.json.decodeFromString<StreamingResponse>(
                    client.get("${Kitsu.API_URL}/anime/$id/streaming-links?include=streamer", ACCEPT).text
                )
                val byId = r.included.orEmpty().associateBy { it.id }
                r.data.orEmpty().mapNotNull { row ->
                    val streamerId = row.relationships?.streamer?.data?.id
                    val name = byId[streamerId]?.attributes?.siteName ?: return@mapNotNull null
                    val link = row.attributes?.url ?: return@mapNotNull null
                    name to link
                }
            }
        }.orEmpty()
        return KitsuMediaFull(id, media, cats, ids.anilistId, ids.malId, ids.muId, relations, streamers)
    }

    // ---- episodes (anime) ----

    data class KitsuEpisode(
        val number: Int?,
        val title: String?,
        val synopsis: String?,
        val thumb: String?,
        val airdate: String?,
    ) : java.io.Serializable

    @Serializable
    private data class EpisodesResponse(val data: List<EpisodeResource>? = null, val links: Links? = null)

    @Serializable
    private data class EpisodeResource(val attributes: EpisodeAttributes? = null)

    @Serializable
    private data class EpisodeAttributes(
        val number: Int? = null,
        val canonicalTitle: String? = null,
        val titles: Map<String, String?>? = null,
        val synopsis: String? = null,
        val description: String? = null,
        val airdate: String? = null,
        val thumbnail: KitsuSync.PosterImage? = null,
    )

    /** Every episode of a Kitsu anime, ordered by number. Public route, no auth. */
    suspend fun getEpisodes(id: String): List<KitsuEpisode> {
        val out = mutableListOf<KitsuEpisode>()
        var url: String? = "${Kitsu.API_URL}/anime/$id/episodes?sort=number&page%5Blimit%5D=20"
        var guard = 0
        while (url != null && guard++ < 60) {
            val page = tryWithSuspend {
                rateLimiter.withPermit {
                    KitsuSync.json.decodeFromString<EpisodesResponse>(client.get(url!!, ACCEPT).text)
                }
            } ?: break
            page.data.orEmpty().forEach { r ->
                val a = r.attributes ?: return@forEach
                out += KitsuEpisode(
                    number = a.number,
                    title = a.canonicalTitle ?: a.titles?.values?.firstOrNull { !it.isNullOrBlank() },
                    synopsis = a.synopsis ?: a.description,
                    thumb = a.thumbnail?.original ?: a.thumbnail?.medium ?: a.thumbnail?.small,
                    airdate = a.airdate,
                )
            }
            url = page.links?.next
        }
        return out
    }

    /** AniList / MAL / MangaUpdates ids Kitsu holds for a media, from its own `/mappings`. */
    private suspend fun crossIds(isAnime: Boolean, mediaId: String): CrossIds = tryWithSuspend {
        rateLimiter.withPermit {
            val kind = if (isAnime) "anime" else "manga"
            val res = KitsuSync.json.decodeFromString<MappingsResponse>(
                client.get("${Kitsu.API_URL}/$kind/$mediaId/mappings?page%5Blimit%5D=20", ACCEPT).text
            )
            var al: Int? = null; var mal: Int? = null; var mu: String? = null
            res.data.orEmpty().forEach { m ->
                val site = m.attributes?.externalSite ?: return@forEach
                val ext = m.attributes.externalId ?: return@forEach
                when {
                    site.startsWith("anilist") -> al = ext.toIntOrNull()
                    site.startsWith("myanimelist") -> mal = ext.toIntOrNull()
                    site.startsWith("mangaupdates") -> mu = ext
                }
            }
            CrossIds(al, mal, mu)
        }
    } ?: CrossIds(null, null, null)

    // ---- filter categories ----

    private var categoryOptions: List<Pair<String, String>>? = null
    private val categoryNames = HashMap<String, String>()

    /** Top ~200 Kitsu categories (slug to title), by media count. Cached after the first fetch. */
    suspend fun getCategories(): List<Pair<String, String>> {
        categoryOptions?.let { return it }
        val out = mutableListOf<Pair<String, String>>()
        var url: String? = "${Kitsu.API_URL}/categories?sort=-totalMediaCount&page%5Blimit%5D=20"
        var guard = 0
        while (url != null && guard++ < 12) {
            val page = tryWithSuspend {
                rateLimiter.withPermit {
                    KitsuSync.json.decodeFromString<CategoriesResponse>(client.get(url!!, ACCEPT).text)
                }
            } ?: break
            page.data.orEmpty().forEach { c ->
                val s = c.attributes?.slug ?: return@forEach
                val t = c.attributes.title ?: return@forEach
                out += s to t
                categoryNames[s] = t
            }
            url = page.links?.next
        }
        if (out.isNotEmpty()) categoryOptions = out
        return out
    }

    fun seedCategoryName(slug: String, label: String) { categoryNames[slug] = label }

    fun resolveCategoryName(slug: String): String =
        categoryNames[slug] ?: slug.split('-').filter { it.isNotBlank() }
            .joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    @Serializable
    data class CategoriesResponse(val data: List<CategoryResource>? = null, val links: Links? = null)

    @Serializable
    data class CategoryResource(val id: String, val attributes: CategoryAttributes? = null)

    @Serializable
    data class CategoryAttributes(val slug: String? = null, val title: String? = null)

    @Serializable
    data class RelationshipsResponse(
        val data: List<RelRow>? = null,
        val included: List<MediaResource>? = null,
    )

    @Serializable
    data class RelRow(val attributes: RelRowAttributes? = null, val relationships: RelRowRelationships? = null)

    @Serializable
    data class RelRowAttributes(val role: String? = null)

    @Serializable
    data class RelRowRelationships(val destination: RelationshipRef? = null)

    @Serializable
    data class StreamingResponse(
        val data: List<StreamRow>? = null,
        val included: List<StreamerResource>? = null,
    )

    @Serializable
    data class StreamRow(val attributes: StreamAttributes? = null, val relationships: StreamRelationships? = null)

    @Serializable
    data class StreamAttributes(val url: String? = null)

    @Serializable
    data class StreamRelationships(val streamer: RelationshipRef? = null)

    @Serializable
    data class StreamerResource(val id: String, val attributes: StreamerAttributes? = null)

    @Serializable
    data class StreamerAttributes(val siteName: String? = null)
}
