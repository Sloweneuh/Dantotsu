package ani.dantotsu.connections.mangaupdates

import android.content.Context
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
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.BottomSheetSourceSearchBinding
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.stripSpansOnPaste
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import androidx.recyclerview.widget.RecyclerView
import android.content.Intent

class AniListQuickSearchDialogFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSourceSearchBinding? = null
    private val binding get() = _binding!!

    private var searched = false
    private var searchJob: Job? = null
    private var searchWatchdog: Runnable? = null
    private var titleOptions: List<String> = emptyList()

    companion object {
        private const val ARG_TITLES = "titles"
        private const val ARG_REQUEST_KEY = "request_key"

        fun newInstance(titles: ArrayList<String>, requestKey: String? = null): AniListQuickSearchDialogFragment {
            return AniListQuickSearchDialogFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_TITLES, titles)
                    if (!requestKey.isNullOrBlank()) putString(ARG_REQUEST_KEY, requestKey)
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
        binding.searchSourceTitle.text = getString(R.string.anilist)

        titleOptions = (arguments?.getStringArrayList(ARG_TITLES) ?: arrayListOf())
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .distinctBy { it.lowercase(Locale.ROOT) }

        val firstTitle = titleOptions.firstOrNull().orEmpty()
        binding.searchBarText.setText(firstTitle)

        val requestKey = arguments?.getString(ARG_REQUEST_KEY)

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

                var results: List<Media>? = null
                var timedOut = false
                try {
                    results = withContext(Dispatchers.IO) {
                        val response = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                            Anilist.query.searchAniManga(
                                type = "MANGA",
                                page = 1,
                                perPage = 25,
                                search = query
                            )
                        }
                        if (response == null) {
                            timedOut = true
                            emptyList()
                        } else {
                            response.results.filter { media ->
                                val format = media.format?.uppercase(Locale.ROOT)
                                val isMangaLike = media.manga != null || format == "MANGA" || format == "NOVEL" || format == "ONE_SHOT"
                                isMangaLike && media.anime == null
                            }
                        }
                    }
                } catch (_: Throwable) {
                    results = null
                } finally {
                    searchWatchdog?.let { binding.searchProgress.removeCallbacks(it) }
                    searchWatchdog = null
                    searchJob = null
                    binding.searchProgressContainer.visibility = View.GONE

                    if (!results.isNullOrEmpty()) {
                        val mutableResults = ArrayList(results)
                        binding.searchRecyclerView.visibility = View.VISIBLE
                        binding.searchRecyclerView.adapter = QuickResultsAdapter(mutableResults) { media ->
                            if (!requestKey.isNullOrBlank()) {
                                parentFragmentManager.setFragmentResult(
                                    requestKey,
                                    Bundle().apply {
                                        putInt("mediaId", media.id)
                                        putString("title", media.userPreferredName.ifBlank { media.mainName() })
                                        putString("cover", media.cover)
                                    }
                                )
                                dismiss()
                            } else {
                                startActivity(
                                    Intent(requireContext(), MediaDetailsActivity::class.java)
                                        .putExtra("mediaId", media.id)
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
                            results == null -> getString(R.string.search_fetch_error)
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

    private inner class QuickResultsAdapter(
        private val list: List<Media>,
        private val onClick: (Media) -> Unit
    ) : RecyclerView.Adapter<QuickResultsAdapter.VH>() {

        inner class VH(val b: ItemMediaCompactBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(ItemMediaCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val media = list[position]
            val b = holder.b
            b.itemCompactImage.loadImage(media.cover)
            b.itemCompactTitle.text = media.userPreferredName.ifBlank { media.mainName() }
            b.itemCompactScore.text = ((media.meanScore ?: 0) / 10.0).toString()
            if (media.format.equals("NOVEL", ignoreCase = true)) {
                b.itemCompactType.visibility = View.VISIBLE
                b.itemCompactRelation.text = "Novel"
                b.itemCompactTypeImage.setImageResource(R.drawable.ic_round_import_contacts_24)
            } else {
                b.itemCompactType.visibility = View.GONE
            }
            b.itemCompactProgressContainer.visibility = View.GONE
            b.itemCompactSourceBadge.visibility = View.GONE
            b.root.setOnClickListener { onClick(media) }
        }

        override fun getItemCount(): Int = list.size
    }
}
