package ani.dantotsu.media

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.connections.anilist.AniMangaSearchResults
import ani.dantotsu.databinding.ActivityListBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.media.user.ListViewPagerAdapter
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CalendarActivity : AppCompatActivity(), AniMangaFilterHost {
    private lateinit var binding: ActivityListBinding
    private val scope = lifecycleScope
    private var selectedTabIdx = 1
    private val model: OtherDetailsViewModel by viewModels()

    override val aniMangaResult: AniMangaSearchResults = AniMangaSearchResults(
        type = "ANIME",
        // Matches SearchActivity's own default: adult content isn't force-included just
        // because the account allows it, only when the user explicitly opts in below.
        isAdult = false,
        results = mutableListOf(),
        hasNextPage = false,
    )
    override val updateChips: () -> Unit = { updateFilterChips() }
    // Status/season/year-range don't meaningfully apply to an airing-right-now feed, and
    // hiding them means calendar presets aren't shaped like real anime-search presets —
    // keep them in their own bucket instead of mixing with search's saved filters.
    override val presetsType = "CALENDAR"
    override val supportsStatusSeasonYear = false

    override fun search() {
        scope.launch { model.loadCalendar(aniMangaResult) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityListBinding.inflate(layoutInflater)

        val primaryColor = getThemeColor(com.google.android.material.R.attr.colorSurface)
        val primaryTextColor = getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val secondaryTextColor = getThemeColor(com.google.android.material.R.attr.colorOutline)

        window.statusBarColor = primaryColor
        window.navigationBarColor = primaryColor
        binding.listTabLayout.setBackgroundColor(primaryColor)
        binding.listAppBar.setBackgroundColor(primaryColor)
        binding.listTitle.setTextColor(primaryTextColor)
        binding.listTabLayout.setTabTextColors(secondaryTextColor, primaryTextColor)
        binding.listTabLayout.setSelectedTabIndicatorColor(primaryTextColor)
        if (!(PrefManager.getVal(PrefName.ImmersiveMode) as Boolean)) {
            this.window.statusBarColor =
                ContextCompat.getColor(this, R.color.nav_bg_inv)
            binding.root.fitsSystemWindows = true

        } else {
            binding.root.fitsSystemWindows = false
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            hideSystemBarsExtendView()
            binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
        }
        setContentView(binding.root)

        binding.listBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.listTitle.setText(R.string.release_calendar)
        binding.listSort.visibility = View.GONE
        binding.random.visibility = View.GONE
        binding.search.visibility = View.GONE
        // Superseded by the "On list" tri-state filter in the filter sheet.
        binding.listed.visibility = View.GONE

        binding.filter.setOnClickListener {
            SearchFilterBottomDialog.newInstance().show(supportFragmentManager, "calendar_filter")
        }
        updateFilterChips()

        binding.listTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                this@CalendarActivity.selectedTabIdx = tab?.position ?: 1
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        model.getCalendar().observe(this) {
            if (it != null) {
                binding.listProgressBar.visibility = View.GONE
                binding.listViewPager.adapter = ListViewPagerAdapter((0 until it.size).toList(), true, this)
                val keys = it.keys.toList()
                val values = it.values.toList()
                val savedTab = this.selectedTabIdx
                TabLayoutMediator(binding.listTabLayout, binding.listViewPager) { tab, position ->
                    tab.text = "${keys[position]} (${values[position].size})"
                }.attach()
                binding.listViewPager.setCurrentItem(savedTab, false)
            }
        }

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(this) {
            if (it) {
                scope.launch {
                    withContext(Dispatchers.IO) { model.loadCalendar(aniMangaResult) }
                    live.postValue(false)
                }
            }
        }
    }

    private fun updateFilterChips() {
        binding.genreChipsGroup.removeAllViews()
        val chips = aniMangaResult.toChipList()
        if (chips.isEmpty()) {
            binding.genreChipsScrollView.visibility = View.GONE
            return
        }
        binding.genreChipsScrollView.visibility = View.VISIBLE
        chips.forEach { chip ->
            val chipView = Chip(this)
            chipView.text = chip.text.replace("_", " ")
            chipView.isCloseIconVisible = true
            chipView.chipBackgroundColor = ContextCompat.getColorStateList(this, R.color.chip_background_color)
            chipView.chipStrokeColor = ColorStateList.valueOf(
                getThemeColor(com.google.android.material.R.attr.colorPrimaryContainer)
            )
            chipView.setTextAppearance(R.style.Suffix)
            chipView.textSize = 14f
            val remove = {
                aniMangaResult.removeChip(chip)
                updateFilterChips()
                search()
            }
            chipView.setOnClickListener { remove() }
            chipView.setOnCloseIconClickListener { remove() }
            binding.genreChipsGroup.addView(chipView)
        }
    }
}
