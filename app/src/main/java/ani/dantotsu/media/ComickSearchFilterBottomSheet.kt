package ani.dantotsu.media

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MenuItem
import android.widget.PopupMenu
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.databinding.BottomSheetComickSearchFilterBinding
import ani.dantotsu.databinding.ItemChipBinding
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.google.android.material.chip.Chip
import eu.kanade.tachiyomi.util.system.getResourceColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class ComickSearchFilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetComickSearchFilterBinding? = null
    private val binding get() = _binding!!

    private lateinit var activity: SearchActivity

    private var selectedGenres = mutableListOf<String>()
    private var excludedGenres = mutableListOf<String>()
    private var selectedCategories = mutableListOf<String>()
    private var excludedCategories = mutableListOf<String>()

    private var allGenres: List<ComickApi.FilterOption> = emptyList()
    private var allCategories: List<ComickApi.FilterOption> = emptyList()
    private var filteredGenres: List<ComickApi.FilterOption> = emptyList()

    private var selectedSort: String? = null
    private var selectedStatus: Int? = null
    private var selectedDemographic = mutableListOf<Int>()
    private var selectedCountry = mutableListOf<String>()
    private var selectedContentRating: String? = null
    private var selectedCompleted: Boolean? = null
    private var selectedTime: Int? = null

    private var selectedMinimum: Int? = null
    private var selectedMinimumRating: Double? = null
    private var selectedFromYear: Int? = null
    private var selectedToYear: Int? = null

    private val yearRangeMin = 1900
    private val yearRangeMax = Calendar.getInstance().get(Calendar.YEAR) + 1

    enum class FilterState {
        NONE, INCLUDED, EXCLUDED
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetComickSearchFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as SearchActivity

        val r = activity.comickSearchResult
        selectedGenres = r.genres?.toMutableList() ?: mutableListOf()
        excludedGenres = r.excludedGenres?.toMutableList() ?: mutableListOf()
        selectedCategories = r.categories?.toMutableList() ?: mutableListOf()
        excludedCategories = r.excludedCategories?.toMutableList() ?: mutableListOf()
        selectedSort = r.sort ?: "created_at"
        selectedStatus = r.status
        selectedDemographic = r.demographic?.toMutableList() ?: mutableListOf()
        selectedCountry = r.country?.toMutableList() ?: mutableListOf()
        selectedContentRating = r.contentRating?.firstOrNull()
        selectedCompleted = r.completed
        selectedTime = r.time
        selectedMinimum = r.minimum
        selectedMinimumRating = r.minimumRating
        selectedFromYear = r.fromYear
        selectedToYear = r.toYear

        setupCategoriesLayout()
        setupAdvancedFilters()
        loadFilterOptions()

        binding.comickFilterReset.setOnClickListener { resetAll() }
        binding.comickFilterCancel.setOnClickListener { dismiss() }
        binding.comickFilterApply.setOnClickListener {
            applyFilters()
            dismiss()
        }

        binding.comickFilterGenresGrid.setOnCheckedChangeListener { _, isChecked ->
            binding.comickFilterGenresRecycler.layoutManager =
                if (!isChecked) LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                else GridLayoutManager(requireContext(), 2, GridLayoutManager.VERTICAL, false)
        }
        binding.comickFilterGenresGrid.isChecked = false

        binding.comickFilterGenresSearch.setOnClickListener {
            val isVisible = binding.comickFilterGenreSearchLayout.visibility == View.VISIBLE
            setGenreSearchMode(!isVisible)
        }

        binding.comickFilterGenreSearchText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                if (q.isBlank()) {
                    updateGenreResults(allGenres)
                } else {
                    updateGenreResults(
                        allGenres.filter { it.name.contains(q, ignoreCase = true) }
                    )
                }
            }
        })

        binding.comickFilterCategorySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                if (q.isBlank()) {
                    updateCategoryResults(selectedCategoryOptions())
                } else {
                    updateCategoryResults(
                        allCategories.filter { it.name.contains(q, ignoreCase = true) }
                    )
                }
            }
        })

        updateGenreSearchIcon(binding.comickFilterGenreSearchLayout.visibility == View.VISIBLE)
    }

    private fun updateGenreSearchIcon(isSearchMode: Boolean) {
        binding.comickFilterGenresSearch.setImageResource(
            if (isSearchMode) R.drawable.ic_round_search_off_24
            else R.drawable.ic_round_search_24
        )
    }

    private fun setGenreSearchMode(enabled: Boolean) {
        binding.comickFilterGenreSearchLayout.visibility = if (enabled) View.VISIBLE else View.GONE
        updateGenreSearchIcon(enabled)
        if (!enabled) {
            binding.comickFilterGenreSearchText.setText("")
        }
    }

    private fun setupCategoriesLayout() {
        binding.comickFilterCategoryResultsRecycler.layoutManager =
            FlexboxLayoutManager(requireContext()).apply {
                flexDirection = FlexDirection.ROW
                flexWrap = FlexWrap.WRAP
                justifyContent = JustifyContent.FLEX_START
            }
    }

    private fun setupAdvancedFilters() {
        val sortOptions = listOf(
            DropdownOption("Latest", "created_at"),
            DropdownOption("Last Updated", "uploaded"),
            DropdownOption(labelForSlug("rating"), "rating"),
            DropdownOption(labelForSlug("average_rating"), "average_rating"),
            DropdownOption("Popular", "user_follow_count"),
        )

        val statusOptions = listOf(
            DropdownOption(getString(R.string.comick_all), null),
            DropdownOption(labelForSlug("ongoing"), 0),
            DropdownOption(labelForSlug("completed"), 1),
            DropdownOption(labelForSlug("hiatus"), 2),
            DropdownOption(labelForSlug("cancelled"), 3),
        )

        val demographicOptions = listOf(
            DropdownOption(getString(R.string.shounen), 1),
            DropdownOption(getString(R.string.shoujo), 2),
            DropdownOption(getString(R.string.seinen), 3),
            DropdownOption(getString(R.string.josei), 4),
        )

        val typeOptions = listOf(
            DropdownOption(getString(R.string.comick_type_jp), "jp"),
            DropdownOption(getString(R.string.comick_type_kr), "kr"),
            DropdownOption(getString(R.string.comick_type_cn), "cn"),
            DropdownOption(getString(R.string.comick_type_others), "others"),
        )

        val contentRatingOptions = listOf(
            DropdownOption(getString(R.string.comick_all), null),
            DropdownOption(labelForSlug("safe"), "safe"),
            DropdownOption(labelForSlug("suggestive"), "suggestive"),
            DropdownOption(labelForSlug("erotica"), "erotica"),
            DropdownOption(labelForSlug("pornographic"), "pornographic"),
        )

        val completedOptions = listOf(
            DropdownOption(getString(R.string.comick_all), null),
            DropdownOption(getString(R.string.comick_completed_only), true),
            DropdownOption(getString(R.string.comick_not_completed_only), false),
        )

        val timeOptions = listOf(
            DropdownOption(getString(R.string.comick_all), null),
            DropdownOption("${getString(R.string.comick_time_3)}", 3),
            DropdownOption("${getString(R.string.comick_time_7)}", 7),
            DropdownOption("${getString(R.string.comick_time_30)}", 30),
            DropdownOption("${getString(R.string.comick_time_90)}", 90),
            DropdownOption("${getString(R.string.comick_time_180)}", 180),
            DropdownOption("${getString(R.string.comick_time_365)}", 365),
            DropdownOption("${getString(R.string.comick_time_730)}", 730),
            DropdownOption("${getString(R.string.comick_time_1095)}", 1095),
            DropdownOption("${getString(R.string.comick_time_1460)}", 1460),
            DropdownOption("${getString(R.string.comick_time_1825)}", 1825),
            DropdownOption("${getString(R.string.comick_time_2190)}", 2190),
        )

        setupSortButton(sortOptions)

        // Bind dropdowns
        bindDropdown(binding.comickFilterStatus, statusOptions, selectedStatus) { selectedStatus = it as? Int }
        bindDropdown(binding.comickFilterContentRating, contentRatingOptions, selectedContentRating) { selectedContentRating = it as? String }
        bindDropdown(binding.comickFilterCompleted, completedOptions, selectedCompleted) { selectedCompleted = it as? Boolean }
        bindDropdown(binding.comickFilterTime, timeOptions, selectedTime) { selectedTime = it as? Int }

        // Multi-select for demographic
        setupMultiSelectChips(binding.comickFilterDemographicRecycler, demographicOptions, selectedDemographic) { value ->
            val intValue = value.value as? Int ?: return@setupMultiSelectChips
            if (selectedDemographic.contains(intValue)) {
                selectedDemographic.remove(intValue)
            } else {
                selectedDemographic.add(intValue)
            }
        }

        // Multi-select for type/country
        setupMultiSelectChips(binding.comickFilterCountryRecycler, typeOptions, selectedCountry) { value ->
            val strValue = value.value as? String ?: return@setupMultiSelectChips
            if (selectedCountry.contains(strValue)) {
                selectedCountry.remove(strValue)
            } else {
                selectedCountry.add(strValue)
            }
        }

        // Numeric fields
        binding.comickFilterMinimum.setText(selectedMinimum?.toString().orEmpty())
        binding.comickFilterMinimumRating.setText(selectedMinimumRating?.toString().orEmpty())

        // Show all switch
        binding.comickFilterShowAll.isChecked = (activity.comickSearchResult.showAll == true)

        // Year range slider
        binding.comickFilterYearRange.valueFrom = yearRangeMin.toFloat()
        binding.comickFilterYearRange.valueTo = yearRangeMax.toFloat()
        binding.comickFilterYearRange.stepSize = 1f

        val start = (selectedFromYear ?: yearRangeMin).coerceIn(yearRangeMin, yearRangeMax)
        val end = (selectedToYear ?: yearRangeMax).coerceIn(yearRangeMin, yearRangeMax)
        val sortedStart = minOf(start, end)
        val sortedEnd = maxOf(start, end)
        binding.comickFilterYearRange.values = listOf(sortedStart.toFloat(), sortedEnd.toFloat())
        updateYearRangeLabel(sortedStart, sortedEnd)

        binding.comickFilterYearRange.addOnChangeListener { slider, _, _ ->
            val values = slider.values
            updateYearRangeLabel(values[0].toInt(), values[1].toInt())
        }
    }

    private fun setupSortButton(options: List<DropdownOption<*>>) {
        val selectedLabel = options.firstOrNull { it.value == selectedSort }?.label
            ?: getString(R.string.sort_by)
        binding.comickFilterSortButton.contentDescription = selectedLabel
        updateSortButtonIcon(selectedSort)

        binding.comickFilterSortButton.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            val typedValue = TypedValue()
            requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
            val primaryColor = typedValue.data

            options.forEachIndexed { index, option ->
                val title = if (option.value == selectedSort) {
                    SpannableString(option.label).apply {
                        setSpan(
                            ForegroundColorSpan(primaryColor),
                            0,
                            length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                } else {
                    option.label
                }
                popup.menu.add(0, index, index, title)
            }

            popup.setOnMenuItemClickListener { item: MenuItem ->
                val selectedOption = options.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
                selectedSort = selectedOption.value as? String
                binding.comickFilterSortButton.contentDescription = selectedOption.label
                updateSortButtonIcon(selectedSort)
                true
            }
            popup.show()
        }
    }

    private fun updateSortButtonIcon(sortValue: String?) {
        val iconRes = when (sortValue) {
            "created_at" -> R.drawable.ic_round_calendar_today_24
            "uploaded" -> R.drawable.ic_round_new_releases_24
            "rating" -> R.drawable.ic_round_star_24
            "average_rating" -> R.drawable.ic_round_star_graph_24
            "user_follow_count" -> R.drawable.ic_round_group_24
            else -> R.drawable.ic_round_sort_24
        }
        binding.comickFilterSortButton.setImageResource(iconRes)
    }

    private fun bindDropdown(
        view: AutoCompleteTextView,
        options: List<DropdownOption<*>>,
        selectedValue: Any?,
        onSelected: (Any?) -> Unit,
    ) {
        val labels = options.map { it.label }
        view.setAdapter(ArrayAdapter(requireContext(), R.layout.item_dropdown, labels))
        val initial = options.firstOrNull { it.value == selectedValue } ?: options.first()
        view.setText(initial.label, false)
        onSelected(initial.value)
        view.setOnItemClickListener { _, _, position, _ ->
            onSelected(options[position].value)
        }
    }

    private fun setupMultiSelectChips(
        recyclerView: RecyclerView,
        options: List<DropdownOption<*>>,
        selectedValues: Collection<*>,
        onSelectionChanged: (DropdownOption<*>) -> Unit,
    ) {
        recyclerView.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        recyclerView.setHasFixedSize(false)

        recyclerView.adapter = object : RecyclerView.Adapter<ChipViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
                val binding = ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                return ChipViewHolder(binding.root as Chip)
            }

            override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
                val option = options[position]
                holder.chip.apply {
                    text = option.label
                    isCheckable = true
                    setOnCheckedChangeListener(null)
                    isChecked = selectedValues.contains(option.value)
                    setOnCheckedChangeListener { _, _ ->
                        onSelectionChanged(option)
                    }
                }
            }

            override fun getItemCount() = options.size
        }
    }

    private class ChipViewHolder(val chip: Chip) : RecyclerView.ViewHolder(chip)

    private fun labelForSlug(value: String): String {
        return value
            .replace('-', '_')
            .split('_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.lowercase(Locale.getDefault()).replaceFirstChar { c ->
                    if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString()
                }
            }
    }

    private fun updateYearRangeLabel(from: Int, to: Int) {
        binding.comickFilterYearRangeValue.text = getString(R.string.year_range)
    }

    private fun loadFilterOptions() {
        CoroutineScope(Dispatchers.IO).launch {
            val genres = ComickApi.getGenres()
            val categories = ComickApi.getCategories(useCache = true)

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                allGenres = genres.sortedBy { it.name.lowercase() }
                allCategories = categories.sortedBy { it.name.lowercase() }

                updateGenreResults(allGenres)
                updateCategoryResults(selectedCategoryOptions())
            }
        }
    }

    private fun selectedCategoryOptions(): List<ComickApi.FilterOption> {
        val selectedSlugs = (selectedCategories + excludedCategories).toSet()
        if (selectedSlugs.isEmpty()) return emptyList()
        return allCategories.filter { it.slug in selectedSlugs }
    }

    private fun updateCategoryResults(options: List<ComickApi.FilterOption>) {
        if (_binding == null) return
        binding.comickFilterCategoryResultsRecycler.adapter =
            GenreCategoryChipAdapter(options, selectedCategories, excludedCategories) { slug, state ->
                when (state) {
                    FilterState.INCLUDED -> {
                        if (!selectedCategories.contains(slug)) selectedCategories.add(slug)
                        excludedCategories.remove(slug)
                    }
                    FilterState.EXCLUDED -> {
                        selectedCategories.remove(slug)
                        if (!excludedCategories.contains(slug)) excludedCategories.add(slug)
                    }
                    FilterState.NONE -> {
                        selectedCategories.remove(slug)
                        excludedCategories.remove(slug)
                    }
                }
            }
    }

    private fun updateGenreResults(options: List<ComickApi.FilterOption>) {
        if (_binding == null) return
        filteredGenres = options
        binding.comickFilterGenresRecycler.adapter =
            GenreCategoryChipAdapter(options, selectedGenres, excludedGenres) { slug, state ->
                when (state) {
                    FilterState.INCLUDED -> {
                        if (!selectedGenres.contains(slug)) selectedGenres.add(slug)
                        excludedGenres.remove(slug)
                    }
                    FilterState.EXCLUDED -> {
                        selectedGenres.remove(slug)
                        if (!excludedGenres.contains(slug)) excludedGenres.add(slug)
                    }
                    FilterState.NONE -> {
                        selectedGenres.remove(slug)
                        excludedGenres.remove(slug)
                    }
                }
            }
    }

    private fun applyFilters() {
        val r = activity.comickSearchResult
        r.genres = selectedGenres.toMutableList().ifEmpty { null }
        r.excludedGenres = excludedGenres.toMutableList().ifEmpty { null }
        r.categories = selectedCategories.toMutableList().ifEmpty { null }
        r.excludedCategories = excludedCategories.toMutableList().ifEmpty { null }
        r.sort = selectedSort ?: "created_at"
        r.status = selectedStatus
        r.demographic = selectedDemographic.toMutableList().ifEmpty { null }
        r.country = selectedCountry.toMutableList().ifEmpty { null }
        r.contentRating = selectedContentRating?.let { mutableListOf(it) }
        r.completed = selectedCompleted
        r.time = selectedTime

        val minStr = binding.comickFilterMinimum.text?.toString()?.trim()
        r.minimum = if (minStr.isNullOrBlank()) null else minStr.toIntOrNull()

        val minRatingStr = binding.comickFilterMinimumRating.text?.toString()?.trim()
        r.minimumRating = if (minRatingStr.isNullOrBlank()) null else minRatingStr.toDoubleOrNull()

        r.showAll = if (binding.comickFilterShowAll.isChecked) true else null

        val values = binding.comickFilterYearRange.values
        val currentYearMax = yearRangeMax
        val isDefaultRange = values[0].toInt() == yearRangeMin && values[1].toInt() == currentYearMax
        if (isDefaultRange) {
            r.fromYear = null
            r.toYear = null
        } else {
            r.fromYear = values[0].toInt()
            r.toYear = values[1].toInt()
        }

        activity.search()
    }

    private fun resetAll() {
        selectedGenres.clear()
        excludedGenres.clear()
        selectedCategories.clear()
        excludedCategories.clear()
        selectedSort = "created_at"
        selectedStatus = null
        selectedDemographic.clear()
        selectedCountry.clear()
        selectedContentRating = null
        selectedCompleted = null
        selectedTime = null
        selectedMinimum = null
        selectedMinimumRating = null

        binding.comickFilterMinimum.setText("")
        binding.comickFilterMinimumRating.setText("")
        binding.comickFilterShowAll.isChecked = false
        binding.comickFilterGenreSearchText.setText("")
        binding.comickFilterCategorySearch.setText("")

        updateGenreResults(allGenres)
        updateCategoryResults(selectedCategoryOptions())

        binding.comickFilterYearRange.values = listOf(yearRangeMin.toFloat(), yearRangeMax.toFloat())
        updateYearRangeLabel(yearRangeMin, yearRangeMax)

        setupAdvancedFilters()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = ComickSearchFilterBottomSheet()
    }
}

