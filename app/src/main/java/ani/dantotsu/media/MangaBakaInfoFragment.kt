package ani.dantotsu.media

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.buildMarkwon
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType.Companion.toAnilistString
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemChipSynonymBinding
import ani.dantotsu.databinding.ItemTagsSectionBinding
import ani.dantotsu.databinding.ItemTitleChipgroupBinding
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.databinding.ItemTitleTextBinding
import ani.dantotsu.isOnline
import ani.dantotsu.media.manga.Manga
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.px
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * MangaBaka info tab. Shown for both AniList media (in [MediaInfoFragment]) and MangaUpdates media
 * (in [ani.dantotsu.connections.mangaupdates.MUMediaInfoContainerFragment]). It resolves the current
 * entry to a MangaBaka series id, then renders the series info, covers, tags (with weight/spoiler
 * treatment) and "similar" recommendations, mirroring the other external info tabs.
 */
class MangaBakaInfoFragment : Fragment() {
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
        val offline = PrefManager.getVal<Boolean>(PrefName.OfflineMode) || !isOnline(requireContext())

        binding.mediaInfoContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += 128f.px + navBarHeight
        }

        model.scrolledToTop.observe(viewLifecycleOwner) {
            if (it) binding.mediaInfoScroll.scrollTo(0, 0)
        }

        if (offline) {
            loaded = true
            showError(getString(R.string.mangabaka_requires_internet))
            return
        }

        model.getMedia().observe(viewLifecycleOwner) { media ->
            val m = media ?: return@observe
            if (!loaded) loadMangaBakaData(m, model)
        }
    }

    private fun loadMangaBakaData(media: Media, model: MediaDetailsViewModel) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                binding.mediaInfoProgressBar.visibility = View.VISIBLE
                binding.mediaInfoContainer.visibility = View.GONE

                // Reuse the series the ViewModel already fetched via the source route (which embeds
                // the full object). Only fetch here when it wasn't preloaded and isn't a confirmed
                // no-match — e.g. the MangaUpdates activity, or preload still in flight.
                var series = model.mangaBakaSeries.value
                if (series == null && model.mangaBakaLoaded.value != true) {
                    series = withContext(Dispatchers.IO) {
                        MangaBakaApi.getSeriesForMedia(media.muSeriesId, media.id, media.idMAL)
                    }
                    model.mangaBakaSeries.postValue(series)
                    model.mangaBakaId.postValue(series?.id)
                    model.mangaBakaLoaded.postValue(true)
                }

                if (_binding == null) return@launch

                if (series == null) {
                    loaded = true
                    showNoData(media)
                    return@launch
                }

                loaded = true
                binding.mediaInfoProgressBar.visibility = View.GONE
                binding.mediaInfoContainer.visibility = View.VISIBLE
                displaySeriesInfo(series, series.id, media, model)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                loaded = true
                if (_binding == null) return@launch
                ani.dantotsu.util.Logger.log("MangaBaka error: ${e.message}")
                ani.dantotsu.util.Logger.log(e)
                showNoData(media)
            }
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
            it.addView(errorView)
        }
    }

    private fun showNoData(media: Media) {
        if (_binding == null) return
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.GONE

        val frameLayout = binding.mediaInfoContainer.parent as? ViewGroup
        frameLayout?.let { container ->
            val pageView = layoutInflater.inflate(R.layout.fragment_nodata_page, container, false)

            pageView.findViewById<android.widget.ImageView>(R.id.logo)
                ?.setImageResource(R.drawable.ic_round_mangabaka_24)
            pageView.findViewById<android.widget.TextView>(R.id.title)
                ?.setText(R.string.mangabaka_no_data_title)
            pageView.findViewById<android.widget.TextView>(R.id.subtitle)?.text =
                getString(R.string.mangabaka_no_data_desc)

            pageView.findViewById<com.google.android.material.button.MaterialButton>(R.id.quickSearchButton)
                ?.apply {
                    text = getString(R.string.open_on_mangabaka)
                    icon = ContextCompat.getDrawable(context, R.drawable.ic_round_mangabaka_24)
                    setOnClickListener {
                        val q = Uri.encode(media.userPreferredName)
                        openLinkInBrowser("https://mangabaka.org/search?q=$q")
                    }
                }

            container.addView(pageView)
        }
    }

    // ---------------------------------------------------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun displaySeriesInfo(
        series: MangaBakaApi.Series,
        seriesId: Long,
        media: Media,
        model: MediaDetailsViewModel
    ) {
        val parent = binding.mediaInfoContainer

        // Remove any previously-added dynamic MangaBaka views to avoid duplicates on re-entry.
        val toRemove = mutableListOf<View>()
        for (i in 0 until parent.childCount) {
            val tag = parent.getChildAt(i).tag
            if (tag is String && tag.endsWith("_mangabaka")) toRemove.add(parent.getChildAt(i))
        }
        toRemove.forEach { parent.removeView(it) }

        val nativeLangs = nativeLanguages(series)

        // Title
        binding.mediaInfoName.text = tripleTab + (series.title ?: getString(R.string.unknown))
        binding.mediaInfoName.setOnLongClickListener { copyToClipboard(series.title ?: ""); true }

        // Romaji / romanized title
        val romaji = series.romanizedTitle?.takeIf { it.isNotBlank() }
        if (romaji != null) {
            binding.mediaInfoNameRomajiContainer.visibility = View.VISIBLE
            binding.mediaInfoNameRomaji.text = tripleTab + romaji
            binding.mediaInfoNameRomaji.setOnLongClickListener { copyToClipboard(romaji); true }
        } else {
            binding.mediaInfoNameRomajiContainer.visibility = View.GONE
        }

        // Mean score (rating is 0-100; layout suffix is " / 10")
        binding.mediaInfoMeanScore.text = series.rating?.let { String.format(Locale.US, "%.1f", it / 10.0) }
            ?: getString(R.string.unknown_value)

        // Status
        binding.mediaInfoStatus.text = statusText(series.status)

        // `total_chapters` / `final_volume` double as the *latest* known chapter/volume while a
        // series is ongoing, so they read as totals only once finished — otherwise as "Latest".
        val status = series.status?.lowercase()
        val isFinished = status in setOf("completed", "cancelled")
        val isOngoing = status in setOf("releasing", "hiatus")

        val totalChapters = series.totalChapters?.takeIf { it.isNotBlank() }
        val totalRow = binding.mediaInfoTotal.parent as? ViewGroup
        if (totalChapters != null && (isFinished || isOngoing)) {
            totalRow?.visibility = View.VISIBLE
            binding.mediaInfoTotalTitle.setText(if (isFinished) R.string.total_chaps else R.string.latest_chapter)
            binding.mediaInfoTotal.text = totalChapters
        } else {
            totalRow?.visibility = View.GONE
        }

        // Volume count (reuse the duration row): "Final Volume" when finished, else "Latest Volume".
        val finalVolume = series.finalVolume?.takeIf { it.isNotBlank() }
        if (finalVolume != null && (isFinished || isOngoing)) {
            binding.mediaInfoDurationContainer.visibility = View.VISIBLE
            (binding.mediaInfoDurationContainer.getChildAt(0) as? android.widget.TextView)
                ?.setText(if (isFinished) R.string.final_volume_label else R.string.latest_volume_label)
            binding.mediaInfoDuration.text = finalVolume
        } else {
            binding.mediaInfoDurationContainer.visibility = View.GONE
        }

        // Format (type)
        binding.mediaInfoFormatLabel.setText(R.string.format)
        binding.mediaInfoFormat.text = formatText(series.type)

        // Source row → repurpose as "Origin" (original language)
        val originName = nativeLangs.firstOrNull()?.let { languageName(it) }
        if (originName != null) {
            binding.mediaInfoSourceContainer.visibility = View.VISIBLE
            binding.mediaInfoSourceLabel.setText(R.string.origin)
            binding.mediaInfoSource.text = originName
        } else {
            binding.mediaInfoSourceContainer.visibility = View.GONE
        }

        // Content Rating (safe / suggestive / erotica / pornographic)
        val contentRating = series.contentRating?.takeIf { it.isNotBlank() }
        if (contentRating != null) {
            binding.mediaInfoContentRatingContainer.visibility = View.VISIBLE
            binding.mediaInfoContentRating.text =
                contentRating.replaceFirstChar { it.uppercase() }
        } else {
            binding.mediaInfoContentRatingContainer.visibility = View.GONE
        }

        // Author
        val author = series.authors?.firstOrNull { it.isNotBlank() }
        if (author != null) {
            binding.mediaInfoAuthorContainer.visibility = View.VISIBLE
            binding.mediaInfoAuthor.text = author
        } else {
            binding.mediaInfoAuthorContainer.visibility = View.GONE
        }

        // Start / end dates — formatted like the AniList tab (e.g. "24 January 2014")
        binding.mediaInfoStart.text = toFuzzyDate(series.published?.startDate)?.toString()
            ?: series.year?.toString() ?: getString(R.string.unknown_value)
        val endFuzzy = toFuzzyDate(series.published?.endDate)
        if (endFuzzy != null) {
            (binding.mediaInfoEnd.parent as? ViewGroup)?.visibility = View.VISIBLE
            binding.mediaInfoEnd.text = endFuzzy.toString()
        } else {
            (binding.mediaInfoEnd.parent as? ViewGroup)?.visibility = View.GONE
        }

        // Popularity row → repurpose as "Rank"
        val rank = series.popularity?.global?.current
        val popRow = binding.mediaInfoPopularity.parent as? ViewGroup
        if (rank != null && rank > 0) {
            popRow?.visibility = View.VISIBLE
            (popRow?.getChildAt(0) as? android.widget.TextView)?.setText(R.string.rank)
            binding.mediaInfoPopularity.text = "#$rank"
        } else {
            popRow?.visibility = View.GONE
        }

        // No favourites equivalent — hide that row.
        (binding.mediaInfoFavorites.parent as? ViewGroup)?.visibility = View.GONE

        // Description (light markdown: *italics*, newlines)
        val desc = series.description?.takeIf { it.isNotBlank() }
            ?: getString(R.string.no_description_available)
        val markwon = buildMarkwon(
            requireContext(),
            userInputContent = false,
            fragment = this,
            linkResolver = { link -> openLinkInBrowser(link) }
        )
        markwon.setMarkdown(binding.mediaInfoDescription, desc.replace(Regex("\\n{3,}"), "\n\n").trim())
        binding.mediaInfoDescription.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        binding.mediaInfoDescription.setOnClickListener {
            val target = if (binding.mediaInfoDescription.maxLines == 5) 100 else 5
            android.animation.ObjectAnimator.ofInt(binding.mediaInfoDescription, "maxLines", target)
                .setDuration(if (target == 100) 950 else 400).start()
        }

        addSynonyms(parent, series, nativeLangs)
        addCovers(parent, seriesId, nativeLangs)
        addAnimeAdaptation(parent, series)
        addGenres(parent, series)
        addTags(parent, series)
        addRecommendations(parent, seriesId, media, model)
    }

    /** English + original-language synonyms, excluding the primary/romanized titles already shown. */
    private fun addSynonyms(parent: ViewGroup, series: MangaBakaApi.Series, nativeLangs: Set<String>) {
        val allowed = nativeLangs + "en"
        val primary = series.title?.trim()
        val romaji = series.romanizedTitle?.trim()
        val shown = LinkedHashSet<String>()
        series.titles.orEmpty().forEach { t ->
            val title = t.title?.trim().orEmpty()
            if (title.isBlank()) return@forEach
            val lang = t.language?.substringBefore('-')?.lowercase() ?: return@forEach
            if (lang !in allowed) return@forEach
            if (title.equals(primary, true) || title.equals(romaji, true)) return@forEach
            shown.add(title)
        }
        if (shown.isEmpty()) return

        val bind = ItemTitleChipgroupBinding.inflate(LayoutInflater.from(context), parent, false)
        bind.itemTitle.setText(R.string.synonyms)
        shown.forEach { title ->
            val chip = ItemChipSynonymBinding.inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
            chip.text = title
            chip.setOnLongClickListener {
                copyToClipboard(title)
                Toast.makeText(requireContext(), getString(R.string.copied_title_toast, title), Toast.LENGTH_SHORT).show()
                true
            }
            bind.itemChipGroup.addView(chip)
        }
        bind.root.tag = "synonyms_mangabaka"
        parent.addView(bind.root)
    }

    /** English + original-language covers, loaded asynchronously into a reserved placeholder. */
    private fun addCovers(parent: ViewGroup, seriesId: Long, nativeLangs: Set<String>) {
        val placeholder = android.widget.FrameLayout(requireContext()).apply {
            tag = "covers_mangabaka"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        parent.addView(placeholder)

        val languages = (listOf("en") + nativeLangs).distinct()
        viewLifecycleOwner.lifecycleScope.launch {
            val images = withContext(Dispatchers.IO) {
                MangaBakaApi.getSeriesImages(seriesId, languages)
            }
            if (_binding == null || images.isEmpty()) return@launch

            val covers = images
                .mapNotNull { img ->
                    val thumb = img.image?.thumbUrl() ?: return@mapNotNull null
                    MangaBakaCover(thumb, img.image?.fullUrl(), img.index)
                }
                .distinctBy { it.thumbUrl }
            if (covers.isEmpty()) return@launch

            ItemTitleRecyclerBinding.inflate(LayoutInflater.from(context), placeholder, false).apply {
                itemTitle.setText(R.string.covers)
                val coverAdapter = MangaBakaCoverAdapter(covers)
                itemRecycler.adapter = coverAdapter
                itemRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                    requireContext(), androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
                )
                itemMore.visibility = View.VISIBLE
                itemMore.setSafeOnClickListener { coverAdapter.showGallery(itemMore, getString(R.string.covers)) }
                placeholder.addView(root)
            }
        }
    }

    private fun addAnimeAdaptation(parent: ViewGroup, series: MangaBakaApi.Series) {
        val anime = series.anime ?: return
        if (series.hasAnime != true && anime.start.isNullOrBlank() && anime.end.isNullOrBlank()) return
        if (anime.start.isNullOrBlank() && anime.end.isNullOrBlank()) return

        val bind = ItemTitleTextBinding.inflate(LayoutInflater.from(context), parent, false)
        bind.itemTitle.text = getString(R.string.anime_adaptation)
        bind.itemText.text = buildString {
            if (!anime.start.isNullOrBlank()) append(getString(R.string.anime_start_format, anime.start))
            if (!anime.end.isNullOrBlank()) append(getString(R.string.anime_end_format, anime.end))
        }.trim()
        bind.itemText.setOnClickListener {
            val target = if (bind.itemText.maxLines == 4) 100 else 4
            android.animation.ObjectAnimator.ofInt(bind.itemText, "maxLines", target).setDuration(400).start()
        }
        bind.root.tag = "anime_info_mangabaka"
        parent.addView(bind.root)
    }

    private fun addGenres(parent: ViewGroup, series: MangaBakaApi.Series) {
        val genres = series.genres?.filter { it.isNotBlank() } ?: return
        if (genres.isEmpty()) return

        // Add the section synchronously to hold its position, then resolve slug -> display names
        // (e.g. "slice_of_life" -> "Slice of Life") from the /v1/genres route and fill the chips.
        val bind = ItemTitleChipgroupBinding.inflate(LayoutInflater.from(context), parent, false)
        bind.itemTitle.setText(R.string.genres)
        bind.root.tag = "genres_mangabaka"
        parent.addView(bind.root)

        viewLifecycleOwner.lifecycleScope.launch {
            val labels = withContext(Dispatchers.IO) { MangaBakaApi.getGenreLabels() }
            if (_binding == null) return@launch
            genres.forEach { slug ->
                val display = labels[slug] ?: titleCase(slug.replace('_', ' '))
                val chip = ItemChipBinding.inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
                chip.text = display
                chip.setOnClickListener { startMangaBakaSearchInApp(genreSlug = slug, genreName = display) }
                chip.setOnLongClickListener {
                    copyToClipboard(display)
                    Toast.makeText(requireContext(), getString(R.string.copied_title_toast, display), Toast.LENGTH_SHORT).show()
                    true
                }
                bind.itemChipGroup.addView(chip)
            }
        }
    }

    /**
     * Tags section. Each tag is a chip with its weight chevron trailing the name (tinted to the
     * text colour); spoiler names are blurred until tapped. A dropdown on the "Tags" label filters
     * by minimum weight. Genre-flagged tags are omitted here (they appear in the genres section).
     */
    private fun addTags(parent: ViewGroup, series: MangaBakaApi.Series) {
        val tags = series.tags
            ?.filter { it.isGenre != true && !it.name.isNullOrBlank() }
            ?.sortedWith(compareByDescending<MangaBakaApi.TagEntry> { weightRank(it.weight) }.thenBy { it.name })
            ?: return
        if (tags.isEmpty()) return

        val textColor = themeColor(com.google.android.material.R.attr.colorOnSurface)
        val bind = ItemTagsSectionBinding.inflate(LayoutInflater.from(context), parent, false)
        bind.root.tag = "tags_mangabaka"

        val filters = listOf(
            TagFilter(getString(R.string.tag_filter_all), null, 0),
            TagFilter(getString(R.string.tag_filter_incidental), R.drawable.ic_weight_incidental, 1),
            TagFilter(getString(R.string.tag_filter_recurrent), R.drawable.ic_weight_recurrent, 2),
            TagFilter(getString(R.string.tag_filter_defining), R.drawable.ic_weight_defining, 3),
            TagFilter(getString(R.string.tag_filter_core), R.drawable.ic_weight_core, 4),
        )

        fun selectFilter(f: TagFilter) {
            bind.tagsFilterText.text = f.label
            if (f.chevron != null) {
                bind.tagsFilterChevron.visibility = View.VISIBLE
                bind.tagsFilterChevron.setImageResource(f.chevron)
                bind.tagsFilterChevron.imageTintList = ColorStateList.valueOf(textColor)
            } else {
                bind.tagsFilterChevron.visibility = View.GONE
            }
            bind.tagsChipGroup.removeAllViews()
            tags.filter { weightRank(it.weight) >= f.threshold }
                .forEach { bind.tagsChipGroup.addView(makeTagChip(it, bind.tagsChipGroup)) }
        }

        bind.tagsFilterButton.setOnClickListener {
            val popup = ListPopupWindow(requireContext())
            popup.anchorView = bind.tagsFilterButton
            popup.setAdapter(TagFilterAdapter(requireContext(), filters))
            popup.isModal = true
            popup.setContentWidth(200f.px)
            popup.setBackgroundDrawable(
                ContextCompat.getDrawable(requireContext(), R.drawable.dropdown_background)
            )
            popup.setOnItemClickListener { _, _, pos, _ ->
                selectFilter(filters[pos])
                popup.dismiss()
            }
            popup.show()
        }

        parent.addView(bind.root)
        // Default to hiding "unweighted" tags — start at the Incidental+ filter.
        selectFilter(filters[1])
    }

    /**
     * Builds a Material tag chip with its weight chevron trailing the name (in the close-icon slot,
     * tinted to the text colour). Spoiler names are masked with blocks and revealed on first tap;
     * a second tap searches. Long-press copies the (revealed) name.
     */
    private fun makeTagChip(tag: MangaBakaApi.TagEntry, group: ViewGroup): Chip {
        val name = tag.name ?: ""
        val chip = ItemChipBinding.inflate(LayoutInflater.from(context), group, false).root

        // Weight chevron on the right via the close-icon slot, tinted to match the chip text.
        weightDrawable(tag.weight)?.let { res ->
            chip.closeIcon = ContextCompat.getDrawable(requireContext(), res)
            chip.isCloseIconVisible = true
            chip.closeIconTint = chip.textColors
            chip.closeIconSize = 16f.px.toFloat()
            chip.closeIconStartPadding = 2f.px.toFloat()
        }

        val search = { startMangaBakaSearchInApp(tag = name) }

        if (tag.isSpoiler == true) {
            chip.text = "▓".repeat(name.length.coerceIn(3, 12))
            val revealed = booleanArrayOf(false)
            val onTap = {
                if (!revealed[0]) {
                    revealed[0] = true
                    chip.text = name
                } else search()
            }
            chip.setOnClickListener { onTap() }
            chip.setOnCloseIconClickListener { onTap() }
        } else {
            chip.text = name
            chip.setOnClickListener { search() }
            chip.setOnCloseIconClickListener { search() }
        }
        chip.setOnLongClickListener {
            copyToClipboard(name)
            Toast.makeText(requireContext(), getString(R.string.copied_title_toast, name), Toast.LENGTH_SHORT).show()
            true
        }
        return chip
    }

    /** A weight-filter option for the Tags dropdown: label, its chevron drawable, and min weight rank. */
    private data class TagFilter(val label: String, val chevron: Int?, val threshold: Int)

    /** Dropdown adapter that renders each filter as a label with its weight chevron ("All" has none). */
    private class TagFilterAdapter(
        private val ctx: Context,
        private val filters: List<TagFilter>,
    ) : BaseAdapter() {
        override fun getCount() = filters.size
        override fun getItem(position: Int) = filters[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(ctx)
                .inflate(R.layout.item_tag_filter_row, parent, false)
            val f = filters[position]
            row.findViewById<TextView>(R.id.filterLabel).text = f.label
            val iv = row.findViewById<ImageView>(R.id.filterChevron)
            if (f.chevron != null) {
                iv.visibility = View.VISIBLE
                iv.setImageResource(f.chevron)
                iv.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.bg_opp)
                )
            } else {
                iv.visibility = View.INVISIBLE
            }
            return row
        }
    }

    /**
     * Recommendations from the `similar` route: prefer the linked AniList media, fall back to a
     * MangaUpdates entry when no AniList link exists, and skip similars that have neither.
     */
    private fun addRecommendations(parent: ViewGroup, seriesId: Long, media: Media, model: MediaDetailsViewModel) {
        val placeholder = android.widget.FrameLayout(requireContext()).apply {
            tag = "recommendations_mangabaka"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        parent.addView(placeholder)

        val isMuMedia = media.muSeriesId != null
        val currentAnilistId = if (isMuMedia) null else media.id
        val currentMuId = media.muSeriesId

        viewLifecycleOwner.lifecycleScope.launch {
            val similar = withContext(Dispatchers.IO) { MangaBakaApi.getSimilar(seriesId) }
            if (_binding == null || similar.isEmpty()) return@launch

            val anilistRecs = model.getMedia().value?.recommendations?.associateBy { it.id } ?: emptyMap()
            val recAnilistPairs = mutableListOf<Pair<Int, Int>>() // index -> anilistId
            val indexToMedia = mutableMapOf<Int, Media>()

            similar.forEachIndexed { index, item ->
                val s = item.series ?: return@forEachIndexed
                val anilistId = s.source?.anilist?.id
                val muId = s.source?.mangaUpdates?.toMuSeriesId()
                when {
                    anilistId != null && anilistId > 0 && anilistId != currentAnilistId ->
                        recAnilistPairs.add(index to anilistId)
                    muId != null && muId > 0 && muId != currentMuId -> {
                        val cover = s.cover?.thumbUrl()
                        val name = s.title ?: return@forEachIndexed
                        indexToMedia[index] = Media(
                            id = (muId and 0x7FFFFFFF).toInt(),
                            name = name,
                            nameRomaji = name,
                            userPreferredName = name,
                            cover = cover,
                            banner = cover,
                            isAdult = false,
                            manga = Manga(),
                            format = "MANGA",
                            muSeriesId = muId,
                        )
                    }
                }
            }

            if (recAnilistPairs.isNotEmpty()) {
                val missingIds = recAnilistPairs.map { it.second }.filter { anilistRecs[it] == null }.distinct()
                val batchById = if (missingIds.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        try { Anilist.query.getMediaBatch(missingIds) } catch (e: Exception) { emptyList() }
                    }.associateBy { it.id }
                } else emptyMap()
                for ((index, anilistId) in recAnilistPairs) {
                    (anilistRecs[anilistId] ?: batchById[anilistId])?.let { indexToMedia[index] = it }
                }
            }

            val recommended = indexToMedia.keys.sorted().mapNotNull { indexToMedia[it] }
            if (recommended.isEmpty() || _binding == null) return@launch

            withContext(Dispatchers.Main) {
                if (_binding == null || placeholder.childCount > 0) return@withContext
                ItemTitleRecyclerBinding.inflate(LayoutInflater.from(context), placeholder, false).apply {
                    itemTitle.setText(R.string.recommended)
                    itemRecycler.adapter = MediaAdaptor(0, ArrayList(recommended), requireActivity())
                    itemRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                        requireContext(), androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
                    )
                    itemMore.visibility = View.VISIBLE
                    itemMore.setSafeOnClickListener {
                        MediaListViewActivity.passedMedia = ArrayList(recommended)
                        startActivity(
                            Intent(requireContext(), MediaListViewActivity::class.java)
                                .putExtra("title", getString(R.string.recommended))
                        )
                    }
                    placeholder.addView(root)
                }
            }
        }
    }

    // --- helpers ---

    /** Launches the in-app MangaBaka search seeded with a genre or tag chip (mirrors the Comick tab). */
    private fun startMangaBakaSearchInApp(
        genreSlug: String? = null,
        genreName: String? = null,
        tag: String? = null,
    ) {
        if (!isAdded) return
        val intent = Intent(requireContext(), SearchActivity::class.java)
            .putExtra("type", SearchType.MANGABAKA.toAnilistString())
        if (!genreSlug.isNullOrBlank()) {
            intent.putExtra("genre", genreSlug)
            if (!genreName.isNullOrBlank()) intent.putExtra("genreName", genreName)
        }
        if (!tag.isNullOrBlank()) intent.putExtra("tag", tag)
        if (!genreSlug.isNullOrBlank() || !tag.isNullOrBlank()) intent.putExtra("search", true)
        startActivity(intent)
    }

    /** Parses an ISO date (`yyyy`, `yyyy-MM`, or `yyyy-MM-dd`) into a [FuzzyDate] for display. */
    private fun toFuzzyDate(iso: String?): FuzzyDate? {
        if (iso.isNullOrBlank()) return null
        val parts = iso.substringBefore('T').split('-')
        val year = parts.getOrNull(0)?.toIntOrNull() ?: return null
        return FuzzyDate(year, parts.getOrNull(1)?.toIntOrNull(), parts.getOrNull(2)?.toIntOrNull())
    }

    private fun nativeLanguages(series: MangaBakaApi.Series): Set<String> =
        series.titles.orEmpty()
            .filter { t -> t.traits?.any { it.equals("native", true) } == true }
            .mapNotNull { it.language?.substringBefore('-')?.lowercase() }
            .toSet()

    private fun statusText(status: String?): String = when (status?.lowercase()) {
        "releasing" -> getString(R.string.ongoing)
        "completed" -> getString(R.string.completed)
        "hiatus" -> getString(R.string.hiatus)
        "cancelled" -> getString(R.string.cancelled)
        "upcoming" -> getString(R.string.upcoming)
        else -> getString(R.string.unknown)
    }

    private fun formatText(type: String?): String = when (type?.lowercase()) {
        "novel", "light_novel" -> getString(R.string.novel)
        "manga" -> getString(R.string.manga)
        "manhwa" -> getString(R.string.manhwa)
        "manhua" -> getString(R.string.manhua)
        null -> getString(R.string.unknown)
        else -> titleCase(type)
    }

    private fun languageName(code: String): String = when (code.lowercase()) {
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "zh" -> "Chinese"
        "en" -> "English"
        "es" -> "Spanish"
        "fr" -> "French"
        else -> code.uppercase()
    }

    private fun weightRank(weight: String?): Int = when (weight?.lowercase()) {
        "core" -> 4
        "defining" -> 3
        "recurrent" -> 2
        "incidental" -> 1
        else -> 0
    }

    private fun weightDrawable(weight: String?): Int? = when (weight?.lowercase()) {
        "core" -> R.drawable.ic_weight_core
        "defining" -> R.drawable.ic_weight_defining
        "recurrent" -> R.drawable.ic_weight_recurrent
        "incidental" -> R.drawable.ic_weight_incidental
        "unweighted" -> R.drawable.ic_weight_unweighted
        else -> null
    }

    private fun titleCase(text: String): String =
        text.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
