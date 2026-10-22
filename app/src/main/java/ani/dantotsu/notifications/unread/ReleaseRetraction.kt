package ani.dantotsu.notifications.unread

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import ani.dantotsu.notifications.NotificationReadState
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications

/**
 * Takes back a new-chapter (or episode) announcement whose chapter its source no longer lists — a
 * mistaken upload pulled from Comick, a MALSync mirror rolling its count back.
 *
 * The checks used to only ever add: an announced chapter stayed in the shade and the notification
 * list after its source dropped it, and its "already announced" key kept the real release from
 * announcing itself when the same number came back. Each check now works out which of its own
 * announcements its latest answer has gone below (dropping their keys itself) and hands them here.
 */
internal object ReleaseRetraction {

    /** One announced chapter to take back, by the id it was posted and stored under. */
    data class Retracted(val notifId: Int, val chapter: Int)

    /**
     * Drops [retracted] from the notification list, and returns the ids whose notification is on
     * screen showing one of them. Those aren't cancelled here: the caller reposts, silently, the
     * ones whose title is still behind at a chapter already announced — replacing the notification
     * in place, so the group summary never loses its last child in between — and passes the rest
     * to [cancel].
     *
     * [type] is the stored entries' [UnreadChapterStore.type]; Comick's are "UnreadChapter" too.
     */
    fun retract(context: Context, retracted: Collection<Retracted>, type: String): Set<Int> {
        if (retracted.isEmpty()) return emptySet()
        val store = PrefManager.getNullableVal<List<UnreadChapterStore>>(
            PrefName.UnreadChapterNotificationStore, null
        ).orEmpty()
        // What each id's notification is showing: the newest chapter stored under it. Another
        // check may have replaced it since with a later chapter (Comick posts under the AniList id),
        // and that one isn't this check's to take down.
        val showing = store.filter { it.type == type }.groupBy { it.mediaId }
            .mapValues { (_, entries) -> entries.maxBy { it.time }.lastChapter }

        val gone = retracted.toHashSet()
        val kept = store.filterNot { it.type == type && Retracted(it.mediaId, it.lastChapter) in gone }
        if (kept.size != store.size) {
            PrefManager.setVal(PrefName.UnreadChapterNotificationStore, kept)
            // Not markRead: that also marks every earlier chapter of the title read, and an earlier
            // chapter still listed is still news.
            NotificationReadState.reconcileChapters(kept)
        }

        val active = activeIds(context)
        val onScreen = retracted
            .filter { it.notifId in active && (showing[it.notifId] ?: it.chapter) == it.chapter }
            .mapTo(HashSet()) { it.notifId }
        Logger.log(
            "ReleaseRetraction: took back ${retracted.size} ($type), " +
                "${store.size - kept.size} stored, ${onScreen.size} on screen"
        )
        return onScreen
    }

    /** Cancels [ids], and the group summary with them if nothing is left under it. */
    fun cancel(context: Context, ids: Collection<Int>) {
        if (ids.isEmpty()) return
        val nm = NotificationManagerCompat.from(context)
        ids.forEach { nm.cancel(it) }
        clearGroupSummaryIfEmpty(context)
    }

    /**
     * Cancels the shared new-chapters group summary once its last child is gone. Android leaves a
     * lone summary on screen otherwise.
     */
    fun clearGroupSummaryIfEmpty(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val childrenLeft = nm.activeNotifications.any {
                it.notification.group == Notifications.GROUP_NEW_CHAPTERS &&
                    it.id != Notifications.ID_NEW_CHAPTERS
            }
            if (!childrenLeft) nm.cancel(Notifications.ID_NEW_CHAPTERS)
        } catch (e: Exception) {
            Logger.log("ReleaseRetraction: group summary cleanup failed: ${e.message}")
        }
    }

    private fun activeIds(context: Context): Set<Int> = runCatching {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .activeNotifications.mapTo(HashSet()) { it.id }
    }.getOrDefault(emptySet())

    /** "<id>:<chapter>" keys, as every check's "already announced" set stores them. */
    fun parseKey(key: String): Pair<String, Int>? {
        val chapter = key.substringAfterLast(':', "").toIntOrNull() ?: return null
        return key.substringBeforeLast(':') to chapter
    }
}
