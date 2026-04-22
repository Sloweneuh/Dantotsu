package ani.dantotsu.connections

import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangaupdates.MangaUpdates
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
                }
            }
        }
        return
    }

    if (Anilist.userid != null) {
        CoroutineScope(Dispatchers.IO).launch {
            val a = number.toFloatOrNull()?.toInt()
            if ((a ?: 0) > (media.userProgress ?: -1)) {
                Anilist.mutation.editList(
                    media.id,
                    a,
                    progressVolumes = media.userVolume,
                    status = if (media.userStatus == "REPEATING") media.userStatus else "CURRENT"
                )
                MAL.query.editList(
                    media.idMAL,
                    media.anime != null,
                    a,
                    null,
                    if (media.userStatus == "REPEATING") media.userStatus!! else "CURRENT",
                    null,
                    media.userVolume
                )
                toast(currContext()?.getString(R.string.setting_progress, a))
            }
            media.userProgress = a
            Refresh.all()
        }
    } else {
        toast(currContext()?.getString(R.string.login_anilist_account))
    }
}