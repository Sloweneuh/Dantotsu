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
import ani.dantotsu.hasNotificationPermission
import eu.kanade.tachiyomi.data.notification.Notifications
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

                // Initialize Anilist from stored preferences (needed when app is closed)
                val storedToken = PrefManager.getVal<String>(PrefName.AnilistToken)
                val storedUserIdStr = PrefManager.getVal<String>(PrefName.AnilistUserId)

                if (!storedToken.isNullOrEmpty() && !storedUserIdStr.isNullOrEmpty()) {
                    // Set Anilist credentials from storage
                    Anilist.token = storedToken
                    val userId = storedUserIdStr.toIntOrNull()
                    if (userId != null && userId != 0) {
                        Anilist.userid = userId
                        Logger.log("UnreadChapterNotificationTask: Loaded Anilist credentials from storage (userId: $userId)")
                    } else {
                        Logger.log("UnreadChapterNotificationTask: Invalid userId in storage: $storedUserIdStr")
                        currentlyPerforming = false
                        return@withContext
                    }
                } else {
                    Logger.log("UnreadChapterNotificationTask: No Anilist credentials found in storage (token empty: ${storedToken.isNullOrEmpty()}, userId empty: ${storedUserIdStr.isNullOrEmpty()})")
                    currentlyPerforming = false
                    return@withContext
                }

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

                    Logger.log("UnreadChapterNotificationTask: found ${mangaList.size} manga")

                    val notificationManager = NotificationManagerCompat.from(context)

                    // Show progress notification if enabled
                    val progressEnabled: Boolean =
                        PrefManager.getVal(PrefName.UnreadChapterCheckingNotifications)
                    Logger.log("UnreadChapterNotificationTask: progressEnabled = $progressEnabled")

                    val hasPermission = hasNotificationPermission(context)
                    Logger.log("UnreadChapterNotificationTask: hasNotificationPermission = $hasPermission")

                    val progressNotification = if (progressEnabled) getProgressNotification(
                        context,
                        mangaList.size
                    ) else null

                    Logger.log("UnreadChapterNotificationTask: progressNotification = ${if (progressNotification != null) "created" else "null"}")

                    // Show initial notification
                    if (progressNotification != null && hasPermission) {
                        Logger.log("UnreadChapterNotificationTask: Showing initial progress notification (0/${mangaList.size})")
                        try {
                            notificationManager.notify(
                                Notifications.ID_UNREAD_CHAPTER_CHECK_PROGRESS,
                                progressNotification
                                    .setProgress(mangaList.size, 0, false)
                                    .setContentText("Processing ${mangaList.size} manga...")
                                    .build()
                            )
                        } catch (e: Exception) {
                            Logger.log("UnreadChapterNotificationTask: Error showing notification: ${e.message}")
                        }

                        // Auto-cancel after timeout
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(30_000L) // 30 seconds timeout
                            notificationManager.cancel(Notifications.ID_UNREAD_CHAPTER_CHECK_PROGRESS)
                        }
                    } else {
                        Logger.log("UnreadChapterNotificationTask: NOT showing notification - progressNotification=${progressNotification != null}, hasPermission=$hasPermission")
                    }

                    // Fetch MalSync data with progress callback
                    val mediaIds = mangaList.map { Pair(it.id, it.idMAL) }
                    val totalManga = mediaIds.size

                    Logger.log("UnreadChapterNotificationTask: Starting batch MalSync API calls for $totalManga manga")

                    val batchResults = MalSyncApi.getBatchProgressByMedia(mediaIds) { batchNum, totalBatches, processedCount, totalCount ->
                        // This callback is invoked by MalSyncApi after each batch of 50
                        Logger.log("UnreadChapterNotificationTask: Batch $batchNum/$totalBatches progress: $processedCount/$totalCount")

                        // Update progress notification
                        if (progressNotification != null && hasPermission) {
                            try {
                                val message = if (processedCount == totalCount) {
                                    "Completed: Processed $totalCount manga"
                                } else {
                                    "Batch $batchNum/$totalBatches: Processed $processedCount/$totalCount manga"
                                }

                                notificationManager.notify(
                                    Notifications.ID_UNREAD_CHAPTER_CHECK_PROGRESS,
                                    progressNotification
                                        .setProgress(totalCount, processedCount, false)
                                        .setContentText(message)
                                        .build()
                                )
                            } catch (e: Exception) {
                                Logger.log("UnreadChapterNotificationTask: Error updating notification: ${e.message}")
                            }
                        }
                    }

                    Logger.log("UnreadChapterNotificationTask: All batches completed, got ${batchResults.size} total results")

                    // Keep the final completion message visible for 2 seconds
                    if (progressNotification != null && hasPermission) {
                        delay(2000)
                    }

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
                            // Store notifications in the notification center
                            storeNotifications(newNotifications)
                        }
                    }

                    // Cancel progress notification
                    if (progressNotification != null) {
                        Logger.log("UnreadChapterNotificationTask: Canceling progress notification")
                        notificationManager.cancel(Notifications.ID_UNREAD_CHAPTER_CHECK_PROGRESS)
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
            // Use localized title
            val title = context.getString(R.string.notification_new_chapter_title)

            // Prepare source display (fallback if blank)
            val sourceDisplay = if (info.source.isBlank()) context.getString(R.string.notification_unknown_source) else info.source

            // Show unread count only if more than 1, use string resources
            val text = if (unreadCount == 1) {
                "${media.userPreferredName}: Chapter ${info.lastChapter}"
            } else {
                "${media.userPreferredName}: Chapter ${info.lastChapter} ($unreadCount unread)"
            }

            // Use subText for source only (short), keep main text without source for better layout
            val subText = context.getString(R.string.notification_source_subtext, sourceDisplay)

            // Create intent to open the media page
            val intent = Intent(context, MediaDetailsActivity::class.java).apply {
                putExtra("media", media as Serializable)
                putExtra("source", info.source)
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
                .setSubText(subText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
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

    private fun storeNotifications(newChapters: List<Pair<Media, UnreadChapterInfo>>) {
        val notificationStore = PrefManager.getNullableVal<List<UnreadChapterStore>>(
            PrefName.UnreadChapterNotificationStore,
            null
        ) ?: listOf()
        val newStore = notificationStore.toMutableList()

        // Keep only last 50 notifications
        if (newStore.size > 50) {
            newStore.sortByDescending { it.time }
            while (newStore.size > 50) {
                newStore.removeAt(newStore.size - 1)
            }
        }

        newChapters.forEach { (media, info) ->
            val unreadCount = info.lastChapter - info.userProgress

            // Check if notification already exists
            val exists = newStore.any {
                it.mediaId == media.id && it.lastChapter == info.lastChapter
            }

            if (!exists) {
                newStore.add(
                    UnreadChapterStore(
                        mediaId = media.id,
                        mediaName = media.userPreferredName,
                        lastChapter = info.lastChapter,
                        unreadCount = unreadCount,
                        source = info.source,
                        image = media.cover,
                        banner = media.banner,
                        time = System.currentTimeMillis()
                    )
                )
            }
        }

        PrefManager.setVal(PrefName.UnreadChapterNotificationStore, newStore)

        // Increment unread notification count
        PrefManager.setVal(
            PrefName.UnreadCommentNotifications,
            PrefManager.getVal<Int>(PrefName.UnreadCommentNotifications) + newChapters.size
        )
    }

    private fun getProgressNotification(
        context: Context,
        size: Int
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, Notifications.CHANNEL_UNREAD_CHAPTER_CHECK_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSmallIcon(ani.dantotsu.R.drawable.notification_icon)
            .setContentTitle("Checking Unread Chapters")
            .setProgress(size, 0, false)
            .setOngoing(true)
            .setAutoCancel(false)
    }
}
