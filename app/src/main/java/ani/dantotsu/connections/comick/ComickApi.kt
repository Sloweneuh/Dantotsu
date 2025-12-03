package ani.dantotsu.connections.comick

import ani.dantotsu.util.Logger
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ComickApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Fetch comic details from Comick API using the slug
     * @param slug The comic slug (e.g., "02-tonikaku-kawaii")
     * @param lang Language code (default: "en")
     * @return ComickResponse or null on failure
     */
    suspend fun getComicDetails(slug: String, lang: String = "en"): ComickResponse? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.comick.dev/comic/$slug/?lang=$lang"
            val request = Request.Builder()
                .url(url)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body.string()

            if (!response.isSuccessful) {
                Logger.log("Comick API error: ${response.code}")
                return@withContext null
            }

            if (body == null || body.isEmpty() || body == "{}") {
                Logger.log("Comick: empty response for slug $slug")
                return@withContext null
            }

            return@withContext try {
                gson.fromJson(body, ComickResponse::class.java)
            } catch (e: Exception) {
                Logger.log("Error parsing Comick JSON: ${e.message}")
                null
            }
        } catch (e: Exception) {
            Logger.log("Error fetching Comick data: ${e.message}")
            null
        }
    }

    /**
     * Search for a comic on Comick and try to match by AniList or MAL ID
     * @param title The manga title to search
     * @param anilistId The AniList ID to match
     * @param malId The MAL ID to match (optional)
     * @return The matching comic slug or null if not found
     */
    suspend fun searchAndMatchComic(title: String, anilistId: Int, malId: Int? = null): String? = withContext(Dispatchers.IO) {
        try {
            val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
            val url = "https://api.comick.dev/v1.0/search/?type=comic&page=1&limit=5&showall=false&q=$encodedTitle&t=false"
            val request = Request.Builder()
                .url(url)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body.string()

            if (!response.isSuccessful) {
                Logger.log("Comick search API error: ${response.code}")
                return@withContext null
            }

            if (body == null || body.isEmpty() || body == "[]") {
                Logger.log("Comick search: no results for title $title")
                return@withContext null
            }

            return@withContext try {
                val type = object : com.google.gson.reflect.TypeToken<List<ComickSearchResult>>() {}.type
                val searchResults: List<ComickSearchResult> = gson.fromJson(body, type)

                // Collect all matching results with their scores
                data class ScoredMatch(val slug: String, val score: Int, val followers: Int)
                val matches = mutableListOf<ScoredMatch>()

                // Try to match by AniList or MAL ID
                for (result in searchResults) {
                    // Fetch full details to get links
                    val details = getComicDetails(result.slug ?: continue)
                    val links = details?.comic?.links
                    val comic = details?.comic

                    var isMatch = false

                    // Check if AniList ID matches
                    if (links?.al == anilistId.toString()) {
                        Logger.log("Comick: Found match by AniList ID: ${result.slug}")
                        isMatch = true
                    }

                    // Check if MAL ID matches
                    if (malId != null && links?.mal == malId.toString()) {
                        Logger.log("Comick: Found match by MAL ID: ${result.slug}")
                        isMatch = true
                    }

                    if (isMatch && comic != null) {
                        // Calculate score based on data completeness
                        var score = 0
                        if (!comic.desc.isNullOrBlank()) score += 10
                        if (!comic.parsed.isNullOrBlank()) score += 10
                        if (comic.bayesian_rating != null) score += 5
                        if (comic.rating_count != null && comic.rating_count > 0) score += 5
                        if (comic.last_chapter != null && comic.last_chapter > 0) score += 5
                        if (comic.chapter_count != null && comic.chapter_count > 0) score += 5
                        if (!comic.md_comic_md_genres.isNullOrEmpty()) score += 10
                        if (comic.mu_comics?.mu_comic_categories?.isNotEmpty() == true) score += 10
                        if (comic.links?.mu != null) score += 5
                        if (!comic.md_titles.isNullOrEmpty()) score += 5

                        val followers = comic.user_follow_count ?: 0
                        matches.add(ScoredMatch(result.slug, score, followers))
                    }
                }

                // If we have matches, return the best one
                if (matches.isNotEmpty()) {
                    val bestMatch = matches.maxByOrNull {
                        // Primary: score (data completeness)
                        // Secondary: follower count
                        it.score * 10000 + it.followers
                    }
                    Logger.log("Comick: Selected best match: ${bestMatch?.slug} (score: ${bestMatch?.score}, followers: ${bestMatch?.followers})")
                    return@withContext bestMatch?.slug
                }

                Logger.log("Comick search: no ID match found for title $title")
                null
            } catch (e: Exception) {
                Logger.log("Error parsing Comick search results: ${e.message}")
                null
            }
        } catch (e: Exception) {
            Logger.log("Error searching Comick: ${e.message}")
            null
        }
    }
}

