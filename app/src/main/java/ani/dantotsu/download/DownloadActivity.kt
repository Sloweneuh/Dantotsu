package ani.dantotsu.download

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityDownloadBinding
import ani.dantotsu.databinding.DialogDownloadSettingsBinding
import ani.dantotsu.download.manage.DownloadManagementFragment
import ani.dantotsu.download.manage.DownloadQueueFragment
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaType
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.Settings
import ani.dantotsu.settings.SettingsAdapter
import ani.dantotsu.settings.SettingsRouter
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.LauncherWrapper
import ani.dantotsu.util.customAlertDialog
import nl.joery.animatedbottombar.AnimatedBottomBar
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DownloadActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDownloadBinding
    lateinit var launcher: LauncherWrapper
    private val downloadsManager get() = Injekt.get<DownloadsManager>()

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
        binding.downloadSettingsButton.setOnClickListener { showDownloadSettingsDialog() }

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

        // Settings search lands here for settings that live in the dialog below (as opposed to
        // the download location, which stays inline on the Manage tab) — open it automatically.
        val anchorTitle = intent.getIntExtra(SettingsRouter.EXTRA_ANCHOR_TITLE, 0)
        if (anchorTitle in DIALOG_SETTING_TITLES) showDownloadSettingsDialog()
    }

    private fun showDownloadSettingsDialog() {
        val view = DialogDownloadSettingsBinding.inflate(layoutInflater)
        view.downloadSettingsRecycler.layoutManager = LinearLayoutManager(this)
        view.downloadSettingsRecycler.adapter = SettingsAdapter(
            arrayListOf(
                Settings(
                    type = 1,
                    name = getString(R.string.download_manager_select),
                    desc = getString(R.string.download_manager_select_desc),
                    icon = R.drawable.ic_download_24,
                    onClick = {
                        val managers = arrayOf("Default", "1DM", "ADM")
                        customAlertDialog().apply {
                            setTitle(getString(R.string.download_manager))
                            singleChoiceItems(
                                managers,
                                PrefManager.getVal(PrefName.DownloadManager),
                            ) { count ->
                                PrefManager.setVal(PrefName.DownloadManager, count)
                            }
                            show()
                        }
                    },
                ),
                Settings(
                    type = 2,
                    name = getString(R.string.allow_metered_downloads),
                    desc = getString(R.string.allow_metered_downloads_desc),
                    icon = R.drawable.ic_download_24,
                    isChecked = PrefManager.getVal(PrefName.AllowMeteredDownloads),
                    switch = { isChecked, _ ->
                        PrefManager.setVal(PrefName.AllowMeteredDownloads, isChecked)
                    },
                ),
                purgeSetting(
                    MediaType.ANIME,
                    R.string.purge_anime_downloads,
                    R.string.purge_anime_downloads_desc,
                    R.string.anime,
                ),
                purgeSetting(
                    MediaType.MANGA,
                    R.string.purge_manga_downloads,
                    R.string.purge_manga_downloads_desc,
                    R.string.manga,
                ),
                purgeSetting(
                    MediaType.NOVEL,
                    R.string.purge_novel_downloads,
                    R.string.purge_novel_downloads_desc,
                    R.string.novels,
                ),
            )
        )
        customAlertDialog().apply {
            setTitle(R.string.download_settings)
            setCustomView(view.root)
            setPosButton(R.string.ok) {}
            show()
        }
    }

    private fun purgeSetting(
        type: MediaType,
        titleRes: Int,
        descRes: Int,
        mediaNameRes: Int,
    ) = Settings(
        type = 1,
        name = getString(titleRes),
        desc = getString(descRes),
        icon = R.drawable.ic_round_delete_24,
        onClick = {
            customAlertDialog().apply {
                setTitle(titleRes)
                setMessage(R.string.purge_confirm, getString(mediaNameRes))
                setPosButton(R.string.yes) {
                    downloadsManager.purgeDownloads(type)
                }
                setNegButton(R.string.no)
                show()
            }
        },
    )

    companion object {
        /** Titles of settings-search entries that live in [showDownloadSettingsDialog]. */
        private val DIALOG_SETTING_TITLES = setOf(
            R.string.download_manager_select,
            R.string.allow_metered_downloads,
            R.string.purge_anime_downloads,
            R.string.purge_manga_downloads,
            R.string.purge_novel_downloads,
        )
    }
}
