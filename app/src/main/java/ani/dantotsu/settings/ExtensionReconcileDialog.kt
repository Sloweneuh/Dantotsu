package ani.dantotsu.settings

import android.app.Activity
import android.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.sync.ExtensionSync
import ani.dantotsu.toast

/**
 * The "your devices have different extensions" chooser.
 *
 * Lives outside the settings screen because two paths raise it: the explicit "Sync extensions now"
 * action, and the banner that appears when another device publishes a different set. Installing and
 * removing extensions goes through the system installer and can never happen on its own, so unlike
 * every other kind of sync this one always ends at a list somebody has to agree to.
 */
fun Activity.showExtensionReconcileDialog(diff: ExtensionSync.Diff) {
    // One flat, user-driven list: installs first, then removals. Installs that can still be
    // found in the repos are pre-checked (additive, safe); removals are unchecked so deleting
    // an extension is always a deliberate opt-in.
    val items = diff.toInstall + diff.toRemove
    val checked = items.map { it.isInstall && it.available }.toBooleanArray()

    val pad = (8 * resources.displayMetrics.density).toInt()
    val recycler = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@showExtensionReconcileDialog)
        adapter = ExtensionReconcileAdapter(items, checked)
        setPadding(0, pad, 0, pad)
    }

    AlertDialog.Builder(this, R.style.MyPopup)
        .setTitle(R.string.sync_extensions)
        .setView(recycler)
        .setPositiveButton(R.string.ext_reconcile_apply) { _, _ ->
            var installed = 0
            var removed = 0
            items.forEachIndexed { i, item ->
                if (!checked[i]) return@forEachIndexed
                if (item.isInstall) {
                    if (item.available) {
                        ExtensionSync.install(item)
                        installed++
                    }
                } else {
                    ExtensionSync.uninstall(item)
                    removed++
                }
            }
            // Do NOT push here: install/uninstall are async and the local set would still
            // reflect the pre-reconcile state. The background push fires once the installs have
            // completed and the extension list has settled.
            toast(getString(R.string.ext_reconcile_summary, installed, removed))
        }
        .setNegativeButton(R.string.cancel, null)
        .create()
        .show()
}
