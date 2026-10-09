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
import ani.dantotsu.R
import ani.dantotsu.notifications.Task
import ani.dantotsu.connections.IdCache
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.comick.Comick
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.comick.ComickLibraryEntry
import ani.dantotsu.connections.comick.ComickSync
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.connections.mangaupdates.MUDetailsCache
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.muMediaKey
import ani.dantotsu.hasNotificationPermission
import ani.dantotsu.inAppIntentForLink
import ani.dantotsu.media.Media
import ani.dantotsu.notifications.MediaCoverNotificationStyle
import ani.dantotsu.notifications.NotificationImageLoader
import ani.dantotsu.notifications.NotificationReadState
import ani.dantotsu.notifications.hasOtherActiveGroupMembers
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.containsMediaId
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
import java.io.Serializable
import java.time.OffsetDateTime
import kotlin.math.floor

/**
 * New-chapter notifications for the titles a user is reading on Comick.
 *
 * The connected library ([Comick.getLibrary]) says what is followed as "reading" and the saved
 * chapter; the public catalog says what the newest chapter is. A title is announced once per new
 * latest chapter, like the MangaUpdates check this is modelled on, and credited to the scanlation
 * group that uploaded it.
 *
 * Where the account's AniList list — or, for a title not on it, its MangaUpdates lists — is further
 * along than Comick's saved chapter, that tracker wins: Comick is brought up to it (when list sync
 * is on) and only chapters past it count as new. Its status wins too: Comick's is set to match
 * (under the same switch), and a title that tracker doesn't have as reading (or rereading) isn't
 * announced. Titles on the unread-chapter exclusion list —
 * under their AniList id, MangaUpdates key, or [ComickUnreadEntry.excludeId] — are never announced.
 *
 * Runs inside [UnreadChapterNotificationTask], after the AniList and MangaUpdates checks — or on its
 * own schedule while that one is off ([execute], with nothing known from them). A title
 * one of those also follows — matched through the AniList/MangaUpdates ids Comick lists for it — is
 * left to it up to the newest chapter it knows, and announced from here only past that: their
 * chapter counts come from MALSync and MangaUpdates, which often have nothing for a title or lag
 * Comick by days. What this announces is marked as announced for that check too, so the chapter
 * isn't announced a second time when its count catches up.
 *
 * A title is shown, and opened, as what it's tracked on: the AniList media Comick links — or
 * MangaBaka maps its MangaUpdates link to — when it's on the AniList list, else the MangaUpdates
 * series when it's on the MangaUpdates lists (an AniList media not yet moved over included). Off
 * both lists it's the AniList media (when AniList is connected), else the MangaUpdates series,
 * else Comick's own; it opens the AniList page when they use AniList (or MangaUpdates isn't an
 * option), else the MangaUpdates page, else Comick's own. Its
 * "Mark as read" records the chapter on Comick (given write access) and on the AniList or
 * MangaUpdates entry when the title is on one of those lists; "Mute" adds it to the exclusion list.
 *
 * Every title found behind, announced or not, is also kept for the home screen's unread row
 * ([UnreadCache.saveComick]).
 */
class ComickUnreadNotificationTask : Task {

    /** The standalone run: nothing is known from the other checks, which aren't running. */
    override suspend fun execute(context: Context): Boolean {
        if (currentlyPerforming) {
            Logger.log("ComickUnreadNotificationTask: already running")
            return false
        }
        return try {
            currentlyPerforming = true
            PrefManager.init(context)
            App.context = context
            checkComickUnread(context, emptyMap(), emptyMap())
            true
        } catch (e: Exception) {
            Logger.log("ComickUnreadNotificationTask: error: ${e.message}")
            false
        } finally {
            currentlyPerforming = false
        }
    }

