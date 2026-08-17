package ani.dantotsu.media.novel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.core.math.MathUtils.clamp
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetSourceSearchBinding
import ani.dantotsu.databinding.ItemCharacterBinding
import ani.dantotsu.loadImage
import ani.dantotsu.px
import ani.dantotsu.parsers.NovelSources
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.util.Logger
import ani.dantotsu.util.hideEmptyState
import ani.dantotsu.util.showError
import ani.dantotsu.util.showNoResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks which entry on a source a novel corresponds to — the novel counterpart of the "wrong
 * title" search on the manga page.
 *
 * The result is remembered by the caller, so this only opens when the automatic match was wrong or
 * has not happened yet, rather than every time the media is opened.
 */
class NovelSourceSearchDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSourceSearchBinding? = null
    private val binding get() = _binding!!

    private var sourceIndex: Int = 0
    private var initialQuery: String = ""
    private var searchJob: Job? = null

    /** Set by the caller; receives the entry the user picked. */
    var onPicked: ((ShowResponse) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        arguments?.let {
            sourceIndex = it.getInt(ARG_SOURCE)
            initialQuery = it.getString(ARG_QUERY).orEmpty()
        }
        super.onCreate(savedInstanceState)
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
        // The layout starts as the sheet looks while it is still loading: its own spinner showing
        // and the whole search area invisible. Nothing reveals it on its own, so a sheet that
        // never ran that step shows a spinner forever and no search bar.
        binding.mediaListProgressBar.visibility = View.GONE
        binding.mediaListLayout.visibility = View.VISIBLE
        binding.searchProgressContainer.visibility = View.GONE
        binding.searchRecyclerView.visibility = View.GONE

        // Same card grid as the anime and manga pickers, sized to the screen the same way.
        binding.searchRecyclerView.layoutManager = GridLayoutManager(
            requireContext(),
            clamp(resources.displayMetrics.widthPixels / 124f.px, 1, 4)
        )
        binding.searchBarText.setText(initialQuery)
        binding.searchBarText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(binding.searchBarText.text.toString()); true
            } else false
        }
        binding.searchBar.setEndIconOnClickListener {
            search(binding.searchBarText.text.toString())
        }
        if (initialQuery.isNotBlank()) search(initialQuery)
    }

    private fun search(query: String) {
        val parser = NovelSources.lnReaderAt(sourceIndex) ?: run {
            binding.searchEmptyState.showError(getString(R.string.source_not_found))
            return
        }
        searchJob?.cancel()
        binding.searchProgressContainer.isVisible = true
        binding.searchRecyclerView.isVisible = false
        binding.searchEmptyState.hideEmptyState()

        searchJob = lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { runCatching { parser.search(query) } }
            if (_binding == null) return@launch
            binding.searchProgressContainer.isVisible = false

            results.onSuccess { list ->
                binding.searchRecyclerView.adapter = ResultAdapter(list) { picked ->
                    onPicked?.invoke(picked)
                    dismiss()
                }
                binding.searchRecyclerView.isVisible = list.isNotEmpty()
                if (list.isEmpty()) binding.searchEmptyState.showNoResults()
                else binding.searchEmptyState.hideEmptyState()
            }.onFailure {
                Logger.log("Novel source search failed: ${it.message}")
                binding.searchEmptyState.showError(it)
            }
        }
    }

    /**
     * Result cards, the same shape the anime and manga source pickers use.
     *
     * Their shared adapter is typed to [ani.dantotsu.media.SourceSearchDialogFragment], so the
     * card layout is bound here rather than that class being widened for one more caller.
     */
    private class ResultAdapter(
        private val items: List<ShowResponse>,
        private val onClick: (ShowResponse) -> Unit,
    ) : RecyclerView.Adapter<ResultAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            ItemCharacterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.binding.itemCompactImage.loadImage(item.coverUrl, 200)
            holder.binding.itemCompactTitle.isSelected = true
            holder.binding.itemCompactTitle.text = item.name
            holder.binding.itemCompactRelation.isVisible = false
            holder.binding.root.setOnClickListener { onClick(item) }
        }

        class Holder(val binding: ItemCharacterBinding) : RecyclerView.ViewHolder(binding.root)
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_SOURCE = "source"
        private const val ARG_QUERY = "query"

        fun newInstance(sourceIndex: Int, query: String) = NovelSourceSearchDialog().apply {
            arguments = Bundle().apply {
                putInt(ARG_SOURCE, sourceIndex)
                putString(ARG_QUERY, query)
            }
        }
    }
}
