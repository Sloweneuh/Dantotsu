package ani.dantotsu.notifications.unread

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.dantotsu.R
import ani.dantotsu.connections.IdCache
import ani.dantotsu.connections.comick.Comick
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.comick.ComickLibraryEntry
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.hasNotificationPermission
import ani.dantotsu.inAppIntentForLink
import ani.dantotsu.notifications.MediaCoverNotificationStyle
import ani.dantotsu.notifications.NotificationImageLoader
import ani.dantotsu.notifications.NotificationReadState
import ani.dantotsu.notifications.hasOtherActiveGroupMembers
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.floor

/**
 * New-chapter notifications for the titles a user is reading on Comick.
 *
 * The connected library ([Comick.getLibrary]) says what is followed as "reading" and the saved
 * chapter; the public catalog says what the newest chapter is. A title is announced once per new
 * latest chapter, like the MangaUpdates check this is modelled on.
 *
 * Runs inside [UnreadChapterNotificationTask], after the AniList and MangaUpdates checks, and skips
 * any title those just covered — matched through the AniList/MangaUpdates ids Comick lists for
 * it — so a series tracked in two places isn't announced twice.
 *
 * A notification opens the series where the user tracks it: the AniList page when they use
 * AniList (or MangaUpdates isn't an option), else the MangaUpdates page, else Comick's own. Its
 * "Mark as read" action records the chapter on Comick, offered only when the connection granted
 * write access.
 */
class ComickUnreadNotificationTask {

    private data class UnreadItem(
        val entry: ComickLibraryEntry,
        val latestChapter: Int,
        val userChapter: Int,
        /** [latestChapter] as Comick numbers it ("13.5") — what "Mark as read" records. */
        val latestExact: String,
        val link: String,
        /** Notification id, and the id the entry is stored under in the notification list. */
        val notifId: Int,
        val coverUrl: String?,
    )

    /** What a Comick title links to elsewhere, from its catalog entry. Cached in [IdCache]. */
    private data class Links(val anilistId: Int?, val mangaUpdates: String?, val coverUrl: String?) {
        /** Comick stores the MangaUpdates id either as the old number or in base 36. */
        val mangaUpdatesIds: Set<Long>
            get() = mangaUpdates?.let { setOfNotNull(it.toLongOrNull(), it.toLongOrNull(36)) }.orEmpty()
    }

    /**
     * @param trackedAnilistIds AniList manga the AniList check just covered.
     * @param trackedMuSeriesIds MangaUpdates series the MangaUpdates check just covered.
     */
    suspend fun checkComickUnread(
        context: Context,
        trackedAnilistIds: Set<Int>,
        trackedMuSeriesIds: Set<Long>,
    ) = withContext(Dispatchers.IO) {
        if (!PrefManager.getVal<Boolean>(PrefName.ComickNotificationsEnabled)) {
            Logger.log("ComickUnreadNotificationTask: notifications disabled")
            return@withContext
        }
        if (!Comick.getSavedToken()) {
            Logger.log("ComickUnreadNotificationTask: Comick not connected, skipping")
            return@withContext
        }
        val library = Comick.getLibrary(ComickApi.MEDIA_TYPE_MANGA, Comick.STATUS_READING) ?: run {
            Logger.log("ComickUnreadNotificationTask: library unavailable")
            return@withContext
        }
        Logger.log("ComickUnreadNotificationTask: ${library.size} titles being read")

        val behind = titlesBehind(library)
        Logger.log("ComickUnreadNotificationTask: ${behind.size} titles with unread chapters")
        if (behind.isEmpty()) return@withContext

        val anilistIn = PrefManager.getVal<String>(PrefName.AnilistToken).isNotEmpty()
        val muIn = MangaUpdates.getSavedToken() && !MangaUpdates.token.isNullOrBlank()

        val unread = behind.mapNotNull { (entry, latest, userChapter, latestExact) ->
            // A catalog lookup failing leaves the title for the next run rather than guessing
            // where it should link, or whether another check already covers it.
            val links = linksFor(entry) ?: return@mapNotNull null
            if (links.anilistId != null && links.anilistId in trackedAnilistIds) return@mapNotNull null
            if (links.mangaUpdatesIds.any { it in trackedMuSeriesIds }) return@mapNotNull null
            val link = when {
                links.anilistId != null && (anilistIn || !muIn || links.mangaUpdates == null) ->
                    "https://anilist.co/manga/${links.anilistId}"
                links.mangaUpdates != null && muIn ->
                    "https://www.mangaupdates.com/series/${links.mangaUpdates}"
                else -> ComickApi.webUrl(entry.slug ?: entry.hid)
            }
            UnreadItem(
                entry, latest, userChapter, latestExact, link,
                // The AniList id where there is one, so this replaces rather than stacks on an
                // older AniList notification for the same series; otherwise one derived from the
                // hid, far outside the range AniList ids occupy.
                notifId = links.anilistId ?: (("comick:" + entry.hid).hashCode() and 0x7FFFFFFF),
                coverUrl = links.coverUrl,
            )
        }
            // Two Comick entries of one series (an official release and a scanlation, both
            // followed) share an AniList id, so they'd post the same notification twice: keep
            // whichever is furthest along.
            .groupBy { it.notifId }
            .map { (_, sameSeries) -> sameSeries.maxBy { it.latestChapter } }
        IdCache.flush()

        val notifiedKey = "notified_comick_chapters"
        val prefs = context.getSharedPreferences("unread_notifications", Context.MODE_PRIVATE)
        val notified = prefs.getStringSet(notifiedKey, emptySet())?.toMutableSet() ?: mutableSetOf()
        val newItems = unread.filter { notified.add("${it.entry.hid}:${it.latestChapter}") }
        prefs.edit().putStringSet(notifiedKey, notified).apply()
        Logger.log("ComickUnreadNotificationTask: ${newItems.size} new chapters to notify")

        if (newItems.isEmpty() || !hasNotificationPermission(context)) return@withContext
        val icons = newItems.associate { it.notifId to NotificationImageLoader.loadBitmap(it.coverUrl) }
        withContext(Dispatchers.Main) {
            sendNotifications(context, newItems, icons)
            storeNotifications(newItems)
        }
    }

