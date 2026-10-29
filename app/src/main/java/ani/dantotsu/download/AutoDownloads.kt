package ani.dantotsu.download

import android.app.Activity
import ani.dantotsu.R
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.download.DownloadsManager.Companion.compareName
import ani.dantotsu.download.manga.MangaDownloadQueue
import ani.dantotsu.download.video.Helper
import ani.dantotsu.isOnMeteredNetwork
import ani.dantotsu.isOnline
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.Selected
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.BaseSources
import ani.dantotsu.parsers.DynamicAnimeParser
import ani.dantotsu.parsers.DynamicMangaParser
import ani.dantotsu.parsers.HAnimeSources
import ani.dantotsu.parsers.HMangaSources
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.parsers.WatchSources
import ani.dantotsu.parsers.MangaReadSources
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.AppScope
import ani.dantotsu.util.Logger
import ani.dantotsu.util.StoragePermissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Downloads what came out since the last read, when the app is opened: for every title on the
 * user's AniList lists that is being read or watched (and, if asked, planned), the next few
 * unread chapters or episodes, from the source the user last picked for it.
 *
 * It only acts on choices the user already made — a saved source, a saved match on it, and for
 * anime a saved server — and never searches a source on its own: a title it has no match for is
 * skipped, rather than downloaded from whatever a search turned up. Run in the foreground, since
 * that is the only time Android lets the download services start.
 *
 * Each title remembers the furthest it auto-downloaded, so a chapter the user deleted unread is
 * not fetched again, and each run adds at most [PER_TITLE] per title.
 */
object AutoDownloads {

    private const val PER_TITLE = 5

    /** How soon a reopen may run it again: the check asks every source on the list. */
    private const val MIN_INTERVAL_MS = 60 * 60 * 1000L

    /** How long to wait at startup for the extensions to be loaded into the source lists. */
    private const val SOURCES_WAIT_MS = 30_000L

    @Volatile
    private var running = false

    /**
     * Runs if switched on, signed in, online — on Wi-Fi unless metered downloads are allowed —
     * with download storage chosen, and not run within [MIN_INTERVAL_MS]. Quiet unless it queues
     * something.
     */
    fun runIfDue(activity: Activity) {
        if (!PrefManager.getVal<Boolean>(PrefName.AutoDownloadEnabled) || running) return
        val anime = PrefManager.getVal<Boolean>(PrefName.AutoDownloadAnime)
        val manga = PrefManager.getVal<Boolean>(PrefName.AutoDownloadManga)
        if (!anime && !manga) return
        // Read from storage as the background checks do: the session may not be restored yet.
        val userId = Anilist.userid
            ?: PrefManager.getVal<String>(PrefName.AnilistUserId).toIntOrNull() ?: return
        if (Anilist.token.isNullOrBlank()) {
            Anilist.token = PrefManager.getVal<String>(PrefName.AnilistToken).takeIf { it.isNotBlank() } ?: return
        }
        if (!isOnline(activity) || PrefManager.getVal(PrefName.OfflineMode)) return
        if (!PrefManager.getVal<Boolean>(PrefName.AllowMeteredDownloads) && isOnMeteredNetwork(activity)) return
        if (!StoragePermissions.hasDirAccess(activity)) return
        val now = System.currentTimeMillis()
        if (now - PrefManager.getVal<Long>(PrefName.AutoDownloadLastRun) < MIN_INTERVAL_MS) return
        PrefManager.setVal(PrefName.AutoDownloadLastRun, now)

        running = true
        AppScope.launch {
            try {
                var queued = 0
                if (manga) queued += runManga(activity, userId)
                if (anime) queued += runAnime(activity, userId)
                Logger.log("AutoDownloads: queued $queued")
                if (queued > 0) {
                    activity.runOnUiThread { snackString(activity.getString(R.string.auto_download_queued, queued)) }
                }
            } catch (e: Exception) {
                Logger.log("AutoDownloads: failed: ${e.message}")
                Logger.log(e)
            } finally {
                running = false
            }
        }
    }

    /** Titles in the statuses the settings cover, from the AniList lists. */
    private suspend fun listed(anime: Boolean, userId: Int): List<Media> {
        val lists = Anilist.query.getMediaListsOrNull(anime, userId) ?: return emptyList()
        val planning = PrefManager.getVal<Boolean>(PrefName.AutoDownloadPlanning)
        return lists.values.flatten().distinctBy { it.id }.filter {
            it.userStatus == "CURRENT" || it.userStatus == "REPEATING" ||
                (planning && it.userStatus == "PLANNING")
        }
    }

    private fun selectedFor(media: Media): Selected? =
        PrefManager.getNullableCustomVal("Selected-${media.id}", null, Selected::class.java)

