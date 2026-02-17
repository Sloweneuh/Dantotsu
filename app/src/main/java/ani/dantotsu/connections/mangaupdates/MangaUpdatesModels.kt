@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package ani.dantotsu.connections.mangaupdates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MULoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class MULoginResponse(
    val status: String,
    val reason: String? = null,
    val context: MULoginContext? = null
)

@Serializable
data class MULoginContext(
    @SerialName("session_token") val sessionToken: String,
    val uid: Int? = null
)

@Serializable
data class MUSearchRequest(
    val search: String,
    val stype: String = "title"
)

@Serializable
data class MUSearchResponse(
    @SerialName("total_hits") val totalHits: Int? = null,
    val results: List<MUSearchResult>? = null
)

@Serializable
data class MUSearchResult(
    val record: MUSeriesRecord? = null,
    val hit_title: String? = null
)

@Serializable
data class MUSeriesRecord(
    @SerialName("series_id") val seriesId: Long,
    val title: String? = null,
    val url: String? = null,
    val associated: List<MUAssociatedTitle>? = null,
    val description: String? = null,
    val image: MUSeriesImage? = null,
    val type: String? = null,
    val year: String? = null,
    val bayesian_rating: String? = null,
    val rating_votes: Int? = null,
    val genres: List<MUGenre>? = null,
    val categories: List<MUCategory>? = null,
    val latest_chapter: Long? = null,
    val status: String? = null,
    val licensed: Boolean? = null,
    val completed: Boolean? = null,
    val anime: MUAnime? = null,
    val related_series: List<MURelatedSeries>? = null,
    val authors: List<MUAuthor>? = null,
    val publishers: List<MUPublisher>? = null,
    val publications: List<MUPublication>? = null,
    val recommendations: List<MURecommendation>? = null,
    val category_recommendations: List<MUCategoryRecommendation>? = null,
    val rank: MURank? = null,
    val last_updated: MUTimestamp? = null
)

@Serializable
data class MUSeriesImage(
    val url: MUImageUrl? = null,
    val height: Int? = null,
    val width: Int? = null
)

@Serializable
data class MUImageUrl(
    val original: String? = null,
    val thumb: String? = null
)

@Serializable
data class MUAssociatedTitle(
    val title: String? = null
)

@Serializable
data class MUAnime(
    val start: String? = null,
    val end: String? = null
)

@Serializable
data class MURelatedSeries(
    @SerialName("relation_id") val relationId: Long? = null,
    @SerialName("relation_type") val relationType: String? = null,
    @SerialName("related_series_id") val relatedSeriesId: Long? = null,
    @SerialName("related_series_name") val relatedSeriesName: String? = null,
    @SerialName("related_series_url") val relatedSeriesUrl: String? = null,
    @SerialName("triggered_by_relation_id") val triggeredByRelationId: Long? = null
)

@Serializable
data class MUGenre(
    val genre: String? = null
)

@Serializable
data class MUCategory(
    val series_id: Long? = null,
    val category: String? = null,
    val votes: Int? = null,
    val votes_plus: Int? = null,
    val votes_minus: Int? = null,
    val added_by: Long? = null
)

@Serializable
data class MUAuthor(
    @SerialName("author_id") val authorId: Long? = null,
    val name: String? = null,
    val type: String? = null
)

@Serializable
data class MUPublisher(
    @SerialName("publisher_id") val publisherId: Long? = null,
    @SerialName("publisher_name") val publisherName: String? = null,
    val type: String? = null,
    val notes: String? = null
)

@Serializable
data class MUPublication(
    val publication_name: String? = null,
    val publisher_name: String? = null,
    val publisher_id: String? = null
)

@Serializable
data class MURecommendation(
    @SerialName("series_id") val seriesId: Long? = null,
    val title: String? = null,
    val url: String? = null,
    val description: String? = null,
    val weight: Int? = null
)

@Serializable
data class MUCategoryRecommendation(
    @SerialName("series_id") val seriesId: Long? = null,
    val title: String? = null,
    val url: String? = null,
    val description: String? = null,
    val weight: Int? = null
)

@Serializable
data class MURank(
    val position: MURankPosition? = null,
    val old_position: MURankPosition? = null,
    val lists: MURankLists? = null
)

@Serializable
data class MURankPosition(
    val week: Int? = null,
    val month: Int? = null,
    val three_months: Int? = null,
    val six_months: Int? = null,
    val year: Int? = null
)

@Serializable
data class MURankLists(
    val reading: Int? = null,
    val wish: Int? = null,
    val complete: Int? = null,
    val unfinished: Int? = null,
    val custom: Int? = null
)

@Serializable
data class MUTimestamp(
    val timestamp: Long? = null,
    @SerialName("as_rfc3339") val asRfc3339: String? = null,
    @SerialName("as_string") val asString: String? = null
)

@Serializable
data class MUUserProfile(
    @SerialName("user_id") val userId: Long? = null,
    val username: String? = null,
    val url: String? = null,
    val avatar: MUAvatar? = null
)

@Serializable
data class MUAvatar(
    val id: Long? = null,
    val url: String? = null,
    val title: String? = null,
    val extension: String? = null,
    val height: Int? = null,
    val width: Int? = null
)

/**
 * Models for user lists endpoint (/v1/lists)
 */
@Serializable
data class MUListEntry(
    @SerialName("series_id") val seriesId: Long? = null,
    @SerialName("series_slug") val seriesSlug: String? = null,
    @SerialName("series_url") val seriesUrl: String? = null,
    val status: String? = null,
    val notes: String? = null
)

@Serializable
data class MUList(
    val id: Long? = null,
    val name: String? = null,
    val description: String? = null,
    // Make entries mutable so callers can populate them when the lists endpoint
    // returns only metadata (no entries).
    var entries: List<MUListEntry>? = null
)

@Serializable
data class MUListsResponse(
    val lists: List<MUList>? = null
)

