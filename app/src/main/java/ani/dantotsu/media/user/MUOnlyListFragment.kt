package ani.dantotsu.media.user

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import ani.dantotsu.connections.mangaupdates.MUMediaAdapter
import ani.dantotsu.home.MergedReadingAdapter
import ani.dantotsu.connections.mangaupdates.toMedia
import ani.dantotsu.databinding.FragmentListBinding
import ani.dantotsu.media.MediaRandomDialogFragment

/**
 * A tab fragment that shows MangaUpdates list entries.
 * When [listKey] is null, shows all entries combined.
 * When [listKey] is set, shows only entries from that specific list key ("Separate" custom lists).
 * Inserted into [ListActivity]'s ViewPager.
 */
class MUOnlyListFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private var listKey: String? = null
    private var currentItems: List<ani.dantotsu.connections.mangaupdates.MUMedia> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listKey = arguments?.getString("listKey")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val model: ListViewModel by activityViewModels()
        val screenWidth = resources.displayMetrics.run { widthPixels / density }

        var currentGrid = model.grid.value ?: false
        currentItems = run {
            val muMap = model.getFilteredMuLists().value
            if (listKey != null) muMap?.get(listKey) ?: emptyList()
            else muMap?.values?.flatten() ?: emptyList()
        }

        fun update() {
            val spanCount = if (currentGrid) (screenWidth / 120f).toInt() else 1
            binding.listRecyclerView.layoutManager = GridLayoutManager(requireContext(), spanCount)
            val density = requireContext().resources.displayMetrics.density
            val basePad = (8 * density).toInt()
            if (currentGrid) {
                // item_media_compact.xml has layout_marginStart="-16dp"; compensate with
                // extra left padding so the first column aligns with other tabs.
                val extraPad = (16 * density).toInt()
                binding.listRecyclerView.setPadding(basePad + extraPad, basePad, basePad, basePad)
                binding.listRecyclerView.adapter = MUMediaAdapter(currentItems, matchParent = false)
            } else {
                binding.listRecyclerView.setPadding(basePad, basePad, basePad, basePad)
                binding.listRecyclerView.adapter = MergedReadingAdapter(currentItems.map { it as Any }, 1, true)
            }
        }

        model.getFilteredMuLists().observe(viewLifecycleOwner) { muMap ->
            currentItems = if (listKey != null)
                muMap?.get(listKey) ?: emptyList()
            else
                muMap?.values?.flatten() ?: emptyList()
            update()
        }

        model.grid.observe(viewLifecycleOwner) { grid ->
            currentGrid = grid
            update()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun randomOptionClick() {
        val combined = ArrayList(currentItems.map { it.toMedia() })
        if (combined.isNotEmpty()) {
            MediaRandomDialogFragment.newInstance(combined)
                .show(parentFragmentManager, "random")
        }
    }

    companion object {
        fun newInstance(listKey: String? = null): MUOnlyListFragment =
            MUOnlyListFragment().apply {
                if (listKey != null) {
                    arguments = Bundle().apply { putString("listKey", listKey) }
                }
            }
    }
}
