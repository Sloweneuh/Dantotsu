package ani.dantotsu.connections.mangaupdates

import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBakaSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/** Maps a MangaUpdates list index (0..4) to the equivalent AniList status. */
fun muListIdToAnilistStatus(listId: Int): String = when (listId) {
    0 -> "CURRENT"
    1 -> "PLANNING"
    2 -> "COMPLETED"
    3 -> "DROPPED"
    4 -> "PAUSED"
    else -> "CURRENT"
}

/**
 * [muListIdToAnilistStatus] restricted to the five standard MangaUpdates lists. Custom lists carry
 * user-defined ids with no tracker equivalent, so list sync skips them rather than pushing a guess —
 * the same call [ani.dantotsu.connections.mangabaka.MangaBakaSync.mapMangaUpdatesList] makes.
 * Converting is different: it always has to land the entry somewhere, so it takes the default above.
 */
fun muStandardListStatus(listId: Int): String? =
    if (listId in 0..4) muListIdToAnilistStatus(listId) else null

/** Converts a MangaUpdates "time added" timestamp (Unix seconds) to a [FuzzyDate] in local time. */
private fun epochSecondsToFuzzyDate(epochSeconds: Long): FuzzyDate {
    val cal = Calendar.getInstance().apply { timeInMillis = epochSeconds * 1000L }
    return FuzzyDate(
        year = cal.get(Calendar.YEAR),
        month = cal.get(Calendar.MONTH) + 1,
        day = cal.get(Calendar.DAY_OF_MONTH),
    )
}

/**
 * The start date a MangaUpdates entry implies: its "date added to list" ([addedAt], Unix seconds).
 *
 * Not for the Planning list (id 1) — there the date records when the series was *bookmarked*, not
 * when it was started, and every other tracker treats a planned entry as having no start date.
 * Returns null when MangaUpdates gave us no timestamp, which leaves any date the destination already
 * holds alone.
 */
fun muStartDate(muListId: Int, addedAt: Long?): FuzzyDate? =
    if (muListId == 1) null else addedAt?.let { epochSecondsToFuzzyDate(it) }

/**
 * Moves a MangaUpdates series over to AniList: adds/updates the AniList list entry (carrying over
 * status, progress and — when known — the "added to list" date as the AniList start date), then
 * removes the series from the MangaUpdates lists. One-way conversion.
 *
 * A first-time AniList entry is landed on chapter 1 before its real progress is written, the same
 * clean-start [ani.dantotsu.connections.anilist.AnilistMutations.primeActivity] does for the
 * reader/player — so the activity feed reads "read chapters 1 - N" instead of a brand-new entry
 * that opens at N.
 *
 * The entry becomes AniList-driven from here on, so the usual AniList list-sync mirrors (MAL and
 * MangaBaka) run too — otherwise the trackers would keep mirroring the MangaUpdates entry we just
 * deleted and drift permanently. They're best-effort and their own toggles gate them, so they run
 * detached: the conversion is already committed by then and making the caller wait on an id lookup
 * plus a PUT/PATCH only makes the confirmation feel slow.
 *
 * @param addedAt MangaUpdates "time added to list" as Unix seconds; becomes the AniList start date
 *   under the rules in [muStartDate]. Null leaves any existing start date untouched.
 * @return true if the AniList entry was written. MangaUpdates removal only runs after that succeeds,
 * so a failure never leaves the entry missing from both lists.
 */
suspend fun convertMuToAnilist(
    muSeriesId: Long,
    muListId: Int,
    anilistId: Int,
    chapter: Int?,
    volume: Int?,
    addedAt: Long? = null,
): Boolean {
    val status = muListIdToAnilistStatus(muListId)
    val startedAt = muStartDate(muListId, addedAt)
    // Land a first-time entry on chapter 1 before the real write below, so AniList's activity feed
    // shows a normal start rather than a first-ever entry jumping straight to a high number.
    // primeActivity no-ops unless a merge window is set and there's a jump to hide, so the "is it
    // already on the user's list?" lookup is only worth making then. A null result means the lookup
    // failed, not that the entry is absent — skip priming in that case rather than risk writing
    // progress 1 over an entry that already has real progress.
    if (Anilist.activityMergeTime != 0 && (chapter ?: 0) > 1) {
        Anilist.query.getMedia(anilistId, type = "MANGA")?.let {
            Anilist.mutation.primeActivity(anilistId, it.userStatus == null, chapter, status, startedAt)
        }
    }
    val added = Anilist.mutation.editList(
        mediaID = anilistId,
        progress = chapter,
        progressVolumes = volume,
        status = status,
        startedAt = startedAt,
    )
    if (added) {
        MangaUpdates.removeFromList(muSeriesId)
        mirrorConvertedEntry(anilistId, status, chapter, volume, startedAt)
    }
    return added
}

/**
 * Pushes a just-converted entry to the trackers that mirror AniList. MangaUpdates series are always
 * manga, so anime handling doesn't apply. The MangaBaka mirror resolves the same series it already
 * held under its MangaUpdates id, so this updates that entry rather than adding a second one.
 */
private fun mirrorConvertedEntry(
    anilistId: Int,
    status: String,
    chapter: Int?,
    volume: Int?,
    startedAt: FuzzyDate?,
) {
    CoroutineScope(Dispatchers.IO).launch {
        launch {
            // MAL keys its list by MAL id, which the MangaUpdates side never carries; look it up
            // only when a MAL account is actually connected so the conversion stays a single
            // request for everyone else.
            if (MAL.token == null) return@launch
            val idMAL = Anilist.query.getMedia(anilistId, type = "MANGA")?.idMAL ?: return@launch
            MAL.query.editList(
                idMAL = idMAL,
                isAnime = false,
                progress = chapter,
                score = null,
                status = status,
                volume = volume,
                start = startedAt,
            )
        }
        launch {
            MangaBakaSync.syncFromAnilist(
                anilistId = anilistId,
                malId = null,
                status = status,
                progressChapter = chapter,
                progressVolume = volume,
                score = null,
                rereads = null,
                isPrivate = null,
                startDate = startedAt,
                finishDate = null,
            )
        }
        launch {
            ani.dantotsu.connections.sync.ListSyncMirror.pushFromAnilist(
                isAnime = false,
                anilistId = anilistId,
                malId = null,
                status = status,
                progress = chapter,
                startDate = startedAt,
            )
        }
    }
}
