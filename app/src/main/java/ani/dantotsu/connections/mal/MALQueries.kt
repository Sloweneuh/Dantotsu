package ani.dantotsu.connections.mal

import ani.dantotsu.client
import ani.dantotsu.connections.anilist.api.FuzzyDate
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

    @Serializable
    data class MalUser(
        val id: Int,
        val name: String,
        val picture: String?,
    )

    suspend fun getUserData(): Boolean {
        val res = tryWithSuspend {
            client.get(
                "$apiUrl/users/@me",
                authHeader ?: return@tryWithSuspend null
            ).parsed<MalUser>()
        } ?: return false
        MAL.userid = res.id
        MAL.username = res.name
        MAL.avatar = res.picture

        return true
    }

    suspend fun editList(
        idMAL: Int?,
        isAnime: Boolean,
        progress: Int?,
        score: Int?,
        status: String,
        rewatch: Int? = null,
        start: FuzzyDate? = null,
        end: FuzzyDate? = null
    ) {
        if (idMAL == null) return
        val data = mutableMapOf("status" to convertStatus(isAnime, status))
        if (progress != null)
            data[if (isAnime) "num_watched_episodes" else "num_chapters_read"] = progress.toString()
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

    suspend fun deleteList(isAnime: Boolean, idMAL: Int?) {
        if (idMAL == null) return
        tryWithSuspend {
            client.delete(
                "$apiUrl/${if (isAnime) "anime" else "manga"}/$idMAL/my_list_status",
                authHeader ?: return@tryWithSuspend null
            )
        }
    }

    private fun convertStatus(isAnime: Boolean, status: String): String {
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
                authHeader ?: emptyMap()
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
                authHeader ?: emptyMap()
            ).parsed<MALMangaResponse>()
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
                    val description = el.selectFirst(".detail .text")?.text()?.trim()
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
     * Given a stack page URL (full or relative), scrape the stack and return the list of MAL IDs
     * for the media contained in that stack (order preserved). This will be used to resolve to
     * AniList media via batch lookup.
     */
    suspend fun getStackEntries(stackUrl: String): List<Int> {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/111.0.0.0 Safari/537.36"
            )

            var baseUrl = stackUrl
            if (baseUrl.startsWith("/")) baseUrl = "https://myanimelist.net$baseUrl"
            else if (!baseUrl.startsWith("http")) baseUrl = "https://myanimelist.net/$baseUrl"

            val ids = mutableListOf<Int>()
            var offset = 0
            val perPage = 20
            var safety = 0

            // request the tile view which contains the .tile-anime-list container
            val tileBase = if (baseUrl.contains("?")) "$baseUrl&view_style=tile" else "$baseUrl?view_style=tile"

            while (true) {
                val url = if (offset == 0) tileBase else "$tileBase&offset=$offset"
                Logger.log("MALQueries.getStackEntries: fetching $url")
                val doc = client.get(url, headers).document

                // prefer the .tile-anime-list container (it is used for both anime and manga stacks)
                val container = doc.selectFirst(".tile-anime-list")
                if (container != null) {
                    val blocks = container.select(".seasonal-anime")
                    for (el in blocks) {
                        // prefer explicit title link or image link
                        val candidates = el.select(".title a[href], .image a[href], a[href]")
                        var found: Int? = null
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
                        if (found != null && !ids.contains(found)) {
                            ids.add(found)
                            Logger.log("MALQueries.getStackEntries: found id $found on $url")
                        }
                    }
                } else {
                    // fallback: try any .seasonal-anime that contains a link to /anime/ or /manga/
                    val fallback = doc.select(".seasonal-anime")
                    for (el in fallback) {
                        val link = el.selectFirst("a[href]")?.attr("href") ?: continue
                        Regex("/(anime|manga)/(\\d+)").find(link)?.groups?.get(2)?.value?.toIntOrNull()?.let { ids.add(it) }
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

            Logger.log("MALQueries.getStackEntries: extracted ids=$ids for stackUrl=$stackUrl")
            ids
        } catch (e: Exception) {
            emptyList()
        }
    }

}