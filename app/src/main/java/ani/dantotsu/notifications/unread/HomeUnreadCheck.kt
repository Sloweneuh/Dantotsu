package ani.dantotsu.notifications.unread

import android.content.Context
import ani.dantotsu.App
import ani.dantotsu.connections.malsync.MalSyncMu
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * The home row's unread check, announcing what it finds the way the background check does.
 *
 * The row asks MALSync itself every time it loads, while notifications only came from
 * [UnreadChapterNotificationTask] on its own clock — so a chapter could sit on the row for many
 * minutes before its notification went out. Here the row's answers go through the background
 * check's own announcing code (same "already announced" sets, so nothing rings twice), and the
 * providers the row didn't ask — MangaUpdates' own counts, Comick — are asked too.
 *
 * [lock] is shared with [UnreadChapterNotificationTask.execute]. The row only ever *tries* it:
 * when the background check is running it is announcing the same chapters already, and the row
 * shows its answers without waiting on a check that can take minutes.
 */
object HomeUnreadCheck {

    /** Held by whichever check is announcing; see the class doc. */
    val lock = Mutex()

    /** Comick's check reads the whole library: not on every visit to the home screen. */
    private const val COMICK_INTERVAL_MS = 15 * 60_000L

    /** How long Comick waits for the MangaUpdates half before going ahead without it. */
    private const val HALF_TIMEOUT_MS = 60_000L

    /** Outlives the home screen: an announcement half-sent when it closes is worse than none. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** When the row last ran the AniList and MangaUpdates checks; the resume check counts it. */
    @Volatile
    var lastRunAt = 0L
        private set

    /** When Comick's check last ran, from here or from the background check. */
    @Volatile
    private var lastComickAt = 0L

    // This round's newest-known chapters, which Comick leaves to the other two checks. Null until
    // that half has answered.
    @Volatile
    private var aniListKnown: Map<Int, Int>? = null

    @Volatile
    private var muKnown: Map<Long, Int>? = null

    private var comickFallback: Job? = null

    private fun context(): Context? = App.instance ?: App.context

    /** Noted by the background check, so the row doesn't run Comick's again right behind it. */
    fun noteComickRan() {
        lastComickAt = System.currentTimeMillis()
    }

    /**
     * The row's AniList half: [batchResults] is MALSync's answer for [mangaList], the reading list.
     * Caches it as the background check would, and announces what's new — unless that check is
     * running, in which case it only caches.
     */
    fun aniList(mangaList: List<Media>, batchResults: Map<Int, ani.dantotsu.connections.malsync.MalSyncResponse>) {
        val context = context() ?: return
        scope.launch {
            if (!lock.tryLock()) {
                Logger.log("HomeUnreadCheck: background check running, AniList half cached only")
                UnreadChapterNotificationTask().announceManga(context, mangaList, batchResults, notify = false)
                return@launch
            }
            try {
                val notify = PrefManager.getVal<Boolean>(PrefName.UnreadMangaNotificationsEnabled) &&
                    !ani.dantotsu.tv.TvMode.isOn(context)
                aniListKnown = UnreadChapterNotificationTask().announceManga(context, mangaList, batchResults, notify)
                lastRunAt = System.currentTimeMillis()
            } catch (e: Exception) {
                Logger.log("HomeUnreadCheck: AniList half failed: ${e.message}")
                aniListKnown = emptyMap()
            } finally {
                lock.unlock()
            }
            maybeComick(context)
        }
    }

    /** The row had no MALSync answer for its AniList half (off, nothing being read, or failed). */
    fun aniListSkipped() {
        aniListKnown = emptyMap()
        context()?.let { c -> scope.launch { maybeComick(c) } }
    }

    /**
     * The row's MangaUpdates half: MALSync's info for [reading] (the MangaUpdates reading list), as
     * the row shows it. Runs the background check's MangaUpdates step on that same list, so its own
     * counts are announced too; without MangaUpdates notifications it is only MALSync's lookup.
     */
    suspend fun mu(reading: List<MUMedia>): Map<Int, UnreadChapterInfo> {
        val context = context()
        val announce = context != null &&
            PrefManager.getVal<Boolean>(PrefName.MangaUpdatesNotificationsEnabled) &&
            PrefManager.getVal<Boolean>(PrefName.MangaUpdatesListEnabled)
        if (!announce || !lock.tryLock()) {
            if (announce) Logger.log("HomeUnreadCheck: background check running, MangaUpdates half not announced")
            else muKnown = emptyMap()
            return MalSyncMu.unreadInfo(reading).also { context?.let { c -> scope.launch { maybeComick(c) } } }
        }
        return try {
            val task = MuUnreadNotificationTask()
            muKnown = task.checkMangaUpdatesUnread(context!!, reading)
            lastRunAt = System.currentTimeMillis()
            task.lastMalSyncInfo
        } catch (e: Exception) {
            Logger.log("HomeUnreadCheck: MangaUpdates half failed: ${e.message}")
            muKnown = emptyMap()
            emptyMap()
        } finally {
            lock.unlock()
            context?.let { c -> scope.launch { maybeComick(c) } }
        }
    }

    /** MangaUpdates has no half to wait for: not signed in, or its list isn't fetched. */
    private suspend fun muInactive(): Boolean =
        !PrefManager.getVal<Boolean>(PrefName.MangaUpdatesListEnabled) ||
            !PrefManager.getVal<Boolean>(PrefName.MangaUpdatesNotificationsEnabled) ||
            !MangaUpdates.getSavedToken()

    /**
     * Comick's check, once the AniList and MangaUpdates halves have answered (it leaves the chapters
     * they know to them), and at most every [COMICK_INTERVAL_MS]. A half that never answers — an
     * empty MangaUpdates reading list isn't asked — is waited on for [HALF_TIMEOUT_MS] at most.
     */
    private suspend fun maybeComick(context: Context) {
        if (System.currentTimeMillis() - lastComickAt < COMICK_INTERVAL_MS) return
        startComick(context, muKnown == null && muInactive())
    }

    @Synchronized
    private fun startComick(context: Context, muInactive: Boolean) {
        val ani = aniListKnown
        val mu = muKnown ?: if (muInactive) emptyMap() else null
        if (ani == null && mu == null) return
        if (ani == null || mu == null) {
            if (comickFallback?.isActive != true) comickFallback = scope.launch {
                delay(HALF_TIMEOUT_MS)
                runComick(context, aniListKnown ?: emptyMap(), muKnown ?: emptyMap())
            }
            return
        }
        comickFallback?.cancel()
        comickFallback = null
        scope.launch { runComick(context, ani, mu) }
    }

    private suspend fun runComick(context: Context, ani: Map<Int, Int>, mu: Map<Long, Int>) {
        if (System.currentTimeMillis() - lastComickAt < COMICK_INTERVAL_MS) return
        if (!lock.tryLock()) return
        try {
            lastComickAt = System.currentTimeMillis()
            aniListKnown = null
            muKnown = null
            Logger.log("HomeUnreadCheck: running Comick's check")
            ComickUnreadNotificationTask().checkComickUnread(context, ani, mu)
        } catch (e: Exception) {
            Logger.log("HomeUnreadCheck: Comick check failed: ${e.message}")
        } finally {
            lock.unlock()
        }
    }
}
