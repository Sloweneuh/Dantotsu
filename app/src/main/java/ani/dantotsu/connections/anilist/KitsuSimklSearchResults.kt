package ani.dantotsu.connections.anilist

import ani.dantotsu.R
import ani.dantotsu.connections.kitsu.KitsuApi
import ani.dantotsu.connections.simkl.SimklApi
import ani.dantotsu.currContext
import java.io.Serializable

/**
 * Kitsu search state. Carries the media kind (`isAnime`) on the results object — like
 * [ComickSearchResults.mediaType] — so paging, restores and saved presets stay on one catalogue.
 * Kitsu's `filter[categories]` is include-only (comma = AND), so there are no "excluded" lists.
 */
data class KitsuSearchResults(
    override var search: String?,
    override var page: Int = 1,
    override var results: MutableList<KitsuApi.Item>,
    override var hasNextPage: Boolean,
    var isAnime: Boolean = false,
    var categories: MutableList<String>? = null,
    var subtypes: MutableList<String>? = null,
    var statuses: MutableList<String>? = null,
    var ageRatings: MutableList<String>? = null,
    var season: String? = null,
    var fromYear: Int? = null,
    var toYear: Int? = null,
    var sort: String? = null,
) : SearchResults<KitsuApi.Item>, Serializable {

    fun toChipList(): List<AniMangaSearchResults.SearchChip> {
        val list = mutableListOf<AniMangaSearchResults.SearchChip>()
        val c = currContext()!!
        sort?.takeIf { it.isNotBlank() }?.let {
            list.add(AniMangaSearchResults.SearchChip("KITSU_SORT", c.getString(R.string.filter_sort, sortLabel(it))))
        }
        subtypes?.forEach { list.add(AniMangaSearchResults.SearchChip("KITSU_SUBTYPE", c.getString(R.string.format) + ": " + titleCase(it))) }
        statuses?.forEach { list.add(AniMangaSearchResults.SearchChip("KITSU_STATUS", c.getString(R.string.status_title) + ": " + titleCase(it))) }
        ageRatings?.forEach { list.add(AniMangaSearchResults.SearchChip("KITSU_AGE", c.getString(R.string.comick_content_rating) + ": " + ageLabel(it))) }
        season?.takeIf { it.isNotBlank() }?.let { list.add(AniMangaSearchResults.SearchChip("KITSU_SEASON", titleCase(it))) }
        if (fromYear != null || toYear != null) {
            list.add(AniMangaSearchResults.SearchChip("KITSU_YEAR_RANGE", c.getString(R.string.filter_year_range, "${fromYear ?: "?"} - ${toYear ?: "?"}")))
        }
        categories?.forEach { list.add(AniMangaSearchResults.SearchChip("KITSU_CAT", KitsuApi.resolveCategoryName(it))) }
        return list
    }

    fun removeChip(chip: AniMangaSearchResults.SearchChip) {
        val c = currContext()!!
        when (chip.type) {
            "KITSU_SORT" -> sort = null
            "KITSU_SEASON" -> season = null
            "KITSU_YEAR_RANGE" -> { fromYear = null; toYear = null }
            "KITSU_CAT" -> categories?.remove(categories?.firstOrNull { KitsuApi.resolveCategoryName(it).equals(chip.text, true) } ?: chip.text)
            "KITSU_SUBTYPE" -> {
                val label = chip.text.removePrefix(c.getString(R.string.format) + ": ")
                subtypes?.remove(subtypes?.firstOrNull { titleCase(it).equals(label, true) })
            }
            "KITSU_STATUS" -> {
                val label = chip.text.removePrefix(c.getString(R.string.status_title) + ": ")
                statuses?.remove(statuses?.firstOrNull { titleCase(it).equals(label, true) })
            }
            "KITSU_AGE" -> {
                val label = chip.text.removePrefix(c.getString(R.string.comick_content_rating) + ": ")
                ageRatings?.remove(ageRatings?.firstOrNull { ageLabel(it).equals(label, true) } ?: label)
            }
        }
    }

    private fun ageLabel(v: String): String = when (v.uppercase()) {
        "G" -> "G (all ages)"
        "PG" -> "PG (teens)"
        "R" -> "R (17+)"
        "R18" -> "R18 (explicit)"
        else -> v
    }

    private fun sortLabel(v: String): String = when (v) {
        "-userCount" -> "Popularity"
        "-averageRating" -> "Rating"
        "-startDate" -> "Newest"
        "titles.canonical" -> "Title"
        else -> v
    }

    private fun titleCase(text: String): String =
        text.split('_', '-').filter { it.isNotBlank() }
            .joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }
}

/** Simkl search state — query only; Simkl's `/search/anime` takes no filter parameters. */
data class SimklSearchResults(
    override var search: String?,
    override var page: Int = 1,
    override var results: MutableList<SimklApi.SimklMedia>,
    override var hasNextPage: Boolean,
) : SearchResults<SimklApi.SimklMedia>, Serializable
