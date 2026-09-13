package ani.dantotsu.connections.kuroiru

import ani.dantotsu.Mapper
import ani.dantotsu.okHttpClient
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request

/**
 * kuroiru.co's read-only anime endpoint, used here only for its curated news feed — announcements,
 * PVs, delays and broadcast changes for a single show, which AniList carries none of.
 *
 * Keyed by **MyAnimeList id** (an AniList id answers 404), so callers without an
 * [ani.dantotsu.media.Media.idMAL] have to resolve one first — see
 * [ani.dantotsu.connections.chiaki.Chiaki.resolveMalId].
 */
object Kuroiru {
    private const val API_URL = "https://kuroiru.co/api"

    /**
     * One news item. [title] arrives HTML-escaped (`&quot;`, `&amp;`) and [time] is a Unix time in
     * **seconds**; both are left as the server sends them and normalized at the call site.
     */
    @Serializable
    data class News(
        val title: String = "",
        val link: String = "",
        val time: Long = 0L,
    )

    @Serializable
    private data class AnimeResponse(val news: List<News>? = null)

    /**
     * News for the anime with this MAL id, newest first. Empty when the show has no news, the id
     * is unknown (the route answers an HTML 404 page) or the request failed.
     */
    suspend fun getNews(malId: Int): List<News> = withContext(Dispatchers.IO) {
        tryWithSuspend(snackbar = false) {
            val request = Request.Builder()
                .url("$API_URL/anime/$malId")
                .get()
                .build()
            val body = okHttpClient.newCall(request).execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
            if (body.isNullOrBlank()) {
                Logger.log("Kuroiru news[$malId]: no body")
                return@tryWithSuspend emptyList<News>()
            }
            // The route answers the whole show, news being one field of it, so the decode is
            // bigger than what comes back here and belongs off the caller's thread.
            Mapper.json.decodeFromString<AnimeResponse>(body).news
                .orEmpty()
                .filter { it.link.isNotBlank() && it.title.isNotBlank() }
                .sortedByDescending { it.time }
        } ?: emptyList()
    }
}
