package ani.dantotsu.util

import android.app.Activity
import ani.dantotsu.connections.sync.ExtensionSyncNotice
import ani.dantotsu.connections.sync.SyncConflictNotice
import ani.dantotsu.connections.sync.SyncLinkNotice
import ani.dantotsu.connections.sync.SyncReloadNotice
import ani.dantotsu.settings.ExtensionUpdateNotice
import ani.dantotsu.settings.SettingsBackupSyncActivity

/**
 * Decides which pending [TopBanner] gets the screen.
 *
 * There is one banner slot, so the notices have to be ordered rather than left to whichever fires
 * last — otherwise a low-stakes one repeatedly displaces the one that matters, and the important
 * notice is never seen. The order is by what happens if it's ignored:
 *
 *  1. an unlinked device isn't syncing at all,
 *  2. a sync conflict leaves syncing stopped until it's settled,
 *  3. a pending reload leaves the current screen showing values that are no longer stored,
 *  4. mismatched extensions leave a device without sources another one has,
 *  5. extension updates are simply waiting, and lose nothing by waiting longer.
 *
 * The first two and the fourth share a property the others don't: they can *only* be finished by a
 * person. Nothing in the app can generate a sync code, choose which settings win, or agree to
 * install an extension on the user's behalf — so if they aren't surfaced, they never happen.
 *
 * Called from the application's activity-resumed callback, so notices follow the user instead of
 * belonging to one screen.
 */
object AppNotices {

    /**
     * Screens where a banner would land on top of full-screen media. Skipped rather than cleared —
     * interrupting playback or reading is worse than waiting, and the notice is still pending when
     * the user comes back out.
     */
    private val IMMERSIVE = setOf(
        "ani.dantotsu.media.anime.ExoplayerView",
        "ani.dantotsu.media.manga.mangareader.MangaReaderActivity",
        "ani.dantotsu.media.novel.novelreader.NovelReaderActivity",
    )

    /**
     * Where a notice's action would take the user, for the notices that lead somewhere.
     *
     * A banner offering to open the screen they are already looking at says nothing — and the sync
     * one says less than nothing, since it lands on top of the very rows it is pointing at.
     *
     * Suppressed rather than dismissed: the notice is still pending and still true, so it comes back
     * as soon as the user is somewhere the offer means something again. Held as a class rather than
     * a class name (unlike [IMMERSIVE], which names screens this package would otherwise have no
     * reason to know) because this one is a screen the notice itself already depends on.
     */
    private val REDUNDANT_ON: Map<String, Class<*>> = mapOf(
        SyncLinkNotice.ID to SettingsBackupSyncActivity::class.java,
    )

    /** Every notice, paired with whether it currently has anything to say. */
    private fun states(): List<Pair<String, Boolean>> = listOf(
        SyncLinkNotice.ID to SyncLinkNotice.isPending(),
        SyncConflictNotice.ID to SyncConflictNotice.isPending(),
        SyncReloadNotice.ID to SyncReloadNotice.isPending(),
        ExtensionSyncNotice.ID to ExtensionSyncNotice.isPending(),
        ExtensionUpdateNotice.ID to ExtensionUpdateNotice.isPending(),
    )

    /**
     * Takes down a banner whose subject stopped being true while it was on screen — turning cloud
     * sync off, or unlinking, from the very settings screen the banner is sitting on. Left alone it
     * would keep offering an action that now does nothing.
     *
     * Safe to call from anywhere that changes sync state; it only acts on the banner showing.
     */
    fun dismissStale() {
        states().forEach { (id, pending) ->
            if (!pending && TopBanner.isShowing(id)) TopBanner.dismiss(id)
        }
    }

    fun showPending(activity: Activity) {
        if (activity.javaClass.name in IMMERSIVE) return
        // Cheap and idempotent; the result is what the first branch below reads.
        SyncLinkNotice.checkOnce()
        // A banner raised earlier may have been settled elsewhere since — resolved on another
        // screen, or made moot by sync being switched off.
        dismissStale()
        when {
            SyncLinkNotice.isPending() -> show(activity, SyncLinkNotice.ID) {
                TopBanner.show(activity, SyncLinkNotice.spec(activity))
            }

            SyncConflictNotice.isPending() -> show(activity, SyncConflictNotice.ID) {
                TopBanner.show(activity, SyncConflictNotice.spec(activity))
            }

            SyncReloadNotice.isPending() -> show(activity, SyncReloadNotice.ID) {
                TopBanner.show(activity, SyncReloadNotice.spec(activity))
            }

            ExtensionSyncNotice.isPending() -> show(activity, ExtensionSyncNotice.ID) {
                TopBanner.show(activity, ExtensionSyncNotice.spec(activity))
            }

            ExtensionUpdateNotice.isPending() -> show(activity, ExtensionUpdateNotice.ID) {
                TopBanner.show(activity, ExtensionUpdateNotice.spec(activity))
                ExtensionUpdateNotice.markShown()
            }
        }
    }

    /**
     * Re-showing the banner already on *this* screen would restart its entrance animation for
     * nothing. On any other screen it has to be raised again: the card belongs to the activity it
     * was added to, so a rotation or a move to another screen leaves the notice with nowhere to be
     * — see [TopBanner.isShowingIn], which is the difference between "raised" and "visible here".
     */
    private inline fun show(activity: Activity, id: String, block: () -> Unit) {
        if (REDUNDANT_ON[id] == activity.javaClass) return
        if (TopBanner.isShowingIn(activity, id)) return
        block()
    }
}
