package ani.dantotsu.connections.mangaupdates

import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.FuzzyDate
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
 * Moves a MangaUpdates series over to AniList: adds/updates the AniList list entry (carrying over
 * status, progress and — when known — the "added to list" date as the AniList start date), then
 * removes the series from the MangaUpdates lists. One-way conversion.
 *
 * @param addedAt MangaUpdates "time added to list" as Unix seconds; when non-null it becomes the
 *   AniList start date. Null leaves any existing start date untouched.
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
    val added = Anilist.mutation.editList(
        mediaID = anilistId,
        progress = chapter,
        progressVolumes = volume,
        status = muListIdToAnilistStatus(muListId),
        startedAt = addedAt?.let { epochSecondsToFuzzyDate(it) },
    )
    if (added) {
        MangaUpdates.removeFromList(muSeriesId)
    }
    return added
}
