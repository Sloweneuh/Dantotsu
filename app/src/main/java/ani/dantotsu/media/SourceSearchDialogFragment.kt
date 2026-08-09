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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import ani.dantotsu.settings.ExtensionsActivity
import ani.dantotsu.util.friendlyErrorReason
import ani.dantotsu.util.hideEmptyState
import ani.dantotsu.util.showError
import ani.dantotsu.util.showErrorWithReason
import ani.dantotsu.util.showNoResults
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

    /**
     * Which search attempt owns the sheet. An extension does its network work in a blocking call,
     * so neither the timeout nor [kotlinx.coroutines.Job.cancel] actually stops one — a search that
     * was given up on stays alive and eventually reaches its `finally` with results in hand. This
     * is what stops it repainting the sheet over whatever replaced it in the meantime.
     */
    private var searchGeneration = 0

    /**
     * The single place a source search that never answered is reported.
     *
     * Reached from two directions — the coroutine's own 10s timeout, and the watchdog for the case
     * where a blocked extension means the coroutine never gets that far.
     */
    private fun showSearchTimedOut(reason: String? = null) {
        val binding = _binding ?: return
        binding.searchProgressContainer.visibility = View.GONE
        binding.searchRecyclerView.visibility = View.GONE
        binding.searchRecyclerView.adapter = null
        val base = binding.root.context.getString(R.string.search_timeout)
        binding.searchEmptyState.showError(if (reason != null) "$base\n$reason" else base)
        ani.dantotsu.snackString(base)
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
                    val generation = ++searchGeneration
                    searchJob = scope.launch {
                        val query = queryOverride ?: _binding?.searchBarText?.text?.toString() ?: return@launch
                        _binding?.searchProgressContainer?.visibility = View.VISIBLE
                        _binding?.searchRecyclerView?.visibility = View.GONE
                        // Hide any previous empty/error placeholder immediately when starting a new search
                        _binding?.searchEmptyState?.hideEmptyState()

                        // Start a UI watchdog to ensure spinner is hidden even if an extension blocks
                        searchWatchdog?.let { _binding?.searchProgress?.removeCallbacks(it) }
                        searchWatchdog = Runnable {
                            // A blocked extension is the case this exists for: it is stuck inside
                            // its own network call, where neither the timeout below nor cancel()
                            // reaches it, so the coroutine never gets far enough to report
                            // anything. This used to hide the spinner and empty the list without
                            // saying why, which is exactly what left the sheet silently blank.
                            searchGeneration++
                            searchJob?.cancel()
                            searchJob = null
                            searchWatchdog = null
                            showSearchTimedOut()
                        }
                        _binding?.searchProgress?.postDelayed(searchWatchdog, 12_000L)

                        var results: List<ani.dantotsu.parsers.ShowResponse>? = null
                        var lastError: Throwable? = null
                        // Which of the two ways `results` came back null: the search answered — with
                        // nothing, or with an error tryWithSuspend swallowed — or it never answered
                        // at all. Both used to be reported as a timeout, so a source that simply
                        // failed sent the user looking for a connection problem instead.
                        var answered = false
                        try {
                            results = withContext(Dispatchers.IO) {
                                kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                                    val found = try {
                                        tryWithSuspend {
                                            source.search(query)
                                        }
                                    } catch (e: Throwable) {
                                        lastError = e
                                        null
                                    }
                                    answered = true
                                    found
                                }
                            }
                        } catch (e: Throwable) {
                            lastError = e
                            results = null
                            answered = true
                        } finally {
                            // A search the watchdog has already given up on, or one the user
                            // replaced, arrives here late — with the timeout placeholder or a newer
                            // search's results already on screen. It owns none of that, so it
                            // touches nothing: not the sheet, and not the job/watchdog now held by
                            // whichever search took its place.
                            if (generation == searchGeneration) {
                                // cancel watchdog and reset job
                                searchWatchdog?.let { _binding?.searchProgress?.removeCallbacks(it) }
                                searchWatchdog = null
                                searchJob = null
                                // Everything below this draws into the sheet and reads its strings.
                                // A `finally` runs on cancellation too, so a search still in flight
                                // when the sheet was dismissed arrives here with no view to draw
                                // into and no context to ask for a string — which is what the
                                // placeholder's first getString() crashed on. The `_binding?` calls
                                // were already no-ops by then; the fragment-scoped ones weren't.
                                if (!isAdded || _binding == null) return@launch
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
                                    _binding?.searchEmptyState?.hideEmptyState()
                                } else {
                                    // Show empty state with different messages depending on cause.
                                    // Drawn straight from here: this coroutine already runs on the
                                    // main dispatcher, and the withContext(Main) that used to wrap
                                    // this suspends — which throws instantly once the job has been
                                    // cancelled, so a search that got cut short drew no placeholder
                                    // at all and left the sheet blank.
                                    _binding?.searchRecyclerView?.visibility = View.GONE
                                    _binding?.searchRecyclerView?.adapter = null
                                    val reason = friendlyErrorReason(lastError)
                                    when {
                                        !answered -> showSearchTimedOut(reason)
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
                                                    val finalText: CharSequence = if (reason != null) {
                                                        SpannableStringBuilder(spannable).append("\n").append(reason)
                                                    } else spannable
                                                    _binding?.searchEmptyState?.showError(finalText)
                                                    _binding?.searchEmptyState?.emptyStateText?.movementMethod = LinkMovementMethod.getInstance()
                                                } else {
                                                    _binding?.searchEmptyState?.showErrorWithReason(reason)
                                                }
                                            } catch (_: Throwable) {
                                                _binding?.searchEmptyState?.showErrorWithReason(reason)
                                            }
                                        }
                                        else -> {
                                            // No results found
                                            _binding?.searchEmptyState?.showNoResults()
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

                    // Keep only Latin-script titles and dedupe case-insensitively, preserving
                    // first-seen order. [seen] is shared with the MALSync pass further down, so a
                    // title already in the dropdown is never appended a second time.
                    val seen = linkedSetOf<String>()
                    fun acceptable(candidates: List<String>): List<String> =
                        candidates.filter { isLatinOnly(it) }
                            .filter { seen.add(it.lowercase(java.util.Locale.ROOT)) }

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

                        acceptable(list)
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

                    // Titles MALSync's quicklinks carry — what each linked site itself calls the
                    // series, which is often the only spelling a source will match. Appended after
                    // the auto-search rather than folded into the list above, so a slow MALSync
                    // never holds up the first search; the details preload usually has the response
                    // cached by now anyway.
                    val quicklinkTitles = media?.let { m ->
                        try {
                            model.getMalSyncQuicklinkTitles(m)
                        } catch (_: Throwable) {
                            emptyList<String>()
                        }
                    } ?: emptyList()
                    acceptable(quicklinkTitles).takeIf { it.isNotEmpty() }?.let { extra ->
                        titleOptions = titleOptions + extra
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
                    // Same reason the watchdog bumps it: cancelling doesn't stop a blocked
                    // extension, so this search can still come back and repopulate the sheet the
                    // user just cleared.
                    searchGeneration++
                    searchJob?.cancel()
                    searchWatchdog?.let { _binding?.searchProgress?.removeCallbacks(it) }
                    searchWatchdog = null
                    searchJob = null
                    _binding?.searchProgressContainer?.visibility = View.GONE
                    _binding?.searchRecyclerView?.visibility = View.GONE
                    _binding?.searchRecyclerView?.adapter = null
                    _binding?.searchEmptyState?.showNoResults(
                        getString(R.string.search_cancelled)
                    )
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