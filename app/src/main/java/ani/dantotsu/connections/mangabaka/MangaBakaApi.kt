package ani.dantotsu.connections.mangabaka

import ani.dantotsu.Mapper
import ani.dantotsu.okHttpClient
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request

/**
 * Public, read-only MangaBaka endpoints.
 *
 * None of these routes require authentication, so they must NOT be gated behind [MangaBaka.token] —
 * they work for logged-out users too. This is the home for the `source` lookup routes and any
 * `series`-related routes added later. Authenticated, per-user operations live in [MangaBakaSync].
 */
object MangaBakaApi {
    const val API_URL = "https://api.mangabaka.org"
    private const val CACHE_PREFIX = "mangabaka_series_"
    private const val AL_CACHE_PREFIX = "mangabaka_al_"
    private const val MU_CACHE_PREFIX = "mangabaka_mu_"

    /** External source path segments understood by the `/v1/source/{source}` routes. */
    enum class Source(val path: String) {
        ANILIST("anilist"),
        MYANIMELIST("my-anime-list"),
        MANGAUPDATES("manga-updates"),
        KITSU("kitsu"),
        ANIMEPLANET("anime-planet"),
    }

    /** In-memory cache of source lookups that returned no match, to avoid re-querying. */
    private val negativeCache = HashSet<String>()

    /**
     * Resolves a MangaBaka series id from an external source id via `/v1/source/{source}/{id}`.
     * Public route — sends no token. Follows merged series to their new id and caches the result.
     */
    suspend fun resolveSeriesId(source: Source, id: Long): Long? {
        val cacheKey = "$CACHE_PREFIX${source.path}_$id"
        val cached = PrefManager.getCustomVal(cacheKey, 0L)
        if (cached > 0L) return cached
        if (cacheKey in negativeCache) return null

        val match = lookupSeries(source, id)
        val resolved = when {
            match == null -> null
            match.state == "merged" && match.mergedWith != null -> match.mergedWith
            else -> match.id
        }

        if (resolved != null) {
            PrefManager.setCustomVal(cacheKey, resolved)
        } else {
            negativeCache.add(cacheKey)
        }
        return resolved
    }

    /**
     * Resolves a MangaBaka series id from an AniList id, falling back to the MyAnimeList id.
     * Public route — no auth required.
     */
    suspend fun resolveFromAnilist(anilistId: Int?, malId: Int?): Long? {
        anilistId?.let { resolveSeriesId(Source.ANILIST, it.toLong())?.let { id -> return id } }
        malId?.let { resolveSeriesId(Source.MYANIMELIST, it.toLong())?.let { id -> return id } }
        return null
    }

    /**
     * Resolves the AniList id for a MangaUpdates series through MangaBaka's cross-source mapping.
     * Public route — no auth required. Returns null when MangaBaka has no matching series or the
     * series isn't linked to AniList.
     */
    suspend fun getAnilistIdFromMangaUpdates(muSeriesId: Long): Int? {
        val cacheKey = "$AL_CACHE_PREFIX${Source.MANGAUPDATES.path}_$muSeriesId"
        val cached = PrefManager.getCustomVal(cacheKey, 0)
        if (cached > 0) return cached
        if (cacheKey in negativeCache) return null

        val anilistId = lookupSeries(Source.MANGAUPDATES, muSeriesId)?.source?.anilist?.id
        if (anilistId != null && anilistId > 0) {
            PrefManager.setCustomVal(cacheKey, anilistId)
        } else {
            negativeCache.add(cacheKey)
        }
        return anilistId
    }

    /**
     * Resolves the MangaUpdates series id for an AniList id, falling back to the MyAnimeList id,
     * through MangaBaka's cross-source mapping. Public route — no auth required. Returns null
     * when MangaBaka has no matching series or the series isn't linked to MangaUpdates.
     */
    suspend fun getMangaUpdatesIdFromAnilist(anilistId: Int?, malId: Int? = null): Long? {
        anilistId?.let { resolveMangaUpdatesId(Source.ANILIST, it.toLong())?.let { id -> return id } }
        malId?.let { resolveMangaUpdatesId(Source.MYANIMELIST, it.toLong())?.let { id -> return id } }
        return null
    }

    private suspend fun resolveMangaUpdatesId(source: Source, id: Long): Long? {
        val cacheKey = "$MU_CACHE_PREFIX${source.path}_$id"
        val cached = PrefManager.getCustomVal(cacheKey, 0L)
        if (cached > 0L) return cached
        if (cacheKey in negativeCache) return null

        val muId = lookupSeries(source, id)?.source?.mangaUpdates?.id?.toLong()
        if (muId != null && muId > 0) {
            PrefManager.setCustomVal(cacheKey, muId)
        } else {
            negativeCache.add(cacheKey)
        }
        return muId
    }

    /**
     * Fetches the best-matching MangaBaka series for an external source id.
     * MangaUpdates is keyed by its URL slug (the numeric id in base-36); other sources use the
     * plain numeric id. Prefers the `active` series when several are returned.
     */
    private suspend fun lookupSeries(source: Source, id: Long): SourceSeries? = tryWithSuspend {
        val idSegment = if (source == Source.MANGAUPDATES) id.toString(36) else id.toString()
        val request = Request.Builder()
            .url("$API_URL/v1/source/${source.path}/$idSegment?with_series=true")
            .get()
            .build()
        val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
        val body = response.body?.string()
        if (!response.isSuccessful || body.isNullOrBlank()) {
            Logger.log("MangaBaka lookup[${source.path}/$idSegment]: HTTP ${response.code}")
            return@tryWithSuspend null
        }
        val series = Mapper.json.decodeFromString<SourceLookupResponse>(body).data?.series
        series?.firstOrNull { it.state == "active" } ?: series?.firstOrNull()
    }

    @Serializable
    data class SourceLookupResponse(val data: SourceData? = null)

    @Serializable
    data class SourceData(val series: List<SourceSeries>? = null)

    @Serializable
    data class SourceSeries(
        val id: Long,
        val state: String? = null,
        @SerialName("merged_with") val mergedWith: Long? = null,
        val source: SourceIds? = null,
    )

    @Serializable
    data class SourceIds(
        val anilist: SourceRef? = null,
        @SerialName("my_anime_list") val myAnimeList: SourceRef? = null,
        @SerialName("manga_updates") val mangaUpdates: SourceRef? = null,
    )

    @Serializable
    data class SourceRef(val id: Int? = null)
}
