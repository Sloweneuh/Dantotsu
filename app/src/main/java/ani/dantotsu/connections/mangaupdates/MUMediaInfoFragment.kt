package ani.dantotsu.connections.mangaupdates

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import ani.dantotsu.R
import ani.dantotsu.media.setInfoLine
import ani.dantotsu.buildMarkwon
import ani.dantotsu.connections.malsync.MalSyncMu
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.media.InfoExtraSections
import ani.dantotsu.media.InfoSectionKind
import ani.dantotsu.media.addInfoSection
import ani.dantotsu.media.offerInfoMedia
import ani.dantotsu.media.wantsInfoSection
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemChipSynonymBinding
import ani.dantotsu.databinding.ItemQuelsBinding
import ani.dantotsu.databinding.ItemTitleChipgroupBinding
import ani.dantotsu.databinding.ItemChapterListBinding
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.databinding.ItemTitleTextBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.isOnline
import ani.dantotsu.loadImage
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaNewsActivity
import ani.dantotsu.media.QuicklinksBottomSheetFragment
import ani.dantotsu.util.LinkTouchListener
import ani.dantotsu.media.SearchActivity
import ani.dantotsu.media.balanceQuelRow
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInAppOrBrowser
import ani.dantotsu.px
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Info fragment for [MUMediaDetailsActivity].
 * Observes [MediaDetailsViewModel.mangaUpdatesSeries] (populated by the activity) and
 * renders MangaUpdates series metadata using the same layout as the existing info tabs.
 */
class MUMediaInfoFragment : Fragment() {

