package ani.dantotsu.media.user

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mangaupdates.MUDetailsCache
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.Normalizer

class ListViewModel : ViewModel() {
    var grid = MutableLiveData(PrefManager.getVal<Boolean>(PrefName.ListGrid))

    private val lists = MutableLiveData<MutableMap<String, ArrayList<Media>>>()
    private val unfilteredLists = MutableLiveData<MutableMap<String, ArrayList<Media>>>()
    val currentFilters = MutableLiveData<ListFilters>(ListFilters())
    private var currentSearchQuery: String = ""

    private val muLists = MutableLiveData<Map<String, List<MUMedia>>>()
    fun getMuLists(): LiveData<Map<String, List<MUMedia>>> = muLists

    private val filteredMuLists = MutableLiveData<Map<String, List<MUMedia>>>()
    fun getFilteredMuLists(): LiveData<Map<String, List<MUMedia>>> = filteredMuLists

    /** Synchronous backing store for MU data — updated before postValue so reads are never stale. */
    private var rawMuData: Map<String, List<MUMedia>>? = null

    /** The sort order that was last passed to [loadLists], used to re-apply MU sorting. */
    private var activeSortOrder: String? = null

    fun getLists(): LiveData<MutableMap<String, ArrayList<Media>>> = lists

    fun getUnfilteredLists(): LiveData<MutableMap<String, ArrayList<Media>>> = unfilteredLists

    suspend fun loadLists(anime: Boolean, userId: Int, sortOrder: String? = null) {
        tryWithSuspend {
            activeSortOrder = sortOrder
            val res = Anilist.query.getMediaLists(anime, userId, sortOrder)
            unfilteredLists.postValue(res)
            val filters = currentFilters.value

            // Load MangaUpdates lists alongside Anilist for manga — only for the logged-in user
            if (!anime && userId == ani.dantotsu.connections.anilist.Anilist.userid &&
                MangaUpdates.token != null &&
                ani.dantotsu.settings.saving.PrefManager.getVal<Boolean>(ani.dantotsu.settings.saving.PrefName.MangaUpdatesListEnabled)
            ) {
                val muResult = MangaUpdates.getAllUserLists()
                val sortedMuResult = sortMuLists(muResult, sortOrder)
                rawMuData = sortedMuResult    // synchronous — always up-to-date
                muLists.postValue(sortedMuResult)  // async notification for observers
                val allMuIds = sortedMuResult.values.flatten().map { it.id }.distinct()
                MUDetailsCache.prefetch(viewModelScope, allMuIds) {
                    recomputeCurrentViewState()
                }
            }

            // Reapply search + filters so any active UI state is preserved after a refresh
            if (currentSearchQuery.isNotEmpty()) {
                performSearch(currentSearchQuery, currentFilters.value, res)
            } else {
                if (filters != null && !filters.isEmpty()) {
                    val filteredLists = res.mapValues { entry ->
                        entry.value.filter { media ->
                            matchesFilters(media, filters)
                        }.let { ArrayList(it) }
                    }.toMutableMap()
                    lists.postValue(filteredLists)
                } else {
                    lists.postValue(res)
                }
                // Keep MU rows aligned with the current filter state after a refresh.
                filteredMuLists.postValue(
                    if (filters != null && !filters.isEmpty()) {
                        applyMuFilters(rawMuData ?: emptyMap(), filters, query = null)
                    } else {
                        rawMuData ?: emptyMap()
                    }
                )
            }
        }
    }

    fun reverseLists() {
        val current = lists.value ?: return
        val reversed = current.mapValues { (_, list) ->
            ArrayList(list.reversed())
        }.toMutableMap()
        lists.postValue(reversed)

        val currentUnfiltered = unfilteredLists.value ?: return
        val reversedUnfiltered = currentUnfiltered.mapValues { (_, list) ->
            ArrayList(list.reversed())
        }.toMutableMap()
        unfilteredLists.postValue(reversedUnfiltered)

        // Reverse MU lists too when sorted by updatedAt
        if (activeSortOrder?.startsWith("updatedAt") == true) {
            rawMuData = rawMuData?.mapValues { (_, list) -> list.reversed() }
            muLists.postValue(rawMuData ?: emptyMap())
            filteredMuLists.postValue(
                filteredMuLists.value?.mapValues { (_, list) -> list.reversed() } ?: emptyMap()
            )
        }
    }

    private fun sortMuLists(
        data: Map<String, List<MUMedia>>,
        sortOrder: String?
    ): Map<String, List<MUMedia>> {
        if (sortOrder?.startsWith("updatedAt") != true) return data
        val descending = !sortOrder.endsWith("_asc")
        return data.mapValues { (_, list) ->
            if (descending) list.sortedByDescending { it.updatedAt ?: 0L }
            else list.sortedBy { it.updatedAt ?: 0L }
        }
    }

