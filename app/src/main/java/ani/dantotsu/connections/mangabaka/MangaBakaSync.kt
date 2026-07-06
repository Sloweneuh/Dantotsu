package ani.dantotsu.connections.mangabaka

import ani.dantotsu.Mapper
import ani.dantotsu.asyncMap
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One-way list synchronisation to MangaBaka.
 *
 * Whenever the user updates a manga entry on AniList (or MangaUpdates, when logged in), the same
 * state is pushed to their MangaBaka library. Nothing is ever pulled back — MangaBaka is a
 * destination only. MangaBaka is a manga tracker, so anime entries are ignored by callers.
 *
 * The matching MangaBaka series is discovered through the public source routes exposed by
 * [MangaBakaApi] (which require no auth). Only the `/v1/my/library` writes here require a token, so
 * the token gate ([isEnabled]) is applied to those operations only — never to the lookups.
 */
object MangaBakaSync {
    private const val API_URL = "https://api.mangabaka.org"
    private val JSON_MEDIA = "application/json".toMediaTypeOrNull()

    /**
     * True when a token is present and the user hasn't disabled list sync.
     * [force] bypasses the pref (used by explicit user actions like the list-compare screen), so it
     * only requires a token.
     */
    fun isEnabled(force: Boolean = false): Boolean =
        MangaBaka.token != null && (force || PrefManager.getVal(PrefName.MangaBakaListSyncEnabled))

    /**
     * Pushes an AniList manga entry to MangaBaka, resolving the series by AniList id (falling back
     * to the MyAnimeList id). [status] is an AniList status string (e.g. CURRENT, PLANNING).
     */
    suspend fun syncFromAnilist(
        anilistId: Int?,
        malId: Int?,
        status: String?,
        progressChapter: Int?,
        progressVolume: Int?,
        score: Int?,
        rereads: Int?,
        isPrivate: Boolean?,
        startDate: FuzzyDate?,
        finishDate: FuzzyDate?,
        force: Boolean = false,
    ): Boolean {
        if (!isEnabled(force)) return false
        val seriesId = MangaBakaApi.resolveFromAnilist(anilistId, malId) ?: return false
        return upsert(
            seriesId,
            LibraryEntryBody(
                state = mapAnilistStatus(status),
                progressChapter = progressChapter,
                progressVolume = progressVolume,
                rating = score?.takeIf { it > 0 },
                numberOfRereads = rereads?.takeIf { it > 0 },
                isPrivate = isPrivate,
                startDate = toIsoDate(startDate),
                finishDate = toIsoDate(finishDate),
            )
        )
    }

    /**
     * Pushes a MangaUpdates entry to MangaBaka, resolving the series by MangaUpdates id.
     * [muListId] is a MangaUpdates list index (0=Reading, 1=Planning, 2=Completed, 3=Dropped, 4=Paused).
     */
    suspend fun syncFromMangaUpdates(
        muSeriesId: Long?,
        muListId: Int?,
        progressChapter: Int?,
        progressVolume: Int?,
        force: Boolean = false,
    ): Boolean {
        if (!isEnabled(force)) return false
        val id = muSeriesId ?: return false
        val seriesId = MangaBakaApi.resolveSeriesId(MangaBakaApi.Source.MANGAUPDATES, id) ?: return false
        return upsert(
            seriesId,
            LibraryEntryBody(
                state = mapMangaUpdatesList(muListId),
                progressChapter = progressChapter,
                progressVolume = progressVolume,
            )
        )
    }

    /** Removes the AniList-linked manga from the MangaBaka library, if present. */
    suspend fun deleteFromAnilist(anilistId: Int?, malId: Int?): Boolean {
        if (!isEnabled()) return false
        val seriesId = MangaBakaApi.resolveFromAnilist(anilistId, malId) ?: return false
        return delete(seriesId)
    }

