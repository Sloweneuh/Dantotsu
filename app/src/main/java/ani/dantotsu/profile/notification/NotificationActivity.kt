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
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityNotificationBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.COMMENT
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.MEDIA
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.ONE
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.SUBSCRIPTION
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.UNREAD_CHAPTER
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.USER
import ani.dantotsu.profile.notification.NotificationFragment.Companion.newInstance
import ani.dantotsu.settings.SettingsAnilistNotificationActivity
import ani.dantotsu.settings.SettingsCommentNotificationActivity
import ani.dantotsu.settings.SettingsSubscriptionNotificationActivity
import ani.dantotsu.settings.SettingsUnreadChapterNotificationActivity
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
            Pair(R.drawable.ic_round_menu_book_24, "Unread")
        )
        if (CommentsEnabled) {
            tabs.add(Pair(R.drawable.ic_round_comment_24, "Comments"))
        }

        tabs.forEach { (icon, title) -> navBar.addTab(navBar.createTab(icon, title)) }

        binding.notificationBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        
        // Settings button click listener
        binding.notificationSettings.setOnClickListener {
            openSettingsForCurrentTab()
        }
        
        val getOne = intent.getIntExtra("activityId", -1)
        if (getOne != -1) navBar.isVisible = false
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

    private fun openSettingsForCurrentTab() {
        val intent = when (selected) {
            0 -> Intent(this, SettingsAnilistNotificationActivity::class.java) // User tab
            1 -> Intent(this, SettingsAnilistNotificationActivity::class.java) // Media tab
            2 -> Intent(this, SettingsSubscriptionNotificationActivity::class.java) // Subscription tab
            3 -> Intent(this, SettingsUnreadChapterNotificationActivity::class.java) // Unread Chapter tab
            4 -> Intent(this, SettingsCommentNotificationActivity::class.java) // Comments tab
            else -> Intent(this, SettingsAnilistNotificationActivity::class.java)
        }
        startActivity(intent)
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
}

