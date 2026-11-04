package ani.dantotsu.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.core.view.isVisible
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetInfoSectionPickerBinding
import ani.dantotsu.databinding.ItemInfoPickerHeaderBinding
import ani.dantotsu.databinding.ItemInfoPickerRowBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.media.InfoSectionKind
import ani.dantotsu.media.InfoSectionMerge
import ani.dantotsu.media.InfoTabLongPress
import ani.dantotsu.media.InfoTabType
import ani.dantotsu.navBarHeight
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
        val args = requireArguments()
        when (args.getString(ARG_MODE)) {
            MODE_COPY -> showCopy()
            MODE_LONG_PRESS -> showLongPress()
            MODE_SOURCES -> {
                val kind = args.getString(ARG_KIND)?.let { InfoSectionKind.forKey(it) }
                if (kind == null) dismiss() else showSources(kind, args.getInt(ARG_ROW), canGoBack = false)
            }

            else -> showSections()
        }
    }

    private fun startPage(title: String, subtitle: String?, onBack: (() -> Unit)?) {
        binding.infoPickerTitle.text = title
        binding.infoPickerSubtitle.text = subtitle
        binding.infoPickerSubtitle.isVisible = subtitle != null
        binding.infoPickerBack.isVisible = onBack != null
        binding.infoPickerBack.setOnClickListener { onBack?.invoke() }
        binding.infoPickerRows.removeAllViews()
        binding.infoPickerScroll.scrollTo(0, 0)
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

    private fun showSections() {
        val host = editor ?: return
        startPage(getString(R.string.info_tab_add_section), null, null)
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
                    dismiss()
                }
            }
        }
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

    /** One source by default; several only after asking to merge. [row] is [NEW_ROW] when adding. */
    private fun showSources(kind: InfoSectionKind, row: Int, canGoBack: Boolean) {
        val host = editor ?: return
        val existing = host.rowAt(row)
        val choices = if (existing != null) host.pickerSources(existing) else host.freeSources(kind)
        val selected = LinkedHashSet<InfoTabType>()
        if (existing != null) selected.addAll(existing.sources)
        else choices.firstOrNull()?.let { selected.add(it) }
        var merge = selected.size > 1

        fun render() {
            startPage(
                kind.label(requireContext(), null),
                getString(kind.descriptionRes),
                if (canGoBack) ({ showSections() }) else null,
            )
            choices.forEach { source ->
                val bind = addRow()
                val isOn = source in selected
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
                    if (merge) MaterialCheckBox(requireContext()) else MaterialRadioButton(requireContext())
                indicator.isChecked = isOn
                indicator.isClickable = false
                indicator.isFocusable = false
                indicator.minimumWidth = 0
                indicator.minimumHeight = 0
                bind.infoPickerIndicator.addView(indicator)
                bind.root.setOnClickListener {
                    if (merge) {
                        if (!selected.remove(source)) selected.add(source)
                    } else {
                        selected.clear()
                        selected.add(source)
                    }
                    render()
                }
            }

            val mergeRow = addRow()
            mergeRow.infoPickerTitle.setText(R.string.info_tab_merge_sources)
            mergeRow.infoPickerDetail.isVisible = merge
            mergeRow.infoPickerDetail.setText(mergeHint(kind.merge))
            val toggle = MaterialSwitch(requireContext())
            toggle.isChecked = merge
            toggle.isClickable = false
            toggle.isFocusable = false
            mergeRow.infoPickerIndicator.addView(toggle)
            (mergeRow.root.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                resources.getDimensionPixelSize(R.dimen.info_picker_group_gap)
            mergeRow.root.setOnClickListener {
                merge = !merge
                if (!merge) selected.firstOrNull()?.let {
                    selected.clear()
                    selected.add(it)
                }
                render()
            }

            binding.infoPickerRows.addView(
                binding.infoPickerConfirmTemplate(
                    if (existing != null) R.string.save else R.string.info_tab_add_section,
                    selected.isNotEmpty(),
                ) {
                    host.applySources(kind, row, choices.filter { it in selected })
                    dismiss()
                }
            )
        }
        render()
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
    }

    // ---- copying a whole tab ---------------------------------------------------------------------

    private fun showCopy() {
        val host = editor ?: return
        startPage(getString(R.string.info_tab_copy_from), null, null)
        host.copySources().forEach { source ->
            val count = host.copyableSections(source).size
            val bind = addRow()
            showLogo(bind, source.iconRes)
            bind.infoPickerTitle.setText(source.labelRes)
            bind.infoPickerDetail.isVisible = true
            if (count == 0) {
                // Nothing of its own it could add: either it's all here, or none of it can show.
                val sections = host.sectionsFrom(source)
                val reason = sections.takeIf { all ->
                    all.none { host.unavailableReason(source, it) == null }
                }?.firstNotNullOfOrNull { host.unavailableReason(source, it) }
                bind.infoPickerDetail.setText(reason ?: R.string.info_tab_section_used)
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
        host.copySources().forEach { source ->
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
        fun forSources(kind: InfoSectionKind, row: Int) = with(MODE_SOURCES) {
            putString(ARG_KIND, kind.key)
            putInt(ARG_ROW, row)
        }
    }
}
