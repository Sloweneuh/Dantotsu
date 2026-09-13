package ani.dantotsu.connections.chiaki

import ani.dantotsu.Mapper
import ani.dantotsu.okHttpClient
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale

/**
 * chiaki.site, the only public source of a hand-curated *watch order* — the sequence a franchise's
 * TV runs, movies, OVAs and specials are meant to be watched in, which AniList's relation graph
 * can't express. It has no JSON API for it, so the order is scraped from the tool's own page.
 *
 * Everything here is keyed by **MyAnimeList id**: chiaki's `data-id`/`?/tools/watch_order/id/{id}`
 * are MAL ids (verified against entries whose MAL and AniList ids differ). Media without an
 * [ani.dantotsu.media.Media.idMAL] therefore have to go through [resolveMalId] first, which is what
 * the autocomplete route is for.
 */
object Chiaki {
    const val WEB_URL = "https://chiaki.site"

    /** `background-image:url('media/a/c0/466.jpg')` on a row's avatar div. */
    private val BACKGROUND_URL = Regex("""url\(['"]?(.+?)['"]?\)""")

    /** The score out of `★8.73 (1,544,390)`, so unaired entries' `★0 (0)` can be dropped. */
    private val RATING_SCORE = Regex("""[0-9]+(\.[0-9]+)?""")

    /** One suggestion from the autocomplete route — chiaki's id here is the series' MAL id. */
    @Serializable
    data class Suggestion(
        val id: Int = 0,
        val value: String = "",
        val image: String? = null,
        val type: String? = null,
        val year: Int? = null,
    )

    /**
     * One entry of a watch order, in the position chiaki lists it. [meta] is the row's own
     * "aired | format | episodes" line joined for display, kept as one string rather than split
     * into fields so a change to which parts the page prints can't silently shift them.
     */
    data class WatchOrderEntry(
        val malId: Int,
        val anilistId: Int?,
        val title: String,
        val englishTitle: String?,
        val imageUrl: String?,
        val meta: String?,
        val rating: String?,
    )

    /**
     * Series matching [term] via `?/tools/autocomplete_series`. Returns an empty list rather than
     * throwing — a failed lookup should leave the caller without a match, not without a screen.
     */
    suspend fun autocomplete(term: String): List<Suggestion> = withContext(Dispatchers.IO) {
        tryWithSuspend(snackbar = false) {
            val query = URLEncoder.encode(term, "utf-8")
            val request = Request.Builder()
                .url("$WEB_URL/?/tools/autocomplete_series&term=$query")
                .get()
                .build()
            val body = okHttpClient.newCall(request).execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
            if (body.isNullOrBlank()) {
                Logger.log("Chiaki autocomplete[$term]: empty response")
                return@tryWithSuspend emptyList<Suggestion>()
            }
            Mapper.json.decodeFromString<List<Suggestion>>(body)
        } ?: emptyList()
    }

    /**
     * Finds the MAL id for a series from its titles, for media AniList has no `idMal` for.
     *
     * Tries each candidate in order and only accepts an exact (case/punctuation-insensitive) title
     * match, never autocomplete's own ranking: the top suggestion for a short title is regularly a
     * spin-off, and a watch order for the wrong franchise reads as fact. No candidate matching
     * means no result. [year] breaks ties between remakes sharing a title; it is a preference, not
     * a filter — chiaki dates a few entries by production year rather than air year.
     */
    suspend fun resolveMalId(titles: List<String>, year: Int? = null): Int? {
        for (title in titles) {
            val suggestions = autocomplete(title)
            if (suggestions.isEmpty()) continue
            val wanted = title.normalizeTitle()
            val exact = suggestions.filter { it.value.normalizeTitle() == wanted }
            val pick = exact.firstOrNull { year != null && it.year == year }
                ?: exact.firstOrNull()
                ?: suggestions.firstOrNull { year != null && it.year == year }
            if (pick != null && pick.id > 0) return pick.id
        }
        return null
    }

    /**
     * The watch order for the series with this MAL id, in chiaki's listed order. An unknown id
     * answers 404, which surfaces here as an empty list.
     */
    suspend fun getWatchOrder(malId: Int): List<WatchOrderEntry> = withContext(Dispatchers.IO) {
        tryWithSuspend(snackbar = false) {
            val request = Request.Builder()
                .url("$WEB_URL/?/tools/watch_order/id/$malId")
                .get()
                .build()
            val html = okHttpClient.newCall(request).execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
            if (html.isNullOrBlank()) {
                Logger.log("Chiaki watchOrder[$malId]: no page")
                return@tryWithSuspend emptyList<WatchOrderEntry>()
            }
            // A long franchise is a 200KB page, so the scrape stays off the caller's thread.
            parseWatchOrder(html)
        } ?: emptyList()
    }

    /** Split out from [getWatchOrder] so the scrape can be exercised without the network. */
    internal fun parseWatchOrder(html: String): List<WatchOrderEntry> =
        Jsoup.parse(html, WEB_URL).select("#wo_list tr").mapNotNull { row ->
            val malId = row.attr("data-id").toIntOrNull() ?: return@mapNotNull null
            val title = row.selectFirst("span.wo_title")?.text()?.trim()
            if (title.isNullOrEmpty()) return@mapNotNull null

            val image = row.selectFirst("div.wo_avatar_big")?.attr("style")
                ?.let { style -> BACKGROUND_URL.find(style)?.groupValues?.getOrNull(1) }
                ?.let { path -> if (path.startsWith("http")) path else "$WEB_URL/${path.trimStart('/')}" }

            val metaElement = row.selectFirst("span.wo_meta")
            // ownText() drops the rating span and the per-tracker links, leaving only the
            // "date | format | episodes" text nodes the row prints between them.
            val meta = metaElement?.ownText()
                ?.split('|')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.joinToString(" • ")
                ?.takeIf { it.isNotEmpty() }

            WatchOrderEntry(
                malId = malId,
                anilistId = row.attr("data-anilist-id").toIntOrNull(),
                title = title,
                englishTitle = row.selectFirst("span.uk-text-small")?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals(title, ignoreCase = true) },
                imageUrl = image,
                meta = meta,
                // Entries that haven't aired carry "★0 (0)", which is a placeholder, not a score.
                rating = metaElement?.selectFirst("span.wo_rating")?.text()?.trim()
                    ?.takeIf { text ->
                        (RATING_SCORE.find(text)?.value?.toDoubleOrNull() ?: 0.0) > 0.0
                    },
            )
        }

    /** Lowercased and stripped of everything but letters and digits, so punctuation can't miss. */
    private fun String.normalizeTitle(): String =
        lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
