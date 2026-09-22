package ani.dantotsu.notifications.unread

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.dantotsu.App
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.malsync.LanguageMapper
import ani.dantotsu.connections.malsync.MalSyncApi
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.connections.sync.UnreadSync
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.notifications.MediaCoverNotificationStyle
import ani.dantotsu.notifications.NotificationImageLoader
import ani.dantotsu.notifications.NotificationReadState
import ani.dantotsu.notifications.Task
import ani.dantotsu.notifications.hasOtherActiveGroupMembers
import ani.dantotsu.hasNotificationPermission
import eu.kanade.tachiyomi.data.notification.Notifications
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.containsMediaId
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable

class UnreadChapterNotificationTask : Task {

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
                        if (!PrefManager.getVal<Boolean>(PrefName.UnreadMangaNotificationsEnabled)) {
                            Logger.log("UnreadChapterNotificationTask: manga chapter notifications disabled; skipping manga check")
                            return@anilistCheck
                        }

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
                            // shared bakes in whichever device computed it's userProgress at that
                            // device's check time — which can be older than mangaList, fetched fresh on
                            // this device just above. Without reconciling against it, a chapter read
                            // since that snapshot (on any device, or on the AniList website) would keep
                            // reporting — and notifying about — a chapter this account has already read.
                            val reconciled = reconcileWithLiveProgress(shared, mangaList)
                            Logger.log(
                                "UnreadChapterNotificationTask: using shared cloud result (${shared.size}, " +
                                    "${reconciled.size} still unread after live progress); skipping MALSync scan"
                            )
                            // A completed scan from another device, so it answers for the whole
                            // list — anything it does not mention is caught up, not unknown.
                            handleUnreadResult(
                                context, reconciled, mangaList,
                                answeredIds = mangaList.mapTo(HashSet()) { it.id }
                            )
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
                                        userProgress = userProgress,
                                        // What this map becomes is the home row's cache (and the
                                        // copy other devices reuse over UnreadSync), so it has to
                                        // carry everything the row orders by. Dropped, every entry
                                        // reaching the row from here had no release date, and the
                                        // by-most-recent order — where an unknown date sorts last —
                                        // had nothing to place them by.
                                        latestChapterAt = result.lastEp.timestampMillis()
                                    )
                                }
                            }
                        }

                        Logger.log("UnreadChapterNotificationTask: found ${unreadInfo.size} manga with unread chapters")

                        // Publish for the user's other devices, then cache + notify locally.
                        UnreadSync.push(unreadInfo)
                        handleUnreadResult(
                            context, unreadInfo, mangaList,
                            answeredIds = batchResults.keys
                        )

                        if (progressNotification != null) {
                            Logger.log("UnreadChapterNotificationTask: Canceling progress notification")
                            notificationManager.cancel(Notifications.ID_UNREAD_CHAPTER_CHECK_PROGRESS)
                        }
                    }
                }

                // === AniList + MALSync anime episode check ===
                // Shares the credentials the manga check above already loaded into Anilist.token /
                // Anilist.userid — those are set unconditionally before that block's own early
                // returns, so they reflect stored credentials here regardless of why it stopped.
                run animeCheck@{
                    if (Anilist.token.isNullOrEmpty() || Anilist.userid == null) return@animeCheck

                    if (!PrefManager.getVal<Boolean>(PrefName.UnreadEpisodeNotificationsEnabled)) {
                        Logger.log("UnreadChapterNotificationTask: anime episode notifications disabled; skipping anime check")
                        return@animeCheck
                    }

                    val malMode = PrefManager.getVal<String>(PrefName.MalSyncCheckMode) ?: "both"
                    if (!PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled) || malMode == "manga") {
                        Logger.log("UnreadChapterNotificationTask: MALSync disabled or set to manga-only; skipping anime check")
                        return@animeCheck
                    }

                    val animeList: List<Media> = try {
                        val homePageData = Anilist.query.initHomePage()
                        homePageData["currentAnime"] ?: emptyList()
                    } catch (e: Exception) {
                        Logger.log("UnreadChapterNotificationTask: error fetching anime list: ${e.message}")
                        emptyList()
                    }

                    if (animeList.isEmpty()) {
                        Logger.log("UnreadChapterNotificationTask: no anime in watching list")
                        return@animeCheck
                    }

                    Logger.log("UnreadChapterNotificationTask: found ${animeList.size} anime")

                    val mediaIds = animeList.map { Pair(it.id, it.idMAL) }
                    // getBatchAnimeEpisodes already looks up each anime's own MALSync dub/sub
                    // preference (MalSyncLanguageHelper) — nothing extra to do to "follow" it here.
                    val batchResults = MalSyncApi.getBatchAnimeEpisodes(mediaIds)

                    Logger.log("UnreadChapterNotificationTask: anime batch completed, got ${batchResults.size} results")

                    val unreadInfo = mutableMapOf<Int, UnreadChapterInfo>()
                    for (media in animeList) {
                        val result = batchResults[media.id]
                        if (result != null && result.lastEp != null) {
                            val userProgress = media.userProgress ?: 0
                            val lastEpisode = result.lastEp.total
                            if (lastEpisode > userProgress) {
                                unreadInfo[media.id] = UnreadChapterInfo(
                                    mediaId = media.id,
                                    lastChapter = lastEpisode,
                                    source = result.source,
                                    userProgress = userProgress,
                                    latestChapterAt = result.lastEp.timestampMillis(),
                                    language = result.id
                                )
                            }
                        }
                    }

                    Logger.log("UnreadChapterNotificationTask: found ${unreadInfo.size} anime with unread episodes")
                    handleUnreadResult(
                        context, unreadInfo, animeList, isAnime = true,
                        answeredIds = batchResults.keys
                    )
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

    /**
     * Re-derives each entry's `userProgress` from [mangaList] — fetched fresh in this same run — and
     * drops anything the live figure shows as already read.
     *
     * Exists for the shared-cloud path: [UnreadSync.push] bakes in whichever device computed the
     * result's progress at that device's check time, which is only as fresh as that device's last scan.
     * Trusting it verbatim is how a chapter read since — on another device, or on the AniList website
     * itself, neither of which this device would otherwise hear about until its own next scan — kept
     * showing (and notifying) as unread.
     */
    private fun reconcileWithLiveProgress(
        unreadInfo: Map<Int, UnreadChapterInfo>,
        mangaList: List<Media>
    ): Map<Int, UnreadChapterInfo> {
        val liveProgressById = mangaList.associate { it.id to (it.userProgress ?: 0) }
        return unreadInfo.mapNotNull { (mediaId, info) ->
            val live = liveProgressById[mediaId] ?: return@mapNotNull mediaId to info
            if (info.lastChapter <= live) return@mapNotNull null
            mediaId to if (live != info.userProgress) info.copy(userProgress = live) else info
        }.toMap()
    }

    /**
     * Caches the result, broadcasts the update, and fires notifications for newly-unread
     * chapters/episodes. [isAnime] picks the episode wording and keeps the anime run's tracking
     * (notified set, notification-center store) separate from the manga one's — and skips
     * [UnreadCache], which only ever fed the home screen's manga-chapter row.
     */
    private suspend fun handleUnreadResult(
        context: Context,
        unreadInfo: Map<Int, UnreadChapterInfo>,
        mediaList: List<Media>,
        isAnime: Boolean = false,
        /** Which of [mediaList] this run actually got an answer for. See [UnreadCache.save]. */
        answeredIds: Set<Int> = emptySet(),
    ) {
        // The map passed in may have come from another device via UnreadSync (a cached result
        // computed under that device's exclude list at that time), so re-filter against this
        // device's current MalSync exclude list rather than trusting it was already applied.
        val excludeList = PrefManager.getVal<Set<String>>(PrefName.MalSyncExcludeList)
        val filteredUnreadInfo = unreadInfo.filterKeys { !excludeList.containsMediaId(it.toString()) }

        if (!isAnime) {
            try {
                UnreadCache.save(context, filteredUnreadInfo, mediaList, answeredIds)
                UnreadCache.broadcastUpdate(context)
            } catch (e: Exception) {
                Logger.log("UnreadChapterNotificationTask: Failed to cache/broadcast unread results: ${e.message}")
            }
        }

        val notifiedKey = if (isAnime) "notified_unread_episodes" else "notified_unread_chapters"
        val notified = getNotifiedSet(context, notifiedKey)
        val newNotifications = mutableListOf<Pair<Media, UnreadChapterInfo>>()

        filteredUnreadInfo.forEach { (mediaId, info) ->
            val key = "$mediaId:${info.lastChapter}"
            if (!notified.contains(key)) {
                val media = mediaList.find { it.id == mediaId }
                if (media != null) {
                    newNotifications.add(media to info)
                    notified.add(key)
                }
            }
        }

        saveNotifiedSet(context, notifiedKey, notified)

        Logger.log("UnreadChapterNotificationTask: ${newNotifications.size} new ${if (isAnime) "episodes" else "chapters"} to notify")

        if (newNotifications.isNotEmpty()) {
            // Fetched here, on the IO dispatcher this whole method already runs on — sendNotifications
            // itself is dispatched to Main below, where blocking network reads aren't allowed.
            val icons = newNotifications.associate { (media, _) ->
                media.id to NotificationImageLoader.loadBitmap(media.cover)
            }
            withContext(Dispatchers.Main) {
                sendNotifications(context, newNotifications, icons, isAnime)
                storeNotifications(newNotifications, isAnime)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendNotifications(
        context: Context,
        newChapters: List<Pair<Media, UnreadChapterInfo>>,
        icons: Map<Int, Bitmap?>,
        isAnime: Boolean = false,
    ) {
        val notificationManager = NotificationManagerCompat.from(context)
        val unitLabel = if (isAnime) "Episode" else "Chapter"
        val pendingLabel = if (isAnime) "unwatched" else "unread"
        // Computed once for the whole batch rather than per item: checking "is anyone else
        // active" before any of *this* batch has posted would otherwise miss that the batch
        // itself is about to post several — e.g. 3 chapters released together are grouped with
        // each other regardless of what (if anything) was already active beforehand.
        val isGrouped = newChapters.size > 1 ||
            context.hasOtherActiveGroupMembers(Notifications.GROUP_NEW_CHAPTERS, excludeId = -1)

        newChapters.forEach { (media, info) ->
            val unreadCount = info.lastChapter - info.userProgress
            // Title (media name), chapter/count and source/language are kept on separate lines —
            // a long title is ellipsized on its own rather than crowding the rest of the sentence.
            val title = media.userPreferredName
            val genericLabel = context.getString(
                if (isAnime) R.string.notification_new_episode_title else R.string.notification_new_chapter_title
            )
            val chapterText = if (unreadCount == 1) {
                "$unitLabel ${info.lastChapter}"
            } else {
                "$unitLabel ${info.lastChapter} ($unreadCount $pendingLabel)"
            }
            // Anime episodes come from whichever MALSync mirror has them; the language (dub/sub) is
            // what the user actually cares about, so show that instead of the streaming source —
            // as a dub/sub icon+code badge when there's a cover to put it next to, spelled out
            // otherwise (the plain-text fallback has no separate lines or icon slot to use).
            val languageBadge = if (isAnime && !info.language.isNullOrBlank()) {
                MediaCoverNotificationStyle.LanguageBadge(
                    LanguageMapper.mapLanguage(info.language).iconRes,
                    LanguageMapper.shortCode(info.language)
                )
            } else null
            val sourceText = if (languageBadge != null) {
                null
            } else {
                val sourceDisplay = if (info.source.isBlank())
                    context.getString(R.string.notification_unknown_source) else info.source
                context.getString(R.string.notification_source_subtext, sourceDisplay)
            }
            val fallbackSourceLine = sourceText ?: LanguageMapper.displayWithType(info.language!!)
            // In the body rather than the header's subtext slot, which the header keeps reserved
            // even without a cover image, and which some launchers otherwise hide entirely.
            val plainBodyText = "$title: $chapterText · $fallbackSourceLine"

            val readKey = NotificationReadState.chapterKey(media.id, info.lastChapter)
            val intent = Intent(context, MediaDetailsActivity::class.java).apply {
                putExtra("media", media as Serializable)
                putExtra("source", info.source)
                putExtra(NotificationReadState.EXTRA_KEY, readKey)
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

            val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_NEW_CHAPTERS_EPISODES)
                .setSmallIcon(R.drawable.notification_icon)
                .setContentTitle(title)
                .setContentText(plainBodyText)
                // A short, generic label rather than the (often long) media name — shown next to
                // the app name in the header, where a long title tends to just get dropped.
                .setSubText(genericLabel)
                .setContentIntent(pendingIntent)
                .setDeleteIntent(NotificationReadState.dismissIntent(context, readKey))
                .addAction(markAsReadAction(context, media, info.lastChapter, isAnime))
                .setAutoCancel(true)
                .setGroup(Notifications.GROUP_NEW_CHAPTERS)
            val cover = icons[media.id]
            if (cover != null) {
                MediaCoverNotificationStyle.apply(
                    context, builder, title, chapterText, cover, languageBadge, sourceText,
                    label = genericLabel, showLabelInExpanded = isGrouped
                )
            } else {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(plainBodyText))
            }

            notificationManager.notify(media.id, builder.build())
            notificationManager.notify(Notifications.ID_NEW_CHAPTERS, createGroupSummary(context))
        }
    }

    /**
     * "Mark as read" / "Mark as watched" — writes [lastChapter] as the new progress on whichever
     * tracker backs [media] without opening the app. The whole [Media] rides along on the intent so
     * [MarkReadNotificationReceiver] can reuse [ani.dantotsu.connections.updateProgressSuspending]
     * (mirrors included) rather than reconstruct it.
     */
    private fun markAsReadAction(
        context: Context,
        media: Media,
        lastChapter: Int,
        isAnime: Boolean,
    ): NotificationCompat.Action {
        val intent = Intent(context, MarkReadNotificationReceiver::class.java).apply {
            action = MarkReadNotificationReceiver.ACTION
            putExtra("media", media as Serializable)
            putExtra(MarkReadNotificationReceiver.EXTRA_PROGRESS, lastChapter)
            putExtra(MarkReadNotificationReceiver.EXTRA_NOTIFICATION_ID, media.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            media.id,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        val label = context.getString(
            if (isAnime) R.string.notification_action_mark_watched
            else R.string.notification_action_mark_read
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_circle_check, label, pendingIntent).build()
    }

    /**
     * Tapping the auto-collapsed stack of new-chapter notifications otherwise has no target and
     * just dismisses them; this summary gives the group header its own tap destination.
     */
    private fun createGroupSummary(context: Context): android.app.Notification {
        // Not "New Chapter Available" — this group holds anime episodes too (and, via
        // MuUnreadNotificationTask, MangaUpdates chapters), so a chapter-specific label would be
        // wrong whenever the stack mixes in an episode.
        val title = context.getString(R.string.notification_new_releases_title)
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
            // Without it, the group's own header — shown above the stack, distinct from each
            // child's — is just a bare timestamp next to the app name.
            .setSubText(title)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(title))
            .setGroup(Notifications.GROUP_NEW_CHAPTERS)
            .setGroupSummary(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    }

    private fun getNotifiedSet(context: Context, key: String): MutableSet<String> {
        val prefs = context.getSharedPreferences("unread_notifications", Context.MODE_PRIVATE)
        return prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveNotifiedSet(context: Context, key: String, notified: Set<String>) {
        val prefs = context.getSharedPreferences("unread_notifications", Context.MODE_PRIVATE)
        prefs.edit().putStringSet(key, notified).apply()
    }

    private fun storeNotifications(
        newChapters: List<Pair<Media, UnreadChapterInfo>>,
        isAnime: Boolean = false,
    ) {
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
                        time = System.currentTimeMillis(),
                        type = if (isAnime) "UnreadEpisode" else "UnreadChapter",
                        language = info.language
                    )
                )
            }
        }

        PrefManager.setVal(PrefName.UnreadChapterNotificationStore, newStore)
        NotificationReadState.reconcileChapters(newStore)
        NotificationReadState.markUnread(
            newChapters.map { (media, info) -> NotificationReadState.chapterKey(media.id, info.lastChapter) }
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

    /**
     * Debug-only: posts an unread chapter/episode notification built from the most recently
     * stored one of this type — so the layout is checked against a real title/cover/chapter
     * instead of made-up text — without needing an account that actually has unread chapters
     * right now to trigger it for real. Falls back to a synthetic placeholder when the store has
     * no entry of this type yet (e.g. a fresh install).
     */
    suspend fun sendTestNotification(context: Context, isAnime: Boolean = false) {
        if (!hasNotificationPermission(context)) return
        val storedType = if (isAnime) "UnreadEpisode" else "UnreadChapter"
        val stored = PrefManager.getNullableVal<List<UnreadChapterStore>>(
            PrefName.UnreadChapterNotificationStore, null
        )?.filter { it.type == storedType }?.maxByOrNull { it.time }

        val (media, info) = if (stored != null) {
            val progress = stored.lastChapter - stored.unreadCount
            Media(
                id = stored.mediaId,
                name = stored.mediaName,
                nameRomaji = stored.mediaName,
                userPreferredName = stored.mediaName,
                isAdult = false,
                cover = stored.image,
                banner = stored.banner,
                userProgress = progress
            ) to UnreadChapterInfo(
                mediaId = stored.mediaId,
                lastChapter = stored.lastChapter,
                source = stored.source,
                userProgress = progress,
                language = stored.language
            )
        } else {
            Media(
                id = TEST_MEDIA_ID,
                name = if (isAnime) "Test Anime" else "Test Manga",
                nameRomaji = if (isAnime) "Test Anime" else "Test Manga",
                userPreferredName = if (isAnime) "Test Anime" else "Test Manga",
                isAdult = false,
                cover = TEST_IMAGE_URL,
                userProgress = 5
            ) to UnreadChapterInfo(
                mediaId = TEST_MEDIA_ID,
                lastChapter = 6,
                source = "Test Source",
                userProgress = 5
            )
        }

        withContext(Dispatchers.IO) {
            val icons = mapOf(media.id to NotificationImageLoader.loadBitmap(media.cover))
            withContext(Dispatchers.Main) {
                sendNotifications(context, listOf(media to info), icons, isAnime)
            }
        }
    }

    companion object {
        @Volatile
        private var currentlyPerforming = false

        private const val TEST_MEDIA_ID = -999
        private const val TEST_IMAGE_URL =
            "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21-YCDoj1EkAxFn.jpg"
    }
}