    private var _binding: FragmentMediaInfoBinding? = null
    private val binding get() = _binding!!
    private val model: MediaDetailsViewModel by activityViewModels()

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
        binding.mediaInfoContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += 128f.px + navBarHeight
        }

        model.scrolledToTop.observe(viewLifecycleOwner) {
            if (it) binding.mediaInfoScroll.scrollTo(0, 0)
        }

        // Show spinner until series data arrives from the activity
        binding.mediaInfoProgressBar.visibility = View.VISIBLE

        model.mangaUpdatesSeries.observe(viewLifecycleOwner) { series ->
            if (series != null) displaySeriesDetails(series)
        }

        // Offline the activity never fetches series details, so render the basics from the
        // downloaded media instead of spinning forever.
        val offline = PrefManager.getVal<Boolean>(PrefName.OfflineMode) ||
                !isOnline(requireContext())
        if (offline && model.mangaUpdatesSeries.value == null) {
            displayOfflineInfo()
        }
    }

    /**
     * Info shown offline, built from the downloaded [Media] (which [MUMediaDetailsActivity] enriched
     * with the MangaUpdates series metadata at download time). No network is used.
     */
    private fun displayOfflineInfo() {
        if (_binding == null) return
        val media = model.getMedia().value
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.VISIBLE

        val tripleTab = "\t\t\t"
        val title = media?.userPreferredName?.takeIf { it.isNotBlank() }
            ?: media?.name ?: getString(R.string.unknown_title)
        binding.mediaInfoName.text = tripleTab + title
        binding.mediaInfoName.setOnLongClickListener { copyToClipboard(title); true }
        binding.mediaInfoNameRomajiContainer.visibility = View.GONE

        binding.mediaInfoMeanScore.text =
            media?.meanScore?.let { String.format(Locale.US, "%.1f", it / 10.0) }
                ?: getString(R.string.unknown_value)
        binding.mediaInfoStatus.text =
            media?.status?.takeIf { it.isNotBlank() } ?: getString(R.string.unknown)
        binding.mediaInfoTotalTitle.setText(R.string.total_chaps)
        binding.mediaInfoTotal.text = media?.manga?.totalChapters?.toString() ?: "~"
        binding.mediaInfoFormat.text = media?.format ?: getString(R.string.manga)
        binding.mediaInfoSourceContainer.visibility = View.GONE

        val author = media?.manga?.author?.name
        if (!author.isNullOrBlank()) {
            binding.mediaInfoAuthorContainer.visibility = View.VISIBLE
            binding.mediaInfoAuthor.text = author
        } else {
            binding.mediaInfoAuthorContainer.visibility = View.GONE
        }

        binding.mediaInfoStart.text =
            media?.startDate?.year?.toString() ?: getString(R.string.unknown_value)
        binding.mediaInfoEnd.visibility = View.GONE
        (binding.mediaInfoEnd.parent as? ViewGroup)?.visibility = View.GONE
        binding.mediaInfoPopularity.visibility = View.GONE
        (binding.mediaInfoPopularity.parent as? ViewGroup)?.visibility = View.GONE

        (binding.mediaInfoFavorites.parent as? ViewGroup)?.visibility = View.GONE
        setInfoLine(
            binding.mediaInfoFollowersContainer, binding.mediaInfoFollowers,
            media?.favourites?.takeIf { it > 0 }?.toString() ?: getString(R.string.question_marks),
        )

        val desc = media?.description?.takeIf { it.isNotBlank() }
            ?: getString(R.string.no_description_available)
        binding.mediaInfoDescription.text = desc
        binding.mediaInfoDescription.setOnClickListener {
            ObjectAnimator.ofInt(
                binding.mediaInfoDescription, "maxLines",
                if (binding.mediaInfoDescription.maxLines == 5) 100 else 5
            ).setDuration(if (binding.mediaInfoDescription.maxLines == 5) 950L else 400L).start()
        }

        val parent = binding.mediaInfoContainer

        // Genres
        val genres = media?.genres.orEmpty()
        if (genres.isNotEmpty()) {
            val bind = ItemTitleChipgroupBinding.inflate(LayoutInflater.from(context), parent, false)
            bind.itemTitle.setText(R.string.genres)
            genres.forEach { genre ->
                val chip = ItemChipBinding
                    .inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
                chip.text = genre
                chip.setOnLongClickListener { copyToClipboard(genre); true }
                bind.itemChipGroup.addView(chip)
            }
            parent.addInfoSection(InfoSectionKind.GENRES, bind.root)
        }

        // Categories (stored in Media.tags)
        val categories = media?.tags.orEmpty()
        if (categories.isNotEmpty()) {
            val bind = ItemTitleChipgroupBinding
                .inflate(LayoutInflater.from(context), parent, false)
            bind.itemTitle.text = getString(R.string.categories)
            categories.forEach { category ->
                val chip = ItemChipBinding
                    .inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
                chip.text = category
                chip.setOnLongClickListener { copyToClipboard(category); true }
                bind.itemChipGroup.addView(chip)
            }
            parent.addInfoSection(InfoSectionKind.TAGS, bind.root)
        }

        // Associated / alternative titles
        val synonyms = media?.synonyms.orEmpty()
        if (synonyms.isNotEmpty()) {
            val bind = ItemTitleChipgroupBinding.inflate(LayoutInflater.from(context), parent, false)
            bind.itemTitle.setText(R.string.synonyms)
            synonyms.forEach { syn ->
                val chip = ItemChipSynonymBinding
                    .inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
                chip.text = syn
                chip.setOnLongClickListener { copyToClipboard(syn); true }
                bind.itemChipGroup.addView(chip)
            }
            parent.addInfoSection(InfoSectionKind.SYNONYMS, bind.root)
        }
    }

    private fun displaySeriesDetails(series: MUSeriesRecord) {
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.VISIBLE

        val tripleTab = "\t\t\t"
        binding.mediaInfoName.text = tripleTab + (series.title ?: getString(R.string.unknown_title))
        binding.mediaInfoName.setOnLongClickListener {
            copyToClipboard(series.title ?: "")
            true
        }
        binding.mediaInfoNameRomajiContainer.visibility = View.GONE

        val parsedStatus = MuStatusText.parse(series.status, series.completed)

        // Rating
        series.bayesian_rating?.let { ratingStr ->
            val rating = ratingStr.toDoubleOrNull()
            binding.mediaInfoMeanScore.text =
                if (rating != null) String.format("%.1f", rating) else ratingStr
        } ?: run { binding.mediaInfoMeanScore.text = getString(R.string.unknown_value) }

        binding.mediaInfoStatus.text =
            parsedStatus.status?.toString() ?: getString(R.string.unknown)
        // Two in five series are counted in volumes, not chapters.
        val inVolumes = parsedStatus.unit == MuStatusText.Unit.VOLUMES
        (binding.mediaInfoTotal.parent as? ViewGroup)?.visibility = if (inVolumes) View.GONE else View.VISIBLE
        binding.mediaInfoTotalTitle.setText(R.string.total_chaps)
        binding.mediaInfoTotal.text = parsedStatus.count?.toString() ?: "~"
        binding.mediaInfoVolumesLabel.setText(R.string.total_vols)
        setInfoLine(
            binding.mediaInfoVolumesContainer, binding.mediaInfoVolumes,
            parsedStatus.count?.takeIf { inVolumes }?.toString(),
        )
        binding.mediaInfoFormat.text = series.type ?: getString(R.string.manga)

        binding.mediaInfoSourceContainer.visibility = View.GONE

        // Primary author
        val firstAuthor =
            series.authors?.firstOrNull { it.type?.equals("Author", ignoreCase = true) == true }
        if (firstAuthor?.name != null) {
            binding.mediaInfoAuthorContainer.visibility = View.VISIBLE
            binding.mediaInfoAuthor.text = firstAuthor.name
        } else {
            binding.mediaInfoAuthorContainer.visibility = View.GONE
        }

        binding.mediaInfoStart.text = series.year ?: getString(R.string.unknown_value)

        // Hide end date and popularity rows
        binding.mediaInfoEnd.visibility = View.GONE
        (binding.mediaInfoEnd.parent as? ViewGroup)?.visibility = View.GONE
        binding.mediaInfoPopularity.visibility = View.GONE
        (binding.mediaInfoPopularity.parent as? ViewGroup)?.visibility = View.GONE

        (binding.mediaInfoFavorites.parent as? ViewGroup)?.visibility = View.GONE
        setInfoLine(
            binding.mediaInfoFollowersContainer, binding.mediaInfoFollowers,
            series.rank?.lists?.let { lists ->
                ((lists.reading ?: 0) + (lists.wish ?: 0) + (lists.complete ?: 0) +
                        (lists.unfinished ?: 0) + (lists.custom ?: 0)).takeIf { it > 0 }?.toString()
            } ?: getString(R.string.question_marks),
        )

        // Description
        val desc = (series.description ?: getString(R.string.no_description_available))
            .replace(Regex("""\n{3,}"""), "\n\n").trim()
        val markwon = buildMarkwon(
            requireContext(),
            userInputContent = false,
            fragment = this
        ) { openLinkInAppOrBrowser(it) }
        markwon.setMarkdown(binding.mediaInfoDescription, desc)
        binding.mediaInfoDescription.movementMethod = LinkMovementMethod.getInstance()
        // Links take the tap over the expand click below; a long press opens one in the browser.
        binding.mediaInfoDescription.setOnTouchListener(LinkTouchListener())
        binding.mediaInfoDescription.setOnClickListener {
            ObjectAnimator.ofInt(
                binding.mediaInfoDescription,
                "maxLines",
                if (binding.mediaInfoDescription.maxLines == 5) 100 else 5
            ).setDuration(if (binding.mediaInfoDescription.maxLines == 5) 950L else 400L).start()
        }

        // ── Dynamic sections ──────────────────────────────────────────────────
        val parent = binding.mediaInfoContainer
        // Remove any previously added dynamic views to avoid duplicates on re-observe
        val toRemove = mutableListOf<View>()
        for (i in 0 until parent.childCount) {
            val t = parent.getChildAt(i).tag
            if (t == "dynamic_mu_section" || t == "latest_release_anchor_mu" ||
                t == "quicklinks_anchor_mu"
            ) toRemove.add(parent.getChildAt(i))
        }
        toRemove.forEach { parent.removeView(it) }

        // Detailed status (expandable, markdown bold support)
        series.status?.let { rawStatus ->
            if (rawStatus.isNotBlank()) {
                val bind = ItemTitleTextBinding.inflate(LayoutInflater.from(context), parent, false)
                bind.itemTitle.setText(R.string.status_title)
                bind.itemText.text = parseSimpleBold(rawStatus.replace("\\", ""))
                bind.itemText.maxLines = 3
                bind.itemText.setOnClickListener {
                    ObjectAnimator.ofInt(
                        bind.itemText, "maxLines",
                        if (bind.itemText.maxLines == 3) 100 else 3
                    ).setDuration(400).start()
                }
                bind.root.tag = "dynamic_mu_section"
                parent.addInfoSection(InfoSectionKind.DETAILED_STATUS, bind.root)
            }
        }

        // Anime adaptation dates
        series.anime?.let { anime ->
            if (!anime.start.isNullOrBlank() || !anime.end.isNullOrBlank()) {
                val bind = ItemTitleTextBinding.inflate(LayoutInflater.from(context), parent, false)
                bind.itemTitle.text = getString(R.string.anime_adaptation)
                bind.itemText.text = buildString {
                    if (!anime.start.isNullOrBlank()) append("Start: ${anime.start}")
                    if (!anime.end.isNullOrBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("End: ${anime.end}")
                    }
                }
                bind.root.tag = "dynamic_mu_section"
                parent.addInfoSection(InfoSectionKind.ANIME_ADAPTATION, bind.root)
            }
        }

        // Alternative / associated titles
        if (!series.associated.isNullOrEmpty()) {
            val bind =
                ItemTitleChipgroupBinding.inflate(LayoutInflater.from(context), parent, false)
            bind.itemTitle.setText(R.string.synonyms)
            series.associated.forEach { assoc ->
                assoc.title?.let { title ->
                    val chip = ItemChipSynonymBinding
                        .inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
                    chip.text = title
                    chip.setOnLongClickListener { copyToClipboard(title); true }
                    bind.itemChipGroup.addView(chip)
                }
            }
            bind.root.tag = "dynamic_mu_section"
            parent.addInfoSection(InfoSectionKind.SYNONYMS, bind.root)
        }

        // Invisible anchors — the latest release card and the MALSync quicklinks are inserted here
        // once their async fetches complete.
        View(context).also {
            it.tag = "quicklinks_anchor_mu"
            parent.addView(it)
        }
        View(context).also {
            it.tag = "latest_release_anchor_mu"
            parent.addView(it)
        }

        // Genres
        if (!series.genres.isNullOrEmpty()) {
            val bind =
                ItemTitleChipgroupBinding.inflate(LayoutInflater.from(context), parent, false)
            bind.itemTitle.setText(R.string.genres)
            val genreNames = series.genres.mapNotNull { it.genre }
            if (genreNames.isNotEmpty()) {
                bind.itemTitleAction.visibility = View.VISIBLE
                bind.itemTitleAction.text = getString(R.string.search_all)
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
            series.genres.forEach { genreObj ->
                genreObj.genre?.let { genre ->
                    val chip = ItemChipBinding
                        .inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
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
                    chip.setOnLongClickListener { copyToClipboard(genre); true }
                    bind.itemChipGroup.addView(chip)
                }
            }
            bind.root.tag = "dynamic_mu_section"
            parent.addInfoSection(InfoSectionKind.GENRES, bind.root)
        }

        // Categories
        if (!series.categories.isNullOrEmpty()) {
            val bind = ItemTitleChipgroupBinding
                .inflate(LayoutInflater.from(context), parent, false)
            bind.itemTitle.text = getString(R.string.categories)
            series.categories.forEach { cat ->
                cat.category?.let { category ->
                    val chip = ItemChipBinding
                        .inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
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
                    chip.setOnLongClickListener { copyToClipboard(category); true }
                    bind.itemChipGroup.addView(chip)
                }
            }
            bind.root.tag = "dynamic_mu_section"
            parent.addInfoSection(InfoSectionKind.TAGS, bind.root)
        }

        // Full author / artist list
        if (!series.authors.isNullOrEmpty()) {
            val bind = ItemTitleTextBinding.inflate(LayoutInflater.from(context), parent, false)
            bind.itemTitle.text = getString(R.string.authors)
            bind.itemText.text = series.authors.joinToString("\n") { author ->
                buildString {
                    append(author.name ?: "")
                    if (!author.type.isNullOrBlank()) append(" (${author.type})")
                }
            }
            bind.root.tag = "dynamic_mu_section"
            parent.addInfoSection(InfoSectionKind.AUTHORS, bind.root)
        }

        // News. MangaBaka carries a feed for MangaUpdates series too, keyed by the MangaUpdates
        // id, which the screen resolves for itself. Same card as the AniList info tab's, centred
        // on its own row since a manga has no watch order to sit beside.
        ItemQuelsBinding.inflate(LayoutInflater.from(context), parent, false).apply {
            // These cards are buttons, not covers - this screen runs a shared element transition
            // on a view named "mediaCover", which must not have these to choose from.
            ViewCompat.setTransitionName(quelStartImage, null)
            ViewCompat.setTransitionName(quelEndImage, null)

            quelStartCard.visibility = View.VISIBLE
            quelStartLabel.setText(R.string.news)
            quelStartAccent.setBackgroundColor(
                requireContext().getThemeColor(androidx.appcompat.R.attr.colorPrimary)
            )
            quelStartImage.loadImage(series.image?.url?.original ?: series.image?.url?.thumb)
            quelStartCard.setSafeOnClickListener {
                startActivity(
                    Intent(requireContext(), MediaNewsActivity::class.java)
                        .putExtra("muSeriesId", series.seriesId)
                        .putExtra("isAnime", false)
                )
            }
            balanceQuelRow()
            root.tag = "dynamic_mu_section"
            parent.addInfoSection(InfoSectionKind.NEWS, root)
        }

        InfoExtraSections.addMangaUpdatesExtras(requireContext(), parent, series)

        // Direct recommendations
        val directRecs = series.recommendations
        if (!directRecs.isNullOrEmpty()) {
            val items = directRecs.mapNotNull { rec ->
                val id = rec.seriesId ?: return@mapNotNull null
                MUMedia(
                    id = id,
                    title = rec.seriesName,
                    url = rec.seriesUrl,
                    coverUrl = rec.seriesImage?.url?.original ?: rec.seriesImage?.url?.thumb,
                    listId = -1,
                    userChapter = null,
                    userVolume = null,
                    latestChapter = null,
                    bayesianRating = null,
                    priority = null,
                )
            }
            if (items.isNotEmpty()) {
                val bind = ItemTitleRecyclerBinding.inflate(LayoutInflater.from(context), parent, false)
                bind.itemTitle.setText(R.string.recommended)
                bind.itemRecycler.adapter = MUMediaAdapter(items)
                bind.itemRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                    requireContext(), androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
                )
                bind.itemMore.visibility = View.GONE
                bind.root.tag = "dynamic_mu_section"
                parent.addInfoSection(InfoSectionKind.RECOMMENDATIONS, bind.root)
                parent.offerInfoMedia(InfoSectionKind.RECOMMENDATIONS, items.map { it.asFallbackMedia() })
            }
        }

        // Category recommendations
        val categoryRecs = series.category_recommendations
        if (!categoryRecs.isNullOrEmpty()) {
            val items = categoryRecs.mapNotNull { rec ->
                val id = rec.seriesId ?: return@mapNotNull null
                MUMedia(
                    id = id,
                    title = rec.seriesName,
                    url = rec.seriesUrl,
                    coverUrl = rec.seriesImage?.url?.original ?: rec.seriesImage?.url?.thumb,
                    listId = -1,
                    userChapter = null,
                    userVolume = null,
                    latestChapter = null,
                    bayesianRating = null,
                    priority = null,
                )
            }
            if (items.isNotEmpty()) {
                val bind = ItemTitleRecyclerBinding.inflate(LayoutInflater.from(context), parent, false)
                bind.itemTitle.setText(R.string.category_recommendations)
                bind.itemRecycler.adapter = MUMediaAdapter(items)
                bind.itemRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                    requireContext(), androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
                )
                bind.itemMore.visibility = View.GONE
                bind.root.tag = "dynamic_mu_section"
                parent.addInfoSection(InfoSectionKind.CATEGORY_RECOMMENDATIONS, bind.root)
                parent.offerInfoMedia(InfoSectionKind.CATEGORY_RECOMMENDATIONS, items.map { it.asFallbackMedia() })
            }
        }

        // MALSync quicklinks — where else this series can be read. Only the ones MangaUpdates can be
        // linked to a MAL entry have any (see [MalSyncMu]); for the rest the anchor just stays an
        // empty view, as it does while the lookup is in flight.
        if (MalSyncMu.enabledForManga() && parent.wantsInfoSection(InfoSectionKind.QUICKLINKS)) viewLifecycleOwner.lifecycleScope.launch {
            val titles = listOfNotNull(series.title) +
                    series.associated.orEmpty().mapNotNull { it.title }
            val sites = withContext(Dispatchers.IO) {
                MalSyncMu.quicklinks(series.seriesId, titles, model.comickSlug.value)
            }?.Sites?.takeIf { it.isNotEmpty() } ?: return@launch
            if (_binding == null) return@launch

            val bindQuick = ItemTitleChipgroupBinding.inflate(LayoutInflater.from(context), parent, false)
            bindQuick.itemTitle.setText(R.string.quicklinks)
            sites.forEach { (siteName, entries) ->
                // Comick has its own tab on this screen, same as on the AniList one.
                if (siteName.contains("comick", ignoreCase = true)) return@forEach
                val chip = ItemChipBinding
                    .inflate(LayoutInflater.from(context), bindQuick.itemChipGroup, false).root
                chip.text = if (entries.size > 1) "$siteName (${entries.size})" else siteName
                chip.setOnClickListener {
                    QuicklinksBottomSheetFragment
                        .newInstance(siteName, ArrayList(entries.values))
                        .show(childFragmentManager, "quicklinks_$siteName")
                }
                bindQuick.itemChipGroup.addView(chip)
            }
            if (bindQuick.itemChipGroup.childCount == 0) return@launch

            val anchor = parent.findViewWithTag<View>("quicklinks_anchor_mu") ?: return@launch
            val insertIndex = parent.indexOfChild(anchor)
            bindQuick.root.tag = "dynamic_mu_section"
            parent.removeView(anchor)
            parent.addInfoSection(InfoSectionKind.QUICKLINKS, bindQuick.root, insertIndex)
        }

        // Latest release card — async fetch from /v1/series/{id}/groups
        if (parent.wantsInfoSection(InfoSectionKind.LATEST_CHAPTER)) viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val latest = MangaUpdates.getSeriesGroups(series.seriesId)?.releaseList?.firstOrNull()
                ?: return@launch
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext

                val anchor = parent.findViewWithTag<View>("latest_release_anchor_mu")
                    ?: return@withContext
                val insertIndex = parent.indexOfChild(anchor)

                val titleBind = ItemTitleTextBinding.inflate(
                    LayoutInflater.from(context), parent, false
                )
                titleBind.itemTitle.setText(R.string.latest_chapter)
                titleBind.itemText.isVisible = false
                titleBind.root.tag = "dynamic_mu_section"

                val b = ItemChapterListBinding.inflate(
                    LayoutInflater.from(context), parent, false
                )
                b.itemDownload.isVisible = false
                b.itemChapterBrowser.isVisible = false
                b.itemEpisodeViewed.isVisible = false
                (b.root.layoutParams as? android.widget.LinearLayout.LayoutParams)?.apply {
                    marginStart = 32f.px
                    marginEnd = 16f.px
                }

                val chapterText = buildString {
                    latest.volume?.let { append("Vol.$it ") }
                    append("Chapter ${latest.chapter ?: "?"}")
                }
                b.itemChapterNumber.text = chapterText

                val dateText = latest.releaseDate?.let { formatReleaseDate(it) } ?: ""
                val groupText = latest.groups?.mapNotNull { it.name }?.joinToString(", ") ?: ""
                val hasDate = dateText.isNotBlank()
                val hasGroup = groupText.isNotBlank()
                b.itemChapterDateLayout.isVisible = hasDate || hasGroup
                b.itemChapterDate.isVisible = hasDate
                b.itemChapterDate.text = dateText
                b.itemChapterDateDivider.isVisible = hasDate && hasGroup
                b.itemChapterScan.isVisible = hasGroup
                b.itemChapterScan.text = groupText
                b.root.tag = "dynamic_mu_section"

                parent.removeView(anchor)
                parent.addInfoSection(InfoSectionKind.LATEST_CHAPTER, titleBind.root, insertIndex)
                parent.addInfoSection(InfoSectionKind.LATEST_CHAPTER, b.root, insertIndex + 1)
            }
        }
    }

    /** The same stand-in the other tabs use for a series known only to MangaUpdates. */
    private fun MUMedia.asFallbackMedia() = ani.dantotsu.media.Media(
        id = (id and 0x7FFFFFFF).toInt(),
        name = title,
        nameRomaji = title ?: "",
        userPreferredName = title ?: "",
        cover = coverUrl,
        banner = coverUrl,
        isAdult = false,
        manga = ani.dantotsu.media.manga.Manga(),
        format = "MANGA",
        muSeriesId = id,
    )

    private fun formatReleaseDate(dateStr: String): String {
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr) ?: return dateStr
            val diff = Date().time - date.time
            val days = diff / 86_400_000L
            when {
                days == 0L -> "Today"
                days == 1L -> "Yesterday"
                days < 7L -> "$days days ago"
                days < 30L -> "${days / 7} week${if (days / 7 > 1) "s" else ""} ago"
                days < 365L -> "${days / 30} month${if (days / 30 > 1) "s" else ""} ago"
                else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(date)
            }
        } catch (e: Exception) {
            dateStr
        }
    }

    /** Converts `**text**` markers to bold spans without a full Markwon pipeline. */
    private fun parseSimpleBold(text: String): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        var pos = 0
        val pattern = Regex("""\*\*([^*]+)\*\*""")
        pattern.findAll(text).forEach { match ->
            if (match.range.first > pos) out.append(text.substring(pos, match.range.first))
            val start = out.length
            out.append(match.groupValues[1])
            out.setSpan(
                StyleSpan(Typeface.BOLD), start, out.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            pos = match.range.last + 1
        }
        if (pos < text.length) out.append(text.substring(pos))
        return out
    }
}
