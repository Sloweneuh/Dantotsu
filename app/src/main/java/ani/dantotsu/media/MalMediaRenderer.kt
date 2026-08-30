package ani.dantotsu.media

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.buildMarkwon
import ani.dantotsu.connections.mal.MALAnimeResponse
import ani.dantotsu.connections.mal.MALGenre
import ani.dantotsu.connections.mal.MALMangaResponse
import ani.dantotsu.connections.mal.MALRelatedNode
import ani.dantotsu.connections.mal.MALRelation
import ani.dantotsu.connections.mal.MALStack
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemChipSynonymBinding
import ani.dantotsu.databinding.ItemTitleChipgroupBinding
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.setSafeOnClickListener
import java.util.Locale

/**
 * Populates a [FragmentMediaInfoBinding] from a fully-loaded official-API MAL media (via
 * [ani.dantotsu.connections.mal.MAL.query]), so [MalMediaActivity] renders the way
 * [KitsuMediaRenderer]/[SimklMediaRenderer] do. Anime and manga responses are different types
 * (unlike Kitsu/Jikan's unified model), so — mirroring [MALInfoFragment]'s own
 * `displayAnimeInfo`/`displayMangaInfo` split — there are two entry points sharing the section
 * builders below.
 */
object MalMediaRenderer {

    private const val tripleTab = "\t\t\t"

    @SuppressLint("SetTextI18n")
    fun renderAnime(
        activity: AppCompatActivity,
        info: FragmentMediaInfoBinding,
        contentHost: LinearLayout,
        full: MALAnimeResponse,
        onGenreClick: (name: String) -> Unit,
        onRelationClick: (malId: Int, isAnime: Boolean) -> Unit,
        anilistRecs: List<Media> = emptyList(),
    ) {
        val container = prepare(info, contentHost)

        val canonical = full.alternativeTitles?.en?.trim()?.takeIf { it.isNotBlank() }
            ?: full.alternativeTitles?.ja?.trim()?.takeIf { it.isNotBlank() } ?: full.title.trim()
        setNames(info, canonical, full.title.trim())

        info.mediaInfoMeanScore.text = full.mean?.let { String.format(Locale.US, "%.1f", it) }
            ?: activity.getString(R.string.unknown_value)
        info.mediaInfoStatus.text = formatStatus(activity, full.status)

        val totalRow = info.mediaInfoTotal.parent as? ViewGroup
        if (full.numEpisodes != null && full.numEpisodes > 0) {
            totalRow?.visibility = View.VISIBLE
            info.mediaInfoTotalTitle.setText(R.string.total_eps)
            info.mediaInfoTotal.text = full.numEpisodes.toString()
        } else {
            totalRow?.visibility = View.GONE
        }

        val duration = full.averageEpisodeDuration?.takeIf { it > 0 }?.let { formatDuration(it) }
        if (duration != null) {
            info.mediaInfoDurationContainer.visibility = View.VISIBLE
            (info.mediaInfoDurationContainer.getChildAt(0) as? android.widget.TextView)?.setText(R.string.ep_duration)
            info.mediaInfoDuration.text = duration
        } else {
            info.mediaInfoDurationContainer.visibility = View.GONE
        }

        info.mediaInfoFormatLabel.setText(R.string.format)
        info.mediaInfoFormat.text = full.mediaType?.let { titleCase(it) } ?: activity.getString(R.string.unknown)

        val source = full.source?.takeIf { it.isNotBlank() && !it.equals("original", true) }
        if (source != null) {
            info.mediaInfoSourceContainer.visibility = View.VISIBLE
            info.mediaInfoSource.text = titleCase(source)
        } else {
            info.mediaInfoSourceContainer.visibility = View.GONE
        }

        val rating = full.rating?.takeIf { it.isNotBlank() }
        if (rating != null) {
            info.mediaInfoContentRatingContainer.visibility = View.VISIBLE
            info.mediaInfoContentRating.text = formatRating(rating)
        } else {
            info.mediaInfoContentRatingContainer.visibility = View.GONE
        }

        val studio = full.studios.firstOrNull()?.name?.takeIf { it.isNotBlank() }
        if (studio != null) {
            info.mediaInfoStudioContainer.visibility = View.VISIBLE
            info.mediaInfoStudio.text = studio
        } else {
            info.mediaInfoStudioContainer.visibility = View.GONE
        }

        setSeason(info, full.startSeason?.season, full.startSeason?.year)
        setDates(info, full.startDate, full.endDate)
        setPopularityFavorites(info, full.rank, full.popularity, full.numScoringUsers)
        setSynopsis(activity, info, full.synopsis)

        addSynonyms(activity, contentHost, full.alternativeTitles?.synonyms.orEmpty(), canonical)
        addGenres(activity, contentHost, full.genres, onGenreClick)
        addRelations(activity, contentHost, full.relatedAnime, full.relatedManga, onRelationClick)
        addRecommendations(activity, contentHost, anilistRecs)
    }

