package ani.dantotsu.connections.malsync

import java.io.Serializable

data class MalSyncResponse(
    val id: String,
    val source: String,
    val group: String?,
    val lang: String,
    val type: String,
    val state: String,
    val lastEp: LastEpisode?,
    val releaseInterval: ReleaseInterval?
) : Serializable

data class LastEpisode(
    val total: Int,
    val timestamp: String?
) : Serializable

data class ReleaseInterval(
    val mean: Long?,
    val sd: Long?,
    val n: Int?,
    val pi: Long?
) : Serializable

data class UnreadChapterInfo(
    val mediaId: Int,
    val lastChapter: Int,
    val source: String,
    val userProgress: Int
) : Serializable

data class BatchProgressResult(
    val malid: String?,  // Can be numeric "5114" or "anilist:173188"
    val data: List<MalSyncResponse>?
) : Serializable

// Quicklinks models for Malsync's quicklinks API responses
// Example response contains a top-level object with Sites: { SiteName: { identifier: {...}, ... }, ... }

data class QuicklinksResponse(
    val id: Int?,
    val type: String?,
    val title: String?,
    val url: String?,
    val total: Int?,
    val image: String?,
    val Sites: Map<String, Map<String, QuicklinkEntry>>?
) : Serializable

data class QuicklinkEntry(
    val id: Int?,
    val identifier: String?,
    val image: String?,
    val malId: Int?,
    val aniId: Int?,
    val page: String?,
    val title: String?,
    val type: String?,
    val url: String?
) : Serializable