package ani.dantotsu.media.user

import android.animation.ObjectAnimator
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.HORIZONTAL
import androidx.recyclerview.widget.RecyclerView.VERTICAL
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.databinding.BottomSheetListFilterBinding
import ani.dantotsu.databinding.ItemChipBinding
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayout
import eu.kanade.tachiyomi.util.system.getResourceColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class ListFilterBottomDialog(
    private val isAnime: Boolean,
    currentFilters: ListFilters,
    private val onApply: (ListFilters) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetListFilterBinding? = null
    private val binding get() = _binding!!

    private var selectedGenres = currentFilters.genres.toMutableList()
    private var selectedTags = currentFilters.tags.toMutableList()
    private var selectedFormats = currentFilters.formats.toMutableList()
    private var selectedStatuses = currentFilters.statuses.toMutableList()
    private var selectedSources = currentFilters.sources.toMutableList()
    private var selectedSeason: String? = currentFilters.season
    private var selectedYear: Int? = currentFilters.year
    private var selectedCountry: String? = currentFilters.countryOfOrigin
    private var scoreRange = currentFilters.scoreRange
    private var yearRange = currentFilters.yearRange
    private var englishLicenced: Boolean = currentFilters.englishLicenced
    private var selectedMuFormat: String? = currentFilters.muFormat
    private var selectedMuYear: Int? = currentFilters.muYear
    private var selectedMuLicensed: String? = currentFilters.muLicensed
    private var selectedMuGenres = currentFilters.muGenres.toMutableList()
    private var selectedMuExcludedGenres = currentFilters.muExcludedGenres.toMutableList()
    private var selectedMuCategories = currentFilters.muCategories.toMutableList()
    private var selectedMuStatusFilters = currentFilters.muStatusFilters.toMutableList()
    private var muCategorySearchJob: Job? = null
    private var tagSearchQuery: String = ""

    private fun createWrapChipLayoutManager(): RecyclerView.LayoutManager {
        return FlexboxLayoutManager(requireContext()).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.FLEX_START
        }
    }

    private val muStatusFilterOptions = listOf(
        "scanlated" to "Scanlated",
        "completed" to "Completed",
        "oneshots" to "Oneshots",
        "no_oneshots" to "No Oneshots",
        "some_releases" to "Has Releases",
        "no_releases" to "No Releases",
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetListFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupDropdowns()
        setupRangeSliders()
        setupChipLists()
        setupButtons()
        setupCountryFilter()
        setupEnglishLicencedFilter()
        setupProviderTabs()
        setupMuFilters()
    }

    private fun setupProviderTabs() {
        val showMuTab = !isAnime &&
            MangaUpdates.token != null &&
            ani.dantotsu.settings.saving.PrefManager.getVal<Boolean>(
                ani.dantotsu.settings.saving.PrefName.MangaUpdatesListEnabled
            )
        if (!showMuTab) {
            binding.listFilterProviderTabs.visibility = GONE
            binding.listFilterAnilistSection.visibility = View.VISIBLE
            binding.listFilterMuSection.visibility = GONE
            return
        }

        binding.listFilterProviderTabs.visibility = View.VISIBLE
        binding.listFilterProviderTabs.removeAllTabs()
        binding.listFilterProviderTabs.addTab(
            binding.listFilterProviderTabs.newTab().setText(R.string.filter_provider_anilist),
            true
        )
        binding.listFilterProviderTabs.addTab(
            binding.listFilterProviderTabs.newTab().setText(R.string.filter_provider_mangaupdates)
        )

        fun setSection(tabPosition: Int) {
            binding.listFilterAnilistSection.visibility = if (tabPosition == 0) View.VISIBLE else GONE
            binding.listFilterMuSection.visibility = if (tabPosition == 1) View.VISIBLE else GONE
            binding.countryFilter.visibility = if (tabPosition == 1) GONE else View.VISIBLE
        }

        setSection(0)
        binding.listFilterProviderTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = setSection(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun setupMuFilters() {
        val showMu = !isAnime &&
            MangaUpdates.token != null &&
            ani.dantotsu.settings.saving.PrefManager.getVal<Boolean>(
                ani.dantotsu.settings.saving.PrefName.MangaUpdatesListEnabled
            )
        binding.listFilterMuSection.visibility = GONE
        if (!showMu) return

        val formats = listOf(
            "", "Manga", "Manhwa", "Manhua", "OEL", "Artbook", "Doujinshi",
            "Drama CD", "Filipino", "French", "German", "Indonesian", "Malaysian",
            "Nordic", "Novel", "Spanish", "Thai", "Vietnamese"
        )
        binding.listMuFilterFormat.setText(selectedMuFormat ?: "", false)
        binding.listMuFilterFormat.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_dropdown, formats)
        )
        binding.listMuFilterFormat.setOnItemClickListener { _, _, _, _ ->
            selectedMuFormat = binding.listMuFilterFormat.text.toString().ifBlank { null }
        }

        val currentYear = Calendar.getInstance().get(Calendar.YEAR) + 1
        val years = listOf("") + (currentYear downTo 1950).map { it.toString() }
        binding.listMuFilterYear.setText(selectedMuYear?.toString() ?: "", false)
        binding.listMuFilterYear.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_dropdown, years)
        )
        binding.listMuFilterYear.setOnItemClickListener { _, _, _, _ ->
            selectedMuYear = binding.listMuFilterYear.text.toString().toIntOrNull()
        }

        val licensedLabels = listOf("", "Licensed", "Not Licensed")
        val licensedLabel = when (selectedMuLicensed) {
            "yes" -> "Licensed"
            "no" -> "Not Licensed"
            else -> ""
        }
        binding.listMuFilterLicensed.setText(licensedLabel, false)
        binding.listMuFilterLicensed.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_dropdown, licensedLabels)
        )
        binding.listMuFilterLicensed.setOnItemClickListener { _, _, _, _ ->
            selectedMuLicensed = when (binding.listMuFilterLicensed.text.toString()) {
                "Licensed" -> "yes"
                "Not Licensed" -> "no"
                else -> null
            }
        }

        binding.listMuFilterStatusRecycler.adapter =
            FilterChipAdapter(muStatusFilterOptions.map { it.second }) { chip ->
                val label = chip.text.toString()
                val apiValue = muStatusFilterOptions.firstOrNull { it.second == label }?.first ?: return@FilterChipAdapter
                chip.isChecked = selectedMuStatusFilters.contains(apiValue)
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        if (!selectedMuStatusFilters.contains(apiValue)) selectedMuStatusFilters.add(apiValue)
                    } else {
                        selectedMuStatusFilters.remove(apiValue)
                    }
                }
            }

        CoroutineScope(Dispatchers.IO).launch {
            val genres = MangaUpdates.getGenres()
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                binding.listMuFilterGenresRecycler.adapter =
                    FilterChipAdapter(genres) { chip ->
                        val genre = chip.text.toString()
                        chip.isChecked = selectedMuGenres.contains(genre)
                        chip.isCloseIconVisible = selectedMuExcludedGenres.contains(genre)
                        chip.setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) {
                                chip.isCloseIconVisible = false
                                selectedMuExcludedGenres.remove(genre)
                                if (!selectedMuGenres.contains(genre)) selectedMuGenres.add(genre)
                            } else {
                                selectedMuGenres.remove(genre)
                            }
                        }
                        chip.setOnLongClickListener {
                            chip.isChecked = false
                            selectedMuGenres.remove(genre)
                            chip.isCloseIconVisible = true
                            if (!selectedMuExcludedGenres.contains(genre)) selectedMuExcludedGenres.add(genre)
                            true
                        }
                        chip.setOnCloseIconClickListener {
                            chip.isCloseIconVisible = false
                            selectedMuExcludedGenres.remove(genre)
                        }
                    }
            }
        }

        binding.listMuFilterGenresGrid.setOnCheckedChangeListener { _, isChecked ->
            binding.listMuFilterGenresRecycler.layoutManager =
                if (!isChecked) LinearLayoutManager(requireContext(), HORIZONTAL, false)
                else GridLayoutManager(requireContext(), 2, VERTICAL, false)
        }
        binding.listMuFilterGenresGrid.isChecked = false

        binding.listMuFilterCategoryResultsRecycler.layoutManager = createWrapChipLayoutManager()

        fun updateCategoryResults(categories: List<String>) {
            if (_binding == null) return
            binding.listMuFilterCategoryResultsRecycler.adapter =
                FilterChipAdapter(categories) { chip ->
                    val category = chip.text.toString()
                    chip.isChecked = selectedMuCategories.contains(category)
                    chip.setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            if (!selectedMuCategories.contains(category)) selectedMuCategories.add(category)
                        } else {
                            selectedMuCategories.remove(category)
                        }
                    }
                }
        }

        updateCategoryResults(selectedMuCategories.toList())
        binding.listMuFilterCategorySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                muCategorySearchJob?.cancel()
                if (query.isBlank()) {
                    binding.listMuFilterCategoryProgress.visibility = View.GONE
                    updateCategoryResults(selectedMuCategories.toList())
                    return
                }

                muCategorySearchJob = CoroutineScope(Dispatchers.IO).launch {
                    delay(400)
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        binding.listMuFilterCategoryProgress.visibility = View.VISIBLE
                    }
                    val results = MangaUpdates.getCategories(query)
                    val sortedResults = sortCategoriesByRelevance(results, query)
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        binding.listMuFilterCategoryProgress.visibility = View.GONE
                        updateCategoryResults(sortedResults)
                    }
                }
            }
        })
    }

    private fun setupDropdowns() {
        // Source
        binding.listFilterSource.setText(selectedSources.firstOrNull())
        binding.listFilterSource.setAdapter(
            ArrayAdapter(
                binding.root.context,
                R.layout.item_dropdown,
                Anilist.source.toTypedArray()
            )
        )

        // Format
        binding.listFilterFormat.setText(selectedFormats.firstOrNull())
        binding.listFilterFormat.setAdapter(
            ArrayAdapter(
                binding.root.context,
                R.layout.item_dropdown,
                (if (isAnime) Anilist.animeFormats else Anilist.mangaFormats).toTypedArray()
            )
        )

        // Status
        binding.listFilterStatus.setText(selectedStatuses.firstOrNull())
        binding.listFilterStatus.setAdapter(
            ArrayAdapter(
                binding.root.context,
                R.layout.item_dropdown,
                if (isAnime) Anilist.animeStatus else Anilist.mangaStatus
            )
        )

        // Season (anime only)
        if (!isAnime) {
            binding.listFilterSeasonCont.visibility = GONE
        } else {
            binding.listFilterSeason.setText(selectedSeason)
            binding.listFilterSeason.setAdapter(
                ArrayAdapter(
                    binding.root.context,
                    R.layout.item_dropdown,
                    Anilist.seasons.toTypedArray()
                )
            )
        }
    }

    private fun setupRangeSliders() {
        // Score range (0.0-10.0 for display)
        binding.listFilterScoreRange.values = listOf(scoreRange.first, scoreRange.second)

        // Year range
        binding.listFilterYearRange.values = listOf(yearRange.first.toFloat(), yearRange.second.toFloat())
    }

    private fun setupChipLists() {
        // Genres
        binding.listFilterGenres.adapter = FilterChipAdapter(Anilist.genres ?: listOf()) { chip ->
            val genre = chip.text.toString()
            chip.isChecked = selectedGenres.contains(genre)
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedGenres.add(genre)
                else selectedGenres.remove(genre)
            }
        }
        binding.listFilterGenresGrid.setOnCheckedChangeListener { _, isChecked ->
            binding.listFilterGenres.layoutManager =
                if (!isChecked) LinearLayoutManager(binding.root.context, HORIZONTAL, false)
                else GridLayoutManager(binding.root.context, 2, VERTICAL, false)
        }
        binding.listFilterGenresGrid.isChecked = false

        // Tags - controlled by adult tags switch
        fun updateTagsList(includeAdult: Boolean) {
            val tagsList = if (includeAdult && Anilist.adult) {
                val adultTags = Anilist.tags?.get(true) ?: listOf()
                val nonAdultTags = Anilist.tags?.get(false) ?: listOf()
                (adultTags + nonAdultTags).distinct().sorted()
            } else {
                Anilist.tags?.get(false) ?: listOf()
            }
            val filteredTags = if (tagSearchQuery.isBlank()) {
                tagsList
            } else {
                val query = tagSearchQuery.trim().lowercase()
                tagsList.filter { it.lowercase().contains(query) }
            }

            binding.listFilterTags.adapter = FilterChipAdapter(filteredTags) { chip ->
                val tag = chip.text.toString()
                chip.isChecked = selectedTags.contains(tag)
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedTags.add(tag)
                    else selectedTags.remove(tag)
                }
            }
            binding.listFilterSearchTagsAnilist.setImageResource(
                if (binding.listFilterTagsQuickSearchLayout.visibility == View.VISIBLE) {
                    R.drawable.ic_round_search_off_24
                } else {
                    R.drawable.ic_round_search_24
                }
            )
        }

        fun setTagSearchMode(enabled: Boolean) {
            binding.listFilterTagsQuickSearchLayout.visibility = if (enabled) View.VISIBLE else GONE
            if (Anilist.adult) {
                binding.listFilterTagsAdultContainer.visibility = if (enabled) GONE else View.VISIBLE
            }
            binding.listFilterSearchTagsAnilist.setImageResource(
                if (enabled) R.drawable.ic_round_search_off_24 else R.drawable.ic_round_search_24
            )

            if (enabled) {
                binding.listFilterTagsQuickSearchText.requestFocus()
                val imm = requireContext().getSystemService(InputMethodManager::class.java)
                imm?.showSoftInput(binding.listFilterTagsQuickSearchText, InputMethodManager.SHOW_IMPLICIT)
            } else {
                tagSearchQuery = ""
                binding.listFilterTagsQuickSearchText.setText("")
                val imm = requireContext().getSystemService(InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(binding.listFilterTagsQuickSearchText.windowToken, 0)
                updateTagsList(binding.listFilterTagsAdult.isChecked)
            }
        }

        // Initialize with non-adult tags
        updateTagsList(false)

        // Set up adult tags switch
        binding.listFilterTagsAdult.isChecked = false
        // Hide adult switch if user doesn't have adult content enabled in settings
        if (!Anilist.adult) {
            binding.listFilterTagsAdult.visibility = GONE
            binding.listFilterTagsAdultContainer.visibility = GONE
        }

        binding.listFilterTagsAdult.setOnCheckedChangeListener { _, isChecked ->
            updateTagsList(isChecked)
        }

        binding.listFilterTagsGrid.setOnCheckedChangeListener { _, isChecked ->
            binding.listFilterTags.layoutManager =
                if (!isChecked) LinearLayoutManager(binding.root.context, HORIZONTAL, false)
                else createWrapChipLayoutManager()
        }
        binding.listFilterTagsGrid.isChecked = false
        binding.listFilterTagsQuickSearchText.addTextChangedListener {
            tagSearchQuery = it?.toString().orEmpty()
            updateTagsList(binding.listFilterTagsAdult.isChecked)
        }
        setTagSearchMode(false)

        binding.listFilterSearchTagsAnilist.setOnClickListener {
            val searchActive = binding.listFilterTagsQuickSearchLayout.visibility == View.VISIBLE
            setTagSearchMode(!searchActive)
        }
    }

    private fun setupButtons() {
        binding.resetListFilter.setOnClickListener {
            val rotateAnimation = ObjectAnimator.ofFloat(binding.resetListFilter, "rotation", 180f, 540f)
            rotateAnimation.duration = 500
            rotateAnimation.interpolator = AccelerateDecelerateInterpolator()
            rotateAnimation.start()

            resetFilters()
        }

        binding.listFilterCancel.setOnClickListener {
            dismiss()
        }

        binding.listFilterApply.setOnClickListener {
            val filters = ListFilters(
                genres = selectedGenres,
                tags = selectedTags,
                formats = listOfNotNull(binding.listFilterFormat.text.toString().takeIf { it.isNotBlank() }),
                statuses = listOfNotNull(binding.listFilterStatus.text.toString().takeIf { it.isNotBlank() }),
                sources = listOfNotNull(binding.listFilterSource.text.toString().takeIf { it.isNotBlank() }),
                season = binding.listFilterSeason.text.toString().takeIf { it.isNotBlank() },
                year = null, // Removed - using yearRange instead
                countryOfOrigin = selectedCountry,
                scoreRange = Pair(
                    binding.listFilterScoreRange.values[0],
                    binding.listFilterScoreRange.values[1]
                ),
                yearRange = Pair(
                    binding.listFilterYearRange.values[0].toInt(),
                    binding.listFilterYearRange.values[1].toInt()
                ),
                englishLicenced = englishLicenced,
                muFormat = binding.listMuFilterFormat.text.toString().ifBlank { selectedMuFormat },
                muYear = binding.listMuFilterYear.text.toString().toIntOrNull() ?: selectedMuYear,
                muLicensed = when (binding.listMuFilterLicensed.text.toString()) {
                    "Licensed" -> "yes"
                    "Not Licensed" -> "no"
                    else -> selectedMuLicensed
                },
                muGenres = selectedMuGenres.toList(),
                muExcludedGenres = selectedMuExcludedGenres.toList(),
                muCategories = selectedMuCategories.toList(),
                muStatusFilters = selectedMuStatusFilters.toList(),
            )
            onApply(filters)
            dismiss()
        }
    }

    private fun setupEnglishLicencedFilter() {
        if (isAnime) return
        binding.listFilterEnglishLicencedCont.visibility = View.VISIBLE
        binding.listFilterEnglishLicenced.isChecked = englishLicenced
        binding.listFilterEnglishLicenced.setOnCheckedChangeListener { _, isChecked ->
            englishLicenced = isChecked
        }
    }

    private fun setupCountryFilter() {
        updateCountryIcon()

        binding.countryFilter.setOnClickListener {
            val popupMenu = PopupMenu(requireContext(), it)
            popupMenu.menuInflater.inflate(R.menu.country_filter_menu, popupMenu.menu)
            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.country_global -> {
                        selectedCountry = null
                        updateCountryIcon()
                    }
                    R.id.country_china -> {
                        selectedCountry = "CN"
                        updateCountryIcon()
                    }
                    R.id.country_south_korea -> {
                        selectedCountry = "KR"
                        updateCountryIcon()
                    }
                    R.id.country_japan -> {
                        selectedCountry = "JP"
                        updateCountryIcon()
                    }
                    R.id.country_taiwan -> {
                        selectedCountry = "TW"
                        updateCountryIcon()
                    }
                }
                true
            }
            popupMenu.show()
        }
    }

    private fun updateCountryIcon() {
        if (selectedCountry == null) {
            val drawable = ContextCompat.getDrawable(
                requireContext(), R.drawable.ic_round_globe_search_googlefonts
            )!!.mutate()
            DrawableCompat.setTint(
                DrawableCompat.wrap(drawable),
                requireContext().getResourceColor(com.google.android.material.R.attr.colorPrimary)
            )
            val size = (35 * resources.displayMetrics.density).toInt()
            drawable.setBounds(0, 0, size, size)
            binding.countryFilter.text = ""
            binding.countryFilter.setCompoundDrawablesRelative(drawable, null, null, null)
        } else {
            binding.countryFilter.setCompoundDrawablesRelativeWithIntrinsicBounds(
                null, null, null, null
            )
            binding.countryFilter.text = when (selectedCountry) {
                "CN" -> "\uD83C\uDDE8\uD83C\uDDF3"
                "KR" -> "\uD83C\uDDF0\uD83C\uDDF7"
                "JP" -> "\uD83C\uDDEF\uD83C\uDDF5"
                "TW" -> "\uD83C\uDDF9\uD83C\uDDFC"
                else -> "\uD83C\uDF10"
            }
        }

        val bounceAnimation = AnimationUtils.loadAnimation(requireContext(), R.anim.bounce_zoom)
        binding.countryFilter.startAnimation(bounceAnimation)
    }

    private fun resetFilters() {
        selectedGenres.clear()
        selectedTags.clear()
        selectedFormats.clear()
        selectedStatuses.clear()
        selectedSources.clear()
        selectedSeason = null
        selectedYear = null
        selectedCountry = null
        scoreRange = Pair(0.0f, 10.0f)
        yearRange = Pair(1970, 2028)
        englishLicenced = false
        binding.listFilterEnglishLicenced.isChecked = false
        selectedMuFormat = null
        selectedMuYear = null
        selectedMuLicensed = null
        selectedMuGenres.clear()
        selectedMuExcludedGenres.clear()
        selectedMuCategories.clear()
        selectedMuStatusFilters.clear()
        tagSearchQuery = ""
        muCategorySearchJob?.cancel()
        binding.listMuFilterFormat.setText("", false)
        binding.listMuFilterYear.setText("", false)
        binding.listMuFilterLicensed.setText("", false)
        binding.listMuFilterCategorySearch.setText("")
        binding.listMuFilterCategoryProgress.visibility = View.GONE
        binding.listMuFilterCategoryResultsRecycler.adapter = FilterChipAdapter(emptyList()) { }
        @Suppress("NotifyDataSetChanged")
        binding.listMuFilterGenresRecycler.adapter?.notifyDataSetChanged()
        @Suppress("NotifyDataSetChanged")
        binding.listMuFilterStatusRecycler.adapter?.notifyDataSetChanged()

        binding.listFilterSource.setText("")
        binding.listFilterFormat.setText("")
        binding.listFilterStatus.setText("")
        binding.listFilterSeason.setText("")
        binding.listFilterScoreRange.values = listOf(0.0f, 10.0f)
        binding.listFilterYearRange.values = listOf(1970f, 2028f)

        binding.listFilterGenres.adapter?.notifyDataSetChanged()
        binding.listFilterTags.adapter?.notifyDataSetChanged()

        binding.listFilterTagsQuickSearchLayout.visibility = GONE
        binding.listFilterTagsQuickSearchText.setText("")
        if (Anilist.adult) {
            binding.listFilterTagsAdultContainer.visibility = View.VISIBLE
        }
        binding.listFilterSearchTagsAnilist.setImageResource(R.drawable.ic_round_search_24)

        // Rebuild tags list so any active quick text filter is cleared from the visible list.
        binding.listFilterTagsAdult.isChecked = false

        updateCountryIcon()
    }

    private fun sortCategoriesByRelevance(categories: List<String>, query: String): List<String> {
        val normalizedQuery = query.lowercase().trim()
        return categories.sortedWith(compareBy { category ->
            val normalizedCategory = category.lowercase()
            when {
                // Exact match (highest priority)
                normalizedCategory == normalizedQuery -> 0
                // Starts with query (high priority)
                normalizedCategory.startsWith(normalizedQuery) -> 1
                // Contains query as a whole word at word boundary (medium priority)
                " $normalizedQuery" in " $normalizedCategory" || normalizedCategory.contains(" $normalizedQuery") -> 2
                // Just contains query (lower priority)
                normalizedQuery in normalizedCategory -> 3
                // No match (shouldn't happen as API filters already)
                else -> 4
            }
        }).filter { it.isNotEmpty() }
    }

    override fun onDestroyView() {
        muCategorySearchJob?.cancel()
        super.onDestroyView()
        _binding = null
    }

    class FilterChipAdapter(
        private val list: List<String>,
        private val perform: ((Chip) -> Unit)
    ) : RecyclerView.Adapter<FilterChipAdapter.SearchChipViewHolder>() {

        inner class SearchChipViewHolder(val binding: ItemChipBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchChipViewHolder {
            val binding = ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return SearchChipViewHolder(binding)
        }

        override fun onBindViewHolder(holder: SearchChipViewHolder, position: Int) {
            val title = list[position]
            holder.setIsRecyclable(false)
            holder.binding.root.apply {
                text = title
                isCheckable = true
                perform.invoke(this)
            }
        }

        override fun getItemCount(): Int = list.size
    }
}

