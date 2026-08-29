package ani.dantotsu.media

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.buildMarkwon
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.isMuNovelType
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemChipSynonymBinding
import ani.dantotsu.databinding.ItemTagsSectionBinding
import ani.dantotsu.databinding.ItemTitleChipgroupBinding
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.databinding.ItemTitleTextBinding
import ani.dantotsu.media.manga.Manga
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.openMangaUpdatesSeriesInApp
import ani.dantotsu.px
import ani.dantotsu.setSafeOnClickListener
import com.google.android.material.chip.Chip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The single MangaBaka series → [FragmentMediaInfoBinding] rendering, shared by the standalone
 * [MangaBakaMediaActivity] and the [MangaBakaInfoFragment] info tab (they used to hold a full copy
 * each). [MangaBakaMediaActivity] passes a distinct [contentHost] (its own scroll) and no
 * [RecoConfig]; the fragment passes `info.mediaInfoContainer` and a [RecoConfig] for its
 * AniList-aware recommendations row.
 */
object MangaBakaMediaRenderer {

    private const val tripleTab = "\t\t\t"

    /** The fragment-only "similar → recommendations" row needs the current media + its view model. */
    data class RecoConfig(val media: Media, val model: MediaDetailsViewModel)

    @SuppressLint("SetTextI18n")
    fun render(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        isAlive: () -> Boolean,
        info: FragmentMediaInfoBinding,
        contentHost: ViewGroup,
        series: MangaBakaApi.Series,
        seriesId: Long,
        /** true → show the title in the stats "Name" row (info tab); false → header shows it. */
        nameInStats: Boolean,
        markwonFragment: Fragment? = null,
        onSearch: (genreSlug: String?, genreName: String?, tag: String?) -> Unit,
        reco: RecoConfig? = null,
    ) {
        val container = info.mediaInfoContainer
        container.visibility = View.VISIBLE

        // Clear any previously-added dynamic MangaBaka views (re-entry).
        (0 until contentHost.childCount).mapNotNull { contentHost.getChildAt(it) }
            .filter { (it.tag as? String)?.endsWith("_mangabaka") == true }
            .forEach { contentHost.removeView(it) }

        val nativeLangs = nativeLanguages(series)
        val displayTitle = series.displayTitle() ?: activity.getString(R.string.unknown)

        if (nameInStats) {
            info.mediaInfoNameContainer.visibility = View.VISIBLE
            info.mediaInfoName.text = tripleTab + displayTitle
            info.mediaInfoName.setOnLongClickListener { copyToClipboard(displayTitle); true }
        } else {
            info.mediaInfoNameContainer.visibility = View.GONE
        }

        val romaji = series.romanizedTitle?.takeIf { it.isNotBlank() }
        if (romaji != null) {
            info.mediaInfoNameRomajiContainer.visibility = View.VISIBLE
            info.mediaInfoNameRomaji.text = tripleTab + romaji
            info.mediaInfoNameRomaji.setOnLongClickListener { copyToClipboard(romaji); true }
        } else {
            info.mediaInfoNameRomajiContainer.visibility = View.GONE
        }

        info.mediaInfoMeanScore.text = series.rating?.let { String.format(Locale.US, "%.1f", it / 10.0) }
            ?: activity.getString(R.string.unknown_value)
        info.mediaInfoStatus.text = statusText(activity, series.status)

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
        info.mediaInfoFormat.text = formatText(activity, series.type)

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
            ?: series.year?.toString() ?: activity.getString(R.string.unknown_value)
        val endFuzzy = toFuzzyDate(series.published?.endDate)
        (info.mediaInfoEnd.parent as? ViewGroup)?.visibility =
            if (endFuzzy != null) View.VISIBLE else View.GONE
        info.mediaInfoEnd.text = endFuzzy?.toString() ?: ""

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
            ?: activity.getString(R.string.no_description_available)
        val markwon = buildMarkwon(
            activity, userInputContent = false, fragment = markwonFragment,
            linkResolver = { link -> if (!openMangaUpdatesSeriesInApp(link)) openLinkInBrowser(link) },
        )
        markwon.setMarkdown(info.mediaInfoDescription, desc.replace(Regex("\\n{3,}"), "\n\n").trim())
        info.mediaInfoDescription.movementMethod = LinkMovementMethod.getInstance()
        info.mediaInfoDescription.setOnClickListener {
            val target = if (info.mediaInfoDescription.maxLines == 5) 100 else 5
            ObjectAnimator.ofInt(info.mediaInfoDescription, "maxLines", target)
                .setDuration(if (target == 100) 950 else 400).start()
        }

        if (contentHost !== container) {
            (container.parent as? ViewGroup)?.removeView(container)
            contentHost.addView(container)
        }

        addSynonyms(activity, contentHost, series, nativeLangs)
        addCovers(activity, scope, isAlive, contentHost, seriesId, nativeLangs)
        addAnimeAdaptation(activity, contentHost, series)
        addGenres(activity, scope, isAlive, contentHost, series, onSearch)
        addTags(activity, contentHost, series, onSearch)
        addRecommendations(activity, scope, isAlive, contentHost, seriesId, reco)
    }

