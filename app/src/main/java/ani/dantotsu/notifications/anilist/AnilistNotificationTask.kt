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
import ani.dantotsu.connections.anilist.api.NotificationType
import ani.dantotsu.notifications.MediaCoverNotificationStyle
import ani.dantotsu.notifications.NotificationImageLoader
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
        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_ANILIST)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(Notifications.GROUP_ANILIST)
            .setDeleteIntent(NotificationReadState.dismissIntent(context, readKey))
        // Media-centric notifications (airing, media updates, ...) get the cover laid out to the
        // left, like the in-app list; everything else (activity/social notifications) falls back
        // to the framework's square icon, which suits a round avatar fine.
        val coverUrl = source.media?.coverImage?.large
        if (coverUrl != null) {
            val cover = NotificationImageLoader.loadBitmap(coverUrl)
            MediaCoverNotificationStyle.apply(context, builder, title, content, cover)
        } else {
            NotificationImageLoader.loadBitmap(source.user?.avatar?.large)?.let { builder.setLargeIcon(it) }
        }
        return builder.build()
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
            // Without it, the group's own header — shown above the stack, distinct from each
            // child's — is just a bare timestamp next to the app name.
            .setSubText(title)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(title))
            .setGroup(Notifications.GROUP_ANILIST)
            .setGroupSummary(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    }

    /**
     * The account's own current userId, loading a saved token first the way [execute] does —
     * shared by both test methods below.
     */
    private suspend fun loggedInUserId(context: Context): Int? {
        PrefManager.init(context)
        val userId = PrefManager.getVal<String>(PrefName.AnilistUserId).toIntOrNull() ?: return null
        Anilist.getSavedToken()
        return userId
    }

    /**
     * Debug-only: posts a real AniList notification — the most recent media-centric one on the
     * logged-in account (airing, media update, ...) — through the exact same [createNotification]
     * production code above, so the cover layout is checked against a real title/cover/text
     * instead of made-up ones. Falls back to a synthetic placeholder when there's no such
     * notification to fetch (logged out, or none on the account) — an actual *unread* one isn't
     * reliably reproducible on demand, so this doesn't require one specifically.
     */
    suspend fun sendTestNotification(context: Context) {
        withContext(Dispatchers.IO) {
            val last = loggedInUserId(context)?.let { userId ->
                Anilist.query.getNotifications(userId, resetNotification = false, type = true)
                    ?.data?.page?.notifications?.firstOrNull { it.media != null }
            }
            val notification = if (last != null) {
                createNotification(
                    context, ActivityItemBuilder.getContent(last), last, NotificationReadState.keyOf(last)
                )
            } else {
                buildSyntheticCoverNotification(context)
            }
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context)
                    .notify(Notifications.CHANNEL_ANILIST, TEST_NOTIFICATION_ID, notification)
            }
        }
    }

    /**
     * Debug-only: posts a real AniList notification — the most recent "liked your activity" one
     * on the logged-in account, or any other non-media one if there isn't one — through the exact
     * same [createNotification] production code above (the activity/social branch: no media, so
     * the framework's square icon rather than the left-aligned cover layout). Falls back to a
     * synthetic placeholder when there's no such notification to fetch.
     */
    suspend fun sendTestActivityLikeNotification(context: Context) {
        withContext(Dispatchers.IO) {
            val last = loggedInUserId(context)?.let { userId ->
                Anilist.query.getNotifications(userId, resetNotification = false)
                    ?.data?.page?.notifications?.filter { it.media == null }?.let { candidates ->
                        candidates.firstOrNull { it.notificationType == NotificationType.ACTIVITY_LIKE.value }
                            ?: candidates.firstOrNull()
                    }
            }
            val notification = if (last != null) {
                createNotification(
                    context, ActivityItemBuilder.getContent(last), last, NotificationReadState.keyOf(last)
                )
            } else {
                buildSyntheticActivityLikeNotification(context)
            }
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context)
                    .notify(Notifications.CHANNEL_ANILIST, TEST_ACTIVITY_LIKE_NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildSyntheticCoverNotification(context: Context): android.app.Notification {
        val bitmap = NotificationImageLoader.loadBitmap(TEST_IMAGE_URL)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("FRAGMENT_TO_LOAD", "NOTIFICATIONS")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            TEST_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = context.getString(R.string.new_anilist_notification)
        val text = "Test Anime: Episode 12 aired"
        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_ANILIST)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        MediaCoverNotificationStyle.apply(context, builder, title, text, bitmap)
        return builder.build()
    }

    private fun buildSyntheticActivityLikeNotification(context: Context): android.app.Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("FRAGMENT_TO_LOAD", "NOTIFICATIONS")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            TEST_ACTIVITY_LIKE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_ANILIST)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(context.getString(R.string.new_anilist_notification))
            .setContentText("Test User liked your activity \"Just started watching Test Anime!\"")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        NotificationImageLoader.loadBitmap(TEST_IMAGE_URL)?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    private companion object {
        const val TEST_NOTIFICATION_ID = 999_991
        const val TEST_ACTIVITY_LIKE_NOTIFICATION_ID = 999_992
        const val TEST_IMAGE_URL =
            "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21-YCDoj1EkAxFn.jpg"
    }
}