data class DropdownOption<T>(val label: String, val value: T?)

private fun Chip.setFilterStyle(
    baseText: String,
    included: Boolean,
    excluded: Boolean,
    defaultBackground: ColorStateList?,
    defaultTextColor: ColorStateList?,
) {
    text = baseText
    when {
        excluded -> {
            chipBackgroundColor = ColorStateList.valueOf(
                context.getResourceColor(com.google.android.material.R.attr.colorErrorContainer)
            )
            setTextColor(context.getResourceColor(com.google.android.material.R.attr.colorOnErrorContainer))
        }

        included -> {
            chipBackgroundColor = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.filter_chip_include_bg)
            )
            setTextColor(ContextCompat.getColor(context, R.color.filter_chip_include_text))
        }

        else -> {
            chipBackgroundColor = defaultBackground
                ?: ColorStateList.valueOf(
                    context.getResourceColor(com.google.android.material.R.attr.colorSurfaceVariant)
                )
            setTextColor(
                defaultTextColor
                    ?: ColorStateList.valueOf(
                        context.getResourceColor(com.google.android.material.R.attr.colorOnSurface)
                    )
            )
        }
    }
    isCloseIconVisible = false
}

class GenreCategoryChipAdapter(
    private val options: List<ComickApi.FilterOption>,
    private val selectedItems: List<String>,
    private val excludedItems: List<String>,
    private val onStateChanged: (String, ComickSearchFilterBottomSheet.FilterState) -> Unit,
) : RecyclerView.Adapter<GenreCategoryChipAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemChipBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(option: ComickApi.FilterOption) {
            val chip = binding.root as Chip
            val slug = option.slug
            val isIncluded = selectedItems.contains(slug)
            val isExcluded = excludedItems.contains(slug)

            chip.text = option.name
            chip.isCheckable = true
            val isChecked = isIncluded || isExcluded
            chip.setOnCheckedChangeListener(null)
            chip.isChecked = isChecked

            val defaultBackground = AppCompatResources.getColorStateList(chip.context, R.color.chip_background_color)
            val defaultTextColor = ColorStateList.valueOf(
                chip.context.getResourceColor(com.google.android.material.R.attr.colorOnSurface)
            )

            chip.setFilterStyle(
                option.name,
                isIncluded,
                isExcluded,
                defaultBackground,
                defaultTextColor
            )

            var internalChange = false
            chip.setOnCheckedChangeListener { _, isCheckedNow ->
                if (internalChange) return@setOnCheckedChangeListener
                if (isCheckedNow) {
                    chip.setFilterStyle(option.name, true, false, defaultBackground, defaultTextColor)
                    onStateChanged(slug, ComickSearchFilterBottomSheet.FilterState.INCLUDED)
                } else {
                    chip.setFilterStyle(option.name, false, false, defaultBackground, defaultTextColor)
                    onStateChanged(slug, ComickSearchFilterBottomSheet.FilterState.NONE)
                }
            }

            chip.setOnLongClickListener {
                internalChange = true
                if (excludedItems.contains(slug)) {
                    chip.isChecked = false
                    chip.setFilterStyle(option.name, false, false, defaultBackground, defaultTextColor)
                    onStateChanged(slug, ComickSearchFilterBottomSheet.FilterState.NONE)
                } else {
                    chip.isChecked = true
                    chip.setFilterStyle(option.name, false, true, defaultBackground, defaultTextColor)
                    onStateChanged(slug, ComickSearchFilterBottomSheet.FilterState.EXCLUDED)
                }
                internalChange = false
                chip.playSoundEffect(SoundEffectConstants.CLICK)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(options[position])
    }

    override fun getItemCount() = options.size
}

class FilterChipAdapter(
    private val options: List<String>,
    private val onChipSetup: (Chip) -> Unit,
) : RecyclerView.Adapter<FilterChipAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemChipBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(label: String) {
            val chip = binding.root as Chip
            chip.text = label
            onChipSetup(chip)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(options[position])
    }

    override fun getItemCount() = options.size
}
