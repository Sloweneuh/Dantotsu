package ani.dantotsu.connections.mangaupdates

import ani.dantotsu.Mapper
import ani.dantotsu.connections.anilist.MediaListCache
import ani.dantotsu.connections.anilist.MUSearchResults
import ani.dantotsu.connections.anilist.AnilistQueries
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object MangaUpdates {
    private const val BASE_URL = "https://api.mangaupdates.com/v1"
    private const val WEB_URL = "https://www.mangaupdates.com"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    var token: String? = null
    var username: String? = null
    var avatar: String? = null

    /** Cache of associated/synonym titles keyed by series id, populated on [getSeriesDetails] calls. */
    val synonymsCache = mutableMapOf<Long, List<String>>()


    /**
     * Login to MangaUpdates API and obtain a JWT token
     * @param username User's username
     * @param password User's password
     * @return true if login successful, false otherwise
     */
    suspend fun login(username: String, password: String): Boolean {
        return tryWithSuspend(false) {
            val loginRequest = MULoginRequest(username, password)
            val jsonBody = Mapper.json.encodeToString(loginRequest)

            val requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$BASE_URL/account/login")
                .put(requestBody)
                .build()

            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }

            val responseBody = extractBody(response)
            if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                Logger.log("MangaUpdates Login: Failed with code ${response.code}")
                return@tryWithSuspend false
            }

            val loginResponse = Mapper.parse<MULoginResponse>(responseBody)

            if (loginResponse.status != "success") {
                Logger.log("MangaUpdates Login: Status ${loginResponse.status}, reason: ${loginResponse.reason}")
                return@tryWithSuspend false
            }

            val sessionToken = loginResponse.context?.sessionToken
            if (sessionToken.isNullOrBlank()) {
                Logger.log("MangaUpdates Login: No session token in response")
                return@tryWithSuspend false
            }

            // Save token and username
            token = sessionToken
            this@MangaUpdates.username = username
            saveCredentials(username, password, sessionToken)

            // Fetch user profile to get avatar
            getUserProfile()

            Logger.log("MangaUpdates Login: Successfully logged in as $username")
            true
        } ?: false
    }

    /**
     * Get user profile information including avatar
     * @return User profile data or null if request fails
     */
    suspend fun getUserProfile(): MUUserProfile? {
        return tryWithSuspend {
            if (token.isNullOrBlank()) {
                Logger.log("MangaUpdates Profile: No token available")
                return@tryWithSuspend null
            }

            val request = Request.Builder()
                .url("$BASE_URL/account/profile")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }

            val responseBody = extractBody(response)
            if (!response.isSuccessful || responseBody == null || responseBody.isBlank()) {
                Logger.log("MangaUpdates Profile: Failed with code ${response.code}")
                return@tryWithSuspend null
            }

            val profile = Mapper.parse<MUUserProfile>(responseBody)

            // Store avatar URL if available. Persisted as well as held, so a restored session can
            // show it without spending a request on it — see [getSavedToken].
            avatar = profile.avatar?.url
            PrefManager.setVal(PrefName.MangaUpdatesAvatar, avatar ?: "")

            profile
        }
    }

    /**
     * Check if we have saved credentials and automatically login if available
     * @return true if logged in (either already or successfully), false otherwise
     */
    /**
     * Puts the session back in memory, logging in only if there isn't one to restore.
     *
     * [saveCredentials] has always stored the session token, and nothing ever read it back — so
     * every cold start traded the saved username and password for a fresh one, and `login` follows
     * that with a profile fetch, making two sequential round trips that everything MangaUpdates-
     * related then queued behind. On the home screen that was most of the leg: measured against a
     * real account, the list requests themselves took 265ms and the rest of the ~1.9s was spent
     * getting to the point of being allowed to make them.
     *
     * The avatar is restored the same way and for the same reason AniList's is — it is shown beside
     * the account name, and re-fetching it is another request in front of the one the user is
     * waiting on.
     *
     * A restored token can of course have expired since; [reauthenticate] handles that where it
     * shows up, on the first request that comes back 401.
     */
    suspend fun getSavedToken(): Boolean {
        // If we already have a token in memory, we're good
        if (!token.isNullOrBlank()) return true

        val storedToken = PrefManager.getVal<String>(PrefName.MangaUpdatesToken).takeIf { it.isNotBlank() }
        if (storedToken != null) {
            token = storedToken
            username = PrefManager.getNullableVal<String>(PrefName.MangaUpdatesUsername, null)
            avatar = PrefManager.getVal<String>(PrefName.MangaUpdatesAvatar).takeIf { it.isNotBlank() }
            return true
        }

        // Try to load saved credentials
        val savedUsername = PrefManager.getNullableVal<String>(PrefName.MangaUpdatesUsername, null)
        val savedPassword = PrefManager.getNullableVal<String>(PrefName.MangaUpdatesPassword, null)

        if (savedUsername != null && savedPassword != null) {
            return login(savedUsername, savedPassword)
        }

        return false
    }

    /**
     * Trades the saved credentials for a new session token after one was rejected.
     *
     * Guarded so a burst of concurrent list requests hitting the same expired token produces one
     * login rather than one each; whoever loses the race finds a fresh token already in place.
     */
    private val reauthLock = Mutex()

    private suspend fun reauthenticate(rejected: String?): Boolean = reauthLock.withLock {
        if (!token.isNullOrBlank() && token != rejected) return@withLock true
        val savedUsername = PrefManager.getNullableVal<String>(PrefName.MangaUpdatesUsername, null)
            ?: return@withLock false
        val savedPassword = PrefManager.getNullableVal<String>(PrefName.MangaUpdatesPassword, null)
            ?: return@withLock false
        Logger.log("MangaUpdates: session token rejected, logging in again")
        token = null
        login(savedUsername, savedPassword)
    }

    /**
     * Save credentials to preferences
     */
    private fun saveCredentials(username: String, password: String, token: String) {
        PrefManager.setVal(PrefName.MangaUpdatesUsername, username)
        PrefManager.setVal(PrefName.MangaUpdatesPassword, password)
        PrefManager.setVal(PrefName.MangaUpdatesToken, token)
    }

    /**
     * Clear saved credentials and logout
     */
    fun logout() {
        token = null
        username = null
        avatar = null
        PrefManager.removeVal(PrefName.MangaUpdatesUsername)
        PrefManager.removeVal(PrefName.MangaUpdatesPassword)
        PrefManager.removeVal(PrefName.MangaUpdatesToken)
        PrefManager.removeVal(PrefName.MangaUpdatesAvatar)
        // The stored home buckets are this account's lists; leaving them would show them to the next.
        MediaListCache.remove(MediaListCache.MU_HOME_KEY)
        Logger.log("MangaUpdates: Logged out")
    }

    /**
     * Paginated series search for use in the global search screen.
     */
    suspend fun searchSeriesPaged(
        r: MUSearchResults,
        page: Int = 1,
        perPage: Int = AnilistQueries.ITEMS_PER_PAGE
    ): MUSearchResults? {
        return tryWithSuspend {
            val searchRequest = MUSearchRequest(
                search = r.search?.takeIf { it.isNotBlank() } ?: " ",
                stype = "title",
                page = page,
                perpage = perPage,
                type = r.format?.let { listOf(it) },
                year = r.year,
                genre = r.genres?.takeIf { it.isNotEmpty() },
                exclude_genre = r.excludedGenres?.takeIf { it.isNotEmpty() },
                category = r.categories?.takeIf { it.isNotEmpty() },
                licensed = r.licensed,
                filters = r.statusFilters?.takeIf { it.isNotEmpty() },
                orderby = r.orderBy,
            )
            val jsonBody = Mapper.json.encodeToString(searchRequest)
            val requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$BASE_URL/series/search")
                .post(requestBody)
                .apply { if (!token.isNullOrBlank()) addHeader("Authorization", "Bearer $token") }
                .build()
            val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
            val responseBody = extractBody(response)
            if (!response.isSuccessful || responseBody == null || responseBody.isBlank()) return@tryWithSuspend null
            val searchResponse = Mapper.parse<MUSearchResponse>(responseBody)
            val results = searchResponse.results?.mapNotNull { it.toMUMedia() }?.toMutableList() ?: mutableListOf()
            val totalHits = searchResponse.totalHits ?: 0
            val responsePage = searchResponse.page ?: page
            val responsePerPage = searchResponse.perPage ?: perPage
            val hasNextPage = responsePerPage > 0 && (responsePage * responsePerPage) < totalHits
            MUSearchResults(
                search = r.search,
                page = responsePage,
                results = results,
                hasNextPage = hasNextPage,
                format = r.format,
                year = r.year,
                genres = r.genres,
                excludedGenres = r.excludedGenres,
                categories = r.categories,
                excludedCategories = r.excludedCategories,
                licensed = r.licensed,
                statusFilters = r.statusFilters,
                orderBy = r.orderBy,
            )
        }
    }

    /**
     * Fetch all available genres from MangaUpdates.
     */
    suspend fun getGenres(): List<String> {
        return tryWithSuspend {
            val request = Request.Builder()
                .url("$BASE_URL/genres")
                .get()
                .apply { if (!token.isNullOrBlank()) addHeader("Authorization", "Bearer $token") }
                .build()
            val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
            val responseBody = extractBody(response)
            if (!response.isSuccessful || responseBody.isNullOrBlank()) return@tryWithSuspend emptyList()
            Mapper.parse<List<MUGenre>>(responseBody).mapNotNull { it.genre }
        } ?: emptyList()
    }

    /**
     * Search categories by [query] and return up to [perPage] results from page 1.
     */
    suspend fun getCategories(query: String, perPage: Int = 100): List<String> {
        return tryWithSuspend {
            val searchRequest = MUCategorySearchRequest(search = query, orderby = "category", page = 1, perpage = perPage)
            val jsonBody = Mapper.json.encodeToString(searchRequest)
            val requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$BASE_URL/categories/search")
                .post(requestBody)
                .apply { if (!token.isNullOrBlank()) addHeader("Authorization", "Bearer $token") }
                .build()
            val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
            val responseBody = extractBody(response)
            if (!response.isSuccessful || responseBody.isNullOrBlank()) return@tryWithSuspend emptyList()
            Mapper.parse<MUCategorySearchResponse>(responseBody).results
                ?.mapNotNull { it.record?.category }
                ?: emptyList()
        } ?: emptyList()
    }

    /**
     * Search for a series by title
     * @param title The title to search for
     * @return Search response with results
     */
    suspend fun searchSeries(title: String): MUSearchResponse? {
        return tryWithSuspend {
            val searchRequest = MUSearchRequest(search = title, stype = "title")
            val jsonBody = Mapper.json.encodeToString(searchRequest)

            val requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$BASE_URL/series/search")
                .post(requestBody)
                .apply {
                    // Add Bearer token if available
                    if (!token.isNullOrBlank()) {
                        addHeader("Authorization", "Bearer $token")
                    }
                }
                .build()

            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }

            val responseBody = extractBody(response)
            if (!response.isSuccessful || responseBody == null || responseBody.isBlank()) {
                Logger.log("MangaUpdates Search: Failed with code ${response.code}")
                return@tryWithSuspend null
            }

            val searchResponse = Mapper.parse<MUSearchResponse>(responseBody)
            searchResponse
        }
    }

    /**
     * Get series details by series ID
     * @param seriesId The numeric series ID
     * @return Series details or null if not found
     */
    suspend fun getSeriesDetails(seriesId: Long): MUSeriesRecord? {
        return tryWithSuspend {
            // First try without authentication (public API)
            val request = Request.Builder()
                .url("$BASE_URL/series/$seriesId")
                .get()
                .build()

            var response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }

            // If unauthorized and we have a token, retry with authentication
            if (response.code == 401 && !token.isNullOrBlank()) {
                val authRequest = Request.Builder()
                    .url("$BASE_URL/series/$seriesId")
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                response = withContext(Dispatchers.IO) {
                    httpClient.newCall(authRequest).execute()
                }
            }

            val responseBody = extractBody(response)
            if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                Logger.log("MangaUpdates GetSeries: Failed with code ${response.code}")
                return@tryWithSuspend null
            }

            val seriesDetails = Mapper.parse<MUSeriesRecord>(responseBody)
            // Cache synonyms for search
            val synonyms = seriesDetails.associated?.mapNotNull { it.title } ?: emptyList()
            if (synonyms.isNotEmpty()) synonymsCache[seriesDetails.seriesId] = synonyms
            seriesDetails
        }
    }

    /**
     * Get group and release list for a series (GET /v1/series/{id}/groups).
     * The release_list is ordered newest-first and contains chapter, date, and group info.
     */
    suspend fun getSeriesGroups(seriesId: Long): MUSeriesGroupsResponse? {
        return tryWithSuspend {
            var request = Request.Builder()
                .url("$BASE_URL/series/$seriesId/groups")
                .get()
                .build()

            var response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }

            if (response.code == 401 && !token.isNullOrBlank()) {
                request = Request.Builder()
                    .url("$BASE_URL/series/$seriesId/groups")
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                response = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }
            }

            val body = extractBody(response)
            if (!response.isSuccessful || body.isNullOrBlank()) {
                Logger.log("MangaUpdates GetSeriesGroups: Failed with code ${response.code}")
                return@tryWithSuspend null
            }

            Mapper.parse<MUSeriesGroupsResponse>(body)
        }
    }

    /**
     * Look up series ID from a URL slug
     * @param urlSlug The alphanumeric URL slug (e.g., "7j43f8y")
     * @return The numeric series ID or null if not found
     */
    suspend fun lookupSeriesIdFromSlug(urlSlug: String?): Long? {
        return tryWithSuspend {
            // First, try to scrape the title from the webpage
            val title = scrapeWebTitle(urlSlug)
            if (title == null) {
                Logger.log("MangaUpdates Lookup: Could not scrape title from slug '$urlSlug'")
                return@tryWithSuspend null
            }

            // Search for the series
            val searchResults = searchSeries(title)
            if (searchResults?.results.isNullOrEmpty()) {
                return@tryWithSuspend null
            }

            // Try to find exact match by URL
            val exactMatch = searchResults.results?.firstOrNull { result ->
                urlSlug?.let { result.record?.url?.contains(it) == true } == true
            }

            if (exactMatch != null) {
                val seriesId = exactMatch.record?.seriesId
                return@tryWithSuspend seriesId
            }

            // No exact match, return first result
            val firstResult = searchResults.results?.firstOrNull()?.record
            firstResult?.seriesId
        }
    }

    /**
     * Scrape the manga title from the MangaUpdates webpage
     * @param urlSlug The URL slug
     * @return The manga title or null if scraping failed
     */
    private suspend fun scrapeWebTitle(urlSlug: String?): String? {
        return tryWithSuspend {
            val pageUrl = "$WEB_URL/series/$urlSlug"
            val request = Request.Builder()
                .url(pageUrl)
                .get()
                .build()

            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }

            val html = extractBody(response)
            if (!response.isSuccessful || html.isNullOrBlank()) {
                Logger.log("MangaUpdates Scrape: Failed to fetch page for slug '$urlSlug'")
                return@tryWithSuspend null
            }

            // Try multiple regex patterns to extract title - use more specific patterns
            val patterns = listOf(
                // OpenGraph title is most reliable
                Regex("property=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
                Regex("content=[\"']([^\"']+)[\"']\\s+property=[\"']og:title[\"']", RegexOption.IGNORE_CASE),
                // Twitter card title
                Regex("name=[\"']twitter:title[\"']\\s+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
                // Title tag - but stop at first < or tag
                Regex("<title>([^<]+?)</title>", RegexOption.IGNORE_CASE),
                // JSON-LD
                Regex("\"title\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
                // H1 heading
                Regex("<h1[^>]*>([^<]+)</h1>", RegexOption.IGNORE_CASE)
            )

            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null && match.groupValues.size > 1) {
                    var title = match.groupValues[1].trim()

                    // Clean up HTML entities
                    title = title
                        .replace("&#x27;", "'")
                        .replace("&quot;", "\"")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")

                    // Remove common suffixes
                    title = title
                        .removeSuffix("- MangaUpdates")
                        .removeSuffix("MangaUpdates")
                        .removeSuffix("- Series")
                        .removeSuffix("-")
                        .removeSuffix("|")
                        .trim()

                    // Validate: title should not contain HTML tags
                    if (title.isNotBlank() &&
                        title.length > 2 &&
                        title.length < 200 &&
                        !title.contains("<") &&
                        !title.contains(">") &&
                        !title.contains("</title>")) {

                        return@tryWithSuspend title
                    }
                }
            }

            Logger.log("MangaUpdates Scrape: Could not extract clean title from HTML")
            null
        }
    }

    /**
     * Get series details from a URL (either numeric ID or alphanumeric slug)
     * @param urlOrId The URL slug (e.g., "7j43f8y") or numeric ID
     * @return Series details or null if not found
     */
    suspend fun getSeriesFromUrl(urlOrId: String?): MUSeriesRecord? {
        return tryWithSuspend {
            val numericIdFromInput = urlOrId?.toLongOrNull()

            // A MangaUpdates URL slug (e.g. "pb8uwds") is nothing but the series id, base36-encoded
            // — the same scheme MUMedia already uses in reverse to build share links. Decoding is a
            // free, offline check, and when it hits, it resolves the id with a single documented
            // `/series/{id}` call, no page fetch or scrape needed at all. It's tried first because
            // it's by far the common case: most slugs reaching this function come bare, off a Comick
            // `links.mu` field or a share link, rather than embedded in a full page URL.
            if (numericIdFromInput == null) {
                urlOrId?.toLongOrNull(36)?.let { idFromSlug ->
                    getSeriesDetails(idFromSlug)?.let { return@tryWithSuspend it }
                }
            }

            // Below: the slug wasn't a bare id-encoding (e.g. it's a human-readable title slug from
            // a full page URL, such as "one-piece") — fall back to resolving it off the page itself,
            // preferring the canonical numeric identifier from its JSON-LD script.
            val pageUrl = if (numericIdFromInput != null) {
                "$WEB_URL/series.html?id=$numericIdFromInput"
            } else {
                "$WEB_URL/series/$urlOrId"
            }

            try {
                val pageRequest = Request.Builder().url(pageUrl).get().build()
                val pageResponse = withContext(Dispatchers.IO) { httpClient.newCall(pageRequest).execute() }

                val pageHtml = extractBody(pageResponse)
                if (!pageHtml.isNullOrBlank()) {
                    try {
                        val ldRegex = Regex("(?is)<script[^>]*type=['\"]application/ld\\+json['\"][^>]*>(.*?)</script>")
                        val ldMatch = ldRegex.find(pageHtml)
                        if (ldMatch != null) {
                            val jsonLd = ldMatch.groupValues[1]
                            val idRegexSimple = Regex("\"identifier\"\\s*:\\s*(\\d+)")
                            val idMatch = idRegexSimple.find(jsonLd)
                            val ldId = idMatch?.groupValues?.get(1)?.toLongOrNull()
                            if (ldId != null) {
                                val byLdId = getSeriesDetails(ldId)
                                if (byLdId != null) return@tryWithSuspend byLdId
                            }
                        }
                    } catch (e: Exception) {
                        Logger.log("MangaUpdates GetFromUrl: JSON-LD parsing failed: ${e.message}")
                    }
                }

                // If JSON-LD not present or didn't yield a working id, try fallback routes.
                // 1) If input was numeric, try the numeric API (might still work)
                if (numericIdFromInput != null) {
                    val directById = getSeriesDetails(numericIdFromInput)
                    if (directById != null) return@tryWithSuspend directById
                }

                // 2) Try to check the final request URL to get a slug
                val finalUrl = pageResponse.request.url.toString()
                val possibleSlug = finalUrl.substringAfterLast('/').substringBefore('?')
                if (possibleSlug.isNotBlank() && possibleSlug != numericIdFromInput?.toString()) {
                    val bySlug = tryDirectApiCall(possibleSlug)
                    if (bySlug != null) return@tryWithSuspend bySlug

                    // 3) As a last fallback, use search-based lookup
                    val lookedUpId = lookupSeriesIdFromSlug(possibleSlug)
                    if (lookedUpId != null) return@tryWithSuspend getSeriesDetails(lookedUpId)
                }
            } catch (e: Exception) {
                Logger.log("MangaUpdates GetFromUrl: Page fetch/extract failed: ${e.message}")
            }

            // Final fallback: try direct slug/id API and then search
            // Try direct API call with given identifier (slug or id string)
            val directResult = tryDirectApiCall(urlOrId)
            if (directResult != null) return@tryWithSuspend directResult

            // Try numeric input direct details if not already tried
            numericIdFromInput?.let { id ->
                val details = getSeriesDetails(id)
                if (details != null) return@tryWithSuspend details
            }

            // Last resort: use search/lookup by title
            val seriesId = lookupSeriesIdFromSlug(urlOrId)
            if (seriesId != null) return@tryWithSuspend getSeriesDetails(seriesId)

            null
        }
    }

    /**
     * Try to fetch series details directly using the slug/identifier
     * Some identifiers may work directly with the API
     */
    private suspend fun tryDirectApiCall(identifier: String?): MUSeriesRecord? {
        return tryWithSuspend {
            val request = Request.Builder()
                .url("$BASE_URL/series/$identifier")
                .get()
                .apply {
                    if (!token.isNullOrBlank()) {
                        addHeader("Authorization", "Bearer $token")
                    }
                }
                .build()

            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }

            if (!response.isSuccessful) {
                return@tryWithSuspend null
            }

            val responseBody = extractBody(response)
            if (responseBody == null || responseBody.isBlank()) {
                return@tryWithSuspend null
            }

            Mapper.parse<MUSeriesRecord>(responseBody)
        }
    }

    // helper to read okhttp response bodies safely
    private fun extractBody(response: okhttp3.Response): String? {
        return try {
            response.body?.string()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetch all entries for a specific list (0=Reading, 1=Planning, 2=Completed, 3=Dropped, 4=Paused).
     */
    suspend fun getUserList(
        listId: Int,
        page: Int = 1,
        perPage: Int = -1,
        /** Handed the final HTTP status, for callers that treat some of them as more than a failure. */
        onHttpCode: ((Int) -> Unit)? = null
    ): MUListResponse? {
        return tryWithSuspend {
            if (token.isNullOrBlank()) {
                Logger.log("MangaUpdates GetUserList: No token available")
                return@tryWithSuspend null
            }

            val requestBody = Mapper.json.encodeToString(
                MUListSearchRequest(page = page, perPage = perPage)
            ).toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("$BASE_URL/lists/$listId/search")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .build()

            var response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }

            // A restored session token can have expired while the app was closed. One login and one
            // retry, rather than reporting an empty list and leaving the user to work out that they
            // have been quietly signed out.
            if (response.code == 401 && reauthenticate(rejected = token)) {
                val retry = Request.Builder()
                    .url("$BASE_URL/lists/$listId/search")
                    .post(
                        Mapper.json.encodeToString(
                            MUListSearchRequest(page = page, perPage = perPage)
                        ).toRequestBody("application/json".toMediaTypeOrNull())
                    )
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                response = withContext(Dispatchers.IO) { httpClient.newCall(retry).execute() }
            }

            onHttpCode?.invoke(response.code)
            val responseBody = extractBody(response)
            if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                Logger.log("MangaUpdates GetUserList[$listId]: Failed with code ${response.code}")
                return@tryWithSuspend null
            }

            val result = Mapper.parse<MUListResponse>(responseBody)
            Logger.log("MangaUpdates GetUserList[$listId]: ${result.totalHits} hits")
            result
        }
    }

    /**
     * Add a series to a user list (POST /lists/series)
     * @param seriesId Numeric series ID
     * @param seriesTitle Series title (used in request body; may be null)
     * @param listId List to add to (0=Reading, 1=Planning, 2=Completed, 3=Dropped, 4=Paused)
     * @param chapter Chapter progress (null = 0)
     * @param volume  Volume progress  (null = 0)
     * @return true on success
     */
    suspend fun addToList(
        seriesId: Long,
        seriesTitle: String?,
        listId: Int,
        chapter: Int?,
        volume: Int?
    ): Boolean {
        return tryWithSuspend {
            // The token is restored in the background, so this has to wait for it — a write sent
            // from an entry point that skipped the home screen would otherwise find no token and
            // give up with only a log line. See [ani.dantotsu.connections.TrackerSessions].
            ani.dantotsu.connections.TrackerSessions.await()
            if (token.isNullOrBlank()) {
                Logger.log("MangaUpdates AddToList: No token available")
                return@tryWithSuspend false
            }

            val payload = listOf(
                MUProgressUpdateRequest(
                    series = MUProgressUpdateSeries(id = seriesId, title = seriesTitle),
                    listId = listId,
                    status = MUProgressUpdateStatus(
                        volume = volume ?: 0,
                        chapter = chapter ?: 0,
                        incrementVolume = 0,
                        incrementChapter = 0
                    ),
                    priority = 0
                )
            )

            val body = Mapper.json.encodeToString(payload)
                .toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("$BASE_URL/lists/series")
                .post(body)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
            val ok = response.isSuccessful
            ok
        } ?: false
    }

    /**
     * Update reading progress for a series already in the user's list.
     * @param seriesId Numeric series ID
     * @param seriesTitle Series title (used in request body; may be null)
     * @param listId List to update (0=Reading, 1=Planning, 2=Completed, 3=Dropped, 4=Paused)
     * @param chapter Chapter progress (null = leave unchanged)
     * @param volume  Volume progress  (null = leave unchanged)
     * @return true on success
     */
    suspend fun updateProgress(
        seriesId: Long,
        seriesTitle: String?,
        listId: Int,
        chapter: Int?,
        volume: Int?
    ): Boolean {
        return tryWithSuspend {
            ani.dantotsu.connections.TrackerSessions.await() // see addToList
            if (token.isNullOrBlank()) {
                Logger.log("MangaUpdates UpdateProgress: No token available")
                return@tryWithSuspend false
            }

            val payload = listOf(
                MUProgressUpdateRequest(
                    series = MUProgressUpdateSeries(id = seriesId, title = seriesTitle),
                    listId = listId,
                    status = MUProgressUpdateStatus(
                        volume = volume ?: 0,
                        chapter = chapter ?: 0,
                        incrementVolume = 0,
                        incrementChapter = 0
                    ),
                    priority = 0
                )
            )

            val body = Mapper.json.encodeToString(payload)
                .toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("$BASE_URL/lists/series/update")
                .post(body)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
            val ok = response.isSuccessful
            ok
        } ?: false
    }

    /**
     * Remove a series from all user lists.
     * @return true on success
     */
    suspend fun removeFromList(seriesId: Long): Boolean {
        return tryWithSuspend {
            ani.dantotsu.connections.TrackerSessions.await() // see addToList
            if (token.isNullOrBlank()) {
                Logger.log("MangaUpdates RemoveFromList: No token available")
                return@tryWithSuspend false
            }
            val payload = listOf(seriesId)
            val body = Mapper.json.encodeToString(payload)
                .toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$BASE_URL/lists/series/delete")
                .post(body)
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
            response.isSuccessful
        } ?: false
    }

    /**
     * Fetch all lists for the authenticated user from /v1/lists,
     * including custom lists.
     */
    suspend fun getUserListsMeta(): List<MUUserList> {
        return tryWithSuspend {
            if (token.isNullOrBlank()) return@tryWithSuspend emptyList<MUUserList>()
            val request = Request.Builder()
                .url("$BASE_URL/lists")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
            val body = extractBody(response) ?: return@tryWithSuspend emptyList<MUUserList>()
            Mapper.parse<List<MUUserList>>(body)
        } ?: emptyList()
    }

    /** Every page of one list, as [MUMedia]. */
    private suspend fun getUserListFully(
        listId: Int,
        onHttpCode: ((Int) -> Unit)? = null
    ): List<MUMedia> {
        val entries = mutableListOf<MUListEntry>()
        var page = 1
        while (true) {
            val response = getUserList(listId, page, onHttpCode = onHttpCode) ?: break
            entries += response.results.orEmpty()
            val total = response.totalHits ?: 0
            if (entries.size >= total) break
            page++
        }
        return entries.mapNotNull { it.toMUMedia(listId) }
    }

    /**
     * Just the reading list (list 0).
     *
     * For callers that only want what's in progress — the waiting widget — and shouldn't pay for the
     * planning, completed, dropped and paused lists that [getAllUserLists] also walks.
     */
    suspend fun getReadingList(): List<MUMedia> = getUserListFully(READING_LIST_ID)

    /** The standard lists, in list-id order — the index is the id MangaUpdates uses. */
    private val STANDARD_LISTS = listOf("Reading", "Planning", "Completed", "Dropped", "Paused")

    /** The buckets the home screen reads; see [getHomeLists]. */
    private val HOME_BUCKETS = setOf("Reading", "Planning")

    /** The user's custom lists as (list id, bucket it feeds), from the configured mapping. */
    private fun customListTargets(): List<Pair<Int, String>> {
        val mappingJson = PrefManager.getVal<String>(PrefName.MuCustomListMapping)
        if (mappingJson.isBlank()) return emptyList()
        return try {
            Mapper.json.decodeFromString<Map<String, String>>(mappingJson)
                .mapNotNull { (idStr, bucket) -> idStr.toIntOrNull()?.let { it to bucket } }
        } catch (e: Exception) {
            Logger.log("MangaUpdates: bad custom list mapping: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetches several lists at once and buckets them by name.
     *
     * One at a time is how this used to run — five standard lists in a loop, each paginating
     * sequentially inside, then the custom lists after that — and on a real library the chain of
     * round trips came to about two and a half seconds, which was the single largest thing on the
     * home screen's load and the leg every other one waited behind. The lists are independent of
     * each other, so the only reason for the wait was the loop.
     *
     * [required] failures propagate and [optional] failures come back empty, which is the behaviour
     * the sequential version had: a standard list that would not load failed the whole call, while
     * the custom-list pass was wrapped so a broken one could not take the rest down with it.
     */
    private suspend fun fetchLists(
        required: List<Pair<Int, String>>,
        optional: List<Pair<Int, String>>
    ): Map<String, List<MUMedia>> {
        val gone = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        val fetched = coroutineScope {
            val requiredJobs = required.map { (listId, bucket) ->
                async { bucket to getUserListFully(listId) }
            }
            val optionalJobs = optional.map { (listId, bucket) ->
                async {
                    bucket to runCatching {
                        getUserListFully(listId) { code ->
                            // A list the account no longer has. Not a failure to retry — it is an
                            // answer, and the only one that makes a stale mapping safe to drop.
                            if (code == 404 || code == 410) gone += listId
                        }
                    }.getOrElse {
                        Logger.log("MangaUpdates: custom list $listId failed: ${it.message}")
                        emptyList()
                    }
                }
            }
            (requiredJobs + optionalJobs).awaitAll()
        }
        if (gone.isNotEmpty()) forgetCustomLists(gone)

        val result = mutableMapOf<String, MutableList<MUMedia>>()
        fetched.forEach { (bucket, entries) ->
            if (entries.isNotEmpty()) result.getOrPut(bucket) { mutableListOf() }.addAll(entries)
        }
        return result.mapValues { it.value.toList() }
    }

    /**
     * Drops mappings for custom lists the account no longer has.
     *
     * Only ever called for a list MangaUpdates answered about — a 404, not a timeout or a refused
     * connection — because a mapping deleted on the strength of a transient failure is one the user
     * has to go and set up again. A list that is merely unreachable keeps its mapping and is asked
     * about next time.
     */
    private fun forgetCustomLists(listIds: Set<Int>) {
        try {
            val mappingJson = PrefManager.getVal<String>(PrefName.MuCustomListMapping)
            if (mappingJson.isBlank()) return
            val mapping = Mapper.json.decodeFromString<Map<String, String>>(mappingJson)
            val kept = mapping.filterKeys { it.toIntOrNull() !in listIds }
            if (kept.size == mapping.size) return
            PrefManager.setVal(
                PrefName.MuCustomListMapping,
                if (kept.isEmpty()) "" else Mapper.json.encodeToString(kept)
            )
            Logger.log("MangaUpdates: dropped mapping for removed custom list(s) $listIds")
        } catch (e: Exception) {
            Logger.log("MangaUpdates: failed to prune custom list mapping: ${e.message}")
        }
    }

    suspend fun getAllUserLists(): Map<String, List<MUMedia>> = fetchLists(
        required = STANDARD_LISTS.mapIndexed { listId, name -> listId to name },
        optional = customListTargets()
    )

    /**
     * Just the lists the home screen draws from.
     *
     * It reads exactly two buckets — "Reading" for the continue row and the MangaUpdates unread
     * row, "Planning" for the planned row — so fetching Completed, Dropped and Paused as well meant
     * three of the five standard lists were downloaded on every home load and never looked at.
     * Custom lists are included only where the user has mapped them into one of those two buckets.
     *
     * [getAllUserLists] stays as it is for the screens that genuinely want everything: the list
     * screen's MangaUpdates tabs, list comparison, and the unread notification scan.
     */
    /**
     * The home buckets as last stored by [getHomeLists], or null if there is nothing stored.
     *
     * Stored as the assembled buckets rather than the raw responses behind them: a bucket can be fed
     * by several lists (a custom list mapped onto "Reading") and a list can span pages, so the
     * responses do not map one-to-one onto what the screen reads, while the assembled form does.
     */
    fun cachedHomeLists(): Map<String, List<MUMedia>>? {
        val body = MediaListCache.read(MediaListCache.MU_HOME_KEY) ?: return null
        return try {
            Mapper.json.decodeFromString<Map<String, List<MUMedia>>>(body)
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Logger.log("MangaUpdates: failed to read stored home lists: ${e.message}")
            null
        }
    }

    suspend fun getHomeLists(): Map<String, List<MUMedia>> = fetchLists(
        required = STANDARD_LISTS.mapIndexed { listId, name -> listId to name }
            .filter { (_, name) -> name in HOME_BUCKETS },
        optional = customListTargets().filter { (_, bucket) -> bucket in HOME_BUCKETS }
    ).also { lists ->
        // Only a result that actually reached MangaUpdates is worth storing. An empty map here
        // means every request failed, and writing that would hand the next launch an empty home
        // screen to show confidently before the real one arrives.
        if (lists.isNotEmpty()) {
            runCatching {
                MediaListCache.write(
                    MediaListCache.MU_HOME_KEY,
                    Mapper.json.encodeToString(lists)
                )
            }.onFailure { Logger.log("MangaUpdates: failed to store home lists: ${it.message}") }
        }
    }

    private const val READING_LIST_ID = 0
}
