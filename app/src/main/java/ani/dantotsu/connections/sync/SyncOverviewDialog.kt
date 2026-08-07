package ani.dantotsu.connections.sync

import android.app.Activity
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleOwner
import ani.dantotsu.R
import ani.dantotsu.navBarHeight
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

    container.addView(title(getString(R.string.cloud_sync_details), 18f, true, 1f))
    container.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            .also { it.bottomMargin = (12 * dp).toInt() }
        alpha = 0.12f
        setBackgroundColor(onBg)
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
            body.addView(title(getString(module.nameRes), 15f, true, 1f, topGap = if (i == 0) 0 else 14))
            body.addView(
                title(
                    getString(R.string.cloud_sync_this_device, describeLocal(module)),
                    13f, false, 0.66f
                )
            )
            body.addView(
                title(getString(R.string.cloud_sync_in_cloud, describeCloud(module)), 13f, false, 0.66f)
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
