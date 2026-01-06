package ani.dantotsu.media.user

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import java.text.Normalizer

class ListViewModel : ViewModel() {
    var grid = MutableLiveData(PrefManager.getVal<Boolean>(PrefName.ListGrid))

    private val lists = MutableLiveData<MutableMap<String, ArrayList<Media>>>()
    private val unfilteredLists = MutableLiveData<MutableMap<String, ArrayList<Media>>>()
    val currentFilters = MutableLiveData<ListFilters>(ListFilters())

    fun getLists(): LiveData<MutableMap<String, ArrayList<Media>>> = lists

    suspend fun loadLists(anime: Boolean, userId: Int, sortOrder: String? = null) {
        tryWithSuspend {
            val res = Anilist.query.getMediaLists(anime, userId, sortOrder)
            lists.postValue(res)
            unfilteredLists.postValue(res)
        }
    }

    fun applyFilters(filters: ListFilters) {
        if (filters.isEmpty()) {
            lists.postValue(unfilteredLists.value)
            currentFilters.postValue(ListFilters())
            return
        }

        currentFilters.postValue(filters)
        val currentLists = unfilteredLists.value ?: return

        val filteredLists = currentLists.mapValues { entry ->
            entry.value.filter { media ->
                matchesFilters(media, filters)
            }.let { ArrayList(it) }
        }.toMutableMap()

        lists.postValue(filteredLists)
    }

    private fun matchesFilters(media: Media, filters: ListFilters): Boolean {
        // Genres - must contain ALL selected genres
        if (filters.genres.isNotEmpty() && !filters.genres.all { it in media.genres }) {
            return false
        }

        // Tags - must contain ALL selected tags
        if (filters.tags.isNotEmpty() && !filters.tags.all { it in media.tags }) {
            return false
        }

        // Format - handle null and case-insensitive comparison
        if (filters.formats.isNotEmpty()) {
            if (media.format == null) return false
            if (!filters.formats.any { it.equals(media.format, ignoreCase = true) }) {
                return false
            }
        }

        // Status - handle null and case-insensitive comparison
        if (filters.statuses.isNotEmpty()) {
            if (media.status == null) return false
            if (!filters.statuses.any { it.equals(media.status, ignoreCase = true) }) {
                return false
            }
        }

        // Source - handle null and case-insensitive comparison
        if (filters.sources.isNotEmpty()) {
            if (media.source == null) return false
            if (!filters.sources.any { it.equals(media.source, ignoreCase = true) }) {
                return false
            }
        }

        // Country of Origin - handle null and exact match
        if (filters.countryOfOrigin != null) {
            if (media.countryOfOrigin == null) return false
            if (!media.countryOfOrigin.equals(filters.countryOfOrigin, ignoreCase = true)) {
                return false
            }
        }

        // Season (for anime)
        if (filters.season != null) {
            val mediaMonth = media.startDate?.month
            val mediaSeason = when (mediaMonth) {
                in 1..3 -> "WINTER"
                in 4..6 -> "SPRING"
                in 7..9 -> "SUMMER"
                in 10..12 -> "FALL"
                else -> null
            }
            if (mediaSeason != filters.season) {
                return false
            }
        }

        // Year
        if (filters.year != null && media.startDate?.year != filters.year) {
            return false
        }

        // Score range (convert from 0.0-10.0 display to 0-100 internal)
        val score = media.meanScore ?: 0
        val internalRange = filters.getInternalScoreRange()
        if (score < internalRange.first || score > internalRange.second) {
            return false
        }

        // Year range - exclude items with no year if not using default range
        val year = media.startDate?.year
        if (filters.yearRange != Pair(1970, 2028)) {
            // Not using default range, so year must exist and be within range
            if (year == null || year < filters.yearRange.first || year > filters.yearRange.second) {
                return false
            }
        }

        return true
    }

    // Legacy methods for backwards compatibility
    fun filterLists(genres: Set<String>) {
        val filters = currentFilters.value?.copy(genres = genres.toList()) ?: ListFilters(genres = genres.toList())
        applyFilters(filters)
    }

    fun filterLists(genre: String) {
        if (genre == "All") {
            applyFilters(ListFilters())
        } else {
            filterLists(setOf(genre))
        }
    }

    private fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val normalized = Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
        return normalized.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "").lowercase()
    }

    fun searchLists(search: String) {
        val query = search.trim()
        if (query.isEmpty()) {
            lists.postValue(unfilteredLists.value)
            return
        }
        val q = normalize(query)
        val currentLists = unfilteredLists.value ?: return
        val filteredLists = currentLists.mapValues { entry ->
            entry.value.filter { media ->
                val name = normalize(media.name)
                val romaji = normalize(media.nameRomaji)
                val userPreferred = normalize(media.userPreferredName)
                val synonyms = media.synonyms.map { normalize(it) }

                (name.contains(q) || romaji.contains(q) || userPreferred.contains(q) || synonyms.any { it.contains(q) })
            }.let { ArrayList(it) }
        }.toMutableMap()

        lists.postValue(filteredLists)
    }

    fun unfilterLists() {
        lists.postValue(unfilteredLists.value)
    }

}