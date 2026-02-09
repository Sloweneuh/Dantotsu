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
    val type: String = "UnreadChapter"
) : Serializable