    @SuppressLint("SetTextI18n")
    fun renderManga(
        activity: AppCompatActivity,
        info: FragmentMediaInfoBinding,
        contentHost: LinearLayout,
        full: MALMangaResponse,
        onGenreClick: (name: String) -> Unit,
        onRelationClick: (malId: Int, isAnime: Boolean) -> Unit,
        anilistRecs: List<Media> = emptyList(),
    ) {
        val container = prepare(info, contentHost)

        val canonical = full.alternativeTitles?.en?.trim()?.takeIf { it.isNotBlank() }
            ?: full.alternativeTitles?.ja?.trim()?.takeIf { it.isNotBlank() } ?: full.title.trim()
        setNames(info, canonical, full.title.trim())

        info.mediaInfoMeanScore.text = full.mean?.let { String.format(Locale.US, "%.1f", it) }
            ?: activity.getString(R.string.unknown_value)
        info.mediaInfoStatus.text = formatStatus(activity, full.status)

        val totalRow = info.mediaInfoTotal.parent as? ViewGroup
        if (full.numChapters != null && full.numChapters > 0) {
            totalRow?.visibility = View.VISIBLE
            info.mediaInfoTotalTitle.setText(R.string.total_chaps)
            info.mediaInfoTotal.text = full.numChapters.toString()
        } else {
            totalRow?.visibility = View.GONE
        }

        val volumes = full.numVolumes?.takeIf { it > 0 }
        if (volumes != null) {
            info.mediaInfoDurationContainer.visibility = View.VISIBLE
            (info.mediaInfoDurationContainer.getChildAt(0) as? android.widget.TextView)?.setText(R.string.volumes)
            info.mediaInfoDuration.text = volumes.toString()
        } else {
            info.mediaInfoDurationContainer.visibility = View.GONE
        }

        info.mediaInfoFormatLabel.setText(R.string.format)
        info.mediaInfoFormat.text = full.mediaType?.let { titleCase(it) } ?: activity.getString(R.string.unknown)
        info.mediaInfoSourceContainer.visibility = View.GONE

        val authorWithStory = full.authors.find { it.role?.contains("Story", ignoreCase = true) == true }
        val author = (authorWithStory ?: full.authors.firstOrNull())?.node
        val authorName = author?.let { "${it.firstName ?: ""} ${it.lastName ?: ""}".trim() }?.takeIf { it.isNotBlank() }
        if (authorName != null) {
            info.mediaInfoAuthorContainer.visibility = View.VISIBLE
            info.mediaInfoAuthor.text = authorName
        } else {
            info.mediaInfoAuthorContainer.visibility = View.GONE
        }

        info.mediaInfoSeasonContainer.visibility = View.GONE
        setDates(info, full.startDate, full.endDate)
        setPopularityFavorites(info, full.rank, full.popularity, full.numScoringUsers)
        setSynopsis(activity, info, full.synopsis)

        addSynonyms(activity, contentHost, full.alternativeTitles?.synonyms.orEmpty(), canonical)
        addGenres(activity, contentHost, full.genres, onGenreClick)
        addRelations(activity, contentHost, full.relatedAnime, full.relatedManga, onRelationClick)
        addRecommendations(activity, contentHost, anilistRecs)
    }

    // ---- shared field setters ----

    private fun prepare(info: FragmentMediaInfoBinding, contentHost: LinearLayout): LinearLayout {
        val container = info.mediaInfoContainer
        container.visibility = View.VISIBLE
        info.mediaInfoNameContainer.visibility = View.GONE
        // Standalone page: reparent the stats table into the activity's own scroll before appending
        // the dynamic sections, same guard KitsuMediaRenderer/SimklMediaRenderer use.
        if (contentHost !== container) {
            (container.parent as? ViewGroup)?.removeView(container)
            contentHost.addView(container)
        }
        return container
    }

    private fun setNames(info: FragmentMediaInfoBinding, canonical: String, rawTitle: String) {
        val romaji = rawTitle.takeIf { it.isNotBlank() && !it.equals(canonical, true) }
        if (romaji != null) {
            info.mediaInfoNameRomajiContainer.visibility = View.VISIBLE
            info.mediaInfoNameRomaji.text = tripleTab + romaji
            info.mediaInfoNameRomaji.setOnLongClickListener { copyToClipboard(romaji); true }
        } else {
            info.mediaInfoNameRomajiContainer.visibility = View.GONE
        }
    }

