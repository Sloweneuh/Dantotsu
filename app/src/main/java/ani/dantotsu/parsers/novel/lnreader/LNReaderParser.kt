package ani.dantotsu.parsers.novel.lnreader

import android.content.Context
import ani.dantotsu.parsers.Book
import ani.dantotsu.parsers.NovelParser
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A [NovelParser] backed by an LNReader plugin.
 *
 * The important difference from every other novel source in this app is what a "novel" *is*.
 * The older `some.random.*` extensions model one as a set of downloadable EPUB volumes, which is
 * what [loadBook] and the whole download path assume. An LNReader plugin instead models one as a
 * chapter list, where each chapter is a fetched HTML fragment and nothing is downloadable.
 *
 * The two are not interchangeable, so they are not blurred together here: [loadBook] deliberately
 * returns no links (see its note), and the chapter-shaped API lives on [loadNovel] and
 * [loadChapterHtml]. Callers pick a path by checking the parser type; the HTML those calls return
 * is converted to EPUB at one explicit boundary in [LNReaderEpub] so that the reader downstream
 * only ever sees EPUB.
 */
class LNReaderParser(
    private val context: Context,
    val plugin: InstalledLNReaderPlugin,
) : NovelParser() {

    override val name: String = plugin.name
    override val saveName: String = "lnreader_${plugin.id}"
    override val hostUrl: String = plugin.plugin.site
    override val language: String = plugin.plugin.lang

    override val volumeRegex =
        Regex("vol\\.? (\\d+(\\.\\d+)?)|volume (\\d+(\\.\\d+)?)", RegexOption.IGNORE_CASE)

    /**
     * One JS context per plugin, created on first use and kept.
     *
     * Building a context means re-evaluating every shim plus the bundle, which is far too much to
     * repeat per search or per chapter. Access is serialised inside the runtime, so sharing one
     * across coroutines is safe.
     */
    @Volatile private var runtime: LNReaderRuntime? = null

    private val manager: LNReaderPluginManager by lazy { Injekt.get() }

    private fun runtime(): LNReaderRuntime =
        runtime ?: synchronized(this) {
            runtime ?: run {
                val source = manager.sourceOf(plugin.id)
                    ?: throw LNReaderPluginException("${plugin.name} is not installed")
                LNReaderRuntime.load(context, plugin.id, source).also { runtime = it }
            }
        }

    /** Kept as a function so tests and callers can drop the native context deterministically. */
    fun release() {
        synchronized(this) {
            runtime?.close()
            runtime = null
        }
    }

    // -----------------------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------------------

    override suspend fun search(query: String): List<ShowResponse> = runCatching {
        val json = runtime().call(
            "searchNovels",
            JSONArray().put(query).put(1).toString()
        )
        parseNovelItems(json)
    }.getOrElse {
        Logger.log("LNReader search failed on ${plugin.name}: ${it.message}")
        emptyList()
    }

    /**
     * A page of search results.
     *
     * [search] exists to satisfy the parser contract used when matching a media to an entry, where
     * only the first page matters. Browsing pages through results, so it needs the page number.
     */
    suspend fun searchPage(query: String, page: Int): List<ShowResponse> = runCatching {
        parseNovelItems(
            runtime().call("searchNovels", JSONArray().put(query).put(page).toString())
        )
    }.getOrElse {
        Logger.log("LNReader search failed on ${plugin.name}: ${it.message}")
        emptyList()
    }

    /**
     * The plugin's own listing, for browsing rather than searching.
     *
     * @param showLatest asks for recently updated rather than popular; see [supportsLatest], as
     *   plugins that ignore the flag return the same listing either way.
     * @param filtersJson the full filter declaration with user-chosen values, as
     *   [LNReaderFilterSet.valuesJson] produces it. Null means the plugin's own defaults.
     */
    suspend fun popular(
        page: Int = 1,
        showLatest: Boolean = false,
        filtersJson: String? = null,
    ): List<ShowResponse> = runCatching {
        val filters = filtersJson ?: runtime().defaultFiltersJson()
        val args = "[$page, {\"showLatestNovels\": $showLatest, \"filters\": $filters}]"
        parseNovelItems(runtime().call("popularNovels", args))
    }.getOrElse {
        Logger.log("LNReader popular failed on ${plugin.name}: ${it.message}")
        emptyList()
    }

    /**
     * The plugin's filter declaration, or null if it has none.
     *
     * Handed back as JSON rather than as a built [LNReaderFilterSet] because callers need to build
     * more than one from it — the live set the user edits, and a pristine copy to compare against
     * when deciding which filters count as changed.
     */
    fun filtersJson(): String? = runCatching {
        runtime().defaultFiltersJson().takeIf { it.isNotBlank() && it != "{}" }
    }.getOrElse {
        Logger.log("LNReader filters failed on ${plugin.name}: ${it.message}")
        null
    }

    /** Whether "latest" is a distinct listing on this plugin rather than a synonym for popular. */
    fun supportsLatest(): Boolean = runCatching { runtime().supportsLatest() }.getOrDefault(false)

    private fun parseNovelItems(json: String): List<ShowResponse> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val path = o.optString("path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ShowResponse(
                name = o.optString("name").ifBlank { path },
                link = path,
                coverUrl = o.optString("cover").orEmpty(),
            )
        }
    }

    // -----------------------------------------------------------------------------------
    // Chapters — the LNReader-shaped API
    // -----------------------------------------------------------------------------------

    /** Details plus the chapter list for one novel, keyed by the path a search result carried. */
    suspend fun loadNovel(novelPath: String): LNReaderNovel {
        val json = runtime().call("parseNovel", JSONArray().put(novelPath).toString())
        val o = JSONObject(json)
        val chaptersArray = o.optJSONArray("chapters") ?: JSONArray()
        val chapters = (0 until chaptersArray.length()).mapNotNull { i ->
            val c = chaptersArray.optJSONObject(i) ?: return@mapNotNull null
            val path = c.optString("path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LNReaderChapter(
                name = c.optString("name").ifBlank { path },
                path = path,
                releaseTime = c.optString("releaseTime").takeIf { it.isNotBlank() },
                chapterNumber = c.optDouble("chapterNumber").takeIf { !it.isNaN() },
            )
        }
        return LNReaderNovel(
            name = o.optString("name").ifBlank { novelPath },
            path = o.optString("path").ifBlank { novelPath },
            cover = o.optString("cover").takeIf { it.isNotBlank() },
            summary = o.optString("summary").takeIf { it.isNotBlank() },
            author = o.optString("author").takeIf { it.isNotBlank() },
            artist = o.optString("artist").takeIf { it.isNotBlank() },
            genres = o.optString("genres").takeIf { it.isNotBlank() },
            status = o.optString("status").takeIf { it.isNotBlank() },
            chapters = chapters,
        )
    }

    /** The chapter body, as the HTML fragment the plugin produced. */
    suspend fun loadChapterHtml(chapterPath: String): String {
        val raw = runtime().call("parseChapter", JSONArray().put(chapterPath).toString())
        // The bridge hands back JSON, so a string result arrives quoted.
        return runCatching { JSONArray("[$raw]").getString(0) }.getOrDefault(raw)
    }

    /**
     * Absolute URL for a path, for "open in browser" and for resolving relative images.
     *
     * Joining the path to the site root is only a guess — a site can route novels and chapters
     * under different prefixes — so the plugin is asked first through its own `resolveUrl`, and the
     * join is what happens when it declares none.
     *
     * Results are remembered because this is answered by evaluating JavaScript on the plugin's own
     * thread, and the same handful of paths get asked about repeatedly as rows are bound.
     */
    private val resolved = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun resolveUrl(path: String, isNovel: Boolean): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val key = "$isNovel|$path"
        resolved[key]?.let { return it }
        val url = runCatching { runtime().resolveUrl(path, isNovel) }.getOrNull()?.takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        } ?: resolve(path)
        resolved[key] = url
        return url
    }

    /** The plain join, for callers that cannot wait on the plugin. */
    fun resolve(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        else -> hostUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    // -----------------------------------------------------------------------------------

    /**
     * Not supported, and deliberately not faked.
     *
     * [Book] is the downloadable-volume model: a cover, a description and a list of file links the
     * downloader fetches as EPUBs. An LNReader plugin has no such links — its content only exists
     * as per-chapter HTML — so returning a plausible-looking [Book] with empty or invented links
     * would push the failure into the downloader, where it would look like a broken source rather
     * than an unsupported operation. Callers must branch on the parser type and use [loadNovel].
     */
    override suspend fun loadBook(link: String, extra: Map<String, String>?): Book =
        throw UnsupportedOperationException(
            "$name is an LNReader plugin: novels are read as chapters, not downloaded as volumes"
        )
}

/** A novel as an LNReader plugin describes it. */
data class LNReaderNovel(
    val name: String,
    val path: String,
    val cover: String?,
    val summary: String?,
    val author: String?,
    val artist: String?,
    val genres: String?,
    val status: String?,
    val chapters: List<LNReaderChapter>,
) {
    /**
     * Genres as a list.
     *
     * The plugin API declares one string, and sources fill it however their page reads it —
     * comma-separated most often, but slashes and pipes turn up too.
     */
    fun genreList(): List<String> = genres.orEmpty()
        .split(',', '/', '|', '·')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

data class LNReaderChapter(
    val name: String,
    val path: String,
    val releaseTime: String?,
    val chapterNumber: Double?,
)