    private data class UnreadItem(
        val entry: ComickLibraryEntry,
        val title: String,
        val latestChapter: Int,
        val userChapter: Int,
        /** [latestChapter] as Comick numbers it ("13.5") — what "Mark as read" records. */
        val latestExact: String,
        val link: String,
        /** Notification id, and the id the entry is stored under in the notification list. */
        val notifId: Int,
        val coverUrl: String?,
        /** Who uploaded the new chapter — its scanlation group, else Comick itself. */
        val source: String,
        val latestChapterAt: Long?,
        /**
         * The AniList media Comick links, with the account's list state when it's on the list —
         * null when the title is shown as the MangaUpdates series it's tracked as instead.
         */
        val anilistMedia: Media?,
        /** The series' entry on the account's MangaUpdates lists, when it has one. */
        val muListEntry: MUMedia?,
        /** The MangaUpdates series Comick links, for showing the title as one. */
        val muSeriesId: Long?,
        /** The AniList id Comick links, whether or not it's on the account's list. */
        val anilistId: Int? = null,
        /** Every MangaUpdates id Comick's link can stand for (see [Links.mangaUpdatesIds]). */
        val muSeriesIds: Set<Long> = emptySet(),
        /** Every followed Comick entry of this series behind on this chapter, [entry] included. */
        val seriesHids: List<String> = listOf(entry.hid),
    ) {
        val onAnilistList: Boolean get() = anilistMedia?.userStatus != null

        /** The exclusion-list entry "Mute" adds: "id||cover||title", as the list editors write. */
        val excludeEntry: String
            get() {
                val id = anilistMedia?.id?.toString()
                    ?: muSeriesId?.let { muMediaKey(it).toString() }
                    ?: ComickUnreadEntry.excludeId(entry.hid)
                return "$id||${coverUrl.orEmpty()}||$title"
            }

        fun toEntry() = ComickUnreadEntry(
            hid = entry.hid,
            slug = entry.slug,
            title = title,
            coverUrl = coverUrl,
            anilistMedia = anilistMedia,
            // The series as MangaUpdates has it on the account's lists, or a stand-in carrying
            // what Comick knows — only when there's no AniList media to show instead.
            muMedia = if (anilistMedia != null) null else muListEntry ?: muSeriesId?.let {
                MUMedia(
                    id = it, title = title, url = null, coverUrl = coverUrl, listId = -1,
                    userChapter = userChapter, userVolume = null, latestChapter = null,
                    bayesianRating = null, priority = null,
                )
            },
            progress = userChapter,
            latestChapter = latestChapter,
            source = source,
            latestChapterAt = latestChapterAt,
            notifId = notifId,
            publicationStatus = entry.publicationStatusWord,
            bayesianRating = entry.bayesian_rating,
        )
    }

    /** What a Comick title links to elsewhere, from its catalog entry. Cached in [IdCache]. */
    private data class Links(val anilistId: Int?, val mangaUpdates: String?, val coverUrl: String?) {
        /** Comick stores the MangaUpdates id either as the old number or in base 36. */
        val mangaUpdatesIds: Set<Long>
            get() = mangaUpdates?.let { setOfNotNull(it.toLongOrNull(), it.toLongOrNull(36)) }.orEmpty()

        /**
         * The current MangaUpdates series id, when Comick gives it in base 36. An all-digit value is
         * an old-style id, which no MangaUpdates screen takes without a lookup first.
         */
        val muSeriesId: Long?
            get() = mangaUpdates?.takeIf { s -> s.any { it.isLetter() } }?.toLongOrNull(36)
    }

