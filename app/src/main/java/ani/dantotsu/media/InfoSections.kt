package ani.dantotsu.media

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import ani.dantotsu.R

/** How several sources' copies of one section combine when a custom tab merges them. */
enum class InfoSectionMerge {
    /** One after the other, each labelled with its source. */
    STACK,

    /** One chip row, duplicates dropped. */
    CHIPS,

    /** One media card row, duplicates dropped, what most sources agree on first. */
    MEDIA,

    /** One stats table; a field's value is repeated only where the sources disagree. */
    FIELDS,

    /** One scrolling row holding each source's own cards, for items only their source can open. */
    ROW,
}

/**
 * The vocabulary of info tab sections. Shared across sources on purpose: "recommendations" from
 * AniList and from MAL are the same kind, which is what makes them mergeable.
 */
enum class InfoSectionKind(
    val key: String,
    @StringRes val labelRes: Int,
    val merge: InfoSectionMerge = InfoSectionMerge.STACK,
) {
    COUNTDOWN("countdown", R.string.info_section_countdown),
    DETAILS("details", R.string.info_section_details, InfoSectionMerge.FIELDS),
    TITLES("titles", R.string.info_section_titles),
    DESCRIPTION("description", R.string.description),
    DETAILED_STATUS("detailed_status", R.string.info_section_detailed_status),
    BACKGROUND("background", R.string.info_section_background),
    BROADCAST("broadcast", R.string.comick_broadcast),
    EXTERNAL_LINKS("external_links", R.string.external_links, InfoSectionMerge.CHIPS),
    QUICKLINKS("quicklinks", R.string.quicklinks, InfoSectionMerge.CHIPS),
    SYNONYMS("synonyms", R.string.synonyms, InfoSectionMerge.CHIPS),
    SOCIAL("social", R.string.info_section_social),
    EPISODES("episodes", R.string.eps),
    LATEST_CHAPTER("latest_chapter", R.string.latest_chapter),
    COVERS("covers", R.string.covers),
    ANIME_ADAPTATION("anime_adaptation", R.string.anime_adaptation),
    STUDIOS("studios", R.string.studios, InfoSectionMerge.CHIPS),
    TRAILER("trailer", R.string.trailer),
    THEMES("themes", R.string.info_section_themes),
    GENRES("genres", R.string.genres, InfoSectionMerge.CHIPS),
    TAGS("tags", R.string.tags, InfoSectionMerge.CHIPS),
    AUTHORS("authors", R.string.authors),
    PUBLISHERS("publishers", R.string.info_section_publishers, InfoSectionMerge.CHIPS),
    WATCH_ORDER("watch_order", R.string.watch_order),
    NEWS("news", R.string.news),
    QUELS("quels", R.string.info_section_quels),
    RELATIONS("relations", R.string.relations),
    RECOMMENDATIONS("recommendations", R.string.recommended, InfoSectionMerge.MEDIA),
    CATEGORY_RECOMMENDATIONS(
        "category_recommendations", R.string.category_recommendations, InfoSectionMerge.MEDIA
    ),
    /** Curated collections of titles: MAL and MangaBaka call theirs stacks, Comick lists. */
    STACKS("stacks", R.string.info_section_stacks_lists, InfoSectionMerge.ROW),
    REVIEWS("reviews", R.string.reviews),
    CHARACTERS("characters", R.string.characters),
    STAFF("staff", R.string.staff),
    COMMENTS("comments", R.string.comick_comments);

    /** One line on what this section shows, for the editor. */
    @get:StringRes
    val descriptionRes: Int
        get() = when (this) {
            COUNTDOWN -> R.string.info_section_desc_countdown
            DETAILS -> R.string.info_section_desc_details
            TITLES -> R.string.info_section_desc_titles
            DESCRIPTION -> R.string.info_section_desc_description
            DETAILED_STATUS -> R.string.info_section_desc_detailed_status
            BACKGROUND -> R.string.info_section_desc_background
            PUBLISHERS -> R.string.info_section_desc_publishers
            BROADCAST -> R.string.info_section_desc_broadcast
            EXTERNAL_LINKS -> R.string.info_section_desc_external_links
            QUICKLINKS -> R.string.info_section_desc_quicklinks
            SYNONYMS -> R.string.info_section_desc_synonyms
            SOCIAL -> R.string.info_section_desc_social
            EPISODES -> R.string.info_section_desc_episodes
            LATEST_CHAPTER -> R.string.info_section_desc_latest_chapter
            COVERS -> R.string.info_section_desc_covers
            ANIME_ADAPTATION -> R.string.info_section_desc_anime_adaptation
            STUDIOS -> R.string.info_section_desc_studios
            TRAILER -> R.string.info_section_desc_trailer
            THEMES -> R.string.info_section_desc_themes
            GENRES -> R.string.info_section_desc_genres
            TAGS -> R.string.info_section_desc_tags
            AUTHORS -> R.string.info_section_desc_authors
            WATCH_ORDER -> R.string.info_section_desc_watch_order
            NEWS -> R.string.info_section_desc_news
            QUELS -> R.string.info_section_desc_quels
            RELATIONS -> R.string.info_section_desc_relations
            RECOMMENDATIONS -> R.string.info_section_desc_recommendations
            CATEGORY_RECOMMENDATIONS -> R.string.info_section_desc_category_recommendations
            STACKS -> R.string.info_section_desc_stacks
            REVIEWS -> R.string.info_section_desc_reviews
            CHARACTERS -> R.string.info_section_desc_characters
            STAFF -> R.string.info_section_desc_staff
            COMMENTS -> R.string.info_section_desc_comments
        }

    /**
     * The logo of who actually provides this section when it is drawn on [source]'s tab. Nearly
     * always that source, but a few are fetched from elsewhere whichever tab shows them. Null for
     * the providers that do nothing else in the app, which only get a mention in the description:
     * better no logo than the logo of the tab, which doesn't provide them.
     */
    fun providerIconRes(context: InfoTabContext, source: InfoTabType): Int? = when {
        this == QUICKLINKS -> R.drawable.ic_malsync
        this == THEMES || this == WATCH_ORDER -> null
        this == NEWS && context == InfoTabContext.ANILIST_ANIME -> null
        this == NEWS -> R.drawable.ic_mangabaka
        else -> source.iconRes
    }

    @StringRes
    fun providerLabelRes(context: InfoTabContext, source: InfoTabType): Int = when {
        this == QUICKLINKS -> R.string.malsync
        this == THEMES -> R.string.info_provider_animethemes
        this == WATCH_ORDER -> R.string.info_provider_chiaki
        this == NEWS && context == InfoTabContext.ANILIST_ANIME -> R.string.info_provider_kuroiru
        this == NEWS -> R.string.mangabaka
        else -> source.labelRes
    }

    /** A section named as [source] names it, since the editor should say what the tab says. */
    fun label(context: Context, source: InfoTabType?): String = context.getString(
        when {
            this == TAGS && source == InfoTabType.MANGAUPDATES -> R.string.categories
            this == STACKS && source == InfoTabType.COMICK -> R.string.comick_custom_lists
            this == STACKS && source != null -> R.string.interest_stacks
            else -> labelRes
        }
    )

    companion object {
        private val byKey = entries.associateBy { it.key }

        // Older saved layouts: Comick's lists had a kind of their own before they joined the stacks,
        // watch order and news were one section before they could be chosen apart, and links were
        // split by type. Simkl's "ratings" are details lines now, so that key is simply dropped.
        fun forKey(key: String): InfoSectionKind? = when (key) {
            "custom_lists" -> STACKS
            "shortcuts" -> NEWS
            "streaming", "official_sites", "resources" -> EXTERNAL_LINKS
            else -> byKey[key]
        }
    }
}

