package ani.dantotsu.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.databinding.BottomSheetSearchBinding
import ani.dantotsu.databinding.ViewTilePanelBinding
import ani.dantotsu.isOnline
import ani.dantotsu.settings.quicktiles.SearchTiles
import ani.dantotsu.settings.quicktiles.TilePanelController
import ani.dantotsu.settings.quicktiles.tileHostOf
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * Where to search. The same tile panel the quick-settings sheet uses, over [SearchTiles] — the
 * fixed column of buttons was the same nine for everyone, most of them never touched.
 */
class SearchBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSearchBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val ARG_QUERY = "query"

        fun newInstance(query: String?): SearchBottomSheet {
            val f = SearchBottomSheet()
            val args = Bundle()
            args.putString(ARG_QUERY, query)
            f.arguments = args
            return f
        }

        fun newInstance(): SearchBottomSheet = newInstance(null)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Read by whichever tile is tapped; see SearchTiles.pendingQuery.
        SearchTiles.pendingQuery = arguments?.getString(ARG_QUERY)

        val offline = !isOnline(requireContext()) ||
                PrefManager.getVal<Boolean>(PrefName.OfflineMode)
        TilePanelController(
            binding = ViewTilePanelBinding.bind(binding.root),
            catalogue = SearchTiles,
            host = tileHostOf(requireActivity()) { dismiss() },
            offline = offline,
        ).attach()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        SearchTiles.pendingQuery = null
        _binding = null
    }
}
