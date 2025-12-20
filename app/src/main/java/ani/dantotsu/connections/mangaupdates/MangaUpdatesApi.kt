package ani.dantotsu.connections.mangaupdates

import ani.dantotsu.Mapper
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
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

            // Store avatar URL if available
            avatar = profile.avatar?.url
            Logger.log("MangaUpdates Profile: Retrieved profile for '${profile.username}', avatar: ${avatar != null}")

            profile
        }
    }

    /**
     * Check if we have saved credentials and automatically login if available
     * @return true if logged in (either already or successfully), false otherwise
     */
    suspend fun getSavedToken(): Boolean {
        // If we already have a token in memory, we're good
        if (!token.isNullOrBlank()) {
            // Try to fetch avatar if we don't have it yet
            if (avatar == null) {
                getUserProfile()
            }
            return true
        }

        // Try to load saved credentials
        val savedUsername = PrefManager.getNullableVal<String>(PrefName.MangaUpdatesUsername, null)
        val savedPassword = PrefManager.getNullableVal<String>(PrefName.MangaUpdatesPassword, null)

        if (savedUsername != null && savedPassword != null) {
            Logger.log("MangaUpdates: Found saved credentials, attempting login")
            return login(savedUsername, savedPassword)
        }

        Logger.log("MangaUpdates: No saved credentials found")
        return false
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
        Logger.log("MangaUpdates: Logged out")
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
            Logger.log("MangaUpdates Search: Found ${searchResponse.totalHits} results for '$title'")
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
                Logger.log("MangaUpdates GetSeries: Public API failed, retrying with auth")
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
            Logger.log("MangaUpdates GetSeries: Retrieved details for '${seriesDetails.title}'")
            seriesDetails
        }
    }

    /**
     * Look up series ID from a URL slug
     * @param urlSlug The alphanumeric URL slug (e.g., "7j43f8y")
     * @return The numeric series ID or null if not found
     */
    suspend fun lookupSeriesIdFromSlug(urlSlug: String): Long? {
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
                Logger.log("MangaUpdates Lookup: No results found for title '$title'")
                return@tryWithSuspend null
            }

            // Try to find exact match by URL
            val exactMatch = searchResults.results?.firstOrNull { result ->
                result.record?.url?.contains(urlSlug) == true
            }

            if (exactMatch != null) {
                val seriesId = exactMatch.record?.seriesId
                Logger.log("MangaUpdates Lookup: Found exact match - ID $seriesId for slug '$urlSlug'")
                return@tryWithSuspend seriesId
            }

            // No exact match, return first result
            val firstResult = searchResults.results?.firstOrNull()?.record
            Logger.log("MangaUpdates Lookup: No exact match, using first result - ID ${firstResult?.seriesId}")
            firstResult?.seriesId
        }
    }

    /**
     * Scrape the manga title from the MangaUpdates webpage
     * @param urlSlug The URL slug
     * @return The manga title or null if scraping failed
     */
    private suspend fun scrapeWebTitle(urlSlug: String): String? {
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

                        Logger.log("MangaUpdates Scrape: Extracted title '$title' from webpage")
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
    suspend fun getSeriesFromUrl(urlOrId: String): MUSeriesRecord? {
        return tryWithSuspend {
            // Prefer resolving the canonical numeric identifier via the page's JSON-LD script
            val numericIdFromInput = urlOrId.toLongOrNull()

            // Build the page URL to fetch: numeric input uses series.html?id=..., slug uses /series/<slug>
            val pageUrl = if (numericIdFromInput != null) {
                "$WEB_URL/series.html?id=$numericIdFromInput"
            } else {
                "$WEB_URL/series/$urlOrId"
            }

            Logger.log("MangaUpdates GetFromUrl: Fetching page for canonical id extraction: $pageUrl")

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
                                Logger.log("MangaUpdates GetFromUrl: Found JSON-LD identifier $ldId, trying numeric API")
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
                    Logger.log("MangaUpdates GetFromUrl: Trying numeric API for input id $numericIdFromInput as fallback")
                    val directById = getSeriesDetails(numericIdFromInput)
                    if (directById != null) return@tryWithSuspend directById
                }

                // 2) Try to check the final request URL to get a slug
                val finalUrl = pageResponse.request.url.toString()
                Logger.log("MangaUpdates GetFromUrl: Page final URL: $finalUrl")
                val possibleSlug = finalUrl.substringAfterLast('/').substringBefore('?')
                if (possibleSlug.isNotBlank() && possibleSlug != numericIdFromInput?.toString()) {
                    Logger.log("MangaUpdates GetFromUrl: Found possible slug '$possibleSlug', trying slug-based API")
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
            Logger.log("MangaUpdates GetFromUrl: Falling back to direct API and search for '$urlOrId'")
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

            Logger.log("MangaUpdates GetFromUrl: All methods failed for '$urlOrId'")
            null
        }
    }

    /**
     * Try to fetch series details directly using the slug/identifier
     * Some identifiers may work directly with the API
     */
    private suspend fun tryDirectApiCall(identifier: String): MUSeriesRecord? {
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
}
