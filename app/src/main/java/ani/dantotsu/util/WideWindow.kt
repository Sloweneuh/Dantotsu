package ani.dantotsu.util

import android.app.Activity
import android.view.ViewGroup
import androidx.core.view.updatePadding
import ani.dantotsu.media.MediaPanes
import ani.dantotsu.px

/**
 * In a wide window, holds this screen's content to [maxDp] and centres it, so rows of settings and
 * the like don't stretch across the whole display. Phones are left alone. Call after setContentView.
 */
fun Activity.capContentWidth(maxDp: Float = 840f) {
    if (!MediaPanes.isTwoPane(resources.configuration)) return
    val content = findViewById<ViewGroup>(android.R.id.content)
    val root = content.getChildAt(0) ?: return
    val baseLeft = root.paddingLeft
    val baseRight = root.paddingRight
    val max = maxDp.px
    // The content frame's own width, which the root's padding doesn't change.
    content.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
        val side = ((right - left - max) / 2).coerceAtLeast(0)
        if (root.paddingLeft != baseLeft + side) root.post {
            root.updatePadding(left = baseLeft + side, right = baseRight + side)
        }
    }
}
