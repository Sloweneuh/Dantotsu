package ani.dantotsu.notifications.unread

import java.io.Serializable

/**
 * Data class to store unread chapter notifications for the notification center
 */
data class UnreadChapterStore(
    val mediaId: Int,
    val mediaName: String,
    val lastChapter: Int,
    val unreadCount: Int,
    val source: String,
    val image: String?,  // Cover image URL
    val banner: String?, // Banner image URL
    val time: Long,      // Timestamp in milliseconds
    val type: String = "UnreadChapter",
    // For UnreadEpisode entries: MALSync language ID (e.g. "en/dub"), shown in place of the source.
    val language: String? = null
) : Serializable {
    companion object {
        // Pinned, like every other stored class. Java derives this from the class shape when it
        // isn't declared, so adding or removing a single field makes every previously stored value
        // unreadable — which PrefManager then reports as a deserialization failure and falls back
        // to the default, losing the stored notifications on upgrade.
        private const val serialVersionUID = 1L
    }
}
