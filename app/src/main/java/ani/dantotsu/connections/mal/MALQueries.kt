package ani.dantotsu.connections.mal

import ani.dantotsu.client
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.serialization.Serializable

class MALQueries {

        /**
         * Batch fetch MAL manga or anime details by a list of MAL IDs (for recommendations, etc.)
         * @param ids List of MAL IDs
         * @param isAnime true for anime, false for manga
         */
        suspend fun getDetailsBatch(ids: List<Int>, isAnime: Boolean): List<Any?> {
            if (ids.isEmpty()) return emptyList()
            return ids.map { id ->
                try {
                    if (isAnime) getAnimeDetails(id) else getMangaDetails(id)
                } catch (e: Exception) {
                    null
                }
            }
        }
    private val apiUrl = "https://api.myanimelist.net/v2"
    private val authHeader: Map<String, String>?
        get() {
            return mapOf("Authorization" to "Bearer ${MAL.token ?: return null}")
        }

    /**
     * Every MAL v2 request needs either a user token or the app's own client-id — this is for the
     * public, no-login browsing (search + the standalone media page): falls back to the client-id
     * header when nobody's signed in. Never used for user-list endpoints (those still gate on
     * [authHeader] being non-null and skip the request entirely when it's not, rather than firing
     * an anonymous request that MAL would just reject).
     */
    private val publicHeader: Map<String, String>
        get() = authHeader ?: mapOf("X-MAL-CLIENT-ID" to MAL.clientId)

    @Serializable
    data class MalUser(
        val id: Int,
        val name: String,
        val picture: String?,
    )

    suspend fun getUserData(): Boolean {
        val res = tryWithSuspend {
            client.get(
                "$apiUrl/users/@me?fields=picture",
                authHeader ?: return@tryWithSuspend null
            ).parsed<MalUser>()
        } ?: return false
        MAL.userid = res.id
        MAL.username = res.name
        MAL.avatar = res.picture
        PrefManager.setVal(PrefName.MALUserName, res.name)

        return true
    }

    suspend fun editList(
        idMAL: Int?,
        isAnime: Boolean,
        progress: Int?,
        score: Int?,
        status: String,
        rewatch: Int? = null,
        volume: Int? = null,
        start: FuzzyDate? = null,
        end: FuzzyDate? = null,
        force: Boolean = false
    ) {
        if (idMAL == null) return
        // `force` bypasses the toggle for explicit user actions (e.g. the list-compare screen).
        if (!force && !PrefManager.getVal<Boolean>(PrefName.MalListSyncEnabled)) return
        // Checked here rather than only at the request, the way MangaBaka and MangaUpdates do it.
        // The toggle syncs between devices but the login doesn't, so "on but signed out" is an
        // ordinary state — it used to build the whole payload and then vanish into a null header,
        // leaving nothing in the log to say why nothing happened.
        if (authHeader == null) {
            Logger.log("MAL: list sync is on but this device isn't signed in; skipping")
            return
        }
        val data = mutableMapOf("status" to convertStatus(isAnime, status))
        if (progress != null)
            data[if (isAnime) "num_watched_episodes" else "num_chapters_read"] = progress.toString()
        if (volume != null)
            data["num_volumes_read"] = volume.toString()
        data[if (isAnime) "is_rewatching" else "is_rereading"] = (status == "REPEATING").toString()
        if (score != null)
            data["score"] = score.div(10).toString()
        if (rewatch != null)
            data[if (isAnime) "num_times_rewatched" else "num_times_reread"] = rewatch.toString()
        if (start != null)
            data["start_date"] = start.toMALString()
        if (end != null)
            data["finish_date"] = end.toMALString()
        tryWithSuspend {
            client.put(
                "$apiUrl/${if (isAnime) "anime" else "manga"}/$idMAL/my_list_status",
                authHeader ?: return@tryWithSuspend null,
                data = data,
            )
        }
    }

    suspend fun deleteList(isAnime: Boolean, idMAL: Int?, force: Boolean = false) {
        if (idMAL == null) return
        if (!force && !PrefManager.getVal<Boolean>(PrefName.MalListSyncEnabled)) return
        if (authHeader == null) {
            Logger.log("MAL: list sync is on but this device isn't signed in; skipping delete")
            return
        }
        tryWithSuspend {
            client.delete(
                "$apiUrl/${if (isAnime) "anime" else "manga"}/$idMAL/my_list_status",
                authHeader ?: return@tryWithSuspend null
            )
        }
    }