    /** Removes the MangaUpdates-linked series from the MangaBaka library, if present. */
    suspend fun deleteFromMangaUpdates(muSeriesId: Long?): Boolean {
        if (!isEnabled()) return false
        val id = muSeriesId ?: return false
        val seriesId = MangaBakaApi.resolveSeriesId(MangaBakaApi.Source.MANGAUPDATES, id) ?: return false
        return delete(seriesId)
    }

    /** Creates the library entry, falling back to a partial update when it already exists. */
    private suspend fun upsert(seriesId: Long, body: LibraryEntryBody): Boolean {
        val json = Mapper.json.encodeToString(body)
        // PATCH updates an existing entry (partial); if it doesn't exist yet, POST creates it.
        if (send(seriesId, "PATCH", json)) return true
        return send(seriesId, "POST", json)
    }

    private suspend fun delete(seriesId: Long): Boolean =
        tryWithSuspend {
            val request = authedRequest(seriesId).delete().build()
            val response = MangaBakaApi.execute(request)
            response.close()
            response.isSuccessful || response.code == 404
        } ?: false

    private suspend fun send(seriesId: Long, method: String, json: String): Boolean =
        tryWithSuspend {
            val request = authedRequest(seriesId)
                .method(method, json.toRequestBody(JSON_MEDIA))
                .build()
            val response = MangaBakaApi.execute(request)
            val ok = response.isSuccessful
            if (!ok) Logger.log("MangaBaka $method[$seriesId]: HTTP ${response.code}")
            response.close()
            ok
        } ?: false

    private fun authedRequest(seriesId: Long): Request.Builder {
        val token = MangaBaka.token ?: ""
        return Request.Builder()
            .url("$API_URL/v1/my/library/$seriesId")
            .addHeader("x-api-key", token)
    }

    /** Every possible MangaBaka library state (mirrors the `state` enum in the API docs). */
    val LIBRARY_STATES = listOf(
        "reading", "plan_to_read", "completed", "paused", "dropped", "rereading", "considering"
    )

    /** The user's whole library: the [entries] we could enumerate, plus the exact [counts] per state. */
    data class LibrarySnapshot(
        val entries: List<LibraryStateEntry>,
        val counts: Map<String, Int>,
    )

    /**
     * Enumerates the library one state at a time. Requires a token.
     *
     * The `/v1/my/library` list endpoint caps offset pagination at 1000 rows, so we page **per state**
     * (each state is well under the cap) rather than over the whole library. Each state's exact size
     * comes from `pagination.count` (immune to the row cap), so [LibrarySnapshot.counts] is reliable
     * for totals even if a huge state can't be fully enumerated.
     */
    suspend fun getLibrarySnapshot(): LibrarySnapshot {
        val token = MangaBaka.token ?: return LibrarySnapshot(emptyList(), emptyMap())
        val perState = LIBRARY_STATES.asyncMap { state -> state to fetchState(token, state) }
        return LibrarySnapshot(
            entries = perState.flatMap { it.second.first },
            counts = perState.associate { it.first to it.second.second },
        )
    }

    /** Pages through a single state; returns (entries fetched, total count reported by the server). */
    private suspend fun fetchState(token: String, state: String): Pair<List<LibraryStateEntry>, Int> {
        val entries = mutableListOf<LibraryStateEntry>()
        var count = 0
        var page = 1
        while (page <= 20) {
            val resp = tryWithSuspend {
                val request = Request.Builder()
                    .url("$API_URL/v1/my/library?state=$state&limit=100&page=$page")
                    .addHeader("x-api-key", token)
                    .get()
                    .build()
                val response = MangaBakaApi.execute(request)
                val body = response.body?.string()
                val ok = response.isSuccessful
                response.close()
                if (ok && body != null) Mapper.json.decodeFromString<LibraryListResponse>(body) else null
            } ?: break
            entries += resp.data
            count = resp.pagination?.count ?: count
            if (resp.data.isEmpty() || entries.size >= count) break
            page++
        }
        return entries to count
    }