/** One row of the stats table. [key] matches the row's `android:tag` in fragment_media_info.xml. */
enum class InfoField(val key: String, @StringRes val labelRes: Int) {
    SCORE("score", R.string.mean_score),
    SCORE_MAL("score_mal", R.string.info_field_score_mal),
    SCORE_IMDB("score_imdb", R.string.info_field_score_imdb),
    STATUS("status", R.string.status_title),
    TRANSLATION("translation", R.string.translation),
    TOTAL("total", R.string.info_field_total),
    DURATION("duration", R.string.ep_duration),
    FORMAT("format", R.string.format),
    SOURCE("source", R.string.source),
    CONTENT_RATING("content_rating", R.string.content_rating),
    STUDIO("studio", R.string.main_studio),
    AUTHOR("author", R.string.author),
    SEASON("season", R.string.season),
    START("start", R.string.start_date),
    END("end", R.string.end_date),
    POPULARITY("popularity", R.string.popularity),
    FAVOURITES("favourites", R.string.favourites),
}

/** Every details line, in [saved] order; lines the app gained since then sit where they usually do. */
fun orderedInfoFields(saved: List<String>?): List<InfoField> {
    val out = saved.orEmpty().mapNotNull { key -> InfoField.entries.find { it.key == key } }.toMutableList()
    InfoField.entries.forEachIndexed { i, field ->
        if (field !in out) out.add(i.coerceAtMost(out.size), field)
    }
    return out
}

