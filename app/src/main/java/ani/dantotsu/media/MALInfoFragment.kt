package ani.dantotsu.media

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import androidx.core.text.HtmlCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemChipSynonymBinding
import ani.dantotsu.databinding.ItemTitleChipgroupBinding
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.isOnline
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ani.dantotsu.setSafeOnClickListener
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class MALInfoFragment : Fragment() {
    private var _binding: FragmentMediaInfoBinding? = null
    private val binding get() = _binding!!
    private var loaded = false

    private val tripleTab = "\t\t\t"

    /**
     * Get list of available titles for searching from MAL UI
     */
    private fun getAvailableTitles(): ArrayList<String> {
        val titles = ArrayList<String>()

        // Add main title from MAL
        binding.mediaInfoName.text?.toString()?.trim()?.let {
            if (it.isNotBlank()) titles.add(it)
        }

        // Add romaji title if different and visible
        if (binding.mediaInfoNameRomajiContainer.visibility == View.VISIBLE) {
            binding.mediaInfoNameRomaji.text?.toString()?.trim()?.let { romaji ->
                if (romaji.isNotBlank() && !titles.contains(romaji)) {
                    titles.add(romaji)
                }
            }
        }

        return titles
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

    @SuppressLint("SetJavaScriptEnabled")
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

        // Check if MAL is logged in
        lifecycleScope.launch {
            val isLoggedIn = withContext(Dispatchers.IO) {
                MAL.getSavedToken()
            }

            model.getMedia().observe(viewLifecycleOwner) { media ->
                if (!isLoggedIn && !loaded) {
                    loaded = true
                    showNotLoggedIn(media)
                    return@observe
                }

                if (isLoggedIn) {
                val m = media ?: return@observe
                if (!loaded) {
                    if (m.idMAL == null) {
                        // No MAL ID, show search option
                        showNoDataWithSearch(m)
                        loaded = true
                        return@observe
                    }

                    lifecycleScope.launch {
                        try {
                            binding.mediaInfoProgressBar.visibility = View.VISIBLE
                            binding.mediaInfoContainer.visibility = View.GONE

                            val malData = withContext(Dispatchers.IO) {
                                if (m.anime != null) {
                                    MAL.query.getAnimeDetails(m.idMAL!!)
                                } else {
                                    MAL.query.getMangaDetails(m.idMAL!!)
                                }
                            }

                            if (malData == null) {
                                showNoDataWithSearch(m)
                                loaded = true
                                return@launch
                            }

                            loaded = true
                            binding.mediaInfoProgressBar.visibility = View.GONE
                            binding.mediaInfoContainer.visibility = View.VISIBLE

                            val parent = _binding?.mediaInfoContainer ?: return@launch
                            val screenWidth = resources.displayMetrics.run { widthPixels / density }

                            // Display MAL data
                            if (m.anime != null) {
                                displayAnimeInfo(malData as ani.dantotsu.connections.mal.MALAnimeResponse, parent, screenWidth, offline, m.id, m.idMAL!!)
                            } else {
                                displayMangaInfo(malData as ani.dantotsu.connections.mal.MALMangaResponse, parent, screenWidth, offline, m.id, m.idMAL!!)
                            }

                        } catch (e: Exception) {
                            showNoDataWithSearch(m)
                            loaded = true
                        }
                    }
                }
                }
            }
        }
    }

    private fun showNotLoggedIn(media: ani.dantotsu.media.Media?) {
        // Use local nullable binding to avoid NPE if view is destroyed while coroutine resumes
        val b = _binding ?: return
        b.mediaInfoProgressBar.visibility = View.GONE
        b.mediaInfoContainer.visibility = View.GONE

        if (media == null) return

        val hasData = media.idMAL != null
        val frameLayout = b.mediaInfoContainer.parent as? ViewGroup

        frameLayout?.let { container ->
            // Use fragment_mal_not_logged_in.xml with login button
            val notLoggedInView = layoutInflater.inflate(
                R.layout.fragment_not_logged_in,
                container,
                false
            )

            //set logo
            notLoggedInView.findViewById<android.widget.ImageView>(R.id.logo)?.setImageResource(R.drawable.ic_myanimelist)

            //set titles
            notLoggedInView.findViewById<android.widget.TextView>(R.id.Title)?.text = getString(R.string.mal_not_logged_in_title)
            notLoggedInView.findViewById<android.widget.TextView>(R.id.desc)?.text = getString(R.string.mal_not_logged_in_desc)

            //set button text
            notLoggedInView.findViewById<com.google.android.material.button.MaterialButton>(R.id.connectButton)?.text = getString(R.string.connect_to_mal)
            notLoggedInView.findViewById<com.google.android.material.button.MaterialButton>(R.id.connectButton)?.icon = requireContext().getDrawable(R.drawable.ic_myanimelist)

            // Connect to MAL button
            notLoggedInView.findViewById<com.google.android.material.button.MaterialButton>(R.id.connectButton)?.setOnClickListener {
                MAL.loginIntent(requireContext())
            }

            // Show either Quick Search OR Open on MAL button (never both)
            if (hasData) {
                // Show "Open on MAL" button
                notLoggedInView.findViewById<com.google.android.material.button.MaterialButton>(R.id.quickSearchButton)?.visibility = View.GONE
                notLoggedInView.findViewById<com.google.android.material.button.MaterialButton>(R.id.openButton)?.text = getString(R.string.open_on_mal)
                notLoggedInView.findViewById<com.google.android.material.button.MaterialButton>(R.id.openButton)?.apply {
                    visibility = View.VISIBLE
                    setOnClickListener {
                        val url = "https://myanimelist.net/${if (media.anime != null) "anime" else "manga"}/${media.idMAL}"
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            } else {
                // Show "Quick Search" button
                notLoggedInView.findViewById<com.google.android.material.button.MaterialButton>(R.id.openButton)?.visibility = View.GONE
                notLoggedInView.findViewById<com.google.android.material.button.MaterialButton>(R.id.quickSearchButton)?.apply {
                    visibility = View.VISIBLE
                    setOnClickListener {
                        showQuickSearchModal(media)
                    }
                }
            }

            container.addView(notLoggedInView)
        }
    }

    private fun showQuickSearchModal(media: ani.dantotsu.media.Media) {
        val context = requireContext()
        val mediaType = if (media.anime != null) "anime" else "manga"
        val titles = ArrayList<String>()
        titles.add(media.userPreferredName)
        if (media.nameRomaji != media.userPreferredName) {
            titles.add(media.nameRomaji)
        }
        media.synonyms.forEach { if (!titles.contains(it)) titles.add(it) }

        val modal = ani.dantotsu.others.CustomBottomDialog.newInstance().apply {
            setTitleText("Search on MyAnimeList")

            // Add each title as a clickable TextView
            titles.forEach { title ->
                val textView = android.widget.TextView(context).apply {
                    text = title
                    textSize = 16f
                    val padding = 16f.px
                    setPadding(padding, padding, padding, padding)
                    setTextColor(androidx.core.content.ContextCompat.getColor(context, ani.dantotsu.R.color.bg_opp))
                    // Use a simple rounded background with ripple effect
                    val outValue = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val url = "https://myanimelist.net/${mediaType}.php?q=${
                            URLEncoder.encode(title, "utf-8")
                        }&cat=$mediaType"
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        dismiss()
                    }
                }
                addView(textView)
            }
        }
        modal.show(parentFragmentManager, "mal_quick_search")
    }

    private fun showError(message: String) {
        // Avoid NPE if view destroyed before this runs
        _binding?.let { b ->
            b.mediaInfoProgressBar.visibility = View.GONE
            b.mediaInfoContainer.visibility = View.VISIBLE
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun showNoDataWithSearch(media: ani.dantotsu.media.Media) {
        // Use local nullable binding to avoid NPE if view is destroyed while coroutine resumes
        val b = _binding ?: return
        b.mediaInfoProgressBar.visibility = View.GONE
        b.mediaInfoContainer.visibility = View.GONE

        val hasData = media.idMAL != null
        val frameLayout = b.mediaInfoContainer.parent as? ViewGroup

        frameLayout?.let { container ->
            // Use fragment_mangaupdates_page.xml (logged in, no login button)
            val pageView = layoutInflater.inflate(
                R.layout.fragment_nodata_page,
                container,
                false
            )

            // set logo
            pageView.findViewById<android.widget.ImageView>(R.id.logo)?.setImageResource(R.drawable.ic_myanimelist)

            // Set title text
            pageView.findViewById<android.widget.TextView>(R.id.title)?.text = getString(R.string.search_on_myanimelist)

            // Set small subtitle message (lookup id dynamically to avoid lint/resource variant issues)
            val subtitleId = resources.getIdentifier("subtitle", "id", requireContext().packageName)
            if (subtitleId != 0) {
                pageView.findViewById<android.widget.TextView>(subtitleId)?.text = getString(R.string.search_sub_myanimelist)
            }

            // Single button: either "Go to Site" OR "Quick Search"
            pageView.findViewById<com.google.android.material.button.MaterialButton>(R.id.quickSearchButton)?.apply {
                val mediaType = if (media.anime != null) "anime" else "manga"
                if (hasData) {
                    // Show "Open on MAL" button
                    text = getString(R.string.open_on_mal)
                    icon = context.getDrawable(R.drawable.ic_open_24)
                    setOnClickListener {
                        val url = "https://myanimelist.net/${mediaType}/${media.idMAL}"
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                } else {
                    // Show "Quick Search" button
                    text = getString(R.string.quick_search)
                    icon = context.getDrawable(R.drawable.ic_round_search_24)
                    setOnClickListener {
                        showQuickSearchModal(media)
                    }
                }
            }

            container.addView(pageView)
        }
    }

    @Suppress("SetTextI18n")
    private fun displayAnimeInfo(
        malData: ani.dantotsu.connections.mal.MALAnimeResponse,
        parent: ViewGroup,
        @Suppress("UNUSED_PARAMETER") screenWidth: Float,
        offline: Boolean,
        anilistId: Int,
        malId: Int
    ) {
        // Title - MAL title is romaji, so show English/Japanese as main title if available
        val mainTitle = malData.alternativeTitles?.en ?: malData.alternativeTitles?.ja ?: malData.title
        binding.mediaInfoName.text = tripleTab + mainTitle
        binding.mediaInfoName.setOnLongClickListener {
            copyToClipboard(mainTitle)
            true
        }

        // Romaji title (MAL's default title is in romaji)
        if (malData.alternativeTitles?.en != null || malData.alternativeTitles?.ja != null) {
            binding.mediaInfoNameRomajiContainer.visibility = View.VISIBLE
            binding.mediaInfoNameRomaji.text = tripleTab + malData.title
            binding.mediaInfoNameRomaji.setOnLongClickListener {
                copyToClipboard(malData.title)
                true
            }
        }

        // Mean Score
        binding.mediaInfoMeanScore.text = malData.mean?.toString() ?: "??"

        // Status
        binding.mediaInfoStatus.text = formatStatus(malData.status)

        // Format/Media Type
        binding.mediaInfoFormat.text = formatMediaType(malData.mediaType)

        // Source
        binding.mediaInfoSource.text = formatSource(malData.source)

        // Dates
        binding.mediaInfoStart.text = formatDate(malData.startDate)
        binding.mediaInfoEnd.text = formatDate(malData.endDate)

        // Popularity
        binding.mediaInfoPopularity.text = malData.numListUsers?.toString() ?: "??"

        // Favorites (using num_scoring_users as proxy)
        binding.mediaInfoFavorites.text = malData.numScoringUsers?.toString() ?: "??"

        // Episode Duration
        if (malData.averageEpisodeDuration != null) {
            val durationInMinutes = malData.averageEpisodeDuration / 60
            val hours = durationInMinutes / 60
            val minutes = durationInMinutes % 60

            val formattedDuration = buildString {
                if (hours > 0) {
                    append("$hours hour")
                    if (hours > 1) append("s")
                }

                if (minutes > 0) {
                    if (hours > 0) append(", ")
                    append("$minutes min")
                    if (minutes > 1) append("s")
                }
            }

            binding.mediaInfoDuration.text = formattedDuration.ifEmpty { "Unknown" }
            binding.mediaInfoDurationContainer.visibility = View.VISIBLE
        }

        // Season
        if (malData.startSeason != null) {
            binding.mediaInfoSeasonContainer.visibility = View.VISIBLE
            val seasonInfo = "${malData.startSeason.season.replaceFirstChar { it.uppercase() }} ${malData.startSeason.year}"
            binding.mediaInfoSeason.text = seasonInfo
        }

        // Studio
        if (malData.studios.isNotEmpty()) {
            binding.mediaInfoStudioContainer.visibility = View.VISIBLE
            binding.mediaInfoStudio.text = malData.studios.first().name
        }

        // Total Episodes
        binding.mediaInfoTotalTitle.setText(R.string.total_eps)
        binding.mediaInfoTotal.text = if (malData.numEpisodes == null || malData.numEpisodes == 0) "~" else malData.numEpisodes.toString()

        // Description
        val desc = HtmlCompat.fromHtml(
            (malData.synopsis ?: "null").replace("\\n", "<br>").replace("\\\"", "\""),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        val infoDesc = tripleTab + if (desc.toString() != "null") desc else getString(R.string.no_description_available)
        binding.mediaInfoDescription.text = infoDesc
        binding.mediaInfoDescription.setOnClickListener {
            if (binding.mediaInfoDescription.maxLines == 5) {
                ObjectAnimator.ofInt(binding.mediaInfoDescription, "maxLines", 100)
                    .setDuration(950).start()
            } else {
                ObjectAnimator.ofInt(binding.mediaInfoDescription, "maxLines", 5)
                    .setDuration(400).start()
            }
        }

        // Synonyms/Alternative Titles
        if (!malData.alternativeTitles?.synonyms.isNullOrEmpty()) {
            val bind = ItemTitleChipgroupBinding.inflate(
                LayoutInflater.from(context),
                parent,
                false
            )
            bind.itemTitle.setText(R.string.synonyms)
            malData.alternativeTitles.synonyms.forEach { synonym ->
                val chip = ItemChipSynonymBinding.inflate(
                    LayoutInflater.from(context),
                    bind.itemChipGroup,
                    false
                ).root
                chip.text = synonym
                chip.setOnLongClickListener { copyToClipboard(synonym); true }
                bind.itemChipGroup.addView(chip)
            }
            parent.addView(bind.root)
        }

        // Genres
        if (malData.genres.isNotEmpty() && !offline) {
            val bind = ItemTitleChipgroupBinding.inflate(
                LayoutInflater.from(context),
                parent,
                false
            )
            bind.itemTitle.setText(R.string.genres)
            malData.genres.forEach { genre ->
                val chip = ItemChipBinding.inflate(
                    LayoutInflater.from(context),
                    bind.itemChipGroup,
                    false
                ).root
                chip.text = genre.name
                chip.setOnLongClickListener { copyToClipboard(genre.name); true }
                bind.itemChipGroup.addView(chip)
            }
            parent.addView(bind.root)
        }

        // Recommendations - Show MAL's recommendations but reuse AniList data for matches
        if (malData.recommendations.isNotEmpty() && !offline) {
            lifecycleScope.launch {
                val model: MediaDetailsViewModel by activityViewModels()
                val anilistRecommendations = model.getMedia().value?.recommendations
                val recommendations = mutableListOf<Media>()

                // Create a map of MAL ID to Media for quick lookup
                val anilistByMalId = anilistRecommendations?.filter { it.idMAL != null }
                    ?.associateBy { it.idMAL } ?: emptyMap()

                // Collect MAL IDs not present in AniList data
                val missingMalIds = malData.recommendations.map { it.node.id }
                    .filter { anilistByMalId[it] == null }

                // Batch fetch missing recommendations from AniList
                val batchFetched = withContext(Dispatchers.IO) {
                    try {
                        Anilist.query.getMediaBatch(missingMalIds, mal = true)
                    } catch (e: Exception) {
                        emptyList<Media>()
                    }
                }
                val batchByMalId = batchFetched.filter { it.idMAL != null }.associateBy { it.idMAL }

                // Process MAL recommendations in order
                for (recommendation in malData.recommendations) {
                    val malId = recommendation.node.id
                    val existingMedia = anilistByMalId[malId] ?: batchByMalId[malId]
                    if (existingMedia != null) {
                        recommendations.add(existingMedia)
                    }
                }

                if (recommendations.isNotEmpty()) {
                    val bind = ItemTitleRecyclerBinding.inflate(
                        LayoutInflater.from(context),
                        parent,
                        false
                    )
                    bind.itemTitle.setText(R.string.recommended)
                    bind.itemRecycler.adapter = MediaAdaptor(0, recommendations, requireActivity())
                    bind.itemRecycler.layoutManager = LinearLayoutManager(
                        requireContext(),
                        LinearLayoutManager.HORIZONTAL,
                        false
                    )
                    bind.itemMore.visibility = View.VISIBLE
                    bind.itemMore.setSafeOnClickListener {
                        MediaListViewActivity.passedMedia = ArrayList(recommendations)
                        startActivity(
                            Intent(requireContext(), MediaListViewActivity::class.java)
                                .putExtra("title", getString(R.string.recommended))
                        )
                    }
                    parent.addView(bind.root)
                }
            }
        }

    }

    @Suppress("SetTextI18n")
    private fun displayMangaInfo(
        malData: ani.dantotsu.connections.mal.MALMangaResponse,
        parent: ViewGroup,
        @Suppress("UNUSED_PARAMETER") screenWidth: Float,
        offline: Boolean,
        anilistId: Int,
        malId: Int
    ) {
        // Title - MAL title is romaji, so show English/Japanese as main title if available
        val mainTitle = malData.alternativeTitles?.en ?: malData.alternativeTitles?.ja ?: malData.title
        binding.mediaInfoName.text = tripleTab + mainTitle
        binding.mediaInfoName.setOnLongClickListener {
            copyToClipboard(mainTitle)
            true
        }

        // Romaji title (MAL's default title is in romaji)
        if (malData.alternativeTitles?.en != null || malData.alternativeTitles?.ja != null) {
            binding.mediaInfoNameRomajiContainer.visibility = View.VISIBLE
            binding.mediaInfoNameRomaji.text = tripleTab + malData.title
            binding.mediaInfoNameRomaji.setOnLongClickListener {
                copyToClipboard(malData.title)
                true
            }
        }

        // Mean Score
        binding.mediaInfoMeanScore.text = malData.mean?.toString() ?: "??"

        // Status
        binding.mediaInfoStatus.text = formatStatus(malData.status)

        // Format/Media Type
        binding.mediaInfoFormat.text = formatMediaType(malData.mediaType)

        // Source (MAL doesn't provide source for manga)
        binding.mediaInfoSourceContainer.visibility = View.GONE

        // Dates
        binding.mediaInfoStart.text = formatDate(malData.startDate)
        binding.mediaInfoEnd.text = formatDate(malData.endDate)

        // Popularity
        binding.mediaInfoPopularity.text = malData.numListUsers?.toString() ?: "??"

        // Favorites (using num_scoring_users as proxy)
        binding.mediaInfoFavorites.text = malData.numScoringUsers?.toString() ?: "??"

        // Author - prefer the one with "Story" in their role
        if (malData.authors.isNotEmpty()) {
            binding.mediaInfoAuthorContainer.visibility = View.VISIBLE
            val authorWithStory = malData.authors.find { it.role?.contains("Story", ignoreCase = true) == true }
            val author = (authorWithStory ?: malData.authors.first()).node
            val authorName = "${author.firstName ?: ""} ${author.lastName ?: ""}".trim()
            binding.mediaInfoAuthor.text = authorName
        }

        // Total Chapters
        binding.mediaInfoTotalTitle.setText(R.string.total_chaps)
        binding.mediaInfoTotal.text = if (malData.numChapters == null || malData.numChapters == 0) "~" else malData.numChapters.toString()

        // Description
        val desc = HtmlCompat.fromHtml(
            (malData.synopsis ?: "null").replace("\\n", "<br>").replace("\\\"", "\""),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        val infoDesc = tripleTab + if (desc.toString() != "null") desc else getString(R.string.no_description_available)
        binding.mediaInfoDescription.text = infoDesc
        binding.mediaInfoDescription.setOnClickListener {
            if (binding.mediaInfoDescription.maxLines == 5) {
                ObjectAnimator.ofInt(binding.mediaInfoDescription, "maxLines", 100)
                    .setDuration(950).start()
            } else {
                ObjectAnimator.ofInt(binding.mediaInfoDescription, "maxLines", 5)
                    .setDuration(400).start()
            }
        }

        // Synonyms/Alternative Titles
        if (!malData.alternativeTitles?.synonyms.isNullOrEmpty()) {
            val bind = ItemTitleChipgroupBinding.inflate(
                LayoutInflater.from(context),
                parent,
                false
            )
            bind.itemTitle.setText(R.string.synonyms)
            malData.alternativeTitles.synonyms.forEach { synonym ->
                val chip = ItemChipSynonymBinding.inflate(
                    LayoutInflater.from(context),
                    bind.itemChipGroup,
                    false
                ).root
                chip.text = synonym
                chip.setOnLongClickListener { copyToClipboard(synonym); true }
                bind.itemChipGroup.addView(chip)
            }
            parent.addView(bind.root)
        }

        // Genres
        if (malData.genres.isNotEmpty() && !offline) {
            val bind = ItemTitleChipgroupBinding.inflate(
                LayoutInflater.from(context),
                parent,
                false
            )
            bind.itemTitle.setText(R.string.genres)
            malData.genres.forEach { genre ->
                val chip = ItemChipBinding.inflate(
                    LayoutInflater.from(context),
                    bind.itemChipGroup,
                    false
                ).root
                chip.text = genre.name
                chip.setOnLongClickListener { copyToClipboard(genre.name); true }
                bind.itemChipGroup.addView(chip)
            }
            parent.addView(bind.root)
        }

        // Recommendations - Show MAL's recommendations but reuse AniList data for matches
        if (malData.recommendations.isNotEmpty() && !offline) {
            lifecycleScope.launch {
                val model: MediaDetailsViewModel by activityViewModels()
                val anilistRecommendations = model.getMedia().value?.recommendations
                val recommendations = mutableListOf<Media>()

                // Create a map of MAL ID to Media for quick lookup
                val anilistByMalId = anilistRecommendations?.filter { it.idMAL != null }
                    ?.associateBy { it.idMAL } ?: emptyMap()

                // Process MAL recommendations
                for (recommendation in malData.recommendations) {
                    val malId = recommendation.node.id

                    // Check if we already have this in AniList data (avoid API call)
                    val existingMedia = anilistByMalId[malId]
                    if (existingMedia != null) {
                        recommendations.add(existingMedia)
                    } else {
                        // Only fetch from API if not already loaded
                        val media = withContext(Dispatchers.IO) {
                            try {
                                Anilist.query.getMedia(malId, mal = true)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (media != null) {
                            recommendations.add(media)
                        }
                    }
                }

                if (recommendations.isNotEmpty()) {
                    val bind = ItemTitleRecyclerBinding.inflate(
                        LayoutInflater.from(context),
                        parent,
                        false
                    )
                    bind.itemTitle.setText(R.string.recommended)
                    bind.itemRecycler.adapter = MediaAdaptor(0, recommendations, requireActivity())
                    bind.itemRecycler.layoutManager = LinearLayoutManager(
                        requireContext(),
                        LinearLayoutManager.HORIZONTAL,
                        false
                    )
                    bind.itemMore.visibility = View.VISIBLE
                    bind.itemMore.setSafeOnClickListener {
                        MediaListViewActivity.passedMedia = ArrayList(recommendations)
                        startActivity(
                            Intent(requireContext(), MediaListViewActivity::class.java)
                                .putExtra("title", getString(R.string.recommended))
                        )
                    }
                    parent.addView(bind.root)
                }
            }
        }
    }

    private fun formatStatus(status: String?): String {
        return when (status?.lowercase()) {
            "finished_airing", "finished" -> "Finished"
            "currently_airing", "currently_publishing" -> "Releasing"
            "not_yet_aired", "not_yet_published" -> "Not Yet Released"
            "on_hiatus", "on_hold" -> "On Hiatus"
            "cancelled" -> "Cancelled"

            else -> status?.replaceFirstChar { it.uppercase() } ?: "Unknown"
        }
    }

    private fun formatMediaType(mediaType: String?): String {
        return when (mediaType?.lowercase()) {
            "unknown" -> "UNKNOWN"
            "tv" -> "TV"
            "ova" -> "OVA"
            "ona" -> "ONA"
            "movie" -> "MOVIE"
            "special" -> "SPECIAL"
            "music" -> "MUSIC"
            "manga" -> "MANGA"
            "novel" -> "NOVEL"
            "light_novel" -> "LIGHT NOVEL"
            "one_shot" -> "ONE SHOT"
            "doujinshi" -> "DOUJINSHI"
            "manhwa" -> "MANHWA"
            "manhua" -> "MANHUA"
            "oel" -> "OEL"
            else -> mediaType?.uppercase()?.replace("_", " ") ?: "UNKNOWN"
        }
    }

    private fun formatSource(source: String?): String {
        return when (source?.lowercase()) {
            "other" -> "OTHER"
            "original" -> "ORIGINAL"
            "manga" -> "MANGA"
            "4_koma_manga" -> "4 KOMA MANGA"
            "web_manga" -> "WEB MANGA"
            "digital_manga" -> "DIGITAL MANGA"
            "novel" -> "NOVEL"
            "light_novel" -> "LIGHT NOVEL"
            "visual_novel" -> "VISUAL NOVEL"
            "game" -> "GAME"
            "card_game" -> "CARD GAME"
            "book" -> "BOOK"
            "picture_book" -> "PICTURE BOOK"
            "radio" -> "RADIO"
            "music" -> "MUSIC"
            else -> source?.uppercase()?.replace("_", " ") ?: "UNKNOWN"
        }
    }

    private fun formatDate(dateString: String?): String {
        if (dateString == null) return "??"

        return try {
            // MAL dates are in format: YYYY-MM-DD
            val parts = dateString.split("-")
            if (parts.size != 3) return dateString

            val year = parts[0]
            val monthNum = parts[1].toIntOrNull() ?: return dateString
            val day = parts[2].toIntOrNull()?.toString() ?: return dateString

            // Convert month number to month name
            val monthNames = arrayOf(
                "January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"
            )
            val month = if (monthNum in 1..12) monthNames[monthNum - 1] else return dateString

            // Format as "Day Month Year" (e.g., "25 August 1989")
            "$day $month $year"
        } catch (e: Exception) {
            dateString
        }
    }
}
