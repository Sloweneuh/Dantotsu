package ani.dantotsu.media.savedfilters

import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AniMangaSearchResults
import ani.dantotsu.connections.anilist.ComickSearchResults
import ani.dantotsu.connections.anilist.MUSearchResults
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.currContext
import ani.dantotsu.media.user.ListFilters
import java.io.Serializable

private fun excludeLabel(name: String): String =
    currContext()?.getString(R.string.filter_exclude, name) ?: "−$name"

data class SavedAniMangaFilter(
    val name: String,
    val type: String,
    val isAdult: Boolean,
    val onList: Boolean? = null,
    val countryOfOrigin: String? = null,
    val sort: String? = null,
    val genres: List<String>? = null,
    val excludedGenres: List<String>? = null,
    val tags: List<String>? = null,
    val excludedTags: List<String>? = null,
    val status: String? = null,
    val source: String? = null,
    val format: String? = null,
    val season: String? = null,
    val yearRangeStart: Int? = null,
    val yearRangeEnd: Int? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        fun from(name: String, r: AniMangaSearchResults) = SavedAniMangaFilter(
            name = name,
            type = r.type,
            isAdult = r.isAdult,
            onList = r.onList,
            countryOfOrigin = r.countryOfOrigin,
            sort = r.sort,
            genres = r.genres?.toList(),
            excludedGenres = r.excludedGenres?.toList(),
            tags = r.tags?.toList(),
            excludedTags = r.excludedTags?.toList(),
            status = r.status,
            source = r.source,
            format = r.format,
            season = r.season,
            yearRangeStart = r.yearRangeStart,
            yearRangeEnd = r.yearRangeEnd,
        )
    }

    fun applyTo(r: AniMangaSearchResults) {
        r.isAdult = isAdult
        r.onList = onList
        r.countryOfOrigin = countryOfOrigin
        r.sort = sort
        r.genres = genres?.toMutableList()
        r.excludedGenres = excludedGenres?.toMutableList()
        r.tags = tags?.toMutableList()
        r.excludedTags = excludedTags?.toMutableList()
        r.status = status
        r.source = source
        r.format = format
        r.season = season
        r.seasonYear = null
        r.startYear = null
        r.yearRangeStart = yearRangeStart
        r.yearRangeEnd = yearRangeEnd
    }

    /** One chip per active filter. Order roughly matches the filter sheet's sections. */
    fun chips(): List<String> {
        val out = mutableListOf<String>()
        sort?.let { s ->
            val idx = Anilist.sortBy.indexOf(s)
            val label = currContext()?.resources?.getStringArray(R.array.sort_by)?.getOrNull(idx) ?: s.replace("_", " ").lowercase()
            out += "Sort : $label"
        }
        status?.let { out += "Status: ${it.replace("_", " ").lowercase()}" }
        format?.let { out += "Format: $it" }
        source?.let { out += "Source: ${it.replace("_", " ").lowercase()}" }
        season?.let { out += "Season: ${it.lowercase()}" }
        countryOfOrigin?.let { out += "Country: $it" }
        if (yearRangeStart != null && yearRangeEnd != null) {
            out += if (yearRangeStart == yearRangeEnd) "Year: $yearRangeStart"
            else "Year: $yearRangeStart-$yearRangeEnd"
        }
        genres?.forEach { out += it }
        excludedGenres?.forEach { out += excludeLabel(it) }
        tags?.forEach { out += it }
        excludedTags?.forEach { out += excludeLabel(it) }
        if (isAdult) out += "18+"
        when (onList) {
            true -> out += "On list"
            false -> out += "Not on list"
            else -> Unit
        }
        return out
    }
}

