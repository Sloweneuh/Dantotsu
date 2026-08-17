package ani.dantotsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetInfoTabOrderBinding
import ani.dantotsu.media.InfoTabContext
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.saving.PrefManager
import com.google.android.material.tabs.TabLayout

/**
 * Reordering and hiding of the info tabs, for every media context at once.
 *
 * A selector along the top switches which context's list is shown; each is a full-width,
 * drag-to-reorder list so the touch targets stay comfortable however many tabs a context has. All
 * the lists are built up front, so switching the selector does not throw away edits made in
 * another, and everything commits together on OK.
 *
 * A bottom sheet rather than a dialog: with novels added there are five contexts, and a dialog's
 * width left their names squeezed into a tab strip too narrow to read.
 *
 * The checkbox only controls whether a tab appears — it does not affect whether the underlying
 * connection fetches (see [ani.dantotsu.media.InfoTabType.fetchEnabled]). Connections switched off
 * are listed but inert, which is the only place the two controls meet: both have to agree before a
 * tab shows, so a disabled connection explains itself here rather than leaving a tick that does
 * nothing.
 */
class InfoTabOrderBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetInfoTabOrderBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetInfoTabOrderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.infoTabButtons.setPadding(0, 0, 0, navBarHeight)
        binding.infoTabRecycler.layoutManager = LinearLayoutManager(requireContext())

        val sections = listOf(
            InfoTabContext.ANILIST_ANIME to getString(R.string.anime),
            InfoTabContext.ANILIST_MANGA to getString(R.string.manga),
            InfoTabContext.ANILIST_NOVEL to getString(R.string.novels),
            InfoTabContext.MANGAUPDATES_MANGA to
                    getString(R.string.mangaupdates) + " · " + getString(R.string.manga),
            InfoTabContext.MANGAUPDATES_NOVEL to
                    getString(R.string.mangaupdates) + " · " + getString(R.string.novels),
        )
        val adapters = sections.associate { (context, _) -> context to buildAdapter(context) }

        var touchHelper: ItemTouchHelper? = null
        fun showSection(context: InfoTabContext) {
            touchHelper?.attachToRecyclerView(null)
            val adapter = adapters.getValue(context)
            binding.infoTabRecycler.adapter = adapter
            touchHelper = attachReorderTouchHelper(binding.infoTabRecycler, adapter)
        }

        sections.forEach { (_, label) ->
            binding.infoTabContextTabs.addTab(binding.infoTabContextTabs.newTab().setText(label))
        }
        binding.infoTabContextTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showSection(sections[tab.position].first)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        showSection(sections.first().first)

        binding.infoTabCancel.setOnClickListener { dismiss() }
        binding.infoTabOk.setOnClickListener {
            adapters.forEach { (context, adapter) -> save(context, adapter) }
            dismiss()
        }
    }

    /**
     * Builds one context's tabs, in saved order.
     *
     * Tabs whose connection switch is off are listed too, sorted to the bottom and drawn inert.
     * They used to be dropped, which read as the list being incomplete — the one tab you came here
     * to find simply absent, with the switch that removed it two screens away and no hint of the
     * connection. It also lost their saved visibility: [save] defaults anything missing from the
     * adapter back to shown, so disabling a connection quietly un-hid its tab for whenever it was
     * switched back on.
     */
    private fun buildAdapter(context: InfoTabContext): InfoTabOrderAdapter {
        val tabs = context.tabs
        val visibility = context.savedVisibility()
        val items = context.savedOrder()
            .sortedBy { !tabs[it].fetchEnabled }
            .map { index ->
                InfoTabOrderItem(
                    index,
                    getString(tabs[index].labelRes),
                    tabs[index].iconRes,
                    visibility.getOrNull(index) == true,
                    tabs[index].fetchEnabled,
                )
            }.toMutableList()
        return InfoTabOrderAdapter(items)
    }

    private fun attachReorderTouchHelper(
        recycler: RecyclerView,
        adapter: InfoTabOrderAdapter,
    ): ItemTouchHelper {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            // Inert rows sit at the bottom and stay there: dragging one would put a tab that
            // cannot appear ahead of tabs that can, and the order it landed in would be saved.
            override fun getMovementFlags(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
            ): Int = if (adapter.isActionable(vh.bindingAdapterPosition)) {
                super.getMovementFlags(rv, vh)
            } else 0

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                if (!adapter.isActionable(target.bindingAdapterPosition)) return false
                adapter.onItemMove(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }
        return ItemTouchHelper(callback).apply { attachToRecyclerView(recycler) }
    }

    /**
     * Persists one context's order and visibility.
     *
     * Fetch-disabled tabs are appended after the reordered ones in their previous relative order;
     * their position does not matter, since [InfoTabContext.visibleOrderedTabs] filters them out
     * regardless.
     */
    private fun save(context: InfoTabContext, adapter: InfoTabOrderAdapter) {
        val finalItems = adapter.getItems()
        val visibleIds = finalItems.map { it.id }
        val hiddenIds = context.savedOrder().filterNot { it in visibleIds }
        PrefManager.setVal(context.orderPref, visibleIds + hiddenIds)
        PrefManager.setVal(
            context.visibilityPref,
            MutableList(context.tabs.size) { i -> finalItems.find { it.id == i }?.visible ?: true }
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "infoTabOrder"
        fun newInstance() = InfoTabOrderBottomSheet()
    }
}
