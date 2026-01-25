package ani.dantotsu.media.user

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.databinding.ActivityListBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale


class ListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityListBinding
    private val scope = lifecycleScope
    private var selectedTabIdx = 0
    private val model: ListViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityListBinding.inflate(layoutInflater)

        val primaryColor = getThemeColor(com.google.android.material.R.attr.colorSurface)
        val primaryTextColor = getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val secondaryTextColor = getThemeColor(com.google.android.material.R.attr.colorOutline)

        window.statusBarColor = primaryColor
        window.navigationBarColor = primaryColor
        binding.listed.visibility = View.GONE
        binding.listTabLayout.setBackgroundColor(primaryColor)
        binding.listAppBar.setBackgroundColor(primaryColor)
        binding.listTitle.setTextColor(primaryTextColor)
        binding.listTabLayout.setTabTextColors(secondaryTextColor, primaryTextColor)
        binding.listTabLayout.setSelectedTabIndicatorColor(primaryTextColor)
        if (!PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) {
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

        val anime = intent.getBooleanExtra("anime", true)
        binding.listTitle.text = getString(
            R.string.user_list, intent.getStringExtra("username"),
            if (anime) getString(R.string.anime) else getString(R.string.manga)
        )
        binding.listTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                this@ListActivity.selectedTabIdx = tab?.position ?: 0
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        model.getLists().observe(this) {
            val defaultKeys = listOf(
                "Reading",
                "Watching",
                "Completed",
                "Paused",
                "Dropped",
                "Planning",
                "Favourites",
                "Rewatching",
                "Rereading",
                "All"
            )
            val userKeys: Array<String> = resources.getStringArray(R.array.keys)

            if (it != null) {
                binding.listProgressBar.visibility = View.GONE
                binding.listViewPager.adapter = ListViewPagerAdapter(it.size, false, this)
                val keys = it.keys.toList()
                    .map { key -> userKeys.getOrNull(defaultKeys.indexOf(key)) ?: key }
                val values = it.values.toList()
                val savedTab = this.selectedTabIdx
                TabLayoutMediator(binding.listTabLayout, binding.listViewPager) { tab, position ->
                    tab.text = "${keys[position]} (${values[position].size})"
                }.attach()
                binding.listViewPager.setCurrentItem(savedTab, false)
            }
        }

        // Observe current filters to update chips when filters are reapplied
        model.currentFilters.observe(this) { filters ->
            if (filters != null) {
                updateFilterChips(filters)
            }
        }

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(this) {
            if (it) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        model.loadLists(
                            anime,
                            intent.getIntExtra("userId", 0)
                        )
                    }
                    live.postValue(false)
                }
            }
        }

        binding.listSort.setOnClickListener {
            val popup = PopupMenu(this, it)
            popup.setOnMenuItemClickListener { item ->
                val sort = when (item.itemId) {
                    R.id.score -> "score"
                    R.id.title -> "title"
                    R.id.updated -> "updatedAt"
                    R.id.release -> "release"
                    else -> null
                }
                PrefManager.setVal(
                    if (anime) PrefName.AnimeListSortOrder else PrefName.MangaListSortOrder,
                    sort ?: ""
                )
                binding.listProgressBar.visibility = View.VISIBLE
                binding.listViewPager.adapter = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        model.loadLists(
                            anime,
                            intent.getIntExtra("userId", 0),
                            sort
                        )
                    }
                }
                true
            }
            popup.inflate(R.menu.list_sort_menu)
            popup.show()
        }

        binding.filter.setOnClickListener {
            val currentFilters = model.currentFilters.value ?: ListFilters()

            val dialog = ListFilterBottomDialog(anime, currentFilters) { filters ->
                model.applyFilters(filters)
                updateFilterChips(filters)
            }
            dialog.show(supportFragmentManager, "list_filter")
        }

        binding.random.setOnClickListener {
            //get the current tab
            val currentTab =
                binding.listTabLayout.getTabAt(binding.listTabLayout.selectedTabPosition)
            val currentFragment =
                supportFragmentManager.findFragmentByTag("f" + currentTab?.position.toString()) as? ListFragment
            currentFragment?.randomOptionClick()
        }

        binding.search.setOnClickListener {
            val wasVisible = binding.searchView.isVisible
            toggleSearchView(wasVisible)
            // When closing search view, the text.clear() in toggleSearchView
            // will trigger searchLists("") which handles filter reapplication
        }

        binding.searchViewText.addTextChangedListener {
            model.searchLists(binding.searchViewText.text.toString())
        }
    }

    private fun toggleSearchView(isVisible: Boolean) {
        if (isVisible) {
            binding.searchView.visibility = View.GONE
            binding.searchViewText.text.clear()
        } else {
            binding.searchView.visibility = View.VISIBLE
            binding.searchViewText.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.searchViewText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun updateFilterChips(filters: ListFilters) {
        binding.genreChipsGroup.removeAllViews()

        if (filters.isEmpty()) {
            binding.genreChipsScrollView.visibility = View.GONE
            return
        }

        binding.genreChipsScrollView.visibility = View.VISIBLE

        // Add genre chips
        filters.genres.forEach { genre ->
            addFilterChip(genre, "Genre") {
                val newFilters = filters.copy(genres = filters.genres - genre)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        // Add tag chips
        filters.tags.forEach { tag ->
            addFilterChip(tag, "Tag") {
                val newFilters = filters.copy(tags = filters.tags - tag)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        // Add format chips
        filters.formats.forEach { format ->
            addFilterChip(format, "Format") {
                val newFilters = filters.copy(formats = filters.formats - format)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        // Add status chips
        filters.statuses.forEach { status ->
            addFilterChip(status, "Status") {
                val newFilters = filters.copy(statuses = filters.statuses - status)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        // Add source chips
        filters.sources.forEach { source ->
            addFilterChip(source, "Source") {
                val newFilters = filters.copy(sources = filters.sources - source)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        // Add season chip
        filters.season?.let { season ->
            addFilterChip(season, "Season") {
                val newFilters = filters.copy(season = null)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        // Add country chip
        filters.countryOfOrigin?.let { country ->
            addFilterChip(country, "Country") {
                val newFilters = filters.copy(countryOfOrigin = null)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        // Add score range chip (if not default)
        if (filters.scoreRange != Pair(0.0f, 10.0f)) {
            val minScore = if (filters.scoreRange.first % 1 == 0f) {
                filters.scoreRange.first.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", filters.scoreRange.first)
            }
            val maxScore = if (filters.scoreRange.second % 1 == 0f) {
                filters.scoreRange.second.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", filters.scoreRange.second)
            }
            addFilterChip("Score: $minScore-$maxScore", "Score") {
                val newFilters = filters.copy(scoreRange = Pair(0.0f, 10.0f))
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        // Add year range chip (if not default)
        if (filters.yearRange != Pair(1970, 2028)) {
            addFilterChip("Years: ${filters.yearRange.first}-${filters.yearRange.second}", "YearRange") {
                val newFilters = filters.copy(yearRange = Pair(1970, 2028))
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }
    }

    private fun addFilterChip(text: String, @Suppress("UNUSED_PARAMETER") type: String, onRemove: () -> Unit) {
        val chip = com.google.android.material.chip.Chip(this)
        chip.text = text
        chip.isCloseIconVisible = true

        // Make entire chip clickable, not just the X icon
        chip.setOnClickListener {
            onRemove()
        }
        chip.setOnCloseIconClickListener {
            onRemove()
        }

        // Apply theme colors to match the app style
        chip.chipBackgroundColor = ContextCompat.getColorStateList(this, R.color.chip_background_color)
        chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(
            getThemeColor(com.google.android.material.R.attr.colorPrimaryContainer)
        )
        chip.setTextAppearance(R.style.Suffix)
        chip.textSize = 14f

        binding.genreChipsGroup.addView(chip)
    }
}
