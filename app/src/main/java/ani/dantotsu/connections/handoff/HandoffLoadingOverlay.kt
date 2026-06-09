package ani.dantotsu.connections.handoff

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import ani.dantotsu.R
import ani.dantotsu.getThemeColor
import ani.dantotsu.loadImage

/**
 * Full-screen, touch-absorbing loading overlay shown on the receiving device while a handoff is
 * resolving (media fetch → source/chapter load → auto-open).
 *
 * It still blocks all interaction with the half-loaded details page underneath, but instead of a
 * bare spinner it shows the incoming media (cover, title, what's being opened) and a live status
 * line, so the user can tell the handoff is actually progressing.
 *
 * Built programmatically and added to the activity content so it works regardless of layout
 * variant / orientation, and carries a safety auto-hide so it can never get stuck.
 */
object HandoffLoadingOverlay {

    private const val TAG = "handoff_loading_overlay"
    private const val STATUS_TAG = "handoff_loading_status"
    private const val SAFETY_TIMEOUT_MS = 30_000L
    private val main = Handler(Looper.getMainLooper())

    private fun Int.px(activity: Activity) =
        (this * activity.resources.displayMetrics.density).toInt()

    /**
     * Shows the overlay, or refreshes the status line if one is already up.
     *
     * @param title     media title, shown under the cover
     * @param cover     cover image URL
     * @param subtitle  what's resuming (e.g. "Chapter 12"), shown under the title
     * @param status    initial status line; defaults to a generic "loading" message
     */
    fun show(
        activity: Activity,
        title: String? = null,
        cover: String? = null,
        subtitle: String? = null,
        status: String? = null,
    ) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG) != null) {
            updateStatus(activity, status)
            return
        }
        val primary = activity.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val secondary = activity.getThemeColor(com.google.android.material.R.attr.colorOutline)
        val maxTextWidth = 280.px(activity)

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = 32.px(activity)
            setPadding(pad, 0, pad, 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }

        if (!cover.isNullOrEmpty()) {
            column.addView(ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(108.px(activity), 156.px(activity))
                scaleType = ImageView.ScaleType.CENTER_CROP
                loadImage(cover)
            })
        }

        if (!title.isNullOrEmpty()) {
            column.addView(TextView(activity).apply {
                text = title
                setTextColor(primary)
                textSize = 16f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
                maxWidth = maxTextWidth
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 16.px(activity), 0, 0)
            })
        }

        if (!subtitle.isNullOrEmpty()) {
            column.addView(TextView(activity).apply {
                text = subtitle
                setTextColor(secondary)
                textSize = 13f
                gravity = Gravity.CENTER
                maxWidth = maxTextWidth
                setPadding(0, 4.px(activity), 0, 0)
            })
        }

        column.addView(ProgressBar(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = 24.px(activity)
            }
        })

        column.addView(TextView(activity).apply {
            tag = STATUS_TAG
            text = status ?: activity.getString(R.string.handoff_loading_status)
            setTextColor(secondary)
            textSize = 13f
            gravity = Gravity.CENTER
            maxWidth = maxTextWidth
            setPadding(0, 16.px(activity), 0, 0)
        })

        val overlay = FrameLayout(activity).apply {
            tag = TAG
            // Absorb every touch so the half-loaded page underneath can't be interacted with.
            isClickable = true
            isFocusable = true
            setBackgroundColor(activity.getThemeColor(android.R.attr.colorBackground))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(column)
        }
        content.addView(overlay)
        main.postDelayed({ hide(activity) }, SAFETY_TIMEOUT_MS)
    }

    /** Updates the status line of a visible overlay (no-op if not shown or [status] is blank). */
    fun updateStatus(activity: Activity, status: String?) {
        if (status.isNullOrEmpty()) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG) == null) return
        content.findViewWithTag<TextView>(STATUS_TAG)?.text = status
    }

    fun hide(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.findViewWithTag<View>(TAG)?.let { content.removeView(it) }
    }
}
