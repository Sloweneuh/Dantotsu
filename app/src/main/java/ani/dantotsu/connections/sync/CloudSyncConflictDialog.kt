package ani.dantotsu.connections.sync

import android.app.Activity
import ani.dantotsu.R
import ani.dantotsu.toast
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * The "your settings differ from the cloud's" prompt.
 *
 * Lives here rather than in the settings screen because two paths raise it: the explicit "Sync now"
 * action, and a device that has never synced but already has settings of its own — that one is
 * detected by a background pull, which has no UI, so it flags
 * [CloudSync.bootstrapPromptPending] and whichever activity notices first shows this.
 *
 * @param onApplied invoked on the UI thread after the cloud copy was adopted, so the caller can
 *   refresh — the settings that just changed have already been read by the live UI.
 */
@OptIn(DelicateCoroutinesApi::class)
fun Activity.showCloudSyncConflictDialog(
    remotePayload: String,
    remoteTs: Long,
    remoteDevice: String?,
    onApplied: () -> Unit,
) {
    val relative = android.text.format.DateUtils.getRelativeTimeSpanString(
        remoteTs, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
    )
    val absolute = java.text.DateFormat
        .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(remoteTs))
    val savedLine = if (!remoteDevice.isNullOrBlank()) {
        getString(R.string.cloud_sync_conflict_saved_device, "$relative ($absolute)", remoteDevice)
    } else {
        getString(R.string.cloud_sync_conflict_saved, "$relative ($absolute)")
    }
    customAlertDialog().apply {
        setTitle(R.string.cloud_sync_conflict_title)
        setMessage(getString(R.string.cloud_sync_conflict_msg) + "\n\n" + savedLine)
        setPosButton(R.string.cloud_sync_keep_local) {
            GlobalScope.launch(Dispatchers.IO) {
                val ok = CloudSync.resolveKeepLocal()
                toast(getString(if (ok) R.string.cloud_sync_done else R.string.cloud_sync_failed))
            }
        }
        setNegButton(R.string.cloud_sync_use_remote) {
            GlobalScope.launch(Dispatchers.IO) {
                val ok = CloudSync.resolveUseRemote(remotePayload, remoteTs)
                runOnUiThread {
                    if (ok) {
                        toast(getString(R.string.cloud_sync_done_updated))
                        onApplied()
                    } else {
                        toast(getString(R.string.cloud_sync_failed))
                    }
                }
            }
        }
        // Cancel deliberately leaves the pending flag set: this decision is consequential enough
        // to be worth re-raising next launch rather than silently dropping.
        setNeutralButton(R.string.cancel) {}
        show()
    }
}
