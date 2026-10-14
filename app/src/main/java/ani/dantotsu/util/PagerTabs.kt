package ani.dantotsu.util

import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import com.google.android.material.tabs.TabLayout
import kotlin.math.min

/**
 * TabLayoutMediator for pagers using [useSharedAxisTransitions]: a tab tap switches with
 * [switchTo], and a swipe settles on its own.
 *
 * TabLayoutMediator with smoothScroll off cuts every swipe short: when a released swipe picks its
 * page, the mediator selects that tab, its own tab listener answers with
 * `setCurrentItem(page, false)`, and ViewPager2 only ignores that while idle, so mid-settle it
 * snaps the rest of the way. Here a tab selection that already matches the pager is left alone.
 */
class PagerTabsMediator(
    private val tabLayout: TabLayout,
    private val viewPager: ViewPager2,
    private val autoRefresh: Boolean = true,
    private val configure: (TabLayout.Tab, Int) -> Unit,
) {
    private var adapter: RecyclerView.Adapter<*>? = null
    private var attached = false

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        private var previousState = ViewPager2.SCROLL_STATE_IDLE
        private var state = ViewPager2.SCROLL_STATE_IDLE

        override fun onPageScrollStateChanged(newState: Int) {
            previousState = state
            state = newState
        }

        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            // Same rules as TabLayoutMediator: follow a drag, but not a jump's single scroll.
            val updateText = state != ViewPager2.SCROLL_STATE_SETTLING ||
                previousState == ViewPager2.SCROLL_STATE_DRAGGING
            val updateIndicator = !(state == ViewPager2.SCROLL_STATE_SETTLING &&
                previousState == ViewPager2.SCROLL_STATE_IDLE)
            tabLayout.setScrollPosition(position, positionOffset, updateText, updateIndicator)
        }

        override fun onPageSelected(position: Int) {
            if (tabLayout.selectedTabPosition == position || position >= tabLayout.tabCount) return
            val updateIndicator = state == ViewPager2.SCROLL_STATE_IDLE ||
                (state == ViewPager2.SCROLL_STATE_SETTLING && previousState == ViewPager2.SCROLL_STATE_IDLE)
            tabLayout.selectTab(tabLayout.getTabAt(position), updateIndicator)
        }
    }

    private val tabListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            // A tab matching the pager came from the pager itself, unless a tap's switch is still
            // fading out (the pager hasn't moved yet, so tapping back to its tab must count).
            val switching = viewPager.getTag(R.id.pager_switch_target) != null
            if (tab.position != viewPager.currentItem || switching) viewPager.switchTo(tab.position)
        }

        override fun onTabUnselected(tab: TabLayout.Tab) {}
        override fun onTabReselected(tab: TabLayout.Tab) {}
    }

    private val dataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = populateTabs()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = populateTabs()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = populateTabs()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = populateTabs()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = populateTabs()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = populateTabs()
    }

    fun attach() {
        check(!attached) { "PagerTabsMediator is already attached" }
        val pagerAdapter = checkNotNull(viewPager.adapter) {
            "PagerTabsMediator attached before ViewPager2 has an adapter"
        }
        adapter = pagerAdapter
        attached = true
        viewPager.registerOnPageChangeCallback(pageCallback)
        tabLayout.addOnTabSelectedListener(tabListener)
        if (autoRefresh) pagerAdapter.registerAdapterDataObserver(dataObserver)
        populateTabs()
        tabLayout.setScrollPosition(viewPager.currentItem, 0f, true)
    }

    fun detach() {
        if (autoRefresh) adapter?.unregisterAdapterDataObserver(dataObserver)
        tabLayout.removeOnTabSelectedListener(tabListener)
        viewPager.unregisterOnPageChangeCallback(pageCallback)
        adapter = null
        attached = false
    }

    private fun populateTabs() {
        tabLayout.removeAllTabs()
        val count = adapter?.itemCount ?: return
        for (i in 0 until count) {
            val tab = tabLayout.newTab()
            configure(tab, i)
            tabLayout.addTab(tab, false)
        }
        if (count > 0) {
            val current = min(viewPager.currentItem, tabLayout.tabCount - 1)
            if (current != tabLayout.selectedTabPosition) tabLayout.selectTab(tabLayout.getTabAt(current))
        }
    }
}