    /**
     * @param anilistKnownLatest the newest chapter the AniList check knows, by AniList id.
     * @param muKnownLatest the newest chapter the MangaUpdates check knows, by series id.
     */
    suspend fun checkComickUnread(
        context: Context,
        anilistKnownLatest: Map<Int, Int>,
        muKnownLatest: Map<Long, Int>,
    ) = withContext(Dispatchers.IO) {
        if (!PrefManager.getVal<Boolean>(PrefName.ComickNotificationsEnabled)) {
            Logger.log("ComickUnreadNotificationTask: notifications disabled")
            clearRow(context)
            return@withContext
        }
        if (!Comick.getSavedToken()) {
            Logger.log("ComickUnreadNotificationTask: Comick not connected, skipping")
            clearRow(context)
            return@withContext
        }
        val (unread, savedByHid) = currentUnread(anilistKnownLatest, muKnownLatest) ?: return@withContext

        UnreadCache.saveComick(unread.map { it.toEntry() })
        UnreadCache.broadcastUpdate(context)

        val notifiedKey = "notified_comick_chapters"
        val prefs = context.getSharedPreferences("unread_notifications", Context.MODE_PRIVATE)
        val notified = prefs.getStringSet(notifiedKey, emptySet())?.toMutableSet() ?: mutableSetOf()
        // Keys are "<hid>:<chapter>", pruned against the whole library rather than this run's
        // unread titles: one left out for a run (a failed lookup, or the AniList/MangaUpdates check
        // knowing the chapter for now) would otherwise lose its key and be announced again.
        pruneNotified(notified, savedByHid)
        // The id each announced chapter went out under, as "<hid>:<chapter>=<id>".
        val announcedIds = prefs.getStringSet(ANNOUNCED_IDS_KEY, emptySet()).orEmpty()
            .mapNotNull { e ->
                val id = e.substringAfterLast('=', "").toIntOrNull() ?: return@mapNotNull null
                e.substringBeforeLast('=') to id
            }.toMap().toMutableMap()
        // Announced already, but shown as something else now — converted from MangaUpdates to
        // AniList since — so the notification still out moves over to the new id, by its old one.
        val moved = unread.mapNotNull { item ->
            val keys = item.seriesHids.map { "$it:${item.latestChapter}" }
            if (keys.none { it in notified }) return@mapNotNull null
            val oldId = keys.firstNotNullOfOrNull { announcedIds[it] } ?: return@mapNotNull null
            if (oldId == item.notifId) null else item to oldId
        }
        // Any entry of the series having announced the chapter counts, so which of two followed
        // entries wins the de-dup in [currentUnread] can change without a repeat.
        val newItems = unread.filter { item ->
            val keys = item.seriesHids.map { "$it:${item.latestChapter}" }
            keys.none { it in notified }.also { notified += keys }
        }
        (newItems + moved.map { it.first }).forEach { item ->
            item.seriesHids.forEach { announcedIds["$it:${item.latestChapter}"] = item.notifId }
        }
        announcedIds.keys.retainAll(notified)
        // Announced here, so the AniList/MangaUpdates check mustn't announce it again once its own
        // count reaches it. Their keys are "<id>:<chapter>" too.
        val anilistNotified = prefs.getStringSet(ANILIST_NOTIFIED_KEY, emptySet()).orEmpty().toMutableSet()
        val muNotified = prefs.getStringSet(MU_NOTIFIED_KEY, emptySet()).orEmpty().toMutableSet()
        newItems.forEach { item ->
            item.anilistId?.let { anilistNotified += "$it:${item.latestChapter}" }
            item.muSeriesIds.forEach { muNotified += "$it:${item.latestChapter}" }
        }
        prefs.edit()
            .putStringSet(notifiedKey, notified)
            .putStringSet(ANILIST_NOTIFIED_KEY, anilistNotified)
            .putStringSet(MU_NOTIFIED_KEY, muNotified)
            .putStringSet(ANNOUNCED_IDS_KEY, announcedIds.map { (k, id) -> "$k=$id" }.toSet())
            .apply()
        Logger.log("ComickUnreadNotificationTask: ${newItems.size} new chapters to notify, ${moved.size} moved")

        if (moved.isNotEmpty()) moveNotifications(context, moved)
        if (newItems.isEmpty() || !hasNotificationPermission(context)) return@withContext
        val announced = withMuCovers(newItems)
        val icons = announced.associate { it.notifId to NotificationImageLoader.loadBitmap(it.coverUrl) }
        withContext(Dispatchers.Main) {
            sendNotifications(context, announced, icons)
            storeNotifications(announced)
        }
    }

