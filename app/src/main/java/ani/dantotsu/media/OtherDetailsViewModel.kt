package ani.dantotsu.media

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AniMangaSearchResults
import java.text.DateFormat
import java.util.Date

class OtherDetailsViewModel : ViewModel() {
    private val character: MutableLiveData<Character> = MutableLiveData(null)
    fun getCharacter(): LiveData<Character> = character
    suspend fun loadCharacter(m: Character) {
        if (character.value == null) character.postValue(Anilist.query.getCharacterDetails(m))
    }

    private val studio: MutableLiveData<Studio> = MutableLiveData(null)
    fun getStudio(): LiveData<Studio> = studio
    suspend fun loadStudio(m: Studio) {
        if (studio.value == null) studio.postValue(Anilist.query.getStudioDetails(m))
    }

    private val author: MutableLiveData<Author> = MutableLiveData(null)
    fun getAuthor(): LiveData<Author> = author
    suspend fun loadAuthor(m: Author) {
        if (author.value == null) author.postValue(Anilist.query.getAuthorDetails(m))
    }

    private var cachedAllCalendarData: Map<String, MutableList<Media>>? = null
    private var cachedLibraryMediaIds: Set<Int>? = null
    private val calendar: MutableLiveData<Map<String, MutableList<Media>>> = MutableLiveData(null)
    fun getCalendar(): LiveData<Map<String, MutableList<Media>>> = calendar
    suspend fun loadCalendar(filters: AniMangaSearchResults? = null) {
        if (cachedAllCalendarData == null || cachedLibraryMediaIds == null) {
            val curr = System.currentTimeMillis() / 1000
            val res = Anilist.query.recentlyUpdated(curr - 86400, curr + (86400 * 6))
            val df = DateFormat.getDateInstance(DateFormat.FULL)
            val allMap = mutableMapOf<String, MutableList<Media>>()
            val idMap = mutableMapOf<String, MutableList<Int>>()

            val userId = Anilist.userid ?: 0
            val userLibrary = Anilist.query.getMediaLists(true, userId)
            val libraryMediaIds = userLibrary.flatMap { it.value }.map { it.id }.toSet()

            res.forEach {
                val v = it.relation?.split(",")?.map { i -> i.toLong() }!!
                val dateInfo = df.format(Date(v[1] * 1000))
                val list = allMap.getOrPut(dateInfo) { mutableListOf() }
                val idList = idMap.getOrPut(dateInfo) { mutableListOf() }
                it.relation = "Episode ${v[0]}"
                if (!idList.contains(it.id)) {
                    idList.add(it.id)
                    list.add(it)
                }
            }

            cachedAllCalendarData = allMap
            cachedLibraryMediaIds = libraryMediaIds
        }

        val cacheToUse = cachedAllCalendarData ?: emptyMap()
        calendar.postValue(
            if (filters != null) {
                applyCalendarFilters(cacheToUse, filters, cachedLibraryMediaIds ?: emptySet())
            } else {
                cacheToUse
            }
        )
    }

    /**
     * Client-side filtering: the calendar is a one-shot cached fetch (no pagination/query
     * params like search has), so "filtering" means re-deriving from [map] rather than
     * re-querying AniList. Mirrors the same fields [AniMangaSearchResults.toChipList] shows.
     */
    private fun applyCalendarFilters(
        map: Map<String, MutableList<Media>>,
        f: AniMangaSearchResults,
        libraryMediaIds: Set<Int>,
    ): Map<String, MutableList<Media>> {
        fun deriveSeason(month: Int?): String? = when (month) {
            12, 1, 2 -> "WINTER"
            3, 4, 5 -> "SPRING"
            6, 7, 8 -> "SUMMER"
            9, 10, 11 -> "FALL"
            else -> null
        }

        fun matches(m: Media): Boolean {
            f.genres?.forEach { if (it !in m.genres) return false }
            f.excludedGenres?.forEach { if (it in m.genres) return false }
            f.tags?.forEach { if (it !in m.tags) return false }
            f.excludedTags?.forEach { if (it in m.tags) return false }
            f.format?.let { if (it != m.format) return false }
            f.status?.let { if (it != m.status) return false }
            f.source?.let { if (it != m.source) return false }
            f.countryOfOrigin?.let { if (it != m.countryOfOrigin) return false }
            f.season?.let { if (deriveSeason(m.startDate?.month) != it) return false }
            f.onList?.let { onList -> if ((m.id in libraryMediaIds) != onList) return false }
            val yearStart = f.yearRangeStart ?: f.startYear ?: f.seasonYear
            val yearEnd = f.yearRangeEnd ?: f.startYear ?: f.seasonYear
            if (yearStart != null || yearEnd != null) {
                val year = m.startDate?.year ?: return false
                if (yearStart != null && year < yearStart) return false
                if (yearEnd != null && year > yearEnd) return false
            }
            return true
        }

        fun sorted(list: List<Media>): List<Media> = when (f.sort) {
            "SCORE_DESC", "SCORE" -> list.sortedByDescending { it.meanScore ?: -1 }
            "POPULARITY_DESC", "TRENDING_DESC" -> list.sortedByDescending { it.popularity ?: -1 }
            "START_DATE_DESC" -> list.sortedByDescending { it.startDate }
            "TITLE_ENGLISH" -> list.sortedBy { it.userPreferredName.lowercase() }
            "TITLE_ENGLISH_DESC" -> list.sortedByDescending { it.userPreferredName.lowercase() }
            else -> list
        }

        return map.mapValues { (_, list) -> ArrayList(sorted(list.filter(::matches))) }
    }
}