package ani.dantotsu.media.user

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MUMediaAdapter
import ani.dantotsu.connections.mangaupdates.toMedia
import ani.dantotsu.databinding.FragmentListBinding
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaAdaptor
import ani.dantotsu.media.MediaRandomDialogFragment
import ani.dantotsu.media.OtherDetailsViewModel

class ListFragment : Fragment() {
    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private var pos: Int? = null
    private var calendar = false
    private var grid: Boolean? = null
    private var list: MutableList<Media>? = null
    private var muList: List<MUMedia>? = null
    private var mediaAdaptor: MediaAdaptor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            pos = it.getInt("list")
            calendar = it.getBoolean("calendar")
        }
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
        val screenWidth = resources.displayMetrics.run { widthPixels / density }

        fun update() {
            val g = grid ?: return
            val aniList = list ?: return
            val muItems = muList ?: emptyList()

            val spanCount = if (g) (screenWidth / 120f).toInt() else 1
            val anilistAdaptor = MediaAdaptor(if (g) 0 else 1, aniList, requireActivity(), true)
            mediaAdaptor = anilistAdaptor
            val muAdaptor = MUMediaAdapter(muItems)

            val layoutManager = GridLayoutManager(requireContext(), spanCount)
            binding.listRecyclerView.layoutManager = layoutManager
            binding.listRecyclerView.adapter = ConcatAdapter(anilistAdaptor, muAdaptor)
        }

        if (calendar) {
            val model: OtherDetailsViewModel by activityViewModels()
            model.getCalendar().observe(viewLifecycleOwner) {
                if (it != null) {
                    list = it.values.toList().getOrNull(pos!!)
                    update()
                }
            }
            grid = true
        } else {
            val model: ListViewModel by activityViewModels()

            fun resolveMuList(
                aniMap: Map<String, *>?,
                muMap: Map<String, List<MUMedia>>?
            ): List<MUMedia>? {
                if (muMap == null) return null
                val key = aniMap?.keys?.toList()?.getOrNull(pos!!) ?: return null
                return if (key == "All") muMap.values.flatten() else muMap[key]
            }

            model.getLists().observe(viewLifecycleOwner) { aniMap ->
                if (aniMap != null) {
                    list = aniMap.values.toList().getOrNull(pos!!)
                    muList = resolveMuList(aniMap, model.getFilteredMuLists().value)
                    update()
                }
            }
            model.getFilteredMuLists().observe(viewLifecycleOwner) { muMap ->
                muList = resolveMuList(model.getLists().value, muMap)
                update()
            }
            model.grid.observe(viewLifecycleOwner) {
                grid = it
                update()
            }
        }
    }

    fun randomOptionClick() {
        val aniItems: List<Media> = list ?: emptyList()
        val muItems: List<Media> = (muList ?: emptyList()).map { it.toMedia() }
        val combined = ArrayList(aniItems + muItems)
        if (combined.isNotEmpty()) {
            MediaRandomDialogFragment.newInstance(combined)
                .show(parentFragmentManager, "random")
        }
    }

    companion object {
        fun newInstance(pos: Int, calendar: Boolean = false): ListFragment =
            ListFragment().apply {
                arguments = Bundle().apply {
                    putInt("list", pos)
                    putBoolean("calendar", calendar)
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