data class SavedMUFilter(
    val name: String,
    val format: String? = null,
    val year: Int? = null,
    val genres: List<String>? = null,
    val excludedGenres: List<String>? = null,
    val categories: List<String>? = null,
    val excludedCategories: List<String>? = null,
    val licensed: String? = null,
    val statusFilters: List<String>? = null,
    val orderBy: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        fun from(name: String, r: MUSearchResults) = SavedMUFilter(
            name = name,
            format = r.format,
            year = r.year,
            genres = r.genres?.toList(),
            excludedGenres = r.excludedGenres?.toList(),
            categories = r.categories?.toList(),
            excludedCategories = r.excludedCategories?.toList(),
            licensed = r.licensed,
            statusFilters = r.statusFilters?.toList(),
            orderBy = r.orderBy,
        )
    }

    fun applyTo(r: MUSearchResults) {
        r.format = format
        r.year = year
        r.genres = genres?.toMutableList()
        r.excludedGenres = excludedGenres?.toMutableList()
        r.categories = categories?.toMutableList()
        r.excludedCategories = excludedCategories?.toMutableList()
        r.licensed = licensed
        r.statusFilters = statusFilters?.toMutableList()
        r.orderBy = orderBy
    }

    fun chips(): List<String> {
        val out = mutableListOf<String>()
        format?.let { out += "Format: $it" }
        year?.let { out += "Year: $it" }
        licensed?.let { out += if (it == "yes") "Licensed" else "Not licensed" }
        orderBy?.let { out += "Sort: ${MUSearchResults.STATUS_FILTER_LABELS[it] ?: it}" }
        statusFilters?.forEach { out += MUSearchResults.STATUS_FILTER_LABELS[it] ?: it.replace("_", " ").replaceFirstChar { c -> c.uppercase() } }
        genres?.forEach { out += it }
        excludedGenres?.forEach { out += excludeLabel(it) }
        categories?.forEach { out += it }
        excludedCategories?.forEach { out += excludeLabel(it) }
        return out
    }
}

data class SavedComickFilter(
    val name: String,
    val genres: List<String>? = null,
    val excludedGenres: List<String>? = null,
    val tags: List<String>? = null,
    val excludedTags: List<String>? = null,
    val demographic: List<Int>? = null,
    val country: List<String>? = null,
    val contentRating: List<String>? = null,
    val status: Int? = null,
    val sort: String? = null,
    val time: Int? = null,
    val minimum: Int? = null,
    val minimumRating: Double? = null,
    val fromYear: Int? = null,
    val toYear: Int? = null,
    val completed: Boolean? = null,
    val showAll: Boolean? = null,
    val categories: List<String>? = null,
    val excludedCategories: List<String>? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        fun from(name: String, r: ComickSearchResults) = SavedComickFilter(
            name = name,
            genres = r.genres?.toList(),
            excludedGenres = r.excludedGenres?.toList(),
            tags = r.tags?.toList(),
            excludedTags = r.excludedTags?.toList(),
            demographic = r.demographic?.toList(),
            country = r.country?.toList(),
            contentRating = r.contentRating?.toList(),
            status = r.status,
            sort = r.sort,
            time = r.time,
            minimum = r.minimum,
            minimumRating = r.minimumRating,
            fromYear = r.fromYear,
            toYear = r.toYear,
            completed = r.completed,
            showAll = r.showAll,
            categories = r.categories?.toList(),
            excludedCategories = r.excludedCategories?.toList(),
        )
    }

    fun applyTo(r: ComickSearchResults) {
        r.genres = genres?.toMutableList()
        r.excludedGenres = excludedGenres?.toMutableList()
        r.tags = tags?.toMutableList()
        r.excludedTags = excludedTags?.toMutableList()
        r.demographic = demographic?.toMutableList()
        r.country = country?.toMutableList()
        r.contentRating = contentRating?.toMutableList()
        r.status = status
        r.sort = sort
        r.time = time
        r.minimum = minimum
        r.minimumRating = minimumRating
        r.fromYear = fromYear
        r.toYear = toYear
        r.completed = completed
        r.showAll = showAll
        r.categories = categories?.toMutableList()
        r.excludedCategories = excludedCategories?.toMutableList()
    }

    fun chips(): List<String> {
        val out = mutableListOf<String>()
        sort?.takeIf { it != "created_at" }?.let { out += "Sort : ${ComickApi.SEARCH_SORT_LABELS[it] ?: it.replace('_', ' ')}" }
        status?.let {
            out += "Status: " + when (it) {
                0 -> "ongoing"; 1 -> "completed"; 2 -> "hiatus"; 3 -> "cancelled"
                else -> it.toString()
            }
        }
        contentRating?.forEach { out += "Rating: $it" }
        country?.forEach { out += "Type: ${ComickApi.resolveCountryName(it) ?: it}" }
        demographic?.forEach {
            out += "Demo: " + when (it) {
                1 -> "shounen"; 2 -> "shoujo"; 3 -> "seinen"; 4 -> "josei"
                else -> it.toString()
            }
        }
        if (fromYear != null || toYear != null) {
            out += "Year: ${fromYear ?: "?"}-${toYear ?: "?"}"
        }
        time?.let { out += "Time: ${it}d" }
        minimum?.let { out += "Min chapters: $it" }
        minimumRating?.let { out += "Min rating: $it" }
        if (completed == true) out += "Completed only"
        if (completed == false) out += "Not completed"
        if (showAll == true) out += "Show all"
        genres?.forEach { out += ComickApi.resolveGenreName(it) ?: it }
        excludedGenres?.forEach { out += excludeLabel(ComickApi.resolveGenreName(it) ?: it) }
        tags?.forEach { out += it }
        excludedTags?.forEach { out += excludeLabel(it) }
        categories?.forEach { out += ComickApi.resolveCategoryName(it) ?: it }
        excludedCategories?.forEach { out += excludeLabel(ComickApi.resolveCategoryName(it) ?: it) }
        return out
    }
}

