package ani.dantotsu.notifications.anilist

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.Notification
import ani.dantotsu.notifications.NotificationReadState
import ani.dantotsu.notifications.Task
import ani.dantotsu.profile.activity.ActivityItemBuilder
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AnilistNotificationTask : Task {
    override suspend fun execute(context: Context): Boolean {
        try {
            withContext(Dispatchers.IO) {
                PrefManager.init(context) //make sure prefs are initialized
                val userId = PrefManager.getVal<String>(PrefName.AnilistUserId)
                if (userId.isNotEmpty()) {
                    Anilist.getSavedToken()
                    val res = Anilist.query.getNotifications(
                        userId.toInt(),
                        resetNotification = false
                    )
                    val unreadNotificationCount = res?.data?.user?.unreadNotificationCount ?: 0
                    if (unreadNotificationCount > 0) {
                        val unreadNotifications =
                            res?.data?.page?.notifications?.sortedBy { it.id }
                                ?.takeLast(unreadNotificationCount)
                        val lastId = PrefManager.getVal<Int>(PrefName.LastAnilistNotificationId)
                        val newNotifications = unreadNotifications?.filter { it.id > lastId }
                        // Which types arrive at all is the account's own notification setting,
                        // edited from the notification settings screen; nothing is filtered here.
                        newNotifications?.forEach {
                            val content = ActivityItemBuilder.getContent(it)
                            val notification = createNotification(
                                context, content, it, NotificationReadState.keyOf(it)
                            )
                            if (ActivityCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                NotificationManagerCompat.from(context)
                                    .notify(
                                        Notifications.CHANNEL_ANILIST,
                                        System.currentTimeMillis().toInt(),
                                        notification
                                    )
                                NotificationManagerCompat.from(context)
                                    .notify(
                                        Notifications.CHANNEL_ANILIST,
                                        Notifications.ID_ANILIST,
                                        createGroupSummary(context)
                                    )
                            }
                        }
                        if (newNotifications?.isNotEmpty() == true) {
                            PrefManager.setVal(
                                PrefName.LastAnilistNotificationId,
                                newNotifications.last().id
                            )
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Logger.log("AnilistNotificationTask: ${e.message}")
            Logger.log(e)
            return false
        }
    }

    private fun createNotification(
        context: Context,
        content: String,
        source: Notification,
        readKey: String
    ): android.app.Notification {
        val title = context.getString(R.string.new_anilist_notification)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            val mediaId = source.media?.id
            if (mediaId != null) {
                // Airing / media-change notifications are about one title: land on its page,
                // the way the deep-link path does, rather than on a one-item notification list.
                putExtra("mediaId", mediaId)
            } else {
                putExtra("FRAGMENT_TO_LOAD", "NOTIFICATIONS")
                putExtra("activityId", source.id)
            }
            putExtra(NotificationReadState.EXTRA_KEY, readKey)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            source.id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, Notifications.CHANNEL_ANILIST)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(Notifications.GROUP_ANILIST)
            .setDeleteIntent(NotificationReadState.dismissIntent(context, readKey))
            .build()
    }

    /**
     * Tapping the auto-collapsed stack of Anilist notifications otherwise has no target and just
     * dismisses them; this summary gives the group header its own tap destination.
     */
    private fun createGroupSummary(context: Context): android.app.Notification {
        val title = context.getString(R.string.new_anilist_notification)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("FRAGMENT_TO_LOAD", "NOTIFICATIONS")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            Notifications.ID_ANILIST,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, Notifications.CHANNEL_ANILIST)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(title)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(title))
            .setGroup(Notifications.GROUP_ANILIST)
            .setGroupSummary(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    }

}