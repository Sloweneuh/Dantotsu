package ani.dantotsu.media

import android.content.Context
import ani.dantotsu.R
import ani.dantotsu.media.savedfilters.SavedStackFilter

/**
 * Filtering and sorting a stack's entries on the device, from what resolving them already
 * fetched (see [StackResolver]). Entries come from AniList, MangaUpdates or MangaBaka alone, so
 * some don't know everything: a value an entry doesn't have never hides it from a range or a
 * minimum, but can't satisfy a "must have" either. Exclusions only ever hide what is known.
 */
object StackFilters {

    const val SORT_SCORE = "score"
    const val SORT_POPULARITY = "popularity"
    const val SORT_YEAR = "year"
    const val SORT_LENGTH = "length"
    const val SORT_TITLE = "title"
    val SORTS = listOf(null, SORT_SCORE, SORT_POPULARITY, SORT_YEAR, SORT_LENGTH, SORT_TITLE)

    val LIST_STATUSES = listOf("CURRENT", "PLANNING", "COMPLETED", "PAUSED", "DROPPED", "REPEATING")
    val STATUSES = listOf("RELEASING", "FINISHED", "HIATUS", "CANCELLED", "NOT_YET_RELEASED")
    val FORMATS = listOf(
        "MANGA", "MANHWA", "MANHUA", "NOVEL", "ONE_SHOT",
        "TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA", "MUSIC",
    )
    val LENGTHS = listOf("SHORT", "MEDIUM", "LONG")

    /**
     * What kind of work [media] is. Comics are told apart by country, as AniList files manhwa
     * and manhua under MANGA; MangaUpdates' fallbacks carry its own type words instead.
     */
    fun formatOf(media: Media): String? {
        val format = media.format?.uppercase()?.replace(' ', '_')?.replace('-', '_')
        if (media.anime != null) return format?.takeIf { it in FORMATS }
        return when {
            format == null -> null
            "NOVEL" in format -> "NOVEL"
            format == "ONE_SHOT" || format == "ONESHOT" -> "ONE_SHOT"
            format == "MANHWA" -> "MANHWA"
            format == "MANHUA" -> "MANHUA"
            media.countryOfOrigin == "KR" -> "MANHWA"
            media.countryOfOrigin == "CN" || media.countryOfOrigin == "TW" -> "MANHUA"
            else -> "MANGA"
        }
    }

    /** Chapters for a comic or novel (the newest known one where there's no total), episodes for anime. */
    private fun sizeOf(media: Media): Int? =
        if (media.anime != null) media.anime?.totalEpisodes
        else media.manga?.totalChapters ?: media.muLatestChapter

    /** Under 50 chapters or 13 episodes is short, over 150 chapters or 26 episodes long. */
    fun lengthOf(media: Media): String? {
        val size = sizeOf(media)?.takeIf { it > 0 } ?: return null
        val (short, long) = if (media.anime != null) 13 to 26 else 50 to 150
        return when {
            size < short -> "SHORT"
            size > long -> "LONG"
            else -> "MEDIUM"
        }
    }

    private fun yearOf(media: Media): Int? = media.startDate?.year

    /** The tags that can be offered without spoiling anything; all of them when that isn't known. */
    fun safeTags(media: Media): List<String> =
        media.tagsInfo?.filter { it.isMediaSpoiler != true && it.isGeneralSpoiler != true }?.map { it.name }
            ?: media.tags

    /** The tags AniList marks as only for 18+ media, which the sheet keeps behind its 18+ switch. */
    fun adultTags(media: Media): List<String> =
        media.tagsInfo?.filter { it.isAdult == true }?.map { it.name }.orEmpty()

    fun apply(entries: List<Media>, f: SavedStackFilter): List<Media> {
        val filtered = entries.filter { m ->
            val format = formatOf(m)
            val year = yearOf(m)
            val length = lengthOf(m)
            (m.userStatus == null || m.userStatus !in f.hideListStatuses) &&
                (!f.hideAdult || !m.isAdult) &&
                (f.formats.isEmpty() || format in f.formats) &&
                (format == null || format !in f.excludedFormats) &&
                (f.statuses.isEmpty() || m.status in f.statuses) &&
                (f.minScore == null || m.meanScore == null || m.meanScore >= f.minScore) &&
                (f.fromYear == null || year == null || year >= f.fromYear) &&
                (f.toYear == null || year == null || year <= f.toYear) &&
                (f.lengths.isEmpty() || length == null || length in f.lengths) &&
                f.genres.all { it in m.genres } &&
                f.excludedGenres.none { it in m.genres } &&
                f.tags.all { it in m.tags } &&
                f.excludedTags.none { it in m.tags }
        }
        // Ties, and entries missing what's sorted on, keep the stack's own order behind the rest.
        return when (f.sort) {
            SORT_SCORE -> filtered.sortedByDescendingKnown { it.meanScore }
            SORT_POPULARITY -> filtered.sortedByDescendingKnown { it.popularity }
            SORT_YEAR -> filtered.sortedByDescendingKnown { yearOf(it) }
            SORT_LENGTH -> filtered.sortedByDescendingKnown { sizeOf(it) }
            SORT_TITLE -> filtered.sortedBy { it.userPreferredName.lowercase() }
            else -> filtered
        }
    }

    private inline fun List<Media>.sortedByDescendingKnown(crossinline key: (Media) -> Int?): List<Media> {
        val (known, unknown) = partition { key(it) != null }
        return known.sortedByDescending { key(it) } + unknown
    }

