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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.buildMarkwon
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType.Companion.toAnilistString
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityMangabakaMediaBinding
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemChipSynonymBinding
import ani.dantotsu.databinding.ItemTagsSectionBinding
import ani.dantotsu.databinding.ItemTitleChipgroupBinding
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.databinding.ItemTitleTextBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.openMangaUpdatesSeriesInApp
import ani.dantotsu.openOrCopyAnilistLink
import ani.dantotsu.media.manga.Manga
import ani.dantotsu.px
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Standalone MangaBaka series screen — the search-result destination, mirroring [ComickMediaActivity].
 * It fetches a series by id and renders the same sections as [MangaBakaInfoFragment] (stats, covers,
 * genres, weighted tags, recommendations), but as a self-contained activity rather than an info tab.
 */
class MangaBakaMediaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERIES_ID = "mangabaka_series_id"
    }

    private lateinit var binding: ActivityMangabakaMediaBinding
    private val tripleTab = "\t\t\t"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityMangabakaMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.mangaBakaMediaPages.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }
        binding.mangaBakaMediaClose.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight + 16f.px
        }
        binding.mangaBakaMediaClose.setOnClickListener { finish() }

        val seriesId = intent.getLongExtra(EXTRA_SERIES_ID, -1L)
            .takeIf { it > 0 }
            ?: run {
                val fromPath = intent.data?.pathSegments?.getOrNull(1)?.toLongOrNull()
                fromPath ?: run { finish(); return }
            }

        lifecycleScope.launch {
            val series = withContext(Dispatchers.IO) { MangaBakaApi.getSeries(seriesId) }
            if (series == null) {
                binding.mangaBakaMediaProgress.visibility = View.GONE
                Toast.makeText(this@MangaBakaMediaActivity, getString(R.string.mangabaka_no_data_title), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            setupHeader(series)
            setupSourceButtons(series)
            binding.mangaBakaMediaProgress.visibility = View.GONE
            binding.mangaBakaMediaInfoScroll.visibility = View.VISIBLE
            displaySeriesInfo(series)
        }
    }

    private fun setupHeader(series: MangaBakaApi.Series) {
        val coverUrl = series.cover?.fullUrl() ?: series.cover?.thumbUrl()
        if (coverUrl != null) {
            binding.mangaBakaMediaCover.loadImage(coverUrl)
            blurImage(binding.mangaBakaMediaBanner, coverUrl)
        }
        binding.mangaBakaMediaTitle.text = series.title ?: getString(R.string.unknown)
        binding.mangaBakaMediaScore.text = series.rating?.let { "★ " + String.format(Locale.US, "%.1f", it / 10.0) } ?: ""
    }

    private fun setupSourceButtons(series: MangaBakaApi.Series) {
        val anilistId = series.source?.anilist?.id?.takeIf { it > 0 }
        val muId = series.source?.mangaUpdates?.id?.takeIf { it.isNotBlank() }
        var anyShown = false

        if (anilistId != null) {
            binding.mangaBakaMediaAnilistBtn.visibility = View.VISIBLE
            binding.mangaBakaMediaAnilistBtn.setText(R.string.comick_open_anilist)
            binding.mangaBakaMediaAnilistBtn.setOnClickListener {
                openOrCopyAnilistLink("https://anilist.co/manga/$anilistId")
            }
            anyShown = true
        }
        if (muId != null) {
            binding.mangaBakaMediaMuBtn.visibility = View.VISIBLE
            binding.mangaBakaMediaMuBtn.setText(R.string.comick_open_mangaupdates)
            binding.mangaBakaMediaMuBtn.setOnClickListener {
                val url = "https://www.mangaupdates.com/series/$muId"
                if (!openMangaUpdatesSeriesInApp(url)) openLinkInBrowser(url)
            }
            anyShown = true
        }
        binding.mangaBakaMediaSourceButtons.visibility = if (anyShown) View.VISIBLE else View.GONE
    }

    @SuppressLint("SetTextI18n")
    private fun displaySeriesInfo(series: MangaBakaApi.Series) {
        val info = FragmentMediaInfoBinding.inflate(layoutInflater)
        val parent = info.mediaInfoContainer
        // The container defaults to GONE in the fragment layout (shown only after load) — make it
        // visible here, and hide the in-stats name rows since the header already shows the title.
        info.mediaInfoContainer.visibility = View.VISIBLE
        info.mediaInfoNameContainer.visibility = View.GONE

        val nativeLangs = nativeLanguages(series)

        val romaji = series.romanizedTitle?.takeIf { it.isNotBlank() }
        if (romaji != null) {
            info.mediaInfoNameRomajiContainer.visibility = View.VISIBLE
            info.mediaInfoNameRomaji.text = tripleTab + romaji
            info.mediaInfoNameRomaji.setOnLongClickListener { copyToClipboard(romaji); true }
        } else {
            info.mediaInfoNameRomajiContainer.visibility = View.GONE
        }

        info.mediaInfoMeanScore.text = series.rating?.let { String.format(Locale.US, "%.1f", it / 10.0) }
            ?: getString(R.string.unknown_value)
        info.mediaInfoStatus.text = statusText(series.status)

        val status = series.status?.lowercase()
        val isFinished = status in setOf("completed", "cancelled")
        val isOngoing = status in setOf("releasing", "hiatus")

        val totalChapters = series.totalChapters?.takeIf { it.isNotBlank() }
        val totalRow = info.mediaInfoTotal.parent as? ViewGroup
        if (totalChapters != null && (isFinished || isOngoing)) {
            totalRow?.visibility = View.VISIBLE
            info.mediaInfoTotalTitle.setText(if (isFinished) R.string.total_chaps else R.string.latest_chapter)
            info.mediaInfoTotal.text = totalChapters
        } else {
            totalRow?.visibility = View.GONE
        }

        val finalVolume = series.finalVolume?.takeIf { it.isNotBlank() }
        if (finalVolume != null && (isFinished || isOngoing)) {
            info.mediaInfoDurationContainer.visibility = View.VISIBLE
            (info.mediaInfoDurationContainer.getChildAt(0) as? TextView)
                ?.setText(if (isFinished) R.string.final_volume_label else R.string.latest_volume_label)
            info.mediaInfoDuration.text = finalVolume
        } else {
            info.mediaInfoDurationContainer.visibility = View.GONE
        }

        info.mediaInfoFormatLabel.setText(R.string.format)
        info.mediaInfoFormat.text = formatText(series.type)

        val originName = nativeLangs.firstOrNull()?.let { languageName(it) }
        if (originName != null) {
            info.mediaInfoSourceContainer.visibility = View.VISIBLE
            info.mediaInfoSourceLabel.setText(R.string.origin)
            info.mediaInfoSource.text = originName
        } else {
            info.mediaInfoSourceContainer.visibility = View.GONE
        }

        val contentRating = series.contentRating?.takeIf { it.isNotBlank() }
        if (contentRating != null) {
            info.mediaInfoContentRatingContainer.visibility = View.VISIBLE
            info.mediaInfoContentRating.text = contentRating.replaceFirstChar { it.uppercase() }
        } else {
            info.mediaInfoContentRatingContainer.visibility = View.GONE
        }

        val author = series.authors?.firstOrNull { it.isNotBlank() }
        if (author != null) {
            info.mediaInfoAuthorContainer.visibility = View.VISIBLE
            info.mediaInfoAuthor.text = author
        } else {
            info.mediaInfoAuthorContainer.visibility = View.GONE
        }

        info.mediaInfoStart.text = toFuzzyDate(series.published?.startDate)?.toString()
            ?: series.year?.toString() ?: getString(R.string.unknown_value)
        val endFuzzy = toFuzzyDate(series.published?.endDate)
        if (endFuzzy != null) {
            (info.mediaInfoEnd.parent as? ViewGroup)?.visibility = View.VISIBLE
            info.mediaInfoEnd.text = endFuzzy.toString()
        } else {
            (info.mediaInfoEnd.parent as? ViewGroup)?.visibility = View.GONE
        }

        val rank = series.popularity?.global?.current
        val popRow = info.mediaInfoPopularity.parent as? ViewGroup
        if (rank != null && rank > 0) {
            popRow?.visibility = View.VISIBLE
            (popRow?.getChildAt(0) as? TextView)?.setText(R.string.rank)
            info.mediaInfoPopularity.text = "#$rank"
        } else {
            popRow?.visibility = View.GONE
        }
        (info.mediaInfoFavorites.parent as? ViewGroup)?.visibility = View.GONE

        val desc = series.description?.takeIf { it.isNotBlank() }
            ?: getString(R.string.no_description_available)
        val markwon = buildMarkwon(this, userInputContent = false, linkResolver = { link -> if (!openMangaUpdatesSeriesInApp(link)) openLinkInBrowser(link) })
        markwon.setMarkdown(info.mediaInfoDescription, desc.replace(Regex("\\n{3,}"), "\n\n").trim())
        info.mediaInfoDescription.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        info.mediaInfoDescription.setOnClickListener {
            val target = if (info.mediaInfoDescription.maxLines == 5) 100 else 5
            android.animation.ObjectAnimator.ofInt(info.mediaInfoDescription, "maxLines", target)
                .setDuration(if (target == 100) 950 else 400).start()
        }

        // Detach the container from the inflated fragment root and host it in our scroll.
        (parent.parent as? ViewGroup)?.removeView(parent)
        binding.mangaBakaMediaContent.addView(parent)

        addSynonyms(parent, series, nativeLangs)
        addCovers(parent, series.id, nativeLangs)
        addAnimeAdaptation(parent, series)
        addGenres(parent, series)
        addTags(parent, series)
        addRecommendations(parent, series.id)
    }

    private fun startMangaBakaSearch(
        genreSlug: String? = null,
        genreName: String? = null,
        tag: String? = null,
    ) {
        val intent = Intent(this, SearchActivity::class.java)
            .putExtra("type", SearchType.MANGABAKA.toAnilistString())
        if (!genreSlug.isNullOrBlank()) {
            intent.putExtra("genre", genreSlug)
            if (!genreName.isNullOrBlank()) intent.putExtra("genreName", genreName)
        }
        if (!tag.isNullOrBlank()) intent.putExtra("tag", tag)
        if (!genreSlug.isNullOrBlank() || !tag.isNullOrBlank()) intent.putExtra("search", true)
        startActivity(intent)
    }

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

        val bind = ItemTitleChipgroupBinding.inflate(layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.synonyms)
        shown.forEach { title ->
            val chip = ItemChipSynonymBinding.inflate(layoutInflater, bind.itemChipGroup, false).root
            chip.text = title
            chip.setOnLongClickListener {
                copyToClipboard(title)
                Toast.makeText(this, getString(R.string.copied_title_toast, title), Toast.LENGTH_SHORT).show()
                true
            }
            bind.itemChipGroup.addView(chip)
        }
        parent.addView(bind.root)
    }

    private fun addCovers(parent: ViewGroup, seriesId: Long, nativeLangs: Set<String>) {
        val placeholder = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        parent.addView(placeholder)

        val languages = (listOf("en") + nativeLangs).distinct()
        lifecycleScope.launch {
            val images = withContext(Dispatchers.IO) { MangaBakaApi.getSeriesImages(seriesId, languages) }
            if (isDestroyed || images.isEmpty()) return@launch
            val covers = images.mapNotNull { img ->
                val thumb = img.image?.thumbUrl() ?: return@mapNotNull null
                MangaBakaCover(thumb, img.image?.fullUrl(), img.index)
            }.distinctBy { it.thumbUrl }
            if (covers.isEmpty()) return@launch

            ItemTitleRecyclerBinding.inflate(layoutInflater, placeholder, false).apply {
                itemTitle.setText(R.string.covers)
                val coverAdapter = MangaBakaCoverAdapter(covers)
                itemRecycler.adapter = coverAdapter
                itemRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                    this@MangaBakaMediaActivity, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
                )
                itemMore.visibility = View.VISIBLE
                itemMore.setSafeOnClickListener { coverAdapter.showGallery(itemMore, getString(R.string.covers)) }
                placeholder.addView(root)
            }
        }
    }

    private fun addAnimeAdaptation(parent: ViewGroup, series: MangaBakaApi.Series) {
        val anime = series.anime ?: return
        if (anime.start.isNullOrBlank() && anime.end.isNullOrBlank()) return

        val bind = ItemTitleTextBinding.inflate(layoutInflater, parent, false)
        bind.itemTitle.text = getString(R.string.anime_adaptation)
        bind.itemText.text = buildString {
            if (!anime.start.isNullOrBlank()) append(getString(R.string.anime_start_format, anime.start))
            if (!anime.end.isNullOrBlank()) append(getString(R.string.anime_end_format, anime.end))
        }.trim()
        parent.addView(bind.root)
    }

    private fun addGenres(parent: ViewGroup, series: MangaBakaApi.Series) {
        val genres = series.genres?.filter { it.isNotBlank() } ?: return
        if (genres.isEmpty()) return

        val bind = ItemTitleChipgroupBinding.inflate(layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.genres)
        parent.addView(bind.root)

        lifecycleScope.launch {
            val labels = withContext(Dispatchers.IO) { MangaBakaApi.getGenreLabels() }
            if (isDestroyed) return@launch
            genres.forEach { slug ->
                val display = labels[slug] ?: titleCase(slug.replace('_', ' '))
                val chip = ItemChipBinding.inflate(layoutInflater, bind.itemChipGroup, false).root
                chip.text = display
                chip.setOnClickListener { startMangaBakaSearch(genreSlug = slug, genreName = display) }
                chip.setOnLongClickListener {
                    copyToClipboard(display)
                    Toast.makeText(this@MangaBakaMediaActivity, getString(R.string.copied_title_toast, display), Toast.LENGTH_SHORT).show()
                    true
                }
                bind.itemChipGroup.addView(chip)
            }
        }
    }

    private fun addTags(parent: ViewGroup, series: MangaBakaApi.Series) {
        val tags = series.tags
            ?.filter { it.isGenre != true && !it.name.isNullOrBlank() }
            ?.sortedWith(compareByDescending<MangaBakaApi.TagEntry> { weightRank(it.weight) }.thenBy { it.name })
            ?: return
        if (tags.isEmpty()) return

        val textColor = themeColor(com.google.android.material.R.attr.colorOnSurface)
        val bind = ItemTagsSectionBinding.inflate(layoutInflater, parent, false)

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
            val popup = ListPopupWindow(this)
            popup.anchorView = bind.tagsFilterButton
            popup.setAdapter(TagFilterAdapter(this, filters))
            popup.isModal = true
            popup.setContentWidth(200f.px)
            popup.setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.dropdown_background))
            popup.setOnItemClickListener { _, _, pos, _ ->
                selectFilter(filters[pos])
                popup.dismiss()
            }
            popup.show()
        }

        parent.addView(bind.root)
        selectFilter(filters[1])
    }

    private fun makeTagChip(tag: MangaBakaApi.TagEntry, group: ViewGroup): Chip {
        val name = tag.name ?: ""
        val chip = ItemChipBinding.inflate(layoutInflater, group, false).root

        weightDrawable(tag.weight)?.let { res ->
            chip.closeIcon = ContextCompat.getDrawable(this, res)
            chip.isCloseIconVisible = true
            chip.closeIconTint = chip.textColors
            chip.closeIconSize = 16f.px.toFloat()
            chip.closeIconStartPadding = 2f.px.toFloat()
        }

        val search = { startMangaBakaSearch(tag = name) }

        if (tag.isSpoiler == true) {
            chip.text = "▓".repeat(name.length.coerceIn(3, 12))
            val revealed = booleanArrayOf(false)
            val onTap = {
                if (!revealed[0]) { revealed[0] = true; chip.text = name } else search()
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
            Toast.makeText(this, getString(R.string.copied_title_toast, name), Toast.LENGTH_SHORT).show()
            true
        }
        return chip
    }

    private data class TagFilter(val label: String, val chevron: Int?, val threshold: Int)

    private class TagFilterAdapter(
        private val ctx: Context,
        private val filters: List<TagFilter>,
    ) : BaseAdapter() {
        override fun getCount() = filters.size
        override fun getItem(position: Int) = filters[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_tag_filter_row, parent, false)
            val f = filters[position]
            row.findViewById<TextView>(R.id.filterLabel).text = f.label
            val iv = row.findViewById<ImageView>(R.id.filterChevron)
            if (f.chevron != null) {
                iv.visibility = View.VISIBLE
                iv.setImageResource(f.chevron)
                iv.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.bg_opp))
            } else {
                iv.visibility = View.INVISIBLE
            }
            return row
        }
    }

    private fun addRecommendations(parent: ViewGroup, seriesId: Long) {
        val placeholder = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        parent.addView(placeholder)

        lifecycleScope.launch {
            val similar = withContext(Dispatchers.IO) { MangaBakaApi.getSimilar(seriesId) }
            if (isDestroyed || similar.isEmpty()) return@launch

            val recAnilistPairs = mutableListOf<Pair<Int, Int>>()
            val indexToMedia = mutableMapOf<Int, Media>()

            similar.forEachIndexed { index, item ->
                val s = item.series ?: return@forEachIndexed
                val anilistId = s.source?.anilist?.id
                val muId = s.source?.mangaUpdates?.toMuSeriesId()
                when {
                    anilistId != null && anilistId > 0 -> recAnilistPairs.add(index to anilistId)
                    muId != null && muId > 0 -> {
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
                val ids = recAnilistPairs.map { it.second }.distinct()
                val batchById = withContext(Dispatchers.IO) {
                    try { Anilist.query.getMediaBatch(ids) } catch (e: Exception) { emptyList() }
                }.associateBy { it.id }
                for ((index, anilistId) in recAnilistPairs) {
                    batchById[anilistId]?.let { indexToMedia[index] = it }
                }
            }

            val recommended = indexToMedia.keys.sorted().mapNotNull { indexToMedia[it] }
            if (recommended.isEmpty() || isDestroyed) return@launch

            ItemTitleRecyclerBinding.inflate(layoutInflater, placeholder, false).apply {
                itemTitle.setText(R.string.recommended)
                itemRecycler.adapter = MediaAdaptor(0, ArrayList(recommended), this@MangaBakaMediaActivity)
                itemRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                    this@MangaBakaMediaActivity, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
                )
                itemMore.visibility = View.VISIBLE
                itemMore.setSafeOnClickListener {
                    MediaListViewActivity.passedMedia = ArrayList(recommended)
                    startActivity(
                        Intent(this@MangaBakaMediaActivity, MediaListViewActivity::class.java)
                            .putExtra("title", getString(R.string.recommended))
                    )
                }
                placeholder.addView(root)
            }
        }
    }

    // --- helpers (mirrors MangaBakaInfoFragment) ---

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
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
