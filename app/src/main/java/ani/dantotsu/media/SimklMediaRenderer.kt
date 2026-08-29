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
import ani.dantotsu.connections.anilist.api.MediaTag
import ani.dantotsu.connections.simkl.SimklApi
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemChipSynonymBinding
import ani.dantotsu.databinding.ItemTitleChipgroupBinding
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.setSafeOnClickListener
import java.util.Locale

/**
 * Populates a [FragmentMediaInfoBinding] from a fully-loaded Simkl anime, shared between the
 * standalone [SimklMediaActivity] and the future Simkl info tab. Mirrors [KitsuMediaRenderer].
 */
object SimklMediaRenderer {

    private const val tripleTab = "\t\t\t"

    @SuppressLint("SetTextI18n")
    fun render(
        activity: AppCompatActivity,
        info: FragmentMediaInfoBinding,
        contentHost: LinearLayout,
        full: SimklApi.SimklAnimeFull,
        anilistTags: List<MediaTag>?,
        onGenreClick: (String) -> Unit,
        onSimklMediaClick: (Long) -> Unit,
        /** Info-tab only: Simkl's recommendations pre-resolved to AniList media (tap → in-app page). */
        anilistRecs: List<Media>? = null,
        recSource: Media? = null,
    ) {
        val container = info.mediaInfoContainer
        container.visibility = View.VISIBLE
        info.mediaInfoNameContainer.visibility = View.GONE

        val primary = full.title?.trim()
        val en = full.enTitle?.trim()?.takeIf { it.isNotBlank() && !it.equals(primary, true) }
        if (en != null) {
            info.mediaInfoNameRomajiContainer.visibility = View.VISIBLE
            info.mediaInfoNameRomaji.text = tripleTab + en
            info.mediaInfoNameRomaji.setOnLongClickListener { copyToClipboard(en); true }
        } else {
            info.mediaInfoNameRomajiContainer.visibility = View.GONE
        }

        val simklRating = full.ratings?.simkl?.rating
        info.mediaInfoMeanScore.text = simklRating?.takeIf { it > 0 }
            ?.let { String.format(Locale.US, "%.1f", it) }
            ?: activity.getString(R.string.unknown_value)

        info.mediaInfoStatus.text = full.status?.let { titleCase(it) } ?: activity.getString(R.string.unknown)

        val totalRow = info.mediaInfoTotal.parent as? ViewGroup
        if (full.totalEpisodes != null && full.totalEpisodes!! > 0) {
            totalRow?.visibility = View.VISIBLE
            info.mediaInfoTotalTitle.setText(R.string.total_eps)
            info.mediaInfoTotal.text = full.totalEpisodes.toString()
        } else {
            totalRow?.visibility = View.GONE
        }

        if (full.runtime != null && full.runtime!! > 0) {
            info.mediaInfoDurationContainer.visibility = View.VISIBLE
            (info.mediaInfoDurationContainer.getChildAt(0) as? TextView)?.setText(R.string.ep_duration)
            info.mediaInfoDuration.text = "${full.runtime} min"
        } else {
            info.mediaInfoDurationContainer.visibility = View.GONE
        }

        info.mediaInfoFormatLabel.setText(R.string.format)
        info.mediaInfoFormat.text = full.animeType?.let { titleCase(it) } ?: activity.getString(R.string.unknown)

        val networkLine = listOfNotNull(
            full.network?.takeIf { it.isNotBlank() },
            full.country?.takeIf { it.isNotBlank() }?.uppercase(),
        ).joinToString(" · ")
        if (networkLine.isNotBlank()) {
            info.mediaInfoSourceContainer.visibility = View.VISIBLE
            info.mediaInfoSourceLabel.setText(R.string.network)
            info.mediaInfoSource.text = networkLine
        } else {
            info.mediaInfoSourceContainer.visibility = View.GONE
        }

        val cert = full.certification?.takeIf { it.isNotBlank() }
        if (cert != null) {
            info.mediaInfoContentRatingContainer.visibility = View.VISIBLE
            info.mediaInfoContentRating.text = cert
        } else {
            info.mediaInfoContentRatingContainer.visibility = View.GONE
        }

        info.mediaInfoAuthorContainer.visibility = View.GONE

        val studios = full.studios?.mapNotNull { it.name?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()
        if (studios.isNotEmpty()) {
            info.mediaInfoStudioContainer.visibility = View.VISIBLE
            info.mediaInfoStudio.text = studios.joinToString(", ")
        } else {
            info.mediaInfoStudioContainer.visibility = View.GONE
        }

        // Simkl ships a ready-made "Summer 2023"; only fall back to the bare year.
        val seasonText = full.seasonNameYear?.takeIf { it.isNotBlank() } ?: full.year?.toString()
        if (seasonText != null) {
            info.mediaInfoSeasonContainer.visibility = View.VISIBLE
            info.mediaInfoSeason.text = seasonText
        } else {
            info.mediaInfoSeasonContainer.visibility = View.GONE
        }

        info.mediaInfoStart.text = TrackerFmt.date(full.firstAired) ?: activity.getString(R.string.unknown_value)
        val end = TrackerFmt.date(full.lastAired)
        (info.mediaInfoEnd.parent as? ViewGroup)?.visibility = if (end != null) View.VISIBLE else View.GONE
        info.mediaInfoEnd.text = end ?: ""

        val popRow = info.mediaInfoPopularity.parent as? ViewGroup
        val malRank = full.ratings?.mal?.rank
        if (malRank != null && malRank > 0) {
            popRow?.visibility = View.VISIBLE
            info.mediaInfoPopularity.text = "#$malRank"
        } else {
            popRow?.visibility = View.GONE
        }
        val favRow = info.mediaInfoFavorites.parent as? ViewGroup
        val votes = full.ratings?.simkl?.votes
        if (votes != null && votes > 0) {
            favRow?.visibility = View.VISIBLE
            info.mediaInfoFavorites.text = votes.toString()
        } else {
            favRow?.visibility = View.GONE
        }

        val desc = SimklApi.cleanText(full.overview)
            ?: activity.getString(R.string.no_description_available)
        val markwon = buildMarkwon(activity, userInputContent = false)
        markwon.setMarkdown(info.mediaInfoDescription, desc.replace(Regex("\\n{3,}"), "\n\n").trim())
        info.mediaInfoDescription.movementMethod = LinkMovementMethod.getInstance()
        info.mediaInfoDescription.setOnClickListener {
            val target = if (info.mediaInfoDescription.maxLines == 5) 100 else 5
            ObjectAnimator.ofInt(info.mediaInfoDescription, "maxLines", target)
                .setDuration(if (target == 100) 950 else 400).start()
        }

        // Standalone page: reparent the stats table into the activity's scroll. Info tab: the
        // binding is the fragment view, so the container stays where it is — just append after it.
        if (contentHost !== container) {
            (container.parent as? ViewGroup)?.removeView(container)
            contentHost.addView(container)
        }

        addSynonyms(activity, contentHost, full, primary)
        addRatings(activity, contentHost, full.ratings)
        addGenres(activity, contentHost, full.genres, onGenreClick)
        addTags(activity, contentHost, anilistTags)
        addTrailers(activity, contentHost, full.trailers)
        addRelations(activity, contentHost, full.relations, onSimklMediaClick)
        if (anilistRecs != null) addAniListRecommendations(activity, contentHost, anilistRecs, recSource)
        else addRecommendations(activity, contentHost, full.recommendations, onSimklMediaClick)
    }

    private fun addAniListRecommendations(
        activity: AppCompatActivity, parent: ViewGroup, recs: List<Media>, source: Media?,
    ) {
        if (recs.isEmpty()) return
        val bind = ItemTitleRecyclerBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.recommended)
        bind.itemRecycler.adapter = MediaAdaptor(0, ArrayList(recs), activity, currentMedia = source)
        bind.itemRecycler.layoutManager =
            LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        bind.itemMore.visibility = View.VISIBLE
        bind.itemMore.setSafeOnClickListener {
            MediaListViewActivity.passedMedia = ArrayList(recs)
            source?.let { MediaListViewActivity.passedRecommendationSource = it }
            activity.startActivity(
                android.content.Intent(activity, MediaListViewActivity::class.java)
                    .putExtra("title", activity.getString(R.string.recommended))
            )
        }
        parent.addView(bind.root)
    }

