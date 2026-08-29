package ani.dantotsu.media

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.buildMarkwon
import ani.dantotsu.connections.kitsu.KitsuApi
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemChipSynonymBinding
import ani.dantotsu.databinding.ItemTitleChipgroupBinding
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.openLinkInBrowser
import java.util.Locale

/**
 * Populates a [FragmentMediaInfoBinding] from a fully-loaded Kitsu media, so the standalone
 * [KitsuMediaActivity] and the future Kitsu info tab render identically. The host inflates the
 * binding, decides where `info.root`/`info.mediaInfoContainer` lives, and calls [render].
 */
object KitsuMediaRenderer {

    private const val tripleTab = "\t\t\t"

    @SuppressLint("SetTextI18n")
    fun render(
        activity: AppCompatActivity,
        info: FragmentMediaInfoBinding,
        contentHost: LinearLayout,
        full: KitsuApi.KitsuMediaFull,
        isAnime: Boolean,
        onCategoryClick: (slug: String, name: String) -> Unit,
        onRelationClick: (KitsuApi.Relation) -> Unit,
    ) {
        val media = full.media
        val container = info.mediaInfoContainer
        container.visibility = View.VISIBLE
        info.mediaInfoNameContainer.visibility = View.GONE

        val canonical = media.canonicalTitle?.trim()
        val romaji = media.titles?.get("en_jp")?.trim()?.takeIf { it.isNotBlank() && !it.equals(canonical, true) }
        if (romaji != null) {
            info.mediaInfoNameRomajiContainer.visibility = View.VISIBLE
            info.mediaInfoNameRomaji.text = tripleTab + romaji
            info.mediaInfoNameRomaji.setOnLongClickListener { copyToClipboard(romaji); true }
        } else {
            info.mediaInfoNameRomajiContainer.visibility = View.GONE
        }

        // ---- score / ranks ----
        val score = media.averageRating?.toDoubleOrNull()?.let { it / 10.0 }
        info.mediaInfoMeanScore.text = score?.let { String.format(Locale.US, "%.1f", it) }
            ?: activity.getString(R.string.unknown_value)

        info.mediaInfoStatus.text = statusText(activity, media.status)

        // ---- total (episodes / chapters + volumes) ----
        val totalRow = info.mediaInfoTotal.parent as? ViewGroup
        val totalCount = if (isAnime) media.episodeCount else media.chapterCount
        if (totalCount != null && totalCount > 0) {
            totalRow?.visibility = View.VISIBLE
            info.mediaInfoTotalTitle.setText(if (isAnime) R.string.total_eps else R.string.total_chaps)
            info.mediaInfoTotal.text = totalCount.toString()
        } else {
            totalRow?.visibility = View.GONE
        }

        // ---- duration (episode length / volume count) ----
        val durationLabel: Int
        val durationText = if (isAnime) {
            durationLabel = R.string.ep_duration
            media.episodeLength?.takeIf { it > 0 }?.let { "$it min" }
        } else {
            durationLabel = R.string.volumes
            media.volumeCount?.takeIf { it > 0 }?.toString()
        }
        if (durationText != null) {
            info.mediaInfoDurationContainer.visibility = View.VISIBLE
            (info.mediaInfoDurationContainer.getChildAt(0) as? TextView)?.setText(durationLabel)
            info.mediaInfoDuration.text = durationText
        } else {
            info.mediaInfoDurationContainer.visibility = View.GONE
        }

        // ---- format ----
        info.mediaInfoFormatLabel.setText(R.string.format)
        info.mediaInfoFormat.text = (media.subtype ?: media.showType ?: media.mangaType)
            ?.let { titleCase(it) } ?: activity.getString(R.string.unknown)

        // ---- content rating ----
        val ageRating = media.ageRating?.takeIf { it.isNotBlank() }
        if (ageRating != null) {
            info.mediaInfoContentRatingContainer.visibility = View.VISIBLE
            info.mediaInfoContentRating.text = listOfNotNull(
                ageRating,
                media.ageRatingGuide?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        } else {
            info.mediaInfoContentRatingContainer.visibility = View.GONE
        }

        // ---- serialization (manga) ----
        val serialization = media.serialization?.takeIf { it.isNotBlank() }
        if (!isAnime && serialization != null) {
            info.mediaInfoAuthorContainer.visibility = View.VISIBLE
            info.mediaInfoAuthor.text = serialization
        } else {
            info.mediaInfoAuthorContainer.visibility = View.GONE
        }

        // ---- season ----
        val seasonYear = media.startDate?.take(4)
        // Kitsu's `season` is frequently absent — fall back to the start month.
        val season = media.season?.takeIf { it.isNotBlank() }?.let { titleCase(it) }
            ?: (if (isAnime) TrackerFmt.animeSeason(media.startDate) else null)
        if (season != null || (isAnime && seasonYear != null)) {
            info.mediaInfoSeasonContainer.visibility = View.VISIBLE
            info.mediaInfoSeason.text = listOfNotNull(season, seasonYear).joinToString(" ")
        } else {
            info.mediaInfoSeasonContainer.visibility = View.GONE
        }

        // ---- dates ----
        info.mediaInfoStart.text = TrackerFmt.date(media.startDate) ?: activity.getString(R.string.unknown_value)
        val end = TrackerFmt.date(media.endDate)
        (info.mediaInfoEnd.parent as? ViewGroup)?.visibility =
            if (end != null) View.VISIBLE else View.GONE
        info.mediaInfoEnd.text = end ?: ""

        // ---- popularity / favourites ----
        val popRow = info.mediaInfoPopularity.parent as? ViewGroup
        val popRank = media.popularityRank ?: media.ratingRank
        if (popRank != null && popRank > 0) {
            popRow?.visibility = View.VISIBLE
            info.mediaInfoPopularity.text = "#$popRank"
        } else {
            popRow?.visibility = View.GONE
        }
        val favRow = info.mediaInfoFavorites.parent as? ViewGroup
        val favs = media.favoritesCount ?: media.userCount
        if (favs != null && favs > 0) {
            favRow?.visibility = View.VISIBLE
            info.mediaInfoFavorites.text = favs.toString()
        } else {
            favRow?.visibility = View.GONE
        }

        // ---- synopsis ----
        val desc = media.synopsis?.takeIf { it.isNotBlank() }
            ?: media.description?.takeIf { it.isNotBlank() }
            ?: activity.getString(R.string.no_description_available)
        val markwon = buildMarkwon(activity, userInputContent = false)
        markwon.setMarkdown(info.mediaInfoDescription, desc.replace(Regex("\\n{3,}"), "\n\n").trim())
        info.mediaInfoDescription.movementMethod = LinkMovementMethod.getInstance()
        info.mediaInfoDescription.setOnClickListener {
            val target = if (info.mediaInfoDescription.maxLines == 5) 100 else 5
            ObjectAnimator.ofInt(info.mediaInfoDescription, "maxLines", target)
                .setDuration(if (target == 100) 950 else 400).start()
        }

        // Standalone page: reparent the stats table into the activity's own scroll before appending
        // the dynamic sections. Info tab: the binding *is* the fragment view, so the container is
        // already in place — just append after it.
        if (contentHost !== container) {
            (container.parent as? ViewGroup)?.removeView(container)
            contentHost.addView(container)
        }

        addSynonyms(activity, contentHost, media, canonical)
        addCategories(activity, contentHost, full.categories, onCategoryClick)
        if (isAnime) addStreamers(activity, contentHost, full.streamers)
        addRelations(activity, contentHost, full.relations, onRelationClick)
        addTrailer(activity, contentHost, media.youtubeVideoId)
    }

    fun toEpisodeRows(episodes: List<KitsuApi.KitsuEpisode>): List<TrackerEpisodeRenderer.EpisodeRow> =
        episodes.mapIndexed { i, ep ->
            TrackerEpisodeRenderer.EpisodeRow(
                number = ep.number?.toString() ?: (i + 1).toString(),
                title = ep.title,
                desc = ep.synopsis,
                thumbUrl = ep.thumb,
                date = ep.airdate,
            )
        }

    private fun addSynonyms(
        activity: AppCompatActivity,
        parent: ViewGroup,
        media: KitsuApi.KitsuMedia,
        primary: String?,
    ) {
        val shown = LinkedHashSet<String>()
        media.titles?.values?.forEach { t -> t?.trim()?.takeIf { it.isNotBlank() && !it.equals(primary, true) }?.let { shown.add(it) } }
        media.abbreviatedTitles?.forEach { t -> t.trim().takeIf { it.isNotBlank() && !it.equals(primary, true) }?.let { shown.add(it) } }
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
        parent.addView(bind.root)
    }

    private fun addCategories(
        activity: AppCompatActivity,
        parent: ViewGroup,
        categories: List<Pair<String, String>>,
        onCategoryClick: (String, String) -> Unit,
    ) {
        if (categories.isEmpty()) return
        val bind = ItemTitleChipgroupBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.genres)
        categories.forEach { (slug, name) ->
            val chip = ItemChipBinding.inflate(activity.layoutInflater, bind.itemChipGroup, false).root
            chip.text = name
            chip.setOnClickListener { onCategoryClick(slug, name) }
            chip.setOnLongClickListener {
                copyToClipboard(name)
                true
            }
            bind.itemChipGroup.addView(chip)
        }
        parent.addView(bind.root)
    }