    /**
     * Fetches the authenticated user's full MAL list for the given type, following pagination.
     * Returns the raw entries (media node + list_status). Empty when logged out or on failure.
     */
    suspend fun getUserList(isAnime: Boolean): List<MALListNode> {
        val header = authHeader ?: return emptyList()
        val type = if (isAnime) "animelist" else "mangalist"
        // Request MAL's own totals too (num_episodes / num_chapters,num_volumes) so the comparison can
        // clamp progress to MAL's cap — it refuses counts beyond a finished title's total.
        val fields = if (isAnime) "list_status,main_picture,num_episodes"
        else "list_status,main_picture,num_chapters,num_volumes"
        val result = mutableListOf<MALListNode>()
        var url: String? =
            "$apiUrl/users/@me/$type?fields=$fields&limit=1000&nsfw=true"
        var guard = 0
        while (url != null && guard++ < 50) {
            val page = tryWithSuspend {
                client.get(url!!, header).parsed<MALListResponse>()
            } ?: break
            result += page.data
            url = page.paging?.next
        }
        return result
    }

    internal fun convertStatus(isAnime: Boolean, status: String): String {
        return when (status) {
            "PLANNING" -> if (isAnime) "plan_to_watch" else "plan_to_read"
            "COMPLETED" -> "completed"
            "PAUSED" -> "on_hold"
            "DROPPED" -> "dropped"
            "CURRENT" -> if (isAnime) "watching" else "reading"
            else -> if (isAnime) "watching" else "reading"

        }
    }

    suspend fun getAnimeDetails(malId: Int): MALAnimeResponse? {
        return tryWithSuspend {
            val fields = "id,title,main_picture,alternative_titles,start_date,end_date,synopsis," +
                    "mean,rank,popularity,num_list_users,num_scoring_users,nsfw,created_at,updated_at," +
                    "media_type,status,genres,num_episodes,start_season,broadcast,source," +
                    "average_episode_duration,rating,pictures,background,related_anime,related_manga," +
                    "recommendations,studios,statistics"

            client.get(
                "$apiUrl/anime/$malId?fields=$fields",
                publicHeader, cacheTime = 0,
            ).parsed<MALAnimeResponse>()
        }
    }

    suspend fun getMangaDetails(malId: Int): MALMangaResponse? {
        return tryWithSuspend {
            val fields = "id,title,main_picture,alternative_titles,start_date,end_date,synopsis," +
                    "mean,rank,popularity,num_list_users,num_scoring_users," +
                    "media_type,status,genres,num_volumes,num_chapters,authors{first_name,last_name}," +
                    "recommendations,serialization{name}"

            client.get(
                "$apiUrl/manga/$malId?fields=$fields",
                publicHeader, cacheTime = 0,
            ).parsed<MALMangaResponse>()
        }
    }

    /**
     * Anime/manga search via the official v2 API — public (client-id fallback), no login needed.
     * Unlike Jikan, this endpoint supports only `q`/`limit`/`offset` — no genre, status, rating or
     * sort filters exist server-side, so the MAL search screen doesn't offer a filter sheet.
     *
     * A blank [query] falls back to `/{anime|manga}/ranking` (`ranking_type=all`) — the search
     * endpoint itself rejects an empty `q` with a 400, but the website shows a default ranked
     * listing rather than nothing when you land on the search page without typing, so this does
     * too. Each ranked edge carries an extra `ranking` object [MALSearchResponse] doesn't declare;
     * `ignoreUnknownKeys` just drops it, so the one response model serves both shapes.
     */
    suspend fun search(isAnime: Boolean, query: String?, page: Int, limit: Int = 20): MALSearchResponse? {
        val q = query?.trim()?.takeIf { it.isNotBlank() }
        val kind = if (isAnime) "anime" else "manga"
        val fields = "id,title,main_picture,synopsis,mean,media_type,status,num_episodes,num_chapters,start_season"
        val offset = (page - 1) * limit
        val url = if (q != null) {
            "$apiUrl/$kind?q=${java.net.URLEncoder.encode(q, "UTF-8")}&limit=$limit&offset=$offset&fields=$fields"
        } else {
            "$apiUrl/$kind/ranking?ranking_type=all&limit=$limit&offset=$offset&fields=$fields"
        }
        return tryWithSuspend {
            client.get(url, publicHeader, cacheTime = 0).parsed<MALSearchResponse>()
        }
    }

