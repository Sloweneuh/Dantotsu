package ani.dantotsu.connections.malsync

import ani.dantotsu.util.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object MalSyncApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // Track consecutive network failures to avoid spamming requests
    private var consecutiveNetworkFailures = 0
    private var lastNetworkFailureTime = 0L
    private const val MAX_CONSECUTIVE_FAILURES = 3
    private const val FAILURE_COOLDOWN_MS = 60000L // 1 minute

    /**
     * Check if we should skip API calls due to repeated network failures
     */
    private fun shouldSkipDueToNetworkFailures(): Boolean {
        if (consecutiveNetworkFailures >= MAX_CONSECUTIVE_FAILURES) {
            val timeSinceLastFailure = System.currentTimeMillis() - lastNetworkFailureTime
            if (timeSinceLastFailure < FAILURE_COOLDOWN_MS) {
                Logger.log("MalSync: Skipping API call due to repeated network failures (cooldown active)")
                return true
            } else {
                // Reset after cooldown period
                consecutiveNetworkFailures = 0
            }
        }
        return false
    }

    /**
     * Record a network failure
     */
    private fun recordNetworkFailure() {
        consecutiveNetworkFailures++
        lastNetworkFailureTime = System.currentTimeMillis()
        Logger.log("MalSync: Network failure recorded (count: $consecutiveNetworkFailures)")
    }

    /**
     * Record a successful network call
     */
    private fun recordNetworkSuccess() {
        consecutiveNetworkFailures = 0
    }

    /**
     * Execute a network request with proper error handling
     */
    private fun executeRequest(request: Request): okhttp3.Response? {
        try {
            val response = client.newCall(request).execute()
            recordNetworkSuccess()
            return response
        } catch (e: UnknownHostException) {
            Logger.log("MalSync: Unable to resolve host: ${e.message}")
            recordNetworkFailure()
            return null
        } catch (e: SocketTimeoutException) {
            Logger.log("MalSync: Request timeout: ${e.message}")
            recordNetworkFailure()
            return null
        } catch (e: IOException) {
            Logger.log("MalSync: Network error: ${e.message}")
            recordNetworkFailure()
            return null
        } catch (e: Exception) {
            Logger.log("MalSync: Unexpected error: ${e.message}")
            return null
        }
    }

    /**
     * Reset network failure counter - useful for manual retries
     */
    fun resetNetworkFailures() {
        consecutiveNetworkFailures = 0
        Logger.log("MalSync: Network failure counter reset")
    }

    suspend fun getLastChapter(anilistId: Int, malId: Int? = null): MalSyncResponse? = withContext(Dispatchers.IO) {
        if (shouldSkipDueToNetworkFailures()) {
            return@withContext null
        }

        try {
            var url: String
            var request: Request
            var response: okhttp3.Response?
            var body: String?

            // Try with MAL ID first (preferred)
            if (malId != null) {
                Logger.log("MalSync: Trying MAL ID $malId first")
                url = "https://api.malsync.moe/nc/mal/manga/$malId/pr"
                request = Request.Builder()
                    .url(url)
                    .build()
                response = executeRequest(request)

                if (response == null) {
                    Logger.log("MalSync: Network error fetching MAL ID $malId")
                    return@withContext null
                }

                body = response.body?.string()

                // If MAL ID succeeds and has results, use it
                if (response.isSuccessful && body != null && body != "[]" && body.isNotEmpty()) {
                    Logger.log("MalSync: MAL ID $malId succeeded")
                    val type = object : TypeToken<List<MalSyncResponse>>() {}.type
                    val results: List<MalSyncResponse> = gson.fromJson(body, type)
                    return@withContext getProgressForManga(results)
                }
                Logger.log("MalSync: MAL ID $malId failed or returned no results, falling back to AniList ID")
            }

            // Fallback to Anilist ID
            Logger.log("MalSync: Trying AniList ID $anilistId")
            url = "https://api.malsync.moe/nc/mal/manga/anilist:$anilistId/pr"
            request = Request.Builder()
                .url(url)
                .build()
            response = executeRequest(request)

            if (response == null) {
                Logger.log("MalSync: Network error fetching AniList ID $anilistId")
                return@withContext null
            }

            body = response.body?.string()

            if (!response.isSuccessful) {
                Logger.log("MalSync API error: ${response.code}")
                return@withContext null
            }

            if (body == null || body == "[]" || body.isEmpty()) {
                Logger.log("MalSync: No results found for Anilist ID $anilistId" + (malId?.let { " or MAL ID $it" } ?: ""))
                return@withContext null
            }

            val type = object : TypeToken<List<MalSyncResponse>>() {}.type
            val results: List<MalSyncResponse> = gson.fromJson(body, type)

            // Use the same logic as the web extension's getProgress function
            // Priority: en/sub -> any entry with lang "en" -> first available
            getProgressForManga(results)
        } catch (e: Exception) {
            Logger.log("Error fetching MalSync data: ${e.message}")
            null
        }
    }

    suspend fun getLastEpisode(anilistId: Int, malId: Int?, preferredLanguage: String = "en/dub"): MalSyncResponse? = withContext(Dispatchers.IO) {
        if (shouldSkipDueToNetworkFailures()) {
            return@withContext null
        }

        try {
            // Use MAL ID only (as per user requirements)
            if (malId == null) {
                Logger.log("MalSync: No MAL ID provided for anime $anilistId")
                return@withContext null
            }

            Logger.log("MalSync: Fetching anime data for MAL ID $malId with preferred language $preferredLanguage")
            val url = "https://api.malsync.moe/nc/mal/anime/$malId/pr"
            val request = Request.Builder()
                .url(url)
                .build()
            val response = executeRequest(request)

            if (response == null) {
                Logger.log("MalSync: Network error fetching anime for MAL ID $malId")
                return@withContext null
            }

            val body = response.body?.string()

            if (!response.isSuccessful) {
                Logger.log("MalSync API error: ${response.code}")
                return@withContext null
            }

            if (body == null || body == "[]" || body.isEmpty()) {
                Logger.log("MalSync: No results found for MAL ID $malId")
                return@withContext null
            }

            val type = object : TypeToken<List<MalSyncResponse>>() {}.type
            val results: List<MalSyncResponse> = gson.fromJson(body, type)

            // Use anime-specific progress selection with language preference
            getProgressForAnime(results, preferredLanguage)
        } catch (e: Exception) {
            Logger.log("Error fetching MalSync anime data: ${e.message}")
            null
        }
    }

    /**
     * Get all available language IDs for an anime
     * @param malId MAL anime ID
     * @return List of available language IDs (e.g., ["en/dub", "en/sub", "jp"])
     */
    suspend fun getAvailableLanguages(malId: Int): List<String> = withContext(Dispatchers.IO) {
        if (shouldSkipDueToNetworkFailures()) {
            return@withContext emptyList()
        }

        try {
            Logger.log("MalSync: Fetching available languages for MAL ID $malId")
            val url = "https://api.malsync.moe/nc/mal/anime/$malId/pr"
            val request = Request.Builder()
                .url(url)
                .build()
            val response = executeRequest(request) ?: return@withContext emptyList()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null || body == "[]" || body.isEmpty()) {
                Logger.log("MalSync: No results found for MAL ID $malId")
                return@withContext emptyList()
            }

            val type = object : TypeToken<List<MalSyncResponse>>() {}.type
            val results: List<MalSyncResponse> = gson.fromJson(body, type)

            val languages = results.map { it.id }.distinct()
            Logger.log("MalSync: Available languages for MAL ID $malId: $languages")
            return@withContext languages
        } catch (e: Exception) {
            Logger.log("Error fetching available languages: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get all available languages with episode counts for an anime
     * @param malId MAL anime ID
     * @return List of MalSyncResponse with language IDs and episode counts
     */
    suspend fun getAvailableLanguagesWithEpisodes(malId: Int): List<MalSyncResponse> = withContext(Dispatchers.IO) {
        if (shouldSkipDueToNetworkFailures()) {
            return@withContext emptyList()
        }

        try {
            Logger.log("MalSync: Fetching available languages with episodes for MAL ID $malId")
            val url = "https://api.malsync.moe/nc/mal/anime/$malId/pr"
            val request = Request.Builder()
                .url(url)
                .build()
            val response = executeRequest(request) ?: return@withContext emptyList()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null || body == "[]" || body.isEmpty()) {
                Logger.log("MalSync: No results found for MAL ID $malId")
                return@withContext emptyList()
            }

            val type = object : TypeToken<List<MalSyncResponse>>() {}.type
            val results: List<MalSyncResponse> = gson.fromJson(body, type)

            Logger.log("MalSync: Found ${results.size} language options for MAL ID $malId")
            return@withContext results
        } catch (e: Exception) {
            Logger.log("Error fetching available languages with episodes: ${e.message}")
            emptyList()
        }
    }

    /**
     * Implements the same progress selection logic as the web extension for manga
     * Prioritizes entries based on ID and language with fallback logic
     * Only returns English language entries to avoid showing other languages
     */
    private fun getProgressForManga(results: List<MalSyncResponse>): MalSyncResponse? {
        if (results.isEmpty()) return null

        // Primary: Look for "en/sub" (standard for manga)
        var top = results.firstOrNull { it.id == "en/sub" }

        // Fallback: Look for any English language entry
        if (top == null) {
            top = results.firstOrNull { it.lang == "en" }
        }

        // Only return English entries - don't show other languages
        return top
    }

    /**
     * Implements progress selection logic for anime with language preference
     * Prioritizes based on preferred language with fallback logic
     * @param results List of available MalSync entries
     * @param preferredLanguage Preferred language ID (e.g., "en/dub", "en/sub")
     * @return Best matching MalSyncResponse or null
     */
    private fun getProgressForAnime(results: List<MalSyncResponse>, preferredLanguage: String): MalSyncResponse? {
        if (results.isEmpty()) return null

        Logger.log("MalSync: Selecting anime progress from ${results.size} entries with preferred language $preferredLanguage")
        Logger.log("MalSync: Available IDs: ${results.map { it.id }}")

        // Primary: Look for exact match with preferred language
        var top = results.firstOrNull { it.id == preferredLanguage }
        if (top != null) {
            Logger.log("MalSync: Found exact match for $preferredLanguage")
            return top
        }

        // Fallback 1: If preferred was "en/dub", try "en/sub"
        if (preferredLanguage == "en/dub") {
            top = results.firstOrNull { it.id == "en/sub" }
            if (top != null) {
                Logger.log("MalSync: Fallback to en/sub from en/dub")
                return top
            }
        }

        // Fallback 2: Try any entry with lang "en"
        top = results.firstOrNull { it.lang == "en" }
        if (top != null) {
            Logger.log("MalSync: Fallback to any English language entry: ${top.id}")
            return top
        }

        // Fallback 3: Return first available entry
        Logger.log("MalSync: Using first available entry: ${results.first().id}")
        return results.first()
    }

    /**
     * Batch fetch progress data for multiple manga using the POST endpoint
     * Fetches up to 50 manga at once to reduce API calls
     * Supports both numeric MAL IDs and "anilist:ID" format
     * @param mediaList List of pairs containing (anilistId, malId)
     * @param onProgress Optional callback for progress updates (batchNumber, totalBatches, processedCount, totalCount)
     * @return Map of AniList IDs to MalSyncResponse
     */
    suspend fun getBatchProgressByMedia(
        mediaList: List<Pair<Int, Int?>>,
        onProgress: ((Int, Int, Int, Int) -> Unit)? = null
    ): Map<Int, MalSyncResponse> = withContext(Dispatchers.IO) {
        if (mediaList.isEmpty()) return@withContext emptyMap()

        if (shouldSkipDueToNetworkFailures()) {
            Logger.log("MalSync: Skipping batch request due to repeated network failures")
            return@withContext emptyMap()
        }

        val resultMap = mutableMapOf<Int, MalSyncResponse>()

        // Create cache keys - prefer MAL ID (numeric), fallback to "anilist:ID" (string)
        val cacheKeyMap = mutableMapOf<String, Int>() // cacheKey -> anilistId
        val cacheKeys = mediaList.map { (anilistId, malId) ->
            val key = if (malId != null) {
                malId.toString()  // Numeric: "5114"
            } else {
                "anilist:$anilistId"  // String: "anilist:173188"
            }
            cacheKeyMap[key] = anilistId
            key
        }

        // Batch fetch all manga (up to 50 at a time)
        val chunks = cacheKeys.chunked(50)
        val totalBatches = chunks.size
        val totalCount = mediaList.size

        chunks.forEachIndexed { batchIndex, chunk ->
            val batchNumber = batchIndex + 1
            val processedSoFar = minOf(batchNumber * 50, totalCount)

            try {
                // Check network status before each batch
                if (shouldSkipDueToNetworkFailures()) {
                    Logger.log("MalSync: Stopping batch processing due to network failures")
                    return@withContext resultMap
                }

                // Notify progress before processing batch
                onProgress?.invoke(batchNumber, totalBatches, processedSoFar - chunk.size, totalCount)

                // Wait 5 seconds between requests (rate limiting, matching web extension)
                if (resultMap.isNotEmpty()) {
                    delay(5000)
                }

                // Send cache keys (can be numeric or "anilist:ID" format)
                val jsonArray = org.json.JSONArray(chunk)
                val jsonBody = JSONObject().apply {
                    put("malids", jsonArray)
                }.toString()

                Logger.log("MalSync batch request: $jsonBody")

                val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://api.malsync.moe/nc/mal/manga/POST/pr")
                    .post(requestBody)
                    .build()

                val response = executeRequest(request)

                if (response == null) {
                    Logger.log("MalSync batch API network error for batch $batchNumber")
                    // Continue to next batch instead of stopping completely
                    onProgress?.invoke(batchNumber, totalBatches, processedSoFar, totalCount)
                    return@forEachIndexed
                }

                val body = response.body?.string()

                if (!response.isSuccessful) {
                    Logger.log("MalSync batch API error: ${response.code} - Body: $body")
                    onProgress?.invoke(batchNumber, totalBatches, processedSoFar, totalCount)
                    return@forEachIndexed
                }

                if (body == null || body.isEmpty()) {
                    Logger.log("MalSync batch API returned empty response")
                    onProgress?.invoke(batchNumber, totalBatches, processedSoFar, totalCount)
                    return@forEachIndexed
                }

                Logger.log("MalSync batch response (first 500 chars): ${body.take(500)}")

                try {
                    // The batch POST endpoint returns an array of result objects
                    // Each object has: { "malid": "123" or "anilist:456", "data": [...] }
                    val type = object : TypeToken<List<BatchProgressResult>>() {}.type
                    val batchResults: List<BatchProgressResult> = gson.fromJson(body, type)

                    Logger.log("Parsed ${batchResults.size} results from batch")

                    // Process each manga's results
                    batchResults.forEach { result ->
                        val cacheKey = result.malid ?: return@forEach
                        val anilistId = cacheKeyMap[cacheKey] ?: return@forEach
                        val progress = getProgressForManga(result.data ?: emptyList())
                        if (progress != null) {
                            resultMap[anilistId] = progress
                        }
                    }
                } catch (parseError: Exception) {
                    Logger.log("Error parsing batch response: ${parseError.message}")
                    Logger.log("Response body: $body")
                }

                // Notify progress after batch completes
                onProgress?.invoke(batchNumber, totalBatches, processedSoFar, totalCount)

            } catch (e: Exception) {
                Logger.log("Error fetching batch progress for chunk: ${e.message}")
                e.printStackTrace()
            }
        }

        resultMap
    }

    suspend fun getUnreadChapters(mangaList: List<Int>): Map<Int, MalSyncResponse> = withContext(Dispatchers.IO) {
        val unreadMap = mutableMapOf<Int, MalSyncResponse>()

        mangaList.forEach { mangaId ->
            try {
                val result = getLastChapter(mangaId)
                if (result != null) {
                    unreadMap[mangaId] = result
                }
            } catch (e: Exception) {
                Logger.log("Error fetching chapter for manga $mangaId: ${e.message}")
            }
        }

        unreadMap
    }

    /**
     * Batch fetch episode progress data for multiple anime using MAL IDs only
     * Returns results with language preference applied per anime
     * @param animeList List of pairs containing (anilistId, malId)
     * @return Map of AniList IDs to MalSyncResponse with preferred language
     */
    suspend fun getBatchAnimeEpisodes(animeList: List<Pair<Int, Int?>>): Map<Int, MalSyncResponse> = withContext(Dispatchers.IO) {
        if (animeList.isEmpty()) return@withContext emptyMap()

        if (shouldSkipDueToNetworkFailures()) {
            Logger.log("MalSync: Skipping batch anime request due to repeated network failures")
            return@withContext emptyMap()
        }

        val resultMap = mutableMapOf<Int, MalSyncResponse>()

        // Filter to only include anime with MAL IDs
        val animeWithMalIds = animeList.filter { it.second != null }

        if (animeWithMalIds.isEmpty()) return@withContext emptyMap()

        // Create cache keys using MAL IDs only
        val cacheKeyMap = mutableMapOf<String, Pair<Int, String>>() // malId -> (anilistId, preferredLanguage)
        val cacheKeys = animeWithMalIds.map { (anilistId, malId) ->
            val preferredLanguage = MalSyncLanguageHelper.getPreferredLanguage(anilistId)
            val key = malId.toString()
            cacheKeyMap[key] = Pair(anilistId, preferredLanguage)
            key
        }

        // Batch fetch all anime (up to 50 at a time)
        cacheKeys.chunked(50).forEach { chunk ->
            try {
                // Check network status before each batch
                if (shouldSkipDueToNetworkFailures()) {
                    Logger.log("MalSync: Stopping anime batch processing due to network failures")
                    return@withContext resultMap
                }

                // Wait 5 seconds between requests (rate limiting)
                if (resultMap.isNotEmpty()) {
                    delay(5000)
                }

                val jsonArray = org.json.JSONArray(chunk)
                val jsonBody = JSONObject().apply {
                    put("malids", jsonArray)
                }.toString()

                Logger.log("MalSync batch anime request: $jsonBody")

                val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://api.malsync.moe/nc/mal/anime/POST/pr")
                    .post(requestBody)
                    .build()

                val response = executeRequest(request)

                if (response == null) {
                    Logger.log("MalSync batch anime API network error")
                    return@forEach
                }

                val body = response.body?.string()

                if (!response.isSuccessful) {
                    Logger.log("MalSync batch anime API error: ${response.code} - Body: $body")
                    return@forEach
                }

                if (body == null || body.isEmpty()) {
                    Logger.log("MalSync batch anime API returned empty response")
                    return@forEach
                }

                Logger.log("MalSync batch anime response (first 500 chars): ${body.take(500)}")

                try {
                    val type = object : TypeToken<List<BatchProgressResult>>() {}.type
                    val batchResults: List<BatchProgressResult> = gson.fromJson(body, type)

                    Logger.log("Parsed ${batchResults.size} anime results from batch")

                    // Process each anime's results with language preference
                    batchResults.forEach { result ->
                        val cacheKey = result.malid ?: return@forEach
                        val (anilistId, preferredLanguage) = cacheKeyMap[cacheKey] ?: return@forEach
                        val progress = getProgressForAnime(result.data ?: emptyList(), preferredLanguage)
                        if (progress != null) {
                            resultMap[anilistId] = progress
                        }
                    }
                } catch (parseError: Exception) {
                    Logger.log("Error parsing batch anime response: ${parseError.message}")
                    Logger.log("Response body: $body")
                }

            } catch (e: Exception) {
                Logger.log("Error fetching batch anime progress for chunk: ${e.message}")
                e.printStackTrace()
            }
        }

        resultMap
    }

    /**
     * Fetch quicklinks for a media. Try MAL endpoint first (if malId provided), then AniList endpoint as a fallback.
     * Returns a QuicklinksResponse or null on failure.
     */
    // mediaType should be either "manga" or "anime". Defaults to "manga" to remain backward-compatible.
    suspend fun getQuicklinks(anilistId: Int, malId: Int? = null, mediaType: String = "manga"): QuicklinksResponse? = withContext(Dispatchers.IO) {
        if (shouldSkipDueToNetworkFailures()) {
            return@withContext null
        }

        try {
            // mediaType expected to be "manga" or "anime"; build endpoint accordingly
            val typeSegment = if (mediaType.equals("anime", ignoreCase = true)) "anime" else "manga"

            // If malId is provided, try MAL endpoint first
            var url = if (malId != null) "https://api.malsync.moe/mal/$typeSegment/$malId" else "https://api.malsync.moe/mal/$typeSegment/anilist:$anilistId"
            var request = Request.Builder().url(url).build()
            var response = executeRequest(request)

            if (response == null) {
                Logger.log("MalSync quicklinks: Network error")
                return@withContext null
            }

            var body = response.body?.string()

            // If MAL request failed or returned empty and malId was provided, fallback to AniList endpoint
            if ((!response.isSuccessful || body == null || body.isEmpty()) && malId != null) {
                Logger.log("MalSync quicklinks: MAL id $malId failed, trying AniList id $anilistId")
                url = "https://api.malsync.moe/mal/$typeSegment/anilist:$anilistId"
                request = Request.Builder().url(url).build()
                response = executeRequest(request)

                if (response == null) {
                    Logger.log("MalSync quicklinks: Network error on fallback")
                    return@withContext null
                }

                body = response.body?.string()
            }

            if (!response.isSuccessful) {
                Logger.log("MalSync quicklinks API error: ${response.code}")
                return@withContext null
            }

            if (body == null || body.isEmpty() || body == "{}") {
                Logger.log("MalSync quicklinks: empty response for Anilist $anilistId" + (malId?.let { " or MAL $it" } ?: ""))
                return@withContext null
            }

            // Parse into QuicklinksResponse using Gson
            return@withContext try {
                gson.fromJson(body, QuicklinksResponse::class.java)
            } catch (e: Exception) {
                Logger.log("Error parsing quicklinks JSON: ${e.message}")
                null
            }
        } catch (e: Exception) {
            Logger.log("Error fetching MalSync quicklinks: ${e.message}")
            null
        }
    }

}