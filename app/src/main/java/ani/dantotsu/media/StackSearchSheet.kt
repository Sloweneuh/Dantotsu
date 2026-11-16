package ani.dantotsu.media

import android.content.Context
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.mangabaka.MangaBakaStacks
import ani.dantotsu.connections.mangabaka.MangaBakaStacks.StackSearch
import ani.dantotsu.connections.mangabaka.MangaBakaStacks.StackSort
import ani.dantotsu.databinding.BottomSheetStackSearchBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.media.savedfilters.SavedFilterEntry
import ani.dantotsu.media.savedfilters.SavedFiltersDialog
import ani.dantotsu.media.savedfilters.SavedFiltersStore
import ani.dantotsu.media.savedfilters.SavedStackSearchFilter
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlin.math.roundToInt

/**
 * How MangaBaka's stacks are searched: the filters of `GET /v2/stacks/search`. Laid out and used
 * like the stack filter sheet ([StackFilterBottomSheet]): reset, saved filters, the active ones and
 * sort in the header, a collapsible section per filter, nothing applied until Apply. Reset goes back to the defaults the
 * [Host] gives (one series' stacks start by votes, all of them by trending).
 */
class StackSearchSheet : BottomSheetDialogFragment() {

    interface Host {
        fun onStackSearch(search: StackSearch)

        /** The popup listing the applied filters, each removable. */
        fun openStackSearchManageFilters()
    }

    private var _binding: BottomSheetStackSearchBinding? = null
    private val binding get() = _binding!!

    private var search = StackSearch()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetStackSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    @Suppress("DEPRECATION")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val defaults = (args.getSerializable(ARG_DEFAULTS) as? StackSearch) ?: StackSearch()
        val applied = (args.getSerializable(ARG_SEARCH) as? StackSearch) ?: defaults
        search = (savedInstanceState?.getSerializable(ARG_SEARCH) as? StackSearch) ?: applied
        val host = activity as? Host

        binding.stackSearchSize.addOnChangeListener { slider, _, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val (low, high) = slider.values.map { it.roundToInt() }
            search = search.copy(
                minEntries = low.takeIf { it > 0 },
                // The end of the slider is "no upper bound", not a hundred.
                maxEntries = high.takeIf { it < SIZE_MAX },
            )
            binding.stackSearchSizeValue.text = sizeLabel(requireContext(), search)
        }
        binding.stackSearchSortButton.setOnClickListener { showSortMenu(it) }
        binding.stackSearchReset.setOnClickListener {
            search = defaults
            show()
        }
        binding.stackSearchCancel.setOnClickListener { dismiss() }
        binding.stackSearchApply.setOnClickListener {
            host?.onStackSearch(search)
            dismiss()
        }
        binding.stackSearchSavedFilters.setOnClickListener { showSavedFilters(host) }

        val active = activeChips(requireContext(), applied, defaults).size
        binding.stackSearchManageFilters.isVisible = active > 0
        binding.stackSearchManageFiltersCount.isVisible = active > 0
        binding.stackSearchManageFiltersCount.text = active.toString()
        binding.stackSearchManageFilters.setOnClickListener {
            // The popup edits the applied search, which this sheet's pending choices would undo.
            dismiss()
            host?.openStackSearchManageFilters()
        }

