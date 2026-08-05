package ani.dantotsu.connections.sync

import android.app.Activity
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.showExtensionReconcileDialog
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import ani.dantotsu.util.TopBanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Says that another device has different extensions installed, and opens the chooser.
 *
 * Every other kind of sync settles itself; this one structurally cannot. Installing and removing
 * extensions goes through Android's package installer and always needs a person, so the reconcile
 * list only ever appeared if the user went to Settings and tapped "Sync extensions now" — which
 * means a device could sit indefinitely without sources another device had, and nothing anywhere
 * would say so. The banner is what turns that from something you have to remember to check into
 * something the app tells you.
 */
object ExtensionSyncNotice {

    const val ID = "extension_reconcile"

    /**
     * Which cloud set the user has already declined to reconcile.
     *
     * Dismissal is remembered, and remembered *specifically*: keeping two devices deliberately
     * different — a tablet with sources the phone doesn't want — is an ordinary thing to do, and
     * forgetting the dismissal on restart would nag such a user on every launch forever. Recording
     * which set was declined, rather than a plain "don't ask again", means a genuinely new
     * difference still gets raised: install something on the other device and the cloud set changes,
     * so this is a question that hasn't been answered yet.
     */
    private const val DISMISSED_HASH_KEY = "ext_reconcile_dismissed_hash"

    /** The cloud set this session found, kept so a dismissal can name what it declined. */
    @Volatile private var remoteHash: Int? = null

    fun raise(cloudSetHash: Int) {
        remoteHash = cloudSetHash
        Logger.log("ExtensionSyncNotice: devices have different extensions")
    }

    /** Forgets a raised difference, for when the cloud set it referred to is gone or replaced. */
    fun clear() {
        remoteHash = null
    }

    /** Same reasoning as [SyncConflictNotice.isPending]: only while reconciling could still work. */
    fun isPending(): Boolean {
        val current = remoteHash ?: return false
        if (!PrefManager.getVal<Boolean>(PrefName.SyncExtensionsEnabled)) return false
        if (!SyncIdentity.isLinked()) return false
        return current != PrefManager.getCustomVal(DISMISSED_HASH_KEY, 0)
    }

    fun spec(activity: Activity) = TopBanner.Spec(
        id = ID,
        iconRes = R.drawable.ic_extension,
        title = activity.getString(R.string.ext_reconcile_banner_title),
        subtitle = activity.getString(R.string.ext_reconcile_banner_desc),
        actionLabel = activity.getString(R.string.review),
        onAction = { current -> review(current) },
        onDismiss = { remoteHash?.let { PrefManager.setCustomVal(DISMISSED_HASH_KEY, it) } },
    )

    private fun review(activity: Activity) {
        toast(activity.getString(R.string.please_wait))
        CoroutineScope(Dispatchers.IO).launch {
            val diff = runCatching { ExtensionSync.computeDiff() }.getOrElse {
                Logger.log("ExtensionSyncNotice: diff failed: ${it.message}")
                null
            }
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                when {
                    diff == null -> toast(activity.getString(R.string.cloud_sync_failed))
                    diff.toInstall.isEmpty() && diff.toRemove.isEmpty() -> {
                        // Settled between the check and the tap — the other device caught up, or
                        // this one did.
                        remoteHash = null
                        toast(activity.getString(R.string.cloud_sync_up_to_date))
                    }

                    else -> {
                        remoteHash = null
                        activity.showExtensionReconcileDialog(diff)
                    }
                }
            }
        }
    }
}