data class ListFilters(
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val formats: List<String> = emptyList(),
    val statuses: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val season: String? = null,
    val year: Int? = null,
    val countryOfOrigin: String? = null,
    val scoreRange: Pair<Float, Float> = Pair(0.0f, 10.0f),
    val yearRange: Pair<Int, Int> = Pair(1970, 2028),
    val englishLicenced: Boolean = false,
    val muFormat: String? = null,
    val muYear: Int? = null,
    val muLicensed: String? = null,
    val muGenres: List<String> = emptyList(),
    val muExcludedGenres: List<String> = emptyList(),
    val muCategories: List<String> = emptyList(),
    val muStatusFilters: List<String> = emptyList(),
) {
    fun isEmpty(): Boolean {
        return genres.isEmpty() && tags.isEmpty() && formats.isEmpty() &&
                statuses.isEmpty() && sources.isEmpty() && season == null &&
                year == null && countryOfOrigin == null &&
                scoreRange == Pair(0.0f, 10.0f) && yearRange == Pair(1970, 2028) &&
            !englishLicenced &&
                muFormat == null && muYear == null && muLicensed == null &&
                muGenres.isEmpty() && muExcludedGenres.isEmpty() &&
                muCategories.isEmpty() && muStatusFilters.isEmpty()
    }

    /** True when any filter that cannot be applied to MU entries is active. */
    fun hasAnilistOnlyFilters(): Boolean {
        return genres.isNotEmpty() || tags.isNotEmpty() || formats.isNotEmpty() ||
                statuses.isNotEmpty() || sources.isNotEmpty() || season != null ||
                year != null || countryOfOrigin != null || englishLicenced ||
                scoreRange != Pair(0.0f, 10.0f) || yearRange != Pair(1970, 2028)
    }

    fun hasMuFilters(): Boolean {
        return muFormat != null || muYear != null || muLicensed != null ||
            muGenres.isNotEmpty() || muExcludedGenres.isNotEmpty() ||
                muCategories.isNotEmpty() || muStatusFilters.isNotEmpty()
    }

    // Convert display score (0.0-10.0) to internal score (0-100)
    fun getInternalScoreRange(): Pair<Int, Int> {
        return Pair(
            (scoreRange.first * 10).toInt(),
            (scoreRange.second * 10).toInt()
        )
    }
}

