package ani.dantotsu.util

import android.view.View
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The app's "layout animations" setting; every helper here is a no-op when it's off. */
val animationsEnabled: Boolean
    get() = PrefManager.getVal(PrefName.LayoutAnimations)

/**
 * Shared-axis feel for a pager's tab switches and swipes: pages overlap, cross-fade and travel
 * only about 30dp instead of sliding the full width.
 *
 * Swipes get it live from the page transformer. A tab tap should jump (`setCurrentItem(i, false)`,
 * [PagerTabsMediator]) rather than smooth-scroll — that passes through every
 * page in between, each flashing past in the cross-fade — and the jump then plays the same motion
 * once, from the side of the tab it came from.
 */
fun ViewPager2.useSharedAxisTransitions() {
    if (!animationsEnabled) return
    setTag(R.id.shared_axis_pager, true)
    val travel = 30f * resources.displayMetrics.density
    setPageTransformer { page, position ->
        val offset = abs(position)
        // One page at a time: the old one is gone by the halfway point and the new one fades in
        // after it, as a tab tap does. A plain cross-fade showed both lists at half strength,
        // which read as the items being swapped in place rather than a move.
        page.alpha = (1f - offset * 2f).coerceIn(0f, 1f)
        page.translationX = -position * page.width + position * travel
        page.translationZ = -offset
    }
    // Pages are faded by position; one laid out without a scroll (tabs rebuilt, adapter swapped)
    // could otherwise keep the alpha it had off-screen and stay invisible.
    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { requestTransform() } }
    var dragging = false
    var last = currentItem
    registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrollStateChanged(state: Int) {
            if (state == ViewPager2.SCROLL_STATE_DRAGGING) dragging = true
            else if (state == ViewPager2.SCROLL_STATE_IDLE) dragging = false
        }

        override fun onPageSelected(position: Int) {
            val from = last
            last = position
            // A swipe animated itself; only a jump (no scroll at all) needs the motion played.
            if (dragging || scrollState != ViewPager2.SCROLL_STATE_IDLE || from == position) return
            // Opening on a tab isn't switching to it. Playing the motion then also races the
            // activity's enter transition, which can leave the pager stuck mid-travel.
            if (getTag(R.id.pager_quiet_jump) == true || !isShown) return
            animate().cancel()
            alpha = 0f
            translationX = if (position > from) travel else -travel
            animate().alpha(1f).translationX(0f).setDuration(250)
                .setInterpolator(FastOutSlowInInterpolator()).start()
        }
    })
}

/**
 * Switches to [item] the way a swipe looks on a [useSharedAxisTransitions] pager: the current page
 * fades out drifting away, then the new one fades in sliding into place. A plain jump can only
 * fade the new page in, since the old one is already gone by the time the pager reports it.
 */
fun ViewPager2.switchTo(item: Int) {
    // A tap during the previous switch's fade-out lands it first, so no switch is ever dropped.
    (getTag(R.id.pager_switch_target) as? Int)?.let { pending ->
        setTag(R.id.pager_switch_target, null)
        animate().cancel()
        jumpTo(pending)
    }
    if (item == currentItem) {
        animate().cancel()
        alpha = 1f
        translationX = 0f
        return
    }
    if (getTag(R.id.shared_axis_pager) != true || !animationsEnabled || !isShown) {
        setCurrentItem(item, false)
        return
    }
    val travel = 30f * resources.displayMetrics.density
    val direction = if (item > currentItem) 1 else -1
    setTag(R.id.pager_switch_target, item)
    animate().cancel()
    animate().alpha(0f).translationX(-direction * travel).setDuration(120)
        .setInterpolator(FastOutLinearInInterpolator())
        .withEndAction {
            setTag(R.id.pager_switch_target, null)
            jumpTo(item)
            translationX = direction * travel
            animate().alpha(1f).translationX(0f).setDuration(200)
                .setInterpolator(LinearOutSlowInInterpolator()).start()
        }
        .start()
}

/**
 * Abandons a [switchTo] still fading out, putting the pager back in view, and returns the page it
 * was heading for. For a caller about to reshape the pages, after which that index may name a
 * different page: left running, the switch would land on it — or be cancelled by the reshape's own
 * page change, dropping its end action and leaving its target tag behind to hijack the next tap.
 */
fun ViewPager2.cancelSwitch(): Int? {
    val pending = getTag(R.id.pager_switch_target) as? Int ?: return null
    setTag(R.id.pager_switch_target, null)
    animate().cancel()
    alpha = 1f
    translationX = 0f
    return pending
}

/** Moves to [item] without the shared-axis motion: for opening on a page, not switching to one. */
fun ViewPager2.jumpTo(item: Int) {
    setTag(R.id.pager_quiet_jump, true)
    try {
        setCurrentItem(item, false)
    } finally {
        setTag(R.id.pager_quiet_jump, null)
    }
}

/** Shows or hides a bottom-anchored view (a floating toolbar) by springing it up from below the screen edge. */
fun View.springSlideVisible(show: Boolean) {
    if (!animationsEnabled) {
        translationY = 0f
        isVisible = show
        return
    }
    val offscreen = (height.takeIf { it > 0 } ?: 120) + 120f * resources.displayMetrics.density
    if (show) {
        if (isVisible && translationY == 0f) return
        if (!isVisible) translationY = offscreen
        isVisible = true
        SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_LOW
        }.start()
    } else if (isVisible) {
        SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, offscreen).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            addEndListener { _, canceled, _, _ -> if (!canceled) isVisible = false }
        }.start()
    }
}

/**
 * Runs [block] once this fragment's view is resumed — for a page in a ViewPager2, once the pager
 * has settled on it. Info tabs start their loading and rendering here, so a tab being swiped to
 * (or pre-created next to one) doesn't do that work during the slide.
 */
fun Fragment.whenViewResumed(block: () -> Unit) {
    val owner = viewLifecycleOwner
    owner.lifecycleScope.launch { owner.withResumed { block() } }
}
