package ani.dantotsu.notifications.extension

import ani.dantotsu.App
import ani.dantotsu.download.anime.AnimeServiceDataSingleton
import ani.dantotsu.download.manga.MangaServiceDataSingleton
import ani.dantotsu.download.novel.NovelServiceDataSingleton

/**
 * Whether now is a safe moment to replace extensions behind the user's back.
 *
 * Installing over an extension kills its process and reloads its classes. Do that while an episode
 * is playing or a chapter is open and the source the screen is reading from disappears underneath
 * it — a scheduled convenience breaking the thing the user is actually doing, which is a far worse
 * trade than waiting for the next window.
 *
 * Any started activity counts as in use. That is deliberately blunt: it covers watching, reading,
 * browsing and picture-in-picture in one check, none of which are worth interrupting, and the work
 * simply runs later.
 */
object ExtensionUpdateGate {

    sealed interface Verdict {
        data object Allowed : Verdict
        data class Blocked(val reason: String) : Verdict
    }

    fun check(): Verdict = when {
        App.instance?.mFTActivityLifecycleCallbacks?.isForeground == true ->
            Verdict.Blocked("app is in use")

        // A download owns the network and, for manga, the same extension that is about to be
        // swapped. Let it finish.
        MangaServiceDataSingleton.isServiceRunning -> Verdict.Blocked("manga download running")
        AnimeServiceDataSingleton.isServiceRunning -> Verdict.Blocked("anime download running")
        NovelServiceDataSingleton.isServiceRunning -> Verdict.Blocked("novel download running")

        else -> Verdict.Allowed
    }
}
