package ani.dantotsu.settings

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetInfoFieldsBinding
import ani.dantotsu.databinding.ItemInfoFieldRowBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.media.InfoField
import ani.dantotsu.media.InfoTabType
import ani.dantotsu.media.orderedInfoFields
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.statusBarHeight
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors

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
 * The details table of one section: its lines' order, which of them show and, when several sources
 * are merged, which source fills each. Applies as it goes, like the rest of the tab editor.
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
    private val chosen = HashMap<String, String>()
    private var sources: List<InfoTabType> = emptyList()

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
        hidden.addAll(row.hiddenFields)
        chosen.putAll(row.fieldSources)
        sources = row.sources.takeIf { it.size > 1 }.orEmpty()
        binding.infoFieldsHint.setText(
            if (sources.isEmpty()) R.string.info_tab_fields_hint else R.string.info_tab_fields_hint_merged
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
        editor?.updateFields(rowIndex, order.map { it.key }, hidden.toSet(), chosen.toMap())
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
            val source = chosen[field.key]?.let { key -> sources.find { it.key == key } }
            bind.infoFieldProviderIcon.setImageResource(
                source?.iconRes ?: R.drawable.ic_round_auto_awesome_24
            )
            bind.infoFieldProviderName.text = getString(
                source?.labelRes ?: R.string.info_tab_field_auto
            )
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val field = order[position]
            val bind = holder.bind
            val primary = bind.root.context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
            bind.root.setCardBackgroundColor(SectionCard.fill(bind.root, false))
            bind.infoFieldName.setText(field.labelRes)
            bind.infoFieldHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
                false
            }

            val merged = sources.isNotEmpty()
            bind.infoFieldProvider.isVisible = merged
            bind.infoFieldProviderName.isVisible = merged
            if (merged) {
                bind.infoFieldProvider.backgroundTintList =
                    ColorStateList.valueOf(MaterialColors.compositeARGBWithAlpha(primary, 36))
                showProvider(bind, field)
                bind.infoFieldProvider.setOnClickListener { anchor ->
                    val menu = PopupMenu(
                        ContextThemeWrapper(anchor.context, R.style.ThemeOverlay_Dantotsu_InfoPopup), anchor
                    )
                    menu.menu.add(0, 0, 0, R.string.info_tab_field_auto)
                    sources.forEachIndexed { i, source -> menu.menu.add(0, i + 1, i + 1, source.labelRes) }
                    menu.menu.setGroupCheckable(0, true, true)
                    val current = sources.indexOfFirst { it.key == chosen[field.key] } + 1
                    menu.menu.findItem(current)?.isChecked = true
                    menu.setOnMenuItemClickListener { item ->
                        val source = sources.getOrNull(item.itemId - 1)
                        if (source == null) chosen.remove(field.key) else chosen[field.key] = source.key
                        showProvider(bind, field)
                        commit()
                        true
                    }
                    menu.show()
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
