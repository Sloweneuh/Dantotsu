package ani.dantotsu.connections.anilist

import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.comick.ComickComic
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.currContext
import ani.dantotsu.media.Author
import ani.dantotsu.media.Character
import ani.dantotsu.media.Media
import ani.dantotsu.media.Studio
import ani.dantotsu.profile.User
import java.io.Serializable
import java.util.Calendar

interface SearchResults<T> {
    var search: String?
    var page: Int
    var results: MutableList<T>
    var hasNextPage: Boolean
}

data class AniMangaSearchResults(
    val type: String,
    var isAdult: Boolean,
    var onList: Boolean? = null,
    var perPage: Int? = null,
    var countryOfOrigin: String? = null,
    var sort: String? = null,
    var genres: MutableList<String>? = null,
    var excludedGenres: MutableList<String>? = null,
    var tags: MutableList<String>? = null,
    var excludedTags: MutableList<String>? = null,
    var status: String? = null,
    var source: String? = null,
    var format: String? = null,
    var seasonYear: Int? = null,
    var startYear: Int? = null,
    var yearRangeStart: Int? = null,
    var yearRangeEnd: Int? = null,
    var season: String? = null,
    override var search: String? = null,
    override var page: Int = 1,
    override var results: MutableList<Media>,
    override var hasNextPage: Boolean,
) : SearchResults<Media>, Serializable {
    fun toChipList(): List<SearchChip> {
        val list = mutableListOf<SearchChip>()
        sort?.let {
            val c = currContext()!!
            list.add(
                SearchChip(
                    "SORT",
                    c.getString(
                        R.string.filter_sort,
                        c.resources.getStringArray(R.array.sort_by)[Anilist.sortBy.indexOf(it)]
                    )
                )
            )
        }
        status?.let {
            list.add(SearchChip("STATUS", currContext()!!.getString(R.string.filter_status, it)))
        }
        source?.let {
            list.add(SearchChip("SOURCE", currContext()!!.getString(R.string.filter_source, it)))
        }
        format?.let {
            list.add(SearchChip("FORMAT", currContext()!!.getString(R.string.filter_format, it)))
        }
        countryOfOrigin?.let {
            list.add(SearchChip("COUNTRY", currContext()!!.getString(R.string.filter_country, it)))
        }
        season?.let {
            list.add(SearchChip("SEASON", it))
        }
        if (yearRangeStart != null && yearRangeEnd != null) {
            val c = currContext()!!
            list.add(SearchChip("YEAR_RANGE", c.getString(R.string.filter_year_range, "${yearRangeStart}-${yearRangeEnd}")))
        } else {
            // Backward compatibility for older saved search states that only have a single year.
            val singleYear = startYear ?: seasonYear
            if (singleYear != null) {
                val c = currContext()!!
                list.add(SearchChip("YEAR_RANGE", c.getString(R.string.filter_year_range, "$singleYear-$singleYear")))
            }
        }
        genres?.forEach {
            list.add(SearchChip("GENRE", it))
        }
        excludedGenres?.forEach {
            list.add(
                SearchChip(
                    "EXCLUDED_GENRE",
                    currContext()!!.getString(R.string.filter_exclude, it)
                )
            )
        }
        tags?.forEach {
            list.add(SearchChip("TAG", it))
        }
        excludedTags?.forEach {
            list.add(
                SearchChip(
                    "EXCLUDED_TAG",
                    currContext()!!.getString(R.string.filter_exclude, it)
                )
            )
        }
        return list
    }

    fun removeChip(chip: SearchChip) {
        when (chip.type) {
            "SORT" -> sort = null
            "STATUS" -> status = null
            "SOURCE" -> source = null
            "FORMAT" -> format = null
            "COUNTRY" -> countryOfOrigin = null
            "SEASON" -> season = null
            "YEAR_RANGE" -> {
                startYear = null
                seasonYear = null
                yearRangeStart = null
                yearRangeEnd = null
            }
            "GENRE" -> genres?.remove(chip.text)
            "EXCLUDED_GENRE" -> {
                val value = excludedGenres?.firstOrNull {
                    currContext()?.getString(R.string.filter_exclude, it) == chip.text
                } ?: chip.text
                excludedGenres?.remove(value)
            }
            "TAG" -> tags?.remove(chip.text)
            "EXCLUDED_TAG" -> {
                val value = excludedTags?.firstOrNull {
                    currContext()?.getString(R.string.filter_exclude, it) == chip.text
                } ?: chip.text
                excludedTags?.remove(value)
            }
        }
    }

    data class SearchChip(
        val type: String,
        val text: String
    )
}

