package ani.dantotsu.download.manage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.FragmentDownloadQueueBinding
import ani.dantotsu.download.DownloadState
import ani.dantotsu.download.DownloadTracker
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadQueueFragment : Fragment() {
    private var _binding: FragmentDownloadQueueBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: DownloadQueueAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var dragging = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = DownloadQueueAdapter(
            onCancel = { DownloadTracker.cancel(requireContext(), it) },
            onStartDrag = { itemTouchHelper.startDrag(it) }
        )
        binding.downloadQueueRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.downloadQueueRecycler.adapter = adapter

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = false

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                // Only not-yet-started (queued) items may be reordered.
                return if (adapter.isQueuedEntry(viewHolder.bindingAdapterPosition))
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                // Keep the drag inside the queued section (never past its header or in-progress).
                if (!adapter.isQueuedEntry(from) || !adapter.isQueuedEntry(to)) return false
                adapter.moveItem(from, to)
                return true
            }

            override fun onSelectedChanged(
                viewHolder: RecyclerView.ViewHolder?,
                actionState: Int
            ) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) dragging = true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                dragging = false
                DownloadTracker.applyQueuedOrder(adapter.queuedIds())
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(binding.downloadQueueRecycler)

        binding.downloadQueueCancelAll.setSafeOnClickListener {
            requireContext().customAlertDialog().apply {
                setTitle(R.string.cancel_all_downloads)
                setMessage(R.string.cancel_all_downloads_confirm)
                setPosButton(R.string.yes) {
                    DownloadTracker.cancelAll(requireContext())
                }
                setNegButton(R.string.no)
            }.show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            DownloadTracker.items.collectLatest { list ->
                if (dragging) return@collectLatest
                adapter.submit(list)
                binding.downloadQueueEmpty.visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.downloadQueueCancelAllContainer.visibility =
                    if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
