package ani.dantotsu.media.discover

import ani.dantotsu.connections.TrackerSessions
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.connections.mangabaka.MangaBakaSync
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.syncMuToMal
import ani.dantotsu.connections.sync.ListSyncMirror
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The queue's "Add" button: puts a MangaBaka series on the user's planning list.
 *
 * AniList is the home list, so it is written first whenever the series links to an AniList entry;
 * MangaUpdates only takes the add when there is no AniList link (or no AniList account). Either way
 * the change then fans out to the other trackers exactly as a list-editor save would — MAL,
 * MangaBaka and the [ListSyncMirror] destinations, each behind its own list-sync toggle.
 *
 * Only when neither home tracker can take it does the series go straight into the MangaBaka
 * library, the account the queue came from.
 */
object DiscoverListAdd {

    sealed interface Result {
        data object AddedToAnilist : Result
        data object AddedToMangaUpdates : Result
        data object AddedToMangaBaka : Result
        /** Already on the AniList list; nothing was written, so an existing status isn't clobbered. */
        data class AlreadyOnAnilist(val status: String) : Result
        data object Failed : Result
    }

    private const val ANILIST_PLANNING = "PLANNING"
    private const val MU_PLANNING_LIST = 1

    suspend fun addManga(seriesId: Long, title: String): Result {
        TrackerSessions.await()
        val source = MangaBakaApi.getSeries(seriesId)?.source
        val anilistId = source?.anilist?.id?.takeIf { it > 0 }
        val muId = source?.mangaUpdates?.toMuSeriesId()?.takeIf { it > 0 }

        if (Anilist.token != null && anilistId != null) return addToAnilist(anilistId, isAnime = false, mangaBakaId = seriesId)
        if (MangaUpdates.token != null && muId != null) return addToMangaUpdates(muId, title)

        val written = MangaBakaSync.upsertBatch(
            listOf(MangaBakaSync.anilistWrite(seriesId, ANILIST_PLANNING, null, null, null, null, null)),
            force = true,
        )
        return if (seriesId in written) Result.AddedToMangaBaka else Result.Failed
    }

    /** An AniList anime from the anime queue: AniList is its only home, so there is no fallback. */
    suspend fun addAnime(anilistId: Int): Result {
        TrackerSessions.await()
        if (Anilist.token == null) return Result.Failed
        return addToAnilist(anilistId, isAnime = true, mangaBakaId = null)
    }

    private suspend fun addToAnilist(anilistId: Int, isAnime: Boolean, mangaBakaId: Long?): Result {
        // Read before writing: the queue may not know every list (the manga one only knows the
        // MangaBaka library), and saving PLANNING over an entry the user is halfway through would
        // quietly reset it.
        val media = Anilist.query.getMedia(anilistId) ?: return Result.Failed
        media.userStatus?.let { return Result.AlreadyOnAnilist(it) }
        if (!Anilist.mutation.editList(anilistId, status = ANILIST_PLANNING)) return Result.Failed

        val malId = media.idMAL
        // Best-effort mirrors on a scope that outlives the screen, as the list editor does.
        CoroutineScope(Dispatchers.IO).launch {
            launch {
                MAL.query.editList(malId, isAnime = isAnime, progress = null, score = null, status = ANILIST_PLANNING)
            }
            if (mangaBakaId != null) launch {
                // The MangaBaka id is already known, so this skips the AniList → MangaBaka lookup.
                MangaBakaSync.upsertBatch(
                    listOf(MangaBakaSync.anilistWrite(mangaBakaId, ANILIST_PLANNING, null, null, null, null, null))
                )
            }
            launch {
                ListSyncMirror.pushFromAnilist(
                    isAnime = isAnime, anilistId = anilistId, malId = malId,
                    status = ANILIST_PLANNING, progress = null,
                )
            }
        }
        return Result.AddedToAnilist
    }

    private suspend fun addToMangaUpdates(muId: Long, title: String): Result {
        val ok = MangaUpdates.addToList(muId, title, MU_PLANNING_LIST, chapter = null, volume = null)
        if (!ok) return Result.Failed
        CoroutineScope(Dispatchers.IO).launch {
            // Planning entries carry no start date (see muStartDate).
            launch {
                MangaBakaSync.syncFromMangaUpdates(muId, MU_PLANNING_LIST, null, null)
            }
            launch {
                ListSyncMirror.pushMangaFromMangaUpdates(muId, MU_PLANNING_LIST, progress = null)
            }
            launch {
                syncMuToMal(muId, MU_PLANNING_LIST, listOf(title), chapter = null, volume = null)
            }
        }
        return Result.AddedToMangaUpdates
    }
}
