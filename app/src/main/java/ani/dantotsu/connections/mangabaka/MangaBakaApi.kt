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

        // MangaUpdates' source route is keyed by the URL slug (e.g. "6bkr9t5"), which is the
        // numeric series id encoded in base-36; every other source uses the plain numeric id.
        val idSegment = if (source == Source.MANGAUPDATES) id.toString(36) else id.toString()

        val resolved = tryWithSuspend {
            val request = Request.Builder()
                .url("$API_URL/v1/source/${source.path}/$idSegment?with_series=true")
                .get()
                .build()
            val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) {
                Logger.log("MangaBaka resolve[${source.path}/$id]: HTTP ${response.code}")
                return@tryWithSuspend null
            }
            val series = Mapper.json.decodeFromString<SourceLookupResponse>(body).data?.series
            val match = series?.firstOrNull { it.state == "active" } ?: series?.firstOrNull()
            when {
                match == null -> null
                match.state == "merged" && match.mergedWith != null -> match.mergedWith
                else -> match.id
            }
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

    @Serializable
    data class SourceLookupResponse(val data: SourceData? = null)

    @Serializable
    data class SourceData(val series: List<SourceSeries>? = null)

    @Serializable
    data class SourceSeries(
        val id: Long,
        val state: String? = null,
        @SerialName("merged_with") val mergedWith: Long? = null,
    )
}
