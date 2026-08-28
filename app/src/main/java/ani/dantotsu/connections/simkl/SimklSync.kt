package ani.dantotsu.connections.simkl

import ani.dantotsu.Mapper
import ani.dantotsu.client
import ani.dantotsu.okHttpClient
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One-way **anime** list synchronisation to Simkl.
 *
 * Simkl accepts MyAnimeList / AniList ids directly in its sync payloads, so — unlike Kitsu or
 * MangaBaka — there is no id-resolution step. Nothing is pulled back; Simkl is a destination only.
 *
 *  - status/list → `POST /sync/add-to-list`
 *  - watched episodes → `POST /sync/history` (episodes shorthand)
 *  - score → `POST /sync/ratings`
 *  - removal → `POST /sync/history/remove`
 */
object SimklSync {
    private val JSON = "application/json".toMediaTypeOrNull()

    fun isEnabled(force: Boolean = false): Boolean =
        Simkl.token != null && Simkl.isConfigured() &&
            (force || PrefManager.getVal(PrefName.SimklListSyncEnabled))

    // ---- pushes ----

    /**
     * [status] is an AniList status string; [progress] is watched-episode count. [simklId] is the
     * Simkl record id when the caller already knows it (the compare screen does); otherwise it is
     * resolved here via [SimklApi].
     */
    suspend fun syncFromAnilist(
        anilistId: Int?,
        malId: Int?,
        status: String?,
        progress: Int?,
        score: Int?,
        simklId: Long? = null,
        force: Boolean = false,
    ): Boolean {
        if (!isEnabled(force)) return false
        if (anilistId == null && malId == null) return false
        val simklStatus = mapAnilistStatus(status) ?: return false
        val resolvedSimkl = simklId ?: SimklApi.resolve(anilistId, malId)?.simklId
        // Prefer the Simkl id everywhere once known — it's unambiguous, whereas a MAL/AniList id can
        // resolve to a season Simkl folds several AniList entries into.
        val ids = Ids(simkl = resolvedSimkl, mal = malId.takeIf { resolvedSimkl == null }, anilist = anilistId.takeIf { resolvedSimkl == null })

        // Episode history first: marking episodes watched makes Simkl flip the show to "watching",
        // so add-to-list runs *after* to set the real status (e.g. hold, completed). add-to-list is
        // also what decides whether the title exists on Simkl at all — the rest is best-effort.
        if ((progress ?: 0) > 0) {
            val eps = (1..progress!!).map { EpisodeRef(it) }
            post(
                "/sync/history",
                SyncBody(shows = listOf(AnimeItem(ids = ids, seasons = listOf(SeasonRef(1, eps))))),
            )
        }
        val onSimkl = post(
            "/sync/add-to-list",
            SyncBody(anime = listOf(AnimeItem(to = simklStatus, ids = ids))),
            titleCheck = true,
        )
        if (!onSimkl) return false

        val rating = score?.takeIf { it > 0 }?.let { (it + 5) / 10 }?.coerceIn(1, 10)
        if (rating != null) {
            post("/sync/ratings", SyncBody(anime = listOf(AnimeItem(ids = ids, rating = rating))))
        }
        return true
    }

    suspend fun deleteFromAnilist(
        anilistId: Int?,
        malId: Int?,
        simklId: Long? = null,
        force: Boolean = false,
    ): Boolean {
        if (!isEnabled(force)) return false
        if (anilistId == null && malId == null && simklId == null) return false
        val resolved = simklId ?: SimklApi.resolve(anilistId, malId)?.simklId
        val ids = Ids(simkl = resolved, mal = malId.takeIf { resolved == null }, anilist = anilistId.takeIf { resolved == null })
        return post("/sync/history/remove", SyncBody(shows = listOf(AnimeItem(ids = ids))))
    }

    private suspend fun post(path: String, body: SyncBody, titleCheck: Boolean = false): Boolean =
        tryWithSuspend {
            val token = Simkl.token ?: return@tryWithSuspend false
            val json = Mapper.json.encodeToString(body)
            val request = Request.Builder()
                .url("${Simkl.API_URL}$path?client_id=${Simkl.CLIENT_ID}")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("simkl-api-key", Simkl.CLIENT_ID)
                .post(json.toRequestBody(JSON))
                .build()
            val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
            val code = response.code
            val respBody = response.body?.string()
            response.close()
            if (code !in 200..299) {
                Logger.log("Simkl POST $path: HTTP $code")
                return@tryWithSuspend false
            }
            // Simkl answers 200 even when it doesn't have the title — the id lands in `not_found`
            // under a title bucket (episodes there are just episodes it couldn't match on a title it
            // *does* have, which is fine). Only the add-to-list call acts on this.
            if (titleCheck) {
                val notFound = respBody?.let {
                    runCatching { Mapper.json.decodeFromString<SyncResult>(it).notFound }.getOrNull()
                }
                if (notFound?.titleMissing() == true) {
                    Logger.log("Simkl POST $path: title not on Simkl")
                    return@tryWithSuspend false
                }
            }
            true
        } ?: false

