package ani.dantotsu.connections.mangaupdates

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import androidx.core.math.MathUtils.clamp
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetSourceSearchBinding
import ani.dantotsu.media.MangaUpdatesSearchAdapter
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.settings.ExtensionMediaLinker
import ani.dantotsu.stripSpansOnPaste
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.util.Locale

class MangaUpdatesQuickSearchDialogFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSourceSearchBinding? = null
    private val binding get() = _binding!!

    private var searched = false
    private var searchJob: Job? = null
    private var searchWatchdog: Runnable? = null
    private var titleOptions: List<String> = emptyList()

    companion object {
        private const val ARG_TITLES = "titles"
        private const val ARG_EXT_PKG = "ext_pkg"
        private const val ARG_EXT_LANG = "ext_lang"
        private const val ARG_EXT_MANGA = "ext_manga"

        fun newInstance(
            titles: ArrayList<String>,
            extensionPkg: String? = null,
            extensionLangIndex: Int = 0,
            sManga: SManga? = null,
        ): MangaUpdatesQuickSearchDialogFragment {
            return MangaUpdatesQuickSearchDialogFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_TITLES, titles)
                    if (extensionPkg != null) {
                        putString(ARG_EXT_PKG, extensionPkg)
                        putInt(ARG_EXT_LANG, extensionLangIndex)
                        if (sManga != null) putSerializable(ARG_EXT_MANGA, sManga)
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSourceSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.mediaListContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }

        val imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        binding.mediaListProgressBar.visibility = View.GONE
        binding.mediaListLayout.visibility = View.VISIBLE
        binding.searchRecyclerView.visibility = View.GONE
        binding.searchProgress.visibility = View.GONE
        binding.searchSourceTitle.text = getString(R.string.mu_series_search)

        titleOptions = (arguments?.getStringArrayList(ARG_TITLES) ?: arrayListOf())
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .distinctBy { it.lowercase(Locale.ROOT) }

        val firstTitle = titleOptions.firstOrNull().orEmpty()
        binding.searchBarText.setText(firstTitle)

        fun search(queryOverride: String? = null) {
            if (searchJob?.isActive == true) return
            binding.searchBarText.clearFocus()
            binding.searchBarText.windowToken?.let { token ->
                imm.hideSoftInputFromWindow(token, 0)
            }
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                val query = queryOverride ?: binding.searchBarText.text?.toString().orEmpty()
                if (query.isBlank()) return@launch

                binding.searchProgressContainer.visibility = View.VISIBLE
                binding.searchRecyclerView.visibility = View.GONE
                binding.searchEmptyContainer.visibility = View.GONE

                searchWatchdog?.let { binding.searchProgress.removeCallbacks(it) }
                searchWatchdog = Runnable {
                    binding.searchProgressContainer.visibility = View.GONE
                    binding.searchRecyclerView.visibility = View.VISIBLE
                    binding.searchRecyclerView.adapter = null
                    searchJob?.cancel()
                }
                binding.searchProgress.postDelayed(searchWatchdog, 12_000L)

                var response: MUSearchResponse? = null
                var timedOut = false
                try {
                    response = withContext(Dispatchers.IO) {
                        kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                            MangaUpdates.searchSeries(query)
                        }
                    }
                    if (response == null) timedOut = true
                } catch (_: Throwable) {
                    response = null
                } finally {
                    searchWatchdog?.let { binding.searchProgress.removeCallbacks(it) }
                    searchWatchdog = null
                    searchJob = null
                    binding.searchProgressContainer.visibility = View.GONE

                    val results = response?.results.orEmpty()
                    if (results.isNotEmpty()) {
                        binding.searchRecyclerView.visibility = View.VISIBLE
                        binding.searchRecyclerView.adapter = MangaUpdatesSearchAdapter(results) { selected ->
                            val muMedia = selected.toMUMedia()
                            if (muMedia != null) {
                                val mediaId = (muMedia.id and 0x7FFFFFFF).toInt()
                                applyExtensionLink(mediaId)
                                startActivity(
                                    Intent(requireContext(), MUMediaDetailsActivity::class.java)
                                        .putExtra("muMedia", muMedia as Serializable)
                                )
                            }
                        }
                        binding.searchRecyclerView.layoutManager = GridLayoutManager(
                            requireActivity(),
                            clamp(requireActivity().resources.displayMetrics.widthPixels / 124f.px, 1, 4)
                        )
                        binding.searchEmptyContainer.visibility = View.GONE
                    } else {
                        binding.searchRecyclerView.visibility = View.GONE
                        binding.searchRecyclerView.adapter = null
                        binding.searchEmptyContainer.visibility = View.VISIBLE
                        binding.searchEmptyText.text = when {
                            timedOut -> getString(R.string.search_timeout)
                            response == null -> getString(R.string.search_fetch_error)
                            else -> getString(R.string.search_no_results)
                        }
                    }
                }
            }
        }

        binding.searchBar.setEndIconOnClickListener {
            if (titleOptions.size <= 1) return@setEndIconOnClickListener

            val adapter = ArrayAdapter(requireContext(), R.layout.item_titles_dropdown, titleOptions)
            val popup = ListPopupWindow(requireContext())
            popup.anchorView = binding.searchBarText
            popup.setAdapter(adapter)
            popup.isModal = true

            binding.searchBar.post {
                popup.width = binding.searchBar.width
                popup.verticalOffset = binding.searchBar.height
                popup.setBackgroundDrawable(
                    ContextCompat.getDrawable(requireContext(), R.drawable.dropdown_background)
                )
                popup.show()
            }

            popup.setOnItemClickListener { _, _, position, _ ->
                val selected = titleOptions[position]
                binding.searchBarText.setText(selected)
                popup.dismiss()
                search(selected)
            }
        }

        binding.searchBarText.stripSpansOnPaste()
        binding.searchBarText.setOnEditorActionListener { _, actionId, _ ->
            when (actionId) {
                EditorInfo.IME_ACTION_SEARCH -> {
                    search()
                    true
                }
                else -> false
            }
        }

        binding.searchCancelButton.setOnClickListener {
            searchJob?.cancel()
            searchWatchdog?.let { binding.searchProgress.removeCallbacks(it) }
            searchWatchdog = null
            searchJob = null
            binding.searchProgressContainer.visibility = View.GONE
            binding.searchRecyclerView.visibility = View.VISIBLE
            binding.searchRecyclerView.adapter = null
        }

        if (!searched) {
            searched = true
            val first = titleOptions.firstOrNull()
            if (!first.isNullOrBlank()) search(first)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyExtensionLink(mediaId: Int) {
        val args = arguments ?: return
        val pkg = args.getString(ARG_EXT_PKG) ?: return
        val lang = args.getInt(ARG_EXT_LANG, 0)
        val sManga = args.getSerializable(ARG_EXT_MANGA) as? SManga ?: return
        ExtensionMediaLinker.linkMangaMedia(mediaId, pkg, lang, sManga)
    }

    override fun onDestroyView() {
        try {
            searchJob?.cancel()
            searchWatchdog?.let { binding.searchProgress.removeCallbacks(it) }
        } catch (_: Throwable) {
        }
        searchWatchdog = null
        _binding = null
        super.onDestroyView()
    }
}
