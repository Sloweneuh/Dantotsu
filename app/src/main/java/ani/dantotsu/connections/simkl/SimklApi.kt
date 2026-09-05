package ani.dantotsu.connections.simkl

import ani.dantotsu.FileUrl
import ani.dantotsu.Mapper
import ani.dantotsu.client
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.connections.IdCache
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Simkl id resolution — maps an AniList / MyAnimeList id to the Simkl record it belongs to
 * (`GET /search/id`). Needs the api key but no user token.
 *
 * Simkl often stores what AniList splits into several entries (a season, its recap "special", a
 * plan/compilation cut) as **one** record, so two AniList ids can resolve to the same Simkl id.
 * The compare screen uses that to avoid offering the same Simkl entry twice.
 *
 * Hits and misses are cached in [IdCache] plus an in-memory negative set.
 */
object SimklApi {
    // v2: the info tab resolves by AniList/MAL id through this cache, while the standalone media
    // page gets its id directly from search results — a franchise with several AniList entries
    // (season re-releases, recuts) could have cached a stale/wrong id here from before this cache
    // had any info-tab reader to notice. Bump to drop anything cached under v1.
    private const val CACHE_PREFIX = "simkl_id2_"
    private const val EP_CACHE_PREFIX = "simkl_eps2_"

    private val negativeCache = HashSet<String>()
    private val rateLimiter = Semaphore(4)

    data class Match(val simklId: Long, val totalEpisodes: Int?)

    /** Resolves the Simkl record for an AniList id, falling back to the MyAnimeList id. */
    suspend fun resolve(anilistId: Int?, malId: Int?): Match? {
        anilistId?.let { lookup("anilist", it)?.let { m -> return m } }
        malId?.let { lookup("mal", it)?.let { m -> return m } }
        return null
    }

    private suspend fun lookup(param: String, id: Int): Match? {
        val cacheKey = "$CACHE_PREFIX${param}_$id"
        val cached = IdCache.getLong(cacheKey) ?: 0L
        if (cached > 0L) {
            val eps = IdCache.getInt("$EP_CACHE_PREFIX${param}_$id") ?: 0
            return Match(cached, eps.takeIf { it > 0 })
        }
        if (cacheKey in negativeCache) return null

        // Distinguish "Simkl has no such id" (cache the miss) from "the request failed" (don't — a
        // 429 or a dropped connection shouldn't blacklist the id for the rest of the session).
        val results: List<IdResult>? = tryWithSuspend {
            rateLimiter.withPermit {
                val raw = client.get(
                    "${Simkl.API_URL}/search/id?$param=$id&client_id=${Simkl.CLIENT_ID}",
                    mapOf("simkl-api-key" to Simkl.CLIENT_ID),
                    cacheTime = 0,
                ).text.trim()
                // Simkl returns a bare object for a hit and (per the docs) an empty array for a miss.
                when {
                    raw.startsWith("[") -> Mapper.json.decodeFromString<List<IdResult>>(raw)
                    raw.startsWith("{") -> listOf(Mapper.json.decodeFromString<IdResult>(raw))
                    else -> emptyList()
                }
            }
        }
        if (results == null) return null

        val match = results.firstOrNull()
        val simklId = match?.ids?.simkl
        if (simklId != null && simklId > 0) {
            IdCache.put(cacheKey, simklId)
            match.totalEpisodes?.let { IdCache.put("$EP_CACHE_PREFIX${param}_$id", it) }
            return Match(simklId, match.totalEpisodes)
        }
        Logger.log("Simkl id miss: $param/$id")
        negativeCache.add(cacheKey)
        return null
    }

    @Serializable
    private data class IdResult(
        @SerialName("total_episodes") val totalEpisodes: Int? = null,
        val ids: Ids? = null,
    ) {
        @Serializable
        data class Ids(val simkl: Long? = null)
    }

    // =============================================================================================
    // Search + anime detail  (public routes, api-key only)
    // =============================================================================================

    private val headers get() = mapOf("simkl-api-key" to Simkl.CLIENT_ID)

