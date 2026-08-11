package ani.dantotsu.profile.activity

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityNotificationBinding
import ani.dantotsu.initActivity
import ani.dantotsu.profile.activity.ActivityFragment.Companion.ActivityType
import ani.dantotsu.settings.bindQuickSettings
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import nl.joery.animatedbottombar.AnimatedBottomBar

class FeedActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNotificationBinding
    private lateinit var pagerAdapter: ViewPagerAdapter
    private var selected: Int = 0
    lateinit var navBar: AnimatedBottomBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityNotificationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.notificationTitle.text = getString(R.string.activities)
        // Reads real insets rather than the app's statusBarHeight/navBarHeight globals: those are only
        // filled in as a side effect of some other activity's initActivity() call having already run in
        // this task, and are still zero when this screen is the first thing to open — a widget's header
        // tap now reaches it directly. Margin, not padding, and on the same two views the old
        // (globals-based) code used: notificationToolbar is a fixed 48dp FrameLayout, so padding just
        // crams its content into the same box instead of moving the box; notificationNavBar's height is
        // hand-pinned by a -67dp/67dp margin pair elsewhere in this layout, so padding on it desyncs
        // that and squishes its tabs. A bottom margin on root reserves the space without touching either.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            // displayCutout as well as systemBars: immersive mode hides the status bar, so systemBars
            // reports a zero top inset there and content would sit under the camera cutout. Taking both
            // gives the status bar's inset in normal mode and the cutout's in immersive, with no branch.
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.notificationToolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top
            }
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom
            }
            insets
        }
        // Repurposed from the notification screen's settings icon (this layout is shared with it) into
        // a toggle for hiding the signed-in account's own posts from the Following tab — there is
        // nothing else this screen needs a settings icon for.
        binding.notificationSettings.setImageResource(R.drawable.ic_round_visibility_off_24)
        fun updateHideOwnIcon() {
            binding.notificationSettings.alpha =
                if (PrefManager.getVal<Boolean>(PrefName.HideOwnActivityFromFeed)) 1f else 0.33f
        }
        updateHideOwnIcon()
        binding.notificationSettings.setOnClickListener {
            val hidden = !PrefManager.getVal<Boolean>(PrefName.HideOwnActivityFromFeed)
            PrefManager.setVal(PrefName.HideOwnActivityFromFeed, hidden)
            updateHideOwnIcon()
            pagerAdapter.followingFragment?.reload()
        }
        navBar = binding.notificationNavBar
        val tabs = listOf(
            Pair(R.drawable.ic_round_person_24, "Following"),
            Pair(R.drawable.ic_globe_24, "Global"),
        )
        tabs.forEach { (icon, title) -> navBar.addTab(navBar.createTab(icon, title)) }

        binding.notificationBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.quickSettings.bindQuickSettings(this)
        val getOne = intent.getIntExtra("activityId", -1)
        if (getOne != -1) {
            navBar.visibility = View.GONE
            // A single activity has no "own activity" to hide.
            binding.notificationSettings.visibility = View.GONE
        }
        binding.notificationViewPager.isUserInputEnabled = false
        pagerAdapter = ViewPagerAdapter(supportFragmentManager, lifecycle, getOne)
        binding.notificationViewPager.adapter = pagerAdapter
        binding.notificationViewPager.setOffscreenPageLimit(4)
        binding.notificationViewPager.setCurrentItem(selected, false)
        navBar.selectTabAt(selected)
        navBar.setOnTabSelectListener(object : AnimatedBottomBar.OnTabSelectListener {
            override fun onTabSelected(
                lastIndex: Int,
                lastTab: AnimatedBottomBar.Tab?,
                newIndex: Int,
                newTab: AnimatedBottomBar.Tab
            ) {
                selected = newIndex
                binding.notificationViewPager.setCurrentItem(selected, false)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        navBar.selectTabAt(selected)
    }

    private class ViewPagerAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle,
        private val activityId: Int
    ) : FragmentStateAdapter(fragmentManager, lifecycle) {
        /** The "Following" tab's fragment, once created — so its own settings icon can reload it. */
        var followingFragment: ActivityFragment? = null
            private set

        override fun getItemCount(): Int = if (activityId != -1) 1 else 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> ActivityFragment.newInstance(
                    if (activityId != -1) ActivityType.ONE else ActivityType.USER,
                    activityId = activityId
                ).also { followingFragment = it }

                else -> ActivityFragment.newInstance(ActivityType.GLOBAL)
            }
        }
    }
}
