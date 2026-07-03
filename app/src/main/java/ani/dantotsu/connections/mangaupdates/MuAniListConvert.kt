package ani.dantotsu.connections.mangaupdates

import ani.dantotsu.connections.anilist.Anilist

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
 * Moves a MangaUpdates series over to AniList: adds/updates the AniList list entry (carrying over
 * status and progress), then removes the series from the MangaUpdates lists. One-way conversion.
 *
 * @return true if the AniList entry was written. MangaUpdates removal only runs after that succeeds,
 * so a failure never leaves the entry missing from both lists.
 */
suspend fun convertMuToAnilist(
    muSeriesId: Long,
    muListId: Int,
    anilistId: Int,
    chapter: Int?,
    volume: Int?,
): Boolean {
    val added = Anilist.mutation.editList(
        mediaID = anilistId,
        progress = chapter,
        progressVolumes = volume,
        status = muListIdToAnilistStatus(muListId),
    )
    if (added) {
        MangaUpdates.removeFromList(muSeriesId)
    }
    return added
}
