package ani.dantotsu.notifications.unread

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.dantotsu.App
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.malsync.MalSyncApi
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.notifications.Task
import eu.kanade.tachiyomi.data.notification.Notifications
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Serializable

class UnreadChapterNotificationTask : Task {
    private var currentlyPerforming = false

    override suspend fun execute(context: Context): Boolean {
        if (currentlyPerforming) {
            Logger.log("UnreadChapterNotificationTask: already running")
            return false
        }

        try {
            withContext(Dispatchers.IO) {
                currentlyPerforming = true
                PrefManager.init(context)
                App.context = context

                Logger.log("UnreadChapterNotificationTask: starting check")

                // Check if user is logged in
                if (!Anilist.token.isNullOrEmpty() && Anilist.userid != null) {
                    // Get manga list with status CURRENT (reading)
                    // We use initHomePage to get the current manga list
                    val mangaList: List<Media> = try {
                        val homePageData = Anilist.query.initHomePage()
                        homePageData["currentManga"] ?: emptyList()
                    } catch (e: Exception) {
                        Logger.log("UnreadChapterNotificationTask: error fetching manga list: ${e.message}")
                        emptyList()
                    }

                    if (mangaList.isEmpty()) {
                        Logger.log("UnreadChapterNotificationTask: no manga in reading list")
                        currentlyPerforming = false
                        return@withContext
                    }

                    Logger.log("UnreadChapterNotificationTask: found ${mangaList.size} manga")

                    // Fetch MalSync data
                    val mediaIds = mangaList.map { Pair(it.id, it.idMAL) }
                    val batchResults = MalSyncApi.getBatchProgressByMedia(mediaIds)

                    val unreadInfo = mutableMapOf<Int, UnreadChapterInfo>()
                    for (media in mangaList) {
                        val result = batchResults[media.id]
                        if (result != null && result.lastEp != null) {
                            val userProgress = media.userProgress ?: 0
                            val lastChapter = result.lastEp.total

                            if (lastChapter > userProgress) {
                                unreadInfo[media.id] = UnreadChapterInfo(
                                    mediaId = media.id,
                                    lastChapter = lastChapter,
                                    source = result.source,
                                    userProgress = userProgress
                                )
                            }
                        }
                    }

                    Logger.log("UnreadChapterNotificationTask: found ${unreadInfo.size} manga with unread chapters")

                    // Get previously notified chapters
                    val notifiedKey = "notified_unread_chapters"
                    val notified = getNotifiedChapters(context, notifiedKey)
                    val newNotifications = mutableListOf<Pair<Media, UnreadChapterInfo>>()

                    unreadInfo.forEach { (mediaId, info) ->
                        val key = "$mediaId:${info.lastChapter}"
                        if (!notified.contains(key)) {
                            val media = mangaList.find { it.id == mediaId }
                            if (media != null) {
                                newNotifications.add(media to info)
                                notified.add(key)
                            }
                        }
                    }

                    // Save updated notified list
                    saveNotifiedChapters(context, notifiedKey, notified)

                    Logger.log("UnreadChapterNotificationTask: ${newNotifications.size} new chapters to notify")

                    // Send notifications
                    if (newNotifications.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            sendNotifications(context, newNotifications)
                        }
                    }
                }

                currentlyPerforming = false
            }
            return true
        } catch (e: Exception) {
            Logger.log("UnreadChapterNotificationTask: error: ${e.message}")
            currentlyPerforming = false
            return false
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendNotifications(
        context: Context,
        newChapters: List<Pair<Media, UnreadChapterInfo>>
    ) {
        val notificationManager = NotificationManagerCompat.from(context)

        newChapters.forEachIndexed { index, (media, info) ->
            val unreadCount = info.lastChapter - info.userProgress
            val title = "New Chapter Available"

            // Show unread count only if more than 1
            val text = if (unreadCount == 1) {
                "${media.userPreferredName}: Chapter ${info.lastChapter}"
            } else {
                "${media.userPreferredName}: Chapter ${info.lastChapter} ($unreadCount unread)"
            }

            // Create intent to open the media page
            val intent = Intent(context, MediaDetailsActivity::class.java).apply {
                putExtra("media", media as Serializable)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                media.id, // Unique request code for each media
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
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(media.id, notification)
        }
    }

    private fun getNotifiedChapters(context: Context, key: String): MutableSet<String> {
        val prefs = context.getSharedPreferences("unread_notifications", Context.MODE_PRIVATE)
        return prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveNotifiedChapters(context: Context, key: String, notified: Set<String>) {
        val prefs = context.getSharedPreferences("unread_notifications", Context.MODE_PRIVATE)
        prefs.edit().putStringSet(key, notified).apply()
    }
}

