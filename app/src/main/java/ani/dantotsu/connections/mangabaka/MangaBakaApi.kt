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
import okhttp3.HttpUrl.Companion.toHttpUrl
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
     * Builds the path segment for a `/v1/source/{source}/{id}` lookup. MangaUpdates is keyed by its
     * URL slug: a base-36 id **zero-padded to 7 characters** (e.g. numeric 2159512140 → `0zppu8c`).
     * `Long.toString(36)` drops the leading zero, so it must be padded back or MangaBaka returns 404.
     */
    private fun sourceIdSegment(source: Source, id: Long): String =
        if (source == Source.MANGAUPDATES) id.toString(36).padStart(7, '0') else id.toString()

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
        val idSegment = sourceIdSegment(source, id)
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

    // ---------------------------------------------------------------------------------------------
    // Series info routes (public, read-only) used by the MangaBaka info tab.
    // ---------------------------------------------------------------------------------------------

    /**
     * Fetches the full MangaBaka series for a Dantotsu [ani.dantotsu.media.Media] in a single request.
     * MangaUpdates-backed media (non-null [muSeriesId]) go through the MangaUpdates source route;
     * everything else is treated as AniList media and tries the AniList id, then MyAnimeList.
     *
     * The `/v1/source/{source}/{id}?with_series=true` route embeds the complete series object — it is
     * identical to what `GET /v1/series/{id}` returns — so this one call yields all the info-tab data
     * (and the resolved id) without a second request.
     */
    suspend fun getSeriesForMedia(muSeriesId: Long?, anilistId: Int?, malId: Int?): Series? {
        if (muSeriesId != null) return getSeriesFromSource(Source.MANGAUPDATES, muSeriesId)
        anilistId?.let { getSeriesFromSource(Source.ANILIST, it.toLong())?.let { s -> return s } }
        malId?.let { getSeriesFromSource(Source.MYANIMELIST, it.toLong())?.let { s -> return s } }
        return null
    }

    /**
     * Fetches the full series embedded in the `/v1/source/{source}/{id}?with_series=true` response.
     * Prefers the `active` match and follows `merged` series to their replacement. Public route.
     */
    suspend fun getSeriesFromSource(source: Source, id: Long): Series? = tryWithSuspend {
        val idSegment = sourceIdSegment(source, id)
        val request = Request.Builder()
            .url("$API_URL/v1/source/${source.path}/$idSegment?with_series=true")
            .get()
            .build()
        val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
        val body = response.body?.string()
        if (!response.isSuccessful || body.isNullOrBlank()) {
            Logger.log("MangaBaka source-series[${source.path}/$idSegment]: HTTP ${response.code}")
            return@tryWithSuspend null
        }
        val list = Mapper.json.decodeFromString<SeriesLookupResponse>(body).data?.series
        val match = list?.firstOrNull { it.state == "active" } ?: list?.firstOrNull()
        when {
            match == null -> null
            // Rare: the source points at a merged series — follow it to the current record.
            match.state == "merged" && match.mergedWith != null -> getSeries(match.mergedWith)
            else -> match
        }
    }

    /** In-memory cache of the genre slug → display label map (`/v1/genres`), fetched once. */
    private var genreLabels: Map<String, String>? = null

    /**
     * Returns the genre slug → display-name map from `GET /v1/genres` (e.g. `slice_of_life` →
     * "Slice of Life"). Cached after the first fetch; returns an empty map on failure.
     */
    suspend fun getGenreLabels(): Map<String, String> {
        genreLabels?.let { return it }
        val map = tryWithSuspend {
            val request = Request.Builder().url("$API_URL/v1/genres").get().build()
            val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) {
                Logger.log("MangaBaka genres: HTTP ${response.code}")
                return@tryWithSuspend null
            }
            Mapper.json.decodeFromString<GenresResponse>(body).data
                ?.mapNotNull { g -> val v = g.value; val l = g.label; if (v != null && l != null) v to l else null }
                ?.toMap()
        } ?: emptyMap()
        genreLabels = map
        return map
    }

    /** Fetches the processed series record via `GET /v1/series/{id}`. Public route — no auth. */
    suspend fun getSeries(seriesId: Long): Series? = tryWithSuspend {
        val request = Request.Builder()
            .url("$API_URL/v1/series/$seriesId")
            .get()
            .build()
        val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
        val body = response.body?.string()
        if (!response.isSuccessful || body.isNullOrBlank()) {
            Logger.log("MangaBaka series[$seriesId]: HTTP ${response.code}")
            return@tryWithSuspend null
        }
        Mapper.json.decodeFromString<SeriesResponse>(body).data
    }

    /**
     * Fetches cover images via `GET /v1/series/{id}/images`, filtered to the given [languages] and
     * cover [type] (defaults to front `volume` covers). Only the first page (up to 50) is returned —
     * that's plenty for the covers strip. Public route — no auth.
     */
    suspend fun getSeriesImages(
        seriesId: Long,
        languages: List<String>,
        type: String = "volume",
    ): List<SeriesImage> = tryWithSuspend {
        val urlBuilder = "$API_URL/v1/series/$seriesId/images".toHttpUrl().newBuilder()
            .addQueryParameter("type", type)
            .addQueryParameter("limit", "50")
        languages.forEach { urlBuilder.addQueryParameter("language", it) }
        val request = Request.Builder().url(urlBuilder.build()).get().build()
        val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
        val body = response.body?.string()
        if (!response.isSuccessful || body.isNullOrBlank()) {
            Logger.log("MangaBaka images[$seriesId]: HTTP ${response.code}")
            return@tryWithSuspend emptyList<SeriesImage>()
        }
        Mapper.json.decodeFromString<ImagesResponse>(body).data ?: emptyList()
    } ?: emptyList()

    /** Fetches similar series via `GET /v1/series/{id}/similar`. Public route — no auth. */
    suspend fun getSimilar(seriesId: Long): List<SimilarItem> = tryWithSuspend {
        val request = Request.Builder()
            .url("$API_URL/v1/series/$seriesId/similar")
            .get()
            .build()
        val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
        val body = response.body?.string()
        if (!response.isSuccessful || body.isNullOrBlank()) {
            Logger.log("MangaBaka similar[$seriesId]: HTTP ${response.code}")
            return@tryWithSuspend emptyList<SimilarItem>()
        }
        Mapper.json.decodeFromString<SimilarResponse>(body).data ?: emptyList()
    } ?: emptyList()

    // --- Series models (partial; unknown keys are ignored by Mapper.json) ---

    @Serializable
    data class SeriesResponse(val data: Series? = null)

    /** `/v1/source/{source}/{id}?with_series=true` embeds full [Series] objects under `data.series`. */
    @Serializable
    data class SeriesLookupResponse(val data: SeriesLookupData? = null)

    @Serializable
    data class SeriesLookupData(val series: List<Series>? = null)

    @Serializable
    data class GenresResponse(val data: List<GenreOption>? = null)

    @Serializable
    data class GenreOption(val value: String? = null, val label: String? = null)

    @Serializable
    data class Series(
        val id: Long,
        val state: String? = null,
        @SerialName("merged_with") val mergedWith: Long? = null,
        val title: String? = null,
        @SerialName("native_title") val nativeTitle: String? = null,
        @SerialName("romanized_title") val romanizedTitle: String? = null,
        val cover: CoverImage? = null,
        val authors: List<String>? = null,
        val artists: List<String>? = null,
        val description: String? = null,
        val year: Int? = null,
        val published: Published? = null,
        val status: String? = null,
        @SerialName("is_licensed") val isLicensed: Boolean? = null,
        @SerialName("has_anime") val hasAnime: Boolean? = null,
        val anime: AnimeInfo? = null,
        @SerialName("content_rating") val contentRating: String? = null,
        val type: String? = null,
        val rating: Double? = null,
        val popularity: Popularity? = null,
        @SerialName("final_volume") val finalVolume: String? = null,
        @SerialName("total_chapters") val totalChapters: String? = null,
        val titles: List<TitleEntry>? = null,
        val genres: List<String>? = null,
        @SerialName("tags_v2") val tags: List<TagEntry>? = null,
        val source: SeriesSource? = null,
    )

    @Serializable
    data class Published(
        @SerialName("start_date") val startDate: String? = null,
        @SerialName("end_date") val endDate: String? = null,
    )

    @Serializable
    data class AnimeInfo(val start: String? = null, val end: String? = null)

    @Serializable
    data class Popularity(val global: PopularityBucket? = null, val type: PopularityBucket? = null)

    @Serializable
    data class PopularityBucket(val current: Int? = null)

    @Serializable
    data class TitleEntry(
        val language: String? = null,
        val traits: List<String>? = null,
        val title: String? = null,
        @SerialName("is_primary") val isPrimary: Boolean? = null,
    )

    @Serializable
    data class TagEntry(
        val id: Int? = null,
        val name: String? = null,
        @SerialName("is_spoiler") val isSpoiler: Boolean? = null,
        @SerialName("is_genre") val isGenre: Boolean? = null,
        val weight: String? = null,
    )

    /** Cross-source ids. AniList/MAL ids are numeric; MangaUpdates ids are base-36 URL slugs. */
    @Serializable
    data class SeriesSource(
        val anilist: NumericSourceRef? = null,
        @SerialName("my_anime_list") val myAnimeList: NumericSourceRef? = null,
        @SerialName("manga_updates") val mangaUpdates: StringSourceRef? = null,
    )

    @Serializable
    data class NumericSourceRef(val id: Int? = null)

    @Serializable
    data class StringSourceRef(val id: String? = null) {
        /** MangaUpdates ids are base-36 URL slugs (e.g. "3qzxncc"); decode to the numeric series id. */
        fun toMuSeriesId(): Long? = id?.toLongOrNull(36) ?: id?.toLongOrNull()
    }

    // --- Image models ---

    @Serializable
    data class ImagesResponse(
        val data: List<SeriesImage>? = null,
        @SerialName("available_languages") val availableLanguages: List<String>? = null,
    )

    @Serializable
    data class SeriesImage(
        val id: Long? = null,
        val index: String? = null,
        val type: String? = null,
        val language: String? = null,
        val image: CoverImage? = null,
    )

    @Serializable
    data class CoverImage(
        val raw: RawImage? = null,
        @SerialName("x150") val x150: SizedImage? = null,
        @SerialName("x250") val x250: SizedImage? = null,
        @SerialName("x350") val x350: SizedImage? = null,
    ) {
        /** Best URL for a list/grid thumbnail (medium size, @2x), falling back to the raw image. */
        fun thumbUrl(): String? = x250?.x2 ?: x350?.x2 ?: x150?.x2 ?: raw?.url

        /** Best URL for a full-screen view. */
        fun fullUrl(): String? = raw?.url ?: x350?.x3 ?: x350?.x2 ?: x250?.x2
    }

    @Serializable
    data class RawImage(val url: String? = null, val width: Int? = null, val height: Int? = null)

    @Serializable
    data class SizedImage(
        @SerialName("x1") val x1: String? = null,
        @SerialName("x2") val x2: String? = null,
        @SerialName("x3") val x3: String? = null,
    )

    // --- Similar models ---

    @Serializable
    data class SimilarResponse(val data: List<SimilarItem>? = null)

    @Serializable
    data class SimilarItem(
        val score: Double? = null,
        val series: SimilarSeries? = null,
    )

    @Serializable
    data class SimilarSeries(
        val id: Long,
        val state: String? = null,
        val title: String? = null,
        val cover: CoverImage? = null,
        val type: String? = null,
        val source: SeriesSource? = null,
    )
}
