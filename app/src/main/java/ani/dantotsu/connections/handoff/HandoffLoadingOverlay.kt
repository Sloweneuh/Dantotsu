package ani.dantotsu.connections.handoff

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import ani.dantotsu.getThemeColor

/**
 * Full-screen, touch-absorbing loading overlay shown on the receiving device while a handoff is
 * resolving (media fetch → source/chapter load → auto-open), so the user can't interact with the
 * half-loaded details page before the reader/player opens.
 *
 * Added programmatically to the activity content so it works regardless of layout variant /
 * orientation, and carries a safety auto-hide so it can never get stuck.
 */
object HandoffLoadingOverlay {

    private const val TAG = "handoff_loading_overlay"
    private const val SAFETY_TIMEOUT_MS = 30_000L
    private val main = Handler(Looper.getMainLooper())

    fun show(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG) != null) return
        val overlay = FrameLayout(activity).apply {
            tag = TAG
            isClickable = true
            isFocusable = true
            setBackgroundColor(activity.getThemeColor(android.R.attr.colorBackground))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(ProgressBar(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            })
        }
        content.addView(overlay)
        main.postDelayed({ hide(activity) }, SAFETY_TIMEOUT_MS)
    }

    fun hide(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.findViewWithTag<View>(TAG)?.let { content.removeView(it) }
    }
}
