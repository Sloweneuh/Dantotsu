package ani.dantotsu.parsers

import android.graphics.drawable.Drawable
import ani.dantotsu.FileUrl
import ani.dantotsu.R
import ani.dantotsu.currContext
import ani.dantotsu.media.Media
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Serializable
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.system.measureTimeMillis


abstract class BaseParser {

    /**
     * Name that will be shown in Source Selection
     * **/
    open val name: String = ""

    /**
     * Name used to save the ShowResponse selected by user or by autoSearch
     * **/
    open val saveName: String = ""

    /**
     * The main URL of the Site
     * **/
    open val hostUrl: String = ""

    /**
     * override as `true` if the site **only** has NSFW media
     * **/
    open val isNSFW = false

    /**
     * mostly redundant for official app, But override if you want to add different languages
     * **/
    open val language = "English"

    /**
     * Icon of the site, can be null
     */
    open val icon: Drawable? = null

    /**
     *  Search for Anime/Manga/Novel, returns a List of Responses
     *
     *  use `encode(query)` to encode the query for making requests
     * **/
    abstract suspend fun search(query: String): List<ShowResponse>

    /**
     * Finds the source's entry for an AniList media on its own — what the read/watch screen calls
     * before it can list any chapters or episodes.
     *
     * Searches each title the media is known by, best first, and takes the highest-scoring result
     * across all of them ([SourceMatcher] decides what scores well and what is too weak to use), so a
     * source that only carries the romaji spelling, or names the entry after a synonym, is still
     * found. Returns null when nothing matched well enough — the caller then shows "not found" rather
     * than an entry that looks right and isn't.
     *
     * Once found, the entry is saved under [saveName] and reused, so this only runs the first time a
     * media is opened on a source, or after the user picks a different entry by hand.
     *
     * Isn't necessary to override, but recommended, if you want to improve auto search results
     * **/
    open suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        applySourceLanguage(mediaObj)

        val saved = loadSavedShowResponse(mediaObj.id)
        if (saved != null) {
            if (this !is OfflineMangaParser && this !is OfflineAnimeParser) {
                saveShowResponse(mediaObj.id, saved, true)
            }
            return saved
        }

        val targets = SourceMatcher.targets(mediaObj)
        var best: SourceMatcher.Match? = null
        var answered = false
        var failure: Throwable? = null

        // Every title AniList knows the media by is a query worth trying, since a source indexes only
        // the spelling its own site uses — but each one is a round trip, so they are tried best-first
        // and stopped as soon as a candidate is good enough to not be improved on.
        for (query in SourceMatcher.queries(mediaObj).take(SourceMatcher.MAX_QUERIES)) {
            setUserText("Searching : $query")
            Logger.log("[$name] searching : $query")
            val results = try {
                search(query)
            } catch (e: Throwable) {
                // One query failing shouldn't hide the others; a run where *nothing* answered is
                // reported below, so a broken source still surfaces as an error and not as no match.
                failure = failure ?: e
                Logger.log(e)
                continue
            }
            answered = true
            val match = SourceMatcher.best(results, targets)
            Logger.log(
                "[$name] \"$query\" -> ${match?.response?.name ?: "nothing"}" +
                    " (score ${match?.score ?: 0}, ${results.size} results)"
            )
            if (match != null && match.score > (best?.score ?: -1)) best = match
            if ((best?.score ?: 0) >= SourceMatcher.CONFIDENT) break
        }

        if (!answered && failure != null) throw failure