    private data class Behind(
        val entry: ComickLibraryEntry,
        val latestWhole: Int,
        val savedWhole: Int,
        val latestExact: String,
    )

    /**
     * Every followed title whose newest chapter is past the saved one.
     * Titles without saved progress are skipped: Comick says that means "unknown", not "none read".
     *
     * One catalog request per title, so it is paced to stay inside Comick's 200 requests/minute
     * per IP — two at a time, each holding its slot a little past the response.
     */
    private suspend fun titlesBehind(
        library: List<ComickLibraryEntry>,
    ): List<Behind> = coroutineScope {
        val slots = Semaphore(2)
        library.map { entry ->
            async {
                val saved = entry.progress?.number?.toDoubleOrNull() ?: return@async null
                val latestText = slots.withPermit {
                    ComickApi.getLatestChapter(entry.hid).also { delay(REQUEST_SPACING_MS) }
                }?.chap?.trim() ?: return@async null
                val latest = latestText.toDoubleOrNull() ?: return@async null
                // Whole chapters only, as everywhere else new chapters are counted: a ".5" extra
                // past the saved chapter doesn't announce itself.
                val latestWhole = floor(latest).toInt()
                val savedWhole = floor(saved).toInt()
                if (latestWhole > savedWhole) Behind(entry, latestWhole, savedWhole, latestText) else null
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * The title's AniList/MangaUpdates ids and cover, from its catalog entry — fetched once and
     * kept, since only the full details response carries them. Null when that fetch fails.
     */
    private suspend fun linksFor(entry: ComickLibraryEntry): Links? {
        val hid = entry.hid
        IdCache["comick_al_$hid"]?.let { al ->
            return Links(
                anilistId = al.toIntOrNull(),
                mangaUpdates = IdCache["comick_mu_$hid"]?.takeIf { it.isNotBlank() },
                coverUrl = IdCache["comick_cover_$hid"]?.takeIf { it.isNotBlank() },
            )
        }
        val comic = ComickApi.getComicDetails(entry.slug ?: hid)?.comic ?: return null
        val links = Links(
            anilistId = comic.links?.al?.trim()?.toIntOrNull()?.takeIf { it > 0 },
            mangaUpdates = comic.links?.mu?.trim()?.takeIf { it.isNotBlank() },
            coverUrl = comic.md_covers?.firstOrNull()?.b2key?.let { "https://meo.comick.pictures/$it" },
        )
        // Blank marks "looked up, none" so a title without links isn't fetched again every run.
        IdCache.putAll(
            mapOf(
                "comick_al_$hid" to (links.anilistId?.toString() ?: ""),
                "comick_mu_$hid" to (links.mangaUpdates ?: ""),
                "comick_cover_$hid" to (links.coverUrl ?: ""),
            )
        )
        return links
    }

    @SuppressLint("MissingPermission")
    private fun sendNotifications(context: Context, items: List<UnreadItem>, icons: Map<Int, Bitmap?>) {
        val notificationManager = NotificationManagerCompat.from(context)
        // Once for the batch: see MuUnreadNotificationTask.sendNotifications.
        val isGrouped = items.size > 1 ||
            context.hasOtherActiveGroupMembers(Notifications.GROUP_NEW_CHAPTERS, excludeId = -1)
        // Read-only connections can't record the chapter, so they don't get the action.
        val canWrite = Comick.canWrite()

        items.forEach { item ->
            val unreadCount = item.latestChapter - item.userChapter
            val title = item.entry.title ?: ""
            val genericLabel = context.getString(R.string.notification_new_chapter_title)
            // The exact number ("13.5"); the count stays in whole chapters.
            val chapterText = if (unreadCount == 1) {
                "Chapter ${item.latestExact}"
            } else {
                "Chapter ${item.latestExact} ($unreadCount unread)"
            }
            val sourceText = context.getString(R.string.notification_source_subtext, SOURCE)
            val plainBodyText = "$title: $chapterText · $sourceText"

            val readKey = NotificationReadState.chapterKey(item.notifId, item.latestChapter)
            val intent = (inAppIntentForLink(context, item.link) ?: return@forEach).apply {
                putExtra(NotificationReadState.EXTRA_KEY, readKey)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                item.notifId,
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
                .setSubText(genericLabel)
                .setContentIntent(pendingIntent)
                .setDeleteIntent(NotificationReadState.dismissIntent(context, readKey))
                .setAutoCancel(true)
                .setGroup(Notifications.GROUP_NEW_CHAPTERS)
            if (canWrite) builder.addAction(markAsReadAction(context, item))
            val cover = icons[item.notifId]
            if (cover != null) {
                MediaCoverNotificationStyle.apply(
                    context, builder, title, chapterText, cover,
                    sourceText = sourceText, label = genericLabel, showLabelInExpanded = isGrouped
                )
            } else {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(plainBodyText))
            }

            notificationManager.notify(item.notifId, builder.build())
            notificationManager.notify(Notifications.ID_NEW_CHAPTERS, newReleasesGroupSummary(context))
        }
    }

    /** "Mark as read": writes the announced chapter as the title's progress on Comick. */
    private fun markAsReadAction(context: Context, item: UnreadItem): NotificationCompat.Action {
        val intent = Intent(context, MarkReadNotificationReceiver::class.java).apply {
            action = MarkReadNotificationReceiver.ACTION
            putExtra(MarkReadNotificationReceiver.EXTRA_COMICK_HID, item.entry.hid)
            putExtra(MarkReadNotificationReceiver.EXTRA_COMICK_PROGRESS, item.latestExact)
            putExtra(MarkReadNotificationReceiver.EXTRA_PROGRESS, item.latestChapter)
            putExtra(MarkReadNotificationReceiver.EXTRA_NOTIFICATION_ID, item.notifId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            item.notifId,
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

    private fun storeNotifications(items: List<UnreadItem>) {
        val newStore = (PrefManager.getNullableVal<List<UnreadChapterStore>>(
            PrefName.UnreadChapterNotificationStore,
            null
        ) ?: listOf()).toMutableList()

        if (newStore.size > 50) {
            newStore.sortByDescending { it.time }
            while (newStore.size > 50) newStore.removeAt(newStore.size - 1)
        }

        items.forEach { item ->
            val exists = newStore.any { it.mediaId == item.notifId && it.lastChapter == item.latestChapter }
            if (!exists) {
                newStore.add(
                    UnreadChapterStore(
                        mediaId = item.notifId,
                        mediaName = item.entry.title ?: "",
                        lastChapter = item.latestChapter,
                        unreadCount = item.latestChapter - item.userChapter,
                        source = SOURCE,
                        image = item.coverUrl,
                        banner = item.coverUrl,
                        time = System.currentTimeMillis(),
                        link = item.link,
                    )
                )
            }
        }

        PrefManager.setVal(PrefName.UnreadChapterNotificationStore, newStore)
        NotificationReadState.reconcileChapters(newStore)
        NotificationReadState.markUnread(
            items.map { NotificationReadState.chapterKey(it.notifId, it.latestChapter) }
        )
    }

    private companion object {
        const val SOURCE = "Comick"

        /** How long each catalog request keeps its slot past the response; see [titlesBehind]. */
        const val REQUEST_SPACING_MS = 400L
    }
}
