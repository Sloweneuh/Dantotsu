package ani.dantotsu.connections.sync

import android.app.Activity
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleOwner
import ani.dantotsu.R
import ani.dantotsu.navBarHeight
import ani.dantotsu.util.setLeadingIcon
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch

/**
 * Shows what every sync module holds, here and in the cloud — see [SyncOverview] for why one
 * headline timestamp could not answer the question this replaces.
 *
 * Built in code rather than as a layout because it is a plain list of label/value lines whose
 * length is fixed by the module list, and the cloud half arrives a moment after the sheet opens.
 */
fun Activity.showSyncOverviewDialog() {
    val dp = resources.displayMetrics.density
    val onBg = MaterialColors.getColor(
        findViewById(android.R.id.content), com.google.android.material.R.attr.colorOnBackground
    )
    val error = MaterialColors.getColor(
        findViewById(android.R.id.content), com.google.android.material.R.attr.colorError
    )

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bottom_sheet_background)
        val h = (24 * dp).toInt()
        setPadding(h, (20 * dp).toInt(), h, navBarHeight + (16 * dp).toInt())
    }

    fun title(text: String, size: Float, bold: Boolean, alpha: Float, topGap: Int = 0) =
        AppCompatTextView(this).apply {
            this.text = text
            textSize = size
            typeface = ResourcesCompat.getFont(
                this@showSyncOverviewDialog,
                if (bold) R.font.poppins_bold else R.font.poppins_semi_bold
            )
            setTextColor(onBg)
            this.alpha = alpha
            setPadding(0, (topGap * dp).toInt(), 0, (2 * dp).toInt())
        }

    /** [setLeadingIcon], in the chaining shape the views here are built in. */
    fun AppCompatTextView.withIcon(res: Int, size: Float, tint: Int) =
        apply { setLeadingIcon(res, size, tint) }

    /**
     * A module's name, tagged when that module is the one waiting on the user.
     *
     * The tag sits on the label rather than under the two value lines because those describe what
     * each side holds, and a stalled module's values are unremarkable — the point is which module
     * the user has to go and settle.
     */
    fun moduleLabel(module: SyncOverview.Module, topGap: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, (topGap * dp).toInt(), 0, 0)
        addView(title(getString(module.nameRes), 15f, true, 1f))
        if (module.conflict) addView(
            title(getString(R.string.cloud_sync_conflict_label), 12f, true, 1f)
                .withIcon(R.drawable.ic_round_cloud_alert_24, 14f, error)
                .apply {
                    setTextColor(error)
                    setPadding((8 * dp).toInt(), 0, 0, (2 * dp).toInt())
                }
        )
    }

    container.addView(title(getString(R.string.cloud_sync_details), 18f, true, 1f))
    container.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            .also { it.bottomMargin = (12 * dp).toInt() }
        alpha = 0.12f
        setBackgroundColor(onBg)
    })

    // Sync is namespaced by application id (see SyncIdentity's pathScope), so a release install
    // and a beta install never see each other's data even on one account. Nothing else on this
    // screen says so, and from the outside that is indistinguishable from sync being broken.
    container.addView(AppCompatTextView(this).apply {
        text = "⚠ ${getString(R.string.sync_variant_warning)}"
        textSize = 12f
        typeface = ResourcesCompat.getFont(this@showSyncOverviewDialog, R.font.poppins_semi_bold)
        setTextColor(ContextCompat.getColor(this@showSyncOverviewDialog, R.color.warning_amber))
        setPadding(0, 0, 0, (14 * dp).toInt())
    })

    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    container.addView(body)
    body.addView(title(getString(R.string.please_wait), 13f, false, 0.66f))

    val scroll = NestedScrollView(this).apply { addView(container) }
    val sheet = BottomSheetDialog(this)
    sheet.setContentView(scroll)
    sheet.show()

    val owner = this as? LifecycleOwner ?: return
    owner.lifecycleScope.launch {
        val modules = runCatching { SyncOverview.collect() }.getOrNull()
        if (isFinishing || isDestroyed) return@launch
        body.removeAllViews()
        if (modules == null) {
            body.addView(title(getString(R.string.cloud_sync_details_failed), 13f, false, 0.66f))
            return@launch
        }
        modules.forEachIndexed { i, module ->
            body.addView(moduleLabel(module, topGap = if (i == 0) 0 else 14))
            // Which side each line is describing was carried by the words alone, so the two read as
            // one paragraph of timestamps; the icons make the local/cloud split scannable.
            body.addView(
                title(
                    getString(R.string.cloud_sync_this_device, describeLocal(module)),
                    13f, false, 0.66f
                ).withIcon(R.drawable.ic_round_devices_24, 15f, onBg)
            )
            body.addView(
                title(getString(R.string.cloud_sync_in_cloud, describeCloud(module)), 13f, false, 0.66f)
                    .withIcon(R.drawable.ic_round_cloud_24, 15f, onBg)
            )
        }
    }
}

private fun Activity.describeLocal(module: SyncOverview.Module): String {
    module.localNoteRes?.let { return getString(it) }
    val ts = module.localTs ?: return getString(R.string.cloud_sync_never_synced)
    val time = relative(ts)
    // The count only means anything alongside a time, so it rides with it rather than replacing it.
    return module.localDetail?.let { getString(R.string.cloud_sync_count_at, it, time) } ?: time
}

private fun Activity.describeCloud(module: SyncOverview.Module): String {
    val ts = module.cloudTs ?: return getString(R.string.cloud_sync_nothing_stored)
    val time = relative(ts)
    val withCount =
        module.cloudDetail?.let { getString(R.string.cloud_sync_count_at, it, time) } ?: time
    return module.cloudDevice?.let { getString(R.string.cloud_sync_from_device, withCount, it) }
        ?: withCount
}

private fun relative(ts: Long): String =
    DateUtils.getRelativeTimeSpanString(ts, SyncClock.nowCached(), DateUtils.MINUTE_IN_MILLIS)
        .toString()
