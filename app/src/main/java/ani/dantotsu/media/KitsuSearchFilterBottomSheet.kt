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
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.kitsu.KitsuApi
import ani.dantotsu.databinding.BottomSheetKitsuSearchFilterBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.dismissKeyboard
import ani.dantotsu.media.savedfilters.SavedFilterEntry
import ani.dantotsu.media.savedfilters.SavedFiltersDialog
import ani.dantotsu.media.savedfilters.SavedFiltersStore
import ani.dantotsu.media.savedfilters.SavedKitsuFilter
import com.google.android.material.chip.Chip
import eu.kanade.tachiyomi.util.system.getResourceColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Filter sheet for Kitsu search. Unlike the Comick/MangaBaka sheets, Kitsu's `filter[categories]`
 * is include-only (comma = AND), so every chip is a plain toggle — no long-press-to-exclude.
 */
class KitsuSearchFilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetKitsuSearchFilterBinding? = null
    private val binding get() = _binding!!

    private lateinit var activity: SearchActivity
    private var isAnime = false

    private var selectedCategories = mutableListOf<String>()
    private var selectedSubtypes = mutableListOf<String>()
    private var selectedStatuses = mutableListOf<String>()
    private var selectedAgeRatings = mutableListOf<String>()
    private var selectedSeason: String? = null
    private var selectedSort: String? = null

    private var allCategories: List<FilterOption> = emptyList()

    private val yearRangeMin = 1907
    private val yearRangeMax = Calendar.getInstance().get(Calendar.YEAR) + 1

    data class FilterOption(val value: String, val label: String)

    private val sortOptions = listOf(
        FilterOption("", "Relevance"),
        FilterOption("-userCount", "Popularity"),
        FilterOption("-averageRating", "Rating"),
        FilterOption("-startDate", "Newest"),
        FilterOption("titles.canonical", "Title"),
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetKitsuSearchFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as SearchActivity

        val r = activity.kitsuSearchResult
        isAnime = r.isAnime
        selectedCategories = r.categories?.toMutableList() ?: mutableListOf()
        selectedSubtypes = r.subtypes?.toMutableList() ?: mutableListOf()
        selectedStatuses = r.statuses?.toMutableList() ?: mutableListOf()
        selectedAgeRatings = r.ageRatings?.toMutableList() ?: mutableListOf()
        selectedSeason = r.season
        selectedSort = r.sort

        setupEnumFilters()
        setupSeasonSection()
        setupSortButton()
        setupYearSlider(r.fromYear, r.toYear)
        setupCategorySection()
        loadOptions()

        binding.kitsuFilterReset.setOnClickListener { resetAll() }
        binding.kitsuFilterCancel.setOnClickListener { dismiss() }
        binding.kitsuFilterApply.setOnClickListener { applyFilters(); dismiss() }
        binding.kitsuSavedFiltersButton.setOnClickListener { showSavedFiltersDialog() }
    }

    private fun setupEnumFilters() {
        val subtypes = if (isAnime) listOf(
            "TV" to "TV", "movie" to "Movie", "OVA" to "OVA", "ONA" to "ONA",
            "special" to "Special", "music" to "Music",
        ) else listOf(
            "manga" to "Manga", "novel" to "Novel", "manhwa" to "Manhwa",
            "manhua" to "Manhua", "oneshot" to "Oneshot", "doujin" to "Doujin",
        )
        val statuses = listOf(
            "current" to getString(R.string.ongoing),
            "finished" to getString(R.string.completed),
            "tba" to "TBA",
            "unreleased" to "Unreleased",
            "upcoming" to getString(R.string.upcoming),
        )
        includeList(binding.kitsuFilterSubtypeRecycler, subtypes.map { FilterOption(it.first, it.second) }, selectedSubtypes)
        includeList(binding.kitsuFilterStatusRecycler, statuses.map { FilterOption(it.first, it.second) }, selectedStatuses)

        // Kitsu only populates ageRating for anime — filtering manga by it matches nothing.
        if (isAnime) {
            val ageRatings = listOf(
                "G" to getString(R.string.kitsu_age_g),
                "PG" to getString(R.string.kitsu_age_pg),
                "R" to getString(R.string.kitsu_age_r),
                "R18" to getString(R.string.kitsu_age_r18),
            )
            includeList(binding.kitsuFilterAgeRecycler, ageRatings.map { FilterOption(it.first, it.second) }, selectedAgeRatings)
        } else {
            binding.kitsuFilterAgeLabel.visibility = View.GONE
            binding.kitsuFilterAgeRecycler.visibility = View.GONE
            selectedAgeRatings.clear()
        }
    }

    private fun setupSeasonSection() {
        if (!isAnime) {
            binding.kitsuFilterSeasonLabel.visibility = View.GONE
            binding.kitsuFilterSeasonRecycler.visibility = View.GONE
            return
        }
        val seasons = listOf("winter", "spring", "summer", "fall")
        binding.kitsuFilterSeasonRecycler.layoutManager =
            LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        binding.kitsuFilterSeasonRecycler.adapter = SingleChoiceAdapter(
            seasons.map { FilterOption(it, it.replaceFirstChar { c -> c.uppercase() }) },
            { selectedSeason },
            { selectedSeason = it },
        )
    }

    private fun includeList(
        recycler: RecyclerView,
        options: List<FilterOption>,
        included: MutableList<String>,
    ) {
        recycler.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        recycler.adapter = IncludeAdapter(options, included)
    }

    private fun setupCategorySection() {
        binding.kitsuCategoriesGrid.setOnCheckedChangeListener { _, isChecked ->
            binding.kitsuFilterCategoriesRecycler.layoutManager =
                if (!isChecked) LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
                else GridLayoutManager(requireContext(), 2, GridLayoutManager.VERTICAL, false)
        }
        binding.kitsuCategoriesGrid.isChecked = false

        updateCategorySearchIcon(false)
        binding.kitsuCategoriesSearchBtn.setOnClickListener {
            val visible = binding.kitsuCategorySearchLayout.visibility == View.VISIBLE
            binding.kitsuCategorySearchLayout.visibility = if (visible) View.GONE else View.VISIBLE
            updateCategorySearchIcon(!visible)
            if (visible) {
                binding.kitsuCategorySearchText.setText("")
                binding.kitsuCategorySearchText.dismissKeyboard()
            }
        }
        binding.kitsuCategorySearchText.addTextChangedListener(simpleWatcher { q ->
            updateCategoryResults(if (q.isBlank()) allCategories else allCategories.filter { it.label.contains(q, true) })
        })
    }

    private fun updateCategorySearchIcon(active: Boolean) {
        binding.kitsuCategoriesSearchBtn.setImageResource(
            if (active) R.drawable.ic_round_search_off_24 else R.drawable.ic_round_search_24
        )
    }

    private fun updateCategoryResults(options: List<FilterOption>) {
        if (_binding == null) return
        binding.kitsuFilterCategoriesRecycler.adapter = IncludeAdapter(options, selectedCategories)
    }

    private fun setupYearSlider(fromYear: Int?, toYear: Int?) {
        binding.kitsuFilterYearRange.valueFrom = yearRangeMin.toFloat()
        binding.kitsuFilterYearRange.valueTo = yearRangeMax.toFloat()
        binding.kitsuFilterYearRange.stepSize = 1f
        val start = (fromYear ?: yearRangeMin).coerceIn(yearRangeMin, yearRangeMax)
        val end = (toYear ?: yearRangeMax).coerceIn(yearRangeMin, yearRangeMax)
        binding.kitsuFilterYearRange.values = listOf(minOf(start, end).toFloat(), maxOf(start, end).toFloat())
    }

    private fun setupSortButton() {
        binding.kitsuFilterSortButton.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            val tv = TypedValue()
            requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
            val primary = tv.data
            sortOptions.forEachIndexed { index, option ->
                val title = if (option.value == (selectedSort ?: "")) {
                    SpannableString(option.label).apply {
                        setSpan(ForegroundColorSpan(primary), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else option.label
                popup.menu.add(0, index, index, title)
            }
            popup.setOnMenuItemClickListener { item: MenuItem ->
                selectedSort = sortOptions.getOrNull(item.itemId)?.value?.takeIf { it.isNotBlank() }
                true
            }
            popup.show()
        }
    }

    private fun simpleWatcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChange(s?.toString()?.trim().orEmpty())
    }

    private fun loadOptions() {
        CoroutineScope(Dispatchers.IO).launch {
            val categories = KitsuApi.getCategories().map { FilterOption(it.first, it.second) }
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                allCategories = categories.sortedBy { it.label.lowercase() }
                updateCategoryResults(allCategories)
            }
        }
    }

    private fun writeUiStateToResult() {
        val r = activity.kitsuSearchResult
        r.categories = selectedCategories.toMutableList().ifEmpty { null }
        r.subtypes = selectedSubtypes.toMutableList().ifEmpty { null }
        r.statuses = selectedStatuses.toMutableList().ifEmpty { null }
        r.ageRatings = selectedAgeRatings.toMutableList().ifEmpty { null }
        r.season = selectedSeason
        r.sort = selectedSort
        val values = binding.kitsuFilterYearRange.values
        val from = values[0].toInt()
        val to = values[1].toInt()
        if (from == yearRangeMin && to == yearRangeMax) {
            r.fromYear = null
            r.toYear = null
        } else {
            r.fromYear = from
            r.toYear = to
        }
    }

    private fun applyFilters() {
        writeUiStateToResult()
        activity.updateKitsuChips?.invoke()
        activity.search()
    }

    private fun showSavedFiltersDialog() {
        SavedFiltersDialog.show(
            context = requireContext(),
            loadPresets = {
                SavedFiltersStore.loadKitsu().map { SavedFilterEntry(it.name, it.chips()) }
            },
            onSaveCurrent = { name ->
                writeUiStateToResult()
                SavedFiltersStore.saveKitsu(SavedKitsuFilter.from(name, activity.kitsuSearchResult))
            },
            onApply = { name ->
                val preset = SavedFiltersStore.loadKitsu().firstOrNull { it.name == name } ?: return@show
                preset.applyTo(activity.kitsuSearchResult)
                activity.updateKitsuChips?.invoke()
                activity.search()
                dismiss()
            },
            onDelete = { name -> SavedFiltersStore.deleteKitsu(name) },
            onRename = { oldName, newName -> SavedFiltersStore.renameKitsu(oldName, newName) },
        )
    }

    private fun resetAll() {
        selectedCategories.clear()
        selectedSubtypes.clear()
        selectedStatuses.clear()
        selectedAgeRatings.clear()
        selectedSeason = null
        selectedSort = null
        binding.kitsuCategorySearchText.setText("")
        binding.kitsuFilterYearRange.values = listOf(yearRangeMin.toFloat(), yearRangeMax.toFloat())
        setupEnumFilters()
        setupSeasonSection()
        updateCategoryResults(allCategories)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = KitsuSearchFilterBottomSheet()
    }

    /** Plain include-toggle chip list. */
    private class IncludeAdapter(
        private val options: List<FilterOption>,
        private val included: MutableList<String>,
    ) : RecyclerView.Adapter<IncludeAdapter.Holder>() {

        inner class Holder(val chip: Chip) : RecyclerView.ViewHolder(chip)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val chip = ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false).root as Chip
            return Holder(chip)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val option = options[position]
            val chip = holder.chip
            chip.text = option.label
            chip.isCheckable = true
            chip.isChecked = included.contains(option.value)
            style(chip, chip.isChecked)
            chip.setOnClickListener {
                if (included.contains(option.value)) included.remove(option.value)
                else included.add(option.value)
                chip.isChecked = included.contains(option.value)
                style(chip, chip.isChecked)
            }
        }

        private fun style(chip: Chip, isIncluded: Boolean) {
            val ctx = chip.context
            if (isIncluded) {
                chip.chipBackgroundColor = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.filter_chip_include_bg))
                chip.setTextColor(ContextCompat.getColor(ctx, R.color.filter_chip_include_text))
            } else {
                chip.chipBackgroundColor = AppCompatResources.getColorStateList(ctx, R.color.chip_background_color)
                chip.setTextColor(ctx.getResourceColor(com.google.android.material.R.attr.colorOnSurface))
            }
            chip.isCloseIconVisible = false
        }

        override fun getItemCount() = options.size
    }

    /** Single-choice chip list (used for season). */
    private class SingleChoiceAdapter(
        private val options: List<FilterOption>,
        private val current: () -> String?,
        private val onSelect: (String?) -> Unit,
    ) : RecyclerView.Adapter<SingleChoiceAdapter.Holder>() {

        inner class Holder(val chip: Chip) : RecyclerView.ViewHolder(chip)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val chip = ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false).root as Chip
            return Holder(chip)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val option = options[position]
            val chip = holder.chip
            chip.text = option.label
            chip.isCheckable = true
            chip.isChecked = current() == option.value
            style(chip, chip.isChecked)
            chip.setOnClickListener {
                onSelect(if (current() == option.value) null else option.value)
                notifyDataSetChanged()
            }
        }

        private fun style(chip: Chip, isIncluded: Boolean) {
            val ctx = chip.context
            if (isIncluded) {
                chip.chipBackgroundColor = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.filter_chip_include_bg))
                chip.setTextColor(ContextCompat.getColor(ctx, R.color.filter_chip_include_text))
            } else {
                chip.chipBackgroundColor = AppCompatResources.getColorStateList(ctx, R.color.chip_background_color)
                chip.setTextColor(ctx.getResourceColor(com.google.android.material.R.attr.colorOnSurface))
            }
            chip.isCloseIconVisible = false
        }

        override fun getItemCount() = options.size
    }
}