data class SavedComickListFilter(
    val name: String,
    val sort: String? = null,
    val status: List<Int>? = null,
    val country: List<String>? = null,
    val demographic: List<Int>? = null,
    val contentRating: List<String>? = null,
    val translationCompleted: Boolean? = null,
    val genres: List<String>? = null,
    val excludedGenres: List<String>? = null,
    val fromYear: Int? = null,
    val toYear: Int? = null,
    val minChapters: Int? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }

    fun chips(): List<String> {
        val out = mutableListOf<String>()
        sort?.takeIf { it != "created_at" }?.let { out += "Sort : ${ComickApi.LIST_SORT_LABELS[it] ?: it.replace('_', ' ')}" }
        status?.forEach {
            out += "Status: " + when (it) {
                1 -> "ongoing"; 2 -> "completed"; 3 -> "cancelled"; 4 -> "hiatus"
                else -> it.toString()
            }
        }
        country?.forEach { out += "Type: ${ComickApi.resolveCountryName(it) ?: it}" }
        demographic?.forEach {
            out += "Demo: " + when (it) {
                1 -> "shounen"; 2 -> "shoujo"; 3 -> "seinen"; 4 -> "josei"
                else -> it.toString()
            }
        }
        contentRating?.forEach { out += "Rating: $it" }
        when (translationCompleted) {
            true -> out += "Completed only"
            false -> out += "Not completed"
            null -> Unit
        }
        if (fromYear != null || toYear != null) {
            out += "Year: ${fromYear ?: "?"}-${toYear ?: "?"}"
        }
        minChapters?.let { out += "Min chapters: $it" }
        genres?.forEach { out += ComickApi.resolveGenreName(it) ?: it }
        excludedGenres?.forEach { out += excludeLabel(ComickApi.resolveGenreName(it) ?: it) }
        return out
    }
}

