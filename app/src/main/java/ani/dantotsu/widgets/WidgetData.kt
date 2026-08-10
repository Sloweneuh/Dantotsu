package ani.dantotsu.widgets

import android.content.Context
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.malsync.MalSyncApi
import ani.dantotsu.connections.mangaupdates.MUDetailsCache
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.muMediaKey
import ani.dantotsu.media.Media
import ani.dantotsu.notifications.unread.UnreadCache
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.containsMediaId
import ani.dantotsu.util.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/**
 * One row of a list widget.
 *
 * Deliberately tiny, because this is what gets cached and re-read on every widget update. The old
 * upcoming widget cached whole [Media] objects as JSON — the entire AniList graph, with Gson
 * `InstanceCreator`s registered for Aniyomi's `SAnime`/`SChapter`/`SEpisode` interfaces just to make
 * the round trip possible — and then had to correct the countdowns it read back.
 */
data class WidgetItem(
    val id: Int,
    val title: String,
    val coverUrl: String,
    val isAnime: Boolean,
    /** When the next episode airs, epoch millis. Null for anything not on a schedule. */
    val airingAtMillis: Long? = null,
    val episode: Int? = null,
    /** How far the user has got, and the newest episode/chapter that exists. */
    val progress: Int? = null,
    val latest: Int? = null,
    val total: Int? = null,
    /** When [latest] came out, epoch millis; what the waiting list is ordered by. */
    val latestAt: Long? = null,
    /**
     * Where [latest] came from, shown as the last segment of a waiting row: the site MALSync read, or
     * the scanlation group for a MangaUpdates series. Not used for anime — see [languageId].
     */
    val source: String? = null,
    /**
     * MALSync's `en/dub`-shaped id for an anime row, shown in the same spot [source] occupies for
     * manga — a small dub/sub icon plus the short language code, rather than [source]'s plain text.
     */
    val languageId: String? = null,
    /**
     * Set for MangaUpdates series, which are not AniList media.
     *
     * [id] is then only a key — `muSeriesId` truncated to Int, as [muMediaKey] defines — and must
     * never be sent to AniList as an id. Rows carrying this open MangaUpdates directly.
     */
    val muSeriesId: Long? = null
) {
    /** Episodes or chapters out that the user hasn't reached. */
    val behind: Int get() = ((latest ?: 0) - (progress ?: 0)).coerceAtLeast(0)
}

/**
 * What a widget should say when it has no rows to show.
 *
 * [OFFLINE] and [ERROR] are kept apart because they ask different things of the user: one means "check
 * your connection", the other means the app failed and there is nothing they can do about it. Reporting
 * a code fault as a connection problem sends people looking in the wrong place.
 */
enum class WidgetStatus { OK, LOADING, EMPTY, OFFLINE, ERROR, LOGGED_OUT }

/**
 * Cached widget data, shared by every instance of every list widget.
 *
 * Each dataset is fetched at most once per [MAX_AGE_MS] no matter how many widgets read it, and a
 * failed refresh keeps serving the last good rows instead of blanking the widget.
 */
object WidgetData {

    /** How long cached rows are served before a refresh is attempted. */
    const val MAX_AGE_MS = 4 * 60 * 60 * 1000L

    enum class Dataset(val key: String) {
        /** The airing schedule for anime on the user's own list — the Upcoming widget. */
        AIRING("airing"),
        WAITING("waiting"),

        /**
         * The global airing schedule — every currently-airing JP anime, not just what the user
         * watches — for the This Week widget. Not gated behind [PrefName.AnilistUserId] the way the
         * other two datasets are: it needs no account at all, only [Anilist.query]'s own token.
         */
        CALENDAR("calendar")
    }

    private const val CACHE_PREFS = "ani.dantotsu.widget.cache"
    private val gson = Gson()
    private val itemListType = object : TypeToken<List<WidgetItem>>() {}.type

