package ani.dantotsu.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.TextViewCompat
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.databinding.BottomSheetExtensionFilterBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import ani.dantotsu.databinding.ItemExtensionFilterCheckboxBinding
import ani.dantotsu.databinding.ItemExtensionFilterSelectBinding
import ani.dantotsu.databinding.ItemExtensionFilterSeparatorBinding
import ani.dantotsu.databinding.ItemExtensionFilterSortBinding
import ani.dantotsu.databinding.ItemExtensionFilterTextBinding
import ani.dantotsu.databinding.ItemExtensionFilterTristateBinding
import ani.dantotsu.media.FilterSummary
import ani.dantotsu.media.savedfilters.SavedExtensionFilter
import ani.dantotsu.media.savedfilters.SavedFilterEntry
import ani.dantotsu.media.savedfilters.SavedFiltersDialog
import ani.dantotsu.media.savedfilters.SavedFiltersStore
import ani.dantotsu.others.Xpandable
import ani.dantotsu.px
import ani.dantotsu.snackString
import com.google.android.material.color.MaterialColors
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

class ExtensionFilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetExtensionFilterBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val SEARCH_THRESHOLD = 8
        private var pendingFilters: Any? = null
        private var pendingCallback: ((Any) -> Unit)? = null
        private var pendingSourceId: Long = 0L

        fun newInstance(
            filters: Any,
            sourceId: Long,
            onApply: (Any) -> Unit,
        ): ExtensionFilterBottomSheet {
            pendingFilters = filters
            pendingCallback = onApply
            pendingSourceId = sourceId
            return ExtensionFilterBottomSheet()
        }
    }

    private var filters: Any? = null
    private var onApply: ((Any) -> Unit)? = null
    private var sourceId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filters = pendingFilters
        onApply = pendingCallback
        sourceId = pendingSourceId
        pendingFilters = null
        pendingCallback = null
        pendingSourceId = 0L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetExtensionFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // Force the sheet open so the sticky Apply footer is always reachable.
        (view?.parent as? View)?.let {
            BottomSheetBehavior.from(it).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val fl = filters
        if (fl == null) {
            dismiss()
            return
        }
        render(fl)

        binding.extensionFilterApply.setOnClickListener {
            val applied = filters
            if (applied != null) onApply?.invoke(applied)
            dismiss()
        }
        binding.extensionFilterReset.setOnClickListener {
            resetAndRender(fl)
        }
        binding.savedFiltersButton.setOnClickListener { showSavedFiltersDialog(fl) }

        val browseActivity = requireActivity() as ExtensionBrowseActivity
        val activeFilterCount = browseActivity.activeFilterCount()
        binding.manageFiltersButton.visibility = if (activeFilterCount == 0) View.GONE else View.VISIBLE
        binding.manageFiltersCount.visibility = if (activeFilterCount == 0) View.GONE else View.VISIBLE
        binding.manageFiltersCount.text = activeFilterCount.toString()
        binding.manageFiltersButton.setOnClickListener {
            // The popup outlives this sheet, whose controls still show the values it was rendered
            // with — anything cleared in the popup would read as still set here, and go back the
            // moment the sheet was applied. So the sheet goes first.
            dismiss()
            browseActivity.openManageFilters()
        }
    }

    private fun showSavedFiltersDialog(fl: Any) {
        SavedFiltersDialog.show(
            context = requireContext(),
            loadPresets = {
                val defaults = (activity as? ExtensionBrowseActivity)?.defaultFilterStates()
                SavedFiltersStore.loadExtension(sourceId).map {
                    SavedFilterEntry(it.name, SavedFiltersStore.chipsForExtension(it.states, fl, defaults))
                }
            },
            onSaveCurrent = { name ->
                val states = when (fl) {
                    is FilterList -> SavedFiltersStore.snapshotMangaFilters(fl)
                    is AnimeFilterList -> SavedFiltersStore.snapshotAnimeFilters(fl)
                    else -> emptyList()
                }
                SavedFiltersStore.saveExtension(sourceId, SavedExtensionFilter(name, states))
            },
            onApply = { name ->
                val preset = SavedFiltersStore.loadExtension(sourceId)
                    .firstOrNull { it.name == name } ?: return@show
                val ok = when (fl) {
                    is FilterList -> SavedFiltersStore.applyMangaFilters(fl, preset.states)
                    is AnimeFilterList -> SavedFiltersStore.applyAnimeFilters(fl, preset.states)
                    else -> false
                }
                if (!ok) {
                    snackString(getString(ani.dantotsu.R.string.saved_filters_apply_failed))
                    return@show
                }
                render(fl)
                onApply?.invoke(fl)
                dismiss()
            },
            onDelete = { name -> SavedFiltersStore.deleteExtension(sourceId, name) },
            onRename = { oldName, newName ->
                SavedFiltersStore.renameExtension(sourceId, oldName, newName)
            },
        )
    }

    private fun resetAndRender(fl: Any) {
        when (fl) {
            is FilterList -> fl.forEach { resetFilter(it) }
            is AnimeFilterList -> fl.forEach { resetAnimeFilter(it) }
        }
        render(fl)
    }

    @Suppress("UNCHECKED_CAST")
    private fun resetFilter(f: Filter<*>) {
        when (f) {
            is Filter.Select<*> -> (f as Filter<Int>).state = 0
            is Filter.Text -> (f as Filter<String>).state = ""
            is Filter.CheckBox -> (f as Filter<Boolean>).state = false
            is Filter.TriState -> (f as Filter<Int>).state = Filter.TriState.STATE_IGNORE
            is Filter.Sort -> (f as Filter<Filter.Sort.Selection?>).state = null
            is Filter.Group<*> -> (f.state as? List<Filter<*>>)?.forEach { resetFilter(it) }
            else -> Unit
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resetAnimeFilter(f: AnimeFilter<*>) {
        when (f) {
            is AnimeFilter.Select<*> -> (f as AnimeFilter<Int>).state = 0
            is AnimeFilter.Text -> (f as AnimeFilter<String>).state = ""
            is AnimeFilter.CheckBox -> (f as AnimeFilter<Boolean>).state = false
            is AnimeFilter.TriState -> (f as AnimeFilter<Int>).state = AnimeFilter.TriState.STATE_IGNORE
            is AnimeFilter.Sort -> (f as AnimeFilter<AnimeFilter.Sort.Selection?>).state = null
            is AnimeFilter.Group<*> -> (f.state as? List<AnimeFilter<*>>)?.forEach { resetAnimeFilter(it) }
            else -> Unit
        }
    }

    private fun render(fl: Any) {
        binding.extensionFilterContainer.removeAllViews()
        sectionFilters.clear()
        when (fl) {
            is FilterList -> renderSections(fl.list, binding.extensionFilterContainer)
            is AnimeFilterList -> renderAnimeSections(fl.list, binding.extensionFilterContainer)
        }
    }

    private fun renderSections(filters: List<Filter<*>>, root: LinearLayout) {
        // Every non-header/group filter lives inside a collapsible section. A Header
        // starts a new section; filters before the first Header go in an untitled one.
        var current: LinearLayout = createSection(root, getString(ani.dantotsu.R.string.filter))
        filters.forEach { f ->
            when (f) {
                is Filter.Header -> {
                    // Drop the empty placeholder section if nothing landed in it.
                    dropIfEmpty(current)
                    current = createSection(root, f.name)
                }
                is Filter.Group<*> -> {
                    val children = f.state as? List<*> ?: emptyList<Any>()
                    val inner = createSection(root, f.name, searchable = children.size > SEARCH_THRESHOLD)
                    children.forEach { child ->
                        if (child is Filter<*>) {
                            renderFilter(child, inner)
                            track(inner, child)
                        }
                    }
                }
                else -> {
                    renderFilter(f, current)
                    track(current, f)
                }
            }
        }
        dropIfEmpty(current)
    }

    private fun renderAnimeSections(filters: List<AnimeFilter<*>>, root: LinearLayout) {
        var current: LinearLayout = createSection(root, getString(ani.dantotsu.R.string.filter))
        filters.forEach { f ->
            when (f) {
                is AnimeFilter.Header -> {
                    dropIfEmpty(current)
                    current = createSection(root, f.name)
                }
                is AnimeFilter.Group<*> -> {
                    val children = f.state as? List<*> ?: emptyList<Any>()
                    val inner = createSection(root, f.name, searchable = children.size > SEARCH_THRESHOLD)
                    children.forEach { child ->
                        if (child is AnimeFilter<*>) {
                            renderAnimeFilter(child, inner)
                            track(inner, child)
                        }
                    }
                }
                else -> {
                    renderAnimeFilter(f, current)
                    track(current, f)
                }
            }
        }
        dropIfEmpty(current)
    }

    /** The filters rendered into each section's body, for the line its card shows while closed. */
    private val sectionFilters = HashMap<LinearLayout, MutableList<Any>>()

    private fun track(section: LinearLayout, filter: Any) {
        sectionFilters.getOrPut(section) { mutableListOf() }.add(filter)
    }

    /** Whether a filter is away from its default; sorting is not counted, being an order not a filter. */
    private fun isSet(f: Any?): Boolean = when (f) {
        is Filter.Select<*> -> f.state != 0
        is Filter.Text -> f.state.isNotBlank()
        is Filter.CheckBox -> f.state
        is Filter.TriState -> f.state != Filter.TriState.STATE_IGNORE
        is Filter.Group<*> -> (f.state as? List<*>)?.any { isSet(it) } == true
        is AnimeFilter.Select<*> -> f.state != 0
        is AnimeFilter.Text -> f.state.isNotBlank()
        is AnimeFilter.CheckBox -> f.state
        is AnimeFilter.TriState -> f.state != AnimeFilter.TriState.STATE_IGNORE
        is AnimeFilter.Group<*> -> (f.state as? List<*>)?.any { isSet(it) } == true
        else -> false
    }

    /** Removes a section whose body stayed empty, card and all. */
    private fun dropIfEmpty(section: LinearLayout) {
        if (sectionFilters[section].isNullOrEmpty()) (section.parent as? ViewGroup)?.let { card ->
            (card.parent as? ViewGroup)?.removeView(card)
        }
    }

    /**
     * A collapsible card like the other filter sheets' sections. A long one gets a search box at
     * the top of its body, matching its entries by label.
     */
    private fun createSection(root: LinearLayout, title: String, searchable: Boolean = false): LinearLayout {
        val ctx = root.context
        val card = Xpandable(ctx).apply {
            setHeader(title, ani.dantotsu.R.drawable.ic_round_filter_alt_24)
            accordion = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (root.childCount == 0) 8f.px else 12f.px }
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16f.px, 0, 16f.px, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        if (searchable) {
            val search = EditText(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40f.px).apply {
                    marginStart = 16f.px
                    marginEnd = 16f.px
                    topMargin = 4f.px
                    bottomMargin = 4f.px
                }
                setBackgroundResource(ani.dantotsu.R.drawable.bg_filter_number_field)
                setPadding(12f.px, 0, 12f.px, 0)
                setCompoundDrawablesRelativeWithIntrinsicBounds(ani.dantotsu.R.drawable.ic_round_search_24, 0, 0, 0)
                compoundDrawablePadding = 8f.px
                TextViewCompat.setCompoundDrawableTintList(
                    this,
                    ColorStateList.valueOf(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                )
                hint = getString(ani.dantotsu.R.string.search)
                typeface = ResourcesCompat.getFont(ctx, ani.dantotsu.R.font.poppins_bold)
                textSize = 14f
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_DONE
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val query = s?.toString()?.trim()?.lowercase() ?: ""
                        for (i in 0 until content.childCount) {
                            val child = content.getChildAt(i)
                            child.visibility = if (query.isEmpty() || findLabel(child).lowercase().contains(query))
                                View.VISIBLE else View.GONE
                        }
                    }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                })
            }
            card.addView(search)
        }
        card.addView(content)
        root.addView(card)
        card.summarizeWhenClosed {
            FilterSummary.choices(ctx, sectionFilters[content].orEmpty().count { isSet(it) })
        }
        return content
    }

    private fun findLabel(view: View): String {
        if (view is android.widget.TextView) return view.text?.toString() ?: ""
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val label = findLabel(view.getChildAt(i))
                if (label.isNotEmpty()) return label
            }
        }
        return ""
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderFilter(f: Filter<*>, parent: LinearLayout) {
        when (f) {
            is Filter.Header -> {
                // A Header nested inside a Group becomes its own collapsible sub-section.
                createSection(parent, f.name)
            }
            is Filter.Separator -> addSeparator(parent)
            is Filter.Select<*> -> addSelect(
                parent, f.name,
                values = f.values.map { it?.toString() ?: "" },
                current = (f as Filter<Int>).state,
                onChange = { (f as Filter<Int>).state = it }
            )
            is Filter.Text -> addText(
                parent, f.name,
                current = (f as Filter<String>).state,
                onChange = { (f as Filter<String>).state = it }
            )
            is Filter.CheckBox -> addCheckbox(
                parent, f.name,
                current = (f as Filter<Boolean>).state,
                onChange = { (f as Filter<Boolean>).state = it }
            )
            is Filter.TriState -> addTriState(
                parent, f.name,
                get = { (f as Filter<Int>).state },
                set = { (f as Filter<Int>).state = it }
            )
            is Filter.Sort -> {
                val state = (f as Filter<Filter.Sort.Selection?>).state
                addSort(
                    parent, f.name,
                    values = f.values.toList(),
                    currentIndex = state?.index ?: 0,
                    currentAscending = state?.ascending ?: false,
                    onChange = { idx, asc ->
                        (f as Filter<Filter.Sort.Selection?>).state = Filter.Sort.Selection(idx, asc)
                    }
                )
            }
            is Filter.Group<*> -> {
                val children = f.state as? List<*> ?: emptyList<Any>()
                val inner = createSection(parent, f.name, searchable = children.size > SEARCH_THRESHOLD)
                children.forEach { child ->
                    if (child is Filter<*>) renderFilter(child, inner)
                }
            }
            else -> Unit
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderAnimeFilter(f: AnimeFilter<*>, parent: LinearLayout) {
        when (f) {
            is AnimeFilter.Header -> {
                createSection(parent, f.name)
            }
            is AnimeFilter.Separator -> addSeparator(parent)
            is AnimeFilter.Select<*> -> addSelect(
                parent, f.name,
                values = f.values.map { it?.toString() ?: "" },
                current = (f as AnimeFilter<Int>).state,
                onChange = { (f as AnimeFilter<Int>).state = it }
            )
            is AnimeFilter.Text -> addText(
                parent, f.name,
                current = (f as AnimeFilter<String>).state,
                onChange = { (f as AnimeFilter<String>).state = it }
            )
            is AnimeFilter.CheckBox -> addCheckbox(
                parent, f.name,
                current = (f as AnimeFilter<Boolean>).state,
                onChange = { (f as AnimeFilter<Boolean>).state = it }
            )
            is AnimeFilter.TriState -> addTriState(
                parent, f.name,
                get = { (f as AnimeFilter<Int>).state },
                set = { (f as AnimeFilter<Int>).state = it }
            )
            is AnimeFilter.Sort -> {
                val state = (f as AnimeFilter<AnimeFilter.Sort.Selection?>).state
                addSort(
                    parent, f.name,
                    values = f.values.toList(),
                    currentIndex = state?.index ?: 0,
                    currentAscending = state?.ascending ?: false,
                    onChange = { idx, asc ->
                        (f as AnimeFilter<AnimeFilter.Sort.Selection?>).state =
                            AnimeFilter.Sort.Selection(idx, asc)
                    }
                )
            }
            is AnimeFilter.Group<*> -> {
                val children = f.state as? List<*> ?: emptyList<Any>()
                val inner = createSection(parent, f.name, searchable = children.size > SEARCH_THRESHOLD)
                children.forEach { child ->
                    if (child is AnimeFilter<*>) renderAnimeFilter(child, inner)
                }
            }
            else -> Unit
        }
    }

    private fun addSeparator(parent: LinearLayout) {
        val b = ItemExtensionFilterSeparatorBinding.inflate(layoutInflater, parent, false)
        parent.addView(b.root)
    }

    private fun addSelect(
        parent: LinearLayout,
        name: String,
        values: List<String>,
        current: Int,
        onChange: (Int) -> Unit
    ) {
        val b = ItemExtensionFilterSelectBinding.inflate(layoutInflater, parent, false)
        b.filterSelectLayout.hint = name
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, values)
        b.filterSelectDropdown.setAdapter(adapter)
        if (values.isNotEmpty()) {
            val safe = current.coerceIn(0, values.size - 1)
            b.filterSelectDropdown.setText(values[safe], false)
        }
        b.filterSelectDropdown.setOnItemClickListener { _, _, pos, _ -> onChange(pos) }
        parent.addView(b.root)
    }

    private fun addText(
        parent: LinearLayout,
        name: String,
        current: String,
        onChange: (String) -> Unit
    ) {
        val b = ItemExtensionFilterTextBinding.inflate(layoutInflater, parent, false)
        b.filterTextLayout.hint = name
        b.filterTextEdit.setText(current)
        b.filterTextEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { onChange(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        parent.addView(b.root)
    }

    private fun addCheckbox(
        parent: LinearLayout,
        name: String,
        current: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val b = ItemExtensionFilterCheckboxBinding.inflate(layoutInflater, parent, false)
        b.filterCheckbox.text = name
        b.filterCheckbox.isChecked = current
        b.filterCheckbox.setOnCheckedChangeListener { _, v -> onChange(v) }
        parent.addView(b.root)
    }

    private fun addTriState(
        parent: LinearLayout,
        name: String,
        get: () -> Int,
        set: (Int) -> Unit
    ) {
        val b = ItemExtensionFilterTristateBinding.inflate(layoutInflater, parent, false)
        b.filterTriStateLabel.text = name

        fun refresh() {
            when (get()) {
                Filter.TriState.STATE_INCLUDE -> {
                    b.filterTriStateBox.setImageResource(
                        ani.dantotsu.R.drawable.ic_filter_tristate_include_24
                    )
                    b.filterTriStateBox.imageTintList =
                        android.content.res.ColorStateList.valueOf(
                            com.google.android.material.color.MaterialColors.getColor(
                                b.filterTriStateBox,
                                androidx.appcompat.R.attr.colorPrimary
                            )
                        )
                }
                Filter.TriState.STATE_EXCLUDE -> {
                    b.filterTriStateBox.setImageResource(
                        ani.dantotsu.R.drawable.ic_filter_tristate_exclude_24
                    )
                    b.filterTriStateBox.imageTintList =
                        android.content.res.ColorStateList.valueOf(
                            com.google.android.material.color.MaterialColors.getColor(
                                b.filterTriStateBox,
                                androidx.appcompat.R.attr.colorError
                            )
                        )
                }
                else -> {
                    b.filterTriStateBox.setImageResource(
                        ani.dantotsu.R.drawable.ic_filter_tristate_empty_24
                    )
                    b.filterTriStateBox.imageTintList =
                        android.content.res.ColorStateList.valueOf(
                            com.google.android.material.color.MaterialColors.getColor(
                                b.filterTriStateBox,
                                com.google.android.material.R.attr.colorOnSurfaceVariant
                            )
                        )
                }
            }
        }
        refresh()
        b.root.setOnClickListener {
            val next = when (get()) {
                Filter.TriState.STATE_IGNORE -> Filter.TriState.STATE_INCLUDE
                Filter.TriState.STATE_INCLUDE -> Filter.TriState.STATE_EXCLUDE
                else -> Filter.TriState.STATE_IGNORE
            }
            set(next)
            refresh()
        }
        parent.addView(b.root)
    }

    private fun addSort(
        parent: LinearLayout,
        name: String,
        values: List<String>,
        currentIndex: Int,
        currentAscending: Boolean,
        onChange: (index: Int, ascending: Boolean) -> Unit
    ) {
        if (values.isEmpty()) return
        val b = ItemExtensionFilterSortBinding.inflate(layoutInflater, parent, false)
        b.filterSortLayout.hint = name
        var index = currentIndex.coerceIn(0, values.size - 1)
        var ascending = currentAscending

        fun entryLabel(i: Int): String {
            val base = values[i]
            return if (i == index) "$base  ${if (ascending) "↑" else "↓"}" else base
        }

        // A non-filtering ArrayAdapter so that repeatedly showing the dropdown keeps
        // the full option list visible regardless of the editor's current text.
        val adapter = object : ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_list_item_1,
            MutableList(values.size) { entryLabel(it) }
        ) {
            private val noFilter = object : android.widget.Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val items = MutableList(values.size) { entryLabel(it) }
                    val r = FilterResults()
                    r.values = items
                    r.count = items.size
                    return r
                }

                @Suppress("UNCHECKED_CAST")
                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    clear()
                    addAll((results?.values as? List<String>) ?: emptyList())
                    notifyDataSetChanged()
                }
            }

            override fun getFilter(): android.widget.Filter = noFilter
        }
        b.filterSortDropdown.setAdapter(adapter)

        fun refresh() {
            b.filterSortDropdown.setText(entryLabel(index), false)
            adapter.clear()
            adapter.addAll(MutableList(values.size) { entryLabel(it) })
            adapter.notifyDataSetChanged()
        }
        refresh()

        b.filterSortDropdown.setOnItemClickListener { _, _, pos, _ ->
            if (pos == index) {
                ascending = !ascending
            } else {
                index = pos
            }
            refresh()
            onChange(index, ascending)
            // Keep the dropdown open so the user can toggle direction with another tap.
            b.filterSortDropdown.post {
                if (isAdded) b.filterSortDropdown.showDropDown()
            }
        }
        parent.addView(b.root)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