    /** Whether [entries] mix kinds of work (manga and novels, say), so each card should say which it is. */
    fun isMixed(entries: List<Media>): Boolean = entries.mapNotNull { formatOf(it) }.distinct().size > 1

    /**
     * The kind line under a card, label and icon: every card's in a [mixed] stack, and otherwise
     * only a novel's where the list marks novels ([novels]) — a list of manga needs no line saying so.
     */
    fun typeLine(context: Context, media: Media, mixed: Boolean, novels: Boolean): Pair<String, Int>? {
        val format = formatOf(media) ?: return null
        if (!mixed && !(novels && format == "NOVEL")) return null
        return formatLabel(context, format) to InfoExtraSections.typeIcon(media.anime != null, format)
    }

    // ---- labels ----

    fun sortLabel(context: Context, sort: String?): String = context.getString(
        when (sort) {
            SORT_SCORE -> R.string.stack_sort_score
            SORT_POPULARITY -> R.string.stack_sort_popularity
            SORT_YEAR -> R.string.stack_sort_year
            SORT_LENGTH -> R.string.stack_sort_length
            SORT_TITLE -> R.string.stack_sort_title
            else -> R.string.stack_sort_stack
        }
    )

    fun sortIcon(sort: String?): Int = when (sort) {
        SORT_SCORE -> R.drawable.ic_round_star_24
        SORT_POPULARITY -> R.drawable.ic_round_trending_up_24
        SORT_YEAR -> R.drawable.ic_round_new_releases_24
        SORT_LENGTH -> R.drawable.ic_round_straighten_outline_24
        SORT_TITLE -> R.drawable.ic_sort
        else -> R.drawable.ic_round_format_list_numbered_24
    }

    fun formatLabel(context: Context, format: String): String = when (format) {
        "MANGA" -> context.getString(R.string.manga)
        "MANHWA" -> context.getString(R.string.manhwa)
        "MANHUA" -> context.getString(R.string.manhua)
        "NOVEL" -> context.getString(R.string.novel)
        "ONE_SHOT" -> context.getString(R.string.stack_format_one_shot)
        "TV" -> context.getString(R.string.stack_format_tv)
        "TV_SHORT" -> context.getString(R.string.stack_format_tv_short)
        "MOVIE" -> context.getString(R.string.stack_format_movie)
        "SPECIAL" -> context.getString(R.string.stack_format_special)
        "OVA" -> "OVA"
        "ONA" -> "ONA"
        "MUSIC" -> context.getString(R.string.stack_format_music)
        else -> format
    }

    fun statusLabel(context: Context, status: String): String = context.getString(
        when (status) {
            "RELEASING" -> R.string.stack_status_releasing
            "FINISHED" -> R.string.stack_status_finished
            "HIATUS" -> R.string.stack_status_hiatus
            "CANCELLED" -> R.string.stack_status_cancelled
            else -> R.string.stack_status_not_yet_released
        }
    )

    fun listStatusLabel(context: Context, status: String): String = context.getString(
        when (status) {
            "CURRENT" -> R.string.stack_list_current
            "PLANNING" -> R.string.stack_list_planning
            "COMPLETED" -> R.string.stack_list_completed
            "PAUSED" -> R.string.stack_list_paused
            "DROPPED" -> R.string.stack_list_dropped
            else -> R.string.stack_list_repeating
        }
    )

    fun lengthLabel(context: Context, length: String): String = context.getString(
        when (length) {
            "SHORT" -> R.string.stack_length_short
            "LONG" -> R.string.stack_length_long
            else -> R.string.stack_length_medium
        }
    )

    /**
     * One chip per active filter, each with the filter as it would be without it — which is what
     * removing the chip applies. In the order the sheet lists them.
     */
    fun activeChips(context: Context, f: SavedStackFilter): List<Pair<String, SavedStackFilter>> {
        val out = mutableListOf<Pair<String, SavedStackFilter>>()
        fun not(label: String) = context.getString(R.string.filter_exclude, label)
        f.sort?.let { out += context.getString(R.string.stack_chip_sort, sortLabel(context, it)) to f.copy(sort = null) }
        f.hideListStatuses.forEach { s ->
            out += context.getString(R.string.stack_chip_hide, listStatusLabel(context, s)) to
                f.copy(hideListStatuses = f.hideListStatuses - s)
        }
        f.formats.forEach { out += formatLabel(context, it) to f.copy(formats = f.formats - it) }
        f.excludedFormats.forEach { out += not(formatLabel(context, it)) to f.copy(excludedFormats = f.excludedFormats - it) }
        f.statuses.forEach { out += statusLabel(context, it) to f.copy(statuses = f.statuses - it) }
        f.minScore?.let { out += context.getString(R.string.stack_chip_min_score, it) to f.copy(minScore = null) }
        if (f.hideAdult) out += context.getString(R.string.stack_filter_hide_adult) to f.copy(hideAdult = false)
        f.genres.forEach { out += it to f.copy(genres = f.genres - it) }
        f.excludedGenres.forEach { out += not(it) to f.copy(excludedGenres = f.excludedGenres - it) }
        f.tags.forEach { out += it to f.copy(tags = f.tags - it) }
        f.excludedTags.forEach { out += not(it) to f.copy(excludedTags = f.excludedTags - it) }
        FilterSummary.range(f.fromYear, f.toYear)?.let {
            out += context.getString(R.string.filter_count_range, context.getString(R.string.year), it) to
                f.copy(fromYear = null, toYear = null)
        }
        f.lengths.forEach { out += lengthLabel(context, it) to f.copy(lengths = f.lengths - it) }
        return out
    }
}
