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
import ani.dantotsu.connections.malsync.MalSyncMu
import ani.dantotsu.connections.mangaupdates.MUDetailsCache
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.muMediaKey
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class MuUnreadNotificationTask : Task {

    companion object {
        @Volatile
        private var currentlyPerforming = false

        private const val TEST_MEDIA_ID = -999L
        private const val TEST_IMAGE_URL =
            "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21-YCDoj1EkAxFn.jpg"
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

        Logger.log("MuUnreadNotificationTask: checking MangaUpdates unread chapters")

        val unreadItems = currentUnreadItems(context)
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
            // MUMedia.coverUrl is only ever populated by getSeriesDetails — the reading-list fetch
            // above never calls it, so every item here starts with a null cover. Resolve it through
            // the same app-wide details cache the rest of the app uses for list-sourced MUMedia
            // (MUMediaAdapter, MUMediaDetailsActivity, ...), concurrently since it's per-item.
            val resolvedItems = coroutineScope {
                newItems.map { item ->
                    async {
                        if (item.media.coverUrl != null) return@async item
                        val coverUrl = MUDetailsCache.ensure(item.media.id)?.coverUrl
                            ?: return@async item
                        item.copy(media = item.media.copy(coverUrl = coverUrl))
                    }
                }.awaitAll()
            }
            // Fetched here, on the IO dispatcher this whole method already runs on — sendNotifications
            // itself is dispatched to Main below, where blocking network reads aren't allowed.
            val icons = resolvedItems.associate { it.media.id to NotificationImageLoader.loadBitmap(it.media.coverUrl) }
            withContext(Dispatchers.Main) {
                sendNotifications(context, resolvedItems, icons)
                storeNotifications(resolvedItems)
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

    /**
     * Fetches the reading list and returns every entry with an unread chapter right now — before
     * the "already notified" dedup filter [checkMangaUpdatesUnread] applies on top, so this
     * reflects genuinely current account state rather than just what hasn't been announced yet.
     * Also used, unfiltered, by [sendTestNotification] to find a real entry to test with. Returns
     * an empty list when logged out or a fetch fails (caller decides whether that's worth logging).
     */
    private suspend fun currentUnreadItems(context: Context): List<UnreadItem> {
        val tokenLoaded = MangaUpdates.getSavedToken()
        if (!tokenLoaded || MangaUpdates.token.isNullOrBlank()) {
            Logger.log("MuUnreadNotificationTask: MangaUpdates not logged in, skipping")
            return emptyList()
        }

        val allLists = try {
            MangaUpdates.getAllUserLists()
        } catch (e: Exception) {
            Logger.log("MuUnreadNotificationTask: getAllUserLists error: ${e.message}")
            return emptyList()
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

        return readingList.mapNotNull { muMedia ->
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
    }

    @SuppressLint("MissingPermission")
    private fun sendNotifications(context: Context, items: List<UnreadItem>, icons: Map<Long, Bitmap?>) {
        val notificationManager = NotificationManagerCompat.from(context)
        // Computed once for the whole batch rather than per item: checking "is anyone else
        // active" before any of *this* batch has posted would otherwise miss that the batch
        // itself is about to post several. Shares the group with UnreadChapterNotificationTask,
        // so an already-active chapter/episode notification counts as "grouped" too.
        val isGrouped = items.size > 1 ||
            context.hasOtherActiveGroupMembers(Notifications.GROUP_NEW_CHAPTERS, excludeId = -1)

        items.forEach { (muMedia, latestChapter, source) ->
            val unreadCount = latestChapter - (muMedia.userChapter ?: 0)
            // Title (media name), chapter/count and source are kept on separate lines — a long
            // title is ellipsized on its own rather than crowding the rest of the sentence.
            val title = muMedia.title ?: ""
            val genericLabel = context.getString(R.string.notification_new_chapter_title)
            val chapterText = if (unreadCount == 1) {
                "Chapter $latestChapter"
            } else {
                "Chapter $latestChapter ($unreadCount unread)"
            }
            val sourceText = context.getString(
                R.string.notification_source_subtext,
                source ?: "MangaUpdates"
            )
            // In the body rather than the header's subtext slot, which the header keeps reserved
            // even without a cover image, and which some launchers otherwise hide entirely.
            val plainBodyText = "$title: $chapterText · $sourceText"

            val notifId = muMediaKey(muMedia.id)
            val readKey = NotificationReadState.chapterKey(notifId, latestChapter)
            val intent = Intent(context, MUMediaDetailsActivity::class.java).apply {
                putExtra("muMedia", muMedia)
                putExtra(NotificationReadState.EXTRA_KEY, readKey)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

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

            val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_NEW_CHAPTERS_EPISODES)
                .setSmallIcon(R.drawable.notification_icon)
                .setContentTitle(title)
                .setContentText(plainBodyText)
                // A short, generic label rather than the (often long) media name — shown next to
                // the app name in the header, where a long title tends to just get dropped.
                .setSubText(genericLabel)
                .setContentIntent(pendingIntent)
                .setDeleteIntent(NotificationReadState.dismissIntent(context, readKey))
                .addAction(markAsReadAction(context, muMedia, latestChapter, notifId))
                .setAutoCancel(true)
                .setGroup(Notifications.GROUP_NEW_CHAPTERS)
            val cover = icons[muMedia.id]
            if (cover != null) {
                MediaCoverNotificationStyle.apply(
                    context, builder, title, chapterText, cover,
                    sourceText = sourceText, label = genericLabel, showLabelInExpanded = isGrouped
                )
            } else {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(plainBodyText))
            }

            notificationManager.notify(notifId, builder.build())
            notificationManager.notify(Notifications.ID_NEW_CHAPTERS, createGroupSummary(context))
        }
    }

    /**
     * "Mark as read" — writes [latestChapter] to MangaUpdates (and its mirrors) for this series
     * without opening the app. The [MUMedia] rides along so [MarkReadNotificationReceiver] can turn
     * it into a [ani.dantotsu.media.Media] and reuse the shared progress-update path.
     */
    private fun markAsReadAction(
        context: Context,
        muMedia: MUMedia,
        latestChapter: Int,
        notifId: Int,
    ): NotificationCompat.Action {
        val intent = Intent(context, MarkReadNotificationReceiver::class.java).apply {
            action = MarkReadNotificationReceiver.ACTION
            putExtra("muMedia", muMedia)
            putExtra(MarkReadNotificationReceiver.EXTRA_PROGRESS, latestChapter)
            putExtra(MarkReadNotificationReceiver.EXTRA_NOTIFICATION_ID, notifId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notifId,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_circle_check,
            context.getString(R.string.notification_action_mark_read),
            pendingIntent
        ).build()
    }

    /**
     * Tapping the auto-collapsed stack of new-chapter notifications otherwise has no target and
     * just dismisses them; this summary gives the group header its own tap destination. Shares
     * the group key with UnreadChapterNotificationTask since both feed the same channel.
     */
    private fun createGroupSummary(context: Context): android.app.Notification {
        // Not "New Chapter Available" — this group holds anime episodes too (via
        // UnreadChapterNotificationTask, which shares the same group key), so a chapter-specific
        // label would be wrong whenever the stack mixes in an episode.
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
        NotificationReadState.reconcileChapters(newStore)
        NotificationReadState.markUnread(
            items.map { (muMedia, latestChapter, _) ->
                NotificationReadState.chapterKey(muMediaKey(muMedia.id), latestChapter)
            }
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

    /**
     * Debug-only: posts a MangaUpdates unread notification built from a real, currently-unread
     * entry on the logged-in account — fetched live via [currentUnreadItems], the same way
     * [checkMangaUpdatesUnread] does, but without its "already notified" filter, so this stays
     * repeatable without waiting for a fresh chapter. Falls back to the most recently stored
     * MangaUpdates entry, then to a synthetic placeholder, when there's nothing to fetch (logged
     * out, or nothing currently unread).
     */
    suspend fun sendTestNotification(context: Context) {
        if (!hasNotificationPermission(context)) return
        withContext(Dispatchers.IO) {
            val (muMedia, item) = resolveTestItem(context)
            val icons = mapOf(muMedia.id to NotificationImageLoader.loadBitmap(muMedia.coverUrl))
            withContext(Dispatchers.Main) {
                sendNotifications(context, listOf(item), icons)
            }
        }
    }

    private suspend fun resolveTestItem(context: Context): Pair<MUMedia, UnreadItem> {
        val live = try {
            currentUnreadItems(context).firstOrNull()
        } catch (e: Exception) {
            Logger.log("MuUnreadNotificationTask: sendTestNotification live fetch failed: ${e.message}")
            null
        }
        if (live != null) {
            val coverUrl = live.media.coverUrl ?: MUDetailsCache.ensure(live.media.id)?.coverUrl
            val media = if (coverUrl != null && coverUrl != live.media.coverUrl) {
                live.media.copy(coverUrl = coverUrl)
            } else live.media
            return media to live.copy(media = media)
        }

        val stored = PrefManager.getNullableVal<List<UnreadChapterStore>>(
            PrefName.UnreadChapterNotificationStore, null
        )?.filter { it.source == "MangaUpdates" }?.maxByOrNull { it.time }

        if (stored != null) {
            val progress = stored.lastChapter - stored.unreadCount
            val m = MUMedia(
                id = stored.mediaId.toLong(),
                title = stored.mediaName,
                url = null,
                coverUrl = stored.image,
                listId = 0,
                userChapter = progress,
                userVolume = null,
                latestChapter = stored.lastChapter,
                bayesianRating = null,
                priority = null
            )
            return m to UnreadItem(m, stored.lastChapter, stored.source)
        }

        val m = MUMedia(
            id = TEST_MEDIA_ID,
            title = "Test MangaUpdates Manga",
            url = null,
            coverUrl = TEST_IMAGE_URL,
            listId = 0,
            userChapter = 5,
            userVolume = null,
            latestChapter = 6,
            bayesianRating = null,
            priority = null
        )
        return m to UnreadItem(m, 6, "Test Source")
    }
}
