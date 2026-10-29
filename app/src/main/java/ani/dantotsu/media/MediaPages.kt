package ani.dantotsu.media

import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityMediaBinding
import ani.dantotsu.navBarHeight
import ani.dantotsu.px

/** The windows of a media page (AniList and MangaUpdates alike), as saved in `Selected.window`. */
object MediaWindow {
    const val INFO = 0
    const val LIST = 1
    const val COMMENTS = 2
}

/** Pages are keyed by window, so the fragments the pager restores after a resize between one and
 *  two panes land on the right page. */
class MediaPagerAdapter(
    activity: FragmentActivity,
    private val windows: List<Int>,
    private val create: (window: Int) -> Fragment
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = windows.size

    override fun getItemId(position: Int): Long = windows[position].toLong()

    override fun containsItem(itemId: Long): Boolean = itemId.toInt() in windows

    override fun createFragment(position: Int): Fragment = create(windows[position])
}

/** Wide windows (tablets, unfolded foldables, desktop) show the Watch/Read list beside the pager,
 *  which then holds only the other windows. Phones, even in landscape, keep one pane. */
class MediaPanes(private val activity: FragmentActivity, private val binding: ActivityMediaBinding) {

    val twoPane = isTwoPane(activity.resources.configuration)

    companion object {
        fun isTwoPane(config: Configuration) =
            config.smallestScreenWidthDp >= 600 && config.screenWidthDp >= 840
    }

    /** The pager's windows out of [all]: the list leaves it when it has its own pane. */
    fun pageWindows(all: List<Int>) = if (twoPane) all - MediaWindow.LIST else all

    /** Puts [list] in the side pane (again if [replace]), or clears a stale one in a single pane. */
    fun setUp(replace: Boolean = false, list: () -> Fragment) {
        val fm = activity.supportFragmentManager
        val existing = fm.findFragmentById(R.id.mediaSidePane)
        if (!twoPane) {
            // Shrunk back to one pane: the pager has its own list now.
            existing?.let { fm.beginTransaction().remove(it).commitNowAllowingStateLoss() }
            return
        }
        binding.mediaViewPagerContainer.orientation = LinearLayout.HORIZONTAL
        // A baseline-aligned row measures its 0dp weighted panes a second time at unlimited width
        // to find their baselines: every pass then laid Info out thousands of pixels wide and back,
        // relaying it every frame and snapping its scroll to the top.
        binding.mediaViewPagerContainer.isBaselineAligned = false
        binding.mediaViewPager.updateLayoutParams<LinearLayout.LayoutParams> {
            width = 0
            weight = 1f
        }
        binding.mediaSidePane.isVisible = true
        if (existing == null || replace) {
            fm.beginTransaction().replace(R.id.mediaSidePane, list()).commitNowAllowingStateLoss()
        }
        // The comment box belongs to the Comments pane, not across the list.
        binding.mediaViewPager.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val box = binding.commentMessageContainer
            if (box.layoutParams.width == v.width) return@addOnLayoutChangeListener
            // Outside this layout pass, or the box's relayout runs a second one.
            box.post {
                // Portrait stacks it in a LinearLayout above the tab bar; landscape puts it straight
                // in the CoordinatorLayout.
                box.updateLayoutParams<ViewGroup.LayoutParams> {
                    width = v.width
                    when (this) {
                        is LinearLayout.LayoutParams -> gravity = Gravity.START
                        is CoordinatorLayout.LayoutParams -> gravity = Gravity.BOTTOM or Gravity.START
                    }
                }
            }
        }
    }
}

/**
 * The same split for the standalone pages (Comick, Kitsu, Simkl, extension media) that stack an Info
 * page and a list page in one [pages] frame and switch them with a [bar]. Wide windows show the two
 * side by side and drop the bar, which would have nothing left to switch.
 */
class StackedPanes(
    private val pages: FrameLayout,
    private val info: View,
    private val list: View,
    private val bar: View
) {
    val twoPane = MediaPanes.isTwoPane(pages.resources.configuration)

    fun setUp() {
        if (!twoPane) return
        bar.isVisible = false
        // The frame left room for the bar above the system navigation; only the latter is left.
        pages.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = navBarHeight }
        halve()
        pages.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> pages.post { halve() } }
    }

    /** Shows both panes once there's something in them. */
    fun reveal() {
        info.isVisible = true
        list.isVisible = true
    }

    private fun halve() {
        val width = pages.width.takeIf { it > 0 } ?: return
        val half = width / 2
        if (info.layoutParams.width == half) return
        info.updateLayoutParams<FrameLayout.LayoutParams> {
            this.width = half
            gravity = Gravity.START
        }
        list.updateLayoutParams<FrameLayout.LayoutParams> {
            this.width = width - half
            gravity = Gravity.END
        }
    }

    companion object {
        private val MAX_INFO_WIDTH = 840f.px

        /**
         * An Info-only page in a wide window: one column capped at a readable width and centred,
         * rather than lines running the whole screen. [scroll]'s single child is the column.
         */
        fun capInfoWidth(scroll: ViewGroup) {
            if (!MediaPanes.isTwoPane(scroll.resources.configuration)) return
            scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val column = scroll.getChildAt(0) ?: return@addOnLayoutChangeListener
                val width = minOf(scroll.width, MAX_INFO_WIDTH)
                if (column.layoutParams.width != width) scroll.post {
                    column.updateLayoutParams<FrameLayout.LayoutParams> {
                        this.width = width
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                }
            }
        }
    }
}