    private fun addStreamers(
        activity: AppCompatActivity,
        parent: ViewGroup,
        streamers: List<Pair<String, String>>,
    ) {
        if (streamers.isEmpty()) return
        val bind = ItemTitleChipgroupBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.comick_resources)
        streamers.forEach { (name, url) ->
            val chip = ItemChipBinding.inflate(activity.layoutInflater, bind.itemChipGroup, false).root
            chip.text = name
            chip.setOnClickListener { openLinkInBrowser(url) }
            bind.itemChipGroup.addView(chip)
        }
        parent.addView(bind.root)
    }

    private fun addRelations(
        activity: AppCompatActivity,
        parent: ViewGroup,
        relations: List<KitsuApi.Relation>,
        onRelationClick: (KitsuApi.Relation) -> Unit,
    ) {
        if (relations.isEmpty()) return
        // Same compact media-card strip AniList's relations section uses.
        val bind = ItemTitleRecyclerBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.relations)
        bind.itemRecycler.adapter = KitsuRelationsAdapter(relations, onRelationClick)
        bind.itemRecycler.layoutManager =
            LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        parent.addView(bind.root)
    }

    private fun addTrailer(activity: AppCompatActivity, parent: ViewGroup, youtubeId: String?) {
        val id = youtubeId?.takeIf { it.isNotBlank() } ?: return
        val bind = ItemTitleChipgroupBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.trailer)
        val chip = ItemChipBinding.inflate(activity.layoutInflater, bind.itemChipGroup, false).root
        chip.text = activity.getString(R.string.trailer)
        chip.setOnClickListener { openLinkInBrowser("https://www.youtube.com/watch?v=$id") }
        bind.itemChipGroup.addView(chip)
        parent.addView(bind.root)
    }

    private fun statusText(activity: AppCompatActivity, status: String?): String = when (status?.lowercase()) {
        "current" -> activity.getString(R.string.ongoing)
        "finished" -> activity.getString(R.string.completed)
        "tba" -> activity.getString(R.string.unknown)
        "unreleased", "upcoming" -> activity.getString(R.string.upcoming)
        else -> activity.getString(R.string.unknown)
    }

    private fun titleCase(text: String): String =
        text.split('_', '-', ' ').filter { it.isNotBlank() }
            .joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }
}