data class SavedListFilter(
    val name: String,
    val isAnime: Boolean,
    val genres: List<String> = emptyList(),
    val excludedGenres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val excludedTags: List<String> = emptyList(),
    val formats: List<String> = emptyList(),
    val statuses: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val season: String? = null,
    val countryOfOrigin: String? = null,
    val scoreRangeFrom: Float = 0f,
    val scoreRangeTo: Float = 10f,
    val yearRangeFrom: Int = 1970,
    val yearRangeTo: Int = 2028,
    val englishLicenced: Boolean = false,
    val muFormat: String? = null,
    val muYear: Int? = null,
    val muLicensed: String? = null,
    val muGenres: List<String> = emptyList(),
    val muExcludedGenres: List<String> = emptyList(),
    val muCategories: List<String> = emptyList(),
    val muExcludedCategories: List<String> = emptyList(),
    val muStatusFilters: List<String> = emptyList(),
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        fun from(name: String, isAnime: Boolean, f: ListFilters) = SavedListFilter(
            name = name,
            isAnime = isAnime,
            genres = f.genres,
            excludedGenres = f.excludedGenres,
            tags = f.tags,
            excludedTags = f.excludedTags,
            formats = f.formats,
            statuses = f.statuses,
            sources = f.sources,
            season = f.season,
            countryOfOrigin = f.countryOfOrigin,
            scoreRangeFrom = f.scoreRange.first,
            scoreRangeTo = f.scoreRange.second,
            yearRangeFrom = f.yearRange.first,
            yearRangeTo = f.yearRange.second,
            englishLicenced = f.englishLicenced,
            muFormat = f.muFormat,
            muYear = f.muYear,
            muLicensed = f.muLicensed,
            muGenres = f.muGenres,
            muExcludedGenres = f.muExcludedGenres,
            muCategories = f.muCategories,
            muExcludedCategories = f.muExcludedCategories,
            muStatusFilters = f.muStatusFilters,
        )
    }

    fun chips(): List<String> {
        val out = mutableListOf<String>()
        formats.forEach { out += "Format: $it" }
        statuses.forEach { out += "Status: $it" }
        sources.forEach { out += "Source: $it" }
        season?.let { out += "Season: ${it.lowercase()}" }
        countryOfOrigin?.let { out += "Country: $it" }
        if (scoreRangeFrom != 0f || scoreRangeTo != 10f) {
            out += "Score: $scoreRangeFrom-$scoreRangeTo"
        }
        if (yearRangeFrom != 1970 || yearRangeTo != 2028) {
            out += "Year: $yearRangeFrom-$yearRangeTo"
        }
        genres.forEach { out += it }
        excludedGenres.forEach { out += excludeLabel(it) }
        tags.forEach { out += it }
        excludedTags.forEach { out += excludeLabel(it) }
        if (englishLicenced) out += "English licensed"
        // MangaUpdates section
        muFormat?.let { out += "MU Format: $it" }
        muYear?.let { out += "MU Year: $it" }
        muLicensed?.let { out += if (it == "yes") "MU Licensed" else "MU Not licensed" }
        muStatusFilters.forEach { out += "MU ${MUSearchResults.STATUS_FILTER_LABELS[it] ?: it.replace("_", " ")}" }
        muGenres.forEach { out += "MU $it" }
        muExcludedGenres.forEach { out += "MU ${excludeLabel(it)}" }
        muCategories.forEach { out += "MU $it" }
        muExcludedCategories.forEach { out += "MU ${excludeLabel(it)}" }
        return out
    }

    fun toFilters(): ListFilters = ListFilters(
        genres = genres,
        excludedGenres = excludedGenres,
        tags = tags,
        excludedTags = excludedTags,
        formats = formats,
        statuses = statuses,
        sources = sources,
        season = season,
        year = null,
        countryOfOrigin = countryOfOrigin,
        scoreRange = Pair(scoreRangeFrom, scoreRangeTo),
        yearRange = Pair(yearRangeFrom, yearRangeTo),
        englishLicenced = englishLicenced,
        muFormat = muFormat,
        muYear = muYear,
        muLicensed = muLicensed,
        muGenres = muGenres,
        muExcludedGenres = muExcludedGenres,
        muCategories = muCategories,
        muExcludedCategories = muExcludedCategories,
        muStatusFilters = muStatusFilters,
    )
}

/**
 * Snapshot of an extension's filter list state. Each entry is the state of one
 * leaf filter (Select.state Int, Text.state String, CheckBox.state Boolean,
 * TriState.state Int, or Sort.state SavedSortSelection?), in depth-first order.
 *
 * Presets are scoped per source.id: a preset saved for source A is meaningless
 * for source B. We only apply when the snapshot length matches the live filter
 * list and each entry's type matches its target filter.
 */
data class SavedExtensionFilter(
    val name: String,
    val states: List<Any?>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }

    /**
     * Fallback chips when we don't have the live FilterList. Without it we
     * can't label values, so we just emit a placeholder per non-default state.
     * Wiring code should prefer [SavedFiltersStore.chipsForExtension].
     */
    fun chips(): List<String> {
        val out = mutableListOf<String>()
        states.forEachIndexed { i, v ->
            val display = when (v) {
                null -> null
                is Int -> if (v != 0) "Filter $i: $v" else null
                is String -> if (v.isNotEmpty()) "Filter $i: $v" else null
                is Boolean -> if (v) "Filter $i" else null
                is SavedSortSelection -> "Filter $i: #${v.index}${if (v.ascending) "↑" else "↓"}"
                else -> "Filter $i"
            }
            display?.let { out += it }
        }
        return out
    }
}

// ---- Shared helpers ----

internal fun pluralCounted(n: Int, singular: String, plural: String = singular + "s"): String =
    if (n == 1) "1 $singular" else "$n $plural"

data class SavedSortSelection(
    val index: Int,
    val ascending: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Bundles a source's extension presets together so the whole set can live
 * under a single PrefName at [ani.dantotsu.settings.saving.internal.Location.General],
 * which makes it eligible for the existing backup/restore flow. Each bundle
 * is keyed by the Aniyomi/Mihon `Source.id`.
 */
data class SavedExtensionFilterBundle(
    val sourceId: Long,
    val presets: List<SavedExtensionFilter>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