    /** The index of the source the user last picked for [media] in [sources], once it is loaded. */
    private suspend fun sourceIndex(sources: BaseSources, media: Media): Int? {
        val name = PrefManager.getNullableCustomVal("SelectedSource-${media.id}", null, String::class.java)
            ?: return null
        // At startup the extensions are still being loaded into the lists.
        return withTimeoutOrNull(SOURCES_WAIT_MS) {
            while (true) {
                val i = sources.names.indexOf(name)
                if (i >= 0) return@withTimeoutOrNull i
                delay(1000)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }

    private fun lastKey(anime: Boolean, id: Int) = "autodl_${if (anime) "a" else "m"}_$id"

    /** The furthest of [media]'s chapters or episodes auto-downloaded already, or its progress. */
    private fun from(anime: Boolean, media: Media): Float = maxOf(
        (media.userProgress ?: 0).toFloat(),
        PrefManager.getCustomVal(lastKey(anime, media.id), 0f),
    )

    private suspend fun runManga(activity: Activity, userId: Int): Int {
        val downloads = Injekt.get<DownloadsManager>()
        val asPdf = PrefManager.getVal<Boolean>(PrefName.MangaDownloadPdf)
        var queued = 0
        for (media in listed(anime = false, userId)) {
            // Chapter downloads are for manga; novels have their own reader and downloads.
            if (media.format != "MANGA" && media.format != "ONE_SHOT") continue
            try {
                val sources: MangaReadSources = if (media.isAdult) HMangaSources else MangaSources
                val index = sourceIndex(sources, media) ?: continue
                val parser = sources[index] as? DynamicMangaParser ?: continue
                val selected = selectedFor(media) ?: continue
                val show = parser.loadSavedShowResponse(media.id) ?: continue
                parser.sourceLanguage = selected.langIndex
                val hidden = selected.scanlators.orEmpty().toSet()
                val start = from(anime = false, media)
                val chapters = sources.loadChapters(index, show).values
                    .filter { (it.scanlator ?: "Unknown") !in hidden }
                    .mapNotNull { ch -> numberOf(ch.number, anime = false)?.let { it to ch } }
                    .filter { (n, _) -> n > start }
                    // One chapter per number: several scanlators can carry the same one.
                    .distinctBy { it.first }
                    .sortedBy { it.first }
                    .take(PER_TITLE)
                // Advanced only past what is downloaded or queued: a chapter the source failed to give
                // pages for stops the run there, to be tried again next time, in order.
                var furthest = start
                for ((number, chapter) in chapters) {
                    val done = downloads.mangaDownloadedTypes.any {
                        it.uniqueName == chapter.uniqueNumber() && media.compareName(it.titleName)
                    }
                    if (!done && !MangaDownloadQueue.isQueued(media, chapter)) {
                        if (!MangaDownloadQueue.enqueue(activity, media, chapter, parser, asPdf)) break
                        queued++
                    }
                    furthest = maxOf(furthest, number)
                }
                if (furthest > start) PrefManager.setCustomVal(lastKey(anime = false, media.id), furthest)
            } catch (e: Exception) {
                Logger.log("AutoDownloads: ${media.id} (manga) failed: ${e.message}")
            }
        }
        return queued
    }

    private suspend fun runAnime(activity: Activity, userId: Int): Int {
        // The in-app downloader needs its add-on; an external download manager can't be fed
        // without the user's hand on it.
        if (PrefManager.getVal<Int>(PrefName.DownloadManager) != 0) return 0
        if (!Injekt.get<DownloadAddonManager>().isAvailable()) return 0
        val downloads = Injekt.get<DownloadsManager>()
        var queued = 0
        for (media in listed(anime = true, userId)) {
            try {
                val sources: WatchSources = if (media.isAdult) HAnimeSources else AnimeSources
                val index = sourceIndex(sources, media) ?: continue
                val parser = sources[index] as? DynamicAnimeParser ?: continue
                val selected = selectedFor(media) ?: continue
                val server = selected.server ?: continue
                val show = parser.loadSavedShowResponse(media.id) ?: continue
                parser.sourceLanguage = selected.langIndex
                val start = from(anime = true, media)
                val episodes = sources.loadEpisodes(index, show.link, show.extra, show.sAnime).values
                    .mapNotNull { ep -> numberOf(ep.number, anime = true)?.let { it to ep } }
                    .filter { (n, _) -> n > start }
                    .distinctBy { it.first }
                    .sortedBy { it.first }
                    .take(PER_TITLE)
                var furthest = start
                val subLang = PrefManager.getNullableCustomVal("subLang_${media.id}", null, String::class.java)
                for ((number, episode) in episodes) {
                    if (downloads.queryDownload(media.mainName(), episode.number, ani.dantotsu.media.MediaType.ANIME)) {
                        furthest = maxOf(furthest, number)
                        continue
                    }
                    // As for chapters: an episode that can't be fetched stops the run there.
                    val link = episode.link ?: break
                    val sEpisode = episode.sEpisode ?: break
                    val extractor = parser.loadSingleVideoServer(server, link, episode.extra, sEpisode, false)
                        ?: break
                    val video = extractor.videos.getOrNull(selected.video) ?: extractor.videos.firstOrNull()
                        ?: break
                    // A torrent needs the torrent add-on started by hand.
                    if (video.file.url.startsWith("magnet:") || video.file.url.endsWith(".torrent")) break
                    val subtitles = extractor.subtitles
                        .filter { subLang == null || it.language == subLang }
                        .map { it.file.url to it.language }
                    activity.runOnUiThread {
                        Helper.startAnimeDownloadService(
                            activity, media.mainName(), episode.number, video, subtitles,
                            emptyList(), media, episode.thumb?.url ?: media.banner ?: media.cover,
                        )
                    }
                    furthest = maxOf(furthest, number)
                    queued++
                }
                if (furthest > start) PrefManager.setCustomVal(lastKey(anime = true, media.id), furthest)
            } catch (e: Exception) {
                Logger.log("AutoDownloads: ${media.id} (anime) failed: ${e.message}")
            }
        }
        return queued
    }

    /** A chapter or episode's number out of how the source names it ("Ch. 41", "41", "41.5"). */
    private fun numberOf(name: String, anime: Boolean): Float? =
        name.toFloatOrNull()
            ?: if (anime) MediaNameAdapter.findEpisodeNumber(name) else MediaNameAdapter.findChapterNumber(name)
}
