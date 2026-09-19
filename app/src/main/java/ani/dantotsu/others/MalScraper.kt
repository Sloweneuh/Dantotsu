package ani.dantotsu.others

import ani.dantotsu.client
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.tryWithSuspend
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * MyAnimeList data scraped from the site itself: localized titles and media type, the
 * per-episode list (titles and filler flags), and user reviews.
 *
 * Theme songs used to be scraped here too — they come from AnimeThemes now
 * ([ani.dantotsu.connections.animethemes.AnimeThemes]), which has the songs as data and the
 * sequences as video, where MAL only had a sentence per theme.
 *
 * The episode list and the reviews used to come from Jikan, which is itself only a JSON wrapper
 * around these same pages. With Jikan shutting down they are read straight from MAL — same
 * source, one less hop, and no third-party rate limit. Responses ride the shared 6-hour HTTP
 * cache, so revisiting a media page costs nothing.
 */
object MalScraper {
    private const val SITE_URL = "https://myanimelist.net"

    /** MAL serves the desktop markup these parsers target only to a desktop UA. */
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/111.0.0.0 Safari/537.36"
    )

    suspend fun loadMedia(media: Media) {
        try {
            withTimeout(6000) {
                if (media.anime != null) {
                    val res =
                        client.get("https://myanimelist.net/anime/${media.idMAL}", headers).document
                    val a = res.select(".title-english").text()
                    media.nameMAL = if (a != "") a else res.select(".title-name").text()
                    media.typeMAL =
                        if (res.select("div.spaceit_pad > a")
                                .isNotEmpty()
                        ) res.select("div.spaceit_pad > a")[0].text() else null
                } else {
                    val res =
                        client.get("https://myanimelist.net/manga/${media.idMAL}", headers).document
                    val b = res.select(".title-english").text()
                    val a = res.select(".h1-title").text().removeSuffix(b)
                    media.nameMAL = a
                    media.typeMAL =
                        if (res.select("div.spaceit_pad > a")
                                .isNotEmpty()
                        ) res.select("div.spaceit_pad > a")[0].text() else null
                }
            }
        } catch (e: Exception) {
            // if (e is TimeoutCancellationException) snackString(currContext()?.getString(R.string.error_loading_mal_data))
        }
    }

    private const val EPISODES_PER_PAGE = 100

    /** ~2500 episodes. Past that it is a title nobody tracks episode-by-episode anyway. */
    private const val MAX_EPISODE_PAGES = 25

    /** Boruto. Personal revenge with 34566 :prayge: */
    private const val ALL_FILLER_MAL_ID = 34566

    /**
     * Episode titles and filler flags keyed by episode number — the same shape, and the same
     * keys, the watch tab used to merge from Jikan.
     *
     * Paginated 100 at a time. A page that fails keeps the pages already collected rather than
     * dropping the whole map, so a long-running series still gets most of its titles.
     */
    suspend fun getEpisodes(malId: Int): Map<String, Episode> {
        val eps = mutableMapOf<String, Episode>()
        var offset = 0
        var page = 0
        while (page < MAX_EPISODE_PAGES) {
            val doc = tryWithSuspend(snackbar = false) {
                client.get("$SITE_URL/anime/$malId/_/episode?offset=$offset", headers).document
            } ?: break

            val rows = doc.select("tr.episode-list-data")
            if (rows.isEmpty()) break

            for (row in rows) {
                val numberCell = row.selectFirst("td.episode-number") ?: continue
                val number = numberCell.attr("data-raw").ifBlank { numberCell.text() }.trim()
                if (number.isEmpty()) continue
                val titleCell = row.selectFirst("td.episode-title")
                // MAL badges filler and recap episodes in the title cell.
                val tag = titleCell?.selectFirst("span.icon-episode-type-bg")?.text()?.trim()
                eps[number] = Episode(
                    number,
                    title = titleCell?.selectFirst("a")?.text()?.trim()?.takeIf { it.isNotEmpty() },
                    filler = malId == ALL_FILLER_MAL_ID || tag.equals("Filler", true),
                )
            }

            page++
            if (rows.size < EPISODES_PER_PAGE) break
            offset += EPISODES_PER_PAGE
            delay(300) // be a good guest on someone else's HTML
        }
        return eps
    }

    suspend fun getAnimeReviews(malId: Int, page: Int = 1): MALReviewsPage? =
        getReviews("anime", malId, page)

    suspend fun getMangaReviews(malId: Int, page: Int = 1): MALReviewsPage? =
        getReviews("manga", malId, page)

    private suspend fun getReviews(type: String, malId: Int, page: Int): MALReviewsPage? =
        tryWithSuspend(snackbar = false) {
            val res = client.get("$SITE_URL/$type/$malId/_/reviews?p=$page", headers)
            if (!res.isSuccessful) return@tryWithSuspend null
            val doc = res.document
            MALReviewsPage(
                reviews = doc.select("div.review-element").mapNotNull { parseReview(it) },
                // The "More Reviews" link is rendered only while another page exists.
                hasNextPage = doc.selectFirst("a[data-ga-click-type=review-more-reviews]") != null
            )
        }

    private fun parseReview(el: Element): MALReview? {
        val permalink = el.selectFirst("div.open a[href]")?.attr("href")?.takeIf { it.isNotBlank() }
        val username = el.selectFirst("div.username a")?.text()?.trim()?.takeIf { it.isNotEmpty() }

        // The listing clips long reviews: the tail sits in a hidden span, and a `js-visible` span
        // holds the "..." joining the two halves — drop that, keep both halves.
        val body = el.selectFirst("div.text")?.clone()?.also {
            it.select("span.js-visible").remove()
        }?.toPlainText()?.takeIf { it.isNotEmpty() }

        if (username == null && body == null) return null

        val avatar = el.selectFirst("div.thumb img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.takeIf { it.isNotBlank() }

        return MALReview(
            id = permalink?.let { REVIEW_ID.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0,
            username = username,
            avatarUrl = avatar,
            dateUnix = el.selectFirst("div.update_at")?.let {
                parseReviewDate(it.text().trim(), it.attr("title").trim())
            },
            score = el.selectFirst("div.rating span.num")?.text()?.trim()?.toIntOrNull(),
            review = body,
            url = permalink
        )
    }

    private val REVIEW_ID = Regex("id=([0-9]+)")
    private val BR_TAG = Regex("(?i)<br[^>]*>")

    /** Review bodies are HTML with `<br>` line breaks; both screens want flat text with newlines. */
    private fun Element.toPlainText(): String =
        Jsoup.parseBodyFragment(html().replace(BR_TAG, "\n")).body().wholeText().trim()

    /**
     * MAL prints the day in the cell ("Jan 25, 2010") and the time of day in its `title`
     * ("7:34 AM"). The time matters because a same-day review renders as "x hours ago".
     */
    private fun parseReviewDate(date: String, time: String): Int? {
        if (date.isEmpty()) return null
        return parseDate("MMM d, yyyy h:mm a", "$date $time") ?: parseDate("MMM d, yyyy", date)
    }

    private fun parseDate(pattern: String, value: String): Int? = try {
        SimpleDateFormat(pattern, Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }.parse(value)?.let { (it.time / 1000L).toInt() }
    } catch (_: Exception) {
        null
    }
}

data class MALReviewsPage(
    val reviews: List<MALReview>,
    val hasNextPage: Boolean
)

data class MALReview(
    val id: Int,
    val username: String?,
    val avatarUrl: String?,
    val dateUnix: Int?,
    val score: Int?,
    val review: String?,
    val url: String?
) : java.io.Serializable
