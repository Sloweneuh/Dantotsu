package ani.dantotsu.connections.malsync

import ani.dantotsu.util.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
}

