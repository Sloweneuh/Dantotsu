package ani.dantotsu.media

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.buildMarkwon
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.MangaUpdatesLoginDialog
import ani.dantotsu.media.manga.Manga
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.isOnline
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.stripSpansOnPaste
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MangaUpdatesInfoFragment : Fragment() {
    private var _binding: FragmentMediaInfoBinding? = null
    private val binding
        get() = _binding!!
    private var loaded = false
    private var isLoggedIn = false

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val model: MediaDetailsViewModel by activityViewModels()
        val offline: Boolean =
                PrefManager.getVal(PrefName.OfflineMode) || !isOnline(requireContext())

        binding.mediaInfoContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += 128f.px + navBarHeight
        }

        model.scrolledToTop.observe(viewLifecycleOwner) {
            if (it) binding.mediaInfoScroll.scrollTo(0, 0)
        }

        if (offline) {
            loaded = true
            showError(getString(R.string.mangaupdates_requires_internet))
            return
        }

        // Observe login status in background
        lifecycleScope.launch(Dispatchers.IO) {
            isLoggedIn = MangaUpdates.getSavedToken()
        }

        // Observe both media and MU link
        var currentMedia: Media? = null

        model.getMedia().observe(viewLifecycleOwner) { media ->
            currentMedia = media
            if (media != null) {
                checkAndDisplay(model, media)
            }
        }

        model.mangaUpdatesLink.observe(viewLifecycleOwner) { muLink ->
            currentMedia?.let { checkAndDisplay(model, it) }
        }

        model.mangaUpdatesLoaded.observe(viewLifecycleOwner) { muLoaded ->
            if (muLoaded) currentMedia?.let { checkAndDisplay(model, it) }
        }

        // New: observe preloaded series details and display immediately if available
        model.mangaUpdatesSeries.observe(viewLifecycleOwner) { series ->
            if (series != null && !loaded) {
                // If media isn't available yet, bail out — checkAndDisplay will retry when it is
                val media = currentMedia ?: return@observe

                // Avoid re-rendering the same series; allow rendering if UI is empty or showing
                // fallback
                val currentTitle = binding.mediaInfoName.text?.toString()?.trim()
                if (!currentTitle.isNullOrBlank() &&
                                series.title != null &&
                                currentTitle.contains(series.title!!, ignoreCase = true)
                ) {
                    // Already showing this series — nothing to do
                    return@observe
                }

                // Display the series (displaySeriesDetails sets loaded = true internally)
                binding.mediaInfoProgressBar.visibility = View.GONE
                binding.mediaInfoContainer.visibility = View.VISIBLE
                displaySeriesDetails(series, media, model)
            }
        }
    }

    private fun checkAndDisplay(model: MediaDetailsViewModel, media: Media) {
        if (loaded) return // Only display once

        val muLink = model.mangaUpdatesLink.value
        val muHasLoaded = model.mangaUpdatesLoaded.value == true

        // Wait for MangaUpdates to finish loading before making a decision
        if (!muHasLoaded) {
            // Still waiting for MangaUpdates to load
            return
        }

        // If ViewModel already preloaded the series details, the observer above will handle
        // displaying it.
        // At this point we decide what UI to show. We must NOT mark `loaded = true` if we still
        // need to fetch
        // data (so the mangaUpdatesSeries observer can display it later). Only set `loaded` when we
        // actually
        // add a static/fallback view or display the series directly.

        val seriesIdentifier = muLink?.let { extractMUIdentifier(it) } ?: ""

        // Check login status and decide
        lifecycleScope.launch(Dispatchers.IO) {
            isLoggedIn = MangaUpdates.getSavedToken()

            withContext(Dispatchers.Main) {
                // Guard: if another coroutine or observer already rendered the series, bail out.
                // Multiple observers can launch concurrent coroutines before loaded=true is set;
                // this prevents the second (or third) coroutine from clearing and re-rendering,
                // and specifically prevents the "else" branch from setting container GONE after
                // displaySeriesDetails already made it visible.
                if (loaded) return@withContext

                // If the ViewModel did not provide a MU link, try to find one in the media's
                // external links
                var effectiveMuLink = muLink
                if (effectiveMuLink.isNullOrBlank()) {
                    try {
                        val extLinks = media.externalLinks
                        extLinks.forEach { linkEntry ->
                            val candidate = linkEntry.getOrNull(1) ?: linkEntry.getOrNull(0)
                            if (!candidate.isNullOrBlank() &&
                                            candidate.contains("mangaupdates", ignoreCase = true)
                            ) {
                                effectiveMuLink = candidate
                                return@forEach
                            }
                        }
                    } catch (_: Exception) {
                        // ignore
                    }
                }

                // Copy effectiveMuLink into an immutable local to avoid smart-cast issues
                val tmpLink = effectiveMuLink
                if (tmpLink != null) {
                    val link = tmpLink.trim()
                    // derive identifier from the effective link
                    val seriesIdentifier = extractMUIdentifier(link)

                    if (isLoggedIn && seriesIdentifier.isNotBlank()) {
                        // Try to use ViewModel's preloaded data first
                        val preloaded = model.mangaUpdatesSeries.value
                        if (preloaded != null) {
                            // Display immediately and mark loaded
                            displaySeriesDetails(preloaded, media, model)
                            loaded = true
                        } else {
                            // Need to fetch now -> show progress and wait for observer to populate
                            binding.mediaInfoProgressBar.visibility = View.VISIBLE
                            binding.mediaInfoContainer.visibility = View.GONE
                            // Do not set loaded; observer will display when data arrives
                            model.fetchMangaUpdatesSeriesByIdentifier(seriesIdentifier)
                        }
                    } else {
                        // Not logged in, show login/open fallback and mark loaded
                        Logger.log(
                                "MangaUpdates Fragment: Not logged in — showing fallback login view"
                        )
                        showMangaUpdatesButton(link)
                        loaded = true
                    }
                } else {
                    // No MU link found, show search buttons and mark loaded
                    Logger.log("MangaUpdates Fragment: No MU link found — showing search fallback")
                    showNoDataWithSearch(media)
                    loaded = true
                }
            }
        }
    }

    // Helper: extract either numeric series ID (from ?id=), the slug from a MangaUpdates link, or
    // return id directly
    private fun extractMUIdentifier(muLink: String): String {
        // If the input is already a simple ID (numeric or alphanumeric slug), return it as-is
        val simpleIdPattern = Regex("^[A-Za-z0-9_-]+$")
        if (muLink.matches(simpleIdPattern)) return muLink

        try {
            val uri = Uri.parse(muLink)
            // Prefer explicit id query parameter
            val idParam = uri.getQueryParameter("id")
            if (!idParam.isNullOrBlank()) return idParam

            // Fallback to last path segment
            val path = uri.path ?: ""
            var lastSeg = path.trimEnd('/').substringAfterLast('/')

            // If the link contains series.html?id=... without proper parsing above, try to extract
            // after '='
            if (lastSeg.isBlank() && muLink.contains("=")) {
                val afterEq = muLink.substringAfter('=', "")
                if (afterEq.isNotBlank()) return afterEq
            }

            // If lastSeg still contains query params (e.g., "series.html?id=159827"), strip them
            if (lastSeg.contains("?")) {
                lastSeg = lastSeg.substringBefore('?')
            }

            // If the last segment looks like "series.html", try to extract id manually
            if (lastSeg.contains("series.html") && muLink.contains("id=")) {
                val afterEq = muLink.substringAfter("id=", "")
                if (afterEq.isNotBlank()) return afterEq.substringBefore('&')
            }

            return lastSeg
        } catch (e: Exception) {
            // Fallback: return last path-like token
            return muLink.substringAfterLast('/')
        }
    }

    private fun showError(message: String) {
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.GONE

        val frameLayout = binding.mediaInfoContainer.parent as? ViewGroup
        frameLayout?.let {
            val errorView = layoutInflater.inflate(android.R.layout.simple_list_item_1, it, false)
            (errorView as? android.widget.TextView)?.apply {
                text = message
                val padding = 32f.px
                setPadding(padding, padding, padding, padding)
                textSize = 16f
            }
            // Tag so it can be removed later when switching to the real info view
            errorView.tag = "mu_fallback_view"
            it.addView(errorView)
        }
    }

    // Remove any previously-added fallback views (login / no-data / error) so the real info UI can
    // be shown cleanly
    private fun clearFallbackViews() {
        val parent = binding.mediaInfoContainer.parent as? ViewGroup ?: return
        val toRemove = mutableListOf<View>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.tag == "mu_fallback_view") {
                toRemove.add(child)
            }
        }
        toRemove.forEach { parent.removeView(it) }
    }

    private fun showMangaUpdatesButton(muLink: String) {
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.GONE

        val frameLayout = binding.mediaInfoContainer.parent as? ViewGroup
        frameLayout?.let { container ->
            // Remove any previous fallback views to avoid stacking multiple pages
            clearFallbackViews()
            // Use fragment_not_logged_in.xml with login button
            val notLoggedInView =
                    layoutInflater.inflate(
                            ani.dantotsu.R.layout.fragment_not_logged_in,
                            container,
                            false
                    )

            // Mark it so we can remove it later when the real content is ready
            notLoggedInView.tag = "mu_fallback_view"

            // Set icon
            notLoggedInView
                    .findViewById<android.widget.ImageView>(R.id.logo)
                    ?.setImageDrawable(
                            requireContext().getDrawable(R.drawable.ic_round_mangaupdates_24)
                    )

            // Set text
            notLoggedInView.findViewById<TextView>(ani.dantotsu.R.id.Title)?.text =
                    getString(ani.dantotsu.R.string.mu_not_logged_in_title)
            notLoggedInView.findViewById<TextView>(ani.dantotsu.R.id.desc)?.text =
                    getString(ani.dantotsu.R.string.mu_not_logged_in_desc)

            // Login button
            notLoggedInView.findViewById<com.google.android.material.button.MaterialButton>(
                            ani.dantotsu.R.id.connectButton
                    )
                    ?.text = getString(ani.dantotsu.R.string.login_to_mangaupdates)
            notLoggedInView.findViewById<com.google.android.material.button.MaterialButton>(
                            ani.dantotsu.R.id.connectButton
                    )
                    ?.icon =
                    requireContext().getDrawable(ani.dantotsu.R.drawable.ic_round_mangaupdates_24)

            notLoggedInView.findViewById<com.google.android.material.button.MaterialButton>(
                            ani.dantotsu.R.id.connectButton
                    )
                    ?.setOnClickListener {
                        val loginDialog = MangaUpdatesLoginDialog()
                        loginDialog.setOnLoginSuccessListener {
                            // Remove the fallback UI immediately, show progress and re-check
                            clearFallbackViews()
                            binding.mediaInfoProgressBar.visibility = View.VISIBLE
                            binding.mediaInfoContainer.visibility = View.GONE
                            loaded = false
                            isLoggedIn = true
                            val model: MediaDetailsViewModel by activityViewModels()
                            model.getMedia().value?.let { media -> checkAndDisplay(model, media) }
                        }
                        loginDialog.show(parentFragmentManager, "mangaupdates_login")
                    }

            // Show "Open on MU" button (hide Quick Search)
            notLoggedInView.findViewById<com.google.android.material.button.MaterialButton>(
                            ani.dantotsu.R.id.quickSearchButton
                    )
                    ?.visibility = View.GONE
            notLoggedInView.findViewById<com.google.android.material.button.MaterialButton>(
                            ani.dantotsu.R.id.openButton
                    )
                    ?.text = getString(ani.dantotsu.R.string.open_on_mangaupdates)
            notLoggedInView.findViewById<com.google.android.material.button.MaterialButton>(
                            ani.dantotsu.R.id.openButton
                    )
                    ?.apply {
                        visibility = View.VISIBLE
                        setOnClickListener {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(muLink)))
                        }
                    }

            container.addView(notLoggedInView)
