package ani.dantotsu.download

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityDownloadBinding
import ani.dantotsu.download.manage.DownloadManagementFragment
import ani.dantotsu.download.manage.DownloadQueueFragment
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.SettingsRouter
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.LauncherWrapper
import nl.joery.animatedbottombar.AnimatedBottomBar

class DownloadActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDownloadBinding
    lateinit var launcher: LauncherWrapper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        launcher = LauncherWrapper(this, ActivityResultContracts.OpenDocumentTree())

        binding.downloadContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.downloadBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.downloadSettingsButton.setOnClickListener {
            if (binding.downloadSettingsContainer.isVisible) {
                onBackPressedDispatcher.onBackPressed()
            } else {
                showDownloadSettingsPanel()
            }
        }

        val pager = binding.downloadViewPager
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int) =
                if (position == 0) DownloadQueueFragment() else DownloadManagementFragment()
        }

        val bar = binding.downloadBottomBar
        bar.addTab(
            bar.createTab(
                R.drawable.ic_round_cloud_download_24, R.string.download_queue, R.id.downloadQueueTab
            )
        )
        bar.addTab(
            bar.createTab(
                R.drawable.ic_round_library_books_24,
                R.string.download_management,
                R.id.downloadManageTab
            )
        )
        bar.setOnTabSelectListener(object : AnimatedBottomBar.OnTabSelectListener {
            override fun onTabSelected(
                lastIndex: Int,
                lastTab: AnimatedBottomBar.Tab?,
                newIndex: Int,
                newTab: AnimatedBottomBar.Tab
            ) {
                pager.setCurrentItem(newIndex, true)
            }
        })
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                bar.selectTabAt(position)
            }
        })

        val startTab = intent.getIntExtra("tab", 0)
        pager.setCurrentItem(startTab, false)
        bar.selectTabAt(startTab)

        // Settings search lands here for settings that live in the panel below (as opposed to
        // the download location, which stays inline on the Manage tab) — open it automatically.
        val anchorTitle = intent.getIntExtra(SettingsRouter.EXTRA_ANCHOR_TITLE, 0)
        if (anchorTitle in PANEL_SETTING_TITLES) showDownloadSettingsPanel()
    }

    private fun showDownloadSettingsPanel() {
        val changeUIVisibility: (Boolean) -> Unit = { show ->
            binding.downloadBottomBar.isVisible = show
            binding.downloadViewPager.isVisible = show
            binding.downloadSettingsContainer.isGone = show
        }
        val fragment = DownloadSettingsFragment().getInstance {
            changeUIVisibility(true)
        }
        changeUIVisibility(false)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.slide_up, R.anim.slide_down, R.anim.slide_up, R.anim.slide_down)
            .replace(R.id.downloadSettingsContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    companion object {
        /** Titles of settings-search entries that live in [showDownloadSettingsPanel]. */
        private val PANEL_SETTING_TITLES = setOf(
            R.string.download_manager_select,
            R.string.allow_metered_downloads,
            R.string.purge_anime_downloads,
            R.string.purge_manga_downloads,
            R.string.purge_novel_downloads,
        )
    }
}
