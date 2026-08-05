package ani.dantotsu.connections.sync

import android.app.Activity
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger
import ani.dantotsu.util.TopBanner

/**
 * Tells the user that a background sync changed this device's settings, and offers to redraw the
 * screen they're looking at.
 *
 * The problem it solves is narrow but real: a pull applies preferences that the visible screens
 * have *already read*, so the app carries on rendering the old values — and a settings screen would
 * write the stale ones back on the next toggle. The data is correct; only what's on screen isn't.
 *
 * Reloading is a courtesy rather than an obligation, which is why dismissing simply clears it: the
 * preferences are already stored correctly, and the next cold start renders them without any of
 * this. That is also why the pending state is in memory only — a process that restarts has re-read
 * everything, so there is nothing left to be stale.
 */
object SyncReloadNotice {

    const val ID = "sync_reload"

    @Volatile private var pending = false

    /** Called after a background apply changed this device's settings. */
    fun raise() {
        pending = true
        Logger.log("SyncReloadNotice: settings changed underneath the UI")
    }

    fun clear() {
        pending = false
    }

    fun isPending(): Boolean = pending

    fun spec(activity: Activity) = TopBanner.Spec(
        id = ID,
        iconRes = R.drawable.ic_round_sync_24,
        title = activity.getString(R.string.cloud_sync_done_updated),
        subtitle = activity.getString(R.string.cloud_sync_reload_hint),
        actionLabel = activity.getString(R.string.reload),
        onAction = { current ->
            clear()
            // Same signal the settings screens use for a restore, so whatever reads it on the way
            // back up behaves as though the user had applied a backup.
            PrefManager.setCustomVal("reload", true)
            if (!current.isFinishing && !current.isDestroyed) current.recreate()
        },
        onDismiss = { clear() },
    )
}
