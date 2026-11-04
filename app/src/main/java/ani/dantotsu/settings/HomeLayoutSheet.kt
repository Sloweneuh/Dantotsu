package ani.dantotsu.settings

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetHomeLayoutBinding
import ani.dantotsu.databinding.ItemInfoTabChoiceBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.color.MaterialColors

/**
 * Which sections Home shows, and in what order. Commits on Save rather than as it goes, unlike the
 * info tab screens, because applying it restarts the settings page behind the sheet.
 */
class HomeLayoutSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetHomeLayoutBinding? = null
    private val binding get() = _binding!!

    override val opensExpanded = true

    override fun onStart() {
        super.onStart()
        capSheetHeight()
    }

    private class Row(val id: Int, val name: String, var visible: Boolean)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetHomeLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.homeLayoutContent.setPadding(0, 0, 0, navBarHeight)

        val views = resources.getStringArray(R.array.home_layouts)
        val visibility = PrefManager.getVal<List<Boolean>>(PrefName.HomeLayout)
        val savedOrder = PrefManager.getVal<List<Int>>(PrefName.HomeLayoutOrder)
        // Older preferences hold fewer entries than there are sections; those sections show.
        val order = if (savedOrder.size != views.size) views.indices.toList() else savedOrder
        val rows = order.map { Row(it, views[it], visibility.getOrNull(it) ?: true) }.toMutableList()

        lateinit var touchHelper: ItemTouchHelper
        val adapter = RowAdapter(rows) { touchHelper.startDrag(it) }
        binding.homeLayoutRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.homeLayoutRecycler.adapter = adapter
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                rows.add(to, rows.removeAt(from))
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) lift(vh?.itemView, true)
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                lift(vh.itemView, false)
            }
        }).also { it.attachToRecyclerView(binding.homeLayoutRecycler) }

        binding.homeLayoutSave.setOnClickListener {
            PrefManager.setVal(PrefName.HomeLayoutOrder, rows.map { it.id })
            PrefManager.setVal(
                PrefName.HomeLayout,
                MutableList(views.size) { i -> rows.find { it.id == i }?.visible ?: true }
            )
            dismiss()
            (activity as? SettingsAppearanceActivity)?.onHomeLayoutSaved()
        }
    }

    private fun lift(view: View?, lifted: Boolean) {
        view ?: return
        view.animate().translationZ(if (lifted) 8f.px.toFloat() else 0f)
            .scaleX(if (lifted) 1.02f else 1f).scaleY(if (lifted) 1.02f else 1f)
            .setDuration(120).start()
    }

    /** Positions in R.array.home_layouts, whose order the stored preferences are tied to. */
    private fun iconFor(id: Int) = when (id) {
        0 -> R.drawable.ic_round_play_circle_24
        1, 5 -> R.drawable.ic_round_favorite_24
        2, 6 -> R.drawable.ic_round_bookmark_24
        3 -> R.drawable.ic_round_new_releases_24
        4 -> R.drawable.ic_round_import_contacts_24
        7 -> R.drawable.ic_round_auto_awesome_24
        8 -> R.drawable.ic_round_group_outline_24
        else -> R.drawable.ic_round_grid_view_24
    }

    private inner class RowAdapter(
        private val rows: List<Row>,
        private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    ) : RecyclerView.Adapter<RowAdapter.VH>() {
        inner class VH(val bind: ItemInfoTabChoiceBinding) : RecyclerView.ViewHolder(bind.root)

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            ItemInfoTabChoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            val bind = holder.bind
            val primary = bind.root.context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
            bind.root.setCardBackgroundColor(SectionCard.fill(bind.root, false))
            bind.infoTabIconTile.backgroundTintList =
                ColorStateList.valueOf(MaterialColors.compositeARGBWithAlpha(primary, 36))
            bind.infoTabIcon.setImageResource(iconFor(row.id))
            bind.infoTabIcon.imageTintList = ColorStateList.valueOf(primary)
            bind.infoTabText.text = row.name
            bind.infoTabHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
                false
            }
            bind.infoTabSwitch.setOnCheckedChangeListener(null)
            bind.infoTabSwitch.isChecked = row.visible
            bind.infoTabSwitch.setOnCheckedChangeListener { _, checked -> row.visible = checked }
        }
    }

    companion object {
        const val TAG = "homeLayout"
    }
}