    private fun setSeason(info: FragmentMediaInfoBinding, season: String?, year: Int?) {
        if (season != null || year != null) {
            info.mediaInfoSeasonContainer.visibility = View.VISIBLE
            info.mediaInfoSeason.text = listOfNotNull(season?.let { titleCase(it) }, year).joinToString(" ")
        } else {
            info.mediaInfoSeasonContainer.visibility = View.GONE
        }
    }

    private fun setDates(info: FragmentMediaInfoBinding, start: String?, end: String?) {
        info.mediaInfoStart.text = TrackerFmt.date(start) ?: start ?: "?"
        val endText = TrackerFmt.date(end) ?: end
        (info.mediaInfoEnd.parent as? ViewGroup)?.visibility = if (endText != null) View.VISIBLE else View.GONE
        info.mediaInfoEnd.text = endText ?: ""
    }

    private fun setPopularityFavorites(info: FragmentMediaInfoBinding, rank: Int?, popularity: Int?, numScoringUsers: Int?) {
        val popRow = info.mediaInfoPopularity.parent as? ViewGroup
        val popRank = rank ?: popularity
        if (popRank != null && popRank > 0) {
            popRow?.visibility = View.VISIBLE
            info.mediaInfoPopularity.text = "#$popRank"
        } else {
            popRow?.visibility = View.GONE
        }
        val favRow = info.mediaInfoFavorites.parent as? ViewGroup
        if (numScoringUsers != null && numScoringUsers > 0) {
            favRow?.visibility = View.VISIBLE
            info.mediaInfoFavorites.text = numScoringUsers.toString()
        } else {
            favRow?.visibility = View.GONE
        }
    }

    private fun setSynopsis(activity: AppCompatActivity, info: FragmentMediaInfoBinding, synopsis: String?) {
        val desc = synopsis?.takeIf { it.isNotBlank() } ?: activity.getString(R.string.no_description_available)
        val markwon = buildMarkwon(activity, userInputContent = false)
        markwon.setMarkdown(info.mediaInfoDescription, desc.replace(Regex("\\n{3,}"), "\n\n").trim())
        info.mediaInfoDescription.movementMethod = LinkMovementMethod.getInstance()
        info.mediaInfoDescription.setOnClickListener {
            val target = if (info.mediaInfoDescription.maxLines == 5) 100 else 5
            ObjectAnimator.ofInt(info.mediaInfoDescription, "maxLines", target)
                .setDuration(if (target == 100) 950 else 400).start()
        }
    }

    private fun formatDuration(seconds: Int): String {
        val minutes = seconds / 60
        val hours = minutes / 60
        val mins = minutes % 60
        return buildString {
            if (hours > 0) append("$hours hour").also { if (hours > 1) append("s") }
            if (mins > 0) {
                if (hours > 0) append(", ")
                append("$mins min").also { if (mins > 1) append("s") }
            }
        }.ifEmpty { "$seconds sec" }
    }

    private fun formatStatus(activity: AppCompatActivity, status: String?): String = when (status?.lowercase()) {
        "finished_airing", "finished" -> activity.getString(R.string.completed)
        "currently_airing", "currently_publishing" -> activity.getString(R.string.ongoing)
        "not_yet_aired", "not_yet_published" -> activity.getString(R.string.upcoming)
        "on_hiatus", "on_hold" -> "On Hiatus"
        "discontinued", "cancelled" -> "Cancelled"
        null -> activity.getString(R.string.unknown)
        else -> titleCase(status)
    }