/**
 * The details lines [sources] can fill on this context's info tabs, so the editor doesn't offer ones
 * that could never show. Has to be kept in step with what each renderer sets. MAL's score isn't here:
 * only Simkl's standalone page shows it, a tab having MAL's own beside it.
 */
fun InfoTabContext.fieldsOf(sources: List<InfoTabType>): Set<InfoField> {
    val anime = this == InfoTabContext.ANILIST_ANIME
    val common = setOf(
        InfoField.SCORE, InfoField.STATUS, InfoField.TOTAL, InfoField.FORMAT, InfoField.START,
        InfoField.FAVOURITES,
    )
    val full = setOf(InfoField.END, InfoField.POPULARITY)
    return sources.flatMap { source ->
        common + when (source) {
            InfoTabType.ANILIST -> full + InfoField.SOURCE + InfoField.AUTHOR + (
                    if (anime) setOf(InfoField.DURATION, InfoField.STUDIO, InfoField.SEASON) else emptySet()
                    )

            InfoTabType.MAL -> full + (
                    if (anime) setOf(InfoField.DURATION, InfoField.STUDIO, InfoField.SEASON, InfoField.SOURCE)
                    else setOf(InfoField.AUTHOR)
                    )

            InfoTabType.COMICK -> full + setOf(
                InfoField.CONTENT_RATING, InfoField.DURATION, InfoField.SOURCE, InfoField.TRANSLATION,
            )

            InfoTabType.MANGAUPDATES -> setOf(InfoField.AUTHOR)
            InfoTabType.MANGABAKA -> full + setOf(
                InfoField.AUTHOR, InfoField.CONTENT_RATING, InfoField.DURATION, InfoField.SOURCE,
            )

            InfoTabType.KITSU -> full + setOf(
                InfoField.AUTHOR, InfoField.CONTENT_RATING, InfoField.DURATION, InfoField.SEASON,
            )

            InfoTabType.SIMKL -> full + setOf(
                InfoField.SCORE_IMDB, InfoField.CONTENT_RATING, InfoField.DURATION, InfoField.SEASON,
                InfoField.SOURCE, InfoField.STUDIO,
            )
        }
    }.toSet()
}

/**
 * The sections [source] draws on this context's media page, in the order it draws them when left
 * alone. Each list has to be kept in step with the fragment or renderer that tags them; a kind
 * listed here that the source never produces only leaves an inert row in the editor.
 */
