package ani.dantotsu.settings

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetInfoSectionPickerBinding
import ani.dantotsu.databinding.ItemInfoPickerHeaderBinding
import ani.dantotsu.databinding.ItemInfoPickerRowBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.media.InfoSectionKind
import ani.dantotsu.media.InfoSectionMerge
import ani.dantotsu.media.InfoSourceMode
import ani.dantotsu.media.InfoTabLongPress
import ani.dantotsu.media.InfoTabType
import ani.dantotsu.media.label
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.util.materialDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton

/**
 * Choosing what goes on a custom tab: a section, then its sources, in place rather than as a dialog
 * on top of a dialog; or a whole source's sections at once. The data and the changes live on
 * [InfoTabEditorActivity], so the sheet only needs its arguments to come back after a rotation.
 */
class InfoSectionPickerSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetInfoSectionPickerBinding? = null
    private val binding get() = _binding!!
    private val editor get() = activity as? InfoTabEditorActivity

    override val opensExpanded = true

    override fun onStart() {
        super.onStart()
        capSheetHeight()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetInfoSectionPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.infoPickerRows.setPadding(0, 0, 0, navBarHeight)
        binding.infoPickerDone.setOnClickListener { dismiss() }
        (binding.infoPickerDone.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin += navBarHeight
        val args = requireArguments()
        when (args.getString(ARG_MODE)) {
            MODE_COPY -> showCopy()
            MODE_OPTIONS -> showOptions(args.getInt(ARG_ROW))
            MODE_LONG_PRESS -> showLongPress()
            MODE_SOURCES -> {
                val kind = args.getString(ARG_KIND)?.let { InfoSectionKind.forKey(it) }
                if (kind == null) dismiss() else showSources(kind, args.getInt(ARG_ROW), canGoBack = false)
            }

            else -> showSections()
        }
    }

    private fun startPage(title: String, subtitle: String?, onBack: (() -> Unit)?, keepScroll: Boolean = false) {
        binding.infoPickerTitle.text = title
        binding.infoPickerSubtitle.text = subtitle
        binding.infoPickerSubtitle.isVisible = subtitle != null
        binding.infoPickerBack.isVisible = onBack != null
        binding.infoPickerBack.setOnClickListener { onBack?.invoke() }
        // Pages that show it say so afterwards: most end in a button of their own.
        showDone(false)
        val scrolled = binding.infoPickerScroll.scrollY
        binding.infoPickerRows.removeAllViews()
        if (keepScroll) binding.infoPickerScroll.post {
            _binding?.infoPickerScroll?.scrollTo(0, scrolled)
        } else binding.infoPickerScroll.scrollTo(0, 0)
    }

    /** A Done kept in sight below the list, with the rows no longer needing room for the nav bar. */
    private fun showDone(show: Boolean) {
        binding.infoPickerDone.isVisible = show
        binding.infoPickerRows.setPadding(0, 0, 0, if (show) 0 else navBarHeight)
    }

    private fun addGroupTitle(text: String) {
        val bind = ItemInfoPickerHeaderBinding.inflate(layoutInflater, binding.infoPickerRows, false)
        bind.root.text = text
        binding.infoPickerRows.addView(bind.root)
    }

    private fun addRow(): ItemInfoPickerRowBinding {
        val bind = ItemInfoPickerRowBinding.inflate(layoutInflater, binding.infoPickerRows, false)
        bind.root.setCardBackgroundColor(SectionCard.fill(bind.root, false))
        binding.infoPickerRows.addView(bind.root)
        return bind
    }

    private fun showLogo(bind: ItemInfoPickerRowBinding, iconRes: Int) {
        val primary = requireContext().getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        bind.infoPickerTile.isVisible = true
        bind.infoPickerTile.backgroundTintList =
            ColorStateList.valueOf(MaterialColors.compositeARGBWithAlpha(primary, 36))
        bind.infoPickerLogo.setImageResource(iconRes)
    }

    // ---- sections --------------------------------------------------------------------------------

    /** Stays open after each one, as a tab is usually several sections. */
    private fun showSections(keepScroll: Boolean = false) {
        val host = editor ?: return
        startPage(getString(R.string.info_tab_add_section), null, null, keepScroll)
        val available = InfoSectionKind.entries.filter { host.freeSources(it).isNotEmpty() }
        val used = InfoSectionKind.entries.filter {
            it !in available && host.usableSources(it).isNotEmpty()
        }
        val blocked = InfoSectionKind.entries.filter {
            host.sourcesOf(it).isNotEmpty() && host.usableSources(it).isEmpty()
        }
        val missing = InfoSectionKind.entries - available.toSet() - used.toSet() - blocked.toSet()

        available.forEach { kind ->
            val free = host.freeSources(kind)
            val bind = sectionRow(kind)
            host.showSourceIcons(bind.infoPickerLogos, kind, free)
            bind.infoPickerTrailing.isVisible = true
            bind.infoPickerTrailing.setImageResource(
                if (free.size > 1) R.drawable.ic_round_arrow_forward_ios_24
                else R.drawable.ic_round_add_tinted_24
            )
            bind.root.setOnClickListener {
                if (free.size > 1) showSources(kind, NEW_ROW, canGoBack = true)
                else {
                    host.applySources(kind, NEW_ROW, free)
                    showSections(keepScroll = true)
                }
            }
        }
        showDone(true)
        // Listed rather than left out: not finding one here reads as the app having lost it.
        if (used.isNotEmpty()) {
            addGroupTitle(getString(R.string.info_tab_section_used))
            used.forEach { sectionRow(it).root.alpha = 0.5f }
        }
        if (blocked.isNotEmpty()) {
            addGroupTitle(getString(R.string.info_tab_section_blocked))
            blocked.forEach { kind ->
                val bind = sectionRow(kind)
                bind.root.alpha = 0.5f
                // "MyAnimeList: login required", per source: each can have its own reason.
                bind.infoPickerDetail.text = getString(kind.descriptionRes) + "\n" +
                        host.sourcesOf(kind).joinToString("\n") { source ->
                            val reason = host.unavailableReason(source, kind)
                            "${getString(source.labelRes)}: ${reason?.let { getString(it) }.orEmpty()}"
                        }
            }
        }
        if (missing.isNotEmpty()) {
            addGroupTitle(getString(R.string.info_tab_section_unavailable, host.contextLabel()))
            missing.forEach { sectionRow(it).root.alpha = 0.5f }
        }
    }

    private fun sectionRow(kind: InfoSectionKind): ItemInfoPickerRowBinding {
        val host = editor
        val bind = addRow()
        bind.infoPickerTitle.text =
            kind.label(requireContext(), host?.sourcesOf(kind)?.singleOrNull())
        bind.infoPickerDetail.isVisible = true
        bind.infoPickerDetail.setText(kind.descriptionRes)
        return bind
    }

    // ---- sources ---------------------------------------------------------------------------------

    /**
     * One source by default; several after asking for them, in one of three modes: merged, in
     * fallback order, or with both subtoggles off, one after another. [row] is [NEW_ROW] when adding.
     */
    private fun showSources(kind: InfoSectionKind, row: Int, canGoBack: Boolean) {
        val host = editor ?: return
        val existing = host.rowAt(row)
        val choices = if (existing != null) host.pickerSources(existing) else host.freeSources(kind)
        val canMerge = kind.merge != InfoSectionMerge.STACK
        // Details decide all this per line, in their own sheet.
        val perLine = kind.merge == InfoSectionMerge.FIELDS
        val listed = ArrayList<InfoTabType>()
        if (existing != null) listed.addAll(existing.sources)
        else choices.firstOrNull()?.let { listed.add(it) }
        var multiple = listed.size > 1
        var mode = existing?.mode ?: InfoSourceMode.MERGE
        if (mode == InfoSourceMode.MERGE && !canMerge) mode = InfoSourceMode.LIST

        lateinit var render: () -> Unit

        fun bindSource(bind: ItemInfoPickerRowBinding, source: InfoTabType) {
            val isOn = source in listed
            showLogo(bind, source.iconRes)
            bind.infoPickerTitle.setText(source.labelRes)
            host.unavailableReason(source, kind)?.let { reason ->
                bind.infoPickerDetail.isVisible = true
                bind.infoPickerDetail.setText(reason)
                bind.infoPickerDetail.setTextColor(
                    requireContext().getThemeColor(androidx.appcompat.R.attr.colorError)
                )
            }
            bind.root.setCardBackgroundColor(SectionCard.fill(bind.root, isOn))
            // Only showing the state: the whole card is what takes the tap.
            val indicator: CompoundButton =
                if (multiple) MaterialCheckBox(requireContext()) else MaterialRadioButton(requireContext())
            indicator.isChecked = isOn
            indicator.isClickable = false
            indicator.isFocusable = false
            indicator.minimumWidth = 0
            indicator.minimumHeight = 0
            bind.infoPickerIndicator.removeAllViews()
            bind.infoPickerIndicator.addView(indicator)
            bind.root.setOnClickListener {
                if (multiple) {
                    if (!listed.remove(source)) listed.add(source)
                } else {
                    listed.clear()
                    listed.add(source)
                }
                render()
            }
        }

        render = {
            startPage(
                kind.label(requireContext(), null),
                getString(kind.descriptionRes),
                if (canGoBack) ({ showSections() }) else null,
            )
            val ordering = multiple && mode == InfoSourceMode.FALLBACK && !perLine
            if (ordering) addOrderedSources(listed) { bind, source -> bindSource(bind, source) }
            choices.filter { !ordering || it !in listed }.forEach { source ->
                val bind = addRow()
                bindSource(bind, source)
                // Keeps the logos in line with the draggable rows above.
                if (ordering) bind.infoPickerHandle.visibility = View.INVISIBLE
            }

            val multipleRow = toggleRow(
                R.string.info_tab_multiple_sources,
                detail = when {
                    !multiple -> null
                    perLine -> R.string.info_tab_multiple_details_hint
                    mode == InfoSourceMode.LIST -> R.string.info_tab_merge_hint_stack
                    else -> null
                },
                checked = multiple,
            ) {
                multiple = !multiple
                if (!multiple) {
                    val kept = listed.firstOrNull()
                    listed.clear()
                    kept?.let { listed.add(it) }
                }
                render()
            }
            (multipleRow.root.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                resources.getDimensionPixelSize(R.dimen.info_picker_group_gap)

            if (multiple && !perLine) {
                val merging = mode == InfoSourceMode.MERGE
                toggleRow(
                    R.string.info_tab_merge_mode,
                    detail = if (canMerge) mergeHint(kind.merge).takeIf { merging }
                    else R.string.info_tab_merge_unavailable,
                    checked = merging,
                    enabled = canMerge,
                    nested = true,
                ) {
                    mode = if (merging) InfoSourceMode.LIST else InfoSourceMode.MERGE
                    render()
                }
                val falling = mode == InfoSourceMode.FALLBACK
                toggleRow(
                    R.string.info_tab_fallback_mode,
                    detail = R.string.info_tab_fallback_hint.takeIf { falling },
                    checked = falling,
                    nested = true,
                ) {
                    mode = if (falling) InfoSourceMode.LIST else InfoSourceMode.FALLBACK
                    render()
                }
            }

            binding.infoPickerRows.addView(
                binding.infoPickerConfirmTemplate(
                    if (existing != null) R.string.save else R.string.info_tab_add_section,
                    listed.isNotEmpty(),
                ) {
                    val picked = if (ordering) listed.toList() else choices.filter { it in listed }
                    host.applySources(kind, row, picked, if (multiple) mode else InfoSourceMode.MERGE)
                    if (canGoBack) showSections() else dismiss()
                }
            )
        }
        render()
    }

    private fun toggleRow(
        titleRes: Int,
        detail: Int?,
        checked: Boolean,
        enabled: Boolean = true,
        nested: Boolean = false,
        onClick: () -> Unit,
    ): ItemInfoPickerRowBinding {
        val bind = addRow()
        bind.infoPickerTitle.setText(titleRes)
        bind.infoPickerDetail.isVisible = detail != null
        detail?.let { bind.infoPickerDetail.setText(it) }
        val toggle = MaterialSwitch(requireContext())
        toggle.isChecked = checked
        toggle.isClickable = false
        toggle.isFocusable = false
        bind.infoPickerIndicator.addView(toggle)
        if (nested) (bind.root.layoutParams as ViewGroup.MarginLayoutParams).apply {
            marginStart = 16f.px
            // Otherwise the parent measures the row without it, and the row is cut off at the end.
            resolveLayoutDirection(binding.infoPickerRows.layoutDirection)
        }
        if (enabled) bind.root.setOnClickListener { onClick() } else bind.root.alpha = 0.5f
        return bind
    }

    /** The chosen sources of a fallback section, dragged into the order they're tried in. */
    @SuppressLint("ClickableViewAccessibility")
    private fun addOrderedSources(
        order: MutableList<InfoTabType>,
        bindRow: (ItemInfoPickerRowBinding, InfoTabType) -> Unit,
    ) {
        class Holder(val bind: ItemInfoPickerRowBinding) : RecyclerView.ViewHolder(bind.root)
        lateinit var helper: ItemTouchHelper
        val list = RecyclerView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutManager = LinearLayoutManager(requireContext())
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        list.adapter = object : RecyclerView.Adapter<Holder>() {
            override fun getItemCount() = order.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                Holder(ItemInfoPickerRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

            override fun onBindViewHolder(holder: Holder, position: Int) {
                bindRow(holder.bind, order[position])
                holder.bind.infoPickerHandle.isVisible = true
                holder.bind.infoPickerHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) helper.startDrag(holder)
                    false
                }
            }
        }
        helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                order.add(to, order.removeAt(from))
                rv.adapter?.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }).also { it.attachToRecyclerView(list) }
        binding.infoPickerRows.addView(list)
    }

    private fun BottomSheetInfoSectionPickerBinding.infoPickerConfirmTemplate(
        textRes: Int, enabled: Boolean, onClick: () -> Unit,
    ): View {
        val button = layoutInflater.inflate(R.layout.item_info_picker_confirm, infoPickerRows, false)
                as com.google.android.material.button.MaterialButton
        button.setText(textRes)
        button.isEnabled = enabled
        button.setOnClickListener { onClick() }
        return button
    }

    private fun mergeHint(merge: InfoSectionMerge) = when (merge) {
        InfoSectionMerge.STACK -> R.string.info_tab_merge_hint_stack
        InfoSectionMerge.CHIPS -> R.string.info_tab_merge_hint_chips
        InfoSectionMerge.MEDIA -> R.string.info_tab_merge_hint_media
        InfoSectionMerge.FIELDS -> R.string.info_tab_merge_hint_fields
        InfoSectionMerge.ROW -> R.string.info_tab_merge_hint_row
        InfoSectionMerge.LIST -> R.string.info_tab_merge_hint_list
        InfoSectionMerge.LINES -> R.string.info_tab_merge_hint_lines
    }

    // ---- copying a whole tab ---------------------------------------------------------------------

    private fun showCopy() {
        val host = editor ?: return
        startPage(getString(R.string.info_tab_copy_from), null, null)

        fun choice(source: InfoTabEditorActivity.CopySource) {
            val count = source.rows.size
            val bind = addRow()
            showLogo(bind, source.iconRes)
            bind.infoPickerTitle.text = source.name
            bind.infoPickerDetail.isVisible = true
            if (count == 0) {
                bind.infoPickerDetail.setText(source.emptyReason ?: R.string.info_tab_section_used)
                bind.root.alpha = 0.5f
            } else {
                bind.infoPickerDetail.text =
                    resources.getQuantityString(R.plurals.info_tab_section_count, count, count)
                bind.root.setOnClickListener {
                    host.copyFrom(source)
                    dismiss()
                }
            }
        }
        addGroupTitle(getString(R.string.info_tab_presets))
        host.presets().forEach { choice(it) }
        host.copyChoices().forEach { (context, tabs) ->
            addGroupTitle(context.label(requireContext()))
            tabs.forEach { choice(it) }
        }
    }

    // ---- a section's options ---------------------------------------------------------------------

    private fun showOptions(index: Int) {
        val host = editor ?: return
        val row = host.rowAt(index) ?: return dismiss()
        val usual = row.kind.label(requireContext(), row.sources.singleOrNull())
        startPage(row.title ?: usual, getString(row.kind.descriptionRes), null, keepScroll = true)

        val nameRow = addRow()
        nameRow.infoPickerTitle.setText(R.string.info_section_name)
        nameRow.infoPickerDetail.isVisible = true
        nameRow.infoPickerDetail.text = row.title ?: usual
        nameRow.infoPickerTrailing.isVisible = true
        nameRow.infoPickerTrailing.setImageResource(R.drawable.ic_round_edit_outline_24)
        nameRow.root.setOnClickListener { askName(index, usual) }

        toggleRow(
            R.string.info_section_start_collapsed,
            detail = R.string.info_section_start_collapsed_hint,
            checked = row.startCollapsed,
        ) {
            host.updateOptions(index, row.title, !row.startCollapsed, row.itemLimit)
            showOptions(index)
        }

        if (row.kind.hasItems) {
            addGroupTitle(getString(R.string.info_section_limit))
            listOf(null, 3, 5, 10).forEach { limit ->
                val bind = addRow()
                val isOn = row.itemLimit == limit
                bind.infoPickerTitle.text =
                    limit?.toString() ?: getString(R.string.info_section_limit_all)
                bind.root.setCardBackgroundColor(SectionCard.fill(bind.root, isOn))
                val indicator = MaterialRadioButton(requireContext())
                indicator.isChecked = isOn
                indicator.isClickable = false
                indicator.isFocusable = false
                indicator.minimumWidth = 0
                indicator.minimumHeight = 0
                bind.infoPickerIndicator.addView(indicator)
                bind.root.setOnClickListener {
                    host.updateOptions(index, row.title, row.startCollapsed, limit)
                    showOptions(index)
                }
            }
        }
        showDone(true)
    }

    private fun askName(index: Int, usual: String) {
        val host = editor ?: return
        val row = host.rowAt(index) ?: return
        val input = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            setText(row.title)
            hint = usual
            isSingleLine = true
        }
        val frame = android.widget.FrameLayout(requireContext()).apply {
            setPadding(24f.px, 8f.px, 24f.px, 0)
            addView(input)
        }
        requireContext().materialDialog()
            .setTitle(R.string.info_section_name)
            .setView(frame)
            .setPositiveButton(R.string.save) { _, _ ->
                // Empty, or the usual name typed back in, is no name of its own.
                val name = input.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != usual }
                host.updateOptions(index, name, row.startCollapsed, row.itemLimit)
                if (_binding != null) showOptions(index)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- long press ------------------------------------------------------------------------------

    private fun showLongPress() {
        val host = editor ?: return
        startPage(getString(R.string.info_tab_long_press_title), null, null)

        fun option(action: String, iconRes: Int, label: String) {
            val bind = addRow()
            val isOn = host.longPress == action
            showLogo(bind, iconRes)
            bind.infoPickerTitle.text = label
            bind.root.setCardBackgroundColor(SectionCard.fill(bind.root, isOn))
            val indicator = MaterialRadioButton(requireContext())
            indicator.isChecked = isOn
            indicator.isClickable = false
            indicator.isFocusable = false
            indicator.minimumWidth = 0
            indicator.minimumHeight = 0
            bind.infoPickerIndicator.addView(indicator)
            bind.root.setOnClickListener {
                host.onLongPressPicked(action)
                dismiss()
            }
        }
        option(InfoTabLongPress.NAME, R.drawable.ic_round_label_outline_24, getString(R.string.info_tab_long_press_name))
        option(InfoTabLongPress.EDIT, R.drawable.ic_round_edit_outline_24, getString(R.string.info_tab_long_press_edit))
        // Not a source that's turned off, unless it's what this tab is already set to.
        host.copySources().filter { it.fetchEnabled || host.longPress == it.key }.forEach { source ->
            option(
                source.key, source.iconRes,
                getString(R.string.info_tab_long_press_open, getString(source.labelRes)),
            )
        }
        option(InfoTabLongPress.NONE, R.drawable.ic_round_close_24, getString(R.string.info_tab_long_press_none))
    }

    companion object {
        const val TAG = "infoSectionPicker"
        private const val ARG_MODE = "mode"
        private const val ARG_KIND = "kind"
        private const val ARG_ROW = "row"
        private const val MODE_ADD = "add"
        private const val MODE_SOURCES = "sources"
        private const val MODE_COPY = "copy"
        private const val MODE_LONG_PRESS = "long_press"
        private const val MODE_OPTIONS = "options"

        /** In place of a row index: the section is being added. */
        const val NEW_ROW = -1

        private fun with(mode: String, extra: Bundle.() -> Unit = {}) = InfoSectionPickerSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_MODE, mode)
                extra()
            }
        }

        fun forAdding() = with(MODE_ADD)
        fun forCopying() = with(MODE_COPY)
        fun forLongPress() = with(MODE_LONG_PRESS)
        fun forOptions(row: Int) = with(MODE_OPTIONS) { putInt(ARG_ROW, row) }
        fun forSources(kind: InfoSectionKind, row: Int) = with(MODE_SOURCES) {
            putString(ARG_KIND, kind.key)
            putInt(ARG_ROW, row)
        }
    }
}
