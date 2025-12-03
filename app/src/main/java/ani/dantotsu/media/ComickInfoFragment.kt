package ani.dantotsu.media

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
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.comick.ComickResponse
import ani.dantotsu.connections.malsync.MalSyncApi
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.databinding.ItemChipBinding
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
            showError("Comick data requires an internet connection")
            return
        }

        model.getMedia().observe(viewLifecycleOwner) { media ->
            val m = media ?: return@observe
            if (!loaded) {
                lifecycleScope.launch {
                    try {
                        binding.mediaInfoProgressBar.visibility = View.VISIBLE
                        binding.mediaInfoContainer.visibility = View.GONE

                        // Get the Comick slug from MalSync quicklinks
                        val quicklinks = withContext(Dispatchers.IO) {
                            val mediaType = if (m.anime != null) "anime" else "manga"
                            MalSyncApi.getQuicklinks(m.id, m.idMAL, mediaType)
                        }

                        var comickSlug = quicklinks?.Sites?.entries?.firstOrNull {
                            it.key.equals("Comick", true) || it.key.contains("comick", true)
                        }?.value?.values?.firstOrNull()?.identifier

                        // If not found in MalSync, try search API
                        if (comickSlug == null) {
                            comickSlug = withContext(Dispatchers.IO) {
                                val title = media.name ?: media.nameRomaji
                                if (!title.isNullOrBlank()) {
                                    ComickApi.searchAndMatchComic(title, media.id, media.idMAL)
                                } else {
                                    null
                                }
                            }
                        }

                        if (comickSlug == null) {
                            showError("No Comick entry found for this media")
                            return@launch
                        }

                        val comickData = withContext(Dispatchers.IO) {
                            ComickApi.getComicDetails(comickSlug)
                        }

                        if (comickData == null) {
                            showError("Failed to load Comick data")
                            return@launch
                        }

                        loaded = true
                        binding.mediaInfoProgressBar.visibility = View.GONE
                        binding.mediaInfoContainer.visibility = View.VISIBLE

                        displayComickInfo(comickData)

                    } catch (e: Exception) {
                        showError("Error loading Comick data: ${e.message}")
                    }
                }
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

    @Suppress("SetTextI18n")
    private fun displayComickInfo(comickData: ComickResponse) {
        val parent = _binding?.mediaInfoContainer ?: return
        val comic = comickData.comic ?: return

        // Display Title
        binding.mediaInfoName.text = tripleTab + (comic.title ?: "Unknown")
        binding.mediaInfoName.setOnLongClickListener {
            copyToClipboard(comic.title ?: "")
            true
        }

        // Display Alternative Titles (English/Japanese if available)
        val englishTitle = comic.md_titles?.firstOrNull { it.lang == "en" }?.title
        val japaneseTitle = comic.md_titles?.firstOrNull { it.lang == "ja" }?.title
        if (englishTitle != null || japaneseTitle != null) {
            binding.mediaInfoNameRomajiContainer.visibility = View.VISIBLE
            val altTitle = englishTitle ?: japaneseTitle
            binding.mediaInfoNameRomaji.text = tripleTab + altTitle
            binding.mediaInfoNameRomaji.setOnLongClickListener {
                copyToClipboard(altTitle ?: "")
                true
            }
        }

        // Mean Score (bayesian_rating)
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

        // Start Date (Year)
        binding.mediaInfoStart.text = comic.year?.toString() ?: "??"
        
        // End Date - Use translation_completed status
        binding.mediaInfoEnd.text = if (comic.translation_completed == true) {
            "Completed"
        } else {
            "Ongoing"
        }

        // Popularity (user_follow_count)
        binding.mediaInfoPopularity.text = comic.user_follow_count?.toString() ?: "??"

        // Favorites (follow_rank)
        binding.mediaInfoFavorites.text = "#${comic.follow_rank ?: "??"}"

        // Total Chapters
        binding.mediaInfoTotalTitle.setText(ani.dantotsu.R.string.total_chaps)
        binding.mediaInfoTotal.text = comic.last_chapter?.let {
            if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
        } ?: "??"

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
                bind.itemText.setOnLongClickListener {
                    copyToClipboard(infoText)
                    true
                }

                bind.root.tag = "anime_info_comick"
                parent.addView(bind.root)
            }
        }

        // Add MangaUpdates quicklink if available
        val muLink = comic.links?.mu
        if (!muLink.isNullOrBlank() && parent.findViewWithTag<View>("quicklinks_comick") == null) {
            val bind = ani.dantotsu.databinding.ItemTitleChipgroupMultilineBinding.inflate(
                LayoutInflater.from(context),
                parent,
                false
            )
            bind.itemTitle.text = "Quicklinks"

            val muChip = ItemChipBinding.inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
            muChip.text = "MangaUpdates"
            val muUrl = "https://www.mangaupdates.com/series/$muLink"
            muChip.setOnClickListener {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(muUrl))
                startActivity(intent)
            }
            muChip.setOnLongClickListener {
                copyToClipboard(muUrl)
                true
            }
            bind.itemChipGroup.addView(muChip)

            bind.root.tag = "quicklinks_comick"
            parent.addView(bind.root)
        }

        // Add Genres section
        val genres = comic.md_comic_md_genres
        if (!genres.isNullOrEmpty() && parent.findViewWithTag<View>("genres_comick") == null) {
            val bind = ani.dantotsu.databinding.ItemTitleChipgroupMultilineBinding.inflate(
                LayoutInflater.from(context),
                parent,
                false
            )
            bind.itemTitle.text = "Genres"

            genres.forEach { genreWrapper ->
                val genre = genreWrapper.md_genres ?: return@forEach
                val genreName = genre.name ?: return@forEach

                val chip = ItemChipBinding.inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
                chip.text = genreName
                chip.setOnLongClickListener {
                    copyToClipboard(genreName)
                    true
                }
                bind.itemChipGroup.addView(chip)
            }

            bind.root.tag = "genres_comick"
            parent.addView(bind.root)
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

                    val chip = ItemChipBinding.inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
                    chip.text = title
                    chip.isClickable = true
                    chip.setOnClickListener {
                        copyToClipboard(title)
                        Toast.makeText(requireContext(), "Copied: $title", Toast.LENGTH_SHORT).show()
                    }

                    bind.itemChipGroup.addView(chip)
                }

                bind.root.tag = "tags_comick"
                parent.addView(bind.root)
            }
        }

        // Add Recommendations
        val recommendations = comic.recommendations
        if (!recommendations.isNullOrEmpty() && parent.findViewWithTag<View>("recommendations_comick") == null) {
            lifecycleScope.launch {
                val recommendedMedia = mutableListOf<ani.dantotsu.media.Media>()

                // Only process recommendations that have an AniList ID
                val recsWithAnilistId = recommendations.filter { rec ->
                    rec.relates?.slug != null && !rec.relates.slug.isBlank()
                }

                // Fetch AniList data for recommendations with AniList IDs
                for (rec in recsWithAnilistId.take(10)) {
                    val slug = rec.relates?.slug
                    if (slug.isNullOrBlank()) continue

                    try {
                        // Get the full details to check for AniList ID
                        val details = withContext(Dispatchers.IO) {
                            ComickApi.getComicDetails(slug)
                        }

                        val anilistId = details?.comic?.links?.al?.toIntOrNull()
                        if (anilistId != null) {
                            // Fetch the media from AniList
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
    }
}