444            // We've shown a fallback view; mark as loaded so we don't attempt to re-render
            loaded = true
        }
    }

    private fun showQuickSearchModal(media: Media) {
        // Guard: if fragment is detached, bail out to avoid IllegalStateException
        val fragmentContext = context ?: return
        val titles = ArrayList<String>()
        titles.add(media.userPreferredName)
        if (media.nameRomaji != media.userPreferredName) {
            titles.add(media.nameRomaji)
        }

        // Filter English titles
        val englishSynonyms =
                media.synonyms.filter { title ->
                    if (title.isBlank()) return@filter false
                    val hasCJK =
                            title.any { char ->
                                char.code in 0x3040..0x309F ||
                                        char.code in 0x30A0..0x30FF ||
                                        char.code in 0x4E00..0x9FFF ||
                                        char.code in 0xAC00..0xD7AF ||
                                        char.code in 0x1100..0x11FF
                            }
                    !hasCJK
                }
        englishSynonyms.forEach { if (!titles.contains(it)) titles.add(it) }

        val modal =
                ani.dantotsu.others.CustomBottomDialog.newInstance().apply {
                    // Use fragmentContext for resource lookups to avoid accidental requireContext()
                    setTitleText(fragmentContext.getString(R.string.mu_search_title))

                    // Add each title as a clickable TextView
                    titles.forEach { title ->
                        val textView =
                                android.widget.TextView(fragmentContext).apply {
                                    text = title
                                    textSize = 16f
                                    val padding = 16f.px
                                    setPadding(padding, padding, padding, padding)
                                    setTextColor(
                                            androidx.core.content.ContextCompat.getColor(
                                                    fragmentContext,
                                                    ani.dantotsu.R.color.bg_opp
                                            )
                                    )
                                    // Use a simple rounded background with ripple effect
                                    val outValue = android.util.TypedValue()
                                    fragmentContext.theme.resolveAttribute(
                                            android.R.attr.selectableItemBackground,
                                            outValue,
                                            true
                                    )
                                    setBackgroundResource(outValue.resourceId)
                                    isClickable = true
                                    isFocusable = true
                                    setOnClickListener {
                                        // Perform search and show results in-tab (guarded inside the method)
                                        showMangaUpdatesSearchResults(media, title)
                                        dismiss()
                                    }
                                }
                        addView(textView)
                    }
                }

        // Only show the dialog if the fragment is still added and the FragmentManager state
        // hasn't been saved (avoids IllegalStateException when committing after onSaveInstanceState)
        if (isAdded && !parentFragmentManager.isStateSaved) {
            modal.show(parentFragmentManager, "mangaupdates_quick_search")
        }
    }

    private fun showMangaUpdatesSearchResults(media: Media, initialQuery: String) {
        // Capture context early to avoid issues if fragment gets detached
        val fragmentContext = context ?: return

        // Hide the current content
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.GONE

        val frameLayout = binding.mediaInfoContainer.parent as? ViewGroup

        frameLayout?.let { container ->
            val model: MediaDetailsViewModel by activityViewModels()

            // Build title options and fetch remote titles (Comick / MU preload) before rendering UI
            viewLifecycleOwner.lifecycleScope.launch {
                val titleOptions = mutableListOf<String>()
                titleOptions.add(media.userPreferredName)
                if (media.nameRomaji != media.userPreferredName) titleOptions.add(media.nameRomaji)

                // Do not include MangaUpdates titles here — quick-search is only for AniList/Comick

                // If Comick data is available in ViewModel (fetched by Comick tab), reuse it for titles
                val comickResponse = try { model.comickData.value } catch (_: Exception) { null }
                if (comickResponse != null) {
                    comickResponse.comic?.title?.let { t -> if (t.isNotBlank() && !titleOptions.contains(t)) titleOptions.add(t) }
                    comickResponse.comic?.md_titles?.filter { it.lang?.equals("en", true) == true }
                        ?.mapNotNull { it.title }
                        ?.forEach { t -> if (!titleOptions.contains(t)) titleOptions.add(t) }
                }

                // Add English synonyms from Media
                val englishSynonyms =
                    media.synonyms.filter { title ->
                        if (title.isBlank()) return@filter false
                        val hasCJK =
                            title.any { char ->
                                char.code in 0x3040..0x309F ||
                                char.code in 0x30A0..0x30FF ||
                                char.code in 0x4E00..0x9FFF ||
                                char.code in 0xAC00..0xD7AF ||
                                char.code in 0x1100..0x11FF
                            }
                        !hasCJK
                    }
                englishSynonyms.forEach { if (!titleOptions.contains(it)) titleOptions.add(it) }

                // Now render the UI on the main thread
                withContext(Dispatchers.Main) {
                    // IMPORTANT: Remove all previous views to prevent overlap
                    container.removeAllViews()

                    // Inflate search layout
                    val searchView = layoutInflater.inflate(R.layout.fragment_search_page, container, false)

                    val searchBarLayout =
                        searchView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.searchBarLayout)
                    val searchBar = searchView.findViewById<android.widget.AutoCompleteTextView>(R.id.searchBarText)
                    searchBar.stripSpansOnPaste()
                    val searchProgress = searchView.findViewById<android.widget.ProgressBar>(R.id.searchProgress)
                    val emptyMessage = searchView.findViewById<android.widget.TextView>(R.id.emptyMessage)
                    val recyclerView = searchView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.searchRecyclerView)

                    // Set up RecyclerView
                    recyclerView.layoutManager =
                        androidx.recyclerview.widget.GridLayoutManager(
                            fragmentContext,
                            androidx.core.math.MathUtils.clamp(
                                fragmentContext.resources.displayMetrics.widthPixels / 124f.px,
                                1,
                                4
                            )
                        )

                    // Set initial query
                    searchBar.setText(initialQuery)

                    // Function to perform search - define early so it can be used in dropdown
                    fun performSearch(query: String) {
                        if (query.isBlank()) return

                        searchProgress.visibility = View.VISIBLE
                        emptyMessage.visibility = View.GONE
                        recyclerView.visibility = View.GONE

                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val results = withContext(Dispatchers.IO) { MangaUpdates.searchSeries(query) }
                                withContext(Dispatchers.Main) {
                                    searchProgress.visibility = View.GONE
                                    if (results?.results.isNullOrEmpty()) {
                                        emptyMessage.visibility = View.VISIBLE
                                        recyclerView.visibility = View.GONE
                                    } else {
                                        emptyMessage.visibility = View.GONE
                                        recyclerView.visibility = View.VISIBLE
                                        recyclerView.adapter =
                                            MangaUpdatesSearchAdapter(results!!.results!!) { selectedResult ->
                                                selectedResult.record?.let { series -> saveMangaUpdatesSelection(media, series) }
                                            }
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    searchProgress.visibility = View.GONE
                                    emptyMessage.visibility = View.VISIBLE
                                    emptyMessage.text = fragmentContext.getString(R.string.error_loading_data)
                                    android.widget.Toast.makeText(fragmentContext, getString(R.string.error_message, e.message ?: ""), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }

                    // Add dropdown icon to search bar if there are multiple titles
                    if (titleOptions.size > 1) {
                        searchBarLayout.endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_DROPDOWN_MENU
                        searchBarLayout.setEndIconOnClickListener {
                            val adapter = android.widget.ArrayAdapter(fragmentContext, R.layout.item_titles_dropdown, titleOptions)
                            val popup = androidx.appcompat.widget.ListPopupWindow(fragmentContext)
                            popup.anchorView = searchBar
                            popup.setAdapter(adapter)
                            popup.isModal = true
                            searchBarLayout.post {
                                popup.width = searchBarLayout.width
                                popup.verticalOffset = searchBarLayout.height
                                popup.setBackgroundDrawable(androidx.core.content.ContextCompat.getDrawable(fragmentContext, R.drawable.dropdown_background))
                                try { popup.listView?.elevation = 12f } catch (_: Throwable) {}
                                popup.show()
                            }
                            popup.setOnItemClickListener { _, _, position, _ ->
                                val selected = titleOptions[position]
                                searchBar.setText(selected)
                                popup.dismiss()
                                performSearch(selected)
                            }
                        }
                    }

                    // Set up search listener
                    searchBar.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                            performSearch(searchBar.text.toString())
                            val imm = fragmentContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                            imm?.hideSoftInputFromWindow(searchBar.windowToken, 0)
                            true
                        } else false
                    }

                    // Add the search view to the container
                    container.addView(searchView)

                    // Perform initial search
                    performSearch(initialQuery)
                }
            }
        }
    }

    private fun saveMangaUpdatesSelection(
            media: Media,
            series: ani.dantotsu.connections.mangaupdates.MUSeriesRecord
    ) {
        val muLink = "https://www.mangaupdates.com/series/${series.seriesId.toString(36)}"
        val model: MediaDetailsViewModel by activityViewModels()

        // Persist the link immediately (without posting partial series to LiveData — the search
        // API returns an abbreviated MUSeriesRecord that lacks genres, categories, synonyms, etc.)
        model.saveMangaUpdatesLink(media.id, muLink)

        // Clear the search view and restore the original layout
        val frameLayout =
                binding.root.findViewById<ViewGroup>(R.id.mediaInfoScroll)?.getChildAt(0) as?
                        ViewGroup
        frameLayout?.let { parent ->
            parent.removeAllViews()
            parent.addView(binding.mediaInfoProgressBar)
            parent.addView(binding.mediaInfoContainer)
        }

        // Block observer re-renders while we fetch full details; show progress
        loaded = true
        binding.mediaInfoProgressBar.visibility = View.VISIBLE
        binding.mediaInfoContainer.visibility = View.GONE

        android.widget.Toast.makeText(
                        requireContext(),
                        getString(R.string.linked_mangaupdates_entry),
                        android.widget.Toast.LENGTH_SHORT
                )
                .show()

        // Search results only carry a partial MUSeriesRecord (no genres, categories, synonyms,
        // anime adaptation, etc.). Fetch the full detail record before rendering so all dynamic
        // sections are populated correctly.
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val fullSeries = MangaUpdates.getSeriesDetails(series.seriesId) ?: series
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                loaded = false
                displaySeriesDetails(fullSeries, media, model)
                // Update ViewModel with full data; postValue fires after loaded=true, so observers bail
                model.saveMangaUpdatesLink(media.id, muLink, fullSeries)
            }
        }
    }

    private fun showNoDataWithSearch(media: Media) {
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.GONE

        // Use the page layout for no-data case
        val frameLayout = binding.mediaInfoContainer.parent as? ViewGroup
        frameLayout?.let { container ->
            // Remove previous fallback views to avoid duplicates
            clearFallbackViews()
            val pageView =
                    layoutInflater.inflate(
                            ani.dantotsu.R.layout.fragment_nodata_page,
                            container,
                            false
                    )

            // Tag so it can be removed later
            pageView.tag = "mu_fallback_view"

            // set icon
            pageView.findViewById<android.widget.ImageView>(R.id.logo)
                    ?.setImageDrawable(
                            requireContext()
                                    .getDrawable(ani.dantotsu.R.drawable.ic_round_mangaupdates_24)
                    )

            // Set title
            pageView.findViewById<android.widget.TextView>(R.id.title)?.text =
                    getString(ani.dantotsu.R.string.mu_no_data_title)

            // Set small subtitle message
            pageView.findViewById<android.widget.TextView>(R.id.subtitle)?.text =
                    getString(ani.dantotsu.R.string.search_sub_mangaupdates)

            // Single button: Quick Search (since we don't have a link)
            pageView.findViewById<com.google.android.material.button.MaterialButton>(
                            ani.dantotsu.R.id.quickSearchButton
                    )
                    ?.apply {
                        text = getString(ani.dantotsu.R.string.quick_search)
                        icon = context.getDrawable(ani.dantotsu.R.drawable.ic_round_search_24)
                        setOnClickListener { showQuickSearchModal(media) }
                    }

            container.addView(pageView)
            // Mark loaded after adding the fallback page
            Logger.log("MangaUpdates Fragment: Added no-data fallback page")
            loaded = true
        }
    }

    @SuppressLint("SetTextI18n")
    private fun displaySeriesDetails(
            series: ani.dantotsu.connections.mangaupdates.MUSeriesRecord,
            media: Media,
            model: MediaDetailsViewModel
    ) {
        // Remove any previously shown fallback (login / no-data / error) before displaying details
        clearFallbackViews()
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.VISIBLE

        // We are now displaying real series data; mark as loaded so observers won't overwrite later
        loaded = true
        val tripleTab = "\t\t\t"

        // Set up the standard fields first
        // Title
        binding.mediaInfoName.text = tripleTab + (series.title ?: getString(R.string.unknown_title))
        binding.mediaInfoName.setOnLongClickListener {
            copyToClipboard(series.title ?: "")
            true
        }

        // Hide romaji title container for MangaUpdates
        binding.mediaInfoNameRomajiContainer.visibility = View.GONE

        // Parse status field to extract chapter count and status text
        // Example: "143 Chapters (Ongoing)" -> chapters: "143", status: "Ongoing"
        var chaptersCount = "~"
        var statusText = getString(R.string.unknown)

        series.status?.let { fullStatus ->
            // Extract first number for chapter count
            val chapterMatch = Regex("""(\d+)\s+Chapter""").find(fullStatus)
            chapterMatch?.groupValues?.get(1)?.let { chaptersCount = it }

            // Extract text in first parentheses for status
            val statusMatch = Regex("""\(([^)]+)\)""").find(fullStatus)
            statusMatch?.groupValues?.get(1)?.let { statusText = it }
        }

        // Mean Score (Rating)
        series.bayesian_rating?.let { ratingStr ->
            val rating = ratingStr.toDoubleOrNull()
            binding.mediaInfoMeanScore.text =
                    if (rating != null) {
                        String.format("%.1f", rating)
                    } else {
                        ratingStr
                    }
        }
                ?: run { binding.mediaInfoMeanScore.text = getString(R.string.unknown_value) }

        // Status (extracted from detailed status)
        binding.mediaInfoStatus.text = statusText

        // Total Chapters (extracted from detailed status)
        binding.mediaInfoTotalTitle.setText(ani.dantotsu.R.string.total_chaps)
        binding.mediaInfoTotal.text = chaptersCount

        // Format/Type
        binding.mediaInfoFormat.text = series.type ?: getString(R.string.manga)

        // Source (hide for MangaUpdates)
        binding.mediaInfoSourceContainer.visibility = View.GONE

        // Author (show first author of type "Author" in standard field)
        val firstAuthor =
                series.authors?.firstOrNull { it.type?.equals("Author", ignoreCase = true) == true }
        if (firstAuthor?.name != null) {
            binding.mediaInfoAuthorContainer.visibility = View.VISIBLE
            binding.mediaInfoAuthor.text = firstAuthor.name
        } else {
            binding.mediaInfoAuthorContainer.visibility = View.GONE
        }

        // Start date (Year)
        binding.mediaInfoStart.text = series.year ?: getString(R.string.unknown_value)

        // End date (hide entire TableRow container)
        binding.mediaInfoEnd.visibility = View.GONE
        (binding.mediaInfoEnd.parent as? ViewGroup)?.visibility = View.GONE

        // Popularity (hide entire TableRow container)
        binding.mediaInfoPopularity.visibility = View.GONE
        (binding.mediaInfoPopularity.parent as? ViewGroup)?.visibility = View.GONE

        // Favorites -> use for Followers count
        // Change the label from "Favourites" to "Followers"
        val favoritesRow = binding.mediaInfoFavorites.parent as? ViewGroup
        favoritesRow?.let { row ->
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i)
                if (child is android.widget.TextView && child.id != binding.mediaInfoFavorites.id) {
                    child.text = getString(ani.dantotsu.R.string.followers)
                    break
                }
            }
        }

        series.rank?.lists?.let { lists ->
            val followers =
                    (lists.reading
                            ?: 0) +
                            (lists.wish ?: 0) +
                            (lists.complete ?: 0) +
                            (lists.unfinished ?: 0) +
                            (lists.custom ?: 0)
            binding.mediaInfoFavorites.text =
                    if (followers > 0) followers.toString() else getString(R.string.question_marks)
        }
                ?: run { binding.mediaInfoFavorites.text = getString(R.string.question_marks) }

        // Description — render the full synopsis as Markdown
        val fullDesc =
                series.description ?: getString(ani.dantotsu.R.string.no_description_available)

        val descCleaned = fullDesc.replace(Regex("""\n{3,}"""), "\n\n").trim()

        val markwon = buildMarkwon(
                requireContext(),
                userInputContent = false,
                fragment = this,
                linkResolver = { link -> ani.dantotsu.openLinkInBrowser(link) }
        )
        markwon.setMarkdown(binding.mediaInfoDescription, descCleaned)
        binding.mediaInfoDescription.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        binding.mediaInfoDescription.setOnClickListener {
            if (binding.mediaInfoDescription.maxLines == 5) {
                android.animation.ObjectAnimator.ofInt(
                                binding.mediaInfoDescription,
                                "maxLines",
                                100
                        )
                        .setDuration(950)
                        .start()
            } else {
                android.animation.ObjectAnimator.ofInt(binding.mediaInfoDescription, "maxLines", 5)
                        .setDuration(400)
                        .start()
            }
        }

        // Now add additional sections to the container
        val parent = binding.mediaInfoContainer

        // Clear any previously added dynamic views to prevent duplicates.
        // Keep only the static views from the layout.
        val viewsToRemove = mutableListOf<View>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val tag = child.tag
            if (tag is String && (
                        tag == "dynamic_mu_section" ||
                                tag.contains("_mu") ||
                                tag == "unlink_mangaupdates_button" ||
                                tag == "recommendations_mu"
                        )
            ) {
                viewsToRemove.add(child)
            }
        }
        viewsToRemove.forEach { parent.removeView(it) }

        // Status (detailed chapter info with markdown formatting, expandable)
        series.status?.let { rawStatusText ->
            if (rawStatusText.isNotBlank()) {
                val bind =
                        ani.dantotsu.databinding.ItemTitleTextBinding.inflate(
                                LayoutInflater.from(context),
                                parent,
                                false
                        )
                bind.itemTitle.setText(ani.dantotsu.R.string.status_title)

                // First, remove escaped characters (backslashes before special chars)
                // This converts "001\\~040" to "001~040", etc.
                val statusText = rawStatusText.replace("\\", "")

                // Process markdown: **text** -> bold
                val processedText = android.text.SpannableStringBuilder()
                var currentPos = 0
                val boldPattern = Regex("""\*\*([^*]+)\*\*""")

                boldPattern.findAll(statusText).forEach { match ->
                    // Add text before the match
                    if (match.range.first > currentPos) {
                        processedText.append(statusText.substring(currentPos, match.range.first))
                    }

                    // Add bolded text
                    val boldText = match.groupValues[1]
                    val start = processedText.length
                    processedText.append(boldText)
                    processedText.setSpan(
                            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                            start,
                            processedText.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )

                    currentPos = match.range.last + 1
                }

                // Add remaining text
                if (currentPos < statusText.length) {
                    processedText.append(statusText.substring(currentPos))
                }

                bind.itemText.text = processedText
                bind.itemText.maxLines = 3

                // Make expandable on click
                bind.itemText.setOnClickListener {
                    if (bind.itemText.maxLines == 3) {
                        android.animation.ObjectAnimator.ofInt(bind.itemText, "maxLines", 100)
                                .setDuration(400)
                                .start()
                    } else {
                        android.animation.ObjectAnimator.ofInt(bind.itemText, "maxLines", 3)
                                .setDuration(400)
                                .start()
                    }
                }

                bind.root.tag = "dynamic_mu_section"
                parent.addView(bind.root)
            }
        }

        // Anime Adaptation Info (like Comick)
        series.anime?.let { anime ->
            if (!anime.start.isNullOrBlank() || !anime.end.isNullOrBlank()) {
                val bind =
                        ani.dantotsu.databinding.ItemTitleTextBinding.inflate(
                                LayoutInflater.from(context),
                                parent,
                                false
                        )
                bind.itemTitle.text = getString(ani.dantotsu.R.string.anime_adaptation)
                bind.itemText.text = buildString {
                    if (!anime.start.isNullOrBlank()) {
                        append("Start: ${anime.start}")
                    }
                    if (!anime.end.isNullOrBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("End: ${anime.end}")
                    }
                }

                // Make expendable on click
                bind.itemText.setOnClickListener {
                    if (bind.itemText.maxLines == 3) {
                        android.animation.ObjectAnimator.ofInt(bind.itemText, "maxLines", 100)
                                .setDuration(400)
                                .start()
                    } else {
                        android.animation.ObjectAnimator.ofInt(bind.itemText, "maxLines", 3)
                                .setDuration(400)
                                .start()
                    }
                }
                bind.root.tag = "dynamic_mu_section"
                parent.addView(bind.root)
            }
        }

        // Alternative Titles / Synonyms (single row)
        if (!series.associated.isNullOrEmpty()) {
            val bind =
                    ani.dantotsu.databinding.ItemTitleChipgroupBinding.inflate(
                            LayoutInflater.from(context),
                            parent,
                            false
                    )
            bind.itemTitle.setText(ani.dantotsu.R.string.synonyms)
            series.associated.forEach { assoc ->
                assoc.title?.let { title ->
                    val chip =
                            ani.dantotsu.databinding.ItemChipSynonymBinding.inflate(
                                            LayoutInflater.from(context),
                                            bind.itemChipGroup,
                                            false
                                    )
                                    .root
                    chip.text = title
                    chip.setOnLongClickListener {
                        copyToClipboard(title)
                        true
                    }
                    bind.itemChipGroup.addView(chip)
                }
            }
            bind.root.tag = "dynamic_mu_section"
            parent.addView(bind.root)
        }

        // Genres (clickable for search, with "Search All" next to title)
        if (!series.genres.isNullOrEmpty()) {
            val bind =
                    ani.dantotsu.databinding.ItemTitleChipgroupBinding.inflate(
                            LayoutInflater.from(context),
                            parent,
                            false
                    )
            bind.itemTitle.setText(ani.dantotsu.R.string.genres)

            // Add "Search All" action next to the title
            val genreNames = series.genres.mapNotNull { it.genre }
            if (genreNames.isNotEmpty()) {
                bind.itemTitleAction.visibility = View.VISIBLE
                bind.itemTitleAction.text = getString(ani.dantotsu.R.string.search_all)
                bind.itemTitleAction.setOnClickListener {
                    startActivity(
                        Intent(requireContext(), SearchActivity::class.java).apply {
                            putExtra("type", "MANGAUPDATES")
                            putExtra("search", true)
                            putStringArrayListExtra("genres", ArrayList(genreNames))
                        }
                    )
                }
            }

            // Add individual genre chips
            series.genres.forEach { genreObj ->
                genreObj.genre?.let { genre ->
                    val chip =
                            ani.dantotsu.databinding.ItemChipBinding.inflate(
                                            LayoutInflater.from(context),
                                            bind.itemChipGroup,
                                            false
                                    )
                                    .root
                    chip.text = genre
                    chip.isClickable = true
                    chip.setOnClickListener {
                        startActivity(
                            Intent(requireContext(), SearchActivity::class.java).apply {
                                putExtra("type", "MANGAUPDATES")
                                putExtra("search", true)
                                putExtra("genre", genre)
                            }
                        )
                    }
                    chip.setOnLongClickListener {
                        copyToClipboard(genre)
                        android.widget.Toast.makeText(
                                        requireContext(),
                                        "Copied: $genre",
                                        android.widget.Toast.LENGTH_SHORT
                                )
                                .show()
                        true
                    }
                    bind.itemChipGroup.addView(chip)
                }
            }
            bind.root.tag = "dynamic_mu_section"
            parent.addView(bind.root)
        }

        // Categories (clickable for search, like Comick)
        if (!series.categories.isNullOrEmpty()) {
            val bind =
                    ani.dantotsu.databinding.ItemTitleChipgroupMultilineBinding.inflate(
                            LayoutInflater.from(context),
                            parent,
                            false
                    )
            bind.itemTitle.text = getString(ani.dantotsu.R.string.categories)
            series.categories.forEach { cat ->
                cat.category?.let { category ->
                    val chip =
                            ani.dantotsu.databinding.ItemChipBinding.inflate(
                                            LayoutInflater.from(context),
                                            bind.itemChipGroup,
                                            false
                                    )
                                    .root
                    chip.text = category
                    chip.isClickable = true
                    chip.setOnClickListener {
                        startActivity(
                            Intent(requireContext(), SearchActivity::class.java).apply {
                                putExtra("type", "MANGAUPDATES")
                                putExtra("search", true)
                                putExtra("category", category)
                            }
                        )
                    }
                    chip.setOnLongClickListener {
                        copyToClipboard(category)
                        android.widget.Toast.makeText(
                                        requireContext(),
                                        "Copied: $category",
                                        android.widget.Toast.LENGTH_SHORT
                                )
                                .show()
                        true
                    }
                    bind.itemChipGroup.addView(chip)
                }
            }
            bind.root.tag = "dynamic_mu_section"
            parent.addView(bind.root)
        }
        // Recommendations — resolve all recs in one pass (original logic), then display as two rows
        val directRecIds = series.recommendations?.mapNotNull { it.seriesId }?.toSet() ?: emptySet()
        val allRecs = buildList<ani.dantotsu.connections.mangaupdates.MURecommendation> {
            series.recommendations?.let { addAll(it) }
            series.category_recommendations?.forEach { cat ->
                add(ani.dantotsu.connections.mangaupdates.MURecommendation(
                    seriesId = cat.seriesId,
                    seriesName = cat.seriesName,
                    seriesUrl = cat.seriesUrl,
                    seriesImage = cat.seriesImage,
                    weight = cat.weight,
                ))
            }
        }.distinctBy { it.seriesId }
        if (allRecs.isNotEmpty() &&
                parent.findViewWithTag<View>("recommendations_mu") == null
        ) {
            viewLifecycleOwner.lifecycleScope.launch {
                val currentMuSeriesId = media.muSeriesId
                val currentAnilistId = media.id
                val anilistRecommendations = model.getMedia().value?.recommendations
                val anilistById = anilistRecommendations?.associateBy { it.id } ?: emptyMap()
                val directMedia = mutableListOf<Media>()
                val categoryMedia = mutableListOf<Media>()

                val recAnilistPairs = mutableListOf<Pair<Int, Int>>()
                val recMuMedia = mutableMapOf<Int, Media>()

                val comickEnabled = ani.dantotsu.settings.saving.PrefManager.getVal<Boolean>(
                    ani.dantotsu.settings.saving.PrefName.ComickEnabled
                )

                for ((index, rec) in allRecs.withIndex()) {
                    val recSeriesId = rec.seriesId ?: continue
                    if (recSeriesId == currentMuSeriesId) continue
                    val recName = rec.seriesName ?: continue
                    val coverUrl = rec.seriesImage?.url?.original ?: rec.seriesImage?.url?.thumb
                    if (!comickEnabled) {
                        recMuMedia[index] = Media(
                            id = (recSeriesId and 0x7FFFFFFF).toInt(),
                            name = recName,
                            nameRomaji = recName,
                            userPreferredName = recName,
                            cover = coverUrl,
                            banner = coverUrl,
                            isAdult = false,
                            manga = Manga(),
                            format = "MANGA",
                            muSeriesId = recSeriesId,
                        )
                        continue
                    }
                    try {
                        val slug = withContext(Dispatchers.IO) {
                            ComickApi.searchAndMatchComicByMuId(listOf(recName), recSeriesId)
                        }
                        if (slug != null) {
                            val details = withContext(Dispatchers.IO) { ComickApi.getComicDetails(slug) }
                            val anilistId = details?.comic?.links?.al?.toIntOrNull()
                            if (anilistId != null && anilistId != currentAnilistId) {
                                recAnilistPairs.add(Pair(index, anilistId))
                            } else {
                                recMuMedia[index] = Media(
                                    id = (recSeriesId and 0x7FFFFFFF).toInt(),
                                    name = recName,
                                    nameRomaji = recName,
                                    userPreferredName = recName,
                                    cover = coverUrl,
                                    banner = coverUrl,
                                    isAdult = false,
                                    manga = Manga(),
                                    format = "MANGA",
                                    muSeriesId = recSeriesId,
                                )
                            }
                        } else {
                            recMuMedia[index] = Media(
                                id = (recSeriesId and 0x7FFFFFFF).toInt(),
                                name = recName,
                                nameRomaji = recName,
                                userPreferredName = recName,
                                cover = coverUrl,
                                banner = coverUrl,
                                isAdult = false,
                                manga = Manga(),
                                format = "MANGA",
                                muSeriesId = recSeriesId,
                            )
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }

                val indexToMedia = mutableMapOf<Int, Media>()
                indexToMedia.putAll(recMuMedia)

                if (recAnilistPairs.isNotEmpty()) {
                    val allAnilistIds = recAnilistPairs.map { it.second }
                    val missingIds = allAnilistIds.filter { anilistById[it] == null }
                    val batchFetched = if (missingIds.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            try {
                                Anilist.query.getMediaBatch(missingIds)
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                    } else emptyList()
                    val batchById = batchFetched.associateBy { it.id }
                    for ((index, anilistId) in recAnilistPairs) {
                        val m = anilistById[anilistId] ?: batchById[anilistId]
                        if (m != null) indexToMedia[index] = m
                    }
                }

                for (index in indexToMedia.keys.sorted()) {
                    val m = indexToMedia[index] ?: continue
                    val recSeriesId = allRecs.getOrNull(index)?.seriesId
                    if (recSeriesId != null && recSeriesId in directRecIds) {
                        directMedia.add(m)
                    } else {
                        categoryMedia.add(m)
                    }
                }

                if (directMedia.isNotEmpty() || categoryMedia.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        if (directMedia.isNotEmpty()) {
                            ani.dantotsu.databinding.ItemTitleRecyclerBinding.inflate(
                                    LayoutInflater.from(context),
                                    parent,
                                    false
                            ).apply {
                                itemTitle.setText(ani.dantotsu.R.string.recommended)
                                itemRecycler.adapter = MediaAdaptor(0, directMedia, requireActivity())
                                itemRecycler.layoutManager =
                                    androidx.recyclerview.widget.LinearLayoutManager(
                                        requireContext(),
                                        androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                                        false
                                    )
                                itemMore.visibility = View.VISIBLE
                                itemMore.setSafeOnClickListener {
                                    MediaListViewActivity.passedMedia = ArrayList(directMedia)
                                    startActivity(
                                        Intent(requireContext(), MediaListViewActivity::class.java)
                                            .putExtra("title", getString(ani.dantotsu.R.string.recommended))
                                    )
                                }
                                root.tag = "dynamic_mu_section"
                                parent.addView(root)
                            }
                        }
                        if (categoryMedia.isNotEmpty()) {
                            ani.dantotsu.databinding.ItemTitleRecyclerBinding.inflate(
                                    LayoutInflater.from(context),
                                    parent,
                                    false
                            ).apply {
                                itemTitle.setText(ani.dantotsu.R.string.category_recommendations)
                                itemRecycler.adapter = MediaAdaptor(0, categoryMedia, requireActivity())
                                itemRecycler.layoutManager =
                                    androidx.recyclerview.widget.LinearLayoutManager(
                                        requireContext(),
                                        androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                                        false
                                    )
                                itemMore.visibility = View.VISIBLE
                                itemMore.setSafeOnClickListener {
                                    MediaListViewActivity.passedMedia = ArrayList(categoryMedia)
                                    startActivity(
                                        Intent(requireContext(), MediaListViewActivity::class.java)
                                            .putExtra("title", getString(ani.dantotsu.R.string.category_recommendations))
                                    )
                                }
                                root.tag = "dynamic_mu_section"
                                parent.addView(root)
                            }
                        }
                    }
                }
            }
        }

        val isManualSelection =
            PrefManager.getNullableCustomVal<String>(
                "mangaupdates_link_${media.id}",
                null,
                String::class.java
            ) != null

        if (isManualSelection) {
            // Remove any previous unlink button so we don't duplicate it
            parent.findViewWithTag<View>("unlink_mangaupdates_button")?.let { parent.removeView(it) }

            val unlinkBtn =
                com.google.android.material.button.MaterialButton(requireContext()).apply {
                text = resources.getString(R.string.unlink_mangaupdates)
                icon =
                    androidx.appcompat.content.res.AppCompatResources.getDrawable(
                        requireContext(),
                        ani.dantotsu.R.drawable.ic_round_close_24
                    )
                iconGravity =
                    com.google.android.material.button.MaterialButton.ICON_GRAVITY_START
                layoutParams =
                    android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        .apply {
                            val margin = 16f.px
                            setMargins(margin, margin, margin, margin)
                        }
                setOnClickListener {
                    loaded = false
                    model.removeMangaUpdatesLink(media.id)
                    android.widget.Toast.makeText(
                            requireContext(),
                            getString(R.string.mangaupdates_entry_unlinked),
                            android.widget.Toast.LENGTH_SHORT
                        )
                        .show()
                }
                tag = "unlink_mangaupdates_button"
                }
            parent.addView(unlinkBtn)
        }
    }
}