    /**
     * Scrape interest stacks from the MAL media page since the public API does not expose stacks.
     * Returns a list of MALStack containing URL, covers, name and number of entries.
     */
    suspend fun getStacks(malId: Int, isAnime: Boolean): List<MALStack> {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/111.0.0.0 Safari/537.36"
            )

            // first fetch the main media page to find the full stacks URL (with slug)
            val mainPageUrl = "https://myanimelist.net/${if (isAnime) "anime" else "manga"}/$malId"
            val mainDoc = client.get(mainPageUrl, headers).document

            var baseStacksUrl = "https://myanimelist.net/${if (isAnime) "anime" else "manga"}/$malId/stacks"
            // try to find an anchor that contains the malId and stacks (this typically contains the slug)
            val moreStacksEl = mainDoc.select("a[href]").firstOrNull { el ->
                val href = el.attr("href")
                href.contains("/${if (isAnime) "anime" else "manga"}/$malId/") && href.contains("/stacks")
            }
            if (moreStacksEl != null) {
                var href = moreStacksEl.attr("href")
                if (href.startsWith("/")) href = "https://myanimelist.net$href"
                else if (!href.startsWith("http")) href = "https://myanimelist.net/$href"
                baseStacksUrl = href
            }
            val stacks = mutableListOf<MALStack>()
            var offset = 0
            val perPage = 20
            var totalCount: Int? = null
            var safetyCounter = 0

            while (true) {
                // build page URL (include offset param when > 0)
                val url = if (offset == 0) baseStacksUrl else "$baseStacksUrl?offset=$offset"
                val doc = client.get(url, headers).document

                // try to read total count from page (if present)
                if (totalCount == null) {
                    totalCount = doc.selectFirst("#total-count")?.attr("data-total")?.toIntOrNull()
                        ?: Regex("(\\d+)\\s*Stacks", RegexOption.IGNORE_CASE).find(doc.title())?.groups?.get(1)?.value?.toIntOrNull()
                }

                val blocks = doc.select(".column-item")
                for (el in blocks) {
                    val link = el.selectFirst("a")?.attr("href") ?: continue
                    val covers = el.select(".img .edge img").mapNotNull { it.attr("src") }
                    val name = el.selectFirst(".detail .title a")?.text()?.trim() ?: ""
                    val statText = el.selectFirst(".detail .foot .stat")?.text() ?: ""
                    val entries = Regex("(\\d+)\\s*Entries", RegexOption.IGNORE_CASE).find(statText)?.groups?.get(1)?.value?.toIntOrNull()
                        ?: Regex("(\\d+)").find(statText)?.groups?.get(1)?.value?.toIntOrNull() ?: 0
                    val description = el.selectFirst(".detail .text")?.html()?.trim()
                    stacks.add(MALStack(url = link, covers = covers, name = name, entries = entries, description = description))
                }

                // stop if we've got all known stacks
                if (totalCount != null && stacks.size >= totalCount) break

                // if there's a next page link use its offset, else try to detect via rel=next
                val nextLink = doc.selectFirst("link[rel=next]") ?: doc.selectFirst(".pagination a.link:not(.current)")
                if (nextLink == null) break

                val href = nextLink.attr("href")
                val nextOffset = Regex("[?&]offset=(\\d+)").find(href)?.groups?.get(1)?.value?.toIntOrNull()
                offset = if (nextOffset != null && nextOffset > offset) nextOffset else offset + perPage

                // safety guard to prevent infinite loops
                safetyCounter++
                if (safetyCounter > 100) break
            }

            stacks
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fetch just the title of a MAL interest stack from its page.
     */
    suspend fun getStackName(stackUrl: String): String? = getStackNameAndDescription(stackUrl).first

