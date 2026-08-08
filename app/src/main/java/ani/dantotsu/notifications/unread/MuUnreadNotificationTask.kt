package ani.dantotsu.notifications.unread

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.dantotsu.App
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.connections.malsync.MalSyncMu
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.muMediaKey
import ani.dantotsu.notifications.Task
import ani.dantotsu.hasNotificationPermission
import eu.kanade.tachiyomi.data.notification.Notifications
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.containsMediaId
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MuUnreadNotificationTask : Task {

    companion object {
        @Volatile
        private var currentlyPerforming = false
    }

    override suspend fun execute(context: Context): Boolean {
        if (currentlyPerforming) {
            Logger.log("MuUnreadNotificationTask: already running")
            return false
        }
        return try {
            withContext(Dispatchers.IO) {
                currentlyPerforming = true
                PrefManager.init(context)
                App.context = context
                checkMangaUpdatesUnread(context)
                currentlyPerforming = false
            }
            true
        } catch (e: Exception) {
            Logger.log("MuUnreadNotificationTask: error: ${e.message}")
            currentlyPerforming = false
            false
        }
    }

    suspend fun checkMangaUpdatesUnread(context: Context) {
        if (!PrefManager.getVal<Boolean>(PrefName.MangaUpdatesNotificationsEnabled)) {
            Logger.log("MuUnreadNotificationTask: MangaUpdates notifications disabled")
            return
        }
        if (!PrefManager.getVal<Boolean>(PrefName.MangaUpdatesListEnabled)) {
            Logger.log("MuUnreadNotificationTask: MangaUpdates list fetch disabled")
            return
        }

        val tokenLoaded = MangaUpdates.getSavedToken()
        if (!tokenLoaded || MangaUpdates.token.isNullOrBlank()) {
            Logger.log("MuUnreadNotificationTask: MangaUpdates not logged in, skipping")
            return
        }

        Logger.log("MuUnreadNotificationTask: checking MangaUpdates unread chapters")

        val allLists = try {
            MangaUpdates.getAllUserLists()
        } catch (e: Exception) {
            Logger.log("MuUnreadNotificationTask: getAllUserLists error: ${e.message}")
            return
        }

        val readingList = allLists["Reading"] ?: emptyList()

        // What MALSync knows about the series that could be linked to a MAL entry — often a chapter
        // ahead of MangaUpdates' own count, and with the source it landed on. The rest are unchanged.
        val malSyncInfo = try {
            MalSyncMu.unreadInfo(readingList)
        } catch (e: Exception) {
            Logger.log("MuUnreadNotificationTask: MALSync lookup failed: ${e.message}")
            emptyMap()
        }
        val excludeList = PrefManager.getVal<Set<String>>(PrefName.MalSyncExcludeList)

        val unreadItems = readingList.mapNotNull { muMedia ->
            if (excludeList.containsMediaId(muMediaKey(muMedia.id).toString())) return@mapNotNull null
            val info = malSyncInfo[muMediaKey(muMedia.id)]
            val latest = MalSyncMu.latestChapter(muMedia.latestChapter, info?.lastChapter)
                ?: return@mapNotNull null
            if (latest <= (muMedia.userChapter ?: 0)) return@mapNotNull null
            // Where to read the chapter being announced, whenever MALSync's site actually has it —
            // which includes the common case of the two agreeing on the number, since only MALSync
            // names a site. It's withheld only when MALSync is *behind*, where naming its source
            // would point at a site that doesn't carry the chapter in the notification.
            val source = info?.source
                ?.takeIf { it.isNotBlank() && info.lastChapter >= (muMedia.latestChapter ?: 0) }
            UnreadItem(muMedia, latest, source)
        }

        Logger.log("MuUnreadNotificationTask: found ${unreadItems.size} items with unread chapters")

        if (unreadItems.isEmpty()) return

        val notifiedKey = "notified_mu_chapters"
        val notified = getNotifiedSet(context, notifiedKey)
        val newItems = mutableListOf<UnreadItem>()

        unreadItems.forEach { item ->
            val key = "${item.media.id}:${item.latestChapter}"
            if (!notified.contains(key)) {
                newItems.add(item)
                notified.add(key)
            }
        }

        saveNotifiedSet(context, notifiedKey, notified)

        Logger.log("MuUnreadNotificationTask: ${newItems.size} new chapters to notify")

        if (newItems.isNotEmpty() && hasNotificationPermission(context)) {
            withContext(Dispatchers.Main) {
                sendNotifications(context, newItems)
                storeNotifications(newItems)
            }
        }
    }

    /** A MangaUpdates entry with unread chapters, over both sources that can know about them. */
    private data class UnreadItem(
        val media: MUMedia,
        val latestChapter: Int,
        /** MALSync's source, when MALSync is the one reporting [latestChapter]. */
        val source: String?,
    )

    @SuppressLint("MissingPermission")
    private fun sendNotifications(context: Context, items: List<UnreadItem>) {
        val notificationManager = NotificationManagerCompat.from(context)

        items.forEach { (muMedia, latestChapter, source) ->
            val unreadCount = latestChapter - (muMedia.userChapter ?: 0)
            val title = context.getString(R.string.notification_new_chapter_title)
            val text = if (unreadCount == 1) {
                "${muMedia.title}: Chapter $latestChapter"
            } else {
                "${muMedia.title}: Chapter $latestChapter ($unreadCount unread)"
            }
            val subText = context.getString(
                R.string.notification_source_subtext,
                source ?: "MangaUpdates"
            )

            val intent = Intent(context, MUMediaDetailsActivity::class.java).apply {
                putExtra("muMedia", muMedia)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val notifId = muMediaKey(muMedia.id)
            val pendingIntent = PendingIntent.getActivity(
                context,
                notifId,
                intent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )

            val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_NEW_CHAPTERS_EPISODES)
                .setSmallIcon(R.drawable.notification_icon)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(subText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setGroup(Notifications.GROUP_NEW_CHAPTERS)
                .build()

            notificationManager.notify(notifId, notification)
            notificationManager.notify(Notifications.ID_NEW_CHAPTERS, createGroupSummary(context))
        }
    }

    /**
     * Tapping the auto-collapsed stack of new-chapter notifications otherwise has no target and
     * just dismisses them; this summary gives the group header its own tap destination. Shares
     * the group key with UnreadChapterNotificationTask since both feed the same channel.
     */
    private fun createGroupSummary(context: Context): android.app.Notification {
        val title = context.getString(R.string.notification_new_chapter_title)
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("FRAGMENT_TO_LOAD", "NOTIFICATIONS")
            putExtra("selectedTab", 3)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            Notifications.ID_NEW_CHAPTERS,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        return NotificationCompat.Builder(context, Notifications.CHANNEL_NEW_CHAPTERS_EPISODES)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(title)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(title))
            .setGroup(Notifications.GROUP_NEW_CHAPTERS)
            .setGroupSummary(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    }

    private fun storeNotifications(items: List<UnreadItem>) {
        val notificationStore = PrefManager.getNullableVal<List<UnreadChapterStore>>(
            PrefName.UnreadChapterNotificationStore,
            null
        ) ?: listOf()
        val newStore = notificationStore.toMutableList()

        if (newStore.size > 50) {
            newStore.sortByDescending { it.time }
            while (newStore.size > 50) newStore.removeAt(newStore.size - 1)
        }

        items.forEach { (muMedia, latestChapter, source) ->
            val unreadCount = latestChapter - (muMedia.userChapter ?: 0)
            val mediaId = muMediaKey(muMedia.id)

            val exists = newStore.any { it.mediaId == mediaId && it.lastChapter == latestChapter }
            if (!exists) {
                newStore.add(
                    UnreadChapterStore(
                        mediaId = mediaId,
                        mediaName = muMedia.title ?: "",
                        lastChapter = latestChapter,
                        unreadCount = unreadCount,
                        source = source ?: "MangaUpdates",
                        image = muMedia.coverUrl,
                        banner = muMedia.coverUrl,
                        time = System.currentTimeMillis()
                    )
                )
            }
        }

        PrefManager.setVal(PrefName.UnreadChapterNotificationStore, newStore)
        PrefManager.setVal(
            PrefName.UnreadCommentNotifications,
            PrefManager.getVal<Int>(PrefName.UnreadCommentNotifications) + items.size
        )
    }

    private fun getNotifiedSet(context: Context, key: String): MutableSet<String> {
        val prefs = context.getSharedPreferences("unread_notifications", Context.MODE_PRIVATE)
        return prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveNotifiedSet(context: Context, key: String, notified: Set<String>) {
        val prefs = context.getSharedPreferences("unread_notifications", Context.MODE_PRIVATE)
        prefs.edit().putStringSet(key, notified).apply()
    }
}
