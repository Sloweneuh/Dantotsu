package ani.dantotsu.notifications.subscription

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.dantotsu.App
import ani.dantotsu.FileUrl
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.UrlMedia
import ani.dantotsu.hasNotificationPermission
import ani.dantotsu.notifications.MediaCoverNotificationStyle
import ani.dantotsu.notifications.NotificationImageLoader
import ani.dantotsu.notifications.NotificationReadState
import ani.dantotsu.notifications.Task
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.Episode
import ani.dantotsu.parsers.MangaChapter
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications.CHANNEL_SUBSCRIPTION_CHECK
import eu.kanade.tachiyomi.data.notification.Notifications.CHANNEL_SUBSCRIPTION_CHECK_PROGRESS
import eu.kanade.tachiyomi.data.notification.Notifications.GROUP_SUBSCRIPTION_CHECK
import eu.kanade.tachiyomi.data.notification.Notifications.ID_SUBSCRIPTION_CHECK
import eu.kanade.tachiyomi.data.notification.Notifications.ID_SUBSCRIPTION_CHECK_PROGRESS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class SubscriptionNotificationTask : Task {
    private var currentlyPerforming = false

    @SuppressLint("MissingPermission")
    override suspend fun execute(context: Context): Boolean {
        if (!currentlyPerforming) {
            try {
                withContext(Dispatchers.IO) {
                    PrefManager.init(context)
                    currentlyPerforming = true
                    App.context = context
                    Logger.log("SubscriptionNotificationTask: execute")
                    var timeout = 15_000L
                    do {
                        delay(1000)
                        timeout -= 1000
                    } while (timeout > 0 && !AnimeSources.isInitialized && !MangaSources.isInitialized)
                    Logger.log("SubscriptionNotificationTask: timeout: $timeout")
                    if (timeout <= 0) {
                        currentlyPerforming = false
                        return@withContext
                    }
                    val subscriptions = SubscriptionHelper.getSubscriptions()
                    var i = 0
                    val index = subscriptions.map { i++; it.key to i }.toMap()
                    val notificationManager = NotificationManagerCompat.from(context)

                    val progressEnabled: Boolean =
                        PrefManager.getVal(PrefName.SubscriptionCheckingNotifications)
                    val progressNotification = if (progressEnabled) getProgressNotification(
                        context,
                        subscriptions.size
                    ) else null
                    if (progressNotification != null && hasNotificationPermission(context)) {
                        notificationManager.notify(
                            ID_SUBSCRIPTION_CHECK_PROGRESS,
                            progressNotification.build()
                        )
                        //Seems like if the parent coroutine scope gets cancelled, the notification stays
                        //So adding this as a safeguard? dk if this will be useful
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(5 * subscriptions.size * 1000L)
                            notificationManager.cancel(ID_SUBSCRIPTION_CHECK_PROGRESS)
                        }
                    }

                    fun progress(progress: Int, parser: String, media: String) {
                        if (progressNotification != null && hasNotificationPermission(context))
                            notificationManager.notify(
                                ID_SUBSCRIPTION_CHECK_PROGRESS,
                                progressNotification
                                    .setProgress(subscriptions.size, progress, false)
                                    .setContentText("$media on $parser")
                                    .build()
                            )
                    }

                    subscriptions.toList().map {
                        val media = it.second
                        val text = if (media.isAnime) {
                            // Null when the pinned source isn't available (uninstalled, or
                            // not loaded yet): skip this run rather than check a wrong source.
                            val parser =
                                SubscriptionHelper.getAnimeParser(media) ?: return@map
                            progress(index[it.first]!!, parser.name, media.name)
                            val ep: Episode? =
                                SubscriptionHelper.getEpisode(
                                    parser,
                                    media
                                )
                            if (ep != null) context.getString(R.string.episode) + "${ep.number}${
                                if (ep.title != null) " : ${ep.title}" else ""
                            }${
                                if (ep.isFiller) " [Filler]" else ""
                            } " + context.getString(R.string.just_released) to ep.thumbnail
                            else null
                        } else {
                            val parser =
                                SubscriptionHelper.getMangaParser(media) ?: return@map
                            progress(index[it.first]!!, parser.name, media.name)
                            val ep: MangaChapter? =
                                SubscriptionHelper.getChapter(
                                    parser,
                                    media
                                )
                            if (ep != null) ep.number + " " + context.getString(R.string.just_released) to FileUrl[media.image]
                            else null
                        } ?: return@map
                        val readKey = addSubscriptionToStore(
                            SubscriptionStore(
                                media.name,
                                text.first,
                                media.id,
                                image = media.image,
                                banner = media.banner
                            )
                        )
                        val notification = createNotification(
                            context.applicationContext,
                            media,
                            text.first,
                            text.second,
                            readKey
                        )
                        if (hasNotificationPermission(context)) {
                            NotificationManagerCompat.from(context)
                                .notify(
                                    CHANNEL_SUBSCRIPTION_CHECK,
                                    System.currentTimeMillis().toInt(),
                                    notification
                                )
                            NotificationManagerCompat.from(context)
                                .notify(
                                    CHANNEL_SUBSCRIPTION_CHECK,
                                    ID_SUBSCRIPTION_CHECK,
                                    createGroupSummary(context.applicationContext)
                                )
                        }
                    }

                    if (progressNotification != null) notificationManager.cancel(
                        ID_SUBSCRIPTION_CHECK_PROGRESS
                    )
                    currentlyPerforming = false
                }
                return true
            } catch (e: Exception) {
                Logger.log("SubscriptionNotificationTask: ${e.message}")
                Logger.log(e)
                return false
            }
        } else {
            return false
        }
    }

    @SuppressLint("MissingPermission")
    private fun createNotification(
        context: Context,
        media: SubscriptionHelper.Companion.SubscribeMedia,
        text: String,
        thumbnail: FileUrl?,
        readKey: String
    ): android.app.Notification {
        val pendingIntent = getIntent(context, media.id, readKey)
        val icon =
            if (media.isAnime) R.drawable.ic_round_movie_filter_24 else R.drawable.ic_round_menu_book_24

        val builder = NotificationCompat.Builder(context, CHANNEL_SUBSCRIPTION_CHECK)
            .setSmallIcon(icon)
            .setContentTitle(media.name)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(NotificationReadState.dismissIntent(context, readKey))
            .setAutoCancel(true)
            .setGroup(GROUP_SUBSCRIPTION_CHECK)

        if (thumbnail != null) {
            val bitmap = NotificationImageLoader.loadBitmap(thumbnail.url)
            MediaCoverNotificationStyle.apply(context, builder, media.name, text, bitmap)
        }

        return builder.build()

    }

    /**
     * Tapping the auto-collapsed stack of subscription notifications otherwise has no target and
     * just dismisses them; this summary gives the group header its own tap destination.
     */
    private fun createGroupSummary(context: Context): android.app.Notification {
        val title = context.getString(R.string.subscriptions)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("FRAGMENT_TO_LOAD", "NOTIFICATIONS")
            putExtra("selectedTab", 2)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            ID_SUBSCRIPTION_CHECK,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_SUBSCRIPTION_CHECK)
            .setSmallIcon(R.drawable.ic_round_notifications_active_24)
            .setContentTitle(title)
            // Without it, the group's own header — shown above the stack, distinct from each
            // child's — is just a bare timestamp next to the app name.
            .setSubText(title)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(title))
            .setGroup(GROUP_SUBSCRIPTION_CHECK)
            .setGroupSummary(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    }

    private fun getProgressNotification(
        context: Context,
        size: Int
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_SUBSCRIPTION_CHECK_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(context.getString(R.string.checking_subscriptions_title))
            .setProgress(size, 0, false)
            .setOngoing(true)
            .setAutoCancel(false)
    }

    private fun getIntent(context: Context, mediaId: Int, readKey: String): PendingIntent {
        val notifyIntent = Intent(context, UrlMedia::class.java)
            .putExtra("media", mediaId)
            .putExtra(NotificationReadState.EXTRA_KEY, readKey)
            .setAction(mediaId.toString())
            .apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        return PendingIntent.getActivity(
            context, mediaId, notifyIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
            } else {
                PendingIntent.FLAG_ONE_SHOT
            }
        )
    }

    /**
     * Files [notification] as unread and returns its read-state key — the existing entry's when
     * the same release is already stored, since that's the one the centre will show.
     */
    private fun addSubscriptionToStore(notification: SubscriptionStore): String {
        val notificationStore = PrefManager.getNullableVal<List<SubscriptionStore>>(
            PrefName.SubscriptionNotificationStore,
            null
        ) ?: listOf()
        val newStore = notificationStore.toMutableList()
        if (newStore.size >= 100) {
            newStore.remove(newStore.minByOrNull { it.time })
        }
        val existing = newStore.find {
            it.title == notification.title && it.content == notification.content
        }
        val stored = existing ?: notification.also { newStore.add(it) }
        PrefManager.setVal(PrefName.SubscriptionNotificationStore, newStore)
        NotificationReadState.reconcileSubscriptions(newStore)
        val key = NotificationReadState.keyOf(stored)
        NotificationReadState.markUnread(key)
        return key
    }
}