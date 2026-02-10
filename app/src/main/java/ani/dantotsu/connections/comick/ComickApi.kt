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

    // Cache for merged comic data by slug
    private val mergedComicCache = mutableMapOf<String, ComickComic>()

    /**
     * Check if a Comick entry's raw or engtl links match any of the external links from AniList
     * Uses exact URL matching only to prevent false positives
     * @param comickLinks The links object from Comick
     * @param externalLinks List of external link URLs from AniList
     * @return true if any link matches
     */
    private fun validateComickLinks(comickLinks: ComickLinks?, externalLinks: List<String>?): Boolean {
        if (comickLinks == null || externalLinks.isNullOrEmpty()) {
            Logger.log("Comick: Validation skipped - comickLinks: ${comickLinks != null}, externalLinks: ${externalLinks?.size ?: 0}")
            return false
        }

        Logger.log("Comick: Validating links - raw: ${comickLinks.raw}, engtl: ${comickLinks.engtl}")
        Logger.log("Comick: Against ${externalLinks.size} external link(s): $externalLinks")

        // Normalize URLs for comparison (remove trailing slashes, convert to lowercase)
        fun normalizeUrl(url: String?): String? {
            return url?.trim()?.lowercase()?.removeSuffix("/")
        }

        val normalizedExternalLinks = externalLinks.mapNotNull { normalizeUrl(it) }
        val rawNormalized = normalizeUrl(comickLinks.raw)
        val engtlNormalized = normalizeUrl(comickLinks.engtl)

        Logger.log("Comick: Normalized - raw: $rawNormalized, engtl: $engtlNormalized")
        Logger.log("Comick: Normalized external links: $normalizedExternalLinks")

        // Check if raw link matches any external link (exact match only)
        if (rawNormalized != null) {
            val matchFound = normalizedExternalLinks.any { it == rawNormalized }
            Logger.log("Comick: Raw link check - found match: $matchFound")
            if (matchFound) {
                Logger.log("Comick: ✓ Validated by matching raw link: ${comickLinks.raw}")
                return true
            }
        }

        // Check if engtl link matches any external link (exact match only)
        if (engtlNormalized != null) {
            val matchFound = normalizedExternalLinks.any { it == engtlNormalized }
            Logger.log("Comick: Engtl link check - found match: $matchFound")
            if (matchFound) {
                Logger.log("Comick: ✓ Validated by matching engtl link: ${comickLinks.engtl}")
                return true
            }
        }

        Logger.log("Comick: ✗ No matching links found")
        return false
    }

    /**
     * Merge multiple ComickComic instances, taking the best data from each
     * Priority: primary comic as base, fill in missing fields from others, use highest last_chapter
     */
    private fun mergeComics(primary: ComickComic, others: List<ComickComic>): ComickComic {
        // Collect all comics including primary
        val allComics = listOf(primary) + others

        // Find the highest last_chapter across all entries
        val highestLastChapter = allComics.mapNotNull { it.last_chapter }.maxOrNull()

        // Merge data: use primary's data, but fill in nulls from others
        return ComickComic(
            id = primary.id,
            title = primary.title ?: allComics.firstNotNullOfOrNull { it.title },
            desc = primary.desc ?: allComics.firstNotNullOfOrNull { it.desc },
            parsed = primary.parsed ?: allComics.firstNotNullOfOrNull { it.parsed },
            slug = primary.slug,
            country = primary.country ?: allComics.firstNotNullOfOrNull { it.country },
            status = primary.status ?: allComics.firstNotNullOfOrNull { it.status },
            year = primary.year ?: allComics.firstNotNullOfOrNull { it.year },
            bayesian_rating = primary.bayesian_rating ?: allComics.firstNotNullOfOrNull { it.bayesian_rating },
            rating_count = primary.rating_count ?: allComics.firstNotNullOfOrNull { it.rating_count },
            follow_rank = primary.follow_rank ?: allComics.firstNotNullOfOrNull { it.follow_rank },
            user_follow_count = primary.user_follow_count ?: allComics.firstNotNullOfOrNull { it.user_follow_count },
            last_chapter = highestLastChapter ?: primary.last_chapter, // Use highest last_chapter
            chapter_count = primary.chapter_count ?: allComics.firstNotNullOfOrNull { it.chapter_count },
            demographic = primary.demographic ?: allComics.firstNotNullOfOrNull { it.demographic },
            final_chapter = primary.final_chapter ?: allComics.firstNotNullOfOrNull { it.final_chapter },
            final_volume = primary.final_volume ?: allComics.firstNotNullOfOrNull { it.final_volume },
            has_anime = primary.has_anime ?: allComics.firstNotNullOfOrNull { it.has_anime },
            anime = primary.anime ?: allComics.firstNotNullOfOrNull { it.anime },
            mu_comics = primary.mu_comics ?: allComics.firstNotNullOfOrNull { it.mu_comics },
            translation_completed = primary.translation_completed ?: allComics.firstNotNullOfOrNull { it.translation_completed },
            content_rating = primary.content_rating ?: allComics.firstNotNullOfOrNull { it.content_rating },
            md_titles = primary.md_titles?.takeIf { it.isNotEmpty() }
                ?: allComics.firstNotNullOfOrNull { it.md_titles?.takeIf { list -> list.isNotEmpty() } },
            md_comic_md_genres = primary.md_comic_md_genres?.takeIf { it.isNotEmpty() }
                ?: allComics.firstNotNullOfOrNull { it.md_comic_md_genres?.takeIf { list -> list.isNotEmpty() } },
            md_covers = primary.md_covers?.takeIf { it.isNotEmpty() }
                ?: allComics.firstNotNullOfOrNull { it.md_covers?.takeIf { list -> list.isNotEmpty() } },
            links = primary.links ?: allComics.firstNotNullOfOrNull { it.links },
            recommendations = primary.recommendations?.takeIf { it.isNotEmpty() }
                ?: allComics.firstNotNullOfOrNull { it.recommendations?.takeIf { list -> list.isNotEmpty() } }
        )
    }

    /**
     * Fetch comic details from Comick API using the slug
     * @param slug The comic slug (e.g., "02-tonikaku-kawaii")
     * @param lang Language code (default: "en")
     * @param useCache Whether to check the merged data cache (default: true)
     * @return ComickResponse or null on failure
     */
    suspend fun getComicDetails(slug: String, lang: String = "en", useCache: Boolean = true): ComickResponse? = withContext(Dispatchers.IO) {
        try {
            // Check cache first if requested
            if (useCache) {
                val cachedMergedComic = mergedComicCache[slug]
                if (cachedMergedComic != null) {
                    Logger.log("Comick: Using cached merged data for slug: $slug")
                    // Still need to fetch for firstChap data
                    val response = fetchComicDetailsRaw(slug, lang)
                    return@withContext ComickResponse(cachedMergedComic, response?.firstChap)
                }
            }

            return@withContext fetchComicDetailsRaw(slug, lang)
        } catch (e: Exception) {
            Logger.log("Error fetching Comick data: ${e.message}")
            null
        }
    }

    /**
     * Internal function to fetch raw comic details from API without cache
     */
    private suspend fun fetchComicDetailsRaw(slug: String, lang: String = "en"): ComickResponse? {
        try {
            val url = "https://api.comick.dev/comic/$slug/?lang=$lang"
            val request = Request.Builder()
                .url(url)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body.string()

            if (!response.isSuccessful) {
                Logger.log("Comick API error: ${response.code}")
                return null
            }

            if (body == null || body.isEmpty() || body == "{}") {
                Logger.log("Comick: empty response for slug $slug")
                return null
            }

            return try {
                val comickResponse = gson.fromJson(body, ComickResponse::class.java)
                // Log the links object to debug
                Logger.log("Comick: Fetched details for slug '$slug' - links: ${comickResponse?.comic?.links}")
                comickResponse
            } catch (e: Exception) {
                Logger.log("Error parsing Comick JSON: ${e.message}")
                Logger.log("Response body preview: ${body.take(500)}")
                null
            }
        } catch (e: Exception) {
            Logger.log("Error in fetchComicDetailsRaw: ${e.message}")
            return null
        }
    }

    /**
     * Search for a comic on Comick and try to match by AniList or MAL ID
     * @param titles List of titles to try searching (will try each in order)
     * @param anilistId The AniList ID to match
     * @param malId The MAL ID to match (optional)
     * @param malSyncSlugs Optional list of slugs from MalSync to include in comparison
     * @param externalLinks External links from AniList to validate against raw/engtl links
     * @return The matching comic slug or null if not found
     */
    suspend fun searchAndMatchComic(
        titles: List<String>,
        anilistId: Int,
        malId: Int? = null,
        malSyncSlugs: List<String>? = null,
        externalLinks: List<String>? = null
    ): String? = withContext(Dispatchers.IO) {
        // Collect all valid comics from both MalSync and search
        val allValidComics = mutableListOf<ComickComic>()
        val validMuIds = mutableSetOf<String>() // Track MU IDs from confirmed valid entries
        val potentialMalSyncComics = mutableListOf<Pair<ComickComic, String?>>() // Comics with their MU IDs

        // Step 1: Process MalSync slugs first
        if (!malSyncSlugs.isNullOrEmpty()) {
            Logger.log("Comick: Processing ${malSyncSlugs.size} slug(s) from MalSync")
            for (slug in malSyncSlugs) {
                val details = getComicDetails(slug, useCache = false)
                val comic = details?.comic
                val links = comic?.links

                if (comic == null) continue

                // Verify this slug actually matches our IDs or external links
                var isMatch = false
                if (links?.al == anilistId.toString()) {
                    Logger.log("Comick: MalSync slug '$slug' verified by AniList ID (followers: ${comic.user_follow_count})")
                    isMatch = true
                }
                if (malId != null && links?.mal == malId.toString()) {
                    Logger.log("Comick: MalSync slug '$slug' verified by MAL ID (followers: ${comic.user_follow_count})")
                    isMatch = true
                }
                // Check if raw or engtl links match external links
                if (!isMatch && validateComickLinks(links, externalLinks)) {
                    Logger.log("Comick: MalSync slug '$slug' verified by matching external links (followers: ${comic.user_follow_count})")
                    isMatch = true
                }

                if (isMatch) {
                    allValidComics.add(comic)
                    // Track the MU ID of this valid entry
                    links?.mu?.let { muId ->
                        validMuIds.add(muId)
                        Logger.log("Comick: Tracked MU ID '$muId' from valid MalSync entry")
                    }
                } else {
                    // Store for potential validation by MU ID
                    potentialMalSyncComics.add(Pair(comic, links?.mu))
                }
            }

            // Check if any unmatched MalSync entries share MU IDs with valid entries
            if (validMuIds.isNotEmpty()) {
                for ((comic, muId) in potentialMalSyncComics) {
                    if (muId != null && muId in validMuIds) {
                        Logger.log("Comick: MalSync entry '${comic.slug}' validated by shared MU ID '$muId' (followers: ${comic.user_follow_count})")
                        allValidComics.add(comic)
                    }
                }
            }
        }

        // Step 2: Try search with each title to find additional entries
        Logger.log("Comick: Searching with ${titles.size} title(s)")
        for (title in titles) {
            if (title.isBlank()) continue

            val searchResult = searchWithTitle(title, anilistId, malId, returnAllValid = true, existingValidMuIds = validMuIds, externalLinks = externalLinks)
            if (searchResult is List<*>) {
                @Suppress("UNCHECKED_CAST")
                val searchComics = searchResult as List<ComickComic>
                // Add comics that aren't already in our list
                searchComics.forEach { comic ->
                    if (allValidComics.none { it.slug == comic.slug }) {
                        allValidComics.add(comic)
                    }
                }
            }
        }

        // Step 3: If we have any valid comics, select the best one
        if (allValidComics.isNotEmpty()) {
            Logger.log("Comick: Found ${allValidComics.size} total valid entries")
            return@withContext selectBestComic(allValidComics)
        }

        Logger.log("Comick: No valid entries found")
        return@withContext null
    }

    /**
     * Select the best comic from a list of valid entries
     * Priority: most followers, then merge data from others
     */
    private fun selectBestComic(validComics: List<ComickComic>): String? {
        // Take the one with most followers as the base
        val primaryComic = validComics.maxByOrNull { it.user_follow_count ?: 0 } ?: return null
        val primarySlug = primaryComic.slug ?: return null

        Logger.log("Comick: Selected entry with most followers: $primarySlug (followers: ${primaryComic.user_follow_count})")

        // Get other valid comics (excluding the primary)
        val otherComics = validComics.filter { it.slug != primarySlug }

        if (otherComics.isNotEmpty()) {
            // Merge data from all entries
            val mergedComic = mergeComics(primaryComic, otherComics)

            // Log the merging details
            val highestLastChapter = validComics.mapNotNull { it.last_chapter }.maxOrNull()
            if (highestLastChapter != null && highestLastChapter > (primaryComic.last_chapter ?: 0.0)) {
                Logger.log("Comick: Merged higher last_chapter: $highestLastChapter (was ${primaryComic.last_chapter})")
            }

            // Cache the merged data
            mergedComicCache[primarySlug] = mergedComic
            Logger.log("Comick: Cached merged data for slug: $primarySlug")
        } else {
            Logger.log("Comick: No other entries to merge, using primary entry as-is")
        }

        return primarySlug
    }

    /**
     * Helper function to search with a single title
     * @param returnAllValid If true, returns all valid comics instead of selecting the best
     * @param existingValidMuIds MU IDs already validated from MalSync entries
     * @param externalLinks External links from AniList to validate against raw/engtl links
     */
    private suspend fun searchWithTitle(
        title: String,
        anilistId: Int,
        malId: Int? = null,
        returnAllValid: Boolean = false,
        existingValidMuIds: Set<String>? = null,
        externalLinks: List<String>? = null
    ): Any? {
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
                return null
            }

            if (body == null || body.isEmpty() || body == "[]") {
                Logger.log("Comick search: no results for title $title")
                return null
            }

            val type = object : com.google.gson.reflect.TypeToken<List<ComickSearchResult>>() {}.type
            val searchResults: List<ComickSearchResult> = gson.fromJson(body, type)

            // Collect all matching results with their comic data
            val validComics = mutableListOf<ComickComic>()
            val validMuIds = mutableSetOf<String>() // Track MU IDs from confirmed valid entries
            // Start with existing MU IDs from MalSync if provided
            existingValidMuIds?.let { validMuIds.addAll(it) }
            val potentialComics = mutableListOf<Pair<ComickComic, String?>>() // Comics with their MU IDs

            // First pass: Find entries that match by AniList or MAL ID
            for (result in searchResults) {
                Logger.log("Comick: Checking search result: ${result.slug} - ${result.title}")
                // Fetch full details WITHOUT cache to get raw follower counts
                val details = getComicDetails(result.slug ?: continue, useCache = false)
                val links = details?.comic?.links
                val comic = details?.comic

                if (comic == null) {
                    Logger.log("Comick: Failed to get details for slug: ${result.slug}")
                    continue
                }

                Logger.log("Comick: Entry '${result.slug}' has - al: ${links?.al}, mal: ${links?.mal}, raw: ${links?.raw}, engtl: ${links?.engtl}")

                var isMatch = false

                // Check if AniList ID matches
                if (links?.al == anilistId.toString()) {
                    Logger.log("Comick: Found match by AniList ID: ${result.slug} (followers: ${comic.user_follow_count})")
                    isMatch = true
                }

                // Check if MAL ID matches
                if (malId != null && links?.mal == malId.toString()) {
                    Logger.log("Comick: Found match by MAL ID: ${result.slug} (followers: ${comic.user_follow_count})")
                    isMatch = true
                }

                // Check if raw or engtl links match external links
                if (!isMatch && validateComickLinks(links, externalLinks)) {
                    Logger.log("Comick: Found match by external links: ${result.slug} (followers: ${comic.user_follow_count})")
                    isMatch = true
                }

                if (isMatch) {
                    validComics.add(comic)
                    // Track the MU ID of this valid entry
                    links?.mu?.let { muId ->
                        validMuIds.add(muId)
                        Logger.log("Comick: Tracked MU ID '$muId' from valid entry")
                    }
                } else {
                    Logger.log("Comick: Entry '${result.slug}' did not match any validation criteria")
                    // Store for potential validation by MU ID in second pass
                    potentialComics.add(Pair(comic, links?.mu))
                }
            }

            // Second pass: Check if any unmatched entries share MU IDs with valid entries
            if (validMuIds.isNotEmpty()) {
                for ((comic, muId) in potentialComics) {
                    if (muId != null && muId in validMuIds) {
                        Logger.log("Comick: Entry '${comic.slug}' validated by shared MU ID '$muId' (followers: ${comic.user_follow_count})")
                        validComics.add(comic)
                    }
                }
            }

            // Return based on mode
            if (validComics.isNotEmpty()) {
                return if (returnAllValid) {
                    validComics // Return all for comparison
                } else {
                    selectBestComic(validComics) // Select and merge the best one
                }
            }

            return null
        } catch (e: Exception) {
            Logger.log("Error searching Comick with title '$title': ${e.message}")
            return null
        }
    }

    /**
     * Search for comics on Comick by title (for user-initiated search)
     * @param query The search query
     * @param allowAdult If false, filters out pornographic content (only allows "safe" and "suggestive")
     * @return List of matching comics
     */
    suspend fun searchComics(query: String, allowAdult: Boolean = true): List<ComickComic>? = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://api.comick.dev/v1.0/search/?type=comic&page=1&limit=25&showall=false&q=$encodedQuery&t=false"
            val request = Request.Builder()
                .url(url)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body.string()

            if (!response.isSuccessful) {
                Logger.log("Comick search API error: ${response.code}")
                return@withContext null
            }

            if (body.isNullOrEmpty() || body == "[]") {
                Logger.log("Comick search: no results for query '$query'")
                return@withContext emptyList()
            }

            val allResults = gson.fromJson(body, Array<ComickComic>::class.java).toList()

            // Filter by content rating if adult content is not allowed
            val results = if (!allowAdult) {
                allResults.filter { comic ->
                    val rating = comic.content_rating?.lowercase()
                    rating == "safe" || rating == "suggestive"
                }
            } else {
                allResults
            }

            Logger.log("Comick search: Found ${results.size} results for query '$query' (filtered: ${!allowAdult})")
            return@withContext results
        } catch (e: Exception) {
            Logger.log("Error searching Comick: ${e.message}")
            return@withContext null
        }
    }
}