data class CharacterSearchResults(
    override var search: String?,
    override var page: Int = 1,
    override var results: MutableList<Character>,
    override var hasNextPage: Boolean,
) : SearchResults<Character>, Serializable

data class StudioSearchResults(
    override var search: String?,
    override var page: Int = 1,
    override var results: MutableList<Studio>,
    override var hasNextPage: Boolean,
) : SearchResults<Studio>, Serializable


data class StaffSearchResults(
    override var search: String?,
    override var page: Int = 1,
    override var results: MutableList<Author>,
    override var hasNextPage: Boolean,
) : SearchResults<Author>, Serializable

data class UserSearchResults(
    override var search: String?,
    override var page: Int = 1,
    override var results: MutableList<User>,
    override var hasNextPage: Boolean,
) : SearchResults<User>, Serializable

data class MUSearchResults(
    override var search: String?,
    override var page: Int = 1,
    override var results: MutableList<MUMedia>,
    override var hasNextPage: Boolean,
    var format: String? = null,
    var year: Int? = null,
    var genres: MutableList<String>? = null,
    var excludedGenres: MutableList<String>? = null,
    var categories: MutableList<String>? = null,
    var excludedCategories: MutableList<String>? = null,
    var licensed: String? = null,
    var statusFilters: MutableList<String>? = null,
    var orderBy: String? = null,
) : SearchResults<MUMedia>, Serializable {
    fun toChipList(): List<AniMangaSearchResults.SearchChip> {
        val list = mutableListOf<AniMangaSearchResults.SearchChip>()
        format?.let { list.add(AniMangaSearchResults.SearchChip("MU_FORMAT", it)) }
        year?.let { list.add(AniMangaSearchResults.SearchChip("MU_YEAR", it.toString())) }
        licensed?.let {
            val label = if (it == "yes") "Licensed" else "Not Licensed"
            list.add(AniMangaSearchResults.SearchChip("MU_LICENSED", label))
        }
        orderBy?.let {
            list.add(AniMangaSearchResults.SearchChip("MU_ORDER", STATUS_FILTER_LABELS[it] ?: it))
        }
        genres?.forEach { list.add(AniMangaSearchResults.SearchChip("MU_GENRE", it)) }
        excludedGenres?.forEach {
            list.add(
                AniMangaSearchResults.SearchChip(
                    "MU_EXCL_GENRE",
                    currContext()!!.getString(R.string.filter_exclude, it)
                )
            )
        }
        categories?.forEach { list.add(AniMangaSearchResults.SearchChip("MU_CAT", it)) }
        statusFilters?.forEach {
            list.add(AniMangaSearchResults.SearchChip("MU_FILTER_$it", STATUS_FILTER_LABELS[it] ?: it))
        }
        return list
    }

    fun removeChip(chip: AniMangaSearchResults.SearchChip) {
        when {
            chip.type == "MU_FORMAT" -> format = null
            chip.type == "MU_YEAR" -> year = null
            chip.type == "MU_LICENSED" -> licensed = null
            chip.type == "MU_ORDER" -> orderBy = null
            chip.type == "MU_GENRE" -> genres?.remove(chip.text)
            chip.type == "MU_EXCL_GENRE" -> {
                val value = excludedGenres?.firstOrNull {
                    currContext()?.getString(R.string.filter_exclude, it) == chip.text
                } ?: chip.text
                excludedGenres?.remove(value)
            }
            chip.type == "MU_CAT" -> categories?.remove(chip.text)
            chip.type.startsWith("MU_FILTER_") -> statusFilters?.remove(chip.type.removePrefix("MU_FILTER_"))
        }
    }

    companion object {
        val STATUS_FILTER_LABELS = mapOf(
            "scanlated" to "Scanlated",
            "completed" to "Completed",
            "oneshots" to "Oneshots",
            "no_oneshots" to "No Oneshots",
            "some_releases" to "Has Releases",
            "no_releases" to "No Releases",
            "score" to "Score",
            "title" to "Title",
            "year" to "Year",
            "rating" to "Rating",
            "date_added" to "Date Added",
            "week_pos" to "Weekly Rank",
            "month1_pos" to "Monthly Rank",
            "month3_pos" to "3-Month Rank",
            "month6_pos" to "6-Month Rank",
            "year_pos" to "Yearly Rank",
            "list_reading" to "Reading Count",
            "list_wish" to "Wish Count",
            "list_complete" to "Complete Count",
        )
    }
}