    fun posterUrl(poster: String?, size: String = "_ca"): String? =
        poster?.takeIf { it.isNotBlank() }?.let { "https://simkl.in/posters/$it$size.jpg" }

    fun episodeImageUrl(img: String?, size: String = "_w"): String? =
        img?.takeIf { it.isNotBlank() }?.let { "https://simkl.in/episodes/$it$size.jpg" }

    /**
     * Simkl's overviews and episode synopses use a backtick where an apostrophe belongs
     * ("Demon King`s defeat"). Left alone it also trips Markwon into opening code spans, so every
     * bit of Simkl prose is normalised through here before display.
     */
    fun cleanText(s: String?): String? =
        s?.replace('`', '\'')?.trim()?.takeIf { it.isNotEmpty() }

    /** One search-result row. */
    @Serializable
    data class SimklMedia(
        val title: String? = null,
        @SerialName("title_romaji") val titleRomaji: String? = null,
        val year: Int? = null,
        @SerialName("anime_type") val animeType: String? = null,
        val poster: String? = null,
        val ratings: Ratings? = null,
        val ids: SearchIds? = null,
    ) : java.io.Serializable {
        @Serializable
        data class SearchIds(
            @SerialName("simkl_id") val simklIdA: Long? = null,
            val simkl: Long? = null,
            val slug: String? = null,
        ) {
            val simklId: Long? get() = simklIdA ?: simkl
        }

        val simklId: Long? get() = ids?.simklId
    }
    // SimklMedia is java-serialized as part of SimklSearchResults' aspirational Serializable.

    suspend fun search(query: String?, page: Int): List<SimklMedia> {
        val q = query?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return tryWithSuspend {
            rateLimiter.withPermit {
                val raw = client.get(
                    "${Simkl.API_URL}/search/anime?q=${java.net.URLEncoder.encode(q, "UTF-8")}" +
                        "&page=$page&limit=20&extended=full&client_id=${Simkl.CLIENT_ID}",
                    headers, cacheTime = 0,
                ).text.trim()
                if (raw.startsWith("[")) Mapper.json.decodeFromString<List<SimklMedia>>(raw)
                else emptyList()
            }
        }.orEmpty()
    }

    // ---- full anime ----

    @Serializable
    data class Rating(val rating: Double? = null, val votes: Int? = null, val rank: Int? = null)

    @Serializable
    data class Ratings(
        val simkl: Rating? = null,
        val mal: Rating? = null,
        val imdb: Rating? = null,
    )

    @Serializable
    data class FullIds(
        val simkl: Long? = null,
        val slug: String? = null,
        val mal: String? = null,
        val anilist: String? = null,
        val anidb: String? = null,
        val tvdb: String? = null,
        val imdb: String? = null,
    )

    @Serializable
    data class Trailer(val name: String? = null, val youtube: String? = null)

    @Serializable
    data class Studio(val id: Int? = null, val name: String? = null)

    /** `alt_titles` rows are objects — `{ "name": "...", "lang": "..." }` — not bare strings. */
    @Serializable
    data class AltTitle(val name: String? = null, val lang: String? = null)

    /** One related anime (sequel, prequel, side story …), from the full anime's `relations`. */
    @Serializable
    data class SimklRelation(
        val title: String? = null,
        @SerialName("en_title") val enTitle: String? = null,
        val year: Int? = null,
        val poster: String? = null,
        @SerialName("anime_type") val animeType: String? = null,
        @SerialName("relation_type") val relationType: String? = null,
        val ids: SimklMedia.SearchIds? = null,
    ) {
        val simklId: Long? get() = ids?.simklId
    }

    /** One episode, from `GET /anime/episodes/{id}?extended=full`. */
    @Serializable
    data class SimklEpisode(
        val title: String? = null,
        val description: String? = null,
        val episode: Int? = null,
        val type: String? = null,
        val aired: Boolean? = null,
        val img: String? = null,
        val date: String? = null,
    )