fun InfoTabContext.sectionsOf(source: InfoTabType): List<InfoSectionKind> {
    val anime = this == InfoTabContext.ANILIST_ANIME
    val muPage = this == InfoTabContext.MANGAUPDATES_MANGA || this == InfoTabContext.MANGAUPDATES_NOVEL
    val head = listOf(InfoSectionKind.DETAILS, InfoSectionKind.TITLES, InfoSectionKind.DESCRIPTION)
    return when (source) {
        InfoTabType.ANILIST -> buildList {
            if (anime) add(InfoSectionKind.COUNTDOWN)
            addAll(head)
            addAll(
                listOf(
                    InfoSectionKind.EXTERNAL_LINKS, InfoSectionKind.QUICKLINKS,
                    InfoSectionKind.SYNONYMS, InfoSectionKind.SOCIAL, InfoSectionKind.TRAILER,
                )
            )
            if (anime) add(InfoSectionKind.THEMES)
            addAll(
                listOfNotNull(
                    InfoSectionKind.GENRES, InfoSectionKind.TAGS,
                    InfoSectionKind.WATCH_ORDER.takeIf { anime }, InfoSectionKind.NEWS,
                    InfoSectionKind.QUELS, InfoSectionKind.RELATIONS, InfoSectionKind.REVIEWS,
                    InfoSectionKind.CHARACTERS, InfoSectionKind.STAFF,
                    InfoSectionKind.RECOMMENDATIONS,
                )
            )
        }

        InfoTabType.MAL -> head + listOfNotNull(
            InfoSectionKind.BACKGROUND, InfoSectionKind.SYNONYMS, InfoSectionKind.GENRES,
            InfoSectionKind.COVERS, InfoSectionKind.RELATIONS,
            InfoSectionKind.PUBLISHERS.takeIf { !anime },
            InfoSectionKind.RECOMMENDATIONS, InfoSectionKind.STACKS, InfoSectionKind.REVIEWS,
        )

        InfoTabType.COMICK -> head + InfoSectionKind.SYNONYMS + (
                if (anime) listOf(
                    InfoSectionKind.BROADCAST, InfoSectionKind.EPISODES, InfoSectionKind.STUDIOS,
                    InfoSectionKind.EXTERNAL_LINKS, InfoSectionKind.TRAILER,
                ) else listOf(
                    InfoSectionKind.LATEST_CHAPTER, InfoSectionKind.COVERS,
                    InfoSectionKind.ANIME_ADAPTATION,
                )
                ) + listOf(
            InfoSectionKind.GENRES, InfoSectionKind.TAGS, InfoSectionKind.RECOMMENDATIONS,
            InfoSectionKind.STACKS, InfoSectionKind.REVIEWS, InfoSectionKind.COMMENTS,
        )

        InfoTabType.MANGAUPDATES -> head + listOf(
            InfoSectionKind.DETAILED_STATUS, InfoSectionKind.ANIME_ADAPTATION,
            InfoSectionKind.SYNONYMS,
        ) + (if (muPage) listOf(InfoSectionKind.QUICKLINKS) else emptyList()) + listOf(
            InfoSectionKind.LATEST_CHAPTER, InfoSectionKind.GENRES, InfoSectionKind.TAGS,
            InfoSectionKind.PUBLISHERS,
        ) + (
                if (muPage) listOf(InfoSectionKind.AUTHORS, InfoSectionKind.NEWS)
                else emptyList()
                ) + listOf(
            InfoSectionKind.RELATIONS, InfoSectionKind.RECOMMENDATIONS,
            InfoSectionKind.CATEGORY_RECOMMENDATIONS,
        )

        InfoTabType.MANGABAKA -> head + listOf(
            InfoSectionKind.SYNONYMS, InfoSectionKind.EXTERNAL_LINKS, InfoSectionKind.COVERS,
            InfoSectionKind.ANIME_ADAPTATION, InfoSectionKind.GENRES, InfoSectionKind.TAGS,
            InfoSectionKind.PUBLISHERS, InfoSectionKind.RELATIONS,
            InfoSectionKind.RECOMMENDATIONS, InfoSectionKind.STACKS,
        )

        InfoTabType.KITSU -> head + InfoSectionKind.SYNONYMS + InfoSectionKind.GENRES + (
                if (anime) listOf(InfoSectionKind.EXTERNAL_LINKS) else emptyList()
                ) + listOf(InfoSectionKind.RELATIONS, InfoSectionKind.TRAILER) + (
                if (anime) listOf(InfoSectionKind.EPISODES) else emptyList()
                )

        InfoTabType.SIMKL -> head + listOf(
            InfoSectionKind.SYNONYMS, InfoSectionKind.GENRES,
            InfoSectionKind.TRAILER, InfoSectionKind.RELATIONS, InfoSectionKind.RECOMMENDATIONS,
            InfoSectionKind.EPISODES,
        )
    }
}

/** The kind a section view was tagged with. Keyed, because the renderers use the plain tag too. */
var View.infoSectionKind: InfoSectionKind?
    get() = getTag(R.id.info_section_kind) as? InfoSectionKind
    set(value) = setTag(R.id.info_section_kind, value)

/** Adds [view] as a [kind] section. On a host that is not an info tab this is a plain addView. */
fun ViewGroup.addInfoSection(kind: InfoSectionKind, view: View, index: Int = -1) {
    view.infoSectionKind = kind
    addView(view, index)
}

/**
 * Everything added directly to this view while [block] runs belongs to [kind]. For helpers that add
 * their own views, and which also serve screens with no notion of a section.
 */
inline fun ViewGroup.infoSection(kind: InfoSectionKind, block: () -> Unit) {
    val host = this as? InfoSectionContainer
    val previous = host?.currentKind
    host?.currentKind = kind
    try {
        block()
    } finally {
        host?.currentKind = previous
    }
}

/** False when the user hid this section, so work that only feeds it can be skipped. */
fun ViewGroup.wantsInfoSection(kind: InfoSectionKind): Boolean =
    (this as? InfoSectionContainer)?.wants(kind) ?: true

/**
 * Whether [second] directly follows [first] where these sections are going, so that they can share
 * a row. Where the host isn't arranged at all, they are drawn in their usual order, so they do.
 */
fun ViewGroup.infoSectionsAdjacent(first: InfoSectionKind, second: InfoSectionKind): Boolean =
    (this as? InfoSectionContainer)?.adjacent(first, second) ?: true

/** Hands over a section's media, for a custom tab that merges it with another source's. */
fun ViewGroup.offerInfoMedia(kind: InfoSectionKind, media: List<Media>) {
    (this as? InfoSectionContainer)?.offerMedia(kind, media)
}

/** Hands over chips for a section whose own view is not a chip row (AniList's genre cards). */
fun ViewGroup.offerInfoChips(kind: InfoSectionKind, chips: List<InfoChip>) {
    (this as? InfoSectionContainer)?.offerChips(kind, chips)
}
