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
    fun getLists(): LiveData<MutableMap<String, ArrayList<Media>>> = lists
    suspend fun loadLists(anime: Boolean, userId: Int, sortOrder: String? = null) {
        tryWithSuspend {
            val res = Anilist.query.getMediaLists(anime, userId, sortOrder)
            lists.postValue(res)
            unfilteredLists.postValue(res)
        }
    }

    fun filterLists(genre: String) {
        if (genre == "All") {
            lists.postValue(unfilteredLists.value)
            return
        }
        val currentLists = unfilteredLists.value ?: return
        val filteredLists = currentLists.mapValues { entry ->
            entry.value.filter { media ->
                genre in media.genres
            }.let { ArrayList(it) }
        }.toMutableMap()

        lists.postValue(filteredLists)
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