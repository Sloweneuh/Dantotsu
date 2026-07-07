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
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.databinding.BottomSheetMangabakaSearchFilterBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.media.savedfilters.SavedFilterEntry
import ani.dantotsu.media.savedfilters.SavedFiltersDialog
import ani.dantotsu.media.savedfilters.SavedFiltersStore
import ani.dantotsu.media.savedfilters.SavedMangaBakaFilter
import com.google.android.material.chip.Chip
import eu.kanade.tachiyomi.util.system.getResourceColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Filter sheet for MangaBaka search — mirrors the Comick filter sheet's interaction model. Every
 * filter (format/status/content-rating, genres, tags) is an include-exclude chip: tap to include,
 * long-press to exclude. Genres and tags are single-line with a search-icon toggle and a grid switch;
 * tags come from `/v1/tags` and only populate as the user types (selected tags stay visible).
 */
class MangaBakaSearchFilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetMangabakaSearchFilterBinding? = null
    private val binding get() = _binding!!

    private lateinit var activity: SearchActivity

    private var selectedGenres = mutableListOf<String>()
    private var excludedGenres = mutableListOf<String>()
    private var selectedTags = mutableListOf<String>()
    private var excludedTags = mutableListOf<String>()
    private var selectedTypes = mutableListOf<String>()
    private var excludedTypes = mutableListOf<String>()
    private var selectedStatuses = mutableListOf<String>()
    private var excludedStatuses = mutableListOf<String>()
    private var selectedContentRatings = mutableListOf<String>()
    private var excludedContentRatings = mutableListOf<String>()
    private var selectedSort: String? = null

    private var allGenres: List<FilterOption> = emptyList()
    private var allTags: List<FilterOption> = emptyList()

    private val yearRangeMin = 1900
    private val yearRangeMax = Calendar.getInstance().get(Calendar.YEAR) + 1

    data class FilterOption(val value: String, val label: String)

    private val sortOptions = listOf(
        FilterOption("", "Relevance"),
        FilterOption("popularity_desc", "Popularity"),
        FilterOption("score_desc", "Rating"),
        FilterOption("latest", "Latest"),
        FilterOption("trending_7d", "Trending (7d)"),
        FilterOption("trending_30d", "Trending (30d)"),
        FilterOption("name_asc", "Name A-Z"),
        FilterOption("published_year_desc", "Year (newest)"),
        FilterOption("chapters_desc", "Chapters"),
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetMangabakaSearchFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as SearchActivity

        val r = activity.mangaBakaSearchResult
        selectedGenres = r.genres?.toMutableList() ?: mutableListOf()
        excludedGenres = r.excludedGenres?.toMutableList() ?: mutableListOf()
        selectedTags = r.tags?.toMutableList() ?: mutableListOf()
        excludedTags = r.excludedTags?.toMutableList() ?: mutableListOf()
        selectedTypes = r.types?.toMutableList() ?: mutableListOf()
        excludedTypes = r.excludedTypes?.toMutableList() ?: mutableListOf()
        selectedStatuses = r.statuses?.toMutableList() ?: mutableListOf()
        excludedStatuses = r.excludedStatuses?.toMutableList() ?: mutableListOf()
        selectedContentRatings = r.contentRatings?.toMutableList() ?: mutableListOf()
        excludedContentRatings = r.excludedContentRatings?.toMutableList() ?: mutableListOf()
        selectedSort = r.sort

        setupEnumFilters()
        setupSortButton()
        setupYearSlider(r.fromYear, r.toYear)
        setupGenreSection()
        setupTagSection()
        loadOptions()

        binding.mbFilterReset.setOnClickListener { resetAll() }
        binding.mbFilterCancel.setOnClickListener { dismiss() }
        binding.mbFilterApply.setOnClickListener { applyFilters(); dismiss() }
        binding.mbSavedFiltersButton.setOnClickListener { showSavedFiltersDialog() }
    }

    // --- format / status / content rating (include-exclude chips) ---

    private fun setupEnumFilters() {
        val types = listOf(
            FilterOption("manga", getString(R.string.manga)),
            FilterOption("manhwa", getString(R.string.manhwa)),
            FilterOption("manhua", getString(R.string.manhua)),
            FilterOption("novel", getString(R.string.novel)),
            FilterOption("oel", "OEL"),
            FilterOption("other", getString(R.string.other)),
        )
        val statuses = listOf(
            FilterOption("releasing", getString(R.string.ongoing)),
            FilterOption("completed", getString(R.string.completed)),
            FilterOption("hiatus", getString(R.string.hiatus)),
            FilterOption("cancelled", getString(R.string.cancelled)),
            FilterOption("upcoming", getString(R.string.upcoming)),
        )
        val contentRatings = listOf(
            FilterOption("safe", "Safe"),
            FilterOption("suggestive", "Suggestive"),
            FilterOption("erotica", "Erotica"),
            FilterOption("pornographic", "Pornographic"),
        )
        includeExclude(binding.mbFilterTypeRecycler, types, selectedTypes, excludedTypes, grid = false)
        includeExclude(binding.mbFilterStatusRecycler, statuses, selectedStatuses, excludedStatuses, grid = false)
        includeExclude(binding.mbFilterContentRecycler, contentRatings, selectedContentRatings, excludedContentRatings, grid = false)
    }

    private fun includeExclude(
        recycler: RecyclerView,
        options: List<FilterOption>,
        included: MutableList<String>,
        excluded: MutableList<String>,
        grid: Boolean,
    ) {
        recycler.layoutManager = if (grid)
            GridLayoutManager(requireContext(), 2, GridLayoutManager.VERTICAL, false)
        else LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        recycler.adapter = IncludeExcludeAdapter(options, included, excluded)
    }

    // --- genres ---

    private fun setupGenreSection() {
        binding.mbGenresGrid.setOnCheckedChangeListener { _, isChecked ->
            binding.mbFilterGenresRecycler.layoutManager =
                if (!isChecked) LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
                else GridLayoutManager(requireContext(), 2, GridLayoutManager.VERTICAL, false)
        }
        binding.mbGenresGrid.isChecked = false

        updateGenreSearchIcon(false)
        binding.mbGenresSearchBtn.setOnClickListener {
            val visible = binding.mbGenreSearchLayout.visibility == View.VISIBLE
            binding.mbGenreSearchLayout.visibility = if (visible) View.GONE else View.VISIBLE
            updateGenreSearchIcon(!visible)
            if (visible) binding.mbGenreSearchText.setText("")
        }
        binding.mbGenreSearchText.addTextChangedListener(simpleWatcher { q ->
            updateGenreResults(if (q.isBlank()) allGenres else allGenres.filter { it.label.contains(q, true) })
        })
    }

    private fun updateGenreSearchIcon(active: Boolean) {
        binding.mbGenresSearchBtn.setImageResource(
            if (active) R.drawable.ic_round_search_off_24 else R.drawable.ic_round_search_24
        )
    }

    private fun updateGenreResults(options: List<FilterOption>) {
        if (_binding == null) return
        binding.mbFilterGenresRecycler.adapter = IncludeExcludeAdapter(options, selectedGenres, excludedGenres)
    }

    // --- tags ---

    private fun setupTagSection() {
        binding.mbTagsGrid.setOnCheckedChangeListener { _, isChecked ->
            binding.mbFilterTagsRecycler.layoutManager =
                if (!isChecked) LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
                else GridLayoutManager(requireContext(), 2, GridLayoutManager.VERTICAL, false)
        }
        binding.mbTagsGrid.isChecked = false

        updateTagSearchIcon(false)
        binding.mbTagsSearchBtn.setOnClickListener {
            val visible = binding.mbTagSearchLayout.visibility == View.VISIBLE
            binding.mbTagSearchLayout.visibility = if (visible) View.GONE else View.VISIBLE
            updateTagSearchIcon(!visible)
            if (visible) binding.mbTagSearchText.setText("")
        }
        binding.mbTagSearchText.addTextChangedListener(simpleWatcher { q ->
            if (q.isBlank()) updateTagResults(selectedTagOptions())
            else updateTagResults(allTags.filter { it.label.contains(q, true) }.take(80))
        })
    }

    private fun updateTagSearchIcon(active: Boolean) {
        binding.mbTagsSearchBtn.setImageResource(
            if (active) R.drawable.ic_round_search_off_24 else R.drawable.ic_round_search_24
        )
    }

    private fun selectedTagOptions(): List<FilterOption> {
        val chosen = (selectedTags + excludedTags).toSet()
        if (chosen.isEmpty()) return emptyList()
        return allTags.filter { it.value in chosen }.ifEmpty { chosen.map { FilterOption(it, it) } }
    }

    private fun updateTagResults(options: List<FilterOption>) {
        if (_binding == null) return
        binding.mbFilterTagsRecycler.adapter = IncludeExcludeAdapter(options, selectedTags, excludedTags)
    }

    // --- year slider ---

    private fun setupYearSlider(fromYear: Int?, toYear: Int?) {
        binding.mbFilterYearRange.valueFrom = yearRangeMin.toFloat()
        binding.mbFilterYearRange.valueTo = yearRangeMax.toFloat()
        binding.mbFilterYearRange.stepSize = 1f
        val start = (fromYear ?: yearRangeMin).coerceIn(yearRangeMin, yearRangeMax)
        val end = (toYear ?: yearRangeMax).coerceIn(yearRangeMin, yearRangeMax)
        binding.mbFilterYearRange.values = listOf(minOf(start, end).toFloat(), maxOf(start, end).toFloat())
    }

    // --- sort ---

    private fun setupSortButton() {
        binding.mbFilterSortButton.setOnClickListener { anchor ->
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

    // --- data ---

    private fun simpleWatcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChange(s?.toString()?.trim().orEmpty())
    }

    private fun loadOptions() {
        CoroutineScope(Dispatchers.IO).launch {
            val genres = MangaBakaApi.getGenreOptions()
                .mapNotNull { g -> val v = g.value; val l = g.label; if (v != null && l != null) FilterOption(v, l) else null }
            val tags = MangaBakaApi.getTagOptions()
                .mapNotNull { t -> t.name?.let { FilterOption(it, it) } }
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                allGenres = genres.sortedBy { it.label.lowercase() }
                allTags = tags.sortedBy { it.label.lowercase() }
                updateGenreResults(allGenres)
                updateTagResults(selectedTagOptions())
            }
        }
    }

    private fun writeUiStateToResult() {
        val r = activity.mangaBakaSearchResult
        r.genres = selectedGenres.toMutableList().ifEmpty { null }
        r.excludedGenres = excludedGenres.toMutableList().ifEmpty { null }
        r.tags = selectedTags.toMutableList().ifEmpty { null }
        r.excludedTags = excludedTags.toMutableList().ifEmpty { null }
        r.types = selectedTypes.toMutableList().ifEmpty { null }
        r.excludedTypes = excludedTypes.toMutableList().ifEmpty { null }
        r.statuses = selectedStatuses.toMutableList().ifEmpty { null }
        r.excludedStatuses = excludedStatuses.toMutableList().ifEmpty { null }
        r.contentRatings = selectedContentRatings.toMutableList().ifEmpty { null }
        r.excludedContentRatings = excludedContentRatings.toMutableList().ifEmpty { null }
        r.sort = selectedSort
        val values = binding.mbFilterYearRange.values
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
        activity.updateMangaBakaChips?.invoke()
        activity.search()
    }

    private fun showSavedFiltersDialog() {
        SavedFiltersDialog.show(
            context = requireContext(),
            loadPresets = {
                SavedFiltersStore.loadMangaBaka().map { SavedFilterEntry(it.name, it.chips()) }
            },
            onSaveCurrent = { name ->
                writeUiStateToResult()
                SavedFiltersStore.saveMangaBaka(SavedMangaBakaFilter.from(name, activity.mangaBakaSearchResult))
            },
            onApply = { name ->
                val preset = SavedFiltersStore.loadMangaBaka().firstOrNull { it.name == name } ?: return@show
                preset.applyTo(activity.mangaBakaSearchResult)
                activity.updateMangaBakaChips?.invoke()
                activity.search()
                dismiss()
            },
            onDelete = { name -> SavedFiltersStore.deleteMangaBaka(name) },
            onRename = { oldName, newName -> SavedFiltersStore.renameMangaBaka(oldName, newName) },
        )
    }

    private fun resetAll() {
        selectedGenres.clear(); excludedGenres.clear()
        selectedTags.clear(); excludedTags.clear()
        selectedTypes.clear(); excludedTypes.clear()
        selectedStatuses.clear(); excludedStatuses.clear()
        selectedContentRatings.clear(); excludedContentRatings.clear()
        selectedSort = null
        binding.mbGenreSearchText.setText("")
        binding.mbTagSearchText.setText("")
        binding.mbFilterYearRange.values = listOf(yearRangeMin.toFloat(), yearRangeMax.toFloat())
        setupEnumFilters()
        updateGenreResults(allGenres)
        updateTagResults(emptyList())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = MangaBakaSearchFilterBottomSheet()
    }

    /** Chip list where tap toggles include and long-press toggles exclude. */
    private class IncludeExcludeAdapter(
        private val options: List<FilterOption>,
        private val included: MutableList<String>,
        private val excluded: MutableList<String>,
    ) : RecyclerView.Adapter<IncludeExcludeAdapter.Holder>() {

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
            chip.setOnCheckedChangeListener(null)
            chip.isChecked = included.contains(option.value) || excluded.contains(option.value)
            style(chip, included.contains(option.value), excluded.contains(option.value))

            chip.setOnClickListener {
                val isIncluded = included.contains(option.value)
                val isExcluded = excluded.contains(option.value)
                included.remove(option.value); excluded.remove(option.value)
                if (!isIncluded && !isExcluded) included.add(option.value)
                chip.isChecked = included.contains(option.value) || excluded.contains(option.value)
                style(chip, included.contains(option.value), excluded.contains(option.value))
            }
            chip.setOnLongClickListener {
                val isExcluded = excluded.contains(option.value)
                included.remove(option.value); excluded.remove(option.value)
                if (!isExcluded) excluded.add(option.value)
                chip.isChecked = included.contains(option.value) || excluded.contains(option.value)
                style(chip, included.contains(option.value), excluded.contains(option.value))
                true
            }
        }

        private fun style(chip: Chip, isIncluded: Boolean, isExcluded: Boolean) {
            val ctx = chip.context
            when {
                isExcluded -> {
                    chip.chipBackgroundColor = ColorStateList.valueOf(
                        ctx.getResourceColor(com.google.android.material.R.attr.colorErrorContainer))
                    chip.setTextColor(ctx.getResourceColor(com.google.android.material.R.attr.colorOnErrorContainer))
                }
                isIncluded -> {
                    chip.chipBackgroundColor = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.filter_chip_include_bg))
                    chip.setTextColor(ContextCompat.getColor(ctx, R.color.filter_chip_include_text))
                }
                else -> {
                    chip.chipBackgroundColor = AppCompatResources.getColorStateList(ctx, R.color.chip_background_color)
                    chip.setTextColor(ctx.getResourceColor(com.google.android.material.R.attr.colorOnSurface))
                }
            }
            chip.isCloseIconVisible = false
        }

        override fun getItemCount() = options.size
    }
}