    /** Removes a series from the library by its MangaBaka id. [force] bypasses the list-sync toggle. */
    suspend fun deleteById(seriesId: Long?, force: Boolean = false): Boolean {
        if (!isEnabled(force)) return false
        val id = seriesId ?: return false
        return delete(id)
    }

    /**
     * Current library state for a single series, or null when it isn't in the library (404) or on
     * error. Requires a token.
     */
    suspend fun getLibraryEntry(seriesId: Long): LibraryStateEntry? =
        tryWithSuspend {
            val request = authedRequest(seriesId).get().build()
            val response = MangaBakaApi.execute(request)
            val body = response.body?.string()
            val ok = response.isSuccessful
            response.close()
            if (ok && body != null) Mapper.json.decodeFromString<LibraryEntryResponse>(body).data else null
        }

    /** Maps an AniList status string to a MangaBaka library state. */
    internal fun mapAnilistStatus(status: String?): String? = when (status) {
        "CURRENT" -> "reading"
        "PLANNING" -> "plan_to_read"
        "COMPLETED" -> "completed"
        "DROPPED" -> "dropped"
        "PAUSED" -> "paused"
        "REPEATING" -> "rereading"
        else -> null
    }

    /** Maps a MangaUpdates list index to a MangaBaka library state. */
    internal fun mapMangaUpdatesList(listId: Int?): String? = when (listId) {
        0 -> "reading"
        1 -> "plan_to_read"
        2 -> "completed"
        3 -> "dropped"
        4 -> "paused"
        else -> null
    }

    /** Formats a complete [FuzzyDate] as an ISO-8601 date-time; null when the date is incomplete. */
    private fun toIsoDate(date: FuzzyDate?): String? {
        val y = date?.year ?: return null
        val m = date.month ?: return null
        val d = date.day ?: return null
        return "%04d-%02d-%02dT00:00:00.000Z".format(y, m, d)
    }

    /** Paginated library list response (`GET /v1/my/library`). Series info is not embedded. */
    @Serializable
    data class LibraryListResponse(
        val pagination: LibraryPagination? = null,
        val data: List<LibraryStateEntry> = emptyList(),
    )

    @Serializable
    data class LibraryPagination(
        val count: Int? = null,
        val next: String? = null,
    )

    /** Single library entry response (`GET /v1/my/library/{series_id}`). */
    @Serializable
    data class LibraryEntryResponse(
        val data: LibraryStateEntry? = null,
    )

    /**
     * The user-specific state of a series in their MangaBaka library. The embedded [series] (and
     * [seriesId]) are present on the list endpoint ("with series info"); unknown keys are ignored, so
     * this parses whether or not they show up at runtime.
     */
    @Serializable
    data class LibraryStateEntry(
        val state: String? = null,
        @SerialName("progress_chapter") val progressChapter: Int? = null,
        @SerialName("progress_volume") val progressVolume: Int? = null,
        val rating: Int? = null,
        @SerialName("series_id") val seriesId: Long? = null,
        val series: MangaBakaApi.Series? = null,
    ) {
        /** MangaBaka series id, taken from the top-level field or the embedded series object. */
        fun resolvedSeriesId(): Long? = seriesId ?: series?.id
        fun coverUrl(): String? = series?.cover?.thumbUrl()
        fun title(): String? = series?.title
    }

    @Serializable
    private data class LibraryEntryBody(
        val state: String? = null,
        @SerialName("progress_chapter") val progressChapter: Int? = null,
        @SerialName("progress_volume") val progressVolume: Int? = null,
        val rating: Int? = null,
        @SerialName("number_of_rereads") val numberOfRereads: Int? = null,
        @SerialName("is_private") val isPrivate: Boolean? = null,
        @SerialName("start_date") val startDate: String? = null,
        @SerialName("finish_date") val finishDate: String? = null,
    )
}
