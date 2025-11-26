package ani.dantotsu.media

import android.animation.ObjectAnimator
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
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemTitleChipgroupBinding
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.isOnline
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MALInfoFragment : Fragment() {
    private var _binding: FragmentMediaInfoBinding? = null
    private val binding get() = _binding!!
    private var loaded = false

    private val tripleTab = "\t\t\t"

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

            if (!isLoggedIn) {
                showNotLoggedIn()
                return@launch
            }

            model.getMedia().observe(viewLifecycleOwner) { media ->
                if (media != null && !loaded && media.idMAL != null) {
                    lifecycleScope.launch {
                        try {
                            binding.mediaInfoProgressBar.visibility = View.VISIBLE
                            binding.mediaInfoContainer.visibility = View.GONE

                            val malData = withContext(Dispatchers.IO) {
                                if (media.anime != null) {
                                    MAL.query.getAnimeDetails(media.idMAL!!)
                                } else {
                                    MAL.query.getMangaDetails(media.idMAL!!)
                                }
                            }

                            if (malData == null) {
                                showError("Failed to load MAL data")
                                return@launch
                            }

                            loaded = true
                            binding.mediaInfoProgressBar.visibility = View.GONE
                            binding.mediaInfoContainer.visibility = View.VISIBLE

                            val parent = _binding?.mediaInfoContainer ?: return@launch
                            val screenWidth = resources.displayMetrics.run { widthPixels / density }

                            // Display MAL data
                            if (media.anime != null) {
                                displayAnimeInfo(malData as ani.dantotsu.connections.mal.MALAnimeResponse, parent, screenWidth, offline)
                            } else {
                                displayMangaInfo(malData as ani.dantotsu.connections.mal.MALMangaResponse, parent, screenWidth, offline)
                            }

                        } catch (e: Exception) {
                            showError("Error loading MAL data: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun showNotLoggedIn() {
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.GONE

        val frameLayout = binding.mediaInfoContainer.parent as ViewGroup
        val notLoggedInView = layoutInflater.inflate(
            R.layout.fragment_mal_not_logged_in,
            frameLayout,
            false
        )

        notLoggedInView.findViewById<View>(R.id.connectMalButton)?.setOnClickListener {
            MAL.loginIntent(requireContext())
        }

        frameLayout.addView(notLoggedInView)
    }

    private fun showError(message: String) {
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.VISIBLE
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    @Suppress("SetTextI18n")
    private fun displayAnimeInfo(
        malData: ani.dantotsu.connections.mal.MALAnimeResponse,
        parent: ViewGroup,
        @Suppress("UNUSED_PARAMETER") screenWidth: Float,
        offline: Boolean
    ) {
        // Title
        binding.mediaInfoName.text = tripleTab + malData.title
        binding.mediaInfoName.setOnLongClickListener {
            copyToClipboard(malData.title)
            true
        }

        // Romaji/English title
        if (malData.alternativeTitles?.en != null || malData.alternativeTitles?.ja != null) {
            binding.mediaInfoNameRomajiContainer.visibility = View.VISIBLE
            val altTitle = malData.alternativeTitles.en ?: malData.alternativeTitles.ja ?: ""
            binding.mediaInfoNameRomaji.text = tripleTab + altTitle
            binding.mediaInfoNameRomaji.setOnLongClickListener {
                copyToClipboard(altTitle)
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
        binding.mediaInfoTotal.text = malData.numEpisodes?.toString() ?: "~"

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
                val chip = ItemChipBinding.inflate(
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

        // Recommendations
        if (malData.recommendations.isNotEmpty() && !offline) {
            lifecycleScope.launch {
                val recommendations = mutableListOf<Media>()

                malData.recommendations.take(10).forEach { recommendation ->
                    val media = withContext(Dispatchers.IO) {
                        try {
                            Anilist.query.getMedia(recommendation.node.id, mal = true)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (media != null) {
                        recommendations.add(media)
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
        offline: Boolean
    ) {
        // Title
        binding.mediaInfoName.text = tripleTab + malData.title
        binding.mediaInfoName.setOnLongClickListener {
            copyToClipboard(malData.title)
            true
        }

        // Romaji/English title
        if (malData.alternativeTitles?.en != null || malData.alternativeTitles?.ja != null) {
            binding.mediaInfoNameRomajiContainer.visibility = View.VISIBLE
            val altTitle = malData.alternativeTitles.en ?: malData.alternativeTitles.ja ?: ""
            binding.mediaInfoNameRomaji.text = tripleTab + altTitle
            binding.mediaInfoNameRomaji.setOnLongClickListener {
                copyToClipboard(altTitle)
                true
            }
        }

        // Mean Score
        binding.mediaInfoMeanScore.text = malData.mean?.toString() ?: "??"

        // Status
        binding.mediaInfoStatus.text = formatStatus(malData.status)

        // Format/Media Type
        binding.mediaInfoFormat.text = formatMediaType(malData.mediaType)

        // Source (hide for manga)
        binding.mediaInfoSource.text = "Manga"

        // Dates
        binding.mediaInfoStart.text = formatDate(malData.startDate)
        binding.mediaInfoEnd.text = formatDate(malData.endDate)

        // Popularity
        binding.mediaInfoPopularity.text = malData.numListUsers?.toString() ?: "??"

        // Favorites (using num_scoring_users as proxy)
        binding.mediaInfoFavorites.text = malData.numScoringUsers?.toString() ?: "??"

        // Author
        if (malData.authors.isNotEmpty()) {
            binding.mediaInfoAuthorContainer.visibility = View.VISIBLE
            val author = malData.authors.first().node
            val authorName = "${author.firstName ?: ""} ${author.lastName ?: ""}".trim()
            binding.mediaInfoAuthor.text = authorName
        }

        // Total Chapters
        binding.mediaInfoTotalTitle.setText(R.string.total_chaps)
        binding.mediaInfoTotal.text = malData.numChapters?.toString() ?: "~"

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
                val chip = ItemChipBinding.inflate(
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

        // Recommendations
        if (malData.recommendations.isNotEmpty() && !offline) {
            lifecycleScope.launch {
                val recommendations = mutableListOf<Media>()

                malData.recommendations.take(10).forEach { recommendation ->
                    val media = withContext(Dispatchers.IO) {
                        try {
                            Anilist.query.getMedia(recommendation.node.id, mal = true)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (media != null) {
                        recommendations.add(media)
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
            else -> status?.replaceFirstChar { it.uppercase() } ?: "Unknown"
        }
    }

    private fun formatMediaType(mediaType: String?): String {
        return when (mediaType?.lowercase()) {
            "tv" -> "TV"
            "ova" -> "OVA"
            "ona" -> "ONA"
            "movie" -> "Movie"
            "special" -> "Special"
            "manga" -> "Manga"
            "novel" -> "Novel"
            "one_shot" -> "One Shot"
            "doujinshi" -> "Doujinshi"
            "manhwa" -> "Manhwa"
            "manhua" -> "Manhua"
            else -> mediaType?.uppercase() ?: "Unknown"
        }
    }

    private fun formatSource(source: String?): String {
        return when (source?.lowercase()) {
            "original" -> "Original"
            "manga" -> "Manga"
            "light_novel" -> "Light Novel"
            "visual_novel" -> "Visual Novel"
            "video_game" -> "Video Game"
            "other" -> "Other"
            "novel" -> "Novel"
            "web_manga" -> "Web Manga"
            else -> source?.replaceFirstChar { it.uppercase() } ?: "Unknown"
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

