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
import java.util.concurrent.TimeUnit

object MalSyncApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun getLastChapter(anilistId: Int, malId: Int? = null): MalSyncResponse? = withContext(Dispatchers.IO) {
        try {
            // Try with Anilist ID first
            var url = "https://api.malsync.moe/nc/mal/manga/anilist:$anilistId/pr"
            var request = Request.Builder()
                .url(url)
                .build()

            var response = client.newCall(request).execute()
            var body = response.body?.string()

            // If Anilist ID fails or returns empty, try with MAL ID
            if ((!response.isSuccessful || body == null || body == "[]" || body.isEmpty()) && malId != null) {
                Logger.log("MalSync: Anilist ID $anilistId failed, trying MAL ID $malId")
                url = "https://api.malsync.moe/nc/mal/manga/$malId/pr"
                request = Request.Builder()
                    .url(url)
                    .build()
                response = client.newCall(request).execute()
                body = response.body?.string()
            }

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
            getProgress(results)
        } catch (e: Exception) {
            Logger.log("Error fetching MalSync data: ${e.message}")
            null
        }
    }

    /**
     * Implements the same progress selection logic as the web extension
     * Prioritizes entries based on ID and language with fallback logic
     * Only returns English language entries to avoid showing other languages
     */
    private fun getProgress(results: List<MalSyncResponse>): MalSyncResponse? {
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
     * Batch fetch progress data for multiple manga using the POST endpoint
     * Fetches up to 50 manga at once to reduce API calls
     * Supports both numeric MAL IDs and "anilist:ID" format
     * @param mediaList List of pairs containing (anilistId, malId)
     * @return Map of AniList IDs to MalSyncResponse
     */
    suspend fun getBatchProgressByMedia(mediaList: List<Pair<Int, Int?>>): Map<Int, MalSyncResponse> = withContext(Dispatchers.IO) {
        if (mediaList.isEmpty()) return@withContext emptyMap()

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
        cacheKeys.chunked(50).forEach { chunk ->
            try {
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

                val response = client.newCall(request).execute()
                val body = response.body?.string()

                if (!response.isSuccessful) {
                    Logger.log("MalSync batch API error: ${response.code} - Body: $body")
                    return@forEach
                }

                if (body == null || body.isEmpty()) {
                    Logger.log("MalSync batch API returned empty response")
                    return@forEach
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
                        val progress = getProgress(result.data ?: emptyList())
                        if (progress != null) {
                            resultMap[anilistId] = progress
                        }
                    }
                } catch (parseError: Exception) {
                    Logger.log("Error parsing batch response: ${parseError.message}")
                    Logger.log("Response body: $body")
                }

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
     * Fetch quicklinks for a media. Try MAL endpoint first (if malId provided), then AniList endpoint as a fallback.
     * Returns a QuicklinksResponse or null on failure.
     */
    // mediaType should be either "manga" or "anime". Defaults to "manga" to remain backward-compatible.
    suspend fun getQuicklinks(anilistId: Int, malId: Int? = null, mediaType: String = "manga"): QuicklinksResponse? = withContext(Dispatchers.IO) {
        try {
            // mediaType expected to be "manga" or "anime"; build endpoint accordingly
            val typeSegment = if (mediaType.equals("anime", ignoreCase = true)) "anime" else "manga"

            // If malId is provided, try MAL endpoint first
            var url = if (malId != null) "https://api.malsync.moe/mal/$typeSegment/$malId" else "https://api.malsync.moe/mal/$typeSegment/anilist:$anilistId"
            var request = Request.Builder().url(url).build()
            var response = client.newCall(request).execute()
            var body = response.body?.string()

            // If MAL request failed or returned empty and malId was provided, fallback to AniList endpoint
            if ((!response.isSuccessful || body == null || body.isEmpty()) && malId != null) {
                Logger.log("MalSync quicklinks: MAL id $malId failed, trying AniList id $anilistId")
                url = "https://api.malsync.moe/mal/$typeSegment/anilist:$anilistId"
                request = Request.Builder().url(url).build()
                response = client.newCall(request).execute()
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