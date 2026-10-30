package ani.dantotsu.media

import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBakaSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Saving a list entry: AniList, then the trackers that mirror it. Shared by the list editor sheet and
 * TV mode's status picker, so an edit made from either reaches the same places.
 */
object ListEntrySave {

    /**
     * Saves [media]'s entry as given and returns whether AniList took it. [initialScore] is the score
     * before this edit, which decides whether an emptied score clears Comick's rating.
     */
    suspend fun save(
        media: Media,
        progress: Int?,
        volume: Int?,
        score: Int?,
        rewatch: Int?,
        notes: String?,
        status: String,
        startD: FuzzyDate?,
        endD: FuzzyDate?,
        initialScore: Int?,
    ): Boolean {
        // Same treatment the reader/player gives a first-ever
        // entry: land it on 1 so the feed reads as a range
        // rather than an entry that opens partway in. It
        // applies to whatever status is being set, so an entry
        // taken straight to completed here gets it too.
        Anilist.mutation.primeActivity(
            media.id,
            isNewEntry = media.userStatus == null,
            progress = progress,
            status = status,
            startedAt = startD,
        )
        val ok = Anilist.mutation.editList(
            media.id,
            progress,
            volume,
            score,
            rewatch,
            notes,
            status,
            media.isListPrivate,
            startD,
            endD,
            media.inCustomListsOf?.mapNotNull { if (it.value) it.key else null }
        )
        // AniList has taken the edit; MAL and MangaBaka are
        // best-effort mirrors whose results are discarded
        // either way. Run them together on a scope that
        // outlives this sheet, so saving doesn't sit through
        // their round trips (MangaBaka alone costs an id
        // lookup, a PATCH, and a POST when the entry is new).
        val id = media.id
        val idMAL = media.idMAL
        val isAnime = media.anime != null
        val isManga = media.manga != null
        val isPrivate = media.isListPrivate
        CoroutineScope(Dispatchers.IO).launch {
            launch {
                MAL.query.editList(
                    idMAL,
                    isAnime,
                    progress,
                    score,
                    status,
                    rewatch,
                    volume,
                    startD,
                    endD
                )
            }
            if (isManga) launch {
                MangaBakaSync.syncFromAnilist(
                    anilistId = id,
                    malId = idMAL,
                    status = status,
                    progressChapter = progress,
                    progressVolume = volume,
                    score = score,
                    rereads = rewatch,
                    isPrivate = isPrivate,
                    startDate = startD,
                    finishDate = endD,
                )
            }
            launch {
                ani.dantotsu.connections.sync.ListSyncMirror.pushFromAnilist(
                    isAnime = isAnime,
                    anilistId = id,
                    malId = idMAL,
                    status = status,
                    progress = progress,
                    score = score,
                    // A score emptied in this edit clears Comick's rating; one left
                    // empty doesn't, so a rating set on Comick survives other edits.
                    comickScore = if (score != initialScore) score ?: 0 else score,
                    startDate = startD,
                    finishDate = endD,
                )
            }
        }
        return ok
    }
}
