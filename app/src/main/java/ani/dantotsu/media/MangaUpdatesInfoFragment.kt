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
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.MangaUpdatesLoginDialog
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.isOnline
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
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
            Logger.log("MangaUpdates Fragment: Login status = $isLoggedIn")
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
            if (series != null) {
                Logger.log(
                        "MangaUpdates Fragment: ViewModel provided series: title=${series.title}, genres=${series.genres?.size ?: 0}"
                )
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

                // Display the series (displaySeriesDetails will clear fallbacks and mark loaded)
                binding.mediaInfoProgressBar.visibility = View.GONE
                binding.mediaInfoContainer.visibility = View.VISIBLE
                currentMedia?.let { displaySeriesDetails(series, it, model) }
                loaded = true
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

        Logger.log(
                "MangaUpdates Fragment: checkAndDisplay decision: muLink=$muLink, muHasLoaded=$muHasLoaded, isLoggedIn(approx)=$isLoggedIn"
        )
        // Check login status and decide
        lifecycleScope.launch(Dispatchers.IO) {
            isLoggedIn = MangaUpdates.getSavedToken()
            Logger.log("MangaUpdates Fragment: Login status = $isLoggedIn")

            withContext(Dispatchers.Main) {
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
                    Logger.log("MangaUpdates Fragment: Using MU link (vm or external): $link")
                    // derive identifier from the effective link
                    val seriesIdentifier = extractMUIdentifier(link)
                    Logger.log("MangaUpdates Fragment: Derived seriesIdentifier=$seriesIdentifier")

                    if (isLoggedIn && seriesIdentifier.isNotBlank()) {
                        // Try to use ViewModel's preloaded data first
                        val preloaded = model.mangaUpdatesSeries.value
                        if (preloaded != null) {
                            // Display immediately and mark loaded
                            Logger.log(
                                    "MangaUpdates Fragment: Using preloaded series from ViewModel: ${preloaded.title}"
                            )
                            displaySeriesDetails(preloaded, media, model)
                            loaded = true
                        } else {
                            // Need to fetch now -> show progress and wait for observer to populate
                            Logger.log(
                                    "MangaUpdates Fragment: Fetching details for $seriesIdentifier"
                            )
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
            Logger.log("MangaUpdates Fragment: Added not-logged-in fallback view for link=$muLink")
            // We've shown a fallback view; mark as loaded so we don't attempt to re-render
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
            // IMPORTANT: Remove all previous views to prevent overlap
            container.removeAllViews()

            // Inflate search layout
            val searchView = layoutInflater.inflate(R.layout.fragment_search_page, container, false)

            val searchBarLayout =
                    searchView.findViewById<com.google.android.material.textfield.TextInputLayout>(
                            R.id.searchBarLayout
                    )
            val searchBar =
                    searchView.findViewById<android.widget.AutoCompleteTextView>(R.id.searchBarText)
            val searchProgress =
                    searchView.findViewById<android.widget.ProgressBar>(R.id.searchProgress)
            val emptyMessage = searchView.findViewById<android.widget.TextView>(R.id.emptyMessage)
            val recyclerView =
                    searchView.findViewById<androidx.recyclerview.widget.RecyclerView>(
                            R.id.searchRecyclerView
                    )

            // Build list of title options for dropdown
            val titleOptions = mutableListOf<String>()
            titleOptions.add(media.userPreferredName)
            if (media.nameRomaji != media.userPreferredName) {
                titleOptions.add(media.nameRomaji)
            }
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
                        val results =
                                withContext(Dispatchers.IO) { MangaUpdates.searchSeries(query) }

                        withContext(Dispatchers.Main) {
                            searchProgress.visibility = View.GONE
                            if (results?.results.isNullOrEmpty()) {
                                emptyMessage.visibility = View.VISIBLE
                                recyclerView.visibility = View.GONE
                            } else {
                                emptyMessage.visibility = View.GONE
                                recyclerView.visibility = View.VISIBLE
                                recyclerView.adapter =
                                        MangaUpdatesSearchAdapter(results!!.results!!) {
                                                selectedResult ->
                                            // Save the selection
                                            selectedResult.record?.let { series ->
                                                saveMangaUpdatesSelection(media, series)
                                            }
                                        }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            searchProgress.visibility = View.GONE
                            emptyMessage.visibility = View.VISIBLE
                            emptyMessage.text =
                                    fragmentContext.getString(R.string.error_loading_data)
                            android.widget.Toast.makeText(
                                            fragmentContext,
                                            getString(R.string.error_message, e.message ?: ""),
                                            android.widget.Toast.LENGTH_SHORT
                                    )
                                    .show()
                        }
                    }
                }
            }

            // Add dropdown icon to search bar if there are multiple titles
            if (titleOptions.size > 1) {
                searchBarLayout.endIconMode =
                        com.google.android.material.textfield.TextInputLayout.END_ICON_DROPDOWN_MENU

                // Set up dropdown when end icon is clicked
                searchBarLayout.setEndIconOnClickListener {
                    val adapter =
                            android.widget.ArrayAdapter(
                                    fragmentContext,
                                    R.layout.item_titles_dropdown,
                                    titleOptions
                            )
                    val popup = androidx.appcompat.widget.ListPopupWindow(fragmentContext)
                    popup.anchorView = searchBar
                    popup.setAdapter(adapter)
                    popup.isModal = true

                    searchBarLayout.post {
                        popup.width = searchBarLayout.width
                        popup.verticalOffset = searchBarLayout.height
                        popup.setBackgroundDrawable(
                                androidx.core.content.ContextCompat.getDrawable(
                                        fragmentContext,
                                        R.drawable.dropdown_background
                                )
                        )
                        try {
                            popup.listView?.elevation = 12f
                        } catch (_: Throwable) {}
                        popup.show()
                    }

                    popup.setOnItemClickListener { _, _, position, _ ->
                        val selected = titleOptions[position]
                        searchBar.setText(selected)
                        popup.dismiss()
                        // Automatically perform the search
                        performSearch(selected)
                    }
                }
            }

            // Set up search listener
            searchBar.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    performSearch(searchBar.text.toString())
                    // Hide keyboard
                    val imm =
                            fragmentContext.getSystemService(
                                    android.content.Context.INPUT_METHOD_SERVICE
                            ) as?
                                    android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(searchBar.windowToken, 0)
                    true
                } else {
                    false
                }
            }

            // Add the search view to the container
            container.addView(searchView)

            // Perform initial search
            performSearch(initialQuery)
        }
    }

    private fun saveMangaUpdatesSelection(
            media: Media,
            series: ani.dantotsu.connections.mangaupdates.MUSeriesRecord
    ) {
        val muLink = "https://www.mangaupdates.com/series/${series.seriesId.toString(36)}"
        val model: MediaDetailsViewModel by activityViewModels()
        model.saveMangaUpdatesLink(media.id, muLink, series)

        // Clear the search view and restore the original layout
        val frameLayout =
                binding.root.findViewById<ViewGroup>(R.id.mediaInfoScroll)?.getChildAt(0) as?
                        ViewGroup
        frameLayout?.let { parent ->
            parent.removeAllViews()
            parent.addView(binding.mediaInfoProgressBar)
            parent.addView(binding.mediaInfoContainer)
        }

        // Observer will handle displaying details since we updated LiveData via ViewModel
        android.widget.Toast.makeText(
                        requireContext(),
                        getString(R.string.linked_mangaupdates_entry),
                        android.widget.Toast.LENGTH_SHORT
                )
                .show()
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
        Logger.log(
                "MangaUpdates Fragment: displaySeriesDetails - title=${series.title}, descriptionPresent=${!series.description.isNullOrBlank()}"
        )
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

        // Description (extract only synopsis, remove any text that was supposed to be
        // italic/bold/links)
        val fullDesc =
                series.description ?: getString(ani.dantotsu.R.string.no_description_available)

        // Remove markdown links entirely
        val linkPattern = Regex("""\[(.*?)\]\(.*?\)""")
        val descNoLinks =
                linkPattern
                        .replace(fullDesc) { matchResult ->
                            "" // Remove the entire link, including the text
                        }
                        .replace(Regex("""\n{3,}"""), "\n\n")

        val italicPattern = Regex("""\*(.*?)\*""")
        val boldPattern = Regex("""\*\*(.*?)\*\*""")
        val parenthesisPattern = Regex("""\((.*?)\)""")
        val descNoMarkdown =
                italicPattern
                        .replace(descNoLinks) { matchResult ->
                            "" // Remove text inside single asterisks
                        }
                        .let { intermediate ->
                            boldPattern.replace(intermediate) { matchResult ->
                                "" // Remove text inside double asterisks
                            }
                        }
                        .let { intermediate2 ->
                            parenthesisPattern.replace(intermediate2) { matchResult ->
                                "" // Remove text inside parentheses
                            }
                        }

        // Remove isolated asterisks, underscores, parentheses and other markdown chars
        val markdownCharsPattern = Regex("""[\*\_\`\~\|\>\#\-\+\=]""")
        val descNoMarkdownChars = markdownCharsPattern.replace(descNoMarkdown, "")

        // Remove isolated brackets, parentheses and similar
        val bracketsPattern = Regex("""[\[\]\(\)\{\}]""")
        val descNoBrackets = bracketsPattern.replace(descNoMarkdownChars, "")

        // Remove excessive newlines
        val excessiveNewlinesPattern = Regex("""\n{3,}""")
        val descCleaned = excessiveNewlinesPattern.replace(descNoBrackets, "\n\n")

        binding.mediaInfoDescription.text = tripleTab + descCleaned.trim()
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

        // Clear any previously added dynamic views to prevent duplicates
        // Keep only the static views from the layout (everything before the first
        // ItemTitleTextBinding)
        val viewsToRemove = mutableListOf<View>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            // Remove any views that were dynamically added (they'll have specific binding types)
            // We identify them by checking if they're NOT part of the original layout
            if (child.tag == "dynamic_mu_section") {
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
                    // Combine all genres with underscores
                    val allGenres = genreNames.joinToString("_")
                    val url = "https://www.mangaupdates.com/series?genre=$allGenres"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
                        // Open MangaUpdates genre search
                        val url = "https://www.mangaupdates.com/series?genre=$genre"
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
                        // Open MangaUpdates category search (URL encode the category name)
                        val encoded = java.net.URLEncoder.encode(category, "UTF-8")
                        val url = "https://www.mangaupdates.com/series?category=$encoded"
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
        }

        // Unlink button
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
                    tag = "dynamic_mu_section"
                }
        parent.addView(unlinkBtn)
    }
}