    private fun addSynonyms(activity: AppCompatActivity, parent: ViewGroup, synonyms: List<String>, primary: String?) {
        val shown = synonyms.mapNotNull { it.trim().takeIf { s -> s.isNotBlank() && !s.equals(primary, true) } }.distinct()
        if (shown.isEmpty()) return
        val bind = ItemTitleChipgroupBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.synonyms)
        shown.forEach { title ->
            val chip = ItemChipSynonymBinding.inflate(activity.layoutInflater, bind.itemChipGroup, false).root
            chip.text = title
            chip.setOnLongClickListener { copyToClipboard(title); true }
            bind.itemChipGroup.addView(chip)
        }
        parent.addView(bind.root)
    }

    private fun addGenres(
        activity: AppCompatActivity,
        parent: ViewGroup,
        genres: List<MALGenre>,
        onGenreClick: (String) -> Unit,
    ) {
        if (genres.isEmpty()) return
        val bind = ItemTitleChipgroupBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.genres)
        genres.forEach { g ->
            val chip = ItemChipBinding.inflate(activity.layoutInflater, bind.itemChipGroup, false).root
            chip.text = g.name
            chip.setOnClickListener { onGenreClick(g.name) }
            chip.setOnLongClickListener { copyToClipboard(g.name); true }
            bind.itemChipGroup.addView(chip)
        }
        parent.addView(bind.root)
    }

    /** MAL's related-node entries carry only id/title/main_picture — no synopsis — so a chip
     * strip, not a card recycler, same call as Kitsu's relations for name-only data.
     * `relatedAnime`/`relatedManga` come from separate response fields, so the anime/manga split
     * is tagged onto each pair here rather than concatenated away before we can use it. */
    private fun addRelations(
        activity: AppCompatActivity,
        parent: ViewGroup,
        relatedAnime: List<MALRelation>,
        relatedManga: List<MALRelation>,
        onRelationClick: (Int, Boolean) -> Unit,
    ) {
        val relations = relatedAnime.map { it to true } + relatedManga.map { it to false }
        if (relations.isEmpty()) return
        val bind = ItemTitleChipgroupBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.relations)
        relations.forEach { (rel, relIsAnime) ->
            val node: MALRelatedNode = rel.node
            val chip = ItemChipBinding.inflate(activity.layoutInflater, bind.itemChipGroup, false).root
            chip.text = "${titleCase(rel.relationTypeFormatted)}: ${node.title}"
            chip.setOnClickListener { onRelationClick(node.id, relIsAnime) }
            bind.itemChipGroup.addView(chip)
        }
        parent.addView(bind.root)
    }

    private val acronyms = setOf("tv", "ova", "ona")

    /** MAL's rating codes (g/pg/pg_13/r/r+/rx) — titleCase alone reads "Pg 13"; these are the
     * conventional labels, matching the industry notation Kitsu's own rating chips use. */
    private fun formatRating(rating: String): String = when (rating.lowercase()) {
        "g" -> "G"
        "pg" -> "PG"
        "pg_13" -> "PG-13"
        "r" -> "R - 17+"
        "r+" -> "R+"
        "rx" -> "Rx"
        else -> titleCase(rating)
    }

    private fun titleCase(text: String): String =
        text.split('_', '-', ' ').filter { it.isNotBlank() }
            .joinToString(" ") { p -> if (p.lowercase() in acronyms) p.uppercase() else p.replaceFirstChar { it.uppercase() } }

    private fun addRecommendations(activity: AppCompatActivity, parent: ViewGroup, anilistRecs: List<Media>) {
        if (anilistRecs.isEmpty()) return
        val bind = ItemTitleRecyclerBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.recommended)
        bind.itemRecycler.adapter = MediaAdaptor(0, anilistRecs.toMutableList(), activity)
        bind.itemRecycler.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        bind.itemMore.visibility = View.VISIBLE
        bind.itemMore.setSafeOnClickListener {
            MediaListViewActivity.passedMedia = ArrayList(anilistRecs)
            activity.startActivity(
                android.content.Intent(activity, MediaListViewActivity::class.java)
                    .putExtra("title", activity.getString(R.string.recommended))
            )
        }
        parent.addView(bind.root)
    }

    /**
     * Interest stacks aren't in the official API — [MalMediaActivity] scrapes them from the MAL
     * page after the first render, then appends the row here, the same way [MALInfoFragment]'s tab
     * does. Public (unlike the other section builders) so the activity can call it once that
     * multi-page scrape lands; a no-op for an empty list. Sits after recommendations, matching the
     * tab's ordering.
     */
    fun addStacks(activity: AppCompatActivity, parent: ViewGroup, stacks: List<MALStack>, isAnime: Boolean) {
        if (stacks.isEmpty()) return
        val bind = ItemTitleRecyclerBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.interest_stacks)
        bind.itemRecycler.adapter = StackAdapter(stacks, isAnime)
        bind.itemRecycler.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        bind.itemMore.visibility = View.VISIBLE
        bind.itemMore.setSafeOnClickListener {
            StackListViewActivity.passedStacks = ArrayList(stacks)
            activity.startActivity(
                android.content.Intent(activity, StackListViewActivity::class.java)
                    .putExtra("title", activity.getString(R.string.interest_stacks))
                    .putExtra("isAnime", isAnime)
            )
        }
        parent.addView(bind.root)
    }
}
