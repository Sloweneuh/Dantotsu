package ani.dantotsu.connections.mal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MALAnimeResponse(
    val id: Int,
    val title: String,
    @SerialName("main_picture") val mainPicture: MALPicture? = null,
    @SerialName("alternative_titles") val alternativeTitles: MALAlternativeTitles? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val synopsis: String? = null,
    val mean: Double? = null,
    val rank: Int? = null,
    val popularity: Int? = null,
    @SerialName("num_list_users") val numListUsers: Int? = null,
    @SerialName("num_scoring_users") val numScoringUsers: Int? = null,
    val nsfw: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val status: String? = null,
    val genres: List<MALGenre> = emptyList(),
    @SerialName("num_episodes") val numEpisodes: Int? = null,
    @SerialName("start_season") val startSeason: MALSeason? = null,
    val broadcast: MALBroadcast? = null,
    val source: String? = null,
    @SerialName("average_episode_duration") val averageEpisodeDuration: Int? = null,
    val rating: String? = null,
    val pictures: List<MALPicture> = emptyList(),
    val background: String? = null,
    @SerialName("related_anime") val relatedAnime: List<MALRelation> = emptyList(),
    @SerialName("related_manga") val relatedManga: List<MALRelation> = emptyList(),
    val recommendations: List<MALRecommendation> = emptyList(),
    val studios: List<MALStudio> = emptyList(),
    val statistics: MALStatistics? = null
)

@Serializable
data class MALMangaResponse(
    val id: Int,
    val title: String,
    @SerialName("main_picture") val mainPicture: MALPicture? = null,
    @SerialName("alternative_titles") val alternativeTitles: MALAlternativeTitles? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val synopsis: String? = null,
    val mean: Double? = null,
    val rank: Int? = null,
    val popularity: Int? = null,
    @SerialName("num_list_users") val numListUsers: Int? = null,
    @SerialName("num_scoring_users") val numScoringUsers: Int? = null,
    val nsfw: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val status: String? = null,
    val genres: List<MALGenre> = emptyList(),
    @SerialName("num_volumes") val numVolumes: Int? = null,
    @SerialName("num_chapters") val numChapters: Int? = null,
    val authors: List<MALAuthorRole> = emptyList(),
    val pictures: List<MALPicture> = emptyList(),
    val background: String? = null,
    @SerialName("related_anime") val relatedAnime: List<MALRelation> = emptyList(),
    @SerialName("related_manga") val relatedManga: List<MALRelation> = emptyList(),
    val recommendations: List<MALRecommendation> = emptyList(),
    val serialization: List<MALSerialization> = emptyList()
)

@Serializable
data class MALPicture(
    val medium: String? = null,
    val large: String? = null
)

@Serializable
data class MALAlternativeTitles(
    val synonyms: List<String> = emptyList(),
    val en: String? = null,
    val ja: String? = null
)

@Serializable
data class MALGenre(
    val id: Int,
    val name: String
)

@Serializable
data class MALSeason(
    val year: Int,
    val season: String
)

@Serializable
data class MALBroadcast(
    @SerialName("day_of_the_week") val dayOfTheWeek: String? = null,
    @SerialName("start_time") val startTime: String? = null
)

@Serializable
data class MALStudio(
    val id: Int,
    val name: String
)

@Serializable
data class MALAuthorRole(
    val node: MALAuthor,
    val role: String? = null
)

@Serializable
data class MALAuthor(
    val id: Int,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null
)

@Serializable
data class MALSerialization(
    val node: MALSerializationNode
)

@Serializable
data class MALSerializationNode(
    val id: Int,
    val name: String
)

@Serializable
data class MALRelation(
    val node: MALRelatedNode,
    @SerialName("relation_type") val relationType: String,
    @SerialName("relation_type_formatted") val relationTypeFormatted: String
)

@Serializable
data class MALRelatedNode(
    val id: Int,
    val title: String,
    @SerialName("main_picture") val mainPicture: MALPicture? = null
)

@Serializable
data class MALRecommendation(
    val node: MALRelatedNode,
    @SerialName("num_recommendations") val numRecommendations: Int
)

@Serializable
data class MALStatistics(
    val status: MALStatusStatistics? = null,
    @SerialName("num_list_users") val numListUsers: Int? = null
)

@Serializable
data class MALStatusStatistics(
    val watching: String? = null,
    val completed: String? = null,
    @SerialName("on_hold") val onHold: String? = null,
    val dropped: String? = null,
    @SerialName("plan_to_watch") val planToWatch: String? = null,
    val reading: String? = null,
    @SerialName("plan_to_read") val planToRead: String? = null
)

@Serializable
data class MALStack(
    val url: String,
    val covers: List<String> = emptyList(),
    val name: String,
    val entries: Int = 0,
    val description: String? = null
)

@Serializable
data class MALStackEntry(
    val id: Int,
    val intro: String? = null
)

