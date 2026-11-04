package ani.dantotsu.media

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import ani.dantotsu.connections.mal.MALStack
import ani.dantotsu.media.StackAdapter
import ani.dantotsu.others.MalScraper
import ani.dantotsu.others.MALReview
import ani.dantotsu.util.Logger
import ani.dantotsu.util.whenViewResumed
import com.xwray.groupie.GroupieAdapter

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
        // ViewPager2's offscreenPageLimit can destroy this fragment's view while keeping the
        // fragment instance alive; without resetting this, the media observer's `if (loaded)
        // return` guard would skip re-rendering into the fresh (empty) binding.
        loaded = false
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
        // Not during the pager's slide: wait until this tab is on screen and settled.
        whenViewResumed {
            lifecycleScope.launch {
                val isLoggedIn = withContext(Dispatchers.IO) {
                    MAL.getSavedToken()
                }

                model.getMedia().observe(viewLifecycleOwner) { load(model, isLoggedIn, offline) }
                // MangaUpdates-backed media have no MAL id of their own — it's resolved in the
                // background from a cross-source mapping, so the first pass above usually has to wait.
                // Re-run once that lands.
                model.muMalLoaded.observe(viewLifecycleOwner) { load(model, isLoggedIn, offline) }
            }
        }
    }

    /**
     * Renders the tab, once. The MAL id comes from the media itself for AniList entries and from
     * [MediaDetailsViewModel.muMalId] for MangaUpdates ones.
     */
    private fun load(model: MediaDetailsViewModel, isLoggedIn: Boolean, offline: Boolean) {
        if (loaded) return
        val media = model.getMedia().value

        // A MangaUpdates series whose MAL id is still being resolved: nothing can be decided yet,
        // not even the no-match page — this renders once, so a premature page would stick around
        // after the id arrives.
        if (media?.muSeriesId != null && media.idMAL == null && model.muMalLoaded.value != true) return
        val malId = media?.idMAL ?: model.muMalId.value

        if (!isLoggedIn) {
            loaded = true
            // A custom tab has no room for a login page, and can still show what is public.
            val container = _binding?.mediaInfoContainer
            if (container?.forwardsSections == true) {
                if (malId != null && media != null) {
                    addStacks(container, malId, media.anime != null, offline)
                    addReviews(container, malId, media.anime != null, offline)
                }
                return
            }
            showNotLoggedIn(media, malId)
            return
        }

        val m = media ?: return
        if (malId == null) {
            showNoData(m, null)
            loaded = true
            return
        }

        // Mark loaded synchronously (before the suspending fetch below) so a second observer
        // firing on the same main-thread pass can't slip past the guard at the top of this
        // function and start a duplicate render.
        loaded = true

        lifecycleScope.launch {
            try {
                binding.mediaInfoProgressBar.visibility = View.VISIBLE
                binding.mediaInfoContainer.visibility = View.GONE

                val malData = withContext(Dispatchers.IO) {
                    if (m.anime != null) {
                        MAL.query.getAnimeDetails(malId)
                    } else {
                        MAL.query.getMangaDetails(malId)
                    }
                }

                if (malData == null) {
                    showNoData(m, malId)
                    return@launch
                }

                binding.mediaInfoProgressBar.visibility = View.GONE
                binding.mediaInfoContainer.visibility = View.VISIBLE

                val parent = _binding?.mediaInfoContainer ?: return@launch
                val screenWidth = resources.displayMetrics.run { widthPixels / density }

                // Display MAL data
                if (m.anime != null) {
                    displayAnimeInfo(malData as ani.dantotsu.connections.mal.MALAnimeResponse, parent, screenWidth, offline, m.id, malId)
                } else {
                    displayMangaInfo(malData as ani.dantotsu.connections.mal.MALMangaResponse, parent, screenWidth, offline, m.id, malId)
                }

            } catch (e: Exception) {
                showNoData(m, malId)
            }
        }
    }

    private fun showNotLoggedIn(media: ani.dantotsu.media.Media?, malId: Int?) {
        // Use local nullable binding to avoid NPE if view is destroyed while coroutine resumes
        val b = _binding ?: return
        b.mediaInfoProgressBar.visibility = View.GONE
        b.mediaInfoContainer.visibility = View.GONE

        if (media == null) return

        val hasData = malId != null
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

            notLoggedInView.findViewById<View>(R.id.quickSearchButton)?.visibility = View.GONE
            if (hasData) {
                // Show "Open on MAL" button
                notLoggedInView.findViewById<com.google.android.material.button.MaterialButton>(R.id.openButton)?.text = getString(R.string.open_on_mal)
                notLoggedInView.findViewById<com.google.android.material.button.MaterialButton>(R.id.openButton)?.apply {
                    visibility = View.VISIBLE
                    setOnClickListener {
                        val url = "https://myanimelist.net/${if (media.anime != null) "anime" else "manga"}/$malId"
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            } else {
                notLoggedInView.findViewById<View>(R.id.openButton)?.visibility = View.GONE
            }

            container.addView(notLoggedInView)
        }
    }

    private fun showError(message: String) {
        // Avoid NPE if view destroyed before this runs
        _binding?.let { b ->
            b.mediaInfoProgressBar.visibility = View.GONE
            b.mediaInfoContainer.visibility = View.VISIBLE
        }
        ani.dantotsu.toast(message, android.widget.Toast.LENGTH_LONG)
    }

    /** No MAL entry for this title ([malId] null), or one that couldn't be loaded. */
    private fun showNoData(media: ani.dantotsu.media.Media, malId: Int?) {
        // Use local nullable binding to avoid NPE if view is destroyed while coroutine resumes
        val b = _binding ?: return
        b.mediaInfoProgressBar.visibility = View.GONE
        b.mediaInfoContainer.visibility = View.GONE

        val container = b.mediaInfoContainer.parent as? ViewGroup ?: return
        val pageView = layoutInflater.inflate(R.layout.fragment_nodata_page, container, false)
        pageView.findViewById<android.widget.ImageView>(R.id.logo)?.setImageResource(R.drawable.ic_myanimelist)
        pageView.findViewById<android.widget.TextView>(R.id.title)?.setText(
            if (malId != null) R.string.mal_load_failed_title else R.string.mal_not_found_title
        )
        pageView.findViewById<android.widget.TextView>(R.id.subtitle)?.setText(
            if (malId != null) R.string.mal_load_failed_desc else R.string.mal_not_found_desc
        )
        pageView.findViewById<com.google.android.material.button.MaterialButton>(R.id.quickSearchButton)?.apply {
            if (malId != null) {
                val mediaType = if (media.anime != null) "anime" else "manga"
                text = getString(R.string.open_on_mal)
                icon = context.getDrawable(R.drawable.ic_round_open_in_browser_24)
                setOnClickListener {
                    val url = "https://myanimelist.net/${mediaType}/$malId"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            } else {
                visibility = View.GONE
            }
        }
        container.addView(pageView)
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

        // MAL has no favourites count in its API; this is how many people scored it, and says so.
        ((binding.mediaInfoFavorites.parent as? ViewGroup)?.getChildAt(0) as? android.widget.TextView)
            ?.setText(R.string.scored_by)
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
            parent.addInfoSection(InfoSectionKind.SYNONYMS, bind.root)
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
                val tap = MalGenreLinks.confirmingTap { MalGenreLinks.open(genre.id, true) }
                chip.setOnClickListener { tap() }
                chip.setOnLongClickListener { copyToClipboard(genre.name); true }
                bind.itemChipGroup.addView(chip)
            }
            parent.offerInfoChips(InfoSectionKind.GENRES, malData.genres.map { genre ->
                InfoChip(genre.name, opensWebsite = true) { MalGenreLinks.open(genre.id, true) }
            })
            parent.addInfoSection(InfoSectionKind.GENRES, bind.root)
        }

        // Placeholder to anchor stacks/recommendations ordering
        val stacksPlaceholder = android.widget.FrameLayout(requireContext()).apply {
            tag = "stacks_mal_placeholder"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        parent.addView(stacksPlaceholder)

        if (!offline) InfoExtraSections.addMalExtras(
            parent, malData.background, malData.pictures, malData.relatedAnime, malData.relatedManga,
        )

        // Recommendations - Show MAL's recommendations but reuse AniList data for matches
        if (malData.recommendations.isNotEmpty() && !offline && parent.wantsInfoSection(InfoSectionKind.RECOMMENDATIONS)) {
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
                        Anilist.query.getMediaBatch(missingMalIds, mal = true, mediaType = "ANIME")
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
                    bind.itemRecycler.adapter = MediaAdaptor(
                        0, recommendations, requireActivity(),
                        currentMedia = model.getMedia().value
                    )
                    bind.itemRecycler.layoutManager = LinearLayoutManager(
                        requireContext(),
                        LinearLayoutManager.HORIZONTAL,
                        false
                    )
                    bind.itemMore.visibility = View.VISIBLE
                    bind.itemMore.setSafeOnClickListener {
                        MediaListViewActivity.passedMedia = ArrayList(recommendations)
                        MediaListViewActivity.passedRecommendationSource = model.getMedia().value
                        startActivity(
                            Intent(requireContext(), MediaListViewActivity::class.java)
                                .putExtra("title", getString(R.string.recommended))
                        )
                    }
                    // Tag recommendations so stacks can identify them, and insert before placeholder so stacks remain after.
                    bind.root.tag = "recommendations_mal"
                    val placeholder = parent.findViewWithTag<View>("stacks_mal_placeholder")
                    if (placeholder != null) {
                        val idx = parent.indexOfChild(placeholder)
                        parent.addInfoSection(InfoSectionKind.RECOMMENDATIONS, bind.root, idx)
                    } else {
                        parent.addInfoSection(InfoSectionKind.RECOMMENDATIONS, bind.root)
                    }
                    parent.offerInfoMedia(InfoSectionKind.RECOMMENDATIONS, recommendations)
                }
            }
        }

        addStacks(parent, malId, isAnime = true, offline)
        addReviews(parent, malId, isAnime = true, offline)
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

        // MAL has no favourites count in its API; this is how many people scored it, and says so.
        ((binding.mediaInfoFavorites.parent as? ViewGroup)?.getChildAt(0) as? android.widget.TextView)
            ?.setText(R.string.scored_by)
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
            parent.addInfoSection(InfoSectionKind.SYNONYMS, bind.root)
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
                val tap = MalGenreLinks.confirmingTap { MalGenreLinks.open(genre.id, false) }
                chip.setOnClickListener { tap() }
                chip.setOnLongClickListener { copyToClipboard(genre.name); true }
                bind.itemChipGroup.addView(chip)
            }
            parent.offerInfoChips(InfoSectionKind.GENRES, malData.genres.map { genre ->
                InfoChip(genre.name, opensWebsite = true) { MalGenreLinks.open(genre.id, false) }
            })
            parent.addInfoSection(InfoSectionKind.GENRES, bind.root)
        }

        if (!offline) InfoExtraSections.addMalExtras(
            parent, malData.background, malData.pictures, malData.relatedAnime, malData.relatedManga,
            malData.serialization,
        )

        // Recommendations - Show MAL's recommendations but reuse AniList data for matches
        if (malData.recommendations.isNotEmpty() && !offline && parent.wantsInfoSection(InfoSectionKind.RECOMMENDATIONS)) {
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
                    bind.itemRecycler.adapter = MediaAdaptor(
                        0, recommendations, requireActivity(),
                        currentMedia = model.getMedia().value
                    )
                    bind.itemRecycler.layoutManager = LinearLayoutManager(
                        requireContext(),
                        LinearLayoutManager.HORIZONTAL,
                        false
                    )
                    bind.itemMore.visibility = View.VISIBLE
                    bind.itemMore.setSafeOnClickListener {
                        MediaListViewActivity.passedMedia = ArrayList(recommendations)
                        MediaListViewActivity.passedRecommendationSource = model.getMedia().value
                        startActivity(
                            Intent(requireContext(), MediaListViewActivity::class.java)
                                .putExtra("title", getString(R.string.recommended))
                        )
                    }
                    // Tag recommendations so stacks can identify them and ensure order
                    bind.root.tag = "recommendations_mal"
                    val placeholder = parent.findViewWithTag<View>("stacks_mal_placeholder")
                    if (placeholder != null) {
                        val idx = parent.indexOfChild(placeholder)
                        parent.addInfoSection(InfoSectionKind.RECOMMENDATIONS, bind.root, idx)
                    } else {
                        parent.addInfoSection(InfoSectionKind.RECOMMENDATIONS, bind.root)
                    }
                    parent.offerInfoMedia(InfoSectionKind.RECOMMENDATIONS, recommendations)
                }
            }
        }

        
        addStacks(parent, malId, isAnime = false, offline)
        addReviews(parent, malId, isAnime = false, offline)
    }

    /** Scraped from public pages, so this needs no login, unlike the rest of the tab. */
    private fun addStacks(parent: ViewGroup, malId: Int, isAnime: Boolean, offline: Boolean) {
        // Stacks (interest stacks) - scraped from MAL page since API doesn't expose them
        if (!offline && parent.wantsInfoSection(InfoSectionKind.STACKS)) {
            lifecycleScope.launch {
                val stacks = withContext(Dispatchers.IO) {
                    try {
                        MAL.query.getStacks(malId, isAnime)
                    } catch (e: Exception) { emptyList<MALStack>() }
                }
                val ctx = context ?: return@launch
                if (_binding == null) return@launch
                if (stacks.isNotEmpty()) {
                    val bind = ItemTitleRecyclerBinding.inflate(
                        LayoutInflater.from(ctx),
                        parent,
                        false
                    )
                    bind.itemTitle.setText(R.string.interest_stacks)
                    bind.itemRecycler.adapter = StackAdapter(stacks, isAnime)
                    bind.itemRecycler.layoutManager = LinearLayoutManager(
                        ctx,
                        LinearLayoutManager.HORIZONTAL,
                        false
                    )
                    bind.itemMore.visibility = View.VISIBLE
                    bind.itemMore.setSafeOnClickListener {
                        StackListViewActivity.passedStacks = ArrayList(stacks)
                        startActivity(
                            Intent(ctx, StackListViewActivity::class.java)
                                .putExtra("title", getString(R.string.interest_stacks))
                                .putExtra("isAnime", isAnime)
                        )
                    }
                    // If recommendations exist, insert stacks after recommendations; otherwise insert before placeholder.
                    val recView = parent.findViewWithTag<View>("recommendations_mal")
                    if (recView != null) {
                        val recIdx = parent.indexOfChild(recView)
                        parent.addInfoSection(InfoSectionKind.STACKS, bind.root, recIdx + 1)
                    } else {
                        val placeholder = parent.findViewWithTag<View>("stacks_mal_placeholder")
                        if (placeholder != null) {
                            val idx = parent.indexOfChild(placeholder)
                            parent.addInfoSection(InfoSectionKind.STACKS, bind.root, idx)
                        } else {
                            parent.addInfoSection(InfoSectionKind.STACKS, bind.root)
                        }
                    }
                }
            }
        }
    }

    /** Scraped from public pages, so this needs no login, unlike the rest of the tab. */
    private fun addReviews(parent: ViewGroup, malId: Int, isAnime: Boolean, offline: Boolean) {
        // Reviews scraped from MAL
        if (!offline && parent.wantsInfoSection(InfoSectionKind.REVIEWS)) {
            lifecycleScope.launch {
                val reviews = withContext(Dispatchers.IO) {
                    try {
                        val response = if (isAnime) MalScraper.getAnimeReviews(malId) else MalScraper.getMangaReviews(malId)
                        Logger.log("MAL Reviews: malId=$malId response=${response != null} dataSize=${response?.reviews?.size}")
                        response?.reviews ?: emptyList()
                    } catch (e: Exception) {
                        Logger.log("MAL Reviews error: ${e.message}")
                        emptyList<MALReview>()
                    }
                }
                val ctx = context ?: return@launch
                if (_binding == null) return@launch
                Logger.log("MAL Reviews: showing ${reviews.size} reviews")
                if (reviews.isNotEmpty()) {
                    val bind = ItemTitleRecyclerBinding.inflate(
                        LayoutInflater.from(ctx),
                        parent,
                        false
                    )
                    bind.itemTitle.setText(R.string.reviews)
                    val groupAdapter = GroupieAdapter()
                    reviews.take(3).forEach { groupAdapter.add(MALReviewAdapter(it)) }
                    bind.itemRecycler.adapter = groupAdapter
                    bind.itemRecycler.layoutManager = LinearLayoutManager(ctx)
                    bind.itemMore.visibility = View.VISIBLE
                    bind.itemMore.setSafeOnClickListener {
                        ctx.startActivity(
                            android.content.Intent(ctx, MALReviewActivity::class.java)
                                .putExtra("malId", malId)
                                .putExtra("isAnime", isAnime)
                        )
                    }
                    parent.addInfoSection(InfoSectionKind.REVIEWS, bind.root)
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
