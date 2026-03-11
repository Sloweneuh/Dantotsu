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
    val stype: String = "title",
    val page: Int = 1,
    val perpage: Int = -1,
    val type: List<String>? = null,
    val year: Int? = null,
    val genre: List<String>? = null,
    val exclude_genre: List<String>? = null,
    val include_categories: List<String>? = null,
    val exclude_categories: List<String>? = null,
)

@Serializable
data class MUCategorySearchRequest(
    val search: String = "",
    val orderby: String = "alpha",
    val page: Int = 1,
    val perpage: Int = -1
)

@Serializable
data class MUCategorySearchResponse(
    @SerialName("total_hits") val totalHits: Int? = null,
    val page: Int? = null,
    @SerialName("per_page") val perPage: Int? = null,
    val results: List<MUCategorySearchEntry>? = null
)

@Serializable
data class MUCategorySearchEntry(
    val category: MUCategory? = null
)

@Serializable
data class MUProgressUpdateRequest(
    val series: MUProgressUpdateSeries,
    @SerialName("list_id") val listId: Int,
    val status: MUProgressUpdateStatus,
    val priority: Int? = null
)

@Serializable
data class MUProgressUpdateSeries(
    val id: Long,
    val title: String? = null
)

@Serializable
data class MUProgressUpdateStatus(
    val volume: Int? = null,
    val chapter: Int? = null
)

@Serializable
data class MUSearchResponse(
    @SerialName("total_hits") val totalHits: Int? = null,
    val page: Int? = null,
    @SerialName("per_page") val perPage: Int? = null,
    val results: List<MUSearchResult>? = null
)

@Serializable
data class MUSearchResult(
    val record: MUSeriesRecord? = null,
    val hit_title: String? = null,
    val metadata: MUSearchResultMetadata? = null
)

@Serializable
data class MUSearchResultMetadata(
    @SerialName("user_list") val userList: MUSearchResultUserList? = null
)

@Serializable
data class MUSearchResultUserList(
    @SerialName("list_id") val listId: Int? = null,
    val status: MUProgressUpdateStatus? = null
)

fun MUSearchResult.toMUMedia(): MUMedia? {
    val r = record ?: return null
    return MUMedia(
        id = r.seriesId,
        title = r.title,
        url = r.url,
        coverUrl = r.image?.url?.run { original ?: thumb },
        listId = metadata?.userList?.listId ?: -1,
        userChapter = metadata?.userList?.status?.chapter,
        userVolume = metadata?.userList?.status?.volume,
        latestChapter = r.latest_chapter?.toInt(),
        bayesianRating = r.bayesian_rating?.toDoubleOrNull(),
        priority = null
    )
}

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
    @SerialName("series_name") val seriesName: String? = null,
    @SerialName("series_url") val seriesUrl: String? = null,
    @SerialName("series_image") val seriesImage: MUSeriesImage? = null,
    val weight: Int? = null
)

@Serializable
data class MUCategoryRecommendation(
    @SerialName("series_id") val seriesId: Long? = null,
    @SerialName("series_name") val seriesName: String? = null,
    @SerialName("series_url") val seriesUrl: String? = null,
    @SerialName("series_image") val seriesImage: MUSeriesImage? = null,
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

// ── User-list models ──────────────────────────────────────────────────────────

@Serializable
data class MUListSearchRequest(
    val search: String? = null,
    val page: Int = 1,
    @SerialName("per_page") val perPage: Int = -1
)

@Serializable
data class MUListResponse(
    @SerialName("total_hits") val totalHits: Int? = null,
    val page: Int? = null,
    @SerialName("per_page") val perPage: Int? = null,
    val list: MUListInfo? = null,
    val results: List<MUListEntry>? = null
)

@Serializable
data class MUListInfo(
    @SerialName("list_id") val listId: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val type: String? = null
)

@Serializable
data class MUListEntry(
    val record: MUListRecord? = null,
    val metadata: MUListEntryMetadata? = null
)

@Serializable
data class MUListRecord(
    val series: MUListSeries? = null,
    @SerialName("list_id") val listId: Int? = null,
    val status: MUReadingStatus? = null,
    val priority: Int? = null,
    @SerialName("time_added") val timeAdded: MUTimestamp? = null
)

@Serializable
data class MUListSeries(
    val id: Long,
    val url: String? = null,
    val title: String? = null
)

@Serializable
data class MUReadingStatus(
    val volume: Int? = null,
    val chapter: Int? = null
)

@Serializable
data class MUListEntryMetadata(
    val series: MUListSeriesMetadata? = null,
    @SerialName("user_rating") val userRating: Double? = null
)

@Serializable
data class MUListSeriesMetadata(
    @SerialName("bayesian_rating") val bayesianRating: Double? = null,
    @SerialName("latest_chapter") val latestChapter: Int? = null,
    @SerialName("last_updated") val lastUpdated: MUTimestamp? = null
)

/** Represents a list entry returned by /v1/lists (includes custom lists). */
@Serializable
data class MUUserList(
    @SerialName("list_id") val listId: Int,
    val title: String? = null,
    val description: String? = null,
    val type: String? = null,
    val icon: String? = null,
    val custom: Boolean = false
)

