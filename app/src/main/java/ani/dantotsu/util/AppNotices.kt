package ani.dantotsu.util

import android.app.Activity
import android.content.Context
import ani.dantotsu.App
import ani.dantotsu.R
import ani.dantotsu.isOnline
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.connections.anilist.AnilistOutageNotice
import ani.dantotsu.connections.sync.ExtensionSyncNotice
import ani.dantotsu.connections.sync.SyncConflictNotice
import ani.dantotsu.connections.sync.SyncLinkNotice
import ani.dantotsu.connections.sync.SyncReloadNotice
import ani.dantotsu.offline.BackOnlineNotice
import ani.dantotsu.others.AppUpdateNotice
import ani.dantotsu.settings.ExtensionObsoleteNotice
import ani.dantotsu.settings.ExtensionUpdateNotice
import ani.dantotsu.settings.SettingsBackupSyncActivity

/**
 * Decides the order pending [TopBanner]s stack in.
 *
 * Only the top card of the stack can be read without unfolding it, so the notices have to be
 * ordered rather than left to whichever fires last — otherwise a low-stakes one repeatedly takes the
 * top from the one that matters. The order is by what happens if it's ignored:
 *
 *  0. a device left in offline mode after its connection came back is cut off from everything
 *     below — the network notices are held back while offline — and from the app besides,
 *  1. an unlinked device isn't syncing at all,
 *  2. a sync conflict leaves syncing stopped until it's settled,
 *  3. an AniList outage leaves every screen empty with nothing to explain it,
 *  4. a pending reload leaves the current screen showing values that are no longer stored,
 *  5. mismatched extensions leave a device without sources another one has,
 *  6. an app update is waiting, and the fixes in it with it,
 *  7. extension updates are simply waiting, and lose nothing by waiting longer,
 *  8. an obsolete extension has no one-tap fix — reviewing it can wait for whenever the user
 *     gets to the extensions screen on their own.
 *
 * The first two and the fifth share a property the others don't: they can *only* be finished by a
 * person. Nothing in the app can generate a sync code, choose which settings win, or agree to
 * install an extension on the user's behalf — so if they aren't surfaced, they never happen.
 *
 * The outage notice sits above them despite being the one thing here nobody can fix, because it is
 * also the only one that stops being true on its own: deferred behind a notice that waits for the
 * user, it would expire unseen, and the outage it was meant to explain would read as a broken app.
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
        SyncConflictNotice.ID to SettingsBackupSyncActivity::class.java,
    )

    /**
     * Notices whose offer cannot be taken up without a connection: linking a device, settling a
     * conflict, downloading an app update, installing or updating extensions, and an AniList outage — which says nothing at
     * all to someone who is not trying to reach AniList.
     *
     * [SyncReloadNotice] is deliberately absent. The pull that raised it has already landed in
     * preferences, so applying it is entirely local and works offline.
     */
    private val NEEDS_NETWORK = setOf(
        SyncLinkNotice.ID,
        SyncConflictNotice.ID,
        AnilistOutageNotice.ID,
        ExtensionSyncNotice.ID,
        AppUpdateNotice.ID,
        ExtensionUpdateNotice.ID,
    )

    private fun offline(context: Context?): Boolean {
        val ctx = context ?: return false
        return !isOnline(ctx) || PrefManager.getVal(PrefName.OfflineMode)
    }

    /**
     * Every notice the notification settings can turn off, in stacking order: its id, its name
     * there, its icon, and — for the two that only a person can act on — a warning of what goes
     * unsaid without it.
     */
    data class Choice(val id: String, val title: Int, val icon: Int, val warning: Int? = null)

    val CHOICES = listOf(
        Choice(BackOnlineNotice.ID, R.string.notice_back_online, R.drawable.ic_round_wifi_24),
        Choice(SyncLinkNotice.ID, R.string.notice_sync_link, R.drawable.ic_round_cloud_sync_24,
            R.string.notice_unsaid_sync),
        Choice(SyncConflictNotice.ID, R.string.notice_sync_conflict, R.drawable.ic_round_cloud_alert_24,
            R.string.notice_unsaid_sync),
        Choice(AnilistOutageNotice.ID, R.string.notice_anilist_outage, R.drawable.ic_round_cloud_off_24),
        Choice(SyncReloadNotice.ID, R.string.notice_sync_reload, R.drawable.ic_round_refresh_24),
        Choice(ExtensionSyncNotice.ID, R.string.notice_extension_sync, R.drawable.ic_round_extension_sync_24),
        Choice(AppUpdateNotice.ID, R.string.notice_app_update, R.drawable.ic_round_system_update_24),
        Choice(ExtensionUpdateNotice.ID, R.string.notice_extension_update,
            R.drawable.ic_round_extension_settings_24),
        Choice(ExtensionObsoleteNotice.ID, R.string.notice_extension_obsolete,
            R.drawable.ic_round_error_outline_24),
    )

    /** Whether the user lets [id] show — the master switch, then that notice's own. */
    fun isEnabled(id: String): Boolean =
        PrefManager.getVal<Boolean>(PrefName.AppNoticesEnabled) &&
            id !in PrefManager.getVal<Set<String>>(PrefName.DisabledAppNotices)

    /** Turns one notice on or off, taking its banner down if it is showing. */
    fun setEnabled(id: String, enabled: Boolean) {
        val disabled = PrefManager.getVal<Set<String>>(PrefName.DisabledAppNotices)
        PrefManager.setVal(PrefName.DisabledAppNotices, if (enabled) disabled - id else disabled + id)
        dismissStale()
    }

    /** Whether a notice is worth raising here and now: connection, and whether the user wants it. */
    private fun allowed(context: Context?, id: String) =
        !(id in NEEDS_NETWORK && offline(context)) && isEnabled(id)

    /**
     * Every notice, paired with whether it currently has anything to say — which offline means
     * anything it can still act on.
     */
    private fun states(context: Context?): List<Pair<String, Boolean>> = listOf(
        BackOnlineNotice.ID to BackOnlineNotice.isPending(),
        SyncLinkNotice.ID to SyncLinkNotice.isPending(),
        SyncConflictNotice.ID to SyncConflictNotice.isPending(),
        AnilistOutageNotice.ID to AnilistOutageNotice.isPending(),
        SyncReloadNotice.ID to SyncReloadNotice.isPending(),
        ExtensionSyncNotice.ID to ExtensionSyncNotice.isPending(),
        AppUpdateNotice.ID to AppUpdateNotice.isPending(),
        ExtensionUpdateNotice.ID to ExtensionUpdateNotice.isPending(),
        ExtensionObsoleteNotice.ID to ExtensionObsoleteNotice.isPending(),
    ).map { (id, pending) -> id to (pending && allowed(context, id)) }

    /**
     * Takes down a banner whose subject stopped being true while it was on screen — turning cloud
     * sync off, or unlinking, from the very settings screen the banner is sitting on. Left alone it
     * would keep offering an action that now does nothing.
     *
     * Safe to call from anywhere that changes sync state; it only acts on the banner showing.
     */
    fun dismissStale() {
        // App.context so that switching offline mode on takes down a banner already raised, not
        // just stops the next one; the callers that change sync state have no activity to hand.
        states(App.context).forEach { (id, pending) ->
            if (!pending && TopBanner.isShowing(id)) TopBanner.dismiss(id)
        }
    }

    fun showPending(activity: Activity) {
        if (activity.javaClass.name in IMMERSIVE) return
        // Cheap and idempotent; the result is what the stack below is built from.
        SyncLinkNotice.checkOnce()
        // A banner raised earlier may have been settled elsewhere since — resolved on another
        // screen, or made moot by sync being switched off.
        dismissStale()
        // Every pending notice, in the order above: the most pressing on top, the rest stacked
        // behind it rather than held back unseen until it goes.
        val pending = states(activity)
            .filter { (id, pending) -> pending && REDUNDANT_ON[id] != activity.javaClass }
            .map { (id, _) -> spec(activity, id) }
        TopBanner.show(activity, pending)
    }

    private fun spec(activity: Activity, id: String): TopBanner.Spec = when (id) {
        BackOnlineNotice.ID -> BackOnlineNotice.spec(activity)
        SyncLinkNotice.ID -> SyncLinkNotice.spec(activity)
        SyncConflictNotice.ID -> SyncConflictNotice.spec(activity)
        AnilistOutageNotice.ID -> AnilistOutageNotice.spec(activity)
        SyncReloadNotice.ID -> SyncReloadNotice.spec(activity)
        ExtensionSyncNotice.ID -> ExtensionSyncNotice.spec(activity)
        // Shown once per process: marked once the user has actually been able to read them, which
        // a card peeking out from behind another isn't.
        AppUpdateNotice.ID -> AppUpdateNotice.spec(activity).copy(onShown = AppUpdateNotice::markShown)
        ExtensionUpdateNotice.ID ->
            ExtensionUpdateNotice.spec(activity).copy(onShown = ExtensionUpdateNotice::markShown)
        ExtensionObsoleteNotice.ID ->
            ExtensionObsoleteNotice.spec(activity).copy(onShown = ExtensionObsoleteNotice::markShown)
        else -> error("No notice $id")
    }
}