        val response = best?.takeIf { it.score >= SourceMatcher.ACCEPT }?.response
        if (response == null) {
            Logger.log(
                "[$name] no match for ${mediaObj.mainName()}" +
                    " (best ${best?.response?.name ?: "none"} scored ${best?.score ?: 0})"
            )
        }
        saveShowResponse(mediaObj.id, response)
        return response
    }

    /**
     * Points the parser at the language the chapters/episodes should come from, before searching.
     *
     * Two things have to hold. A media nobody picked a language for searches English, because a
     * source's first entry is whatever language the extension happens to list first — which is how
     * chapter lists used to arrive in a language nobody asked for. But a language the user *did* pick
     * has to survive: the read/watch screen sets it on the parser and persists it as
     * [ani.dantotsu.media.Selected.langIndex] before triggering the load that lands here, so
     * overwriting it with English is what made the language dropdown look like it did nothing.
     *
     * Neither value distinguishes "picked the first entry" from "never picked", so index 0 is read as
     * unset — the case that costs is a user deliberately choosing the first entry on an extension
     * that also carries English, where this still lands on English as it did before.
     */
    private fun applySourceLanguage(mediaObj: Media) {
        val selected = mediaObj.selected?.langIndex
        when (this) {
            is DynamicMangaParser ->
                this.sourceLanguage =
                    resolveLanguage(extension.sources.map { it.lang }, selected, this.sourceLanguage)

            is DynamicAnimeParser ->
                this.sourceLanguage =
                    resolveLanguage(extension.sources.map { it.lang }, selected, this.sourceLanguage)
        }
    }

    private fun resolveLanguage(langs: List<String>, selected: Int?, current: Int): Int {
        listOf(selected, current).firstOrNull { it != null && it > 0 && it in langs.indices }
            ?.let { return it }
        val english = langs.indexOfFirst {
            val code = it.lowercase()
            code == "en" || code.startsWith("en") || code.contains("english")
        }
        return if (english != -1) english else selected?.takeIf { it in langs.indices } ?: 0
    }

    /**
     * ping the site to check if it's working or not.
     * @return Triple<Int, Int?, String> : First Int is the status code, Second Int is the response time in milliseconds, Third String is the response message.
     */
    fun ping(): Triple<Int, Int?, String> {
        val client = OkHttpClient()
        var statusCode = 0
        var responseTime: Int? = null
        var responseMessage = ""
        println("Pinging $name at $hostUrl")
        try {
            val request = Request.Builder()
                .url(hostUrl)
                .build()
            responseTime = measureTimeMillis {
                client.newCall(request).execute().use { response ->
                    statusCode = response.code
                    responseMessage = response.message.ifEmpty { "None" }
                }
            }.toInt()
        } catch (e: Exception) {
            Logger.log("Failed to ping $name")
            statusCode = -1
            responseMessage = if (e.message.isNullOrEmpty()) "None" else e.message!!
            Logger.log(e)
        }
        return Triple(statusCode, responseTime, responseMessage)
    }

    /**
     * Used to get an existing Search Response which was selected by the user.
     * @param mediaId : The mediaId of the Media object.
     * @return ShowResponse? : The ShowResponse object if found, else null.
     */
    open suspend fun loadSavedShowResponse(mediaId: Int): ShowResponse? {
        checkIfVariablesAreEmpty()
        return SavedShowResponse.load(mediaId, saveName)
    }

    /**
     * Used to save Shows Response using `saveName`.
     * @param mediaId : The mediaId of the Media object.
     * @param response : The ShowResponse object to save.
     * @param selected : Boolean : If the ShowResponse was selected by the user or not.
     */
    open suspend fun saveShowResponse(mediaId: Int, response: ShowResponse?, selected: Boolean = false) {
        checkIfVariablesAreEmpty()
        if (response != null) {
            setUserText(
                "${
                    if (selected) currContext()!!.getString(R.string.selected) else currContext()!!.getString(
                        R.string.found
                    )
                } : ${response.name}"
            )
            // Run on IO dispatcher for thread safety
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SavedShowResponse.save(mediaId, saveName, response)
            }
        }
    }

    fun checkIfVariablesAreEmpty() {
        if (hostUrl.isEmpty()) throw UninitializedPropertyAccessException("Cannot find any installed extensions")
        if (name.isEmpty()) throw UninitializedPropertyAccessException("Cannot find any installed extensions")
        if (saveName.isEmpty()) throw UninitializedPropertyAccessException("Cannot find any installed extensions")
    }

    open var showUserText = ""
    open var showUserTextListener: ((String) -> Unit)? = null

    /**
     * Used to show messages & errors to the User, a useful way to convey what's currently happening or what was done.
     * **/
    fun setUserText(string: String) {
        showUserText = string
        showUserTextListener?.invoke(showUserText)
    }

    fun encode(input: String): String = URLEncoder.encode(input, "utf-8").replace("+", "%20")
    fun decode(input: String): String = URLDecoder.decode(input, "utf-8")

    val defaultImage = "https://s4.anilist.co/file/anilistcdn/media/manga/cover/medium/default.jpg"
}


/**
 * A single show which contains some episodes/chapters which is sent by the site using their search function.
 *
 * You might wanna include `otherNames` & `total` too, to further improve user experience.
 *
 * You can also store a Map of Strings if you want to save some extra data.
 * **/
data class ShowResponse(
    val name: String,
    val link: String,
    val coverUrl: FileUrl,

    //would be Useful for custom search, ig
    val otherNames: List<String> = listOf(),

    //Total number of Episodes/Chapters in the show.
    val total: Int? = null,

    //In case you want to sent some extra data
    val extra: MutableMap<String, String>? = null,

    //SAnime object from Aniyomi
    val sAnime: SAnime? = null,

    //SManga object from Aniyomi
    val sManga: SManga? = null
) : Serializable {
    constructor(
        name: String,
        link: String,
        coverUrl: String,
        otherNames: List<String> = listOf(),
        total: Int? = null,
        extra: MutableMap<String, String>? = null
    ) : this(name, link, FileUrl(coverUrl), otherNames, total, extra)

    constructor(
        name: String,
        link: String,
        coverUrl: String,
        otherNames: List<String> = listOf(),
        total: Int? = null
    ) : this(name, link, FileUrl(coverUrl), otherNames, total)

    constructor(name: String, link: String, coverUrl: String, otherNames: List<String> = listOf())
            : this(name, link, FileUrl(coverUrl), otherNames)

    constructor(name: String, link: String, coverUrl: String)
            : this(name, link, FileUrl(coverUrl))

    constructor(name: String, link: String, coverUrl: String, sAnime: SAnime)
            : this(name, link, FileUrl(coverUrl), sAnime = sAnime)

    constructor(name: String, link: String, coverUrl: String, sManga: SManga)
            : this(name, link, FileUrl(coverUrl), sManga = sManga)

    companion object {
        private const val serialVersionUID = 1L
    }
}