    fun toEpisodeRows(episodes: List<SimklApi.SimklEpisode>): List<TrackerEpisodeRenderer.EpisodeRow> =
        episodes.mapIndexed { i, ep ->
            TrackerEpisodeRenderer.EpisodeRow(
                number = ep.episode?.toString() ?: (i + 1).toString(),
                title = SimklApi.cleanText(ep.title),
                desc = SimklApi.cleanText(ep.description),
                thumbUrl = SimklApi.episodeImageUrl(ep.img),
                date = ep.date,
                numberPrefix = ep.type?.takeIf { !it.equals("episode", true) }?.take(1)?.uppercase(),
            )
        }

    private fun addRelations(
        activity: AppCompatActivity,
        parent: ViewGroup,
        relations: List<SimklApi.SimklRelation>?,
        onSimklMediaClick: (Long) -> Unit,
    ) {
        val cards = relations.orEmpty().filter { it.simklId != null }.map {
            SimklMediaCardAdapter.Card(
                title = it.title ?: it.enTitle ?: "",
                posterPath = it.poster,
                label = it.relationType,
                simklId = it.simklId,
            )
        }
        if (cards.isEmpty()) return
        val bind = ItemTitleRecyclerBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.relations)
        bind.itemRecycler.adapter = SimklMediaCardAdapter(cards, onSimklMediaClick)
        bind.itemRecycler.layoutManager =
            LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        parent.addView(bind.root)
    }

    private fun addSynonyms(
        activity: AppCompatActivity,
        parent: ViewGroup,
        full: SimklApi.SimklAnimeFull,
        primary: String?,
    ) {
        val shown = LinkedHashSet<String>()
        full.enTitle?.trim()?.takeIf { it.isNotBlank() && !it.equals(primary, true) }?.let { shown.add(it) }
        full.altTitles?.forEach { t -> t.name?.trim()?.takeIf { it.isNotBlank() && !it.equals(primary, true) }?.let { shown.add(it) } }
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

    private fun addRatings(activity: AppCompatActivity, parent: ViewGroup, ratings: SimklApi.Ratings?) {
        ratings ?: return
        val rows = buildList {
            ratings.simkl?.rating?.takeIf { it > 0 }?.let { add("Simkl " + fmt(it, ratings.simkl?.votes)) }
            ratings.mal?.rating?.takeIf { it > 0 }?.let { add("MAL " + fmt(it, ratings.mal?.votes)) }
            ratings.imdb?.rating?.takeIf { it > 0 }?.let { add("IMDb " + fmt(it, ratings.imdb?.votes)) }
        }
        if (rows.isEmpty()) return
        val bind = ItemTitleChipgroupBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.mean_score)
        rows.forEach { label ->
            val chip = ItemChipBinding.inflate(activity.layoutInflater, bind.itemChipGroup, false).root
            chip.text = label
            bind.itemChipGroup.addView(chip)
        }
        parent.addView(bind.root)
    }

    private fun fmt(rating: Double, votes: Int?): String =
        String.format(Locale.US, "%.1f", rating) + (votes?.takeIf { it > 0 }?.let { " ($it)" } ?: "")

    /**
     * Simkl's API only carries a short genre list, so — when the record maps to an AniList id — the
     * fuller AniList tag list (the same one the Simkl website shows) is surfaced here. Media
     * spoilers stay blurred until tapped.
     */
    private fun addTags(activity: AppCompatActivity, parent: ViewGroup, tags: List<MediaTag>?) {
        val list = tags?.filter { it.name.isNotBlank() }
            ?.sortedByDescending { it.rank ?: 0 } ?: return
        if (list.isEmpty()) return

        val bind = ItemTitleChipgroupBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.tags)
        list.forEach { tag ->
            val chip = ItemChipBinding.inflate(activity.layoutInflater, bind.itemChipGroup, false).root
            val label = tag.rank?.let { "${tag.name} $it%" } ?: tag.name
            if (tag.isMediaSpoiler == true) {
                val revealed = booleanArrayOf(false)
                chip.text = "▓".repeat(tag.name.length.coerceIn(3, 12))
                chip.setOnClickListener {
                    if (!revealed[0]) { revealed[0] = true; chip.text = label }
                }
            } else {
                chip.text = label
            }
            chip.setOnLongClickListener { copyToClipboard(tag.name); true }
            bind.itemChipGroup.addView(chip)
        }
        parent.addView(bind.root)
    }

    private fun addGenres(
        activity: AppCompatActivity,
        parent: ViewGroup,
        genres: List<String>?,
        onGenreClick: (String) -> Unit,
    ) {
        val list = genres?.filter { it.isNotBlank() } ?: return
        if (list.isEmpty()) return
        val bind = ItemTitleChipgroupBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.genres)
        list.forEach { genre ->
            val chip = ItemChipBinding.inflate(activity.layoutInflater, bind.itemChipGroup, false).root
            chip.text = genre
            chip.setOnClickListener { onGenreClick(genre) }
            chip.setOnLongClickListener { copyToClipboard(genre); true }
            bind.itemChipGroup.addView(chip)
        }
        parent.addView(bind.root)
    }

    private fun addTrailers(
        activity: AppCompatActivity,
        parent: ViewGroup,
        trailers: List<SimklApi.Trailer>?,
    ) {
        val list = trailers?.filter { !it.youtube.isNullOrBlank() } ?: return
        if (list.isEmpty()) return
        val bind = ItemTitleChipgroupBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.trailer)
        list.forEachIndexed { i, t ->
            val chip = ItemChipBinding.inflate(activity.layoutInflater, bind.itemChipGroup, false).root
            chip.text = t.name?.takeIf { it.isNotBlank() } ?: "${activity.getString(R.string.trailer)} ${i + 1}"
            chip.setOnClickListener { openLinkInBrowser("https://www.youtube.com/watch?v=${t.youtube}") }
            bind.itemChipGroup.addView(chip)
        }
        parent.addView(bind.root)
    }

    private fun addRecommendations(
        activity: AppCompatActivity,
        parent: ViewGroup,
        recs: List<SimklApi.SimklMedia>?,
        onSimklMediaClick: (Long) -> Unit,
    ) {
        val cards = recs.orEmpty().filter { it.simklId != null }.map {
            SimklMediaCardAdapter.Card(
                title = it.title ?: it.titleRomaji ?: "",
                posterPath = it.poster,
                label = null,
                simklId = it.simklId,
            )
        }
        if (cards.isEmpty()) return
        val bind = ItemTitleRecyclerBinding.inflate(activity.layoutInflater, parent, false)
        bind.itemTitle.setText(R.string.recommended)
        bind.itemRecycler.adapter = SimklMediaCardAdapter(cards, onSimklMediaClick)
        bind.itemRecycler.layoutManager =
            LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        parent.addView(bind.root)
    }

    private fun titleCase(text: String): String =
        text.split('_', '-', ' ').filter { it.isNotBlank() }
            .joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }
}
