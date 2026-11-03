package ani.dantotsu.others

import android.content.Context
import ani.dantotsu.client
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Studio logos, from MyAnimeList: AniList has no pictures of studios, MAL has one for most. Its
 * studio list (one page, every studio with its id) is read once a week and kept on disk; a studio
 * is found on it by name, and its logo is then at a fixed address by that id.
 */
object MalStudios {

    private const val LIST_URL = "https://myanimelist.net/anime/producer"
    private const val FILE = "mal_studios.tsv"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private val mutex = Mutex()
    @Volatile
    private var byName: Map<String, Int>? = null

    /** The logo of the studio called [name], or null when MAL doesn't list one by that name. */
    suspend fun logo(context: Context, name: String): String? {
        val id = ids(context)[key(name)] ?: ids(context)[key(name.removePrefix("Studio ").removePrefix("studio "))]
        return id?.let { "https://cdn.myanimelist.net/images/company/$it.png" }
    }

    private suspend fun ids(context: Context): Map<String, Int> {
        byName?.let { return it }
        return mutex.withLock {
            byName ?: withContext(Dispatchers.IO) { load(context) }.also { byName = it }
        }
    }

    private suspend fun load(context: Context): Map<String, Int> {
        val file = File(context.filesDir, FILE)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
        if (!fresh) runCatching {
            val html = client.get(LIST_URL, mapOf("User-Agent" to USER_AGENT)).text
            // <a href="/anime/producer/569/MAPPA" class="genre-name-link">MAPPA (124)</a>
            val rows = Regex("""/anime/producer/(\d+)/[^"]*"[^>]*>([^<]+?)(?:\s*\(\d[\d,]*\))?</a>""")
                .findAll(html).map { "${it.groupValues[1]}\t${org.jsoup.parser.Parser.unescapeEntities(it.groupValues[2].trim(), false)}" }
                .toList()
            if (rows.size > 100) file.writeText(rows.joinToString("\n"))
        }.onFailure { Logger.log("MalStudios: list fetch failed: ${it.message}") }
        if (!file.exists()) return emptyMap()
        return file.readLines().mapNotNull { line ->
            val (id, name) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            id.toIntOrNull()?.let { key(name) to it }
        }.toMap()
    }

    /** A name as compared: case, spacing and punctuation aside ("A-1 Pictures" is "a1pictures"). */
    private fun key(name: String) = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/111.0.0.0 Safari/537.36"
}