    private fun addSynonyms(
        activity: AppCompatActivity, parent: ViewGroup, series: MangaBakaApi.Series, nativeLangs: Set<String>,
    ) {
        val allowed = nativeLangs + "en"
        val primary = series.displayTitle()?.trim()
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

        val bind = ItemTitleChipgroupBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.synonyms)
        shown.forEach { title ->
            val chip = ItemChipSynonymBinding.inflate(activity.layoutInflater, bind.itemChipGroup, false).root
            chip.text = title
            chip.setOnLongClickListener {
                copyToClipboard(title)
                Toast.makeText(activity, activity.getString(R.string.copied_title_toast, title), Toast.LENGTH_SHORT).show()
                true
            }
            bind.itemChipGroup.addView(chip)
        }
        bind.root.tag = "synonyms_mangabaka"
        parent.addView(bind.root)
    }

    private fun addCovers(
        activity: AppCompatActivity, scope: CoroutineScope, isAlive: () -> Boolean,
        parent: ViewGroup, seriesId: Long, nativeLangs: Set<String>,
    ) {
        val placeholder = FrameLayout(activity).apply {
            tag = "covers_mangabaka"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        parent.addView(placeholder)

        val languages = (listOf("en") + nativeLangs).distinct()
        scope.launch {
            val images = withContext(Dispatchers.IO) { MangaBakaApi.getSeriesImages(seriesId, languages) }
            if (!isAlive() || images.isEmpty()) return@launch
            val covers = images.mapNotNull { img ->
                val thumb = img.image?.thumbUrl() ?: return@mapNotNull null
                MangaBakaCover(thumb, img.image?.fullUrl(), img.index)
            }.distinctBy { it.thumbUrl }
            if (covers.isEmpty()) return@launch

            ItemTitleRecyclerBinding.inflate(activity.layoutInflater, placeholder, false).apply {
                itemTitle.setText(R.string.covers)
                val coverAdapter = MangaBakaCoverAdapter(covers)
                itemRecycler.adapter = coverAdapter
                itemRecycler.layoutManager =
                    LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
                itemMore.visibility = View.VISIBLE
                itemMore.setSafeOnClickListener { coverAdapter.showGallery(itemMore, activity.getString(R.string.covers)) }
                placeholder.addView(root)
            }
        }
    }

    private fun addAnimeAdaptation(activity: AppCompatActivity, parent: ViewGroup, series: MangaBakaApi.Series) {
        val anime = series.anime ?: return
        if (anime.start.isNullOrBlank() && anime.end.isNullOrBlank()) return

        val bind = ItemTitleTextBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.text = activity.getString(R.string.anime_adaptation)
        bind.itemText.text = buildString {
            if (!anime.start.isNullOrBlank()) append(activity.getString(R.string.anime_start_format, anime.start))
            if (!anime.end.isNullOrBlank()) append(activity.getString(R.string.anime_end_format, anime.end))
        }.trim()
        bind.itemText.setOnClickListener {
            val target = if (bind.itemText.maxLines == 4) 100 else 4
            ObjectAnimator.ofInt(bind.itemText, "maxLines", target).setDuration(400).start()
        }
        bind.root.tag = "anime_info_mangabaka"
        parent.addView(bind.root)
    }

    private fun addGenres(
        activity: AppCompatActivity, scope: CoroutineScope, isAlive: () -> Boolean,
        parent: ViewGroup, series: MangaBakaApi.Series,
        onSearch: (String?, String?, String?) -> Unit,
    ) {
        val genres = series.genres?.filter { it.isNotBlank() } ?: return
        if (genres.isEmpty()) return

        val bind = ItemTitleChipgroupBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.genres)
        bind.root.tag = "genres_mangabaka"
        parent.addView(bind.root)

        scope.launch {
            val labels = withContext(Dispatchers.IO) { MangaBakaApi.getGenreLabels() }
            if (!isAlive()) return@launch
            genres.forEach { slug ->
                val display = labels[slug] ?: titleCase(slug.replace('_', ' '))
                val chip = ItemChipBinding.inflate(activity.layoutInflater, bind.itemChipGroup, false).root
                chip.text = display
                chip.setOnClickListener { onSearch(slug, display, null) }
                chip.setOnLongClickListener {
                    copyToClipboard(display)
                    Toast.makeText(activity, activity.getString(R.string.copied_title_toast, display), Toast.LENGTH_SHORT).show()
                    true
                }
                bind.itemChipGroup.addView(chip)
            }
        }
    }

    private fun addTags(
        activity: AppCompatActivity, parent: ViewGroup, series: MangaBakaApi.Series,
        onSearch: (String?, String?, String?) -> Unit,
    ) {
        val tags = series.tags
            ?.filter { it.isGenre != true && !it.name.isNullOrBlank() }
            ?.sortedWith(compareByDescending<MangaBakaApi.TagEntry> { weightRank(it.weight) }.thenBy { it.name })
            ?: return
        if (tags.isEmpty()) return

        val textColor = themeColor(activity, com.google.android.material.R.attr.colorOnSurface)
        val bind = ItemTagsSectionBinding.inflate(activity.layoutInflater, parent, false)
        bind.root.tag = "tags_mangabaka"

        val filters = MangaBakaTagWeights.options.map {
            TagFilter(activity.getString(it.label), it.chevron, it.threshold)
        }

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
                .forEach { bind.tagsChipGroup.addView(makeTagChip(activity, it, bind.tagsChipGroup, onSearch)) }
        }

        bind.tagsFilterButton.setOnClickListener {
            val popup = ListPopupWindow(activity)
            popup.anchorView = bind.tagsFilterButton
            popup.setAdapter(TagFilterAdapter(activity, filters))
            popup.isModal = true
            popup.setContentWidth(200f.px)
            popup.setBackgroundDrawable(ContextCompat.getDrawable(activity, R.drawable.dropdown_background))
            popup.setOnItemClickListener { _, _, pos, _ ->
                selectFilter(filters[pos])
                popup.dismiss()
            }
            popup.show()
        }

        parent.addView(bind.root)
        selectFilter(filters[MangaBakaTagWeights.defaultIndex()])
    }

    private fun makeTagChip(
        activity: AppCompatActivity, tag: MangaBakaApi.TagEntry, group: ViewGroup,
        onSearch: (String?, String?, String?) -> Unit,
    ): Chip {
        val name = tag.name ?: ""
        val chip = ItemChipBinding.inflate(activity.layoutInflater, group, false).root

        weightDrawable(tag.weight)?.let { res ->
            chip.closeIcon = ContextCompat.getDrawable(activity, res)
            chip.isCloseIconVisible = true
            chip.closeIconTint = chip.textColors
            chip.closeIconSize = 16f.px.toFloat()
            chip.closeIconStartPadding = 2f.px.toFloat()
        }

        val search = { onSearch(null, null, name) }

        if (tag.isSpoiler == true) {
            chip.text = "▓".repeat(name.length.coerceIn(3, 12))
            val revealed = booleanArrayOf(false)
            val onTap = { if (!revealed[0]) { revealed[0] = true; chip.text = name } else search() }
            chip.setOnClickListener { onTap() }
            chip.setOnCloseIconClickListener { onTap() }
        } else {
            chip.text = name
            chip.setOnClickListener { search() }
            chip.setOnCloseIconClickListener { search() }
        }
        chip.setOnLongClickListener {
            copyToClipboard(name)
            Toast.makeText(activity, activity.getString(R.string.copied_title_toast, name), Toast.LENGTH_SHORT).show()
            true
        }
        return chip
    }

    private data class TagFilter(val label: String, val chevron: Int?, val threshold: Int)

    private class TagFilterAdapter(
        private val ctx: Context, private val filters: List<TagFilter>,
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

    private suspend fun muFallbackMedia(muId: Long, name: String, cover: String?): Media {
        val muType = withContext(Dispatchers.IO) {
            try { MangaUpdates.getSeriesDetails(muId)?.type } catch (e: Exception) { null }
        }
        return Media(
            id = (muId and 0x7FFFFFFF).toInt(),
            name = name, nameRomaji = name, userPreferredName = name,
            cover = cover, banner = cover, isAdult = false, manga = Manga(),
            format = if (isMuNovelType(muType)) "NOVEL" else "MANGA",
            muSeriesId = muId,
        )
    }

    private fun addRecommendations(
        activity: AppCompatActivity, scope: CoroutineScope, isAlive: () -> Boolean,
        parent: ViewGroup, seriesId: Long, reco: RecoConfig?,
    ) {
        val placeholder = FrameLayout(activity).apply {
            tag = "recommendations_mangabaka"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        parent.addView(placeholder)

        val currentAnilistId = reco?.media?.takeIf { it.muSeriesId == null }?.id
        val currentMuId = reco?.media?.muSeriesId

        scope.launch {
            val similar = withContext(Dispatchers.IO) { MangaBakaApi.getSimilar(seriesId) }
            if (!isAlive() || similar.isEmpty()) return@launch

            val existingRecs = reco?.model?.getMedia()?.value?.recommendations?.associateBy { it.id } ?: emptyMap()
            val recAnilistPairs = mutableListOf<Pair<Int, Int>>()
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
                        val name = s.displayTitle() ?: return@forEachIndexed
                        indexToMedia[index] = muFallbackMedia(muId, name, cover)
                    }
                }
            }

            if (recAnilistPairs.isNotEmpty()) {
                val missingIds = recAnilistPairs.map { it.second }.filter { existingRecs[it] == null }.distinct()
                val batchById = if (missingIds.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        try { Anilist.query.getMediaBatch(missingIds) } catch (e: Exception) { emptyList() }
                    }.associateBy { it.id }
                } else emptyMap()
                for ((index, anilistId) in recAnilistPairs) {
                    (existingRecs[anilistId] ?: batchById[anilistId])?.let { indexToMedia[index] = it }
                }
            }

            val recommended = indexToMedia.keys.sorted().mapNotNull { indexToMedia[it] }
            if (recommended.isEmpty() || !isAlive()) return@launch

            withContext(Dispatchers.Main) {
                if (!isAlive() || placeholder.childCount > 0) return@withContext
                ItemTitleRecyclerBinding.inflate(activity.layoutInflater, placeholder, false).apply {
                    itemTitle.setText(R.string.recommended)
                    itemRecycler.adapter = MediaAdaptor(
                        0, ArrayList(recommended), activity, currentMedia = reco?.media
                    )
                    itemRecycler.layoutManager =
                        LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
                    itemMore.visibility = View.VISIBLE
                    itemMore.setSafeOnClickListener {
                        MediaListViewActivity.passedMedia = ArrayList(recommended)
                        reco?.media?.let { MediaListViewActivity.passedRecommendationSource = it }
                        activity.startActivity(
                            Intent(activity, MediaListViewActivity::class.java)
                                .putExtra("title", activity.getString(R.string.recommended))
                        )
                    }
                    placeholder.addView(root)
                }
            }
        }
    }

    // --- helpers ---

    fun toFuzzyDate(iso: String?): FuzzyDate? {
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

    private fun statusText(ctx: Context, status: String?): String = when (status?.lowercase()) {
        "releasing" -> ctx.getString(R.string.ongoing)
        "completed" -> ctx.getString(R.string.completed)
        "hiatus" -> ctx.getString(R.string.hiatus)
        "cancelled" -> ctx.getString(R.string.cancelled)
        "upcoming" -> ctx.getString(R.string.upcoming)
        else -> ctx.getString(R.string.unknown)
    }

    private fun formatText(ctx: Context, type: String?): String = when (type?.lowercase()) {
        "novel", "light_novel" -> ctx.getString(R.string.novel)
        "manga" -> ctx.getString(R.string.manga)
        "manhwa" -> ctx.getString(R.string.manhwa)
        "manhua" -> ctx.getString(R.string.manhua)
        null -> ctx.getString(R.string.unknown)
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

    private fun themeColor(ctx: Context, attr: Int): Int {
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