    @Serializable
    private data class SyncResult(
        @SerialName("not_found") val notFound: NotFound? = null,
    )

    @Serializable
    private data class NotFound(
        val shows: List<kotlinx.serialization.json.JsonElement>? = null,
        val movies: List<kotlinx.serialization.json.JsonElement>? = null,
        val anime: List<kotlinx.serialization.json.JsonElement>? = null,
    ) {
        fun titleMissing(): Boolean = !shows.isNullOrEmpty() || !movies.isNullOrEmpty() ||
            !anime.isNullOrEmpty()
    }

    // ---- snapshot for the compare screen ----

    data class LibraryEntry(
        val status: String?,
        val watchedEpisodes: Int,
        val totalEpisodes: Int?,
        val userRating: Int?,
        val anilistId: Int?,
        val malId: Int?,
        val simklId: Long?,
        val title: String?,
        val coverUrl: String?,
    )

    /** The user's whole Simkl anime library, flattened for the comparison. */
    suspend fun getLibrary(): List<LibraryEntry> {
        val header = Simkl.authHeader ?: return emptyList()
        val res = tryWithSuspend {
            client.get(
                "${Simkl.API_URL}/sync/all-items/anime/all?extended=full&client_id=${Simkl.CLIENT_ID}",
                header,
            ).parsed<AllItemsResponse>()
        } ?: return emptyList()
        return res.anime.orEmpty().mapNotNull { item ->
            val show = item.show ?: return@mapNotNull null
            LibraryEntry(
                status = item.status,
                watchedEpisodes = item.watchedEpisodesCount ?: 0,
                totalEpisodes = item.totalEpisodesCount,
                userRating = item.userRating,
                anilistId = show.ids?.anilist?.toIntOrNull(),
                malId = show.ids?.mal?.toIntOrNull(),
                simklId = show.ids?.simkl,
                title = show.title,
                coverUrl = show.poster?.let { "https://simkl.in/posters/${it}_m.webp" },
            )
        }
    }

    // ---- mapping helpers ----

    fun toCanon(status: String?): String = when (status) {
        "watching" -> "CURRENT"
        "plantowatch" -> "PLANNING"
        "completed" -> "COMPLETED"
        "hold" -> "PAUSED"
        "dropped" -> "DROPPED"
        else -> "CURRENT"
    }

    fun mapAnilistStatus(status: String?): String? = when (status) {
        "CURRENT", "REPEATING" -> "watching"
        "PLANNING" -> "plantowatch"
        "COMPLETED" -> "completed"
        "PAUSED" -> "hold"
        "DROPPED" -> "dropped"
        else -> null
    }

    /** Simkl rating (1..10) back to the 0..100 scale for diff display. */
    fun ratingTo100(r: Int?): Int? = r?.takeIf { it > 0 }?.times(10)

    // ---- models ----

    @Serializable
    data class Ids(
        val simkl: Long? = null,
        val mal: Int? = null,
        val anilist: Int? = null,
    )

    @Serializable
    data class EpisodeRef(val number: Int)

    @Serializable
    data class SeasonRef(val number: Int, val episodes: List<EpisodeRef>)

    @Serializable
    data class AnimeItem(
        val to: String? = null,
        val rating: Int? = null,
        val ids: Ids,
        val seasons: List<SeasonRef>? = null,
    )

    /**
     * Sync write body. `/sync/add-to-list` and `/sync/ratings` take anime under `anime`;
     * `/sync/history` and `/sync/history/remove` take it under `shows`. Only one is ever set;
     * the null one is omitted (explicitNulls = false).
     */
    @Serializable
    data class SyncBody(
        val anime: List<AnimeItem>? = null,
        val shows: List<AnimeItem>? = null,
    )

    @Serializable
    data class AllItemsResponse(val anime: List<AllItem>? = null)

    @Serializable
    data class AllItem(
        val status: String? = null,
        @SerialName("watched_episodes_count") val watchedEpisodesCount: Int? = null,
        @SerialName("total_episodes_count") val totalEpisodesCount: Int? = null,
        @SerialName("user_rating") val userRating: Int? = null,
        val show: Show? = null,
    )

    @Serializable
    data class Show(
        val title: String? = null,
        val poster: String? = null,
        val ids: ShowIds? = null,
    )

    @Serializable
    data class ShowIds(
        val simkl: Long? = null,
        val mal: String? = null,
        val anilist: String? = null,
    )
}
