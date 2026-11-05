package ani.dantotsu.media

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
import ani.dantotsu.openLinkInAppOrBrowser
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.util.LinkTouchListener
import java.util.Locale

/**
 * Populates a [FragmentMediaInfoBinding] from a fully-loaded Simkl anime, shared between the
 * standalone [SimklMediaActivity] and the future Simkl info tab. Mirrors [KitsuMediaRenderer].
 */
object SimklMediaRenderer {

    private const val tripleTab = "\t\t\t"

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    fun render(
        activity: AppCompatActivity,
        info: FragmentMediaInfoBinding,
        contentHost: LinearLayout,
        full: SimklApi.SimklAnimeFull,
        showMalScore: Boolean = true,
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

        // The other sites' scores Simkl carries sit under its own. On the tab, MAL's has a tab of
        // its own right beside it.
        if (showMalScore) full.ratings?.mal?.takeIf { (it.rating ?: 0.0) > 0 }?.let { rating ->
            info.mediaInfoScoreMalContainer.visibility = View.VISIBLE
            info.mediaInfoScoreMal.text = fmt(rating.rating!!, rating.votes)
        }
        full.ratings?.imdb?.takeIf { (it.rating ?: 0.0) > 0 }?.let { rating ->
            info.mediaInfoScoreImdbContainer.visibility = View.VISIBLE
            info.mediaInfoScoreImdb.text = fmt(rating.rating!!, rating.votes)
        }

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
            info.mediaInfoDuration.text = ani.dantotsu.getAppString(R.string.tv_minutes, full.runtime)
        } else {
            info.mediaInfoDurationContainer.visibility = View.GONE
        }

        info.mediaInfoFormatLabel.setText(R.string.format)
        info.mediaInfoFormat.text = full.animeType?.let { titleCase(it) } ?: activity.getString(R.string.unknown)

        val networkLine = listOfNotNull(
            full.network?.takeIf { it.isNotBlank() },
            full.country?.takeIf { it.isNotBlank() }?.uppercase(),
        ).joinToString(" · ")
        info.mediaInfoSourceContainer.visibility = View.GONE
        setInfoLine(info.mediaInfoNetworkContainer, info.mediaInfoNetwork, networkLine.takeIf { it.isNotBlank() })

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

        (info.mediaInfoPopularity.parent as? ViewGroup)?.visibility = View.GONE
        (info.mediaInfoFavorites.parent as? ViewGroup)?.visibility = View.GONE
        setInfoLine(
            info.mediaInfoRankContainer, info.mediaInfoRank,
            full.ratings?.mal?.rank?.takeIf { it > 0 }?.let { "#$it" },
        )
        setInfoLine(
            info.mediaInfoScoredByContainer, info.mediaInfoScoredBy,
            full.ratings?.simkl?.votes?.takeIf { it > 0 }?.toString(),
        )

        val desc = SimklApi.cleanText(full.overview)
            ?: activity.getString(R.string.no_description_available)
        // Not user content, so links open — in-app where the app has a screen for them — rather
        // than being copied the way AniList comments are.
        val markwon = buildMarkwon(
            activity, userInputContent = false, linkResolver = { openLinkInAppOrBrowser(it) },
        )
        markwon.setMarkdown(info.mediaInfoDescription, desc.replace(Regex("\\n{3,}"), "\n\n").trim())
        info.mediaInfoDescription.movementMethod = LinkMovementMethod.getInstance()
        // Links take the tap over the expand click below; a long press opens one in the browser.
        info.mediaInfoDescription.setOnTouchListener(LinkTouchListener())
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

        contentHost.infoSection(InfoSectionKind.SYNONYMS) { addSynonyms(activity, contentHost, full, primary) }
        contentHost.infoSection(InfoSectionKind.GENRES) { addGenres(activity, contentHost, full.genres, onGenreClick) }
        contentHost.infoSection(InfoSectionKind.TAGS) { addTags(activity, contentHost, anilistTags) }
        contentHost.infoSection(InfoSectionKind.TRAILER) { addTrailers(activity, contentHost, full.trailers) }
        contentHost.infoSection(InfoSectionKind.RELATIONS) { addRelations(activity, contentHost, full.relations, onSimklMediaClick) }
        contentHost.infoSection(InfoSectionKind.RECOMMENDATIONS) {
            if (anilistRecs != null) {
                addAniListRecommendations(activity, contentHost, anilistRecs, recSource)
                contentHost.offerInfoMedia(InfoSectionKind.RECOMMENDATIONS, anilistRecs)
            } else addRecommendations(activity, contentHost, full.recommendations, onSimklMediaClick)
        }
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
                ani.dantotsu.toast(activity.getString(R.string.copied_title_toast, title))
                true
            }
            bind.itemChipGroup.addView(chip)
        }
        parent.addView(bind.root)
    }

    private fun fmt(rating: Double, votes: Int?): String =
        String.format(Locale.US, "%.1f", rating) + (votes?.takeIf { it > 0 }?.let { " ($it)" } ?: "")

    /**
     * Simkl's API only carries a short genre list, so — when the record maps to an AniList id — the
     * fuller AniList tag list (the same one the Simkl website shows) is surfaced here, rendered by
     * [AnilistTagChips] just as the AniList page renders its own: spoilers masked until asked for,
     * each tag's description a long press away. There's no AniList tag search to send a tap to
     * from here, so a revealed chip does nothing further.
     */
    private fun addTags(activity: AppCompatActivity, parent: ViewGroup, tags: List<MediaTag>?) {
        AnilistTagChips.render(activity, parent, tags.orEmpty().map { AnilistTagChips.of(it) })
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
