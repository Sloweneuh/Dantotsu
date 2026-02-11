package ani.dantotsu.media

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.text.HtmlCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.comick.ComickResponse
import ani.dantotsu.connections.malsync.MalSyncApi
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemChipSynonymBinding
import ani.dantotsu.databinding.ItemTitleTextBinding
import ani.dantotsu.isOnline
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ComickInfoFragment : Fragment() {
    private var _binding: FragmentMediaInfoBinding? = null
    private val binding get() = _binding!!
    private var loaded = false

    private val tripleTab = "\t\t\t"

    /**
     * Filter synonyms to likely English titles by checking for Latin characters
     * and filtering out titles with CJK (Chinese/Japanese/Korean) characters
     */
    private fun filterEnglishTitles(synonyms: List<String>): List<String> {
        return synonyms.filter { title ->
            if (title.isBlank()) return@filter false

            // Check if the title contains CJK characters
            val hasCJK = title.any { char ->
                // Japanese Hiragana, Katakana, and Kanji ranges
                char.code in 0x3040..0x309F || // Hiragana
                char.code in 0x30A0..0x30FF || // Katakana
                char.code in 0x4E00..0x9FFF || // CJK Unified Ideographs
                // Korean Hangul
                char.code in 0xAC00..0xD7AF || // Hangul Syllables
                char.code in 0x1100..0x11FF    // Hangul Jamo
            }

            // Only include titles without CJK characters (likely English/Latin script)
            !hasCJK
        }
    }

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
            showError("Comick data requires an internet connection")
            return
        }

        model.getMedia().observe(viewLifecycleOwner) { media ->
            ani.dantotsu.util.Logger.log("Comick: ========== OBSERVER TRIGGERED ==========")
            val m = media ?: return@observe
            ani.dantotsu.util.Logger.log("Comick: Media object is not null")
            if (!loaded) {
                loadComickData(m, model)
            }
        }
    }

    private fun loadComickData(media: Media, model: MediaDetailsViewModel) {
                ani.dantotsu.util.Logger.log("Comick: Not yet loaded, will process")
                ani.dantotsu.util.Logger.log("Comick: Starting to load data for ${media.userPreferredName}")
                ani.dantotsu.util.Logger.log("Comick: Media has ${media.externalLinks.size} external links at observer trigger")
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        binding.mediaInfoProgressBar.visibility = View.VISIBLE
                        binding.mediaInfoContainer.visibility = View.GONE

                        // Check if data was already preloaded by the ViewModel
                        var comickSlug = model.comickSlug.value

                        if (comickSlug == null) {
                            // Check if user has manually saved a slug for this media
                            val savedSlug = PrefManager.getNullableCustomVal<String>("comick_slug_${media.id}", null, String::class.java)
                            if (savedSlug != null) {
                                ani.dantotsu.util.Logger.log("Comick: Found saved slug '$savedSlug' in preferences")
                                comickSlug = savedSlug
                                model.comickSlug.postValue(savedSlug)
                            } else {
                                // Data not preloaded yet, fetch it now
                                ani.dantotsu.util.Logger.log("Comick: Fetching slug (not preloaded)")

                                // Get the Comick slug from MalSync quicklinks
                                ani.dantotsu.util.Logger.log("Comick: Checking MalSync for slug")
                                val quicklinks = try {
                                    kotlinx.coroutines.withTimeout(10000L) {
                                        withContext(Dispatchers.IO) {
                                            val mediaType = if (media.anime != null) "anime" else "manga"
                                            MalSyncApi.getQuicklinks(media.id, media.idMAL, mediaType)
                                        }
                                    }
                                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                    ani.dantotsu.util.Logger.log("Comick: MalSync timeout after 10 seconds")
                                    null
                                } catch (e: Exception) {
                                    ani.dantotsu.util.Logger.log("Comick: MalSync error: ${e.message}")
                                    ani.dantotsu.util.Logger.log(e)
                                    null
                                }

                                // Get ALL Comick slugs from MalSync (can be multiple entries)
                                val malSyncSlugs = quicklinks?.Sites?.entries?.firstOrNull {
                                    it.key.equals("Comick", true) || it.key.contains("comick", true)
                                }?.value?.values?.mapNotNull { it.identifier } ?: emptyList()

                                ani.dantotsu.util.Logger.log("Comick: MalSync returned ${malSyncSlugs.size} slug(s): $malSyncSlugs")

                                // Always try search API to ensure we get the best match
                                ani.dantotsu.util.Logger.log("Comick: Starting search and comparison")
                                comickSlug = withContext(Dispatchers.IO) {
                                    // Build a list of titles to try, prioritizing English titles
                                    val titlesToTry = mutableListOf<String>()

                                    // Add main English title first
                                    media.name?.let { titlesToTry.add(it) }

                                    // Add English synonyms (filter out non-Latin titles)
                                    val englishSynonyms = filterEnglishTitles(media.synonyms)
                                    englishSynonyms.forEach { synonym ->
                                        if (!titlesToTry.contains(synonym)) {
                                            titlesToTry.add(synonym)
                                        }
                                    }

                                    // Add romaji title as fallback
                                    if (!titlesToTry.contains(media.nameRomaji)) {
                                        titlesToTry.add(media.nameRomaji)
                                    }

                                    if (titlesToTry.isNotEmpty()) {
                                        // Debug: Check if externalLinks exists on Media object
                                        ani.dantotsu.util.Logger.log("Comick: DEBUG - media.externalLinks size: ${media.externalLinks.size}, content: ${media.externalLinks}")

                                        // Extract external link URLs for validation
                                        val externalLinkUrls = media.externalLinks.mapNotNull { it.getOrNull(1) }
                                        ani.dantotsu.util.Logger.log("Comick: Found ${externalLinkUrls.size} external link(s) for validation: $externalLinkUrls")

                                        // Pass MalSync slugs and external links for comparison
                                        ComickApi.searchAndMatchComic(
                                            titlesToTry,
                                            media.id,
                                            media.idMAL,
                                            malSyncSlugs.takeIf { it.isNotEmpty() },
                                            externalLinkUrls.takeIf { it.isNotEmpty() }
                                        )
                                    } else {
                                        null
                                    }
                                }
                            }
                        } else {
                            ani.dantotsu.util.Logger.log("Comick: Using preloaded slug '$comickSlug'")
                        }

                        if (comickSlug == null) {
                            ani.dantotsu.util.Logger.log("Comick: No slug found, showing search")
                            model.comickSlug.postValue(null)
                            model.comickLoaded.postValue(true)
                            loaded = true
                            binding.mediaInfoProgressBar.visibility = View.GONE
                            binding.mediaInfoContainer.visibility = View.VISIBLE
                            showNoDataWithSearch(media)
                            return@launch
                        }

                        ani.dantotsu.util.Logger.log("Comick: Found slug '$comickSlug', fetching details")
                        // Store the slug in ViewModel so the tab can use it
                        model.comickSlug.postValue(comickSlug)
                        val comickData = withContext(Dispatchers.IO) {
                            ComickApi.getComicDetails(comickSlug)
                        }

                        if (comickData == null) {
                            ani.dantotsu.util.Logger.log("Comick: Failed to fetch comic details")
                            loaded = true
                            binding.mediaInfoProgressBar.visibility = View.GONE
                            binding.mediaInfoContainer.visibility = View.VISIBLE
                            showNoDataWithSearch(media)
                            return@launch
                        }

                        ani.dantotsu.util.Logger.log("Comick: Got comic data, displaying info")

                        // Store MangaUpdates link if available
                        val muLink = comickData.comic?.links?.mu
                        if (!muLink.isNullOrBlank()) {
                            model.mangaUpdatesLink.postValue("https://www.mangaupdates.com/series/$muLink")
                        } else {
                            model.mangaUpdatesLink.postValue(null)
                        }

                        model.comickLoaded.postValue(true)
                        loaded = true
                        binding.mediaInfoProgressBar.visibility = View.GONE
                        binding.mediaInfoContainer.visibility = View.VISIBLE

                        displayComickInfo(comickData)
                        ani.dantotsu.util.Logger.log("Comick: Display complete")

                    } catch (e: Exception) {
                        model.comickSlug.postValue(null)
                        model.comickLoaded.postValue(true)
                        loaded = true
                        binding.mediaInfoProgressBar.visibility = View.GONE
                        binding.mediaInfoContainer.visibility = View.VISIBLE
                        ani.dantotsu.util.Logger.log("Comick error: ${e.message}")
                        ani.dantotsu.util.Logger.log(e)
                        showNoDataWithSearch(media)
                    }
                }
    }

    private fun showError(message: String) {
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.GONE

        val frameLayout = binding.mediaInfoContainer.parent as? ViewGroup
        frameLayout?.let {
            val errorView = layoutInflater.inflate(
                android.R.layout.simple_list_item_1,
                it,
                false
            )
            (errorView as? android.widget.TextView)?.apply {
                text = message
                val padding = 32f.px
                setPadding(padding, padding, padding, padding)
                textSize = 16f
            }
            it.addView(errorView)
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun showQuickSearchModal(media: Media) {
        val context = requireContext()
        val titles = ArrayList<String>()
        titles.add(media.userPreferredName)
        if (media.nameRomaji != media.userPreferredName) {
            titles.add(media.nameRomaji)
        }

        // Filter English titles
        val englishSynonyms = filterEnglishTitles(media.synonyms)
        englishSynonyms.forEach { if (!titles.contains(it)) titles.add(it) }

        val modal = ani.dantotsu.others.CustomBottomDialog.newInstance().apply {
            setTitleText(context.getString(R.string.search_on_comick_title))

            // Add each title as a clickable TextView
            titles.forEach { title ->
                val textView = android.widget.TextView(context).apply {
                    text = title
                    textSize = 16f
                    val padding = 16f.px
                    setPadding(padding, padding, padding, padding)
                    setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.bg_opp))
                    // Use a simple rounded background with ripple effect
                    val outValue = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        // Perform search and show results in-tab
                        showComickSearchResults(media, title)
                        dismiss()
                    }
                }
                addView(textView)
            }
        }
        modal.show(parentFragmentManager, "comick_quick_search")
    }

    private fun showComickSearchResults(media: Media, initialQuery: String) {
        // Capture context early to avoid issues if fragment gets detached
        val fragmentContext = requireContext()

        // Hide the current content
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.GONE

        val frameLayout = binding.mediaInfoContainer.parent as? ViewGroup

        frameLayout?.let { container ->
            // IMPORTANT: Remove all previous views to prevent overlap
            container.removeAllViews()

            // Inflate search layout
            val searchView = layoutInflater.inflate(
                R.layout.fragment_search_page,
                container,
                false
            )

            val searchBarLayout = searchView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.searchBarLayout)
            val searchBar = searchView.findViewById<android.widget.AutoCompleteTextView>(R.id.searchBarText)
            val searchProgress = searchView.findViewById<android.widget.ProgressBar>(R.id.searchProgress)
            val emptyMessage = searchView.findViewById<android.widget.TextView>(R.id.emptyMessage)
            val recyclerView = searchView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.searchRecyclerView)

            // Build list of title options for dropdown
            val titleOptions = mutableListOf<String>()
            titleOptions.add(media.userPreferredName)
            if (media.nameRomaji != media.userPreferredName) {
                titleOptions.add(media.nameRomaji)
            }
            val englishSynonyms = filterEnglishTitles(media.synonyms)
            englishSynonyms.forEach { if (!titleOptions.contains(it)) titleOptions.add(it) }

            // Set up RecyclerView
            recyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(
                fragmentContext,
                androidx.core.math.MathUtils.clamp(
                    fragmentContext.resources.displayMetrics.widthPixels / 124f.px,
                    1, 4
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
                        // Get adult content preference
                        val allowAdult = PrefManager.getVal<Boolean>(PrefName.AdultOnly)

                        val results = withContext(Dispatchers.IO) {
                            ComickApi.searchComics(query, allowAdult)
                        }

                        withContext(Dispatchers.Main) {
                            searchProgress.visibility = View.GONE
                            if (results.isNullOrEmpty()) {
                                emptyMessage.visibility = View.VISIBLE
                                recyclerView.visibility = View.GONE
                            } else {
                                emptyMessage.visibility = View.GONE
                                recyclerView.visibility = View.VISIBLE
                                recyclerView.adapter = ComickSearchAdapter(results) { selectedComic ->
                                    // Save the selection - user knows it's correct, no verification needed
                                    selectedComic.slug?.let { slug ->
                                        saveComickSelection(media, slug)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            searchProgress.visibility = View.GONE
                            emptyMessage.visibility = View.VISIBLE
                            emptyMessage.text = fragmentContext.getString(R.string.error_loading_data)
                            Toast.makeText(fragmentContext, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            // Add dropdown icon to search bar if there are multiple titles
            if (titleOptions.size > 1) {
                searchBarLayout.endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_DROPDOWN_MENU

                // Set up dropdown when end icon is clicked
                searchBarLayout.setEndIconOnClickListener {
                    val adapter = android.widget.ArrayAdapter(fragmentContext, R.layout.item_titles_dropdown, titleOptions)
                    val popup = androidx.appcompat.widget.ListPopupWindow(fragmentContext)
                    popup.anchorView = searchBar
                    popup.setAdapter(adapter)
                    popup.isModal = true

                    searchBarLayout.post {
                        popup.width = searchBarLayout.width
                        popup.verticalOffset = searchBarLayout.height
                        popup.setBackgroundDrawable(
                            androidx.core.content.ContextCompat.getDrawable(fragmentContext, R.drawable.dropdown_background)
                        )
                        try { popup.listView?.elevation = 12f } catch (_: Throwable) {}
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
                    val imm = fragmentContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(searchBar.windowToken, 0)
                    true
                } else false
            }

            container.addView(searchView)

            // Perform initial search
            performSearch(initialQuery)
        }
    }

    private fun saveComickSelection(media: Media, slug: String) {
        // Check if fragment is still attached
        if (!isAdded || context == null) return

        val model: MediaDetailsViewModel by activityViewModels()

        // Save the slug to preferences - user selected this, so we trust it
        PrefManager.setCustomVal("comick_slug_${media.id}", slug)

        // Update ViewModel
        model.comickSlug.postValue(slug)

        // Show progress and fetch the selected entry directly
        binding.mediaInfoProgressBar.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val comickData = withContext(Dispatchers.IO) {
                    ComickApi.getComicDetails(slug)
                }

                withContext(Dispatchers.Main) {
                    if (comickData == null) {
                        binding.mediaInfoProgressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Failed to fetch Comick data", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }

                    // Store MangaUpdates link if available
                    val muLink = comickData.comic?.links?.mu
                    if (!muLink.isNullOrBlank()) {
                        model.mangaUpdatesLink.postValue("https://www.mangaupdates.com/series/$muLink")
                    }

                    model.comickLoaded.postValue(true)
                    loaded = true

                    // Clear the search view and restore the original layout
                    val frameLayout = binding.root.findViewById<ViewGroup>(R.id.mediaInfoScroll)
                        ?.getChildAt(0) as? ViewGroup
                    frameLayout?.let { parent ->
                        parent.removeAllViews()
                        parent.addView(binding.mediaInfoProgressBar)
                        parent.addView(binding.mediaInfoContainer)
                    }

                    binding.mediaInfoProgressBar.visibility = View.GONE
                    binding.mediaInfoContainer.visibility = View.VISIBLE
                    displayComickInfo(comickData)

                    Toast.makeText(requireContext(), "Successfully linked to Comick entry", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.mediaInfoProgressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun unlinkComickSelection(media: Media) {
        // Check if fragment is still attached
        if (!isAdded || context == null) return

        val model: MediaDetailsViewModel by activityViewModels()

        // Remove the saved slug from preferences
        PrefManager.removeCustomVal("comick_slug_${media.id}")

        // Clear ViewModel
        model.comickSlug.postValue(null)

        // Reset loaded flag and show quick search
        loaded = false
        showNoDataWithSearch(media)

        Toast.makeText(requireContext(), "Comick entry unlinked", Toast.LENGTH_SHORT).show()
    }

    private fun showNoDataWithSearch(media: Media) {
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.GONE

        val frameLayout = binding.mediaInfoContainer.parent as? ViewGroup

        frameLayout?.let { container ->
            // Use fragment_mangaupdates_page.xml style layout
            val pageView = layoutInflater.inflate(
                ani.dantotsu.R.layout.fragment_nodata_page,
                container,
                false
            )

            // Set logo
            pageView.findViewById<android.widget.ImageView>(ani.dantotsu.R.id.logo)?.setImageResource(ani.dantotsu.R.drawable.ic_round_comick_24)

            // Set title for Comick
            pageView.findViewById<android.widget.TextView>(ani.dantotsu.R.id.title)?.setText(ani.dantotsu.R.string.search_on_comick)

            // Single button: Quick Search
            pageView.findViewById<com.google.android.material.button.MaterialButton>(ani.dantotsu.R.id.quickSearchButton)?.apply {
                text = getString(ani.dantotsu.R.string.quick_search)
                icon = context.getDrawable(ani.dantotsu.R.drawable.ic_round_search_24)
                setOnClickListener {
                    showQuickSearchModal(media)
                }
            }

            container.addView(pageView)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun displayComickInfo(comickData: ComickResponse) {
        if (_binding == null) {
            ani.dantotsu.util.Logger.log("Comick: binding is null in displayComickInfo")
            return
        }

        val parent = binding.mediaInfoContainer
        val comic = comickData.comic
        if (comic == null) {
            ani.dantotsu.util.Logger.log("Comick: comic data is null")
            return
        }

        // Check if this is a manually selected entry (saved in preferences)
        val model: MediaDetailsViewModel by activityViewModels()
        val media = model.getMedia().value
        val isManualSelection = media?.let {
            PrefManager.getNullableCustomVal<String>("comick_slug_${it.id}", null, String::class.java) != null
        } ?: false

        // Display Title
        binding.mediaInfoName.text = tripleTab + (comic.title ?: "Unknown")
        binding.mediaInfoName.setOnLongClickListener {
            copyToClipboard(comic.title ?: "")
            true
        }

        // Display Romaji Title based on country of origin
        val romajiLang = when (comic.country?.lowercase()) {
            "jp" -> "ja-ro"
            "kr" -> "ko-ro"
            "cn" -> "zh-ro"
            else -> null
        }

        val romajiTitle = romajiLang?.let { lang ->
            comic.md_titles?.firstOrNull { it.lang == lang }?.title
        }

        if (romajiTitle != null) {
            binding.mediaInfoNameRomajiContainer.visibility = View.VISIBLE
            binding.mediaInfoNameRomaji.text = tripleTab + romajiTitle
            binding.mediaInfoNameRomaji.setOnLongClickListener {
                copyToClipboard(romajiTitle)
                true
            }
        } else {
            binding.mediaInfoNameRomajiContainer.visibility = View.GONE
        }
        binding.mediaInfoMeanScore.text = comic.bayesian_rating ?: "??"

        // Status - Publication status only
        binding.mediaInfoStatus.text = when (comic.status) {
            1 -> "Ongoing"
            2 -> "Completed"
            3 -> "Cancelled"
            4 -> "Hiatus"
            else -> "Unknown"
        }

        // Translation - Separate field for translation status
        binding.mediaInfoTranslationContainer.visibility = View.VISIBLE
        binding.mediaInfoTranslation.text = if (comic.translation_completed == true) {
            "Completed"
        } else {
            "Ongoing"
        }

        // Change Format label to Demographic
        binding.mediaInfoFormatLabel.text = "Demographic"

        // Demographic
        binding.mediaInfoFormat.text = when (comic.demographic) {
            1 -> "Shounen"
            2 -> "Shoujo"
            3 -> "Seinen"
            4 -> "Josei"
            else -> "Unknown"
        }

        // Change Source label to Format
        binding.mediaInfoSourceLabel.text = "Format"

        // Source/Format - Country of origin (Manga/Manhwa/Manhua)
        binding.mediaInfoSource.text = when (comic.country?.lowercase()) {
            "jp" -> "Manga"
            "kr" -> "Manhwa"
            "cn" -> "Manhua"
            else -> comic.country?.uppercase() ?: "Unknown"
        }

        // Change Start Date label to Published
        binding.mediaInfoStart.parent?.let { tableRow ->
            if (tableRow is ViewGroup && tableRow.childCount > 0) {
                val label = tableRow.getChildAt(0)
                if (label is android.widget.TextView) {
                    label.text = "Published"
                }
            }
        }
        // Published (Year)
        binding.mediaInfoStart.text = comic.year?.toString() ?: "??"

        // Hide End Date field - Comick doesn't provide actual end dates
        binding.mediaInfoEnd.parent?.let { parent ->
            if (parent is ViewGroup) {
                parent.visibility = View.GONE
            }
        }

        // Change Popularity label to Followers
        binding.mediaInfoPopularity.parent?.let { tableRow ->
            if (tableRow is ViewGroup && tableRow.childCount > 0) {
                val label = tableRow.getChildAt(0)
                if (label is android.widget.TextView) {
                    label.text = "Followers"
                }
            }
        }
        // Followers (user_follow_count)
        binding.mediaInfoPopularity.text = comic.user_follow_count?.toString() ?: "??"

        // Change Favorites label to Ranking
        binding.mediaInfoFavorites.parent?.let { tableRow ->
            if (tableRow is ViewGroup && tableRow.childCount > 0) {
                val label = tableRow.getChildAt(0)
                if (label is android.widget.TextView) {
                    label.text = "Ranking"
                }
            }
        }
        // Ranking (follow_rank)
        binding.mediaInfoFavorites.text = "#${comic.follow_rank ?: "??"}"

        // Check if we should hide Latest Chapter (when completed and latest >= final)
        val finalChapterStr = comic.final_chapter
        val isCompleted = comic.status == 2 // 2 = Completed
        val isTranslationCompleted = comic.translation_completed == true
        val lastChapter = comic.last_chapter

        // Parse final_chapter string to Double for comparison
        val finalChapterNum = finalChapterStr?.toDoubleOrNull()

        // Debug logging
        ani.dantotsu.util.Logger.log("Comick: Latest Chapter Debug - lastChapter=$lastChapter, finalChapter=$finalChapterStr, finalChapterNum=$finalChapterNum")
        ani.dantotsu.util.Logger.log("Comick: Status - isCompleted=$isCompleted, isTranslationCompleted=$isTranslationCompleted")

        // Hide latest chapter if:
        // 1. Both status and translation are completed
        // 2. Latest chapter exists and final chapter exists
        // 3. Latest chapter is >= final chapter (handles cases like 26.5 vs 26)
        val shouldHideLatestChapter = isCompleted && isTranslationCompleted &&
                                      lastChapter != null && finalChapterNum != null &&
                                      lastChapter >= finalChapterNum

        ani.dantotsu.util.Logger.log("Comick: shouldHideLatestChapter=$shouldHideLatestChapter")

        // Latest Chapter - hide if both status and translation are completed and latest >= final
        if (shouldHideLatestChapter) {
            binding.mediaInfoTotal.parent?.let { parent ->
                if (parent is ViewGroup) {
                    parent.visibility = View.GONE
                }
            }
        } else {
            binding.mediaInfoTotal.parent?.let { parent ->
                if (parent is ViewGroup) {
                    parent.visibility = View.VISIBLE
                }
            }
            binding.mediaInfoTotalTitle.setText(ani.dantotsu.R.string.latest_chapter)
            binding.mediaInfoTotal.text = lastChapter?.let {
                if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
            } ?: "??"
        }

        // Final Chapter (using duration container)
        val finalVolume = comic.final_volume
        if (finalChapterStr != null || finalVolume != null) {
            binding.mediaInfoDurationContainer.visibility = View.VISIBLE

            // Change the label to "Final Chapter"
            binding.mediaInfoDurationContainer.let { container ->
                if (container.childCount > 0) {
                    val label = container.getChildAt(0)
                    if (label is android.widget.TextView) {
                        label.text = getString(ani.dantotsu.R.string.final_chapter)
                    }
                }
            }

            // Format the text based on what's available
            binding.mediaInfoDuration.text = when {
                finalVolume != null && finalChapterStr != null -> "Volume $finalVolume, Chapter $finalChapterStr"
                finalChapterStr != null -> "Chapter $finalChapterStr"
                finalVolume != null -> "Volume $finalVolume"
                else -> "??"
            }
        } else {
            binding.mediaInfoDurationContainer.visibility = View.GONE
        }

        // Description
        val rawDescription = comic.parsed ?: comic.desc ?: "No description available"
        val parsedDescription = HtmlCompat.fromHtml(
            rawDescription,
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        binding.mediaInfoDescription.text = tripleTab + parsedDescription
        binding.mediaInfoDescription.setOnClickListener {
            if (binding.mediaInfoDescription.maxLines == 5) {
                android.animation.ObjectAnimator.ofInt(binding.mediaInfoDescription, "maxLines", 100)
                    .setDuration(950).start()
            } else {
                android.animation.ObjectAnimator.ofInt(binding.mediaInfoDescription, "maxLines", 5)
                    .setDuration(400).start()
            }
        }

        // Add Synonyms / Alternative Titles (English only from md_titles)
        val mdTitles = comic.md_titles
        if (!mdTitles.isNullOrEmpty()) {
            // Filter for English titles only
            val englishTitles = mdTitles.filter {
                it.lang?.equals("en", ignoreCase = true) == true && !it.title.isNullOrBlank()
            }.mapNotNull { it.title }

            if (englishTitles.isNotEmpty() && parent.findViewWithTag<View>("synonyms_comick") == null) {
                val bind = ani.dantotsu.databinding.ItemTitleChipgroupBinding.inflate(
                    LayoutInflater.from(context),
                    parent,
                    false
                )
                bind.itemTitle.text = "Synonyms"

                englishTitles.forEach { title ->
                    val chip = ItemChipSynonymBinding.inflate(
                        LayoutInflater.from(context),
                        bind.itemChipGroup,
                        false
                    ).root
                    chip.text = title
                    chip.setOnLongClickListener {
                        copyToClipboard(title)
                        Toast.makeText(requireContext(), "Copied: $title", Toast.LENGTH_SHORT).show()
                        true
                    }
                    bind.itemChipGroup.addView(chip)
                }

                bind.root.tag = "synonyms_comick"
                parent.addView(bind.root)
            }
        }

        // Add Anime Info if available
        if (comic.has_anime == true && comic.anime != null) {
            val animeInfo = comic.anime
            if (parent.findViewWithTag<View>("anime_info_comick") == null) {
                val bind = ItemTitleTextBinding.inflate(LayoutInflater.from(context), parent, false)
                bind.itemTitle.text = "Anime Adaptation"

                val infoText = buildString {
                    if (!animeInfo.start.isNullOrBlank()) {
                        append("\nStart: ${animeInfo.start}")
                    }
                    if (!animeInfo.end.isNullOrBlank()) {
                        append("\nEnd: ${animeInfo.end}")
                    }
                }

                bind.itemText.text = infoText
                
                // Make text expandable on click
                bind.itemText.setOnClickListener {
                    if (bind.itemText.maxLines == 4) {
                        android.animation.ObjectAnimator.ofInt(bind.itemText, "maxLines", 100)
                            .setDuration(400).start()
                    } else {
                        android.animation.ObjectAnimator.ofInt(bind.itemText, "maxLines", 4)
                            .setDuration(400).start()
                    }
                }
                
                bind.itemText.setOnLongClickListener {
                    copyToClipboard(infoText)
                    Toast.makeText(requireContext(), "Copied anime info", Toast.LENGTH_SHORT).show()
                    true
                }

                bind.root.tag = "anime_info_comick"
                parent.addView(bind.root)
            }
        }

        // Quicklinks removed - use tabs instead

        // Add Genres section - grouped by type (Genre, Format, Theme)
        val genres = comic.md_comic_md_genres
        ani.dantotsu.util.Logger.log("Comick: genres list size = ${genres?.size}")
        if (!genres.isNullOrEmpty()) {
            // Group genres by their type (case-insensitive)
            val genresByGroup = genres.mapNotNull { it.md_genres }.groupBy { it.group?.lowercase() }
            ani.dantotsu.util.Logger.log("Comick: genresByGroup = ${genresByGroup.keys}")

            // Order: Genre (Genres), Theme, Format, Content
            val orderedGroups = listOf(
                "genre" to "Genres:",
                "theme" to "Theme:",
                "content" to "Content:",
                "format" to "Format:"
            )

            orderedGroups.forEach { (groupType, label) ->
                // Check if this specific genre type section already exists
                if (parent.findViewWithTag<View>("genres_${groupType}_comick") != null) {
                    ani.dantotsu.util.Logger.log("Comick: Tag already exists for $groupType")
                    return@forEach
                }

                val groupGenres = genresByGroup[groupType]
                ani.dantotsu.util.Logger.log("Comick: $groupType has ${groupGenres?.size} genres")
                if (groupGenres.isNullOrEmpty()) return@forEach

                // Use single-line chip group for genres
                val bind = ani.dantotsu.databinding.ItemTitleChipgroupBinding.inflate(
                    LayoutInflater.from(context),
                    parent,
                    false
                )
                bind.itemTitle.text = label

                // Add genre chips
                var chipCount = 0
                groupGenres.forEach { genre ->
                    val genreName = genre.name ?: run {
                        ani.dantotsu.util.Logger.log("Comick: Genre has no name")
                        return@forEach
                    }
                    val genreSlug = genre.slug ?: run {
                        ani.dantotsu.util.Logger.log("Comick: Genre '$genreName' has no slug")
                        return@forEach
                    }

                    val chip = ItemChipBinding.inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
                    chip.text = genreName

                    // Normal tap: search by genre/tag
                    chip.setOnClickListener {
                        val url = "https://comick.dev/search?genres=$genreSlug"
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    }

                    // Long tap: copy genre/tag name
                    chip.setOnLongClickListener {
                        copyToClipboard(genreName)
                        android.widget.Toast.makeText(requireContext(), "Copied: $genreName", android.widget.Toast.LENGTH_SHORT).show()
                        true
                    }

                    bind.itemChipGroup.addView(chip)
                    chipCount++
                }

                ani.dantotsu.util.Logger.log("Comick: Added $chipCount chips for $groupType")
                bind.root.tag = "genres_${groupType}_comick"
                parent.addView(bind.root)
                ani.dantotsu.util.Logger.log("Comick: Added genre section for $groupType to parent")
            }
        }

        // Add Tags (mu_comic_categories)
        val categories = comic.mu_comics?.mu_comic_categories
        if (!categories.isNullOrEmpty()) {
            // Sort by positive votes (popularity)
            val sortedCategories = categories.sortedByDescending { it.positive_vote ?: 0 }

            if (parent.findViewWithTag<View>("tags_comick") == null) {
                val bind = ani.dantotsu.databinding.ItemTitleChipgroupMultilineBinding.inflate(
                    LayoutInflater.from(context),
                    parent,
                    false
                )
                bind.itemTitle.text = "Tags"


                sortedCategories.forEach { category ->
                    val categoryInfo = category.mu_categories ?: return@forEach
                    val title = categoryInfo.title ?: return@forEach
                    val tagSlug = categoryInfo.slug ?: return@forEach

                    val chip = ItemChipBinding.inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
                    chip.text = title

                    // Normal tap: search by tag
                    chip.setOnClickListener {
                        val url = "https://comick.dev/search?tags=$tagSlug"
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    }

                    // Long tap: copy tag name
                    chip.setOnLongClickListener {
                        copyToClipboard(title)
                        Toast.makeText(requireContext(), "Copied: $title", Toast.LENGTH_SHORT).show()
                        true
                    }

                    bind.itemChipGroup.addView(chip)
                }

                bind.root.tag = "tags_comick"
                parent.addView(bind.root)
            }
        }

        // Add Recommendations - Show Comick's recommendations but reuse AniList data for matches
        val recommendations = comic.recommendations
        if (!recommendations.isNullOrEmpty() && parent.findViewWithTag<View>("recommendations_comick") == null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val model: MediaDetailsViewModel by activityViewModels()
                val currentAnilistId = model.getMedia().value?.id
                val anilistRecommendations = model.getMedia().value?.recommendations
                val recommendedMedia = mutableListOf<ani.dantotsu.media.Media>()

                // Create a map of AniList ID to Media for quick lookup
                val anilistById = anilistRecommendations?.associateBy { it.id } ?: emptyMap()

                // Only process recommendations that have a slug
                val recsWithSlug = recommendations.filter { rec ->
                    rec.relates?.slug != null && !rec.relates.slug.isBlank()
                }

                // Process Comick recommendations
                for (rec in recsWithSlug) {
                    val slug = rec.relates?.slug ?: continue

                    try {
                        // Get Comick details to find the AniList ID
                        val details = withContext(Dispatchers.IO) {
                            ComickApi.getComicDetails(slug)
                        }

                        val anilistId = details?.comic?.links?.al?.toIntOrNull()
                        if (anilistId != null && anilistId != currentAnilistId) {
                            // Check if we already have this in AniList data (avoid API call)
                            val existingMedia = anilistById[anilistId]
                            if (existingMedia != null) {
                                recommendedMedia.add(existingMedia)
                            } else {
                                // Only fetch from API if not already loaded
                                val media = withContext(Dispatchers.IO) {
                                    try {
                                        ani.dantotsu.connections.anilist.Anilist.query.getMedia(anilistId)
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                                if (media != null) {
                                    recommendedMedia.add(media)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Skip this recommendation if there's an error
                        continue
                    }
                }

                // Display recommendations if we have any
                if (recommendedMedia.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        ani.dantotsu.databinding.ItemTitleRecyclerBinding.inflate(
                            LayoutInflater.from(context),
                            parent,
                            false
                        ).apply {
                            itemTitle.setText(ani.dantotsu.R.string.recommended)
                            itemRecycler.adapter = ani.dantotsu.media.MediaAdaptor(
                                0,
                                recommendedMedia,
                                requireActivity()
                            )
                            itemRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                                requireContext(),
                                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                                false
                            )
                            root.tag = "recommendations_comick"
                            parent.addView(root)
                        }
                    }
                }
            }
        }

        // Add "Unlink" button at the bottom if this is a manual selection
        if (isManualSelection && media != null) {
            val unlinkButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
                text = "Unlink Entry"
                icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(requireContext(), R.drawable.ic_round_close_24)
                iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_START
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    val margin = 16f.px
                    setMargins(margin, margin, margin, margin)
                }
                setOnClickListener {
                    unlinkComickSelection(media)
                }
            }
            parent.addView(unlinkButton) // Add as last child
        }
    }
}