    fun applyFilters(filters: ListFilters) {
        currentFilters.postValue(filters)

        // If there's an active search query, reapply the search with new filters
        if (currentSearchQuery.isNotEmpty()) {
            performSearch(currentSearchQuery, filters)
            return
        }

        // No active search, just apply filters
        if (filters.isEmpty()) {
            lists.postValue(unfilteredLists.value)
            filteredMuLists.postValue(rawMuData ?: emptyMap())
            return
        }

        val currentLists = unfilteredLists.value ?: return
        val hideAniListForMu = filters.hasMuFilters() && !filters.hasAnilistOnlyFilters()

        // Anilist items: hidden entirely when only MU filters are active.
        val filteredLists = if (hideAniListForMu) {
            currentLists.mapValues { ArrayList<Media>() }.toMutableMap()
        } else {
            currentLists.mapValues { entry ->
                entry.value.filter { media ->
                    matchesFilters(media, filters)
                }.let { ArrayList(it) }
            }.toMutableMap()
        }
        lists.postValue(filteredLists)

        val filteredMu = applyMuFilters(rawMuData ?: emptyMap(), filters, query = null)
        filteredMuLists.postValue(filteredMu)
    }

    private fun matchesFilters(media: Media, filters: ListFilters): Boolean {
        // Genres - must contain ALL selected genres
        if (filters.genres.isNotEmpty() && !filters.genres.all { it in media.genres }) {
            return false
        }

        // Excluded genres - must contain NONE of the excluded genres
        if (filters.excludedGenres.isNotEmpty() && filters.excludedGenres.any { excluded ->
                media.genres.any { it.equals(excluded, ignoreCase = true) }
            }
        ) {
            return false
        }

        // Tags - must contain ALL selected tags
        if (filters.tags.isNotEmpty() && !filters.tags.all { it in media.tags }) {
            return false
        }

        // Excluded tags - must contain NONE of the excluded tags
        if (filters.excludedTags.isNotEmpty() && filters.excludedTags.any { excluded ->
                media.tags.any { it.equals(excluded, ignoreCase = true) }
            }
        ) {
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

        // English licenced - must have a non-blank English title
        if (filters.englishLicenced && media.name.isNullOrBlank()) {
            return false
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
        currentSearchQuery = query  // Save current search query

        if (query.isEmpty()) {
            // Restore MU list respecting AniList-only suppression rules.
            val filters = currentFilters.value
            filteredMuLists.postValue(applyMuFilters(rawMuData ?: emptyMap(), filters, query = null))
            // When search is cleared, reapply current filters if they exist
            if (filters != null && !filters.isEmpty()) {
                // Don't call applyFilters here to avoid resetting currentSearchQuery
                val currentLists = unfilteredLists.value ?: return
                val hideAniListForMu = filters.hasMuFilters() && !filters.hasAnilistOnlyFilters()
                val filteredLists = if (hideAniListForMu) {
                    currentLists.mapValues { ArrayList<Media>() }.toMutableMap()
                } else {
                    currentLists.mapValues { entry ->
                        entry.value.filter { media ->
                            matchesFilters(media, filters)
                        }.let { ArrayList(it) }
                    }.toMutableMap()
                }
                lists.postValue(filteredLists)
            } else {
                lists.postValue(unfilteredLists.value)
            }
            return
        }

        // Perform search with current filters
        performSearch(query, currentFilters.value)
    }

    private fun performSearch(
        query: String,
        filters: ListFilters?,
        baseLists: MutableMap<String, ArrayList<Media>>? = null
    ) {
        val q = normalize(query)

        // Filter MU items by title/synonyms and active MU filters.
        val rawMuLists = rawMuData
        if (rawMuLists != null) {
            val muResult = applyMuFilters(rawMuLists, filters, q)
            filteredMuLists.postValue(muResult)
        }

        // Suppress all AniList results when only MU filters are active.
        if (filters?.hasMuFilters() == true && !filters.hasAnilistOnlyFilters()) {
            val currentLists = unfilteredLists.value ?: return
            lists.postValue(currentLists.mapValues { ArrayList<Media>() }.toMutableMap())
            return
        }

        // Determine which list to search: if filters are active, search filtered list; otherwise search all
        val sourceLists = baseLists ?: unfilteredLists.value
        val baseList = if (filters != null && !filters.isEmpty()) {
            // Apply filters first to get the filtered list, then search within that
            sourceLists?.mapValues { entry ->
                entry.value.filter { media ->
                    matchesFilters(media, filters)
                }.let { ArrayList(it) }
            }?.toMutableMap()
        } else {
            sourceLists
        }

        val currentLists = baseList ?: return
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

    private fun applyMuFilters(
        data: Map<String, List<MUMedia>>,
        filters: ListFilters?,
        query: String?
    ): Map<String, List<MUMedia>> {
        if (filters?.hasAnilistOnlyFilters() == true && !filters.hasMuFilters()) return emptyMap()

        if (filters?.hasMuFilters() == true) {
            prefetchMissingMuDetails(data)
        }

        val normalizedQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        var output = data.mapValues { (_, list) ->
            list.filter { mu ->
                matchesMuFilters(mu, filters) && matchesMuSearch(mu, normalizedQuery)
            }
        }

        return output
    }

    private fun matchesMuSearch(mu: MUMedia, normalizedQuery: String?): Boolean {
        if (normalizedQuery.isNullOrBlank()) return true
        return normalize(mu.title).contains(normalizedQuery) ||
            MangaUpdates.synonymsCache[mu.id]?.any { normalize(it).contains(normalizedQuery) } == true
    }

    private fun matchesMuFilters(mu: MUMedia, filters: ListFilters?): Boolean {
        if (filters == null) return true
        val detail = MUDetailsCache.get(mu.id)

        filters.muFormat?.let { required ->
            val type = detail?.type?.trim()
            if (type.isNullOrBlank() || !type.equals(required, ignoreCase = true)) return false
        }

        filters.muYear?.let { requiredYear ->
            if (detail?.year != requiredYear) return false
        }

        filters.muLicensed?.let { licensed ->
            val hasLicense = detail?.hasEnglishPublisher
            if (licensed == "yes" && hasLicense != true) return false
            if (licensed == "no" && hasLicense != false) return false
        }

        if (filters.muGenres.isNotEmpty()) {
            val genres = detail?.genres ?: emptySet()
            if (!filters.muGenres.all { g -> genres.any { it.equals(g, ignoreCase = true) } }) return false
        }

        if (filters.muExcludedGenres.isNotEmpty()) {
            val genres = detail?.genres ?: emptySet()
            if (filters.muExcludedGenres.any { g -> genres.any { it.equals(g, ignoreCase = true) } }) return false
        }

        if (filters.muCategories.isNotEmpty()) {
            val categories = detail?.categories ?: emptySet()
            if (!filters.muCategories.all { c -> categories.any { it.equals(c, ignoreCase = true) } }) return false
        }

        if (filters.muExcludedCategories.isNotEmpty()) {
            val categories = detail?.categories ?: emptySet()
            if (filters.muExcludedCategories.any { c -> categories.any { it.equals(c, ignoreCase = true) } }) return false
        }

        if (filters.muStatusFilters.isNotEmpty()) {
            val completed = detail?.completed == true
            val latestChapter = detail?.latestChapter
            val hasReleases = latestChapter != null && latestChapter > 0
            val isOneShot = latestChapter != null && latestChapter <= 1
            val matchesStatus = filters.muStatusFilters.all { status ->
                when (status) {
                    "scanlated" -> hasReleases
                    "completed" -> completed
                    "oneshots" -> isOneShot
                    "no_oneshots" -> latestChapter == null || latestChapter > 1
                    "some_releases" -> hasReleases
                    "no_releases" -> !hasReleases
                    else -> true
                }
            }
            if (!matchesStatus) return false
        }

        return true
    }

    private fun sortMuListByOrder(list: List<MUMedia>, orderBy: String): List<MUMedia> {
        return when (orderBy) {
            "title" -> list.sortedBy { normalize(it.title) }
            "year" -> list.sortedByDescending { MUDetailsCache.get(it.id)?.year ?: Int.MIN_VALUE }
            "score", "rating" -> list.sortedByDescending { it.bayesianRating ?: Double.MIN_VALUE }
            "date_added" -> list.sortedByDescending { it.updatedAt ?: Long.MIN_VALUE }
            "list_reading" -> list.sortedByDescending { if (it.listId == 0) 1 else 0 }
            "list_wish" -> list.sortedByDescending { if (it.listId == 1) 1 else 0 }
            "list_complete" -> list.sortedByDescending { if (it.listId == 2) 1 else 0 }
            else -> list
        }
    }

    private fun prefetchMissingMuDetails(data: Map<String, List<MUMedia>>) {
        val missingIds = data.values.flatten().map { it.id }.distinct().filter { MUDetailsCache.get(it) == null }
        if (missingIds.isEmpty()) return
        MUDetailsCache.prefetch(viewModelScope, missingIds) {
            recomputeCurrentViewState()
        }
    }

    private fun recomputeCurrentViewState() {
        viewModelScope.launch(Dispatchers.Default) {
            val filters = currentFilters.value
            val query = currentSearchQuery
            if (query.isBlank()) {
                filteredMuLists.postValue(applyMuFilters(rawMuData ?: emptyMap(), filters, query = null))
            } else {
                filteredMuLists.postValue(applyMuFilters(rawMuData ?: emptyMap(), filters, normalize(query)))
            }
        }
    }

    fun unfilterLists() {
        filteredMuLists.postValue(muLists.value)
        // When unfiltering for search, check if we should reapply saved filters
        val filters = currentFilters.value
        if (filters != null && !filters.isEmpty()) {
            applyFilters(filters)
        } else {
            lists.postValue(unfilteredLists.value)
        }
    }

}