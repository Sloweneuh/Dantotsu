package ani.dantotsu.notifications.unread

import android.content.Context
import ani.dantotsu.R

/**
 * A new-release notification's middle line: "Chapter 12", or "Chapter 12 (3 unread)" when more
 * than the one is waiting — the episode wording for anime. Shared by every new-release check.
 */
internal fun Context.newReleaseText(number: String, unreadCount: Int, isAnime: Boolean = false): String =
    if (unreadCount == 1) {
        getString(if (isAnime) R.string.notification_episode else R.string.notification_chapter, number)
    } else {
        getString(
            if (isAnime) R.string.notification_episode_unwatched else R.string.notification_chapter_unread,
            number, unreadCount
        )
    }

/**
 * Trims a set of announced releases — "<id>:<chapter>" keys — to the ones that can still matter:
 * titles on the list just fetched ([progressById], their saved progress by id) with a chapter past
 * that progress. A title gone from the list, or a chapter since read, can't be announced again, so
 * keeping its key only grew the set forever.
 *
 * Only for a complete list: a key whose title is missing from [progressById] is dropped.
 */
internal fun pruneNotified(notified: MutableSet<String>, progressById: Map<String, Int>) {
    notified.retainAll { key ->
        val id = key.substringBeforeLast(':')
        val chapter = key.substringAfterLast(':').toIntOrNull() ?: return@retainAll false
        val progress = progressById[id] ?: return@retainAll false
        chapter > progress
    }
}
