package ani.dantotsu.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
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
        private const val ARG_FROM_SHORTCUT = "fromShortcut"

        /** After this many looks the pin tip has done its job and stops appearing. */
        private const val PIN_HINT_LIMIT = 3

        fun newInstance(query: String? = null, fromShortcut: Boolean = false): SearchBottomSheet =
            SearchBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_QUERY, query)
                    putBoolean(ARG_FROM_SHORTCUT, fromShortcut)
                }
            }
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

        // Opened from the launcher's Search shortcut: point out the long-press-to-pin gesture the
        // per-type shortcuts depend on. Shown a handful of times, then never again.
        if (arguments?.getBoolean(ARG_FROM_SHORTCUT) == true) {
            val shown = PrefManager.getVal<Int>(PrefName.SearchPinHintShown)
            if (shown < PIN_HINT_LIMIT) {
                binding.searchPinHint.isVisible = true
                PrefManager.setVal(PrefName.SearchPinHintShown, shown + 1)
            }
        }

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
