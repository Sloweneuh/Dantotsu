package ani.dantotsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.databinding.BottomSheetExtensionFilterBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import ani.dantotsu.databinding.ItemExtensionFilterCheckboxBinding
import ani.dantotsu.databinding.ItemExtensionFilterSectionHeaderBinding
import ani.dantotsu.databinding.ItemExtensionFilterSelectBinding
import ani.dantotsu.databinding.ItemExtensionFilterSeparatorBinding
import ani.dantotsu.databinding.ItemExtensionFilterSortBinding
import ani.dantotsu.databinding.ItemExtensionFilterTextBinding
import ani.dantotsu.databinding.ItemExtensionFilterTristateBinding
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

class ExtensionFilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetExtensionFilterBinding? = null
    private val binding get() = _binding!!

    companion object {
        private var pendingFilters: Any? = null
        private var pendingCallback: ((Any) -> Unit)? = null

        fun newInstance(filters: Any, onApply: (Any) -> Unit): ExtensionFilterBottomSheet {
            pendingFilters = filters
            pendingCallback = onApply
            return ExtensionFilterBottomSheet()
        }
    }

    private var filters: Any? = null
    private var onApply: ((Any) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filters = pendingFilters
        onApply = pendingCallback
        pendingFilters = null
        pendingCallback = null
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
        when (fl) {
            is FilterList -> renderSections(fl.list, binding.extensionFilterContainer)
            is AnimeFilterList -> renderAnimeSections(fl.list, binding.extensionFilterContainer)
        }
    }

    private fun renderSections(filters: List<Filter<*>>, root: LinearLayout) {
        // Every non-header/group filter lives inside a collapsible section. A Header
        // starts a new section; filters before the first Header go in an untitled one.
        var current: LinearLayout = createSection(root, getString(ani.dantotsu.R.string.filter))
        var usedCurrent = false
        filters.forEach { f ->
            when (f) {
                is Filter.Header -> {
                    // Drop the empty placeholder section if nothing landed in it.
                    if (!usedCurrent) (current.parent as? ViewGroup)?.let { p ->
                        (p.parent as? ViewGroup)?.removeView(p)
                    }
                    current = createSection(root, f.name)
                    usedCurrent = false
                }
                is Filter.Group<*> -> {
                    val inner = createSection(root, f.name)
                    (f.state as? List<*>)?.forEach { child ->
                        if (child is Filter<*>) renderFilter(child, inner)
                    }
                }
                else -> {
                    renderFilter(f, current)
                    usedCurrent = true
                }
            }
        }
        if (!usedCurrent) (current.parent as? ViewGroup)?.let { p ->
            (p.parent as? ViewGroup)?.removeView(p)
        }
    }

    private fun renderAnimeSections(filters: List<AnimeFilter<*>>, root: LinearLayout) {
        var current: LinearLayout = createSection(root, getString(ani.dantotsu.R.string.filter))
        var usedCurrent = false
        filters.forEach { f ->
            when (f) {
                is AnimeFilter.Header -> {
                    if (!usedCurrent) (current.parent as? ViewGroup)?.let { p ->
                        (p.parent as? ViewGroup)?.removeView(p)
                    }
                    current = createSection(root, f.name)
                    usedCurrent = false
                }
                is AnimeFilter.Group<*> -> {
                    val inner = createSection(root, f.name)
                    (f.state as? List<*>)?.forEach { child ->
                        if (child is AnimeFilter<*>) renderAnimeFilter(child, inner)
                    }
                }
                else -> {
                    renderAnimeFilter(f, current)
                    usedCurrent = true
                }
            }
        }
        if (!usedCurrent) (current.parent as? ViewGroup)?.let { p ->
            (p.parent as? ViewGroup)?.removeView(p)
        }
    }

    private fun createSection(root: LinearLayout, title: String): LinearLayout {
        val ctx = root.context
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val header = ItemExtensionFilterSectionHeaderBinding.inflate(layoutInflater, wrapper, false)
        header.filterSectionTitle.text = title
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        wrapper.addView(header.root)
        wrapper.addView(content)
        root.addView(wrapper)

        // Force collapsed initial state after the views are attached to the tree.
        content.visibility = View.GONE
        header.filterSectionArrow.rotation = 0f

        header.root.setOnClickListener {
            val expanded = content.visibility == View.VISIBLE
            content.visibility = if (expanded) View.GONE else View.VISIBLE
            header.filterSectionArrow.animate()
                .rotation(if (expanded) 0f else 180f)
                .setDuration(180)
                .start()
        }
        return content
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
                val inner = createSection(parent, f.name)
                (f.state as? List<*>)?.forEach { child ->
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
                val inner = createSection(parent, f.name)
                (f.state as? List<*>)?.forEach { child ->
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
                                com.google.android.material.R.attr.colorPrimary
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
                                com.google.android.material.R.attr.colorError
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
