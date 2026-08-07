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
) : Serializable {

    /**
     * [timestamp] as epoch milliseconds, or null when there isn't one to read.
     *
     * MALSync sends epoch milliseconds as a quoted decimal string — `"timestamp":"1784886362000"`
     * — which is the case that matters. The other two branches are tolerance, not guesswork about
     * the format: a bare epoch in seconds is told from one in milliseconds by magnitude, since a
     * value small enough to be a plausible date in seconds reads as 1970 in milliseconds.
     */
    fun timestampMillis(): Long? {
        val raw = timestamp?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toLongOrNull()?.let { return if (it < SECONDS_CEILING) it * 1000L else it }
        return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
    }

    private companion object {
        /** Year 5138 in seconds; the same number of milliseconds is only 1973. */
        const val SECONDS_CEILING = 100_000_000_000L
    }
}

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
    val userProgress: Int,
    /**
     * When [lastChapter] was released, epoch ms, from [LastEpisode.timestampMillis]. Null when
     * MALSync didn't carry one — those entries sort last under the by-most-recent order.
     */
    val latestChapterAt: Long? = null
) : Serializable

data class UnreleasedEpisodeInfo(
    val mediaId: Int,
    val lastEpisode: Int,
    val languageId: String,        // e.g., "en/dub", "en/sub"
    val languageDisplay: String,   // e.g., "English (Dub)"
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