    @Serializable
    data class SimklAnimeFull(
        val title: String? = null,
        @SerialName("en_title") val enTitle: String? = null,
        @SerialName("alt_titles") val altTitles: List<AltTitle>? = null,
        val year: Int? = null,
        @SerialName("anime_type") val animeType: String? = null,
        val status: String? = null,
        val network: String? = null,
        val country: String? = null,
        @SerialName("total_episodes") val totalEpisodes: Int? = null,
        val runtime: Int? = null,
        @SerialName("first_aired") val firstAired: String? = null,
        @SerialName("last_aired") val lastAired: String? = null,
        @SerialName("season_name_year") val seasonNameYear: String? = null,
        val certification: String? = null,
        val poster: String? = null,
        val fanart: String? = null,
        val overview: String? = null,
        val genres: List<String>? = null,
        val studios: List<Studio>? = null,
        val ratings: Ratings? = null,
        val ids: FullIds? = null,
        val trailers: List<Trailer>? = null,
        val relations: List<SimklRelation>? = null,
        @SerialName("users_recommendations") val recommendations: List<SimklMedia>? = null,
    )

    suspend fun getAnime(simklId: Long): SimklAnimeFull? = tryWithSuspend {
        rateLimiter.withPermit {
            Mapper.json.decodeFromString<SimklAnimeFull>(
                client.get(
                    "${Simkl.API_URL}/anime/$simklId?extended=full&client_id=${Simkl.CLIENT_ID}",
                    headers, cacheTime = 0,
                ).text
            )
        }
    }

    suspend fun getEpisodes(simklId: Long): List<SimklEpisode> = tryWithSuspend {
        rateLimiter.withPermit {
            val raw = client.get(
                "${Simkl.API_URL}/anime/episodes/$simklId?extended=full&client_id=${Simkl.CLIENT_ID}",
                headers, cacheTime = 0,
            ).text.trim()
            if (raw.startsWith("[")) Mapper.json.decodeFromString<List<SimklEpisode>>(raw) else emptyList()
        }
    }.orEmpty()

    @Serializable
    private data class IdsWrapper(val ids: FullIds? = null)

    /**
     * `simkl id → AniList id` for a batch of records — used to turn a Simkl recommendation list
     * into AniList media on an AniList info screen. One lean `/anime/{id}` call per id, four in
     * flight at a time (the shared [rateLimiter]).
     */
    suspend fun resolveAniListIds(simklIds: List<Long>): Map<Long, Int> = coroutineScope {
        simklIds.distinct().map { id ->
            async {
                id to tryWithSuspend {
                    rateLimiter.withPermit {
                        Mapper.json.decodeFromString<IdsWrapper>(
                            client.get(
                                "${Simkl.API_URL}/anime/$id?client_id=${Simkl.CLIENT_ID}",
                                headers, cacheTime = 0,
                            ).text
                        ).ids?.anilist?.toIntOrNull()
                    }
                }
            }
        }.awaitAll()
            .mapNotNull { (k, v) -> v?.let { k to it } }
            .toMap()
    }

    /**
     * Simkl episode titles / synopses / thumbnails keyed by episode number, for the AniList watch
     * screen to merge alongside Kitsu / Anify / Jikan. Resolves the Simkl id from the AniList (or
     * MAL) id first. Only real episodes are included — specials share numbering with the main run.
     */
    suspend fun getEpisodesMeta(anilistId: Int?, malId: Int?): Map<String, Episode>? {
        val simklId = resolve(anilistId, malId)?.simklId ?: return null
        val eps = getEpisodes(simklId)
        if (eps.isEmpty()) return null
        return eps.asSequence()
            .filter { it.type == null || it.type.equals("episode", true) }
            .mapNotNull { ep ->
                val num = ep.episode?.toString() ?: return@mapNotNull null
                num to Episode(
                    number = num,
                    title = cleanText(ep.title),
                    desc = cleanText(ep.description),
                    thumb = episodeImageUrl(ep.img)?.let { FileUrl(it) },
                )
            }
            .toMap()
            .takeIf { it.isNotEmpty() }
    }
}
