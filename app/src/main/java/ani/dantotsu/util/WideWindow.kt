package ani.dantotsu.util

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.media.MediaPanes
import ani.dantotsu.others.PatternBackgroundView
import ani.dantotsu.px

/**
 * In a wide window, holds this screen's content to [maxDp] and centres it, so rows of settings and
 * the like don't stretch across the whole display. Phones are left alone. Call after setContentView.
 */
fun Activity.capContentWidth(maxDp: Float = 840f) {
    (findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as? ViewGroup)?.capContentWidth(maxDp)
}

/**
 * [Activity.capContentWidth] for a view's children — a fragment's root, from onViewCreated.
 *
 * Each block is held in by side margins rather than the whole screen by padding, so the animated
 * empty-state patterns ([PatternBackgroundView]) still cover the full window: a block holding one
 * isn't capped itself, its other children are. A [ViewPager2] is left whole: its pages arrive later
 * and cap themselves.
 */
fun ViewGroup.capContentWidth(maxDp: Float = 840f) {
    if (!MediaPanes.isTwoPane(resources.configuration)) return
    capChildren(this, maxDp.px)
}

/**
 * Holds just this one block to [maxDp] in a wide window: centred in its parent, or kept at its
 * start edge when it should line up with full-width blocks around it ([centred] false).
 */
fun View.capWidth(maxDp: Float = 840f, centred: Boolean = true) {
    if (!MediaPanes.isTwoPane(resources.configuration)) return
    capBlock(this, parent as? ViewGroup ?: return, maxDp.px, centred)
}

private fun capChildren(parent: ViewGroup, max: Int) {
    for (child in parent.children) when {
        child is PatternBackgroundView || child is ViewPager2 -> Unit
        child is ViewGroup && child.holdsFullWidth() -> capChildren(child, max)
        else -> capBlock(child, parent, max)
    }
}

/** Whether something in here must keep the full width: a pattern, or a pager whose pages may hold one. */
private fun ViewGroup.holdsFullWidth(): Boolean = children.any {
    it is PatternBackgroundView || it is ViewPager2 || (it is ViewGroup && it.holdsFullWidth())
}

private fun capBlock(block: View, parent: ViewGroup, max: Int, centred: Boolean = true) {
    val params = block.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    val baseStart = params.marginStart
    val baseEnd = params.marginEnd
    fun apply() {
        val inner = parent.width - parent.paddingLeft - parent.paddingRight
        if (inner <= 0) return
        val spare = (inner - max).coerceAtLeast(0)
        val start = baseStart + if (centred) spare / 2 else 0
        val end = baseEnd + if (centred) spare - spare / 2 else spare
        val now = block.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (now.marginStart == start && now.marginEnd == end) return
        block.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = start
            marginEnd = end
        }
    }
    parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> parent.post { apply() } }
}
