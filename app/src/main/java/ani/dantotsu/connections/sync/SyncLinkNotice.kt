package ani.dantotsu.connections.sync

import android.app.Activity
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import ani.dantotsu.util.TopBanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tells a user who was syncing before that sync now needs a code, and offers to set one up.
 *
 * Cloud sync used to need nothing but an AniList login. It now needs a shared secret, because the
 * data is encrypted before it leaves the device — so an existing install has the feature switched
 * on, no code, and no way to know it has gone quiet. Nothing about that is discoverable.
 *
 * Like the extension reconcile, this can only ever be finished by a person: no code can be
 * generated on the user's behalf without putting each device in its own private cloud, which looks
 * exactly like sync being broken. So it belongs on a banner rather than in a dialog thrown up over
 * whatever they opened the app to do.
 *
 * Deliberately silent for installs that never synced: to them this is an unused feature, and being
 * told to configure it on launch is noise.
 */
object SyncLinkNotice {

    const val ID = "sync_link"

    /** Tri-state: null until the one-off check has run, since part of it needs the network. */
    @Volatile private var pending: Boolean? = null
    @Volatile private var checking = false

    /**
     * Dismissal is remembered for good, unlike the notices that describe a passing state.
     *
     * What this one reports doesn't change on its own: an unlinked device stays unlinked until the
     * user does something about it. Forgetting the dismissal when the process restarts therefore
     * meant showing the same banner on every cold start, permanently, to someone who had already
     * said no — and "I don't use cloud sync any more" is a perfectly ordinary thing to mean by
     * that. Setting it up stays one tap away in Settings, which says so plainly on the row.
     */
    private const val DISMISSED_KEY = "sync_link_dismissed"

    private fun dismissed(): Boolean = PrefManager.getCustomVal(DISMISSED_KEY, false)

    fun isPending(): Boolean =
        pending == true && !dismissed() && !SyncIdentity.isLinked()

    /** When the network half of the check last came back empty; see [checkOnce]. */
    private const val LAST_LOOKED_KEY = "sync_link_last_looked"
    private const val RECHECK_AFTER_MS = 7 * 24 * 60 * 60 * 1000L

    /**
     * Offers setup off the back of something that proves another device exists — a backup being
     * restored, or a handoff arriving. Both are local observations; nothing is published to find
     * them out, and neither depends on this device ever having synced.
     */
    fun offer() {
        if (dismissed() || SyncIdentity.isLinked()) return
        if (Anilist.token.isNullOrEmpty()) return
        pending = true
        Logger.log("SyncLinkNotice: another device is evidently in play; offering setup")
    }

    /**
     * Works out whether this device was syncing before.
     *
     * A local baseline settles it outright and costs nothing. Only a device with no baseline has to
     * ask the network, and that is the case worth being careful about: cloud sync is on by default,
     * so *every* signed-in install that has never synced reaches this point — and asking on each
     * launch would spend a request per user per launch, permanently, to keep re-learning that they
     * have no data. The empty answer is therefore remembered for a day.
     *
     * A day rather than forever because during a rollout another device can still be on a build
     * that writes to the old location, so "nothing there" is true when asked and not necessarily
     * true tomorrow.
     */
    fun checkOnce() {
        if (pending != null || checking) return
        if (Anilist.token.isNullOrEmpty() || SyncIdentity.isLinked()) return
        if (dismissed()) return // asked and answered; don't even spend the lookup
        // The enable toggle is deliberately not consulted. It now defaults to off and is switched
        // on by linking, so for the people this exists for — who were syncing before any of that —
        // it reads off, and gating on it would hide the notice from exactly them.

        if (CloudSync.hasSyncBaseline()) {
            pending = true
            Logger.log("SyncLinkNotice: this device used to sync but has no code")
            return
        }

        val lastLooked = PrefManager.getCustomVal(LAST_LOOKED_KEY, 0L)
        if (lastLooked > 0 && System.currentTimeMillis() - lastLooked < RECHECK_AFTER_MS) {
            pending = false
            return
        }

        checking = true
        CoroutineScope(Dispatchers.IO).launch {
            val found = runCatching { SyncMigration.legacyDataExists() }.getOrDefault(false)
            pending = found
            checking = false
            if (found) {
                Logger.log("SyncLinkNotice: this account has data waiting to be moved")
            } else {
                // Local clock is fine here: this only paces how often we re-ask, and being wrong
                // costs one extra request rather than a wrong sync decision.
                PrefManager.setCustomVal(LAST_LOOKED_KEY, System.currentTimeMillis())
            }
        }
    }

    fun spec(activity: Activity) = TopBanner.Spec(
        id = ID,
        iconRes = R.drawable.ic_round_lock_24,
        title = activity.getString(R.string.sync_relink_title),
        subtitle = activity.getString(R.string.sync_relink_banner_desc),
        actionLabel = activity.getString(R.string.sync_setup_title),
        onAction = { current ->
            current.showSyncSetupDialog(onChanged = {
                // Linked (or not) — either way the question has been put to them.
                if (SyncIdentity.isLinked()) pending = false
            })
        },
        onDismiss = { PrefManager.setCustomVal(DISMISSED_KEY, true) },
    )
}
