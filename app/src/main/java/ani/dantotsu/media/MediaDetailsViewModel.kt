package ani.dantotsu.media

import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mangaupdates.MUSeriesRecord
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.currContext
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.media.anime.SelectorDialogFragment
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.others.AniSkip
import ani.dantotsu.others.Anify
import ani.dantotsu.others.Jikan
import ani.dantotsu.others.Kitsu
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.Book
import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.parsers.MangaReadSources
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.parsers.NovelSources
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.WatchSources
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class MediaDetailsViewModel : ViewModel() {
    val scrolledToTop = MutableLiveData(true)
    val comickSlug = MutableLiveData<String?>(null)
    val mangaUpdatesLink = MutableLiveData<String?>(null)
    val shouldShowSearchModal = MutableLiveData(false)
    val comickLoaded = MutableLiveData(false)
    val mangaUpdatesLoaded = MutableLiveData(false)
    val mangaUpdatesLoading = MutableLiveData(false)

    // New: Hold preloaded MangaUpdates series details (or null if none)
    val mangaUpdatesSeries = MutableLiveData<MUSeriesRecord?>(null)
    val mangaUpdatesError = MutableLiveData<String?>(null)

    // Replace boolean flag with last preloaded media id to avoid cross-media cache issues
    private var lastPreloadedMediaId: Int? = null

    /**
     * Preload Comick and MangaUpdates data in the background for manga entries. This allows tab
     * states to update immediately without waiting for fragment loading.
     */
    fun preloadExternalData(media: Media) {
        // Only preload for manga and only once per media
        if (media.anime != null || lastPreloadedMediaId == media.id) return
        lastPreloadedMediaId = media.id

        MainScope().launch(Dispatchers.IO) {
            try {
                // Import required classes
                val comickApi = ani.dantotsu.connections.comick.ComickApi
                val malSyncApi = ani.dantotsu.connections.malsync.MalSyncApi

                // Filter English titles helper
                fun filterEnglishTitles(synonyms: List<String>): List<String> {
                    return synonyms.filter { title ->
                        if (title.isBlank()) return@filter false
                        val hasCJK =
                                title.any { char ->
                                    char.code in 0x3040..0x309F || // Hiragana
                                    char.code in 0x30A0..0x30FF || // Katakana
                                            char.code in 0x4E00..0x9FFF || // CJK Unified Ideographs
                                            char.code in 0xAC00..0xD7AF || // Hangul Syllables
                                            char.code in 0x1100..0x11FF // Hangul Jamo
                                }
                        !hasCJK
                    }
                }

                // Try MalSync first (only if MALSync info enabled)
                val malMode = ani.dantotsu.settings.saving.PrefManager.getVal<String>(ani.dantotsu.settings.saving.PrefName.MalSyncCheckMode) ?: "both"
                val quicklinks = if (ani.dantotsu.settings.saving.PrefManager.getVal<Boolean>(ani.dantotsu.settings.saving.PrefName.MalSyncInfoEnabled) && malMode != "anime") {
                    try {
                        malSyncApi.getQuicklinks(media.id, media.idMAL)
                    } catch (e: Exception) {
                        null
                    }
                } else null

                val malSyncSlugs =
                        quicklinks?.Sites?.entries
                                ?.firstOrNull {
                                    it.key.equals("Comick", true) || it.key.contains("comick", true)
                                }
                                ?.value
                                ?.values
                                ?.mapNotNull { it.identifier }
                                ?: emptyList()

                // Search for Comick entry
                val titlesToTry = mutableListOf<String>()
                media.name?.let { titlesToTry.add(it) }
                val englishSynonyms = filterEnglishTitles(media.synonyms)
                englishSynonyms.forEach { synonym ->
                    if (!titlesToTry.contains(synonym)) {
                        titlesToTry.add(synonym)
                    }
                }
                if (!titlesToTry.contains(media.nameRomaji)) {
                    titlesToTry.add(media.nameRomaji)
                }

                // Extract external link URLs for validation
                val externalLinkUrls = media.externalLinks.mapNotNull { it.getOrNull(1) }
                ani.dantotsu.util.Logger.log(
                        "Comick Preload: Found ${externalLinkUrls.size} external link(s) for validation: $externalLinkUrls"
                )

                // Check if user has manually saved a slug for this media first
                // If Comick is disabled, skip searching and clear related LiveData
                if (!ani.dantotsu.settings.saving.PrefManager.getVal<Boolean>(ani.dantotsu.settings.saving.PrefName.ComickEnabled)) {
                    comickSlug.postValue(null)
                    comickLoaded.postValue(true)
                }

                if (!ani.dantotsu.settings.saving.PrefManager.getVal<Boolean>(ani.dantotsu.settings.saving.PrefName.ComickEnabled)) {
                    // Ensure we don't run the search below
                }

                // Proceed to determine comickSlug only if Comick enabled
                val savedSlug =
                    if (ani.dantotsu.settings.saving.PrefManager.getVal<Boolean>(ani.dantotsu.settings.saving.PrefName.ComickEnabled)) {
                        PrefManager.getNullableCustomVal<String>(
                            "comick_slug_${media.id}",
                            null,
                            String::class.java
                        )
                    } else null
                val comickSlugValue = if (ani.dantotsu.settings.saving.PrefManager.getVal<Boolean>(ani.dantotsu.settings.saving.PrefName.ComickEnabled)) {
                    if (savedSlug != null) {
                        ani.dantotsu.util.Logger.log(
                                "Comick Preload: Found saved slug '$savedSlug' in preferences"
                        )
                        savedSlug
                    } else if (titlesToTry.isNotEmpty()) {
                        comickApi.searchAndMatchComic(
                                titlesToTry,
                                media.id,
                                media.idMAL,
                                malSyncSlugs.takeIf { it.isNotEmpty() },
                                externalLinkUrls.takeIf { it.isNotEmpty() }
                        )
                    } else null
                } else null

                // Update comickSlug
                comickSlug.postValue(comickSlugValue)

                // Check if user has manually saved a MangaUpdates link (only if MU enabled)
                val savedMULink = if (ani.dantotsu.settings.saving.PrefManager.getVal<Boolean>(ani.dantotsu.settings.saving.PrefName.MangaUpdatesEnabled)) {
                    PrefManager.getNullableCustomVal<String>(
                        "mangaupdates_link_${media.id}",
                        null,
                        String::class.java
                    )
                } else null

                // If we found a Comick slug or have a saved MU link, fetch details
                mangaUpdatesLoading.postValue(true)
                mangaUpdatesError.postValue(null)
                mangaUpdatesSeries.postValue(null)

                // Helper to fetch series details
                suspend fun fetchMUSeries(link: String) {
                    try {
                        val loggedIn = MangaUpdates.getSavedToken()
                        if (loggedIn) {
                            val seriesIdentifier = extractMUIdentifier(link)
                            if (seriesIdentifier.isNotBlank()) {
                                try {
                                    val seriesDetails =
                                            MangaUpdates.getSeriesFromUrl(seriesIdentifier)
                                    ani.dantotsu.util.Logger.log(
                                            "MediaDetailsViewModel: Preload fetched series for id=$seriesIdentifier -> title=${seriesDetails?.title}"
                                    )
                                    mangaUpdatesSeries.postValue(seriesDetails)
                                } catch (e: Exception) {
                                    mangaUpdatesSeries.postValue(null)
                                    mangaUpdatesError.postValue(e.message)
                                }
                            }
                        } else {
                            mangaUpdatesSeries.postValue(null)
                        }
                    } catch (e: Exception) {
                        mangaUpdatesSeries.postValue(null)
                        mangaUpdatesError.postValue(e.message)
                    }
                }

                if (savedMULink != null) {
                    ani.dantotsu.util.Logger.log(
                            "MediaDetailsViewModel: Found saved MangaUpdates link '$savedMULink'"
                    )
                    mangaUpdatesLink.postValue(savedMULink)
                    fetchMUSeries(savedMULink)
                } else if (comickSlugValue != null && ani.dantotsu.settings.saving.PrefManager.getVal<Boolean>(ani.dantotsu.settings.saving.PrefName.MangaUpdatesEnabled)) {
                    try {
                        val comickData = comickApi.getComicDetails(comickSlugValue)
                        val muLink = comickData?.comic?.links?.mu
                        if (!muLink.isNullOrBlank()) {
                            // If the muLink is purely numeric, use the series.html?id= fallback;
                            // otherwise assume it's a slug.
                            val link =
                                    if (muLink.trim().toLongOrNull() != null) {
                                        "https://www.mangaupdates.com/series.html?id=${muLink.trim()}"
                                    } else {
                                        "https://www.mangaupdates.com/series/${muLink.trim()}"
                                    }
                            mangaUpdatesLink.postValue(link)
                            fetchMUSeries(link)
                        } else {
                            mangaUpdatesLink.postValue(null)
                            mangaUpdatesSeries.postValue(null)
                        }
                    } catch (e: Exception) {
                        mangaUpdatesLink.postValue(null)
                        mangaUpdatesSeries.postValue(null)
                    }
                    } else {
                    // If MangaUpdates is disabled, ensure LiveData reflect disabled state
                    if (!ani.dantotsu.settings.saving.PrefManager.getVal<Boolean>(ani.dantotsu.settings.saving.PrefName.MangaUpdatesEnabled)) {
                        mangaUpdatesLink.postValue(null)
                        mangaUpdatesSeries.postValue(null)
                        mangaUpdatesLoaded.postValue(true)
                        mangaUpdatesLoading.postValue(false)
                    } else {
                        mangaUpdatesLink.postValue(null)
                        mangaUpdatesSeries.postValue(null)
                    }
                }
                mangaUpdatesLoading.postValue(false)
                mangaUpdatesLoaded.postValue(true)
            } catch (e: Exception) {
                comickSlug.postValue(null)
                mangaUpdatesLink.postValue(null)
                mangaUpdatesLoading.postValue(false)
                mangaUpdatesLoaded.postValue(true)
                mangaUpdatesSeries.postValue(null)
                mangaUpdatesError.postValue(e.message)
            }
        }
    }

    // Public fallback fetch method: ask ViewModel to fetch MU series by identifier (used by
    // fragment if needed)
    fun fetchMangaUpdatesSeriesByIdentifier(seriesIdentifier: String) {
        if (seriesIdentifier.isBlank()) return
        // Allow explicit fetch requests from the fragment even if a preload previously set
        // loading=true.
        Logger.log(
                "MediaDetailsViewModel: fetchMangaUpdatesSeriesByIdentifier starting for id=$seriesIdentifier"
        )
        mangaUpdatesLoading.postValue(true)
        mangaUpdatesError.postValue(null)
        MainScope().launch(Dispatchers.IO) {
            try {
                val series = MangaUpdates.getSeriesFromUrl(seriesIdentifier)
                Logger.log(
                        "MediaDetailsViewModel: Fallback fetch result for id=$seriesIdentifier -> title=${series?.title}"
                )
                mangaUpdatesSeries.postValue(series)
                mangaUpdatesLoaded.postValue(true)
            } catch (e: Exception) {
                mangaUpdatesSeries.postValue(null)
                mangaUpdatesError.postValue(e.message)
                mangaUpdatesLoaded.postValue(true)
            } finally {
                mangaUpdatesLoading.postValue(false)
            }
        }
    }

    // Helper: extract either numeric series ID (from ?id=), the slug from a MangaUpdates link, or
    // return id directly
    private fun extractMUIdentifier(muLinkRaw: String?): String {
        if (muLinkRaw.isNullOrBlank()) return ""
        val muLink = muLinkRaw.trim()
        // If the input is already a simple ID (numeric or alphanumeric slug), return it as-is
        val simpleIdPattern = Regex("^[A-Za-z0-9_-]+$")
        if (muLink.matches(simpleIdPattern)) return muLink

        return try {
            val uri = android.net.Uri.parse(muLink)
            // Prefer explicit id query parameter
            val idParam = uri.getQueryParameter("id")
            if (!idParam.isNullOrBlank()) return idParam

            // Fallback to last path segment
            val path = uri.path ?: ""
            var lastSeg = path.trimEnd('/').substringAfterLast('/')

            // If the link contains series.html?id=... without proper parsing above, try to extract
            // after '='
            if (lastSeg.isBlank() && muLink.contains("=")) {
                val afterEq = muLink.substringAfter('=', "")
                if (afterEq.isNotBlank()) return afterEq
            }

            // If lastSeg still contains query params (e.g., "series.html?id=159827"), strip them
            if (lastSeg.contains("?")) {
                lastSeg = lastSeg.substringBefore('?')
            }

            // If the last segment looks like "series.html", try to extract id manually
            if (lastSeg.contains("series.html") && muLink.contains("id=")) {
                val afterEq = muLink.substringAfter("id=", "")
                if (afterEq.isNotBlank()) return afterEq.substringBefore('&')
            }

            lastSeg
        } catch (e: Exception) {
            // Fallback: return last path-like token
            muLink.substringAfterLast('/')
        }
    }

    fun saveSelected(id: Int, data: Selected) {
        PrefManager.setCustomVal("Selected-$id", data)
    }

    fun loadSelected(media: Media, isDownload: Boolean = false): Selected {
        val data =
                PrefManager.getNullableCustomVal("Selected-${media.id}", null, Selected::class.java)
                        ?: Selected().let {
                            it.sourceIndex = 0
                            it.preferDub = PrefManager.getVal(PrefName.SettingsPreferDub)
                            saveSelected(media.id, it)
                            it
                        }
        if (isDownload) {
            data.sourceIndex =
                    when {
                        media.anime != null -> {
                            AnimeSources.list.size - 1
                        }
                        media.format == "MANGA" || media.format == "ONE_SHOT" -> {
                            MangaSources.list.size - 1
                        }
                        else -> {
                            NovelSources.list.size - 1
                        }
                    }
        }
        return data
    }

    var continueMedia: Boolean? = null
    private var loading = false

    private val media: MutableLiveData<Media> = MutableLiveData<Media>(null)
    fun getMedia(): LiveData<Media> = media
    fun loadMedia(m: Media) {
        if (!loading) {
            loading = true
            media.postValue(Anilist.query.mediaDetails(m))
        }
        loading = false
    }

    fun setMedia(m: Media) {
        media.postValue(m)
    }

    val responses = MutableLiveData<List<ShowResponse>?>(null)

    // Anime
    private val kitsuEpisodes: MutableLiveData<Map<String, Episode>> =
            MutableLiveData<Map<String, Episode>>(null)

    fun getKitsuEpisodes(): LiveData<Map<String, Episode>> = kitsuEpisodes
    suspend fun loadKitsuEpisodes(s: Media) {
        tryWithSuspend {
            if (kitsuEpisodes.value == null)
                    kitsuEpisodes.postValue(Kitsu.getKitsuEpisodesDetails(s))
        }
    }

    private val anifyEpisodes: MutableLiveData<Map<String, Episode>> =
            MutableLiveData<Map<String, Episode>>(null)

    fun getAnifyEpisodes(): LiveData<Map<String, Episode>> = anifyEpisodes
    suspend fun loadAnifyEpisodes(s: Int) {
        tryWithSuspend {
            if (anifyEpisodes.value == null) anifyEpisodes.postValue(Anify.fetchAndParseMetadata(s))
        }
    }

    private val fillerEpisodes: MutableLiveData<Map<String, Episode>> =
            MutableLiveData<Map<String, Episode>>(null)

    fun getFillerEpisodes(): LiveData<Map<String, Episode>> = fillerEpisodes
    suspend fun loadFillerEpisodes(s: Media) {
        tryWithSuspend {
            if (fillerEpisodes.value == null)
                    fillerEpisodes.postValue(Jikan.getEpisodes(s.idMAL ?: return@tryWithSuspend))
        }
    }

    var watchSources: WatchSources? = null

    private val episodes = MutableLiveData<MutableMap<Int, MutableMap<String, Episode>>>(null)
    private val epsLoaded = mutableMapOf<Int, MutableMap<String, Episode>>()
    fun getEpisodes(): LiveData<MutableMap<Int, MutableMap<String, Episode>>> = episodes
    suspend fun loadEpisodes(media: Media, i: Int, invalidate: Boolean = false) {
        if (!epsLoaded.containsKey(i) || invalidate) {
            epsLoaded[i] = watchSources?.loadEpisodesFromMedia(i, media) ?: return
        }
        episodes.postValue(epsLoaded)
    }

    suspend fun forceLoadEpisode(media: Media, i: Int) {
        epsLoaded[i] = watchSources?.loadEpisodesFromMedia(i, media) ?: return
        episodes.postValue(epsLoaded)
    }

    suspend fun overrideEpisodes(i: Int, source: ShowResponse, id: Int) {
        watchSources?.saveResponse(i, id, source)
        epsLoaded[i] =
                watchSources?.loadEpisodes(i, source.link, source.extra, source.sAnime) ?: return
        episodes.postValue(epsLoaded)
    }

    private var episode = MutableLiveData<Episode?>(null)
    fun getEpisode(): LiveData<Episode?> = episode

    suspend fun loadEpisodeVideos(ep: Episode, i: Int, post: Boolean = true) {
        val link = ep.link ?: return
        if (!ep.allStreams || ep.extractors.isNullOrEmpty()) {
            val list = mutableListOf<VideoExtractor>()
            ep.extractors = list
            watchSources?.get(i)?.apply {
                if (!post && !allowsPreloading) return@apply
                ep.sEpisode?.let {
                    loadByVideoServers(link, ep.extra, it) { extractor ->
                        if (extractor.videos.isNotEmpty()) {
                            list.add(extractor)
                            ep.extractorCallback?.invoke(extractor)
                        }
                    }
                }
                ep.extractorCallback = null
                if (list.isNotEmpty()) ep.allStreams = true
            }
        }

        if (post) {
            episode.postValue(ep)
            MainScope().launch(Dispatchers.Main) { episode.value = null }
        }
    }

    val timeStamps = MutableLiveData<List<AniSkip.Stamp>?>()
    private val timeStampsMap: MutableMap<Int, List<AniSkip.Stamp>?> = mutableMapOf()
    suspend fun loadTimeStamps(
            malId: Int?,
            episodeNum: Int?,
            duration: Long,
            useProxyForTimeStamps: Boolean
    ) {
        malId ?: return
        episodeNum ?: return
        if (timeStampsMap.containsKey(episodeNum))
                return timeStamps.postValue(timeStampsMap[episodeNum])
        val result = AniSkip.getResult(malId, episodeNum, duration, useProxyForTimeStamps)
        timeStampsMap[episodeNum] = result
        timeStamps.postValue(result)
    }

    suspend fun loadEpisodeSingleVideo(
            ep: Episode,
            selected: Selected,
            post: Boolean = true
    ): Boolean {
        if (ep.extractors.isNullOrEmpty()) {

            val server = selected.server ?: return false
            val link = ep.link ?: return false

            ep.extractors =
                    mutableListOf(
                            watchSources?.get(selected.sourceIndex)?.let {
                                selected.sourceIndex = selected.sourceIndex
                                if (!post && !it.allowsPreloading) null
                                else
                                        ep.sEpisode?.let { it1 ->
                                            it.loadSingleVideoServer(
                                                    server,
                                                    link,
                                                    ep.extra,
                                                    it1,
                                                    post
                                            )
                                        }
                            }
                                    ?: return false
                    )
            ep.allStreams = false
        }
        if (post) {
            episode.postValue(ep)
            MainScope().launch(Dispatchers.Main) { episode.value = null }
        }
        return true
    }

    fun setEpisode(ep: Episode?, who: String) {
        Logger.log("set episode ${ep?.number} - $who")
        episode.postValue(ep)
        MainScope().launch(Dispatchers.Main) { episode.value = null }
    }

    val epChanged = MutableLiveData(true)
    fun onEpisodeClick(
            media: Media,
            i: String,
            manager: FragmentManager,
            launch: Boolean = true,
            prevEp: String? = null,
            isDownload: Boolean = false
    ) {
        Handler(Looper.getMainLooper()).post {
            if (manager.findFragmentByTag("dialog") == null && !manager.isDestroyed) {
                if (media.anime?.episodes?.get(i) != null) {
                    media.anime.selectedEpisode = i
                } else {
                    snackString(currContext()?.getString(R.string.episode_not_found, i))
                    return@post
                }
                media.selected = this.loadSelected(media)
                val selector =
                        SelectorDialogFragment.newInstance(
                                media.selected!!.server,
                                launch,
                                prevEp,
                                isDownload
                        )
                selector.show(manager, "dialog")
            }
        }
    }

    // Manga
    var mangaReadSources: MangaReadSources? = null

    private val mangaChapters =
            MutableLiveData<MutableMap<Int, MutableMap<String, MangaChapter>>>(null)
    private val mangaLoaded = mutableMapOf<Int, MutableMap<String, MangaChapter>>()
    fun getMangaChapters(): LiveData<MutableMap<Int, MutableMap<String, MangaChapter>>> =
            mangaChapters

    suspend fun loadMangaChapters(media: Media, i: Int, invalidate: Boolean = false) {
        Logger.log("Loading Manga Chapters : $mangaLoaded")
        if (!mangaLoaded.containsKey(i) || invalidate)
                tryWithSuspend {
                    mangaLoaded[i] =
                            mangaReadSources?.loadChaptersFromMedia(i, media)
                                    ?: return@tryWithSuspend
                }
        mangaChapters.postValue(mangaLoaded)
    }

    suspend fun overrideMangaChapters(i: Int, source: ShowResponse, id: Int) {
        mangaReadSources?.saveResponse(i, id, source)
        tryWithSuspend {
            mangaLoaded[i] = mangaReadSources?.loadChapters(i, source) ?: return@tryWithSuspend
        }
        mangaChapters.postValue(mangaLoaded)
    }

    private val mangaChapter = MutableLiveData<MangaChapter?>(null)
    fun getMangaChapter(): LiveData<MangaChapter?> = mangaChapter
    suspend fun loadMangaChapterImages(
            chapter: MangaChapter,
            selected: Selected,
            post: Boolean = true
    ): Boolean {

        return tryWithSuspend(true) {
            chapter.addImages(
                    mangaReadSources
                            ?.get(selected.sourceIndex)
                            ?.loadImages(chapter.link, chapter.sChapter)
                            ?: return@tryWithSuspend false
            )
            if (post) mangaChapter.postValue(chapter)
            true
        }
                ?: false
    }

    fun loadTransformation(mangaImage: MangaImage, source: Int): BitmapTransformation? {
        return if (mangaImage.useTransformation) mangaReadSources?.get(source)?.getTransformation()
        else null
    }

    val novelSources = NovelSources
    val novelResponses = MutableLiveData<List<ShowResponse>>(null)
    suspend fun searchNovels(query: String, i: Int) {
        val position = if (i >= novelSources.list.size) 0 else i
        val source = novelSources[position]
        tryWithSuspend(post = true) {
            if (source != null) {
                novelResponses.postValue(source.search(query))
            }
        }
    }

    suspend fun autoSearchNovels(media: Media) {
        val source = novelSources[media.selected?.sourceIndex ?: 0]
        tryWithSuspend(post = true) {
            if (source != null) {
                novelResponses.postValue(source.sortedSearch(media))
            }
        }
    }

    val book: MutableLiveData<Book> = MutableLiveData(null)
    suspend fun loadBook(novel: ShowResponse, i: Int) {
        tryWithSuspend {
            book.postValue(
                    novelSources[i]?.loadBook(novel.link, novel.extra) ?: return@tryWithSuspend
            )
        }
    }

    fun saveMangaUpdatesLink(
            mediaId: Int,
            link: String,
            preloadedSeries: ani.dantotsu.connections.mangaupdates.MUSeriesRecord? = null
    ) {
        val key = "mangaupdates_link_${mediaId}"
        val serialized = link // String is handled natively
        ani.dantotsu.settings.saving.PrefManager.setCustomVal(key, serialized)
        ani.dantotsu.util.Logger.log("MediaDetailsViewModel: Saved MU link for $mediaId: $link")

        mangaUpdatesLink.postValue(link)

        if (preloadedSeries != null) {
            mangaUpdatesLoaded.postValue(true)
            mangaUpdatesSeries.postValue(preloadedSeries)
        } else {
            val identifier = extractMUIdentifier(link)
            if (identifier.isNotBlank()) {}
        }
    }

    fun removeMangaUpdatesLink(mediaId: Int) {
        val key = "mangaupdates_link_${mediaId}"
        ani.dantotsu.settings.saving.PrefManager.removeCustomVal(key)
        ani.dantotsu.util.Logger.log("MediaDetailsViewModel: Removed MU link for $mediaId")

        mangaUpdatesLink.postValue(null)
        mangaUpdatesSeries.postValue(null)
        mangaUpdatesLoaded.postValue(true)
    }
}
