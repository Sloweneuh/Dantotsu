package ani.dantotsu.media

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import androidx.appcompat.widget.ListPopupWindow
import ani.dantotsu.R
import androidx.core.content.ContextCompat
import androidx.core.math.MathUtils.clamp
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.databinding.BottomSheetSourceSearchBinding
import ani.dantotsu.media.anime.AnimeSourceAdapter
import ani.dantotsu.media.manga.MangaSourceAdapter
import ani.dantotsu.navBarHeight
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.HAnimeSources
import ani.dantotsu.parsers.HMangaSources
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.px
import ani.dantotsu.stripSpansOnPaste
import ani.dantotsu.tryWithSuspend
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import ani.dantotsu.settings.ExtensionsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceSearchDialogFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSourceSearchBinding? = null
    private val binding get() = _binding!!
    val model: MediaDetailsViewModel by activityViewModels()
    private var searched = false
    var anime = true
    var i: Int? = null
    var id: Int? = null
    var media: Media? = null
    private var searchJob: kotlinx.coroutines.Job? = null
    private var searchWatchdog: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSourceSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.mediaListContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin += navBarHeight }

        val scope = requireActivity().lifecycleScope
        val imm =
            requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        model.getMedia().observe(viewLifecycleOwner) {
            media = it
            if (media != null) {
                binding.mediaListProgressBar.visibility = View.GONE
                binding.mediaListLayout.visibility = View.VISIBLE

                binding.searchRecyclerView.visibility = View.GONE
                // searchProgress should stay hidden until a search starts
                binding.searchProgress.visibility = View.GONE

                i = media!!.selected!!.sourceIndex
                val langIndex = media!!.selected!!.langIndex

                val source = if (media!!.anime != null) {
                    val src = (if (media!!.isAdult) HAnimeSources else AnimeSources)[i!!]
                    // Set the language index for the parser if it's a dynamic parser
                    (src as? ani.dantotsu.parsers.DynamicAnimeParser)?.sourceLanguage = langIndex
                    src
                } else {
                    anime = false
                    val src = (if (media!!.isAdult) HMangaSources else MangaSources)[i!!]
                    // Set the language index for the parser if it's a dynamic parser
                    (src as? ani.dantotsu.parsers.DynamicMangaParser)?.sourceLanguage = langIndex
                    src
                }

                // Helper to check if string contains only Latin alphabet (and common punctuation)
                fun isLatinOnly(str: String): Boolean {
                    return str.all { char ->
                        // Allow basic Latin, Latin Extended, numbers, spaces, and common punctuation
                        char.code in 0x0020..0x007E || // Basic ASCII
                        char.code in 0x00A0..0x00FF || // Latin-1 Supplement
                        char.code in 0x0100..0x017F || // Latin Extended-A
                        char.code in 0x0180..0x024F    // Latin Extended-B
                    }
                }

                // Build titles list - will be populated async
                var titleOptions: List<String> = emptyList()

                // Define search function first so it can be used in the coroutine
                fun search(queryOverride: String? = null) {
                    // prevent concurrent searches
                    if (searchJob?.isActive == true) return
                    _binding?.searchBarText?.clearFocus()
                    _binding?.searchBarText?.windowToken?.let { token ->
                        imm.hideSoftInputFromWindow(token, 0)
                    }
                    searchJob = scope.launch {
                        val query = queryOverride ?: _binding?.searchBarText?.text?.toString() ?: return@launch
                        _binding?.searchProgressContainer?.visibility = View.VISIBLE
                        _binding?.searchRecyclerView?.visibility = View.GONE
                        // Hide any previous empty/error placeholder immediately when starting a new search
                        _binding?.searchEmptyContainer?.visibility = View.GONE

                        // Start a UI watchdog to ensure spinner is hidden even if an extension blocks
                        searchWatchdog?.let { _binding?.searchProgress?.removeCallbacks(it) }
                        searchWatchdog = Runnable {
                            _binding?.searchProgressContainer?.visibility = View.GONE
                            _binding?.searchRecyclerView?.visibility = View.VISIBLE
                            _binding?.searchRecyclerView?.adapter = null
                            searchJob?.cancel()
                        }
                        _binding?.searchProgress?.postDelayed(searchWatchdog, 12_000L)

                        var results: List<ani.dantotsu.parsers.ShowResponse>? = null
                        var timedOut = false
                        try {
                            results = withContext(Dispatchers.IO) {
                                try {
                                    kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                                        tryWithSuspend {
                                            source.search(query)
                                        }
                                    }
                                } catch (_: Throwable) {
                                    null
                                }
                            }
                                if (results == null) {
                                    timedOut = true
                                }
                        } catch (_: Throwable) {
                            results = null
                        } finally {
                            // cancel watchdog and reset job
                            searchWatchdog?.let { _binding?.searchProgress?.removeCallbacks(it) }
                            searchWatchdog = null
                            searchJob = null
                            _binding?.searchProgressContainer?.visibility = View.GONE
                            if (results != null && results.isNotEmpty()) {
                                _binding?.searchRecyclerView?.visibility = View.VISIBLE
                                _binding?.searchRecyclerView?.adapter =
                                    if (anime) AnimeSourceAdapter(results, model, i!!, media!!.id, this@SourceSearchDialogFragment, requireActivity().lifecycleScope, source.hostUrl, source)
                                    else MangaSourceAdapter(results, model, i!!, media!!.id, this@SourceSearchDialogFragment, requireActivity().lifecycleScope, source.hostUrl, source)
                                _binding?.searchRecyclerView?.layoutManager = GridLayoutManager(
                                    requireActivity(),
                                    clamp(requireActivity().resources.displayMetrics.widthPixels / 124f.px, 1, 4)
                                )
                                // Hide any empty/error placeholder
                                _binding?.searchEmptyContainer?.visibility = View.GONE
                            } else {
                                // Show empty state with different messages depending on cause
                                _binding?.searchRecyclerView?.visibility = View.GONE
                                _binding?.searchRecyclerView?.adapter = null
                                _binding?.searchEmptyContainer?.visibility = View.VISIBLE
                                val emptyTextView = _binding?.searchEmptyText
                                withContext(Dispatchers.Main) {
                                    when {
                                        timedOut -> {
                                            emptyTextView?.text = getString(R.string.search_timeout)
                                            ani.dantotsu.snackString(getString(R.string.search_timeout))
                                        }
                                        results == null -> {
                                            val msg = getString(R.string.search_fetch_error)
                                            val target = getString(R.string.search_fetch_error).substringAfter("Check your connection or ").substringBefore(", then try again.")
                                            try {
                                                val spannable = SpannableString(msg)
                                                val start = msg.indexOf(target)
                                                if (start >= 0) {
                                                    val end = start + target.length
                                                    spannable.setSpan(object : ClickableSpan() {
                                                        override fun onClick(widget: View) {
                                                            try {
                                                                val intent = Intent(requireContext(), ExtensionsActivity::class.java)
                                                                startActivity(intent)
                                                            } catch (_: Throwable) {}
                                                        }
                                                    }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                                    emptyTextView?.text = spannable
                                                    emptyTextView?.movementMethod = LinkMovementMethod.getInstance()
                                                } else {
                                                    emptyTextView?.text = msg
                                                }
                                            } catch (_: Throwable) {
                                                emptyTextView?.text = getString(R.string.search_fetch_error)
                                            }
                                        }
                                        else -> {
                                            // No results found
                                            emptyTextView?.text = getString(R.string.search_no_results)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Launch coroutine to build title list with Comick data
                scope.launch {
                    // Fetch Comick titles first if available (for manga only)
                    val comickTitles = mutableListOf<String>()
                    if (media!!.anime == null) {
                        try {
                            val comickSlug = model.comickSlug.value
                            if (!comickSlug.isNullOrBlank()) {
                                try {
                                    val comickData = withContext(Dispatchers.IO) {
                                        ani.dantotsu.connections.comick.ComickApi.getComicDetails(comickSlug)
                                    }
                                    comickData?.comic?.md_titles?.forEach { altTitle ->
                                        // Only add English titles (lang = "en")
                                        if (altTitle.lang == "en" && !altTitle.title.isNullOrBlank()) {
                                            comickTitles.add(altTitle.title.trim())
                                        }
                                    }
                                } catch (_: Throwable) {
                                    // Ignore Comick fetch errors
                                }
                            }
                        } catch (_: Throwable) {
                            // Ignore if Comick data not available
                        }
                    }

                    // Build a deterministic list of candidate titles/synonyms for the dropdown.
                    // Pull every title field available on the Media (AniList english,
                    // userPreferred, romaji and MAL/native), then synonyms, Comick and
                    // MangaUpdates titles. Preserve order and dedupe case-insensitively.
                    titleOptions = run {
                        val list = mutableListOf<String>()

                        fun addIfNotBlank(s: String?) {
                            if (!s.isNullOrBlank()) list.add(s.trim())
                        }

                        media?.let { m ->
                            // 1) Core AniList/MAL title fields, in preferred order
                            addIfNotBlank(m.name)               // English
                            addIfNotBlank(m.userPreferredName)  // User preferred
                            addIfNotBlank(m.nameRomaji)         // Romaji
                            addIfNotBlank(m.nameMAL)            // MAL / native

                            // 2) Synonyms / alternative titles
                            m.synonyms.forEach { addIfNotBlank(it) }

                            // 3) Comick English titles
                            comickTitles.forEach { addIfNotBlank(it) }

                            // 4) MangaUpdates associated/synonym titles
                            model.mangaUpdatesSeries.value?.associated
                                ?.forEach { addIfNotBlank(it.title) }

                            // 5) MangaBaka titles (primary/romanized/native + alternates)
                            model.mangaBakaSeries.value?.let { mb ->
                                addIfNotBlank(mb.title)
                                addIfNotBlank(mb.romanizedTitle)
                                addIfNotBlank(mb.nativeTitle)
                                mb.titles?.forEach { addIfNotBlank(it.title) }
                            }
                        }

                        // Keep only Latin-script titles and dedupe case-insensitively,
                        // preserving first-seen order.
                        val seen = linkedSetOf<String>()
                        list.filter { isLatinOnly(it) }
                            .filter { seen.add(it.lowercase(java.util.Locale.ROOT)) }
                    }

                    // Auto-search with first title if needed (after titleOptions is set)
                    if (!searched) {
                        searched = true
                        val first = titleOptions.firstOrNull()
                        val currentText = _binding?.searchBarText?.text?.toString() ?: ""
                        val defaultFallback = try { media?.mangaName() ?: "" } catch (_: Throwable) { "" }
                        if (!first.isNullOrBlank() && (currentText.isBlank() || currentText == defaultFallback)) {
                            withContext(Dispatchers.Main) {
                                _binding?.searchBarText?.setText(first)
                                search(first)
                            }
                        }
                    }
                }

                binding.searchSourceTitle.text = source.name
                binding.searchBarText.setText(media!!.mangaName())

                // Use the TextInputLayout end icon as the dropdown trigger (robust, accessible)
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
                        try { popup.listView?.elevation = 12f } catch (_: Throwable) {}
                        popup.show()
                    }

                    popup.setOnItemClickListener { _, _, position, _ ->
                        val selected = titleOptions[position]
                        binding.searchBarText.setText(selected)
                        popup.dismiss()
                        // automatically perform the search for convenience
                        search(selected)
                    }
                }

                binding.searchBarText.stripSpansOnPaste()
                binding.searchBarText.setOnEditorActionListener { _, actionId, _ ->
                    return@setOnEditorActionListener when (actionId) {
                        EditorInfo.IME_ACTION_SEARCH -> {
                            search()
                            true
                        }

                        else -> false
                    }
                }

                // Cancel button to stop slow searches
                binding.searchCancelButton.setOnClickListener {
                    searchJob?.cancel()
                    searchWatchdog?.let { _binding?.searchProgress?.removeCallbacks(it) }
                    searchWatchdog = null
                    searchJob = null
                    _binding?.searchProgressContainer?.visibility = View.GONE
                    _binding?.searchRecyclerView?.visibility = View.VISIBLE
                    _binding?.searchRecyclerView?.adapter = null
                }
                // end icon is used as dropdown trigger; searching is done via IME or the search end icon inside TextInputLayout if desired
            }
        }
    }

    override fun onDestroyView() {
        // Cancel pending search and watchdog to avoid UI leaks
        try { searchJob?.cancel() } catch (_: Throwable) {}
        try { searchWatchdog?.let { binding.searchProgress.removeCallbacks(it) } } catch (_: Throwable) {}
        searchWatchdog = null
        super.onDestroyView()
        _binding = null
    }

    override fun dismiss() {
        super.dismiss()
    }
}