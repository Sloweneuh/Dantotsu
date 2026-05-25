package ani.dantotsu.others

import ani.dantotsu.Mapper
import ani.dantotsu.client
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object Jikan {

    const val apiUrl = "https://api.jikan.moe/v4/"

    suspend inline fun <reified T : Any> query(endpoint: String): T? {
        return tryWithSuspend { client.get("$apiUrl$endpoint").parsed() }
    }

    suspend fun getEpisodes(malId: Int): Map<String, Episode> {
        var hasNextPage = true
        var page = 0
        val eps = mutableMapOf<String, Episode>()
        while (hasNextPage) {
            page++
            val res = query<EpisodeResponse>("anime/$malId/episodes?page=$page")
            res?.data?.forEach {
                val ep = it.malID.toString()
                eps[ep] = Episode(
                    ep, title = it.title,
                    //Personal revenge with 34566 :prayge:
                    filler = if (malId != 34566) it.filler else true,
                )
            }
            hasNextPage = res?.pagination?.hasNextPage == true
        }
        return eps
    }

    suspend fun getAnimeReviews(malId: Int, page: Int = 1): ReviewsResponse? =
        fetchReviews("anime/$malId/reviews?page=$page")

    suspend fun getMangaReviews(malId: Int, page: Int = 1): ReviewsResponse? =
        fetchReviews("manga/$malId/reviews?page=$page")

    private suspend fun fetchReviews(endpoint: String): ReviewsResponse? = tryWithSuspend {
        val response = client.get("$apiUrl$endpoint")
        val body = response.text
        Logger.log("Jikan fetchReviews: $endpoint code=${response.code} body=${body.take(500)}")
        if (!response.isSuccessful) return@tryWithSuspend null
        Mapper.parse<ReviewsResponse>(body)
    }

    @Serializable
    data class EpisodeResponse(
        val pagination: Pagination? = null,
        val data: List<Datum>? = null
    ) {
        @Serializable
        data class Datum(
            @SerialName("mal_id")
            val malID: Int,
            val title: String? = null,
            val filler: Boolean,
            //            val recap: Boolean,
        )

        @Serializable
        data class Pagination(
            @SerialName("has_next_page")
            val hasNextPage: Boolean? = null
        )
    }

    @Serializable
    data class ReviewsResponse(
        val pagination: ReviewPagination? = null,
        val data: List<ReviewData>? = null
    ) {
        @Serializable
        data class ReviewPagination(
            @SerialName("last_visible_page") val lastVisiblePage: Int? = null,
            @SerialName("has_next_page") val hasNextPage: Boolean? = null
        )

        @Serializable
        data class ReviewData(
            @SerialName("mal_id") val malId: Int = 0,
            val url: String? = null,
            val date: String? = null,
            val review: String? = null,
            val score: Int? = null,
            @SerialName("is_spoiler") val isSpoiler: Boolean? = null,
            @SerialName("is_preliminary") val isPreliminary: Boolean? = null,
            val user: UserData? = null
        ) {
            @Serializable
            data class UserData(
                val username: String? = null,
                val url: String? = null,
                val images: UserImages? = null
            ) {
                @Serializable
                data class UserImages(
                    val jpg: ImageFile? = null,
                    val webp: ImageFile? = null
                ) {
                    @Serializable
                    data class ImageFile(
                        @SerialName("image_url") val imageUrl: String? = null
                    )
                }
            }
        }
    }

}

data class MALReview(
    val id: Int,
    val username: String?,
    val avatarUrl: String?,
    val dateUnix: Int?,
    val score: Int?,
    val review: String?,
    val url: String?
) : java.io.Serializable

fun Jikan.ReviewsResponse.ReviewData.toMALReview(): MALReview {
    val unix = try {
        if (date.isNullOrBlank()) null
        else java.time.OffsetDateTime.parse(date).toEpochSecond().toInt()
    } catch (_: Exception) { null }
    val avatar = user?.images?.webp?.imageUrl ?: user?.images?.jpg?.imageUrl
    return MALReview(
        id = malId,
        username = user?.username,
        avatarUrl = avatar,
        dateUnix = unix,
        score = score,
        review = review,
        url = url
    )
}