data class ComickSearchResults(
    override var search: String?,
    override var page: Int = 1,
    override var results: MutableList<ComickComic>,
    override var hasNextPage: Boolean,
    var genres: MutableList<String>? = null,
    var excludedGenres: MutableList<String>? = null,
    var tags: MutableList<String>? = null,
    var excludedTags: MutableList<String>? = null,
    var demographic: MutableList<Int>? = null,
    var country: MutableList<String>? = null,
    var contentRating: MutableList<String>? = null,
    var status: Int? = null,
    var sort: String? = null,
    var time: Int? = null,
    var minimum: Int? = null,
    var minimumRating: Double? = null,
    var fromYear: Int? = null,
    var toYear: Int? = null,
    var completed: Boolean? = null,
    var excludeMyList: Boolean? = null,
    var showAll: Boolean? = null,
    var categories: MutableList<String>? = null,
    var excludedCategories: MutableList<String>? = null,
) : SearchResults<ComickComic>, Serializable {
    fun toChipList(): List<AniMangaSearchResults.SearchChip> {
        val list = mutableListOf<AniMangaSearchResults.SearchChip>()
        val context = currContext()!!
        genres?.forEach { list.add(AniMangaSearchResults.SearchChip("COMICK_GENRE", ComickApi.resolveGenreName(it) ?: it)) }
        excludedGenres?.forEach {
            val displayName = ComickApi.resolveGenreName(it) ?: it
            list.add(AniMangaSearchResults.SearchChip("COMICK_EXCL_GENRE", context.getString(R.string.filter_exclude, displayName)))
        }
        tags?.forEach { list.add(AniMangaSearchResults.SearchChip("COMICK_TAG", it)) }
        excludedTags?.forEach { list.add(AniMangaSearchResults.SearchChip("COMICK_EXCL_TAG", context.getString(R.string.filter_exclude, it))) }
        categories?.forEach { list.add(AniMangaSearchResults.SearchChip("COMICK_CATEGORY", ComickApi.resolveCategoryName(it) ?: it)) }
        excludedCategories?.forEach {
            val displayName = ComickApi.resolveCategoryName(it) ?: it
            list.add(AniMangaSearchResults.SearchChip("COMICK_EXCL_CATEGORY", context.getString(R.string.filter_exclude, displayName)))
        }
        demographic?.forEach {
            list.add(
                AniMangaSearchResults.SearchChip(
                    "COMICK_DEMO",
                    context.getString(R.string.demographic) + ": " + when (it) {
                        1 -> currContext()!!.getString(R.string.shounen)
                        2 -> currContext()!!.getString(R.string.shoujo)
                        3 -> currContext()!!.getString(R.string.seinen)
                        4 -> currContext()!!.getString(R.string.josei)
                        else -> currContext()!!.getString(R.string.unknown)
                    }
                )
            )
        }
        country?.forEach { list.add(AniMangaSearchResults.SearchChip("COMICK_COUNTRY", context.getString(R.string.comick_type_label) + ": " + (ComickApi.resolveCountryName(it) ?: it))) }
        contentRating?.forEach { list.add(AniMangaSearchResults.SearchChip("COMICK_CONTENT", context.getString(R.string.comick_content_rating) + ": $it")) }
        status?.let {
            list.add(
                AniMangaSearchResults.SearchChip(
                    "COMICK_STATUS",
                    context.getString(R.string.status_title) + ": " + when (it) {
                        1 -> currContext()!!.getString(R.string.ongoing)
                        2 -> currContext()!!.getString(R.string.completed)
                        3 -> currContext()!!.getString(R.string.cancelled)
                        4 -> currContext()!!.getString(R.string.hiatus)
                        else -> it.toString()
                    }
                )
            )
        }
        time?.let { list.add(AniMangaSearchResults.SearchChip("COMICK_TIME", context.getString(R.string.comick_time_days_label) + ": " + context.getString(R.string.comick_time_days, it))) }
        minimum?.let { list.add(AniMangaSearchResults.SearchChip("COMICK_MINIMUM", context.getString(R.string.comick_minimum_chapters) + ": $it")) }
        minimumRating?.let { list.add(AniMangaSearchResults.SearchChip("COMICK_MIN_RATING", context.getString(R.string.comick_minimum_rating) + ": $it")) }
        val defaultYearRangeEnd = Calendar.getInstance().get(Calendar.YEAR) + 1
        val isDefaultYearRange = fromYear == null && toYear == null || (fromYear == 1900 && toYear == defaultYearRangeEnd)
        if (!isDefaultYearRange && (fromYear != null || toYear != null)) {
            list.add(AniMangaSearchResults.SearchChip("COMICK_YEAR_RANGE", context.getString(R.string.filter_year_range, context.getString(R.string.comick_year_range, fromYear ?: "?", toYear ?: "?"))))
        }
        completed?.let { list.add(AniMangaSearchResults.SearchChip("COMICK_COMPLETED", context.getString(R.string.translation) + ": " + if (it) context.getString(R.string.comick_completed_only) else context.getString(R.string.comick_not_completed_only))) }
        if (showAll == true) {
            list.add(AniMangaSearchResults.SearchChip("COMICK_SHOWALL", context.getString(R.string.comick_show_all)))
        }
        return list
    }

    fun removeChip(chip: AniMangaSearchResults.SearchChip) {
        when (chip.type) {
            "COMICK_GENRE" -> genres?.remove(findSlugByDisplayText(chip.text, genres, ComickApi::resolveGenreName))
            "COMICK_EXCL_GENRE" -> {
                val displayValue = chip.text.rawNotPrefixedValue()
                excludedGenres?.remove(findSlugByDisplayText(displayValue, excludedGenres, ComickApi::resolveGenreName))
            }
            "COMICK_TAG" -> tags?.remove(chip.text.rawPrefixedValue(currContext()!!.getString(R.string.tags)))
            "COMICK_EXCL_TAG" -> excludedTags?.remove(chip.text.rawNotPrefixedValue())
            "COMICK_DEMO" -> {
                val selectedLabel = chip.text.rawPrefixedValue(currContext()!!.getString(R.string.demographic))
                val selectedValue = when (selectedLabel) {
                    currContext()!!.getString(R.string.shounen) -> 1
                    currContext()!!.getString(R.string.shoujo) -> 2
                    currContext()!!.getString(R.string.seinen) -> 3
                    currContext()!!.getString(R.string.josei) -> 4
                    else -> null
                }
                selectedValue?.let { demographic?.remove(it) }
            }
            "COMICK_COUNTRY" -> {
                val selectedLabel = chip.text.rawPrefixedValue(currContext()!!.getString(R.string.comick_type_label))
                country?.remove(findSlugByDisplayText(selectedLabel, country, ComickApi::resolveCountryName))
            }
            "COMICK_CONTENT" -> {
                val selectedValue = chip.text.rawPrefixedValue(currContext()!!.getString(R.string.comick_content_rating))
                contentRating?.remove(selectedValue)
            }
            "COMICK_STATUS" -> status = null
            "COMICK_SORT" -> sort = null
            "COMICK_TIME" -> time = null
            "COMICK_MINIMUM" -> minimum = null
            "COMICK_MIN_RATING" -> minimumRating = null
            "COMICK_YEAR_RANGE" -> {
                fromYear = null
                toYear = null
            }
            "COMICK_COMPLETED" -> completed = null
            "COMICK_SHOWALL" -> showAll = null
            "COMICK_CATEGORY" -> categories?.remove(findSlugByDisplayText(chip.text, categories, ComickApi::resolveCategoryName))
            "COMICK_EXCL_CATEGORY" -> {
                val displayValue = chip.text.rawNotPrefixedValue()
                excludedCategories?.remove(findSlugByDisplayText(displayValue, excludedCategories, ComickApi::resolveCategoryName))
            }
        }
    }

    private fun String.rawPrefixedValue(label: String): String {
        return removePrefix("$label: ")
    }

    private fun String.rawNotPrefixedValue(): String {
        return removePrefix(currContext()!!.getString(R.string.filter_exclude, ""))
    }

    private fun findSlugByDisplayText(
        displayText: String,
        values: MutableList<String>?,
        resolver: (String) -> String?,
    ): String {
        if (displayText.isBlank()) {
            return displayText
        }

        return values?.firstOrNull { slug ->
            resolver(slug)?.equals(displayText, ignoreCase = true) == true
        } ?: displayText
    }

    private fun labelForSort(sortValue: String): String {
        return when (sortValue) {
            "created_at" -> "Latest"
            "uploaded" -> "Last Updated"
            "rating" -> "Rating"
            "average_rating" -> "Average Rating"
            "user_follow_count" -> "Popular"
            "created" -> "Created"
            else -> sortValue.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}