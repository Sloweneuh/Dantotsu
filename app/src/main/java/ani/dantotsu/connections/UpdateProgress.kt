package ani.dantotsu.connections

import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBakaSync
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.muStartDate
import ani.dantotsu.connections.mangaupdates.syncMuToMal
import ani.dantotsu.currContext
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun updateProgress(media: Media, number: String) {
    val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
    if (incognito) {
        toast("Sneaky sneaky :3")
        return
    }

    // MangaUpdates-only media: update MU progress, skip Anilist
    val muSeriesId = media.muSeriesId
    if (muSeriesId != null) {
        CoroutineScope(Dispatchers.IO).launch {
            val a = number.toFloatOrNull()?.toInt()
            if ((a ?: 0) > (media.userProgress ?: -1)) {
                val listId = media.muListId ?: -1
                    val ok = if (listId == -1) {
                    // Not in user list, add to list (default to Reading)
                        val added = MangaUpdates.addToList(
                        seriesId = muSeriesId,
                        seriesTitle = media.name,
                        listId = 0, // 0 = Reading
                        chapter = a,
                        volume = media.userVolume
                    )
                    if (added) media.muListId = 0
                    added
                    } else {
                        MangaUpdates.updateProgress(
                            seriesId    = muSeriesId,
                            seriesTitle = media.name,
                            listId      = listId,
                            chapter     = a,
                            volume      = media.userVolume
                        )
                    }
                if (ok) {
                    PrefManager.setCustomVal(
                        "${ani.dantotsu.connections.mangaupdates.PREF_MU_LAST_READ_PREFIX}$muSeriesId",
                        System.currentTimeMillis()
                    )
                    toast(currContext()?.getString(R.string.setting_progress, a))
                    media.userProgress = a
                    Refresh.all()
                    // Mirror to MangaBaka and MAL afterwards: each costs an id lookup plus a write,
                    // and the update is already committed by then, so making the confirmation wait
                    // on them only makes the app feel slow.
                    val muListId = media.muListId
                    val muStart = muListId?.let { muStartDate(it, media.muAddedAt) }
                    launch {
                        MangaBakaSync.syncFromMangaUpdates(
                            muSeriesId = muSeriesId,
                            muListId = muListId,
                            progressChapter = a,
                            progressVolume = media.userVolume,
                            startDate = muStart,
                        )
                    }
                    if (muListId != null) launch {
                        syncMuToMal(
                            muSeriesId = muSeriesId,
                            muListId = muListId,
                            titles = listOfNotNull(media.name, media.nameRomaji).distinct(),
                            chapter = a,
                            volume = media.userVolume,
                            startDate = muStart,
                        )
                    }
                }
            }
        }
        return
    }

    if (Anilist.userid != null) {
        CoroutineScope(Dispatchers.IO).launch {
            val a = number.toFloatOrNull()?.toInt()
            if ((a ?: 0) > (media.userProgress ?: -1)) {
                Anilist.query.userMediaDetails(media)
                val status = if (media.userStatus == "REPEATING") media.userStatus else "CURRENT"
                var startDate: FuzzyDate? = null
                if (status == "CURRENT" && media.userStartedAt.isEmpty()) {
                    startDate = FuzzyDate().getToday()
                }
                Anilist.mutation.editList(
                    media.id,
                    a,
                    progressVolumes = media.userVolume,
                    status = status,
                    startedAt = startDate
                )
                toast(currContext()?.getString(R.string.setting_progress, a))

                // AniList is the source of truth and has already accepted the update; MAL and
                // MangaBaka are mirrors. Fire them off together instead of chaining them ahead of
                // the toast, or the confirmation waits on up to four extra round trips (MangaBaka
                // needs an id lookup, then a PATCH, then a POST if the entry doesn't exist yet).
                val volume = media.userVolume
                val mirroredStatus =
                    if (media.userStatus == "REPEATING") media.userStatus!! else "CURRENT"
                // The dates AniList now holds: the one we just backfilled, or the one it already had.
                // Mirroring them keeps MAL/MangaBaka from inventing their own start date on first
                // write, which is what leaves the lists permanently out of sync otherwise.
                val mirroredStart = startDate ?: media.userStartedAt.takeIf { !it.isEmpty() }
                val mirroredEnd = media.userCompletedAt.takeIf { !it.isEmpty() }
                launch {
                    MAL.query.editList(
                        media.idMAL,
                        media.anime != null,
                        a,
                        null,
                        mirroredStatus,
                        null,
                        volume,
                        mirroredStart,
                        mirroredEnd
                    )
                }
                if (media.manga != null) {
                    val score = media.userScore.takeIf { it > 0 }
                    val isPrivate = media.isListPrivate
                    launch {
                        MangaBakaSync.syncFromAnilist(
                            anilistId = media.id,
                            malId = media.idMAL,
                            status = mirroredStatus,
                            progressChapter = a,
                            progressVolume = volume,
                            score = score,
                            rereads = null,
                            isPrivate = isPrivate,
                            startDate = mirroredStart,
                            finishDate = mirroredEnd,
                        )
                    }
                }
            }
            media.userProgress = a
            Refresh.all()
        }
    } else {
        toast(currContext()?.getString(R.string.login_anilist_account))
    }
}