    /**
     * Brings notifications already out up to how their titles are shown now — the AniList media
     * a MangaUpdates series was converted to. One still on screen is reposted under its new id
     * without sound, then the old one cancelled; a dismissed one stays dismissed. The entry in the
     * notification centre follows either way, keeping its time and read state.
     */
    private suspend fun moveNotifications(context: Context, moved: List<Pair<UnreadItem, Int>>) {
        val active = runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .activeNotifications.map { it.id }.toSet()
        }.getOrDefault(emptySet())
        val items = withMuCovers(moved.map { it.first })
        val oldIds = moved.associate { (item, oldId) -> item.notifId to oldId }
        val onScreen = items.filter { oldIds[it.notifId] in active }
        val icons = onScreen.associate { it.notifId to NotificationImageLoader.loadBitmap(it.coverUrl) }
        withContext(Dispatchers.Main) {
            if (onScreen.isNotEmpty() && hasNotificationPermission(context)) {
                // Posted before the old ones go, so the group summary always has a child.
                sendNotifications(context, onScreen, icons, silent = true)
                val nm = NotificationManagerCompat.from(context)
                onScreen.forEach { nm.cancel(oldIds.getValue(it.notifId)) }
            }
            moveStoredNotifications(items, oldIds)
        }
    }

    private fun moveStoredNotifications(items: List<UnreadItem>, oldIds: Map<Int, Int>) {
        val store = PrefManager.getNullableVal<List<UnreadChapterStore>>(
            PrefName.UnreadChapterNotificationStore, null
        ) ?: return
        val byOld = items.associateBy { oldIds.getValue(it.notifId) to it.latestChapter }
        val unreadNow = mutableListOf<String>()
        val readOld = mutableListOf<String>()
        val updated = store.map { stored ->
            val item = byOld[stored.mediaId to stored.lastChapter] ?: return@map stored
            val oldKey = NotificationReadState.keyOf(stored)
            readOld += oldKey
            if (NotificationReadState.isUnread(oldKey)) {
                unreadNow += NotificationReadState.chapterKey(item.notifId, item.latestChapter)
            }
            stored.copy(
                mediaId = item.notifId,
                mediaName = item.title,
                image = item.coverUrl,
                banner = item.coverUrl,
                link = item.link,
            )
        }
        if (readOld.isEmpty()) return
        PrefManager.setVal(PrefName.UnreadChapterNotificationStore, updated)
        NotificationReadState.markRead(readOld)
        NotificationReadState.markUnread(unreadNow)
    }

    /**
     * [items] with the MangaUpdates cover in place of Comick's for a title shown as a MangaUpdates
     * series: the reading-list fetch carries no covers, so it comes from [MUDetailsCache].
     */
    private suspend fun withMuCovers(items: List<UnreadItem>): List<UnreadItem> = coroutineScope {
        items.map { item ->
            async {
                if (item.anilistMedia?.cover != null) return@async item
                val muId = item.muSeriesId ?: return@async item
                val cover = item.muListEntry?.coverUrl ?: MUDetailsCache.ensure(muId)?.coverUrl
                    ?: return@async item
                item.copy(coverUrl = cover)
            }
        }.awaitAll()
    }

    /** Takes the Comick half off the home row once this check no longer runs. */
    private fun clearRow(context: Context) {
        if (UnreadCache.cachedComick().isEmpty()) return
        UnreadCache.saveComick(emptyList())
        UnreadCache.broadcastUpdate(context)
    }

    /**
     * What [currentUnread] found: the titles behind, and the saved chapter of every title in the
     * library by hid — the furthest of Comick's and the tracker's where that's known — for pruning
     * the "already announced" keys. A title without saved progress anywhere maps to
     * [Int.MIN_VALUE], so its keys are kept.
     */
    private data class UnreadResult(val items: List<UnreadItem>, val savedByHid: Map<String, Int>)

    /**
     * Every followed title with a chapter past the furthest saved progress, before the "already
     * announced" filter. Null when the library can't be read, so the last answer stands.
     */
    private suspend fun currentUnread(
        anilistKnownLatest: Map<Int, Int>,
        muKnownLatest: Map<Long, Int>,
    ): UnreadResult? {
        val library = Comick.getLibrary(ComickApi.MEDIA_TYPE_MANGA, Comick.STATUS_READING) ?: run {
            Logger.log("ComickUnreadNotificationTask: library unavailable")
            return null
        }
        Logger.log("ComickUnreadNotificationTask: ${library.size} titles being read")

        // A catalog lookup failing leaves the title for the next run rather than guessing where it
        // should link, or whether another check already covers it.
        val linked = library.mapNotNull { entry -> linksFor(entry)?.let { entry to it } }
        // Every followed title Comick links to AniList is a ready-made match for list sync.
        linked.forEach { (entry, links) -> links.anilistId?.let { ComickSync.rememberMatch(it, entry.hid) } }
        IdCache.flush()

        val anilistMedia = anilistMedia(linked.mapNotNull { it.second.anilistId }.distinct())
        // MangaUpdates only speaks for titles the AniList list doesn't hold.
        val needMu = linked.any { (_, links) ->
            links.mangaUpdatesIds.isNotEmpty() && links.anilistId?.let { anilistMedia[it] }?.userStatus == null
        }
        val muEntries = if (needMu) muListEntries() else emptyMap()

        val excludeList = PrefManager.getVal<Set<String>>(PrefName.MalSyncExcludeList)
        val candidates = linked.mapNotNull { (entry, links) ->
            val media = links.anilistId?.let { anilistMedia[it] }
            val muEntry = links.mangaUpdatesIds.firstNotNullOfOrNull { muEntries[it] }
            val comickSaved = entry.progress?.number?.toDoubleOrNull()
            // The tracker the title is on: AniList, else MangaUpdates.
            val (trackerName, trackerSaved) = when {
                media?.userStatus != null -> "AniList" to media.userProgress
                muEntry != null -> "MangaUpdates" to muEntry.userChapter
                else -> null to null
            }
            // The tracker's status wins over Comick's, as its progress does: a title paused, dropped,
            // completed or planned there isn't being read, whatever Comick's list still says.
            val trackerStatus = when {
                media?.userStatus != null -> media.userStatus
                muEntry != null -> MU_STATUSES[muEntry.listId]
                else -> null
            }
            // A tracker ahead, or holding the title under another status: Comick is brought in step,
            // and the chapters the tracker counts as read stay read.
            val progressAhead = trackerSaved != null && trackerSaved > 0 && trackerSaved > (comickSaved ?: 0.0)
            val statusDiffers = trackerStatus != null &&
                ComickSync.toCanon(entry.status) != ComickSync.comparableCanon(trackerStatus)
            if (progressAhead || statusDiffers) {
                Logger.log(
                    "ComickUnreadNotificationTask: ${entry.hid} Comick=$comickSaved/${entry.status} vs " +
                        "$trackerName=$trackerSaved/$trackerStatus, catching up"
                )
                ComickSync.catchUp(
                    entry,
                    progress = trackerSaved.takeIf { progressAhead },
                    status = trackerStatus.takeIf { statusDiffers },
                )
            }
            // Without saved progress anywhere, nothing is known to be unread: Comick says no
            // progress means "unknown", not "none read".
            val saved = if (progressAhead) trackerSaved!!.toDouble() else comickSaved ?: return@mapNotNull null
            if (trackerStatus != null && trackerStatus !in READING_STATUSES) {
                Logger.log("ComickUnreadNotificationTask: ${entry.hid} is $trackerStatus on $trackerName, skipping")
                return@mapNotNull null
            }
            // The same exclusions as the AniList and MangaUpdates checks, under any of its ids.
            if (links.anilistId != null && excludeList.containsMediaId(links.anilistId.toString())) return@mapNotNull null
            if (links.mangaUpdatesIds.any { excludeList.containsMediaId(muMediaKey(it).toString()) }) return@mapNotNull null
            if (excludeList.containsMediaId(ComickUnreadEntry.excludeId(entry.hid))) return@mapNotNull null
            Candidate(entry, links, floor(saved).toInt(), media, muEntry)
        }

        val behind = titlesBehind(candidates).filter { b ->
            val links = b.candidate.links
            // The newest chapter the AniList or MangaUpdates check already knows for this title.
            val known = listOfNotNull(links.anilistId?.let { anilistKnownLatest[it] })
                .plus(links.mangaUpdatesIds.mapNotNull { muKnownLatest[it] })
                .maxOrNull()
            (known == null || b.latestWhole > known).also { ahead ->
                if (!ahead) Logger.log(
                    "ComickUnreadNotificationTask: ${b.candidate.entry.hid} ch ${b.latestWhole} already known " +
                        "to the AniList/MangaUpdates check ($known), leaving it there"
                )
            }
        }
        Logger.log("ComickUnreadNotificationTask: ${behind.size} titles with unread chapters")

        val anilistIn = PrefManager.getVal<String>(PrefName.AnilistToken).isNotEmpty()
        val muIn = MangaUpdates.getSavedToken() && !MangaUpdates.token.isNullOrBlank()

        val candidateSaved = candidates.associate { it.entry.hid to it.savedWhole }
        val savedByHid = library.associate { entry ->
            entry.hid to (candidateSaved[entry.hid]
                ?: entry.progress?.number?.toDoubleOrNull()?.let { floor(it).toInt() }
                ?: Int.MIN_VALUE)
        }

        val items = behind.map { (candidate, latest, latestExact, group, latestAt) ->
            val (entry, links, userChapter, media, muEntry) = candidate
            // Shown as the list it's tracked on: a title with an AniList media but kept only on
            // the MangaUpdates lists (not yet moved over) is the MangaUpdates series — opened,
            // muted and replaced as the MangaUpdates check's own notification would be. Off both
            // lists it's the AniList media when there is one.
            val shownMedia = media?.takeIf { it.userStatus != null || muEntry == null }
            val shownAsMu = shownMedia == null && muEntry != null
            val link = when {
                shownAsMu -> "https://www.mangaupdates.com/series/${muEntry!!.id.toString(36)}"
                links.anilistId != null && (anilistIn || !muIn || links.mangaUpdates == null) ->
                    "https://anilist.co/manga/${links.anilistId}"
                links.mangaUpdates != null && muIn ->
                    "https://www.mangaupdates.com/series/${links.mangaUpdates}"
                else -> ComickApi.webUrl(entry.slug ?: entry.hid)
            }
            UnreadItem(
                entry = entry,
                // Shown as what it's tracked as: the AniList media, else the MangaUpdates series,
                // else Comick's own title and cover.
                title = shownMedia?.userPreferredName?.takeIf { it.isNotBlank() }
                    ?: muEntry?.title?.takeIf { it.isNotBlank() } ?: entry.title ?: "",
                latestChapter = latest,
                userChapter = userChapter,
                latestExact = latestExact,
                link = link,
                // The id the AniList or MangaUpdates check posts the series under, so this
                // replaces rather than stacks on its older notification; otherwise one derived
                // from the hid, far outside the range AniList ids occupy.
                notifId = if (shownAsMu) muMediaKey(muEntry!!.id)
                else links.anilistId ?: (("comick:" + entry.hid).hashCode() and 0x7FFFFFFF),
                // The MangaUpdates cover only as far as it's already cached here; announced titles
                // fetch it in [withMuCovers].
                coverUrl = shownMedia?.cover ?: muEntry?.coverUrl
                    ?: (muEntry?.id ?: links.muSeriesId)?.let { MUDetailsCache.get(it)?.coverUrl }
                    ?: entry.cover_url?.takeIf { it.isNotBlank() } ?: links.coverUrl,
                source = group ?: SOURCE,
                latestChapterAt = latestAt,
                anilistMedia = shownMedia,
                muListEntry = muEntry,
                muSeriesId = muEntry?.id ?: links.muSeriesId,
                anilistId = links.anilistId,
                muSeriesIds = links.mangaUpdatesIds,
            )
        }
            // Two Comick entries of one series (an official release and a scanlation, both
            // followed) share an AniList id, so they'd post the same notification twice: keep
            // whichever is furthest along.
            .groupBy { it.notifId }
            .map { (_, sameSeries) ->
                val best = sameSeries.maxBy { it.latestChapter }
                best.copy(seriesHids = sameSeries.map { it.entry.hid }.distinct())
            }
        return UnreadResult(items, savedByHid)
    }

    /**
     * A title left to check: [savedWhole] is the further of its Comick progress and the tracker's.
     * [anilistMedia] and [muEntry] are what the title is on AniList and on the MangaUpdates lists.
     */
    private data class Candidate(
        val entry: ComickLibraryEntry,
        val links: Links,
        val savedWhole: Int,
        val anilistMedia: Media?,
        val muEntry: MUMedia?,
    )

    private data class Behind(
        val candidate: Candidate,
        val latestWhole: Int,
        val latestExact: String,
        /** The scanlation group(s) that uploaded [latestExact], when Comick names any. */
        val group: String?,
        val latestAt: Long?,
    )

    /**
     * Every candidate whose newest chapter is past the saved one.
     *
     * One catalog request per title the library's newest release doesn't already rule out, paced
     * to stay inside Comick's 200 requests/minute per IP — two at a time, each holding its slot a
     * little past the response.
     */
    private suspend fun titlesBehind(
        candidates: List<Candidate>,
    ): List<Behind> = coroutineScope {
        val slots = Semaphore(2)
        candidates.map { candidate ->
            async {
                // The library already names the newest release; a title it puts at or before the
                // saved chapter costs no catalog request. Its languages are unspecified, so it only
                // ever rules a title out — the chapter list below has the final say.
                val listed = candidate.entry.last_released_number?.toDoubleOrNull()
                if (listed != null && floor(listed).toInt() <= candidate.savedWhole) return@async null
                val chapter = slots.withPermit {
                    ComickApi.getLatestChapter(candidate.entry.hid).also { delay(REQUEST_SPACING_MS) }
                } ?: return@async null
                val latestText = chapter.chap?.trim() ?: return@async null
                val latest = latestText.toDoubleOrNull() ?: return@async null
                // Whole chapters only, as everywhere else new chapters are counted: a ".5" extra
                // past the saved chapter doesn't announce itself.
                val latestWhole = floor(latest).toInt()
                if (latestWhole <= candidate.savedWhole) return@async null
                val group = chapter.group_name.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
                    .distinct().joinToString(", ").ifEmpty { null }
                val latestAt = chapter.created_at?.let {
                    runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
                }
                Behind(candidate, latestWhole, latestText, group, latestAt)
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * The AniList media for [anilistIds], each with the account's list state — `userStatus` is
     * null for one not on the list. Empty when AniList isn't connected or doesn't answer; the
     * check then goes by Comick and MangaUpdates alone.
     */
    private suspend fun anilistMedia(anilistIds: List<Int>): Map<Int, Media> {
        if (anilistIds.isEmpty()) return emptyMap()
        val token = PrefManager.getVal<String>(PrefName.AnilistToken)
        if (token.isEmpty()) return emptyMap()
        if (Anilist.token.isNullOrEmpty()) Anilist.token = token
        // One page holds 50 entries at most, so the ids go in batches of that size.
        return anilistIds.chunked(50).flatMap { batch ->
            runCatching { Anilist.query.getMediaBatch(batch, mediaType = "MANGA") }
                .onFailure { Logger.log("ComickUnreadNotificationTask: AniList lookup failed: ${it.message}") }
                .getOrNull().orEmpty()
        }.associateBy { it.id }
    }

    /** Every series on the account's MangaUpdates lists, by series id. Empty when unavailable. */
    private suspend fun muListEntries(): Map<Long, MUMedia> {
        if (!MangaUpdates.getSavedToken() || MangaUpdates.token.isNullOrBlank()) return emptyMap()
        return runCatching { MangaUpdates.getAllUserLists() }
            .onFailure { Logger.log("ComickUnreadNotificationTask: MangaUpdates lists failed: ${it.message}") }
            .getOrNull().orEmpty()
            .values.flatten()
            // A series can also sit in custom lists; the standard list it's on is what gives its
            // status, so that entry is the one kept.
            .groupBy { it.id }
            .mapValues { (_, entries) -> entries.firstOrNull { it.listId in MU_STATUSES } ?: entries.first() }
    }

    /**
     * The title's AniList/MangaUpdates ids and cover, from its catalog entry — fetched once and
     * kept, since only the full details response carries them. Null when that fetch fails.
     * Fetches are spaced like [titlesBehind]'s, since a first run looks up the whole library.
     */
    private suspend fun linksFor(entry: ComickLibraryEntry): Links? =
        comickLinksFor(entry)?.let { withMangaBakaAnilist(entry.hid, it) }

    /**
     * [links] with the AniList id MangaBaka maps its MangaUpdates series to, when Comick links only
     * MangaUpdates — otherwise a title the account reads on AniList would be shown, opened and
     * de-duplicated as a MangaUpdates series. MangaBaka keeps a found id, so this asks it once.
     */
    private suspend fun withMangaBakaAnilist(hid: String, links: Links): Links {
        if (links.anilistId != null) return links
        val muId = links.muSeriesId ?: return links
        val anilistId = runCatching { MangaBakaApi.getCrossIdsFromMangaUpdates(muId).anilistId }
            .onFailure { Logger.log("ComickUnreadNotificationTask: MangaBaka lookup failed for $hid: ${it.message}") }
            .getOrNull() ?: return links
        return links.copy(anilistId = anilistId)
    }

    private suspend fun comickLinksFor(entry: ComickLibraryEntry): Links? {
        val hid = entry.hid
        IdCache["comick_al_$hid"]?.let { al ->
            return Links(
                anilistId = al.toIntOrNull(),
                mangaUpdates = IdCache["comick_mu_$hid"]?.takeIf { it.isNotBlank() },
                coverUrl = IdCache["comick_cover_$hid"]?.takeIf { it.isNotBlank() },
            )
        }
        val comic = ComickApi.getComicDetails(entry.slug ?: hid).also { delay(REQUEST_SPACING_MS) }
            ?.comic ?: return null
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
    private fun sendNotifications(
        context: Context,
        items: List<UnreadItem>,
        icons: Map<Int, Bitmap?>,
        /** A repost of one already announced ([moveNotifications]): no sound or vibration. */
        silent: Boolean = false,
    ) {
        val notificationManager = NotificationManagerCompat.from(context)
        // Once for the batch: see MuUnreadNotificationTask.sendNotifications.
        val isGrouped = items.size > 1 ||
            context.hasOtherActiveGroupMembers(Notifications.GROUP_NEW_CHAPTERS, excludeId = -1)
        val canWrite = Comick.canWrite()

        items.forEach { item ->
            val unreadCount = item.latestChapter - item.userChapter
            val title = item.title
            val genericLabel = context.getString(R.string.notification_new_chapter_title)
            // The exact number ("13.5"); the count stays in whole chapters.
            val chapterText = context.newReleaseText(item.latestExact, unreadCount)
            val sourceText = context.getString(R.string.notification_source_subtext, item.source)
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
                .setSilent(silent)
            // Somewhere to record the chapter: Comick given write access, or the tracker list
            // the title is on.
            if (canWrite || item.onAnilistList || item.muListEntry != null) {
                builder.addAction(markAsReadAction(context, item, canWrite))
            }
            builder.addAction(muteAction(context, item))
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
            // A repost's summary is already out with the notification it replaces; posting it
            // again would sound, the summary not being silent.
            if (!silent) {
                notificationManager.notify(Notifications.ID_NEW_CHAPTERS, newReleasesGroupSummary(context))
            }
        }
    }

    /**
     * "Mark as read": writes the announced chapter to Comick when [canWrite], and to the AniList
     * or MangaUpdates entry the title is on, so the tracker the notification opens agrees.
     */
    private fun markAsReadAction(context: Context, item: UnreadItem, canWrite: Boolean): NotificationCompat.Action {
        val intent = Intent(context, MarkReadNotificationReceiver::class.java).apply {
            action = MarkReadNotificationReceiver.ACTION
            if (canWrite) {
                putExtra(MarkReadNotificationReceiver.EXTRA_COMICK_HID, item.entry.hid)
                putExtra(MarkReadNotificationReceiver.EXTRA_COMICK_PROGRESS, item.latestExact)
            }
            when {
                item.onAnilistList -> putExtra("media", item.anilistMedia as Serializable)
                item.muListEntry != null -> {
                    putExtra("muMedia", item.muListEntry as Serializable)
                    // Converted to AniList by the time it's tapped, the chapter goes there.
                    item.anilistId?.let { putExtra(MarkReadNotificationReceiver.EXTRA_ANILIST_ID, it) }
                }
            }
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

    /** "Mute": adds the title to the unread exclusion list, the way the list editors do. */
    private fun muteAction(context: Context, item: UnreadItem): NotificationCompat.Action {
        val intent = Intent(context, MarkReadNotificationReceiver::class.java).apply {
            action = MarkReadNotificationReceiver.ACTION_MUTE
            putExtra(MarkReadNotificationReceiver.EXTRA_EXCLUDE_ENTRY, item.excludeEntry)
            putExtra(MarkReadNotificationReceiver.EXTRA_NOTIFICATION_ID, item.notifId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            // Its own request code: the "Mark as read" broadcast already uses the notification id,
            // and two PendingIntents differing only in extras would be the same one.
            item.notifId xor MUTE_REQUEST_MASK,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_round_playlist_remove_24,
            context.getString(R.string.notification_action_mute),
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
                        mediaName = item.title,
                        lastChapter = item.latestChapter,
                        unreadCount = item.latestChapter - item.userChapter,
                        source = item.source,
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

    /**
     * Debug-only: posts a Comick notification for a title the last check found behind — real
     * cover, group and chapter — without waiting for a new chapter. Falls back to a placeholder
     * when that check found none.
     */
    suspend fun sendTestNotification(context: Context) {
        if (!hasNotificationPermission(context)) return
        withContext(Dispatchers.IO) {
            val item = UnreadCache.cachedComick().firstOrNull()?.let { cached ->
                UnreadItem(
                    entry = ComickLibraryEntry(hid = cached.hid, title = cached.title, slug = cached.slug),
                    title = cached.title,
                    latestChapter = cached.latestChapter,
                    userChapter = cached.progress,
                    latestExact = cached.latestChapter.toString(),
                    link = cached.anilistMedia?.let { "https://anilist.co/manga/${it.id}" } ?: cached.webUrl,
                    notifId = cached.notifId,
                    coverUrl = cached.coverUrl,
                    source = cached.source,
                    latestChapterAt = cached.latestChapterAt,
                    anilistMedia = cached.anilistMedia,
                    muListEntry = cached.muMedia?.takeIf { it.listId >= 0 },
                    muSeriesId = cached.muMedia?.id,
                )
            } ?: UnreadItem(
                entry = ComickLibraryEntry(hid = TEST_HID, title = "Test Comick Manga"),
                title = "Test Comick Manga",
                latestChapter = 6,
                userChapter = 5,
                latestExact = "6",
                link = ComickApi.webUrl(TEST_HID),
                notifId = ("comick:$TEST_HID".hashCode() and 0x7FFFFFFF),
                coverUrl = TEST_IMAGE_URL,
                source = "Test Group",
                latestChapterAt = null,
                anilistMedia = null,
                muListEntry = null,
                muSeriesId = null,
            )
            val icons = mapOf(item.notifId to NotificationImageLoader.loadBitmap(item.coverUrl))
            withContext(Dispatchers.Main) { sendNotifications(context, listOf(item), icons) }
        }
    }

    private companion object {
        @Volatile
        var currentlyPerforming = false

        const val SOURCE = "Comick"

        /** The AniList and MangaUpdates checks' "already announced" sets. */
        const val ANILIST_NOTIFIED_KEY = "notified_unread_chapters"
        const val MU_NOTIFIED_KEY = "notified_mu_chapters"

        /** The notification id each announced chapter went out under; see [moveNotifications]. */
        const val ANNOUNCED_IDS_KEY = "notified_comick_ids"

        /** How long each catalog request keeps its slot past the response; see [titlesBehind]. */
        const val REQUEST_SPACING_MS = 400L

        /** AniList statuses a title is being read under; Comick has one "reading" for both. */
        val READING_STATUSES = setOf("CURRENT", "REPEATING")

        /** MangaUpdates' standard lists in AniList's words; custom lists say nothing of status. */
        val MU_STATUSES = mapOf(0 to "CURRENT", 1 to "PLANNING", 2 to "COMPLETED", 3 to "DROPPED", 4 to "PAUSED")

        /** Sets the "Mute" broadcast's request code apart from "Mark as read"'s. */
        const val MUTE_REQUEST_MASK = 0x40000000

        const val TEST_HID = "test"
        const val TEST_IMAGE_URL =
            "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21-YCDoj1EkAxFn.jpg"
    }
}
