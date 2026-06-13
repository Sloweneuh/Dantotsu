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
import ani.dantotsu.connections.sync.UnreadSync
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

    companion object {
        @Volatile
        private var currentlyPerforming = false
    }

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

                // === AniList + MALSync check ===
                run anilistCheck@{
                    val storedToken = PrefManager.getVal<String>(PrefName.AnilistToken)
                    val storedUserIdStr = PrefManager.getVal<String>(PrefName.AnilistUserId)

                    if (!storedToken.isNullOrEmpty() && !storedUserIdStr.isNullOrEmpty()) {
                        Anilist.token = storedToken
                        val userId = storedUserIdStr.toIntOrNull()
                        if (userId != null && userId != 0) {
                            Anilist.userid = userId
                            Logger.log("UnreadChapterNotificationTask: Loaded Anilist credentials from storage (userId: $userId)")
                        } else {
                            Logger.log("UnreadChapterNotificationTask: Invalid userId in storage: $storedUserIdStr")
                            return@anilistCheck
                        }
                    } else {
                        Logger.log("UnreadChapterNotificationTask: No Anilist credentials found in storage (token empty: ${storedToken.isNullOrEmpty()}, userId empty: ${storedUserIdStr.isNullOrEmpty()})")
                        return@anilistCheck
                    }

                    if (!Anilist.token.isNullOrEmpty() && Anilist.userid != null) {
                        val mangaList: List<Media> = try {
                            val homePageData = Anilist.query.initHomePage()
                            homePageData["currentManga"] ?: emptyList()
                        } catch (e: Exception) {
                            Logger.log("UnreadChapterNotificationTask: error fetching manga list: ${e.message}")
                            emptyList()
                        }

                        if (mangaList.isEmpty()) {
                            Logger.log("UnreadChapterNotificationTask: no manga in reading list")
                            return@anilistCheck
                        }

                        Logger.log("UnreadChapterNotificationTask: found ${mangaList.size} manga")

                        // If another of the user's devices already produced a fresh result, reuse it
                        // instead of re-running the costly MALSync batch scan.
                        val sharedMaxAgeMs = maxOf(
                            PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval), 1L
                        ) * 60_000L
                        val shared = UnreadSync.fetchFresh(sharedMaxAgeMs)
                        if (shared != null) {
                            Logger.log("UnreadChapterNotificationTask: using shared cloud result (${shared.size}); skipping MALSync scan")
                            handleUnreadResult(context, shared, mangaList)
                            return@anilistCheck
                        }

                        val notificationManager = NotificationManagerCompat.from(context)

                        val progressEnabled: Boolean =
                            PrefManager.getVal(PrefName.UnreadChapterCheckingNotifications)

                        val hasPermission = hasNotificationPermission(context)

                        val progressNotification = if (progressEnabled) getProgressNotification(
                            context,
                            mangaList.size
                        ) else null

                        if (progressNotification != null && hasPermission) {
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

                            CoroutineScope(Dispatchers.Main).launch {
                                delay(30_000L)
                                notificationManager.cancel(Notifications.ID_UNREAD_CHAPTER_CHECK_PROGRESS)
                            }
                        }

                        val mediaIds = mangaList.map { Pair(it.id, it.idMAL) }

                        Logger.log("UnreadChapterNotificationTask: Starting batch MalSync API calls for ${mediaIds.size} manga")

                        val malMode = PrefManager.getVal<String>(PrefName.MalSyncCheckMode) ?: "both"
                        if (!PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled) || malMode == "anime") {
                            Logger.log("UnreadChapterNotificationTask: MALSync disabled or set to anime-only; skipping MalSync API calls")
                            if (progressNotification != null) {
                                notificationManager.cancel(Notifications.ID_UNREAD_CHAPTER_CHECK_PROGRESS)
                            }
                            return@anilistCheck
                        }

                        val batchResults = MalSyncApi.getBatchProgressByMedia(mediaIds) { batchNum, totalBatches, processedCount, totalCount ->
                            Logger.log("UnreadChapterNotificationTask: Batch $batchNum/$totalBatches progress: $processedCount/$totalCount")

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

                        // Publish for the user's other devices, then cache + notify locally.
                        UnreadSync.push(unreadInfo)
                        handleUnreadResult(context, unreadInfo, mangaList)

                        if (progressNotification != null) {
                            Logger.log("UnreadChapterNotificationTask: Canceling progress notification")
                            notificationManager.cancel(Notifications.ID_UNREAD_CHAPTER_CHECK_PROGRESS)
                        }
                    }
                }

                // === MangaUpdates unread check ===
                MuUnreadNotificationTask().checkMangaUpdatesUnread(context)

                currentlyPerforming = false
            }
            return true
        } catch (e: Exception) {
            Logger.log("UnreadChapterNotificationTask: error: ${e.message}")
            currentlyPerforming = false
            return false
        }
    }

    /** Caches the result, broadcasts the update, and fires notifications for newly-unread chapters. */
    private suspend fun handleUnreadResult(
        context: Context,
        unreadInfo: Map<Int, UnreadChapterInfo>,
        mangaList: List<Media>,
    ) {
        try {
            UnreadCache.save(context, unreadInfo, mangaList)
            UnreadCache.broadcastUpdate(context)
        } catch (e: Exception) {
            Logger.log("UnreadChapterNotificationTask: Failed to cache/broadcast unread results: ${e.message}")
        }

        val notifiedKey = "notified_unread_chapters"
        val notified = getNotifiedSet(context, notifiedKey)
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

        saveNotifiedSet(context, notifiedKey, notified)

        Logger.log("UnreadChapterNotificationTask: ${newNotifications.size} new chapters to notify")

        if (newNotifications.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                sendNotifications(context, newNotifications)
                storeNotifications(newNotifications)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendNotifications(
        context: Context,
        newChapters: List<Pair<Media, UnreadChapterInfo>>
    ) {
        val notificationManager = NotificationManagerCompat.from(context)

        newChapters.forEach { (media, info) ->
            val unreadCount = info.lastChapter - info.userProgress
            val title = context.getString(R.string.notification_new_chapter_title)
            val sourceDisplay = if (info.source.isBlank()) context.getString(R.string.notification_unknown_source) else info.source
            val text = if (unreadCount == 1) {
                "${media.userPreferredName}: Chapter ${info.lastChapter}"
            } else {
                "${media.userPreferredName}: Chapter ${info.lastChapter} ($unreadCount unread)"
            }
            val subText = context.getString(R.string.notification_source_subtext, sourceDisplay)

            val intent = Intent(context, MediaDetailsActivity::class.java).apply {
                putExtra("media", media as Serializable)
                putExtra("source", info.source)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                media.id,
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

    private fun getNotifiedSet(context: Context, key: String): MutableSet<String> {
        val prefs = context.getSharedPreferences("unread_notifications", Context.MODE_PRIVATE)
        return prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveNotifiedSet(context: Context, key: String, notified: Set<String>) {
        val prefs = context.getSharedPreferences("unread_notifications", Context.MODE_PRIVATE)
        prefs.edit().putStringSet(key, notified).apply()
    }

    private fun storeNotifications(newChapters: List<Pair<Media, UnreadChapterInfo>>) {
        val notificationStore = PrefManager.getNullableVal<List<UnreadChapterStore>>(
            PrefName.UnreadChapterNotificationStore,
            null
        ) ?: listOf()
        val newStore = notificationStore.toMutableList()

        if (newStore.size > 50) {
            newStore.sortByDescending { it.time }
            while (newStore.size > 50) newStore.removeAt(newStore.size - 1)
        }

        newChapters.forEach { (media, info) ->
            val unreadCount = info.lastChapter - info.userProgress
            val exists = newStore.any { it.mediaId == media.id && it.lastChapter == info.lastChapter }
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
        PrefManager.setVal(
            PrefName.UnreadCommentNotifications,
            PrefManager.getVal<Int>(PrefName.UnreadCommentNotifications) + newChapters.size
        )
    }

    private fun getProgressNotification(context: Context, size: Int): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, Notifications.CHANNEL_UNREAD_CHAPTER_CHECK_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle("Checking Unread Chapters")
            .setProgress(size, 0, false)
            .setOngoing(true)
            .setAutoCancel(false)
    }
}
