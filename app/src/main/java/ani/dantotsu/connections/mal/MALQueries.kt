package ani.dantotsu.connections.mal

import ani.dantotsu.client
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.tryWithSuspend
import kotlinx.serialization.Serializable

class MALQueries {
    private val apiUrl = "https://api.myanimelist.net/v2"
    private val authHeader: Map<String, String>?
        get() {
            return mapOf("Authorization" to "Bearer ${MAL.token ?: return null}")
        }

    @Serializable
    data class MalUser(
        val id: Int,
        val name: String,
        val picture: String?,
    )

    suspend fun getUserData(): Boolean {
        val res = tryWithSuspend {
            client.get(
                "$apiUrl/users/@me",
                authHeader ?: return@tryWithSuspend null
            ).parsed<MalUser>()
        } ?: return false
        MAL.userid = res.id
        MAL.username = res.name
        MAL.avatar = res.picture

        return true
    }

    suspend fun editList(
        idMAL: Int?,
        isAnime: Boolean,
        progress: Int?,
        score: Int?,
        status: String,
        rewatch: Int? = null,
        start: FuzzyDate? = null,
        end: FuzzyDate? = null
    ) {
        if (idMAL == null) return
        val data = mutableMapOf("status" to convertStatus(isAnime, status))
        if (progress != null)
            data[if (isAnime) "num_watched_episodes" else "num_chapters_read"] = progress.toString()
        data[if (isAnime) "is_rewatching" else "is_rereading"] = (status == "REPEATING").toString()
        if (score != null)
            data["score"] = score.div(10).toString()
        if (rewatch != null)
            data[if (isAnime) "num_times_rewatched" else "num_times_reread"] = rewatch.toString()
        if (start != null)
            data["start_date"] = start.toMALString()
        if (end != null)
            data["finish_date"] = end.toMALString()
        tryWithSuspend {
            client.put(
                "$apiUrl/${if (isAnime) "anime" else "manga"}/$idMAL/my_list_status",
                authHeader ?: return@tryWithSuspend null,
                data = data,
            )
        }
    }

    suspend fun deleteList(isAnime: Boolean, idMAL: Int?) {
        if (idMAL == null) return
        tryWithSuspend {
            client.delete(
                "$apiUrl/${if (isAnime) "anime" else "manga"}/$idMAL/my_list_status",
                authHeader ?: return@tryWithSuspend null
            )
        }
    }

    private fun convertStatus(isAnime: Boolean, status: String): String {
        return when (status) {
            "PLANNING" -> if (isAnime) "plan_to_watch" else "plan_to_read"
            "COMPLETED" -> "completed"
            "PAUSED" -> "on_hold"
            "DROPPED" -> "dropped"
            "CURRENT" -> if (isAnime) "watching" else "reading"
            else -> if (isAnime) "watching" else "reading"

        }
    }

    suspend fun getAnimeDetails(malId: Int): MALAnimeResponse? {
        return tryWithSuspend {
            val fields = "id,title,main_picture,alternative_titles,start_date,end_date,synopsis," +
                    "mean,rank,popularity,num_list_users,num_scoring_users,nsfw,created_at,updated_at," +
                    "media_type,status,genres,num_episodes,start_season,broadcast,source," +
                    "average_episode_duration,rating,pictures,background,related_anime,related_manga," +
                    "recommendations,studios,statistics"

            client.get(
                "$apiUrl/anime/$malId?fields=$fields",
                authHeader ?: emptyMap()
            ).parsed<MALAnimeResponse>()
        }
    }

    suspend fun getMangaDetails(malId: Int): MALMangaResponse? {
        return tryWithSuspend {
            val fields = "id,title,main_picture,alternative_titles,start_date,end_date,synopsis," +
                    "mean,rank,popularity,num_list_users,num_scoring_users," +
                    "media_type,status,genres,num_volumes,num_chapters,authors{first_name,last_name}," +
                    "recommendations,serialization{name}"

            client.get(
                "$apiUrl/manga/$malId?fields=$fields",
                authHeader ?: emptyMap()
            ).parsed<MALMangaResponse>()
        }
    }

}