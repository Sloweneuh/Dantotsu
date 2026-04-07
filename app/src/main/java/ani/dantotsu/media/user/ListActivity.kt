package ani.dantotsu.media.user

import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.databinding.ActivityListBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.stripSpansOnPaste
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
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
    // Base label of the selected tab without the count suffix, e.g. "Reading" or "MangaUpdates"
    private var selectedTabBase: String? = null
    // When the user is actively searching, preserve the tab base so rebuilds don't
    // jump away while results update asynchronously.
    // Prefer preserving a stable tab tag key when possible (set on tabs during build).
    private var preservedTabKeyDuringSearch: String? = null
    private val model: ListViewModel by viewModels()
    /** Position of the dedicated MangaUpdates aggregate tab in the ViewPager, or -1 if absent. */
    private var muTabPosition: Int = -1
    /** Position where the first "Separate" custom MU tab is inserted (before the aggregate tab). */
    private var muSeparateTabsStart: Int = -1
    /** Keys of "Separate" custom MU lists that get their own tabs before [muTabPosition]. */
    private var muSeparateTabs: List<String> = emptyList()
    private val muStandardKeys = setOf("Reading", "Planning", "Completed", "Dropped", "Paused")
    private var anime: Boolean = true

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
        this.anime = anime
        binding.listTitle.text = getString(
            R.string.user_list, intent.getStringExtra("username"),
            if (anime) getString(R.string.anime) else getString(R.string.manga)
        )

        binding.listBack.setOnClickListener {
            finish()
        }
        binding.listTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                this@ListActivity.selectedTabIdx = tab?.position ?: 0
                // Save the base label (drop any trailing " (count)") so we can restore the
                // same logical tab after tabs are rebuilt/filtered.
                val text = tab?.text?.toString()
                selectedTabBase = text?.let { it.replace(Regex(" \\(.+\\)$"), "") }
                // Update find-equivalents visibility when the selected tab changes
                val hasMu = model.getFilteredMuLists().value != null && model.getFilteredMuLists().value!!.values.flatten().isNotEmpty()
                binding.listFindEquivalents.isVisible = hasMu && muTabPosition >= 0 && this@ListActivity.selectedTabIdx == muTabPosition
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        model.getLists().observe(this) {
            if (it != null) {
                binding.listProgressBar.visibility = View.GONE
                buildTabs(it, model.getFilteredMuLists().value)
            }
        }

        // Observe current filters to update chips when filters are reapplied
        model.currentFilters.observe(this) { filters ->
            if (filters != null) {
                updateFilterChips(filters)
            }
        }

        // Update tab counts once MU lists finish loading (or when search filters them)
        model.getFilteredMuLists().observe(this) { muMap ->
            if (muMap == null) return@observe
            val aniMap = model.getLists().value ?: return@observe
            buildTabs(aniMap, muMap)
        }

        // Show find-equivalents button when MU lists are present (only on MU tab)
        model.getFilteredMuLists().observe(this) { muMap ->
            val hasMu = muMap != null && muMap.values.flatten().isNotEmpty()
            // Only show the button if MU lists exist AND the user is on the MangaUpdates tab
            binding.listFindEquivalents.isVisible = hasMu && muTabPosition >= 0 && selectedTabIdx == muTabPosition
            if (hasMu) {
                binding.listFindEquivalents.setOnClickListener {
                    val dialog = ani.dantotsu.others.CustomBottomDialog.newInstance()
                    dialog.setTitleText(getString(R.string.search_equivalents_confirm_title))
                    val tv = android.widget.TextView(this@ListActivity).apply {
                        setPadding(32, 16, 32, 16)
                        text = getString(R.string.search_equivalents_confirm_text)
                        textSize = 14f
                    }
                    dialog.addView(tv)
                    dialog.setNegativeButton(getString(R.string.cancel)) {
                        dialog.dismiss()
                    }
                    dialog.setPositiveButton(getString(R.string.proceed)) {
                        try {
                            // Collect all MU items across lists
                            val allMu = muMap.values.flatten().toMutableList()
                            ani.dantotsu.media.MediaEquivalentsActivity.passedMuMedia = ArrayList(allMu)
                            val intent = android.content.Intent(this@ListActivity, ani.dantotsu.media.MediaEquivalentsActivity::class.java)
                            startActivity(intent)
                        } catch (_: Exception) {}
                        dialog.dismiss()
                    }
                    dialog.show(supportFragmentManager, "mu_equivalents_confirm")
                }
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

        updateSortIcon(anime)

        binding.listSort.setOnClickListener {
            val popup = PopupMenu(this, it)
            popup.setOnMenuItemClickListener { item ->
                val prefName = if (anime) PrefName.AnimeListSortOrder else PrefName.MangaListSortOrder
                val currentSort: String = PrefManager.getVal(prefName)
                // Default directions: score/release/updated → desc, title → asc
                // If the same base is already selected, flip the direction
                val baseKey = when (item.itemId) {
                    R.id.score -> "score"
                    R.id.title -> "title"
                    R.id.release -> "release"
                    R.id.updated -> "updatedAt"
                    else -> null
                }
                val currentBase = currentSort.removeSuffix("_asc").removeSuffix("_desc")
                val onlyDirectionChanged = baseKey != null && currentBase == baseKey
                val sort = baseKey?.let { base ->
                    if (onlyDirectionChanged) {
                        // Toggle direction only
                        if (currentSort.endsWith("_asc")) "${base}_desc" else "${base}_asc"
                    } else {
                        // New sort: title defaults to ascending (A→Z), others to descending
                        if (base == "title") "${base}_asc" else "${base}_desc"
                    }
                }
                PrefManager.setVal(
                    if (anime) PrefName.AnimeListSortOrder else PrefName.MangaListSortOrder,
                    sort ?: ""
                )
                updateSortIcon(anime)
                if (onlyDirectionChanged) {
                    model.reverseLists()
                } else {
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
                }
                true
            }
            popup.inflate(R.menu.list_sort_menu)
            // Force icons to show in the popup menu
            try {
                val field = popup.javaClass.getDeclaredField("mPopup")
                field.isAccessible = true
                val helper = field.get(popup)
                helper.javaClass.getMethod("setForceShowIcon", Boolean::class.java)
                    .invoke(helper, true)
            } catch (_: Exception) { }
            // Highlight the currently active sort method with colorPrimary
            val prefName2 = if (anime) PrefName.AnimeListSortOrder else PrefName.MangaListSortOrder
            val activeSort: String = PrefManager.getVal(prefName2)
            val activeBase = activeSort.removeSuffix("_asc").removeSuffix("_desc")
            val activeItemId = when (activeBase) {
                "score"     -> R.id.score
                "title"     -> R.id.title
                "release"   -> R.id.release
                "updatedAt" -> R.id.updated
                else        -> null
            }
            if (activeItemId != null) {
                val primary = getThemeColor(com.google.android.material.R.attr.colorPrimary)
                val activeItem = popup.menu.findItem(activeItemId)
                activeItem?.let { menuItem ->
                    val span = SpannableString(menuItem.title)
                    span.setSpan(ForegroundColorSpan(primary), 0, span.length, 0)
                    menuItem.title = span
                    menuItem.icon?.let { icon ->
                        val tinted = DrawableCompat.wrap(icon.mutate())
                        DrawableCompat.setTint(tinted, primary)
                        menuItem.icon = tinted
                    }
                }
            }
            popup.show()
        }

        binding.listSort.setOnLongClickListener {
            val prefName = if (anime) PrefName.AnimeListSortOrder else PrefName.MangaListSortOrder
            val currentSort: String = PrefManager.getVal(prefName)
            if (currentSort.isBlank()) return@setOnLongClickListener true
            val sort = if (currentSort.endsWith("_asc")) {
                currentSort.removeSuffix("_asc") + "_desc"
            } else {
                currentSort.removeSuffix("_desc") + "_asc"
            }
            PrefManager.setVal(prefName, sort)
            updateSortIcon(anime)
            model.reverseLists()
            true
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
            val tag = "f" + currentTab?.position.toString()
            val currentFragment = supportFragmentManager.findFragmentByTag(tag)
            when (currentFragment) {
                is ListFragment -> currentFragment.randomOptionClick()
                is MUOnlyListFragment -> currentFragment.randomOptionClick()
            }
        }

        binding.search.setOnClickListener {
            val wasVisible = binding.searchView.isVisible
            toggleSearchView(wasVisible)
            // When closing search view, the text.clear() in toggleSearchView
            // will trigger searchLists("") which handles filter reapplication
        }

        binding.searchViewText.stripSpansOnPaste()
        binding.searchViewText.addTextChangedListener {
            val q = binding.searchViewText.text.toString()
            if (q.isNotEmpty()) {
                val curTab = binding.listTabLayout.getTabAt(binding.listTabLayout.selectedTabPosition)
                // Prefer stable tag, fallback to text-based key prefixed with TEXT:
                preservedTabKeyDuringSearch = curTab?.tag as? String ?: curTab?.text?.toString()
                    ?.replace(Regex(" \\(.+\\)$"), "")?.let { "TEXT:$it" }
            } else {
                preservedTabKeyDuringSearch = null
            }
            model.searchLists(q)
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

        filters.excludedGenres.forEach { genre ->
            addFilterChip(getString(R.string.filter_exclude, genre), "ExcludedGenre") {
                val newFilters = filters.copy(excludedGenres = filters.excludedGenres - genre)
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

        filters.excludedTags.forEach { tag ->
            addFilterChip(getString(R.string.filter_exclude, tag), "ExcludedTag") {
                val newFilters = filters.copy(excludedTags = filters.excludedTags - tag)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        // Add format chips
        filters.formats.forEach { format ->
            addFilterChip(getString(R.string.filter_format, format), "Format") {
                val newFilters = filters.copy(formats = filters.formats - format)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        // Add status chips
        filters.statuses.forEach { status ->
            addFilterChip(getString(R.string.filter_status, status.replace("_", " ")), "Status") {
                val newFilters = filters.copy(statuses = filters.statuses - status)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        // Add source chips
        filters.sources.forEach { source ->
            addFilterChip(getString(R.string.filter_source, source.replace("_", " ")), "Source") {
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
            addFilterChip(getString(R.string.filter_country, country), "Country") {
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

        // Add English licenced chip
        if (filters.englishLicenced) {
            addFilterChip(getString(R.string.filter_english_licenced), "EnglishLicenced") {
                val newFilters = filters.copy(englishLicenced = false)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        filters.muFormat?.let { muFormat ->
            addFilterChip(getString(R.string.filter_format, muFormat), "MuFormat") {
                val newFilters = filters.copy(muFormat = null)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        filters.muYear?.let { muYear ->
            addFilterChip(getString(R.string.filter_year, muYear), "MuYear") {
                val newFilters = filters.copy(muYear = null)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        filters.muLicensed?.let { muLicensed ->
            val label = if (muLicensed == "yes") getString(R.string.mu_filter_licensed) else getString(R.string.not_licensed)
            addFilterChip(label, "MuLicensed") {
                val newFilters = filters.copy(muLicensed = null)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        filters.muGenres.forEach { genre ->
            addFilterChip(genre, "MuGenre") {
                val newFilters = filters.copy(muGenres = filters.muGenres - genre)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        filters.muExcludedGenres.forEach { genre ->
            addFilterChip(getString(R.string.filter_exclude, genre), "MuExcludedGenre") {
                val newFilters = filters.copy(muExcludedGenres = filters.muExcludedGenres - genre)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        filters.muCategories.forEach { category ->
            addFilterChip(category, "MuCategory") {
                val newFilters = filters.copy(muCategories = filters.muCategories - category)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        filters.muExcludedCategories.forEach { category ->
            addFilterChip(getString(R.string.filter_exclude, category), "MuExcludedCategory") {
                val newFilters = filters.copy(muExcludedCategories = filters.muExcludedCategories - category)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }

        filters.muStatusFilters.forEach { status ->
            val label = ani.dantotsu.connections.anilist.MUSearchResults.STATUS_FILTER_LABELS[status] ?: status
            addFilterChip(label, "MuStatus") {
                val newFilters = filters.copy(muStatusFilters = filters.muStatusFilters - status)
                model.applyFilters(newFilters)
                updateFilterChips(newFilters)
            }
        }
    }

    private fun buildTabs(
        aniMap: Map<String, ArrayList<ani.dantotsu.media.Media>>,
        muMap: Map<String, List<ani.dantotsu.connections.mangaupdates.MUMedia>>?
    ) {
        val defaultKeys = listOf(
            "Reading", "Watching", "Completed", "Paused", "Dropped",
            "Planning", "Favourites", "Rewatching", "Rereading", "All"
        )
        val userKeys: Array<String> = resources.getStringArray(R.array.keys)
        val aniKeysList = aniMap.keys.toList()
        val aniValuesList = aniMap.values.toList()

        val showMuTab = !anime && MangaUpdates.token != null
        val allAniIdx = aniKeysList.indexOf("All")
        val insertAt = if (allAniIdx >= 0) allAniIdx else aniKeysList.size

        // Only include non-empty MU separate tabs
        muSeparateTabs = if (showMuTab && muMap != null) {
            muMap.keys.filter { k -> k !in muStandardKeys && (muMap[k]?.isNotEmpty() == true) }
        } else emptyList()

        // Include only anilist entries that have at least one item (anilist + matching MU)
        val aniBeforeIndices = mutableListOf<Int>()
        val aniAfterIndices = mutableListOf<Int>()
        for (i in aniKeysList.indices) {
            val aniKey = aniKeysList[i]
            val aniCount = aniValuesList[i].size
            val muCount = when {
                !showMuTab || muMap == null -> 0
                aniKey == "All" -> muMap.values.sumOf { it.size }
                else -> muMap[aniKey]?.size ?: 0
            }
            if (aniCount + muCount > 0) {
                if (i < insertAt) aniBeforeIndices.add(i) else aniAfterIndices.add(i)
            }
        }
        val aniIndices = aniBeforeIndices + aniAfterIndices

        // Show MU aggregate tab only when there are any MU items
        val totalMu = if (showMuTab && muMap != null) muMap.values.sumOf { it.size } else 0
        muSeparateTabsStart = if (showMuTab) aniBeforeIndices.size else -1
        muTabPosition = if (showMuTab && (totalMu > 0 || muSeparateTabs.isNotEmpty()))
            aniBeforeIndices.size + muSeparateTabs.size else -1

        val totalSize = aniIndices.size + muSeparateTabs.size + (if (muTabPosition >= 0) 1 else 0)
        val savedTab = selectedTabIdx.coerceIn(0, (totalSize - 1).coerceAtLeast(0))

        // Capture the currently visible tab base BEFORE we reset the adapter. When the
        // adapter is replaced and TabLayoutMediator attaches it may immediately select
        // the first tab and trigger our OnTabSelected listener, overwriting
        // `selectedTabBase`. Preserve the pre-rebuild base here for reliable restoration.
        val prevTabText = binding.listTabLayout.getTabAt(binding.listTabLayout.selectedTabPosition)
            ?.text?.toString()
        val prevTabBase = prevTabText?.replace(Regex(" \\(.+\\)$"), "")

        binding.listViewPager.adapter = ListViewPagerAdapter(
            aniIndices, false, this, muTabPosition, muSeparateTabs
        )

        TabLayoutMediator(binding.listTabLayout, binding.listViewPager) { tab, position ->
            when {
                muTabPosition >= 0 && position == muTabPosition -> {
                    tab.text = "MangaUpdates ($totalMu)"
                    tab.tag = "MU:AGGREGATE"
                }
                muTabPosition >= 0 && muSeparateTabsStart >= 0 &&
                        position in muSeparateTabsStart until muTabPosition -> {
                    val key = muSeparateTabs[position - muSeparateTabsStart]
                    tab.text = "$key (${muMap?.get(key)?.size ?: 0})"
                    tab.tag = "MU:SEPARATE:$key"
                }
                muTabPosition >= 0 && position > muTabPosition -> {
                    val aniIdxInList = position - muSeparateTabs.size - 1
                    val origIdx = aniIndices[aniIdxInList]
                    val aniKey = aniKeysList[origIdx]
                    val muCount = when {
                        aniKey == "All" -> muMap?.values?.sumOf { it.size } ?: 0
                        else -> muMap?.get(aniKey)?.size ?: 0
                    }
                    val displayKey = userKeys.getOrNull(defaultKeys.indexOf(aniKey)) ?: aniKey
                    tab.text = "$displayKey (${aniValuesList[origIdx].size + muCount})"
                    tab.tag = "ANI:$aniKey"
                }
                else -> {
                    val origIdx = aniIndices[position]
                    val aniKey = aniKeysList[origIdx]
                    val muCount = when {
                        !showMuTab || muMap == null -> 0
                        aniKey == "All" -> muMap.values.sumOf { it.size }
                        else -> muMap[aniKey]?.size ?: 0
                    }
                    val displayKey = userKeys.getOrNull(defaultKeys.indexOf(aniKey)) ?: aniKey
                    tab.text = "$displayKey (${aniValuesList[origIdx].size + muCount})"
                    tab.tag = "ANI:$aniKey"
                }
            }
        }.attach()
        // Try to find a tab whose stable tag matches the preserved key (if any),
        // otherwise fall back to matching text.
        var finalTab = savedTab
        val matchKey = preservedTabKeyDuringSearch ?: prevTabText?.let { it.replace(Regex(" \\(.+\\)$"), "") }
        if (matchKey != null) {
            for (i in 0 until totalSize) {
                val tab = binding.listTabLayout.getTabAt(i)
                val tag = tab?.tag as? String
                if (tag != null && matchKey.startsWith("TEXT:").not()) {
                    // If we have a preserved tag (not a TEXT: fallback), match tags directly
                    if (tag.equals(matchKey, ignoreCase = true)) {
                        finalTab = i
                        break
                    }
                } else {
                    // Fallback: compare text base with TEXT: payload or plain matchKey
                    val tabText = tab?.text?.toString() ?: continue
                    val tabBase = tabText.replace(Regex(" \\(.+\\)$"), "")
                    val targetBase = if (matchKey.startsWith("TEXT:")) matchKey.removePrefix("TEXT:") else matchKey
                    if (tabBase.equals(targetBase, ignoreCase = true)) {
                        finalTab = i
                        break
                    }
                }
            }
        }
        binding.listViewPager.setCurrentItem(finalTab, false)
    }

    private fun updateSortIcon(anime: Boolean) {
        val prefName = if (anime) PrefName.AnimeListSortOrder else PrefName.MangaListSortOrder
        val currentSort: String = PrefManager.getVal(prefName)
        val iconRes = if (currentSort.endsWith("_asc")) {
            R.drawable.ic_round_sort_up_24
        } else {
            R.drawable.ic_round_sort_24
        }
        binding.listSort.setImageResource(iconRes)
    }

    private fun addFilterChip(text: String, type: String, onRemove: () -> Unit) {
        val chip = com.google.android.material.chip.Chip(this)
        chip.text = text
        chip.isCloseIconVisible = true

        // Add provider icon based on filter type
        val isMuFilter = type.startsWith("Mu")
        
        // Check if MU tab is visible
        val showMuTab = !anime &&
            MangaUpdates.token != null &&
            PrefManager.getVal<Boolean>(PrefName.MangaUpdatesListEnabled)
        
        if (isMuFilter) {
            chip.chipIcon = ContextCompat.getDrawable(this, R.drawable.ic_round_mangaupdates_24)
            chip.chipIconTint = android.content.res.ColorStateList.valueOf(
                getThemeColor(com.google.android.material.R.attr.colorOnSurface)
            )
        } else if (showMuTab && type in listOf("Genre", "Tag", "Format", "Status", "Source", "Season", "Country", "Score", "YearRange", "EnglishLicenced")) {
            // Only show AniList icon if MU tab is visible (to differentiate sources)
            chip.chipIcon = ContextCompat.getDrawable(this, R.drawable.ic_anilist)
            chip.chipIconTint = null
        }

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