    private fun prefs(context: Context) =
        context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)

    /** Rows last written for [dataset], stale or not. Never throws; a broken cache reads as empty. */
    fun cached(context: Context, dataset: Dataset): List<WidgetItem> = try {
        prefs(context).getString(dataset.key, null)
            ?.let { gson.fromJson<List<WidgetItem>>(it, itemListType) }
            ?: emptyList()
    } catch (e: Exception) {
        Logger.log("Widget cache unreadable for ${dataset.key}: $e")
        emptyList()
    }

    fun status(context: Context, dataset: Dataset): WidgetStatus =
        runCatching {
            WidgetStatus.valueOf(
                prefs(context).getString("${dataset.key}_status", null) ?: WidgetStatus.LOADING.name
            )
        }.getOrDefault(WidgetStatus.LOADING)

    private fun isFresh(context: Context, dataset: Dataset): Boolean {
        val updated = prefs(context).getLong("${dataset.key}_updated", 0)
        return updated > 0 && System.currentTimeMillis() - updated < MAX_AGE_MS
    }

    private fun store(context: Context, dataset: Dataset, items: List<WidgetItem>, status: WidgetStatus) {
        prefs(context).edit()
            .putString(dataset.key, gson.toJson(items))
            .putLong("${dataset.key}_updated", System.currentTimeMillis())
            .putString("${dataset.key}_status", status.name)
            .apply()
    }

    private fun storeStatus(context: Context, dataset: Dataset, status: WidgetStatus) {
        prefs(context).edit().putString("${dataset.key}_status", status.name).apply()
    }

    /** Marks a dataset stale so the next read refreshes it. */
    fun invalidate(context: Context, dataset: Dataset) {
        prefs(context).edit().putLong("${dataset.key}_updated", 0).apply()
    }

    private const val FORCE_ANIME_KEY = "waiting_force_anime"

    /**
     * Tells the next [load] of the waiting dataset to refetch the anime half regardless of staleness.
     *
     * Set by the waiting widget's refresh button. That button intentionally leaves the manga/MangaUpdates
     * half alone — those cost a request per series and would make "refresh" slow — but the anime half is
     * one batch call, the same one the home screen's row makes on every redraw with no staleness cache of
     * its own, so a press pulling it fresh too is the same expectation carried over.
     *
     * A persisted flag rather than a parameter because the button (a provider, receiving a broadcast) and
     * the code that reads this (the factory, in its own `RemoteViewsService`) are different components
     * connected only by `notifyAppWidgetViewDataChanged` — there is no call to pass a parameter through.
     */
    fun requestAnimeRefresh(context: Context) {
        prefs(context).edit().putBoolean(FORCE_ANIME_KEY, true).apply()
    }

    private fun consumeAnimeRefreshRequest(context: Context): Boolean {
        val requested = prefs(context).getBoolean(FORCE_ANIME_KEY, false)
        if (requested) prefs(context).edit().remove(FORCE_ANIME_KEY).apply()
        return requested
    }

    /**
     * Rows for [dataset], refreshing over the network only when the cache has aged out or [force] is
     * set. Blocking — call it from a background thread (a `RemoteViewsFactory` callback qualifies).
     *
     * @return true when the returned rows differ from what was cached before, i.e. the widget needs
     *   redrawing.
     */
    suspend fun load(context: Context, dataset: Dataset, force: Boolean = false): Boolean {
        val fresh = isFresh(context, dataset)
        // The waiting list is recomputed every time, even when "fresh": most of what it is made of is
        // local (the unread cache the scheduled check writes, the MangaUpdates list, progress recorded
        // on this device) and rereading prefs is free. Only the parts that need the network are held
        // back. Without this, pressing refresh reread the widget's own stored rows and so could never
        // show anything that had changed since — which is what kept MangaUpdates series out of it even
        // after the home screen had resolved them.
        val networkAllowed = force || !fresh
        // consumeAnimeRefreshRequest() always runs, even when networkAllowed already covers it, so a
        // request left over from a press that happened to land while the dataset was already stale
        // doesn't linger and fire on some unrelated later load.
        val animeRequested = consumeAnimeRefreshRequest(context)
        val animeAllowed = networkAllowed || animeRequested
        if (dataset != Dataset.WAITING && !networkAllowed) return false

        // Unlike the other two, CALENDAR is the global airing schedule — the same query
        // CalendarActivity itself runs — which needs no account at all, only a network connection.
        val userId = PrefManager.getVal<String>(PrefName.AnilistUserId)
        if (dataset != Dataset.CALENDAR && userId.isNullOrEmpty()) {
            store(context, dataset, emptyList(), WidgetStatus.LOGGED_OUT)
            return true
        }
        return try {
            val items = when (dataset) {
                Dataset.AIRING -> fetchAiring(userId!!)
                Dataset.WAITING -> fetchWaiting(context, userId!!, networkAllowed, animeAllowed)
                Dataset.CALENDAR -> fetchCalendar()
            }
            store(
                context,
                dataset,
                items,
                if (items.isEmpty()) WidgetStatus.EMPTY else WidgetStatus.OK
            )
            true
        } catch (e: Throwable) {
            // A failed refresh must not empty the widget: the previous rows stay, only the status
            // changes, so a widget that has data keeps showing it while offline.
            Logger.log("Widget refresh failed for ${dataset.key}: $e")
            Logger.log(e)
            val existing = cached(context, dataset)
            val status = when {
                existing.isNotEmpty() -> WidgetStatus.OK
                e is java.io.IOException -> WidgetStatus.OFFLINE
                else -> WidgetStatus.ERROR
            }
            storeStatus(context, dataset, status)
            true
        }
    }

    private suspend fun fetchAiring(userId: String): List<WidgetItem> =
        Anilist.query.getUpcomingAnime(userId).mapNotNull { media ->
            val airingAt = media.anime?.nextAiringEpisodeTime ?: return@mapNotNull null
            WidgetItem(
                id = media.id,
                title = media.userPreferredName,
                coverUrl = media.cover.orEmpty(),
                isAnime = true,
                airingAtMillis = airingAt * 1000L,
                // Media stores the *previous* episode index; the airing one is the next.
                episode = media.anime?.nextAiringEpisode?.plus(1),
                progress = media.userProgress,
                total = media.anime?.totalEpisodes
            )
        }

    /**
     * The global airing schedule for the This Week widget — every currently-airing JP anime, the same
     * as [ani.dantotsu.media.CalendarActivity]'s own release calendar, not filtered to what the user
     * personally watches. [Anilist.query.recentlyUpdated] is the exact function that screen calls, over
     * the exact same window (a day back, six days ahead), so the widget and the app agree.
     *
     * This is a full, unpaginated pull of every airing episode worldwide in that window — hundreds of
     * entries some weeks — which is fine for a screen fetched on demand but too heavy to force on
     * [WidgetRefresh]'s 30-minute cycle the other datasets get; see the worker for how this one is kept
     * off that clock.
     */
    private suspend fun fetchCalendar(): List<WidgetItem> {
        val now = System.currentTimeMillis() / 1000
        return Anilist.query.recentlyUpdated(now - 86_400, now + 86_400 * 6).mapNotNull { media ->
            // recentlyUpdated() has nowhere else to put the episode/airingAt it fetched them for, so it
            // overloads Media.relation as "episode,airingAt" — CalendarActivity unpacks the same way.
            val (episodeStr, airingAtStr) = media.relation?.split(",")
                ?.takeIf { it.size == 2 } ?: return@mapNotNull null
            val airingAt = airingAtStr.toLongOrNull() ?: return@mapNotNull null
            WidgetItem(
                id = media.id,
                title = media.userPreferredName,
                coverUrl = media.cover.orEmpty(),
                isAnime = true,
                airingAtMillis = airingAt * 1000L,
                episode = episodeStr.toIntOrNull()
            )
        }
    }

    /**
     * Everything with episodes or chapters out that the user hasn't reached, newest release first.
     *
     * All three sources agree on one question — "what is actually available" — and none of them is
     * AniList's own count, which is null for most releasing manga and says nothing about how far a
     * stream is ahead of the broadcast schedule:
     *
     *  - manga on AniList/MAL: the unread cache [UnreadChapterNotificationTask] keeps up to date from
     *    MALSync, read here with no network at all;
     *  - MangaUpdates series: the same MALSync resolution, cached by the home screen ([UnreadCache.saveMu]);
     *  - anime: MALSync's episode counts for the watching list, fetched here.
     *
     * When nothing is waiting — MALSync off, the unread check never run, a brand new account — the
     * list falls back to what was opened most recently, so the widget is never pointlessly blank.
     */
    private suspend fun fetchWaiting(
        context: Context,
        userId: String,
        networkAllowed: Boolean,
        animeAllowed: Boolean
    ): List<WidgetItem> {
        // animeAllowed is its own flag rather than folded into networkAllowed: the refresh button keeps
        // the (expensive, per-series) manga/MangaUpdates half cache-only but always pulls anime fresh —
        // one batch call, same as what a homepage redraw already does unconditionally.
        val anime = if (animeAllowed) unwatchedAnime(context, userId)
        // Neither allowed: keep the anime rows from the last real fetch; they came from MALSync and
        // can't be recomputed locally.
        else cached(context, Dataset.WAITING).filter { it.isAnime }
        val items = (unreadManga(context, networkAllowed) + unreadMangaUpdates(context, networkAllowed) + anime)
            .sortedWith(compareByDescending<WidgetItem> { it.latestAt ?: 0 }.thenByDescending { it.behind })
        return items.ifEmpty { if (networkAllowed) recentlyOpened() else cached(context, Dataset.WAITING) }
    }

    /**
     * The cached progress, or what this device has read since — whichever is further along.
     *
     * The cached counts were gathered on a schedule; anything read since then would otherwise keep
     * showing as waiting. See [WidgetProgress].
     */
    private fun progressOf(context: Context, widgetKey: Int, cached: Int): Int =
        maxOf(cached, WidgetProgress.of(context, widgetKey))

    /**
     * AniList/MAL manga with unread chapters, out of the existing unread-check cache — reconciled
     * against AniList's current progress before deciding what still counts as unread.
     *
     * That cache is only as fresh as its own scheduled check, which runs on its own interval
     * ([ani.dantotsu.settings.saving.PrefName.UnreadChapterNotificationInterval]) independent of the
     * widget's — and has no way at all to see progress moved on another device or the AniList website,
     * short of its next scan. One batch query for just the candidates the cache already flagged closes
     * both gaps without paying for the actual MalSync rescan that decides whether a new chapter exists
     * — that stays on its own schedule; see [fetchWaiting].
     */
    private suspend fun unreadManga(context: Context, networkAllowed: Boolean): List<WidgetItem> {
        val info = UnreadCache.cachedInfo()
        if (info.isEmpty()) return emptyList()
        val cachedMedia = UnreadCache.cachedMedia()

        val liveProgress: Map<Int, Int> = if (networkAllowed && cachedMedia.isNotEmpty()) {
            runCatching { Anilist.query.getMediaBatch(cachedMedia.map { it.id }) }
                .onFailure { Logger.log("Widget: live manga progress check failed: $it") }
                .getOrNull()
                ?.associate { it.id to (it.userProgress ?: 0) }
                .orEmpty()
        } else emptyMap()

        return cachedMedia.mapNotNull { media ->
            val unread = info[media.id] ?: return@mapNotNull null
            val progress = maxOf(
                progressOf(context, media.id, unread.userProgress),
                liveProgress[media.id] ?: 0
            )
            if (unread.lastChapter <= progress) return@mapNotNull null
            WidgetItem(
                id = media.id,
                title = media.userPreferredName,
                coverUrl = media.cover.orEmpty(),
                isAnime = false,
                progress = progress,
                latest = unread.lastChapter,
                total = media.manga?.totalChapters,
                latestAt = unread.latestChapterAt,
                source = unread.source.takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * MangaUpdates series with unread chapters. [WidgetItem.muSeriesId] is what makes them openable.
     *
     * The reading list is fetched here rather than taken from what the home screen last resolved: that
     * only exists once the unread row has actually been drawn, so a user who hasn't opened the home tab
     * — or has that section switched off — saw no MangaUpdates series in the widget at all.
     *
     * No MALSync involved. MangaUpdates reports [MUMedia.latestChapter] itself, so "is there a chapter
     * past my progress" is answerable from one list request; anything MALSync resolved for the home row
     * is merged in on top when it happens to be cached.
     */
    private suspend fun unreadMangaUpdates(
        context: Context,
        networkAllowed: Boolean
    ): List<WidgetItem> {
        val info = UnreadCache.cachedMuInfo()
        val excluded = PrefManager.getVal<Set<String>>(PrefName.MalSyncExcludeList)
        // MangaUpdates.token is a plain in-memory var, loaded from disk only when getSavedToken() runs
        // — normally done once by the home screen at startup. A widget refresh can run in a process
        // that never went through that path (a fresh process spun up for a broadcast or WorkManager
        // job, or simply the app having been killed since), where the token reads as null regardless of
        // whether the user is actually logged in — which was silently skipping MangaUpdates entirely.
        if (MangaUpdates.token.isNullOrBlank()) MangaUpdates.getSavedToken()
        val live = if (networkAllowed && MangaUpdates.token != null &&
            PrefManager.getVal<Boolean>(PrefName.MangaUpdatesListEnabled)
        ) {
            runCatching { MangaUpdates.getReadingList() }
                .onFailure { Logger.log("Widget: MangaUpdates reading list failed: $it") }
                .getOrNull()
        } else null
        if (live != null) MuListCache.save(context, live)

        // Falls back to this widget's own copy of the list, then to whatever the home screen resolved —
        // so a refresh with no network, or before the first list request, still shows these series.
        val series = (live ?: MuListCache.load(context).ifEmpty { UnreadCache.cachedMuMedia() })
            .filterNot { excluded.containsMediaId(muMediaKey(it.id).toString()) }

        val unread = series.mapNotNull { mu ->
            val key = muMediaKey(mu.id)
            val latest = maxOf(mu.latestChapter ?: 0, info[key]?.lastChapter ?: 0)
            val progress = progressOf(context, key, mu.userChapter ?: 0)
            if (latest <= progress) null else Triple(mu, key, latest to progress)
        }

        // What the cover and source resolved to last time, keyed by the same muSeriesId — reused when
        // this pass can't reach MangaUpdates, so a widget offline for a moment doesn't drop covers it
        // already had.
        val previous = cached(context, Dataset.WAITING).mapNotNull { item ->
            item.muSeriesId?.let { it to item }
        }.toMap()

        // A list entry carries no cover, and MalSync's info map only carries a source when it happened
        // to resolve one — both come from the series endpoint instead, one request per series not
        // already cached in this process. Only worth paying for when the network is actually allowed;
        // MUDetailsCache.ensure() is otherwise exactly what left covers unresolved forever, since
        // nothing but the home screen's own prefetch ever populated it, and that cache is in-memory and
        // per-process — a widget refresh after the app was killed starts from empty every time.
        val details: Map<Long, MUDetailsCache.Detail?> = if (networkAllowed) {
            coroutineScope {
                unread.map { (mu, _, _) -> mu.id }.distinct().associateWith { id ->
                    async { runCatching { MUDetailsCache.ensure(id, withRelease = true) }.getOrNull() }
                }.mapValues { it.value.await() }
            }
        } else emptyMap()

        return unread.map { (mu, key, latestAndProgress) ->
            val (latest, progress) = latestAndProgress
            val detail = details[mu.id] ?: MUDetailsCache.get(mu.id)
            val fallback = previous[mu.id]
            WidgetItem(
                id = key,
                title = mu.title.orEmpty(),
                coverUrl = (mu.coverUrl ?: detail?.coverUrl ?: fallback?.coverUrl).orEmpty(),
                isAnime = false,
                progress = progress,
                latest = latest,
                latestAt = info[key]?.latestChapterAt ?: mu.updatedAt,
                source = info[key]?.source?.takeIf { it.isNotBlank() }
                    ?: detail?.latestGroup
                    ?: fallback?.source,
                muSeriesId = mu.id
            )
        }
    }

    /**
     * Watching anime with episodes out past the user's progress, per MALSync.
     *
     * Uses the same batch endpoint and language preference the home screen's row does, and respects the
     * MALSync toggles — with them off, the anime half is simply empty rather than wrong.
     */
    private suspend fun unwatchedAnime(context: Context, userId: String): List<WidgetItem> {
        if (!PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled)) return emptyList()
        if ((PrefManager.getVal<String>(PrefName.MalSyncCheckMode) ?: "both") == "manga") return emptyList()
        val watching = Anilist.query.getWatchingAnime(userId)
        if (watching.isEmpty()) return emptyList()
        val progress = MalSyncApi.getBatchAnimeEpisodes(watching.map { it.id to it.idMAL })
        return watching.mapNotNull { media ->
            val latest = progress[media.id]?.lastEp?.total ?: return@mapNotNull null
            val watched = progressOf(context, media.id, media.userProgress ?: 0)
            if (latest <= watched) return@mapNotNull null
            WidgetItem(
                id = media.id,
                title = media.userPreferredName,
                coverUrl = media.cover.orEmpty(),
                isAnime = true,
                progress = watched,
                latest = latest,
                total = media.anime?.totalEpisodes,
                latestAt = progress[media.id]?.lastEp?.timestampMillis(),
                languageId = progress[media.id]?.id?.takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * The fallback: the last things actually opened in the app, newest first.
     *
     * Ordering comes from [ContinueHistory], not from the AniList CURRENT list — a reading list can
     * hold a thousand releasing series, of which only the handful actually opened belong in a widget.
     */
    private suspend fun recentlyOpened(): List<WidgetItem> {
        val recent = ContinueHistory.recent(WidgetPrefs.MAX_ITEM_LIMIT)
        if (recent.isEmpty()) return emptyList()
        // MangaUpdates entries hold a truncated key, never an AniList id, so they can't be looked up
        // here; they come back through the unread path above once the home screen has resolved them.
        val anilistIds = recent.filter { it.muSeriesId == null }.map { it.mediaId }
        val byId = Anilist.query.getMediaBatch(anilistIds).associateBy { it.id }
        return recent.mapNotNull { entry ->
            val media = byId[entry.mediaId] ?: return@mapNotNull null
            val isAnime = media.anime != null
            WidgetItem(
                id = media.id,
                title = media.userPreferredName,
                coverUrl = media.cover.orEmpty(),
                isAnime = isAnime,
                progress = media.userProgress,
                total = if (isAnime) media.anime?.totalEpisodes else media.manga?.totalChapters
            )
        }
    }

    /**
     * [load] for callers that must block: `RemoteViewsFactory.onDataSetChanged` is documented as
     * running off the main thread and is expected to do its work before returning, so the list is
     * populated by the time the widget draws.
     */
    fun loadBlocking(context: Context, dataset: Dataset, force: Boolean = false): Boolean =
        runBlocking(Dispatchers.IO) { load(context, dataset, force) }
}
