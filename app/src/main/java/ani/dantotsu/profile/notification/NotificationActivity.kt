package ani.dantotsu.profile.notification

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityNotificationBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.notifications.NotificationReadState
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.COMMENT
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.MEDIA
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.ONE
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.SUBSCRIPTION
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.UNREAD_CHAPTER
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.USER
import ani.dantotsu.profile.notification.NotificationFragment.Companion.newInstance
import ani.dantotsu.settings.NotificationSection
import ani.dantotsu.settings.notificationSettingsIntent
import ani.dantotsu.settings.bindQuickSettings
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import nl.joery.animatedbottombar.AnimatedBottomBar

class NotificationActivity : AppCompatActivity() {
    lateinit var binding: ActivityNotificationBinding
    private var selected: Int = 0
    lateinit var navBar: AnimatedBottomBar
    private val CommentsEnabled = PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1

    /**
     * Whether the tabs list every notification or only unread ones. Off each time the screen is
     * opened (kept across a rotation only); the tab fragments observe it and reload on change.
     */
    val showAll = MutableLiveData(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityNotificationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.notificationTitle.text = getString(R.string.notifications)
        binding.notificationToolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        navBar = binding.notificationNavBar
        binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }

        val tabs = mutableListOf(
            Pair(R.drawable.ic_round_person_24, "User"),
            Pair(R.drawable.ic_round_movie_filter_24, "Media"),
            Pair(R.drawable.ic_round_notifications_active_24, "Subs"),
            Pair(R.drawable.ic_round_malsync_notifications_24, "MalSync")
        )
        if (CommentsEnabled) {
            tabs.add(Pair(R.drawable.ic_round_comment_24, "Comments"))
        }

        tabs.forEach { (icon, title) -> navBar.addTab(navBar.createTab(icon, title)) }

        val requestedTab = intent.getIntExtra("selectedTab", -1)
        if (requestedTab in tabs.indices) selected = requestedTab

        binding.notificationBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.quickSettings.bindQuickSettings(this)

        val restoredShowAll = savedInstanceState?.getBoolean(KEY_SHOW_ALL) ?: false
        showAll.value = restoredShowAll
        binding.notificationShowAll.isChecked = restoredShowAll
        binding.notificationShowAll.setOnCheckedChangeListener { _, checked ->
            if (showAll.value != checked) showAll.value = checked
        }
        binding.notificationMarkAllRead.setOnClickListener { currentFragment()?.markAllRead() }

        // Per-tab unread counts on the tab icons, kept live off the stored set: an item tapped
        // here, "mark all as read", or a notification swiped away in the shade all move them.
        PrefManager.getLiveVal(PrefName.UnreadNotificationKeys, setOf<String>())
            .observe(this) { keys -> refreshTabBadges(keys) }

        // Settings button click listener
        binding.notificationSettings.setOnClickListener {
            openSettingsForCurrentTab()
        }
        
        val getOne = intent.getIntExtra("activityId", -1)
        if (getOne != -1) {
            // A single notification opened from the shade: nothing to filter or mark.
            navBar.isVisible = false
            binding.notificationListControls.isVisible = false
        }
        binding.notificationViewPager.isUserInputEnabled = false
        binding.notificationViewPager.adapter =
            ViewPagerAdapter(supportFragmentManager, lifecycle, getOne, CommentsEnabled)
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
        if (this::navBar.isInitialized) {
            navBar.selectTabAt(selected)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHOW_ALL, showAll.value == true)
    }

    /** FragmentStateAdapter tags its fragments "f" + item id, and the item id is the position. */
    private fun currentFragment(): NotificationFragment? =
        supportFragmentManager.findFragmentByTag("f$selected") as? NotificationFragment

    private fun refreshTabBadges(keys: Set<String>) {
        if (!navBar.isVisible) return
        TAB_PREFIXES.take(navBar.tabCount).forEachIndexed { index, prefix ->
            val count = NotificationReadState.unreadCount(prefix, keys)
            if (count > 0) {
                navBar.setBadgeAtTabIndex(index, AnimatedBottomBar.Badge(count.toString()))
            } else {
                navBar.clearBadgeAtTabIndex(index)
            }
        }
    }

    private fun openSettingsForCurrentTab() {
        // One notification screen now; the tab picks which group it opens expanded.
        val section = when (selected) {
            2 -> NotificationSection.SUBSCRIPTIONS // Subscription tab
            3 -> NotificationSection.MALSYNC       // Unread Chapter tab
            4 -> NotificationSection.COMMENTS      // Comments tab
            else -> NotificationSection.ANILIST    // User and Media tabs
        }
        startActivity(notificationSettingsIntent(this, section))
    }

    private class ViewPagerAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle,
        val id: Int = -1,
        val commentsEnabled: Boolean
    ) : FragmentStateAdapter(fragmentManager, lifecycle) {
        override fun getItemCount(): Int = if (id != -1) 1 else if (commentsEnabled) 5 else 4

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> newInstance(if (id != -1) ONE else USER, id)
            1 -> newInstance(MEDIA)
            2 -> newInstance(SUBSCRIPTION)
            3 -> newInstance(UNREAD_CHAPTER)
            4 -> newInstance(COMMENT)
            else -> newInstance(USER)
        }
    }

    private companion object {
        const val KEY_SHOW_ALL = "showAll"

        /** Tab order: User, Media, Subs, MalSync, Comments. */
        val TAB_PREFIXES = listOf(
            NotificationReadState.PREFIX_ANILIST_USER,
            NotificationReadState.PREFIX_ANILIST_MEDIA,
            NotificationReadState.PREFIX_SUBSCRIPTION,
            NotificationReadState.PREFIX_CHAPTER,
            NotificationReadState.PREFIX_COMMENT,
        )
    }
}

