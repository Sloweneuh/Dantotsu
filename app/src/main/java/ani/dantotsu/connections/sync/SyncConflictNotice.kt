package ani.dantotsu.connections.sync

import android.app.Activity
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import ani.dantotsu.util.TopBanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Surfaces a sync divergence the user has to settle, without ambushing them to do it.
 *
 * This replaces a modal dialog thrown up from `MainActivity.onResume`. Two things were wrong with
 * that. It interrupted: a conflict is discovered by a background pull, which happens on launch and
 * on returning to the app, so the dialog landed on someone who had come back to read something. And
 * it was only reachable from the home screen, so a divergence found while the user was anywhere
 * else waited, invisible, for them to wander back.
 *
 * A banner inverts that — it says a decision is waiting and lets them take it when they want to.
 * The dialog still does the actual resolving; it just isn't the thing that interrupts.
 *
 * It also covers a case that had no surfacing at all. When a background pull finds that both sides
 * changed it logs "leaving for manual resolution" and stops — correct, since it must not guess, but
 * from the user's side sync simply went quiet with nothing to explain it or act on.
 */
object SyncConflictNotice {

    const val ID = "sync_conflict"

    /**
     * Set when a background pull found both sides had changed. In memory, because the next pull
     * finds the same divergence and raises it again — there is nothing to remember across restarts.
     */
    @Volatile private var divergent = false

    /**
     * Cleared per process rather than permanently: ignoring a conflict leaves sync stopped, so it
     * should come back next launch, but re-offering it on every screen change within one session
     * would be nagging. [CloudSync.bootstrapPromptPending] persists on its own for the same reason.
     */
    @Volatile private var suppressed = false

    fun raiseDivergent() {
        divergent = true
        Logger.log("SyncConflictNotice: divergence needs the user")
        SyncStatus.refresh()
    }

    /** Forgets a raised conflict, for when the cloud copy it referred to is gone or replaced. */
    fun clear() {
        divergent = false
        SyncStatus.refresh()
    }

    /**
     * A conflict only matters while sync could actually settle it. Switching sync off, or
     * unlinking, leaves the flags set but makes the action inert — and a banner offering something
     * that quietly does nothing reads as the feature being broken rather than turned off.
     */
    fun isPending(): Boolean =
        !suppressed &&
            PrefManager.getVal<Boolean>(PrefName.CloudSyncEnabled) &&
            SyncIdentity.isLinked() &&
            (divergent || CloudSync.bootstrapPromptPending())

    fun spec(activity: Activity) = TopBanner.Spec(
        id = ID,
        iconRes = R.drawable.ic_round_sync_24,
        title = activity.getString(R.string.cloud_sync_conflict_title),
        subtitle = activity.getString(R.string.cloud_sync_conflict_banner),
        actionLabel = activity.getString(R.string.review),
        onAction = { current -> resolve(current) },
        onDismiss = { suppressed = true },
    )

    /**
     * Runs the sync the banner is standing in for, and shows the dialog when it really is a
     * conflict. Anything else means it resolved itself between the pull and the tap — a merge that
     * came out clean, or the other device having caught up — so there is nothing to ask.
     */
    private fun resolve(activity: Activity) {
        toast(activity.getString(R.string.please_wait))
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { CloudSync.syncManual() }.getOrElse {
                Logger.log("SyncConflictNotice: sync threw: ${it.message}")
                null
            }
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                when (result) {
                    is CloudSync.SyncOutcome.Conflict -> {
                        divergent = false
                        activity.showCloudSyncConflictDialog(result) {
                            SyncReloadNotice.raise()
                        }
                    }

                    is CloudSync.SyncOutcome.Merged,
                    is CloudSync.SyncOutcome.Pulled -> {
                        divergent = false
                        SyncReloadNotice.raise()
                        SyncReloadNotice.spec(activity).let { TopBanner.show(activity, it) }
                    }

                    is CloudSync.SyncOutcome.Pushed,
                    is CloudSync.SyncOutcome.UpToDate -> {
                        divergent = false
                        toast(activity.getString(R.string.cloud_sync_up_to_date))
                    }

                    // Switched off or unlinked between the banner being raised and tapped. Nothing
                    // failed, so don't say it did — and drop the banner, which can't act now.
                    is CloudSync.SyncOutcome.Disabled,
                    is CloudSync.SyncOutcome.NoUser -> {
                        divergent = false
                        toast(activity.getString(R.string.cloud_sync_is_disabled))
                    }

                    else -> toast(activity.getString(R.string.cloud_sync_failed))
                }
            }
        }
    }
}