    /**
     * Fetch both the title and description of a MAL interest stack from its page in a single
     * HTTP request. Returns a Pair of (name, description) where either can be null.
     */
    suspend fun getStackNameAndDescription(stackUrl: String): Pair<String?, String?> {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/111.0.0.0 Safari/537.36"
            )
            var url = stackUrl
            if (url.startsWith("/")) url = "https://myanimelist.net$url"
            else if (!url.startsWith("http")) url = "https://myanimelist.net/$url"
            val doc = client.get(url, headers).document
            // Title: try h2.title first, then h1, then <title> tag
            val name = doc.selectFirst("h2.title")?.text()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
                ?: doc.title().substringBefore(" - MyAnimeList.net").takeIf { it.isNotBlank() }
            // Description: use the same .introduction selector as the stack list page
            val description = doc.selectFirst(".introduction")?.html()?.trim()?.takeIf { it.isNotBlank() }
            Pair(name, description)
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    /**
     * Given a stack page URL (full or relative), scrape the stack and return the list of MAL IDs
     * for the media contained in that stack (order preserved). This will be used to resolve to
     * AniList media via batch lookup.
     */
    suspend fun getStackEntries(stackUrl: String): List<MALStackEntry> {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/111.0.0.0 Safari/537.36"
            )

            var baseUrl = stackUrl
            if (baseUrl.startsWith("/")) baseUrl = "https://myanimelist.net$baseUrl"
            else if (!baseUrl.startsWith("http")) baseUrl = "https://myanimelist.net/$baseUrl"

            val ids = mutableListOf<MALStackEntry>()
            var offset = 0
            val perPage = 20
            var safety = 0

            // request the list view which contains the .list-anime-list container
            val listBase = if (baseUrl.contains("?")) "$baseUrl&view_style=list" else "$baseUrl?view_style=list"

            while (true) {
                val url = if (offset == 0) listBase else "$listBase&offset=$offset"
                val doc = client.get(url, headers).document

                // prefer the .list-anime-list container (list view for stacks)
                val container = doc.selectFirst(".list-anime-list")
                if (container != null) {
                    val blocks = container.select(".seasonal-anime, .seasonal-anime.js-seasonal-anime")
                    for (el in blocks) {
                        // prefer explicit title link or image link
                        val candidates = el.select(".title a[href], .image a[href], a[href]")
                        var found: Int? = null
                        var introText: String? = null
                        for (cand in candidates) {
                            val href = cand.attr("href")
                            // try direct anime/manga link
                            val directId = Regex("/(?:anime|manga)/(\\d+)").find(href)?.groups?.get(1)?.value?.toIntOrNull()
                            if (directId != null) {
                                found = directId
                                break
                            }
                            // try ownlist/add link pattern with selected_series_id
                            val selectedId = Regex("[?&]selected_series_id=(\\d+)").find(href)?.groups?.get(1)?.value?.toIntOrNull()
                            if (selectedId != null) {
                                found = selectedId
                                break
                            }
                        }
                        // extract intro text if present in list view
                        introText = el.selectFirst(".intro")?.text()?.trim()
                        if (found != null && ids.none { it.id == found }) {
                            ids.add(MALStackEntry(id = found, intro = introText))
                        }
                    }
                } else {
                    // fallback: try any .seasonal-anime that contains a link to /anime/ or /manga/
                    val fallback = doc.select(".seasonal-anime")
                    for (el in fallback) {
                        val link = el.selectFirst("a[href]")?.attr("href") ?: continue
                        Regex("/(anime|manga)/(\\d+)").find(link)?.groups?.get(2)?.value?.toIntOrNull()?.let {
                            val introText = el.selectFirst(".intro")?.text()?.trim()
                            ids.add(MALStackEntry(id = it, intro = introText))
                        }
                    }
                }

                // detect next page
                val nextLink = doc.selectFirst("link[rel=next]") ?: doc.selectFirst(".pagination a.link:not(.current)")
                if (nextLink == null) break
                val href = nextLink.attr("href")
                val nextOffset = Regex("[?&]offset=(\\d+)").find(href)?.groups?.get(1)?.value?.toIntOrNull()
                offset = if (nextOffset != null && nextOffset > offset) nextOffset else offset + perPage

                safety++
                if (safety > 100) break
            }

            ids
        } catch (e: Exception) {
            emptyList()
        }
    }

}