package ani.dantotsu.download

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityDownloadBinding
import ani.dantotsu.download.manage.DownloadManagementFragment
import ani.dantotsu.download.manage.DownloadQueueFragment
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import nl.joery.animatedbottombar.AnimatedBottomBar

class DownloadActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDownloadBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.downloadContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.downloadBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

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
    }
}
