package ani.dantotsu.connections.malsync

import com.google.gson.annotations.SerializedName
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

