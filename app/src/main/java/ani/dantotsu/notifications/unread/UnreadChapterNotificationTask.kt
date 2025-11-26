package ani.dantotsu.notifications.unread

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.dantotsu.App
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.malsync.MalSyncApi
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.media.Media
import ani.dantotsu.notifications.Task
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UnreadChapterNotificationTask : Task {
    private var currentlyPerforming = false

    @SuppressLint("MissingPermission")
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
            val text = "${media.userPreferredName}: Chapter ${info.lastChapter} ($unreadCount unread)"

            val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_NEW_CHAPTERS_EPISODES)
                .setSmallIcon(R.drawable.ic_round_notifications_active_24)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setGroup(Notifications.GROUP_NEW_CHAPTERS)

            // Use unique notification ID per media
            val notificationId = Notifications.ID_NEW_CHAPTERS + media.id
            notificationManager.notify(notificationId, builder.build())
        }

        // Summary notification if multiple chapters
        if (newChapters.size > 1) {
            val summaryBuilder = NotificationCompat.Builder(context, Notifications.CHANNEL_NEW_CHAPTERS_EPISODES)
                .setSmallIcon(R.drawable.ic_round_notifications_active_24)
                .setContentTitle("New Chapters Available")
                .setContentText("${newChapters.size} manga have new chapters")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setGroup(Notifications.GROUP_NEW_CHAPTERS)
                .setGroupSummary(true)
                .setAutoCancel(true)

            notificationManager.notify(Notifications.ID_NEW_CHAPTERS, summaryBuilder.build())
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

