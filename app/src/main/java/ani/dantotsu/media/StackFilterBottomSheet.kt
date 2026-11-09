package ani.dantotsu.media

import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.databinding.BottomSheetStackFilterBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.dismissKeyboard
import ani.dantotsu.getThemeColor
import ani.dantotsu.media.savedfilters.SavedFilterEntry
import ani.dantotsu.media.savedfilters.SavedFiltersDialog
import ani.dantotsu.media.savedfilters.SavedFiltersStore
import ani.dantotsu.media.savedfilters.SavedStackFilter
import com.google.android.material.chip.Chip

/** The screen showing a stack: what [StackFilterBottomSheet] reads from and writes to. */
interface StackFilterHost {
    val stackFilter: SavedStackFilter

    /** Every entry the stack resolved to, before filtering: where the sheet's choices come from. */
    fun stackEntries(): List<Media>
    fun applyStackFilter(filter: SavedStackFilter)
    fun openStackManageFilters()
}

/**
 * Filters and sorts a stack's entries, MAL's and MangaBaka's alike (Comick lists have their own,
 * [ComickListFilterBottomSheet]). The choices offered are the ones this stack's entries have —
 * no genre nobody in it carries — plus whatever the current filter already holds, so a saved
 * filter applied to another stack can still be undone from here.
 */
class StackFilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetStackFilterBinding? = null
    private val binding get() = _binding!!

    private lateinit var host: StackFilterHost
    private var entries: List<Media> = emptyList()

    private var sort: String? = null
    private val hideListStatuses = mutableListOf<String>()
    private val formats = mutableListOf<String>()
    private val excludedFormats = mutableListOf<String>()
    private val statuses = mutableListOf<String>()
    private val genres = mutableListOf<String>()
    private val excludedGenres = mutableListOf<String>()
    private val tags = mutableListOf<String>()
    private val excludedTags = mutableListOf<String>()
    private val lengths = mutableListOf<String>()

    private var yearMin = 0
    private var yearMax = 0
    private var allTags: List<String> = emptyList()
    private var adultTags: Set<String> = emptySet()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetStackFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        host = requireActivity() as? StackFilterHost ?: run { dismiss(); return }
        entries = host.stackEntries()
        load(host.stackFilter)
        bindAll()

        val ctx = requireContext()
        binding.stackFilterSectionList.summarizeWhenClosed { FilterSummary.choices(ctx, hideListStatuses.size) }
        binding.stackFilterSectionFormat.summarizeWhenClosed {
            FilterSummary.join(
                FilterSummary.choices(ctx, formats.size + statuses.size, excludedFormats.size),
                minScore()?.let { getString(R.string.stack_chip_min_score, it) },
                getString(R.string.stack_filter_hide_adult).takeIf { binding.stackFilterHideAdult.isChecked },
            )
        }
        binding.stackFilterSectionGenres.summarizeWhenClosed {
            FilterSummary.choices(ctx, genres.size + tags.size, excludedGenres.size + excludedTags.size)
        }
        binding.stackFilterSectionRelease.summarizeWhenClosed {
            FilterSummary.join(
                FilterSummary.range(years().first, years().second),
                FilterSummary.choices(ctx, lengths.size),
            )
        }

        binding.stackFilterReset.setOnClickListener {
            load(SavedStackFilter())
            bindAll()
            refreshSummaries()
        }
        binding.stackFilterCancel.setOnClickListener { dismiss() }
        binding.stackFilterApply.setOnClickListener {
            host.applyStackFilter(current())
            dismiss()
        }
        binding.stackFilterSavedFilters.setOnClickListener { showSavedFilters() }
        binding.stackFilterSortButton.setOnClickListener { showSortMenu(it) }

        val active = StackFilters.activeChips(ctx, host.stackFilter).size
        binding.manageFiltersButton.isVisible = active > 0
        binding.manageFiltersCount.isVisible = active > 0
        binding.manageFiltersCount.text = active.toString()
        binding.manageFiltersButton.setOnClickListener {
            // The popup edits the live filter, which this sheet's pending choices would undo.
            dismiss()
            host.openStackManageFilters()
        }

        binding.stackFilterTagsSearch.setOnClickListener {
            setTagSearch(!binding.stackFilterTagSearchLayout.isVisible)
        }
        binding.stackFilterTagSearchText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = bindTags(tagQuery())
        })
    }

    private fun load(f: SavedStackFilter) {
        sort = f.sort
        hideListStatuses.reset(f.hideListStatuses)
        formats.reset(f.formats)
        excludedFormats.reset(f.excludedFormats)
        statuses.reset(f.statuses)
        genres.reset(f.genres)
        excludedGenres.reset(f.excludedGenres)
        tags.reset(f.tags)
        excludedTags.reset(f.excludedTags)
        lengths.reset(f.lengths)
        pendingScore = f.minScore
        pendingYears = f.fromYear to f.toYear
        pendingAdult = f.hideAdult
    }

    private fun MutableList<String>.reset(values: List<String>) {
        clear()
        addAll(values)
    }

    // Held until the controls exist, which bindAll sets them from.
    private var pendingScore: Int? = null
    private var pendingYears: Pair<Int?, Int?> = null to null
    private var pendingAdult = false

    private fun bindAll() {
        val ctx = requireContext()
        updateSortIcon()

        bindMulti(binding.stackFilterListRecycler, StackFilters.LIST_STATUSES, hideListStatuses) {
            StackFilters.listStatusLabel(ctx, it)
        }

        val presentFormats = entries.mapNotNull { StackFilters.formatOf(it) }.toSet() + formats + excludedFormats
        val formatOptions = StackFilters.FORMATS.filter { it in presentFormats }
        binding.stackFilterFormatGroup.isVisible = formatOptions.size > 1 || formats.isNotEmpty() || excludedFormats.isNotEmpty()
        binding.stackFilterFormatRecycler.adapter = triState(
            formatOptions.map { ComickApi.FilterOption(it, StackFilters.formatLabel(ctx, it)) }, formats, excludedFormats,
        )

        val presentStatuses = entries.mapNotNull { it.status }.toSet() + statuses
        val statusOptions = StackFilters.STATUSES.filter { it in presentStatuses }
        binding.stackFilterStatusGroup.isVisible = statusOptions.isNotEmpty()
        bindMulti(binding.stackFilterStatusRecycler, statusOptions, statuses) { StackFilters.statusLabel(ctx, it) }

        binding.stackFilterMinScore.value = (pendingScore ?: 0).coerceIn(0, 95).let { it - it % 5 }.toFloat()
        updateScoreLabel()
        binding.stackFilterMinScore.clearOnChangeListeners()
        binding.stackFilterMinScore.addOnChangeListener { _, _, _ -> updateScoreLabel() }
        binding.stackFilterHideAdult.isChecked = pendingAdult

        val genreOptions = (entries.flatMap { it.genres } + genres + excludedGenres).distinct().sortedBy { it.lowercase() }
        binding.stackFilterGenresGroup.isVisible = genreOptions.isNotEmpty()
        binding.stackFilterGenresRecycler.adapter =
            triState(genreOptions.map { ComickApi.FilterOption(it, it) }, genres, excludedGenres)

        // The tags the most entries share come first: those are the ones that narrow a stack down.
        val counts = entries.flatMap { StackFilters.safeTags(it).distinct() }.groupingBy { it }.eachCount()
        adultTags = entries.flatMap { StackFilters.adultTags(it) }.toSet()
        allTags = (counts.keys + tags + excludedTags).distinct()
            .sortedWith(compareByDescending<String> { counts[it] ?: 0 }.thenBy { it.lowercase() })
        binding.stackFilterTagsGroup.isVisible = allTags.isNotEmpty()
        // A filter already holding an 18+ tag opens with them shown, or it couldn't be undone here.
        binding.stackFilterTagsAdult.setOnCheckedChangeListener(null)
        binding.stackFilterTagsAdult.isChecked = (tags + excludedTags).any { it in adultTags }
        binding.stackFilterTagsAdult.setOnCheckedChangeListener { _, _ -> bindTags(tagQuery()) }
        (binding.stackFilterTagsAdult.parent as View).isVisible = adultTags.isNotEmpty()
        bindTags(tagQuery())

        bindGrid(binding.stackFilterGenresGrid, binding.stackFilterGenresRecycler)
        bindGrid(binding.stackFilterTagsGrid, binding.stackFilterTagsRecycler)

        val years = entries.mapNotNull { it.startDate?.year }.filter { it > 0 }
        val (from, to) = pendingYears
        yearMin = minOf(years.minOrNull() ?: 0, from ?: Int.MAX_VALUE)
        yearMax = maxOf(years.maxOrNull() ?: 0, to ?: 0)
        val hasYears = yearMin in 1..<yearMax
        binding.stackFilterYearGroup.isVisible = hasYears
        if (hasYears) {
            binding.stackFilterYearRange.valueFrom = yearMin.toFloat()
            binding.stackFilterYearRange.valueTo = yearMax.toFloat()
            binding.stackFilterYearRange.values = listOf(
                (from ?: yearMin).coerceIn(yearMin, yearMax).toFloat(),
                (to ?: yearMax).coerceIn(yearMin, yearMax).toFloat(),
            )
            updateYearLabel()
            binding.stackFilterYearRange.clearOnChangeListeners()
            binding.stackFilterYearRange.addOnChangeListener { _, _, _ -> updateYearLabel() }
        }

        binding.stackFilterLengthGroup.isVisible = entries.any { StackFilters.lengthOf(it) != null } || lengths.isNotEmpty()
        bindMulti(binding.stackFilterLengthRecycler, StackFilters.LENGTHS, lengths) { StackFilters.lengthLabel(ctx, it) }
    }

    private fun tagQuery() = binding.stackFilterTagSearchText.text?.toString()?.trim().orEmpty()

    /** A row that scrolls sideways, or every chip at once in two columns. */
    private fun bindGrid(switch: com.google.android.material.materialswitch.MaterialSwitch, recycler: RecyclerView) {
        fun layOut(grid: Boolean) {
            recycler.layoutManager =
                if (grid) androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2, RecyclerView.VERTICAL, false)
                else LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        }
        switch.setOnCheckedChangeListener { _, checked -> layOut(checked) }
        layOut(switch.isChecked)
    }

    private fun bindTags(query: String) {
        if (_binding == null) return
        val showAdult = binding.stackFilterTagsAdult.isChecked
        val offered = if (showAdult) allTags else allTags.filter { it !in adultTags }
        val shown = if (query.isBlank()) offered else offered.filter { it.contains(query, ignoreCase = true) }
        binding.stackFilterTagsRecycler.adapter =
            triState(shown.map { ComickApi.FilterOption(it, it) }, tags, excludedTags)
    }

    private fun setTagSearch(enabled: Boolean) {
        binding.stackFilterTagSearchLayout.isVisible = enabled
        // The field says what it searches, and the switches need the label's room beside it.
        binding.stackFilterTagsLabel.isVisible = !enabled
        binding.stackFilterTagsSearch.setImageResource(
            if (enabled) R.drawable.ic_round_search_off_24 else R.drawable.ic_round_search_24
        )
        if (!enabled) {
            binding.stackFilterTagSearchText.setText("")
            binding.stackFilterTagSearchText.dismissKeyboard()
        }
    }

    /** Include / exclude / neither, as the genre chips of every other filter sheet. */
    private fun triState(
        options: List<ComickApi.FilterOption>, included: MutableList<String>, excluded: MutableList<String>,
    ) = GenreCategoryChipAdapter(options, included, excluded) { key, state ->
        included.remove(key)
        excluded.remove(key)
        when (state) {
            ComickSearchFilterBottomSheet.FilterState.INCLUDED -> included.add(key)
            ComickSearchFilterBottomSheet.FilterState.EXCLUDED -> excluded.add(key)
            ComickSearchFilterBottomSheet.FilterState.NONE -> Unit
        }
    }

    private fun bindMulti(
        recycler: RecyclerView, options: List<String>, selected: MutableList<String>, label: (String) -> String,
    ) {
        recycler.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(ItemChipBinding.inflate(LayoutInflater.from(parent.context), parent, false).root) {}

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val option = options[position]
                (holder.itemView as Chip).apply {
                    text = label(option)
                    isCheckable = true
                    setOnCheckedChangeListener(null)
                    isChecked = option in selected
                    setOnCheckedChangeListener { _, checked -> if (checked) selected.add(option) else selected.remove(option) }
                }
            }

            override fun getItemCount() = options.size
        }
    }

    private fun minScore(): Int? = binding.stackFilterMinScore.value.toInt().takeIf { it > 0 }

    private fun updateScoreLabel() {
        binding.stackFilterMinScoreValue.text = getString(
            R.string.stack_filter_min_score,
            minScore()?.toString() ?: getString(R.string.stack_filter_score_any),
        )
    }

    /** The chosen years, or nulls where the slider is left at an end. */
    private fun years(): Pair<Int?, Int?> {
        if (!binding.stackFilterYearGroup.isVisible) return pendingYears
        val v = binding.stackFilterYearRange.values
        return v[0].toInt().takeIf { it > yearMin } to v[1].toInt().takeIf { it < yearMax }
    }

    private fun updateYearLabel() {
        val v = binding.stackFilterYearRange.values
        binding.stackFilterYearRangeValue.text =
            getString(R.string.comick_year_range, v[0].toInt().toString(), v[1].toInt().toString())
    }

    private fun showSortMenu(anchor: View) {
        val ctx = requireContext()
        val popup = PopupMenu(ctx, anchor)
        val primary = ctx.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        StackFilters.SORTS.forEachIndexed { index, option ->
            val label = StackFilters.sortLabel(ctx, option)
            val title = if (option == sort) SpannableString(label).apply {
                setSpan(ForegroundColorSpan(primary), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else label
            popup.menu.add(0, index, index, title)
        }
        popup.setOnMenuItemClickListener { item ->
            sort = StackFilters.SORTS.getOrNull(item.itemId)
            updateSortIcon()
            true
        }
        popup.show()
    }

    private fun updateSortIcon() {
        binding.stackFilterSortButton.setImageResource(StackFilters.sortIcon(sort))
    }

    private fun refreshSummaries() = listOf(
        binding.stackFilterSectionList, binding.stackFilterSectionFormat,
        binding.stackFilterSectionGenres, binding.stackFilterSectionRelease,
    ).forEach { it.refreshSummary() }

    private fun current(name: String = ""): SavedStackFilter {
        val (from, to) = years()
        return SavedStackFilter(
            name = name,
            sort = sort,
            hideListStatuses = hideListStatuses.toList(),
            formats = formats.toList(),
            excludedFormats = excludedFormats.toList(),
            statuses = statuses.toList(),
            minScore = minScore(),
            genres = genres.toList(),
            excludedGenres = excludedGenres.toList(),
            tags = tags.toList(),
            excludedTags = excludedTags.toList(),
            fromYear = from,
            toYear = to,
            lengths = lengths.toList(),
            hideAdult = binding.stackFilterHideAdult.isChecked,
        )
    }

    private fun showSavedFilters() {
        SavedFiltersDialog.show(
            context = requireContext(),
            loadPresets = { SavedFiltersStore.loadStack().map { SavedFilterEntry(it.name, it.chips()) } },
            onSaveCurrent = { name ->
                val preset = current(name)
                SavedFiltersStore.saveStack(preset)
                host.applyStackFilter(preset)
            },
            onApply = { name ->
                val preset = SavedFiltersStore.loadStack().firstOrNull { it.name == name } ?: return@show
                host.applyStackFilter(preset)
                dismiss()
            },
            onDelete = { name -> SavedFiltersStore.deleteStack(name) },
            onRename = { oldName, newName -> SavedFiltersStore.renameStack(oldName, newName) },
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = StackFilterBottomSheet()
    }
}
