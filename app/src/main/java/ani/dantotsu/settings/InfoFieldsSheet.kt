package ani.dantotsu.settings

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetInfoFieldsBinding
import ani.dantotsu.databinding.ItemInfoFieldRowBinding
import ani.dantotsu.databinding.ItemInfoPickerRowBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.media.InfoField
import ani.dantotsu.media.InfoFieldLine
import ani.dantotsu.media.InfoSourceMode
import ani.dantotsu.media.InfoTabType
import ani.dantotsu.media.orderedInfoFields
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.statusBarHeight
import ani.dantotsu.util.materialDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Stops this sheet short of the status bar. A sheet reaching it gets the bar's inset as padding on
 * top of its own, which shows the dialog's surface as a second, differently coloured band above
 * the sheet's rounded background; a long list scrolls inside the sheet instead.
 */
internal fun BottomSheetDialogFragment.capSheetHeight() {
    (dialog as? BottomSheetDialog)?.behavior?.maxHeight =
        resources.displayMetrics.heightPixels - statusBarHeight - 32f.px
}

/**
 * The details table of one section: its lines' order, which of them show and, with several sources,
 * how each line uses them. Applies as it goes, like the rest of the tab editor.
 */
class InfoFieldsSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetInfoFieldsBinding? = null
    private val binding get() = _binding!!

    override val opensExpanded = true

    override fun onStart() {
        super.onStart()
        capSheetHeight()
    }

    private val editor get() = activity as? InfoTabEditorActivity
    private val rowIndex get() = requireArguments().getInt(ARG_ROW)

    private lateinit var order: MutableList<InfoField>
    private val hidden = HashSet<String>()
    private val lines = HashMap<String, InfoFieldLine>()
    private var defaultMode = InfoSourceMode.MERGE
    private var multipleSources = false
    private var labels: Map<InfoField, Int> = emptyMap()
    private var providers: Map<InfoField, List<InfoTabType>> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetInfoFieldsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // On the list itself, not around it, so its rows scroll all the way to the bottom edge.
        binding.infoFieldsList.setPadding(0, 0, 0, navBarHeight + 16f.px)
        binding.infoFieldsList.clipToPadding = false
        val row = editor?.rowAt(rowIndex)
        if (row == null) {
            dismiss()
            return
        }
        val available = editor?.fieldsFor(row).orEmpty()
        order = orderedInfoFields(row.fieldOrder).filter { it in available }.toMutableList()
        labels = order.associateWith { editor?.fieldLabel(it) ?: it.labelRes }
        providers = order.associateWith { editor?.providersOf(row, it).orEmpty() }
        hidden.addAll(row.hiddenFields)
        lines.putAll(row.fieldLines)
        defaultMode = row.mode
        multipleSources = row.sources.size > 1
        binding.infoFieldsHint.setText(
            if (multipleSources) R.string.info_tab_fields_hint_merged else R.string.info_tab_fields_hint
        )

        lateinit var helper: ItemTouchHelper
        val adapter = FieldAdapter { helper.startDrag(it) }
        binding.infoFieldsList.layoutManager = LinearLayoutManager(requireContext())
        binding.infoFieldsList.adapter = adapter
        helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                order.add(to, order.removeAt(from))
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                commit()
            }
        }).also { it.attachToRecyclerView(binding.infoFieldsList) }
    }

    private fun commit() {
        editor?.updateFields(rowIndex, order.map { it.key }, hidden.toSet(), lines.toMap())
    }

    private fun modeOf(field: InfoField) = lines[field.key]?.mode ?: defaultMode

    /** The sources [field] uses, in its order. */
    private fun usedBy(field: InfoField): List<InfoTabType> {
        val offered = providers[field].orEmpty()
        return lines[field.key]?.sources?.mapNotNull { InfoTabType.forKey(it) }?.filter { it in offered }
            ?.takeIf { it.isNotEmpty() } ?: offered
    }

    /** How one line uses its sources, in a dialog as the list behind it is a drag list of its own. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showLine(field: InfoField, onChanged: () -> Unit) {
        val context = requireContext()
        val used = usedBy(field)
        val listed = (used + (providers[field].orEmpty() - used.toSet())).toMutableList()
        val ticked = used.toMutableSet()
        var mode = modeOf(field)

        fun save() {
            lines[field.key] = InfoFieldLine(mode, listed.filter { it in ticked }.map { it.key })
            onChanged()
            commit()
        }

        class Holder(val bind: ItemInfoPickerRowBinding) : RecyclerView.ViewHolder(bind.root)
        lateinit var helper: ItemTouchHelper
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        list.adapter = object : RecyclerView.Adapter<Holder>() {
            override fun getItemCount() = listed.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                Holder(ItemInfoPickerRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

            override fun onBindViewHolder(holder: Holder, position: Int) {
                val source = listed[position]
                val bind = holder.bind
                val isOn = source in ticked
                val primary = bind.root.context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
                bind.root.setCardBackgroundColor(SectionCard.fill(bind.root, isOn))
                bind.infoPickerTile.isVisible = true
                bind.infoPickerTile.backgroundTintList =
                    ColorStateList.valueOf(MaterialColors.compositeARGBWithAlpha(primary, 36))
                bind.infoPickerLogo.setImageResource(source.iconRes)
                bind.infoPickerTitle.setText(source.labelRes)
                bind.infoPickerHandle.isVisible = true
                bind.infoPickerHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) helper.startDrag(holder)
                    false
                }
                bind.infoPickerIndicator.removeAllViews()
                bind.infoPickerIndicator.addView(MaterialCheckBox(bind.root.context).apply {
                    isChecked = isOn
                    isClickable = false
                    isFocusable = false
                    minimumWidth = 0
                    minimumHeight = 0
                })
                bind.root.setOnClickListener {
                    // A line uses at least one source: switching it off altogether is the sheet's job.
                    if (isOn && ticked.size == 1) return@setOnClickListener
                    if (isOn) ticked.remove(source) else ticked.add(source)
                    notifyItemChanged(holder.bindingAdapterPosition)
                    save()
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
                listed.add(to, listed.removeAt(from))
                rv.adapter?.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                save()
            }
        }).also { it.attachToRecyclerView(list) }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16f.px, 8f.px, 16f.px, 0)
            addView(list)
        }
        val hint = TextView(context).apply {
            textSize = 12f
            alpha = 0.75f
            setPadding(4f.px, 4f.px, 4f.px, 0)
        }

        fun toggle(titleRes: Int, target: InfoSourceMode, onToggled: () -> Unit): MaterialSwitch {
            val bind = ItemInfoPickerRowBinding.inflate(layoutInflater, content, false)
            bind.root.setCardBackgroundColor(SectionCard.fill(bind.root, false))
            bind.infoPickerTitle.setText(titleRes)
            val switch = MaterialSwitch(context).apply {
                isClickable = false
                isFocusable = false
            }
            bind.infoPickerIndicator.addView(switch)
            bind.root.setOnClickListener {
                // The two exclude each other; with both off, every value is listed.
                mode = if (mode == target) InfoSourceMode.LIST else target
                onToggled()
                save()
            }
            content.addView(bind.root)
            return switch
        }

        lateinit var merging: MaterialSwitch
        lateinit var falling: MaterialSwitch
        fun showMode() {
            merging.isChecked = mode == InfoSourceMode.MERGE
            falling.isChecked = mode == InfoSourceMode.FALLBACK
            hint.setText(
                when (mode) {
                    InfoSourceMode.MERGE -> R.string.info_tab_line_merge_hint
                    InfoSourceMode.FALLBACK -> R.string.info_tab_line_fallback_hint
                    InfoSourceMode.LIST -> R.string.info_tab_line_list_hint
                }
            )
        }
        merging = toggle(R.string.info_tab_merge_mode, InfoSourceMode.MERGE) { showMode() }
        falling = toggle(R.string.info_tab_fallback_mode, InfoSourceMode.FALLBACK) { showMode() }
        content.addView(hint)
        showMode()

        context.materialDialog()
            .setTitle(getString(labels[field] ?: field.labelRes))
            .setView(content)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private inner class FieldAdapter(
        private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    ) : RecyclerView.Adapter<FieldAdapter.VH>() {
        inner class VH(val bind: ItemInfoFieldRowBinding) : RecyclerView.ViewHolder(bind.root)

        override fun getItemCount() = order.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            ItemInfoFieldRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        private fun showProvider(bind: ItemInfoFieldRowBinding, field: InfoField) {
            val used = usedBy(field)
            used.firstOrNull()?.let { bind.infoFieldProviderIcon.setImageResource(it.iconRes) }
            bind.infoFieldProviderName.text = used.singleOrNull()?.let { getString(it.labelRes) }
                ?: getString(
                    when (modeOf(field)) {
                        InfoSourceMode.MERGE -> R.string.info_tab_merged
                        InfoSourceMode.FALLBACK -> R.string.info_tab_fallback
                        InfoSourceMode.LIST -> R.string.info_tab_listed
                    }
                )
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val field = order[position]
            val bind = holder.bind
            val primary = bind.root.context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
            bind.root.setCardBackgroundColor(SectionCard.fill(bind.root, false))
            bind.infoFieldName.setText(labels[field] ?: field.labelRes)
            bind.infoFieldHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
                false
            }

            bind.infoFieldProvider.isVisible = multipleSources
            bind.infoFieldProviderName.isVisible = multipleSources
            if (multipleSources) {
                bind.infoFieldProvider.backgroundTintList =
                    ColorStateList.valueOf(MaterialColors.compositeARGBWithAlpha(primary, 36))
                showProvider(bind, field)
                bind.infoFieldProvider.setOnClickListener {
                    // With one source that can fill it, there is nothing to choose.
                    if (providers[field].orEmpty().size > 1) showLine(field) { showProvider(bind, field) }
                }
            }

            bind.infoFieldSwitch.setOnCheckedChangeListener(null)
            bind.infoFieldSwitch.isChecked = field.key !in hidden
            bind.infoFieldContent.alpha = if (field.key in hidden) 0.5f else 1f
            bind.infoFieldSwitch.setOnCheckedChangeListener { _, checked ->
                if (checked) hidden.remove(field.key) else hidden.add(field.key)
                bind.infoFieldContent.alpha = if (checked) 1f else 0.5f
                commit()
            }
        }
    }

    companion object {
        const val TAG = "infoFields"
        private const val ARG_ROW = "row"

        fun newInstance(row: Int) = InfoFieldsSheet().apply {
            arguments = Bundle().apply { putInt(ARG_ROW, row) }
        }
    }
}
