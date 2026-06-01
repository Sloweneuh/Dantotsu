package ani.dantotsu.media

import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.PopupMenu
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.databinding.BottomSheetComickListFilterBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.media.savedfilters.SavedComickListFilter
import ani.dantotsu.media.savedfilters.SavedFilterEntry
import ani.dantotsu.media.savedfilters.SavedFiltersDialog
import ani.dantotsu.media.savedfilters.SavedFiltersStore
import com.google.android.material.chip.Chip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class ComickListFilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetComickListFilterBinding? = null
    private val binding get() = _binding!!

    private lateinit var activity: ComickListActivity

    private var selectedSort: String = "created_at"
    private var selectedStatus = mutableListOf<Int>()
    private var selectedCountry = mutableListOf<String>()
    private var selectedContentRating = mutableListOf<String>()
    private var selectedDemographic = mutableListOf<Int>()
    private var selectedCompleted: Boolean? = null
    private var selectedGenres = mutableListOf<String>()
    private var excludedGenres = mutableListOf<String>()
    private var selectedFromYear: Int? = null
    private var selectedToYear: Int? = null

    private var allGenres: List<ComickApi.FilterOption> = emptyList()

    private val yearRangeMin = 1900
    private val yearRangeMax = Calendar.getInstance().get(Calendar.YEAR) + 1


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetComickListFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as ComickListActivity

        val state = activity.filterState
        selectedSort = state.sort
        selectedStatus = state.selectedStatus.toMutableList()
        selectedCountry = state.selectedCountry.toMutableList()
        selectedContentRating = state.selectedContentRating.toMutableList()
        selectedDemographic = state.selectedDemographic.toMutableList()
        selectedCompleted = state.translationCompleted
        selectedGenres = state.selectedGenres.toMutableList()
        excludedGenres = state.excludedGenres.toMutableList()
        selectedFromYear = state.fromYear
        selectedToYear = state.toYear

        setupFilters()
        loadGenres()

        binding.comickListFilterReset.setOnClickListener { resetAll() }
        binding.comickListFilterCancel.setOnClickListener { dismiss() }
        binding.comickListFilterApply.setOnClickListener {
            applyFilters()
            dismiss()
        }
        binding.comickListFilterSavedFilters.setOnClickListener { showSavedFiltersDialog() }

        binding.comickListFilterGenresGrid.setOnCheckedChangeListener { _, isChecked ->
            binding.comickListFilterGenresRecycler.layoutManager =
                if (!isChecked) LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                else GridLayoutManager(requireContext(), 2, GridLayoutManager.VERTICAL, false)
        }
        binding.comickListFilterGenresGrid.isChecked = false

        binding.comickListFilterGenresSearch.setOnClickListener {
            val visible = binding.comickListFilterGenreSearchLayout.visibility == View.VISIBLE
            setGenreSearchMode(!visible)
        }

        binding.comickListFilterGenreSearchText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                updateGenreResults(if (q.isBlank()) allGenres else allGenres.filter { it.name.contains(q, ignoreCase = true) })
            }
        })

        updateGenreSearchIcon(false)
    }

    private fun updateGenreSearchIcon(isSearch: Boolean) {
        binding.comickListFilterGenresSearch.setImageResource(
            if (isSearch) R.drawable.ic_round_search_off_24 else R.drawable.ic_round_search_24
        )
    }

    private fun setGenreSearchMode(enabled: Boolean) {
        binding.comickListFilterGenreSearchLayout.visibility = if (enabled) View.VISIBLE else View.GONE
        updateGenreSearchIcon(enabled)
        if (!enabled) binding.comickListFilterGenreSearchText.setText("")
    }

    private fun setupFilters() {
        val sortOptions = listOf(
            DropdownOption("Title", "title"),
            DropdownOption("Rating", "bayesian_rating"),
            DropdownOption("Last Upload", "uploaded_at"),
            DropdownOption("Date Added", "created_at"),
        )

        val statusOptions = listOf(
            DropdownOption(getString(R.string.comick_status_ongoing), 1),
            DropdownOption(getString(R.string.comick_status_completed), 2),
            DropdownOption(getString(R.string.comick_status_cancelled), 3),
            DropdownOption(getString(R.string.comick_status_hiatus), 4),
        )

        val countryOptions = listOf(
            DropdownOption(getString(R.string.comick_type_jp), "jp"),
            DropdownOption(getString(R.string.comick_type_kr), "kr"),
            DropdownOption(getString(R.string.comick_type_cn), "cn"),
            DropdownOption(getString(R.string.comick_type_others), "others"),
        )

        val contentRatingOptions = listOf(
            DropdownOption(labelForSlug("safe"), "safe"),
            DropdownOption(labelForSlug("suggestive"), "suggestive"),
            DropdownOption(labelForSlug("erotica"), "erotica"),
        )

        val demographicOptions = listOf(
            DropdownOption(getString(R.string.shounen), 1),
            DropdownOption(getString(R.string.shoujo), 2),
            DropdownOption(getString(R.string.seinen), 3),
            DropdownOption(getString(R.string.josei), 4),
        )

        val completedOptions = listOf(
            DropdownOption(getString(R.string.comick_all), null),
            DropdownOption(getString(R.string.comick_completed_only), true),
            DropdownOption(getString(R.string.comick_not_completed_only), false),
        )

        setupSortButton(sortOptions)

        setupMultiSelectChips(binding.comickListFilterStatusRecycler, statusOptions, selectedStatus) { value ->
            val v = value.value as? Int ?: return@setupMultiSelectChips
            if (selectedStatus.contains(v)) selectedStatus.remove(v) else selectedStatus.add(v)
        }

        setupMultiSelectChips(binding.comickListFilterCountryRecycler, countryOptions, selectedCountry) { value ->
            val v = value.value as? String ?: return@setupMultiSelectChips
            if (selectedCountry.contains(v)) selectedCountry.remove(v) else selectedCountry.add(v)
        }

        setupMultiSelectChips(binding.comickListFilterContentRatingRecycler, contentRatingOptions, selectedContentRating) { value ->
            val v = value.value as? String ?: return@setupMultiSelectChips
            if (selectedContentRating.contains(v)) selectedContentRating.remove(v) else selectedContentRating.add(v)
        }

        setupMultiSelectChips(binding.comickListFilterDemographicRecycler, demographicOptions, selectedDemographic) { value ->
            val v = value.value as? Int ?: return@setupMultiSelectChips
            if (selectedDemographic.contains(v)) selectedDemographic.remove(v) else selectedDemographic.add(v)
        }

        bindDropdown(binding.comickListFilterCompleted, completedOptions, selectedCompleted) { selectedCompleted = it as? Boolean }

        // Year range
        binding.comickListFilterYearRange.valueFrom = yearRangeMin.toFloat()
        binding.comickListFilterYearRange.valueTo = yearRangeMax.toFloat()
        binding.comickListFilterYearRange.stepSize = 1f

        val start = (selectedFromYear ?: yearRangeMin).coerceIn(yearRangeMin, yearRangeMax)
        val end = (selectedToYear ?: yearRangeMax).coerceIn(yearRangeMin, yearRangeMax)
        binding.comickListFilterYearRange.values = listOf(minOf(start, end).toFloat(), maxOf(start, end).toFloat())
        updateYearLabel(minOf(start, end), maxOf(start, end))

        binding.comickListFilterYearRange.addOnChangeListener { slider, _, _ ->
            val v = slider.values
            updateYearLabel(v[0].toInt(), v[1].toInt())
        }
    }

    private fun setupSortButton(options: List<DropdownOption<*>>) {
        updateSortButtonIcon(selectedSort)

        binding.comickListFilterSortButton.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            val typedValue = TypedValue()
            requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
            val primaryColor = typedValue.data

            options.forEachIndexed { index, option ->
                val title = if (option.value == selectedSort) {
                    SpannableString(option.label).apply {
                        setSpan(ForegroundColorSpan(primaryColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else {
                    option.label
                }
                popup.menu.add(0, index, index, title)
            }

            popup.setOnMenuItemClickListener { item: MenuItem ->
                val opt = options.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
                selectedSort = opt.value as? String ?: selectedSort
                updateSortButtonIcon(selectedSort)
                true
            }
            popup.show()
        }
    }

    private fun updateSortButtonIcon(sort: String) {
        val iconRes = when (sort) {
            "title" -> R.drawable.ic_round_sort_24
            "bayesian_rating" -> R.drawable.ic_round_star_24
            "uploaded_at" -> R.drawable.ic_round_new_releases_24
            "created_at" -> R.drawable.ic_round_calendar_today_24
            else -> R.drawable.ic_round_sort_24
        }
        binding.comickListFilterSortButton.setImageResource(iconRes)
    }

    private fun bindDropdown(view: AutoCompleteTextView, options: List<DropdownOption<*>>, selectedValue: Any?, onSelected: (Any?) -> Unit) {
        val labels = options.map { it.label }
        view.setAdapter(ArrayAdapter(requireContext(), R.layout.item_dropdown, labels))
        val initial = options.firstOrNull { it.value == selectedValue } ?: options.first()
        view.setText(initial.label, false)
        onSelected(initial.value)
        view.setOnItemClickListener { _, _, position, _ -> onSelected(options[position].value) }
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
                val b = ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                return ChipViewHolder(b.root as Chip)
            }
            override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
                val option = options[position]
                holder.chip.apply {
                    text = option.label
                    isCheckable = true
                    setOnCheckedChangeListener(null)
                    isChecked = selectedValues.contains(option.value)
                    setOnCheckedChangeListener { _, _ -> onSelectionChanged(option) }
                }
            }
            override fun getItemCount() = options.size
        }
    }

    private fun loadGenres() {
        CoroutineScope(Dispatchers.IO).launch {
            val genres = ComickApi.getGenres()
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                allGenres = genres.sortedBy { it.name.lowercase() }
                updateGenreResults(allGenres)
            }
        }
    }

    private fun updateGenreResults(options: List<ComickApi.FilterOption>) {
        if (_binding == null) return
        binding.comickListFilterGenresRecycler.adapter =
            GenreCategoryChipAdapter(options, selectedGenres, excludedGenres) { slug, state ->
                when (state) {
                    ComickSearchFilterBottomSheet.FilterState.INCLUDED -> { if (!selectedGenres.contains(slug)) selectedGenres.add(slug); excludedGenres.remove(slug) }
                    ComickSearchFilterBottomSheet.FilterState.EXCLUDED -> { selectedGenres.remove(slug); if (!excludedGenres.contains(slug)) excludedGenres.add(slug) }
                    ComickSearchFilterBottomSheet.FilterState.NONE -> { selectedGenres.remove(slug); excludedGenres.remove(slug) }
                }
            }
    }

    private fun updateYearLabel(from: Int, to: Int) {
        binding.comickListFilterYearRangeValue.text = getString(R.string.comick_year_range, from.toString(), to.toString())
    }

    private fun applyFilters() {
        val state = activity.filterState
        state.sort = selectedSort
        state.selectedStatus = selectedStatus.toMutableList()
        state.selectedCountry = selectedCountry.toMutableList()
        state.selectedContentRating = selectedContentRating.toMutableList()
        state.selectedDemographic = selectedDemographic.toMutableList()
        state.translationCompleted = selectedCompleted
        state.selectedGenres = selectedGenres.toMutableList()
        state.excludedGenres = excludedGenres.toMutableList()

        val values = binding.comickListFilterYearRange.values
        val isDefault = values[0].toInt() == yearRangeMin && values[1].toInt() == yearRangeMax
        state.fromYear = if (isDefault) null else values[0].toInt()
        state.toYear = if (isDefault) null else values[1].toInt()

        activity.applyFilterAndDisplay()
    }

    private fun resetAll() {
        selectedSort = "created_at"
        selectedStatus.clear()
        selectedCountry.clear()
        selectedContentRating.clear()
        selectedDemographic.clear()
        selectedCompleted = null
        selectedGenres.clear()
        excludedGenres.clear()
        selectedFromYear = null
        selectedToYear = null

        binding.comickListFilterGenreSearchText.setText("")
        updateGenreResults(allGenres)
        binding.comickListFilterYearRange.values = listOf(yearRangeMin.toFloat(), yearRangeMax.toFloat())
        updateYearLabel(yearRangeMin, yearRangeMax)

        setupFilters()
    }

    private fun showSavedFiltersDialog() {
        SavedFiltersDialog.show(
            context = requireContext(),
            loadPresets = {
                SavedFiltersStore.loadComickList().map { SavedFilterEntry(it.name, it.chips()) }
            },
            onSaveCurrent = { name ->
                applyFilters()
                SavedFiltersStore.saveComickList(buildPreset(name))
            },
            onApply = { name ->
                val preset = SavedFiltersStore.loadComickList().firstOrNull { it.name == name } ?: return@show
                loadPreset(preset)
                applyFilters()
                dismiss()
            },
            onDelete = { name -> SavedFiltersStore.deleteComickList(name) },
            onRename = { oldName, newName -> SavedFiltersStore.renameComickList(oldName, newName) },
        )
    }

    private fun buildPreset(name: String): SavedComickListFilter {
        val values = binding.comickListFilterYearRange.values
        val isDefault = values[0].toInt() == yearRangeMin && values[1].toInt() == yearRangeMax
        return SavedComickListFilter(
            name = name,
            sort = selectedSort,
            status = selectedStatus.toList().ifEmpty { null },
            country = selectedCountry.toList().ifEmpty { null },
            demographic = selectedDemographic.toList().ifEmpty { null },
            contentRating = selectedContentRating.toList().ifEmpty { null },
            translationCompleted = selectedCompleted,
            genres = selectedGenres.toList().ifEmpty { null },
            excludedGenres = excludedGenres.toList().ifEmpty { null },
            fromYear = if (isDefault) null else values[0].toInt(),
            toYear = if (isDefault) null else values[1].toInt(),
        )
    }

    private fun loadPreset(preset: SavedComickListFilter) {
        selectedSort = preset.sort ?: "created_at"
        selectedStatus = preset.status?.toMutableList() ?: mutableListOf()
        selectedCountry = preset.country?.toMutableList() ?: mutableListOf()
        selectedContentRating = preset.contentRating?.toMutableList() ?: mutableListOf()
        selectedDemographic = preset.demographic?.toMutableList() ?: mutableListOf()
        selectedCompleted = preset.translationCompleted
        selectedGenres = preset.genres?.toMutableList() ?: mutableListOf()
        excludedGenres = preset.excludedGenres?.toMutableList() ?: mutableListOf()
        selectedFromYear = preset.fromYear
        selectedToYear = preset.toYear
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = ComickListFilterBottomSheet()
    }
}

private class ChipViewHolder(val chip: Chip) : RecyclerView.ViewHolder(chip)

private fun labelForSlug(value: String): String =
    value.replace('-', '_').split('_').filter { it.isNotBlank() }
        .joinToString(" ") { part ->
            part.lowercase(Locale.getDefault()).replaceFirstChar { c ->
                if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString()
            }
        }