        // Closed sections say what they're set to, as the stack filter sheet's do.
        binding.stackSearchSectionKind.summarizeWhenClosed {
            search.kinds.takeIf { it.isNotEmpty() }?.joinToString(", ") { getString(it.label) }
        }
        binding.stackSearchSectionSpoilers.summarizeWhenClosed { getString(search.spoilers.label) }
        binding.stackSearchSectionSize.summarizeWhenClosed {
            sizeLabel(requireContext(), search).takeIf { search.minEntries != null || search.maxEntries != null }
        }
        binding.stackSearchSectionUpdated.summarizeWhenClosed {
            getString(search.updated.label).takeIf { search.updated != MangaBakaStacks.StackUpdated.ANY }
        }
        show()
    }

    /** Everything as [search] has it. */
    private fun show() {
        multi(binding.stackSearchKind, MangaBakaStacks.StackKind.entries, search.kinds, { it.label }) {
            search = search.copy(kinds = it)
        }
        single(binding.stackSearchSpoilers, MangaBakaStacks.Spoilers.entries, search.spoilers, { it.label }) {
            search = search.copy(spoilers = it)
        }
        single(binding.stackSearchUpdated, MangaBakaStacks.StackUpdated.entries, search.updated, { it.label }) {
            search = search.copy(updated = it)
        }
        binding.stackSearchSize.setValues(
            (search.minEntries ?: 0).toFloat(),
            (search.maxEntries ?: SIZE_MAX).toFloat().coerceAtMost(SIZE_MAX.toFloat()),
        )
        binding.stackSearchSizeValue.text = sizeLabel(requireContext(), search)
        binding.stackSearchSortButton.setImageResource(sortIcon(search.sort))
        listOf(
            binding.stackSearchSectionKind, binding.stackSearchSectionSpoilers,
            binding.stackSearchSectionSize, binding.stackSearchSectionUpdated,
        ).forEach { it.refreshSummary() }
    }

    /** The sorts, the current one in the accent colour, as the stack filter sheet lists its own. */
    private fun showSortMenu(anchor: View) {
        val ctx = requireContext()
        val primary = ctx.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        PopupMenu(ctx, anchor).apply {
            StackSort.entries.forEachIndexed { index, option ->
                val label = getString(option.label)
                menu.add(0, index, index, if (option == search.sort) SpannableString(label).apply {
                    setSpan(ForegroundColorSpan(primary), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else label)
            }
            setOnMenuItemClickListener { item ->
                StackSort.entries.getOrNull(item.itemId)?.let {
                    search = search.copy(sort = it)
                    binding.stackSearchSortButton.setImageResource(sortIcon(it))
                }
                true
            }
            show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(ARG_SEARCH, search)
    }

    private fun showSavedFilters(host: Host?) {
        SavedFiltersDialog.show(
            context = requireContext(),
            loadPresets = { SavedFiltersStore.loadStackSearch().map { SavedFilterEntry(it.name, it.chips()) } },
            onSaveCurrent = { name ->
                SavedFiltersStore.saveStackSearch(SavedStackSearchFilter.of(name, search))
                host?.onStackSearch(search)
            },
            onApply = { name ->
                val preset = SavedFiltersStore.loadStackSearch().firstOrNull { it.name == name } ?: return@show
                host?.onStackSearch(preset.toSearch())
                dismiss()
            },
            onDelete = { name -> SavedFiltersStore.deleteStackSearch(name) },
            onRename = { oldName, newName -> SavedFiltersStore.renameStackSearch(oldName, newName) },
        )
    }

    /** [options] as chips in [group], [selected] checked; picking one hands it to [onPick]. */
    private fun <T> single(
        group: ChipGroup, options: List<T>, selected: T, label: (T) -> Int, onPick: (T) -> Unit,
    ) {
        group.removeAllViews()
        options.forEach { option ->
            group.addView(chip(group, label(option)).apply {
                isChecked = option == selected
                setOnClickListener {
                    onPick(option)
                    isChecked = true
                }
            })
        }
    }

    /** [options] as chips in [group], each in or out of [selected] independently. */
    private fun <T> multi(
        group: ChipGroup, options: List<T>, selected: Set<T>, label: (T) -> Int, onChange: (Set<T>) -> Unit,
    ) {
        group.removeAllViews()
        val picked = selected.toMutableSet()
        options.forEach { option ->
            group.addView(chip(group, label(option)).apply {
                isChecked = option in picked
                setOnCheckedChangeListener { _, checked ->
                    if (checked) picked += option else picked -= option
                    onChange(picked.toSet())
                }
            })
        }
    }

    /** The stack filter sheet's chip ([R.layout.item_chip]), checkable. */
    private fun chip(group: ChipGroup, label: Int) =
        (layoutInflater.inflate(R.layout.item_chip, group, false) as Chip).apply {
            id = View.generateViewId()
            isCheckable = true
            setText(label)
        }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "StackSearchSheet"
        private const val ARG_SEARCH = "search"
        private const val ARG_DEFAULTS = "defaults"

        /** The size slider's end, read as "no upper bound". */
        private const val SIZE_MAX = 100

        fun sizeLabel(context: Context, search: StackSearch): String {
            val low = search.minEntries
            val high = search.maxEntries
            return when {
                low == null && high == null -> context.getString(R.string.stack_search_size_any)
                high == null -> context.getString(R.string.stack_search_size_at_least, low)
                low == null -> context.getString(R.string.stack_search_size_at_most, high)
                else -> context.getString(R.string.stack_search_size_range, low, high)
            }
        }

        /**
         * One chip per filter [search] sets beyond [defaults], each with the search as it would be
         * without it — what removing the chip applies. The sort counts when it isn't the default's;
         * with no [defaults] (a saved filter's chips) it always does.
         */
        fun activeChips(context: Context, search: StackSearch, defaults: StackSearch?): List<Pair<String, StackSearch>> {
            val base = defaults ?: StackSearch()
            val out = mutableListOf<Pair<String, StackSearch>>()
            if (defaults == null || search.sort != defaults.sort) {
                out += context.getString(R.string.stack_chip_sort, context.getString(search.sort.label)) to
                    search.copy(sort = base.sort)
            }
            search.kinds.forEach { out += context.getString(it.label) to search.copy(kinds = search.kinds - it) }
            if (search.spoilers != base.spoilers) {
                out += context.getString(search.spoilers.label) to search.copy(spoilers = base.spoilers)
            }
            if (search.minEntries != null || search.maxEntries != null) {
                out += context.getString(
                    R.string.filter_count_range, context.getString(R.string.stack_search_size), sizeLabel(context, search),
                ) to search.copy(minEntries = null, maxEntries = null)
            }
            if (search.updated != base.updated) {
                out += context.getString(
                    R.string.filter_count_range, context.getString(R.string.stack_search_updated),
                    context.getString(search.updated.label),
                ) to search.copy(updated = base.updated)
            }
            return out
        }

        /** The header's sort icon for [sort], as the stack filter sheet shows its own. */
        fun sortIcon(sort: StackSort): Int = when (sort) {
            StackSort.TRENDING_7D, StackSort.TRENDING_30D -> R.drawable.ic_round_trending_up_24
            StackSort.VOTES -> R.drawable.ic_round_thumb_up_24
            StackSort.SAVES -> R.drawable.ic_round_bookmark_24
            StackSort.UPDATED -> R.drawable.ic_round_history_24
            StackSort.NEWEST -> R.drawable.ic_round_new_releases_24
            StackSort.MOST_SERIES, StackSort.FEWEST_SERIES -> R.drawable.ic_round_format_list_numbered_24
            StackSort.NAME -> R.drawable.ic_sort
        }

        fun newInstance(search: StackSearch, defaults: StackSearch) = StackSearchSheet().apply {
            arguments = Bundle().apply {
                putSerializable(ARG_SEARCH, search)
                putSerializable(ARG_DEFAULTS, defaults)
            }
        }
    }
}
