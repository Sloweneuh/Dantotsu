package ani.dantotsu.connections.sync

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.settings.SyncConflictAdapter
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
 * What it asks about is deliberately narrow. [SyncMerge] has already resolved every setting that
 * changed on only one side, so the choice here covers the overlap alone and both answers keep
 * everything else. When the merge produced no payloads at all — an unparseable side, or a device
 * with no baseline to diff against — it falls back to the whole-payload choice.
 *
 * @param onApplied invoked on the UI thread after settings changed, so the caller can refresh —
 *   the values that just changed have already been read by the live UI.
 */
@OptIn(DelicateCoroutinesApi::class)
fun Activity.showCloudSyncConflictDialog(
    conflict: CloudSync.SyncOutcome.Conflict,
    onApplied: () -> Unit,
) {
    val relative = android.text.format.DateUtils.getRelativeTimeSpanString(
        conflict.remoteTs, SyncClock.nowCached(), android.text.format.DateUtils.MINUTE_IN_MILLIS
    )
    val absolute = java.text.DateFormat
        .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(conflict.remoteTs))
    val savedLine = if (!conflict.remoteDevice.isNullOrBlank()) {
        getString(R.string.cloud_sync_conflict_saved_device, "$relative ($absolute)", conflict.remoteDevice)
    } else {
        getString(R.string.cloud_sync_conflict_saved, "$relative ($absolute)")
    }

    val summary = if (conflict.conflicts.isEmpty()) {
        getString(R.string.cloud_sync_conflict_msg)
    } else {
        resources.getQuantityString(
            R.plurals.cloud_sync_conflict_count,
            conflict.conflicts.size,
            conflict.conflicts.size,
        )
    }

    customAlertDialog().apply {
        setTitle(R.string.cloud_sync_conflict_title)
        setMessage("$summary\n\n$savedLine")
        // Showing the disagreement, not just counting it: which settings, and what each side holds.
        // Without this the choice is a coin flip dressed up as a decision.
        if (conflict.conflicts.isNotEmpty()) {
            setCustomView(conflictListView(conflict.conflicts))
        }
        setPosButton(R.string.cloud_sync_keep_local) {
            resolve(conflict.keepLocalPayload, conflict, onApplied) { CloudSync.resolveKeepLocal() }
        }
        setNegButton(R.string.cloud_sync_use_remote) {
            resolve(conflict.useRemotePayload, conflict, onApplied) {
                CloudSync.resolveUseRemote(conflict.remotePayload, conflict.remoteTs)
            }
        }
        // Cancel deliberately leaves the pending flag set: this decision is consequential enough
        // to be worth re-raising next launch rather than silently dropping.
        setNeutralButton(R.string.cancel) {}
        show()
    }
}

/**
 * The scrolling list of disagreements. Bounded in height so a long list can't push the buttons off
 * a short screen — the same failure the sync-code dialog had.
 */
private fun Activity.conflictListView(conflicts: List<SyncMerge.Conflict>): View {
    val density = resources.displayMetrics.density
    val maxPx = (280 * density).toInt()
    // RecyclerView has no maxHeight, so the cap goes in at measure time: measure the content
    // normally, but never claim more than this. Short lists size themselves; long ones scroll.
    return object : RecyclerView(this) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
        }
    }.apply {
        layoutManager = LinearLayoutManager(this@conflictListView)
        adapter = SyncConflictAdapter(conflicts)
        val pad = (8 * density).toInt()
        setPadding(pad, 0, pad, pad)
        clipToPadding = false
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}

/**
 * Applies the merged [payload] when there is one, else runs [fallback] — the pre-merge behaviour,
 * which resolves the whole payload one way or the other.
 */
@OptIn(DelicateCoroutinesApi::class)
private fun Activity.resolve(
    payload: String?,
    conflict: CloudSync.SyncOutcome.Conflict,
    onApplied: () -> Unit,
    fallback: suspend () -> Boolean,
) {
    GlobalScope.launch(Dispatchers.IO) {
        val ok = if (payload != null) CloudSync.resolveWith(payload, conflict.remoteTs)
        else fallback()
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (ok) {
                toast(getString(R.string.cloud_sync_done_updated))
                onApplied()
            } else {
                toast(getString(R.string.cloud_sync_failed))
            }
        }
    }
}

private const val MAX_LISTED = 8
