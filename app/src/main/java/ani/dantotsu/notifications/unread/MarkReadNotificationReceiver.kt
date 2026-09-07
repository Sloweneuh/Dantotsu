package ani.dantotsu.notifications.unread

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import ani.dantotsu.App
import ani.dantotsu.connections.TrackerSessions
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.toMedia
import ani.dantotsu.connections.updateProgressSuspending
import ani.dantotsu.media.Media
import ani.dantotsu.others.getSerialized
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Backs the "Mark as read" / "Mark as watched" action on a new-chapter / new-episode notification
 * (both the AniList + MALSync one from [UnreadChapterNotificationTask] and the MangaUpdates one from
 * [MuUnreadNotificationTask]).
 *
 * Writes the announced chapter/episode as the new progress on whichever tracker backs the entry —
 * reusing [updateProgressSuspending], so MAL/MangaBaka/cloud mirrors follow exactly as they would
 * from inside the app — then clears the notification and drops the entry from the in-app notification
 * centre and the home-screen unread row.
 *
 * The notification carries the whole [Media] / [MUMedia] as an extra (the content intent already
 * does), so the update needs no network lookup just to find out what to write.
 */
class MarkReadNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "ani.dantotsu.notifications.MARK_READ"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_NOTIFICATION_ID = "notificationId"

        /**
         * How long the broadcast is kept alive waiting for the primary tracker write to land. Under
         * the ~10s foreground-queue deadline that [goAsync] does not extend; the mirror writes run
         * on their own scope and are not waited on.
         */
        private const val WRITE_TIMEOUT_MS = 8_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val progress = intent.getIntExtra(EXTRA_PROGRESS, -1)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val media = intent.getSerialized<Media>("media")
        val muMedia = intent.getSerialized<MUMedia>("muMedia")

        // Clear the notification straight away so the action feels instant; the tracker write and
        // the bookkeeping below carry on in the background.
        val notificationManager = NotificationManagerCompat.from(appContext)
        if (notificationId != -1) runCatching { notificationManager.cancel(notificationId) }
        clearGroupSummaryIfEmpty(appContext)

        if (progress <= 0 || (media == null && muMedia == null)) {
            Logger.log("MarkReadNotificationReceiver: nothing to do (progress=$progress)")
            return
        }

        val pending = goAsync()
        scope.launch {
            try {
                PrefManager.init(appContext)
                App.context = appContext
                TrackerSessions.start()

                val target = media ?: muMedia!!.toMedia()
                if (progress > (target.userProgress ?: 0)) {
                    withTimeoutOrNull(WRITE_TIMEOUT_MS) {
                        // Shows its own "Setting progress to N" confirmation.
                        updateProgressSuspending(target, progress.toString())
                    } ?: Logger.log("MarkReadNotificationReceiver: write timed out, mirrors continue")
                }

                removeStoredNotification(notificationId, progress)
                UnreadCache.removeEntry(appContext, notificationId)
            } catch (e: Exception) {
                Logger.log("MarkReadNotificationReceiver: ${e.message}")
                Logger.log(e)
            } finally {
                clearGroupSummaryIfEmpty(appContext)
                pending.finish()
            }
        }
    }

    /** Drops every stored entry for this media up to [progress] and trims the unread badge count. */
    private fun removeStoredNotification(mediaId: Int, progress: Int) {
        if (mediaId == -1) return
        val store = PrefManager.getNullableVal<List<UnreadChapterStore>>(
            PrefName.UnreadChapterNotificationStore, null
        ) ?: return
        val kept = store.filterNot { it.mediaId == mediaId && it.lastChapter <= progress }
        val removed = store.size - kept.size
        if (removed <= 0) return
        PrefManager.setVal(PrefName.UnreadChapterNotificationStore, kept)
        val badge = PrefManager.getVal<Int>(PrefName.UnreadCommentNotifications)
        PrefManager.setVal(PrefName.UnreadCommentNotifications, (badge - removed).coerceAtLeast(0))
    }

    /**
     * Cancels the shared new-chapters group summary once its last child is gone. Android leaves a
     * lone summary on screen otherwise — see the summary's own doc in [UnreadChapterNotificationTask].
     */
    private fun clearGroupSummaryIfEmpty(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val childrenLeft = nm.activeNotifications.any {
                it.notification.group == Notifications.GROUP_NEW_CHAPTERS &&
                    it.id != Notifications.ID_NEW_CHAPTERS
            }
            if (!childrenLeft) nm.cancel(Notifications.ID_NEW_CHAPTERS)
        } catch (e: Exception) {
            Logger.log("MarkReadNotificationReceiver: group summary cleanup failed: ${e.message}")
        }
    }
}
