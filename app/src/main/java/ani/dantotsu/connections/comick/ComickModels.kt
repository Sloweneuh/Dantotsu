package ani.dantotsu.connections.comick

import java.io.Serializable
import java.time.Instant
import java.time.format.DateTimeParseException
import com.google.gson.annotations.SerializedName
import com.google.gson.JsonElement

data class ComickResponse(
    val comic: ComickComic?,
    val firstChap: ComickFirstChapter?
) : Serializable

data class ComickComic(
    val id: Int?,
    val title: String?,
    val desc: String?,
    val parsed: String?,
    val slug: String?,
    val country: String?,
    val status: Int?,
    val year: Int?,
    val bayesian_rating: String?,
    val rating_count: Int?,
    val follow_rank: Int?,
    val user_follow_count: Int?,
    val last_chapter: Double?,
    val chapter_count: Int?,
    val demographic: Int?,
    val final_chapter: String?,
    val final_volume: String?,
    val has_anime: Boolean?,
    val anime: ComickAnimeInfo?,
    val mu_comics: ComickMuComics?,
    val translation_completed: Boolean?,
    val content_rating: String?,
    val md_titles: List<ComickAlternativeTitle>?,
    val md_comic_md_genres: List<ComickGenre>?,
    val md_covers: List<ComickCover>?,
    val links: ComickLinks?,
    val recommendations: List<ComickRecommendation>?,
    val reviews: List<ComickRawReview>?
) : Serializable

data class ComickFirstChapter(
    val chap: String?,
    val hid: String?,
    val lang: String?,
    val vol: String?
) : Serializable

data class ComickAnimeInfo(
    val start: String?,
    val end: String?
) : Serializable

data class ComickMuComics(
    val mu_comic_categories: List<ComickCategory>?
) : Serializable

data class ComickCategory(
    val mu_categories: ComickCategoryInfo?,
    val positive_vote: Int?,
    val negative_vote: Int?
) : Serializable

data class ComickCategoryInfo(
    val title: String?,
    val slug: String?
) : Serializable

data class ComickAlternativeTitle(
    val title: String?,
    val lang: String?
) : Serializable

data class ComickGenre(
    val md_genres: ComickGenreInfo?
) : Serializable

data class ComickGenreInfo(
    val name: String?,
    val type: String?,
    val slug: String?,
    val group: String?
) : Serializable

data class ComickLinks(
    val al: String?,
    val ap: String?,
    val bw: String?,
    val kt: String?,
    val mu: String?,
    val mal: String?,
    val raw: String?,
    val engtl: String?
) : Serializable

data class ComickSearchResult(
    val id: Int?,
    val slug: String?,
    val title: String?,
    val country: String?,
    val rating: String?,
    val bayesian_rating: String?,
    val status: Int?,
    val last_chapter: Double?,
    val demographic: Int?,
    val year: Int?
) : Serializable

data class ComickRecommendation(
    val up: Int?,
    val down: Int?,
    val total: Int?,
    val relates: ComickRecommendedComic?
) : Serializable

data class ComickRecommendedComic(
    val title: String?,
    val slug: String?,
    val hid: String?,
    val md_covers: List<ComickCover>?
) : Serializable

data class ComickCover(
    val vol: String?,
    val w: Int?,
    val h: Int?,
    val b2key: String?
) : Serializable

data class ComickTraits(
    val username: String?,
    val email: String?
) : Serializable

data class ComickIdentity(
    val traits: ComickTraits?
) : Serializable

data class ComickRawReview(
    val id: String?,
    val content: String?,
    val rating: Int?,
    @SerializedName("created_at") val created_at: String?,
    val identities: JsonElement?
) : Serializable

data class ComickReview(
    val id: String?,
    val username: String?,
    val email: String?,
    val content: String?,
    val rating: Int?,
    val createdAt: Int?
) : Serializable

fun ComickRawReview.toComickReview(): ComickReview {
    val unixSeconds = try {
        if (created_at.isNullOrBlank()) null else Instant.parse(created_at).epochSecond.toInt()
    } catch (e: DateTimeParseException) {
        null
    }

    var email: String? = null
    var username: String? = null
    try {
        identities?.let { elem ->
            if (elem.isJsonArray) {
                val arr = elem.asJsonArray
                if (arr.size() > 0 && arr[0].isJsonObject) {
                    val first = arr[0].asJsonObject
                    if (first.has("traits") && first.get("traits").isJsonObject) {
                        val traits = first.getAsJsonObject("traits")
                        if (traits.has("email")) email = traits.get("email").asString
                        if (traits.has("username")) username = traits.get("username").asString
                    }
                }
            } else if (elem.isJsonObject) {
                val obj = elem.asJsonObject
                if (obj.has("traits") && obj.get("traits").isJsonObject) {
                    val traits = obj.getAsJsonObject("traits")
                    if (traits.has("email")) email = traits.get("email").asString
                    if (traits.has("username")) username = traits.get("username").asString
                }
            }
        }
    } catch (_: Exception) {}

    return ComickReview(
        id = id ?: java.util.UUID.randomUUID().toString(),
        username = username,
        email = email,
        content = content,
        rating = rating,
        createdAt = unixSeconds
    )
}

