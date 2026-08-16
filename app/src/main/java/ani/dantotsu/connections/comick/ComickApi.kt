package ani.dantotsu.connections.comick

import ani.dantotsu.util.Logger
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object ComickApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // Cache for merged comic data, keyed "$mediaType:$slug" — a slug is only unique within a
    // catalogue, so anime and comic entries can share one.
    private val mergedComicCache = mutableMapOf<String, ComickComic>()

    const val MEDIA_TYPE_MANGA = "manga"
    const val MEDIA_TYPE_ANIME = "anime"

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"

    /**
     * Everything under `/v1.0/` sits behind Cloudflare and answers a bare request with 403 — it
     * needs to look like the web client. The legacy unversioned paths don't care, but sending the
     * same headers there is harmless, so every request goes through this.
     */
    private fun request(url: String, accept: String = "application/json"): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", accept)
            .header("Referer", "https://comick.dev/")
            .build()

    /** The site path for an entry: anime and comics live under different roots. */
    fun webUrl(slug: String, mediaType: String = MEDIA_TYPE_MANGA): String =
        if (mediaType == MEDIA_TYPE_ANIME) "https://comick.dev/anime/$slug"
        else "https://comick.dev/comic/$slug"

    data class FilterOption(
        val slug: String,
        val name: String,
        val id: Int? = null,
    )

    val SEARCH_SORT_LABELS = mapOf(
        "created_at" to "Latest",
        "uploaded" to "Last Updated",
        "rating" to "Rating",
        "average_rating" to "Average Rating",
        "user_follow_count" to "Popular",
    )

    val LIST_SORT_LABELS = mapOf(
        "title" to "Title",
        "bayesian_rating" to "Rating",
        "uploaded_at" to "Last Upload",
        "created_at" to "Date Added",
    )

    @Volatile
    private var genreCache: List<FilterOption>? = null

    @Volatile
    private var categoryCache: List<FilterOption>? = null

    // Slug -> name lookups seeded from navigation (e.g. tapping a genre/tag chip) before the full
    // list has been fetched. Kept separate from genreCache/categoryCache so seeding never fools
    // getGenres()/getCategories() into thinking the full list was already fetched.
    private val genreNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val categoryNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun getGenres(): List<FilterOption> = withContext(Dispatchers.IO) {
        val cached = genreCache
        if (!cached.isNullOrEmpty()) {
            return@withContext cached
        }
        val fetched = fetchFilterOptions("https://api.comick.dev/genre/")
        if (fetched.isNotEmpty()) {
            genreCache = fetched
        }
        fetched
    }

    suspend fun getCategories(useCache: Boolean = true): List<FilterOption> = withContext(Dispatchers.IO) {
        val cached = categoryCache
        if (useCache && !cached.isNullOrEmpty()) {
            return@withContext cached
        }
        val fetched = fetchFilterOptions("https://api.comick.dev/category/")
        if (fetched.isNotEmpty()) {
            categoryCache = fetched
        }
        fetched
    }

    fun resolveGenreName(slug: String): String? =
        genreCache?.firstOrNull { it.slug.equals(slug, ignoreCase = true) }?.name
            ?: genreNameCache[slug.lowercase()]

    fun resolveGenreId(slug: String): Int? = genreCache?.firstOrNull { it.slug.equals(slug, ignoreCase = true) }?.id

    fun resolveGenreSlugById(id: Int): String? = genreCache?.firstOrNull { it.id == id }?.slug

    fun resolveGenreSlugByName(name: String): String? = genreCache?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.slug

    fun resolveCategoryName(slug: String): String? =
        categoryCache?.firstOrNull { it.slug.equals(slug, ignoreCase = true) }?.name
            ?: categoryNameCache[slug.lowercase()]

    /**
     * Server-side search over Comick's ~9,500 tags/categories, instead of fetching and
     * filtering the full cached list client-side. Backs the category search box in
     * [ani.dantotsu.media.ComickSearchFilterBottomSheet].
     * @param query Search text (Comick caps this at 40 chars: GET /comic-tags/search?k=)
     * @return Matching tags ranked by Comick's own relevance/popularity, or empty on failure
     */
    suspend fun searchCategories(query: String): List<FilterOption> = withContext(Dispatchers.IO) {
        val trimmed = query.trim().take(40)
        if (trimmed.isBlank()) return@withContext emptyList()
        val url = "https://api.comick.dev/comic-tags/search"
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("k", trimmed)
            ?.build()
            ?.toString()
            ?: return@withContext emptyList()
        fetchFilterOptions(url)
    }

    /**
     * Pre-populate a single known slug→name entry so the chip label is correct before the full
     * genre list is fetched. Writes only to [genreNameCache], never to [genreCache], so it can't
     * make getGenres() think the full list was already fetched and skip the real request.
     */
    fun seedGenreCache(slug: String, name: String) {
        genreNameCache.putIfAbsent(slug.lowercase(), name)
    }

    /**
     * Pre-populate a single known slug→name entry so the chip label is correct before the full
     * category list is fetched. Writes only to [categoryNameCache], never to [categoryCache], so
     * it can't make getCategories() think the full list was already fetched and skip the real request.
     */
    fun seedCategoryCache(slug: String, name: String) {
        categoryNameCache.putIfAbsent(slug.lowercase(), name)
    }

    fun resolveCountryName(code: String): String? {
        return when (code.lowercase()) {
            "jp" -> ani.dantotsu.currContext()?.getString(ani.dantotsu.R.string.comick_type_jp)
            "kr" -> ani.dantotsu.currContext()?.getString(ani.dantotsu.R.string.comick_type_kr)
            "cn" -> ani.dantotsu.currContext()?.getString(ani.dantotsu.R.string.comick_type_cn)
            "others" -> ani.dantotsu.currContext()?.getString(ani.dantotsu.R.string.comick_type_others)
            else -> null
        }
    }

    private fun fetchFilterOptions(url: String): List<FilterOption> {
        return try {
            val request = request(url)
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.log("Comick filter API error: ${response.code}")
                return emptyList()
            }
            val body = response.body.string()
            if (body.isBlank()) return emptyList()
            parseFilterOptions(gson.fromJson(body, JsonElement::class.java))
        } catch (e: Exception) {
            Logger.log("Comick filter parsing error: ${e.message}")
            emptyList()
        }
    }

    private fun parseFilterOptions(root: JsonElement?): List<FilterOption> {
        if (root == null || root.isJsonNull) return emptyList()

        val arr: JsonArray = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> {
                val obj = root.asJsonObject
                val listField = listOf("results", "data", "items")
                    .firstNotNullOfOrNull { key -> obj.get(key)?.takeIf { it.isJsonArray } }
                listField?.asJsonArray ?: JsonArray()
            }
            else -> JsonArray()
        }

        return arr.mapNotNull { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val slug = obj.get("slug")?.asString?.trim().orEmpty().ifBlank {
                obj.get("name")?.asString?.trim().orEmpty().ifBlank {
                    obj.get("title")?.asString?.trim().orEmpty().ifBlank { null }
                }
            } ?: return@mapNotNull null

            val name = obj.get("name")?.asString?.trim().orEmpty().ifBlank {
                obj.get("title")?.asString?.trim().orEmpty().ifBlank { slug }
            }

            val id = runCatching { obj.get("id")?.takeIf { !it.isJsonNull }?.asInt }.getOrNull()

            FilterOption(slug = slug, name = name, id = id)
        }.distinctBy { it.slug.lowercase() }
    }

    /**
     * Check if a Comick entry's raw or engtl links match any of the external links from AniList
     * Uses exact URL matching only to prevent false positives
     * @param comickLinks The links object from Comick
     * @param externalLinks List of external link URLs from AniList
     * @return true if any link matches
     */
    private fun validateComickLinks(comickLinks: ComickLinks?, externalLinks: List<String>?): Boolean {
        if (comickLinks == null || externalLinks.isNullOrEmpty()) {
            return false
        }

        // Normalize URLs for comparison (remove trailing slashes, convert to lowercase)
        fun normalizeUrl(url: String?): String? {
            return url?.trim()?.lowercase()?.removeSuffix("/")
        }

        val normalizedExternalLinks = externalLinks.mapNotNull { normalizeUrl(it) }
        val rawNormalized = normalizeUrl(comickLinks.raw)
        val engtlNormalized = normalizeUrl(comickLinks.engtl)


        // Check if raw link matches any external link (exact match only)
        if (rawNormalized != null) {
            val matchFound = normalizedExternalLinks.any { it == rawNormalized }
            if (matchFound) {
                return true
            }
        }

        // Check if engtl link matches any external link (exact match only)
        if (engtlNormalized != null) {
            val matchFound = normalizedExternalLinks.any { it == engtlNormalized }
            if (matchFound) {
                return true
            }
        }

        // Check if mu link matches any MangaUpdates external link
        val mu = comickLinks.mu?.trim()
        if (!mu.isNullOrBlank()) {
            val muNumeric = mu.toLongOrNull()
            val possibleMuUrls = buildList {
                if (muNumeric != null) {
                    add(normalizeUrl("https://www.mangaupdates.com/series.html?id=$muNumeric"))
                    add(normalizeUrl("https://www.mangaupdates.com/series/${muNumeric.toString(36)}"))
                }
                add(normalizeUrl("https://www.mangaupdates.com/series/$mu"))
            }.filterNotNull()
            val muMatchFound = normalizedExternalLinks.any { extLink -> possibleMuUrls.any { it == extLink } }
            if (muMatchFound) {
                return true
            }
        }

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
            hid = primary.hid ?: allComics.firstNotNullOfOrNull { it.hid },
            title = primary.title ?: allComics.firstNotNullOfOrNull { it.title },
            desc = primary.desc ?: allComics.firstNotNullOfOrNull { it.desc },
            parsed = primary.parsed ?: allComics.firstNotNullOfOrNull { it.parsed },
            slug = primary.slug,
            country = primary.country ?: allComics.firstNotNullOfOrNull { it.country },
            status = primary.status ?: allComics.firstNotNullOfOrNull { it.status },
            year = primary.year ?: allComics.firstNotNullOfOrNull { it.year },
            bayesian_rating = primary.bayesian_rating
                ?: allComics.firstNotNullOfOrNull { it.bayesian_rating },
            rating_count = primary.rating_count
                ?: allComics.firstNotNullOfOrNull { it.rating_count },
            follow_rank = primary.follow_rank ?: allComics.firstNotNullOfOrNull { it.follow_rank },
            user_follow_count = primary.user_follow_count
                ?: allComics.firstNotNullOfOrNull { it.user_follow_count },
            last_chapter = highestLastChapter ?: primary.last_chapter, // Use highest last_chapter
            chapter_count = primary.chapter_count
                ?: allComics.firstNotNullOfOrNull { it.chapter_count },
            demographic = primary.demographic ?: allComics.firstNotNullOfOrNull { it.demographic },
            final_chapter = primary.final_chapter
                ?: allComics.firstNotNullOfOrNull { it.final_chapter },
            final_volume = primary.final_volume
                ?: allComics.firstNotNullOfOrNull { it.final_volume },
            has_anime = primary.has_anime ?: allComics.firstNotNullOfOrNull { it.has_anime },
            anime = primary.anime ?: allComics.firstNotNullOfOrNull { it.anime },
            mu_comics = primary.mu_comics ?: allComics.firstNotNullOfOrNull { it.mu_comics },
            translation_completed = primary.translation_completed
                ?: allComics.firstNotNullOfOrNull { it.translation_completed },
            content_rating = primary.content_rating
                ?: allComics.firstNotNullOfOrNull { it.content_rating },
            md_titles = primary.md_titles?.takeIf { it.isNotEmpty() }
                ?: allComics.firstNotNullOfOrNull { it.md_titles?.takeIf { list -> list.isNotEmpty() } },
            md_comic_md_genres = primary.md_comic_md_genres?.takeIf { it.isNotEmpty() }
                ?: allComics.firstNotNullOfOrNull { it.md_comic_md_genres?.takeIf { list -> list.isNotEmpty() } },
            md_covers = primary.md_covers?.takeIf { it.isNotEmpty() }
                ?: allComics.firstNotNullOfOrNull { it.md_covers?.takeIf { list -> list.isNotEmpty() } },
            links = primary.links ?: allComics.firstNotNullOfOrNull { it.links },
            recommendations = primary.recommendations?.takeIf { it.isNotEmpty() }
                ?: allComics.firstNotNullOfOrNull { it.recommendations?.takeIf { list -> list.isNotEmpty() } },
            reviews = primary.reviews?.takeIf { it.isNotEmpty() }
                ?: allComics.firstNotNullOfOrNull { it.reviews?.takeIf { list -> list.isNotEmpty() } },
            media_type = primary.media_type ?: allComics.firstNotNullOfOrNull { it.media_type },
            anime_profiles = primary.anime_profiles
                ?: allComics.firstNotNullOfOrNull { it.anime_profiles },
            trailers = primary.trailers?.takeIf { it.isNotEmpty() }
                ?: allComics.firstNotNullOfOrNull { it.trailers?.takeIf { list -> list.isNotEmpty() } },
            anime_companies_to_md_comics = primary.anime_companies_to_md_comics?.takeIf { it.isNotEmpty() }
                ?: allComics.firstNotNullOfOrNull { it.anime_companies_to_md_comics?.takeIf { list -> list.isNotEmpty() } },
            to_year = primary.to_year ?: allComics.firstNotNullOfOrNull { it.to_year },
            rating = primary.rating ?: allComics.firstNotNullOfOrNull { it.rating },
        )
    }

    /**
     * Fetch comic details from Comick API using the slug
     * @param slug The comic slug (e.g., "02-tonikaku-kawaii")
     * @param lang Language code (default: "en")
     * @param useCache Whether to check the merged data cache (default: true)
     * @return ComickResponse or null on failure
     */
    suspend fun getComicDetails(
        slug: String,
        lang: String = "en",
        useCache: Boolean = true,
        mediaType: String = MEDIA_TYPE_MANGA
    ): ComickResponse? = withContext(Dispatchers.IO) {
        try {
            // Check cache first if requested
            if (useCache) {
                val cachedMergedComic = mergedComicCache["$mediaType:$slug"]
                if (cachedMergedComic != null) {
                    // Still need to fetch for firstChap data
                    val response = fetchComicDetailsRaw(slug, lang, mediaType)
                    return@withContext ComickResponse(cachedMergedComic, response?.firstChap)
                }
            }

            return@withContext fetchComicDetailsRaw(slug, lang, mediaType)
        } catch (e: Exception) {
            Logger.log("Error fetching Comick data: ${e.message}")
            null
        }
    }

    /**
     * Fetch an anime entry's full details. This must go through `/v1.0/` — the legacy
     * `/comic/{slug}/` path answers 200 for an anime slug but drops `anime_profiles`, `trailers`,
     * the studio list and the streaming links, i.e. everything that makes it an anime entry.
     */
    suspend fun getAnimeDetails(slug: String, useCache: Boolean = true): ComickResponse? =
        getComicDetails(slug, useCache = useCache, mediaType = MEDIA_TYPE_ANIME)

    /**
     * Internal function to fetch raw comic details from API without cache
     */
    private suspend fun fetchComicDetailsRaw(
        slug: String,
        lang: String = "en",
        mediaType: String = MEDIA_TYPE_MANGA
    ): ComickResponse? {
        try {
            val url = if (mediaType == MEDIA_TYPE_ANIME) {
                "https://api.comick.dev/v1.0/comic/$slug/?media_type=anime"
            } else {
                "https://api.comick.dev/comic/$slug/?lang=$lang"
            }
            val request = request(url)

            val response = client.newCall(request).execute()
            val body = response.body.string()

            if (!response.isSuccessful) {
                Logger.log("Comick API error: ${response.code} for $url")
                return null
            }

            if (body == null || body.isEmpty() || body == "{}") {
                Logger.log("Comick: empty response for slug $slug")
                return null
            }

            return try {
                val comickResponse = gson.fromJson(body, ComickResponse::class.java)
                // Log the links object to debug
                comickResponse
            } catch (e: Exception) {
                Logger.log("Error parsing Comick JSON: ${e.message}")
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
        externalLinks: List<String>? = null,
        mediaType: String = MEDIA_TYPE_MANGA
    ): String? = withContext(Dispatchers.IO) {
        // Collect all valid comics from both MalSync and search
        val allValidComics = mutableListOf<ComickComic>()
        val validMuIds = mutableSetOf<String>() // Track MU IDs from confirmed valid entries
        val potentialMalSyncComics = mutableListOf<Pair<ComickComic, String?>>() // Comics with their MU IDs

        // Step 1: Process MalSync slugs first
        if (!malSyncSlugs.isNullOrEmpty()) {
            for (slug in malSyncSlugs) {
                val details = getComicDetails(slug, useCache = false, mediaType = mediaType)
                val comic = details?.comic
                val links = comic?.links

                if (comic == null) continue

                // Verify this slug actually matches our IDs or external links
                var isMatch = false
                if (links?.al == anilistId.toString()) {
                    isMatch = true
                }
                if (malId != null && links?.mal == malId.toString()) {
                    isMatch = true
                }
                // Check if raw or engtl links match external links
                if (!isMatch && validateComickLinks(links, externalLinks)) {
                    isMatch = true
                }

                if (isMatch) {
                    allValidComics.add(comic)
                    // Track the MU ID of this valid entry
                    links?.mu?.let { muId ->
                        validMuIds.add(muId)
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
                        allValidComics.add(comic)
                    }
                }
            }
        }

        // Step 2: Try search with each title to find additional entries
        for (title in titles) {
            if (title.isBlank()) continue

            val searchResult = searchWithTitle(title, anilistId, malId, returnAllValid = true, existingValidMuIds = validMuIds, externalLinks = externalLinks, mediaType = mediaType)
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
            return@withContext selectBestComic(allValidComics, mediaType)
        }

        return@withContext null
    }

    /**
     * Match an AniList *anime* entry to a Comick anime entry. Anime entries carry the AniList and
     * MAL ids of the anime (not of its source manga) in `links.al`/`links.mal`, so the same
     * id-validated matching used for comics applies unchanged — only the catalogue differs.
     */
    suspend fun searchAndMatchAnime(
        titles: List<String>,
        anilistId: Int,
        malId: Int? = null,
        malSyncSlugs: List<String>? = null,
        externalLinks: List<String>? = null
    ): String? = searchAndMatchComic(
        titles, anilistId, malId, malSyncSlugs, externalLinks, MEDIA_TYPE_ANIME
    )

    /**
     * Select the best comic from a list of valid entries
     * Priority: most followers, then merge data from others
     */
    private fun selectBestComic(
        validComics: List<ComickComic>,
        mediaType: String = MEDIA_TYPE_MANGA
    ): String? {
        // Take the one with most followers as the base
        val primaryComic = validComics.maxByOrNull { it.user_follow_count ?: 0 } ?: return null
        val primarySlug = primaryComic.slug ?: return null


        // Get other valid comics (excluding the primary)
        val otherComics = validComics.filter { it.slug != primarySlug }

        if (otherComics.isNotEmpty()) {
            // Merge data from all entries
            val mergedComic = mergeComics(primaryComic, otherComics)

            // Log the merging details
            val highestLastChapter = validComics.mapNotNull { it.last_chapter }.maxOrNull()
            if (highestLastChapter != null && highestLastChapter > (primaryComic.last_chapter ?: 0.0)) {
            }

            // Cache the merged data
            mergedComicCache["$mediaType:$primarySlug"] = mergedComic
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
        externalLinks: List<String>? = null,
        mediaType: String = MEDIA_TYPE_MANGA
    ): Any? {
        try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val mediaTypeParam =
                if (mediaType == MEDIA_TYPE_ANIME) "&media_type=anime" else ""
            val url = "https://api.comick.dev/v1.0/search/?type=comic&page=1&limit=5&showall=false&q=$encodedTitle&t=false$mediaTypeParam"
            val request = request(url)

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

            val type = object : TypeToken<List<ComickSearchResult>>() {}.type
            val searchResults: List<ComickSearchResult> = gson.fromJson(body, type)

            // Collect all matching results with their comic data
            val validComics = mutableListOf<ComickComic>()
            val validMuIds = mutableSetOf<String>() // Track MU IDs from confirmed valid entries
            // Start with existing MU IDs from MalSync if provided
            existingValidMuIds?.let { validMuIds.addAll(it) }
            val potentialComics = mutableListOf<Pair<ComickComic, String?>>() // Comics with their MU IDs

            // First pass: Find entries that match by AniList or MAL ID
            for (result in searchResults) {
                // Fetch full details WITHOUT cache to get raw follower counts
                val details = getComicDetails(result.slug ?: continue, useCache = false, mediaType = mediaType)
                val links = details?.comic?.links
                val comic = details?.comic

                if (comic == null) {
                    Logger.log("Comick: Failed to get details for slug: ${result.slug}")
                    continue
                }

                var isMatch = false

                // Check if AniList ID matches
                if (links?.al == anilistId.toString()) {
                    isMatch = true
                }

                // Check if MAL ID matches
                if (malId != null && links?.mal == malId.toString()) {
                    isMatch = true
                }

                // Check if raw or engtl links match external links
                if (!isMatch && validateComickLinks(links, externalLinks)) {
                    isMatch = true
                }

                if (isMatch) {
                    validComics.add(comic)
                    // Track the MU ID of this valid entry
                    links?.mu?.let { muId ->
                        validMuIds.add(muId)
                    }
                } else {
                    // Store for potential validation by MU ID in second pass
                    potentialComics.add(Pair(comic, links?.mu))
                }
            }

            // Second pass: Check if any unmatched entries share MU IDs with valid entries
            if (validMuIds.isNotEmpty()) {
                for ((comic, muId) in potentialComics) {
                    if (muId != null && muId in validMuIds) {
                        validComics.add(comic)
                    }
                }
            }

            // Return based on mode
            if (validComics.isNotEmpty()) {
                return if (returnAllValid) {
                    validComics // Return all for comparison
                } else {
                    selectBestComic(validComics, mediaType) // Select and merge the best one
                }
            }

            return null
        } catch (e: Exception) {
            Logger.log("Error searching Comick with title '$title': ${e.message}")
            return null
        }
    }

    /**
     * Scrape the covers page for a comic from comick.dev.
     * URL pattern: https://comick.dev/comic/{slug}/cover
     *
     * The page is Next.js SSR, so the covers are present in the raw HTML.
     * Each cover is a `div.h-30.relative`:
     *   - child 1: `<div><img src="https://meo.comick.pictures/{b2key}"></div>`
     *   - child 2: `<div>…volume number…</div>`
     *
     * @param slug The comic slug (e.g., "01-a-transmigrator-s-privilege")
     * @return List of ComickCover objects, or null on failure
     */
    suspend fun getCovers(slug: String): List<ComickCover>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://comick.dev/comic/$slug/cover"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.log("Comick covers: HTTP ${response.code} for slug=$slug")
                return@withContext null
            }
            val html = response.body.string()
            val doc = Jsoup.parse(html)

            // Each cover is inside a wrapper `div` with class containing "relative" (e.g. "h-30 relative").
            // The image may be nested (inside a button) and may use `src`, `data-src` or `srcset`.
            val coverDivs = doc.select("div.h-30.relative, div.relative")
                .mapNotNull { wrapper ->
                    // Only keep wrappers that contain an image element
                    wrapper.selectFirst("img[src], img[data-src], img[srcset]")?.let { wrapper }
                }
                .distinctBy { it }

            if (coverDivs.isEmpty()) {
                return@withContext null
            }

            val baseUrl = "https://meo.comick.pictures/"
            val covers = coverDivs.mapNotNull { wrapper ->
                val img = wrapper.selectFirst("img[src], img[data-src], img[srcset]") ?: return@mapNotNull null

                // Prefer `src`, then `data-src`, then parse the highest-quality URL from `srcset`.
                var src = img.attr("src").trim()
                if (src.isBlank()) src = img.attr("data-src").trim()
                if (src.isBlank()) {
                    val srcset = img.attr("srcset").trim().ifBlank { img.attr("data-srcset").trim() }
                    if (srcset.isNotBlank()) {
                        // srcset format: "url1 144w, url2 240w, ..." — pick the last (highest res) URL
                        src = srcset.split(",").map { it.trim().split(" ")[0] }.lastOrNull() ?: ""
                    }
                }

                if (src.isBlank()) return@mapNotNull null

                // Normalize protocol-relative URLs
                if (src.startsWith("//")) src = "https:$src"

                // Extract a concise key (filename) when possible, otherwise keep the full URL
                val b2key = if (src.startsWith(baseUrl)) src.removePrefix(baseUrl) else src.substringAfterLast('/')

                // Volume badge: the child `div` that doesn't contain an img (usually the small number badge)
                val vol = wrapper.children()
                    .firstOrNull { child -> child.tagName() == "div" && child.selectFirst("img") == null }
                    ?.text()?.trim()?.takeIf { it.isNotBlank() }

                ComickCover(vol = vol, w = null, h = null, b2key = b2key)
            }

            covers.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Logger.log("Comick covers error for slug=$slug: ${e.message}")
            null
        }
    }

    /**
     * Fetch all public custom lists that contain a specific comic (identified by HID).
     * @param hid The comic's HID (e.g. "NYH8PELZ")
     * @param allowAdult Mirrors the accept_mature/erotic/pornographic_content flags the
     * comick.dev web client sends; the API filters returned lists by content rating using
     * these, defaulting to "safe only" when they're absent (many lists are erotica/suggestive).
     * @return List of custom lists, or null on failure
     */
    suspend fun getComicLists(hid: String, allowAdult: Boolean = false): List<ComickCustomList>? = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = "https://api.comick.dev/list/comic/$hid"
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?: return@withContext null
            urlBuilder.addQueryParameter("limit", "60")
            urlBuilder.addQueryParameter("accept_mature_content", allowAdult.toString())
            urlBuilder.addQueryParameter("accept_erotic_content", allowAdult.toString())
            urlBuilder.addQueryParameter("accept_pornographic_content", allowAdult.toString())
            val url = urlBuilder.build().toString()
            val request = request(url)
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.log("Comick lists API error: ${response.code} for $url")
                return@withContext null
            }
            val body = response.body.string()
            Logger.log("Comick lists API response for hid $hid: ${body.take(500)}")
            if (body.isBlank() || body == "[]") return@withContext emptyList()
            gson.fromJson(body, Array<ComickCustomList>::class.java).toList()
        } catch (e: Exception) {
            Logger.log("Error fetching comic lists for hid $hid: ${e.message}")
            null
        }
    }

    /**
     * Fetch chapters for a comic by its HID.
     * Paginates automatically until all chapters are retrieved.
     * @param hid The comic HID
     * @param lang Language code (default "en")
     * @return List of ComickChapter, newest first, or empty list on failure
     */
    suspend fun getChapters(hid: String, lang: String = "en"): List<ComickChapter> = withContext(Dispatchers.IO) {
        val all = mutableListOf<ComickChapter>()
        val limit = 300
        var page = 0
        try {
            while (true) {
                val url = "https://api.comick.dev/comic/$hid/chapters?lang=$lang&limit=$limit&page=$page&chap-order=0"
                val request = request(url)
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) break
                val body = response.body.string()
                if (body.isBlank()) break
                val obj = gson.fromJson(body, com.google.gson.JsonObject::class.java) ?: break
                val arr = obj.getAsJsonArray("chapters") ?: break
                if (arr.size() == 0) break
                val page_chapters = gson.fromJson(arr, Array<ComickChapter>::class.java).toList()
                all.addAll(page_chapters)
                // Stop when the page returned fewer results than requested (last page)
                if (page_chapters.size < limit) break
                page++
            }
        } catch (e: Exception) {
            Logger.log("Error fetching chapters for hid $hid: ${e.message}")
        }
        // Resolve chapters the same way the AniList/MangaUpdates/extension paths do: trust the
        // source order and reverse it, rather than re-sorting by parsed number. The API is queried
        // with chap-order=0 (newest-first) and paginated in that order, so reversing yields
        // oldest-first. This keeps equal-numbered chapters (e.g. two "Chapter 0") in the same
        // relative order as everywhere else instead of flipping them.
        all.reversed()
    }

    /**
     * Fetch the latest (highest-numbered) chapter for a comic.
     * Pass [nearChapter] (from ComickComic.last_chapter) to query near that number
     * so the small fetch window is guaranteed to include it.
     */
    suspend fun getLatestChapter(
        hid: String,
        lang: String = "en",
        nearChapter: Double? = null
    ): ComickChapter? = withContext(Dispatchers.IO) {
        try {
            val chapParam = nearChapter?.let {
                "&chap=${if (it % 1.0 == 0.0) it.toInt() else it}"
            } ?: ""
            val url = "https://api.comick.dev/comic/$hid/chapters?lang=$lang&limit=10$chapParam&chap-order=0"
            val request = request(url)
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body.string()
            if (body.isBlank()) return@withContext null
            val obj = gson.fromJson(body, com.google.gson.JsonObject::class.java) ?: return@withContext null
            val arr = obj.getAsJsonArray("chapters") ?: return@withContext null
            if (arr.size() == 0) return@withContext null
            val chapters = gson.fromJson(arr, Array<ComickChapter>::class.java).toList()
            // Take the chapter with the highest chapter number in the returned window
            chapters.maxByOrNull { it.chap?.toDoubleOrNull() ?: -1.0 }
        } catch (e: Exception) {
            Logger.log("Error fetching latest chapter for hid $hid: ${e.message}")
            null
        }
    }

    /**
     * Fetch all comics in a specific custom list.
     * @param userId The list owner's user ID (UUID)
     * @param listSlug The list slug
     * @return List of comics in the list, or null on failure
     */
    suspend fun getListComics(userId: String, listSlug: String): List<ComickListComic>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.comick.dev/user/$userId/follows?custom_list=$listSlug"
            val request = request(url)
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.log("Comick list comics API error: ${response.code} for $url")
                return@withContext null
            }
            val body = response.body.string()
            if (body.isBlank() || body == "[]") return@withContext emptyList()
            gson.fromJson(body, Array<ComickFollowEntry>::class.java).toList()
                .mapNotNull { entry -> entry.md_comics?.copy(created_at = entry.created_at) }
        } catch (e: Exception) {
            Logger.log("Error fetching list comics for user $userId, list $listSlug: ${e.message}")
            null
        }
    }

    /**
     * Fetch all public custom lists for a given user.
     * @param userId The user's UUID
     * @return List of custom lists, or null on failure
     */
    suspend fun getUserLists(userId: String): List<ComickCustomList>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.comick.dev/list/list?user_id=$userId&limit=100"
            val request = request(url)
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.log("Comick user lists API error: ${response.code} for $url")
                return@withContext null
            }
            val body = response.body.string()
            if (body.isBlank() || body == "[]") return@withContext emptyList()
            gson.fromJson(body, Array<ComickCustomList>::class.java).toList()
        } catch (e: Exception) {
            Logger.log("Error fetching user lists for userId $userId: ${e.message}")
            null
        }
    }

    /**
     * Search for a Comick entry matching a MangaUpdates series by checking only [ComickLinks.mu].
     * Tries each title in order, fetches full details for each result, and returns the slug of
     * the comic whose `links.mu` equals the numeric or base-36 representation of [muSeriesId].
     */
    suspend fun searchAndMatchComicByMuId(
        titles: List<String>,
        muSeriesId: Long
    ): String? = withContext(Dispatchers.IO) {
        val muIdNumeric = muSeriesId.toString()          // e.g. "123456"
        val muIdBase36  = muSeriesId.toString(36)        // e.g. "abc12"

        for (title in titles) {
            if (title.isBlank()) continue
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val url = "https://api.comick.dev/v1.0/search/?type=comic&page=1&limit=5&showall=false&q=$encodedTitle&t=false"
            val request = request(url)
            val response = try { client.newCall(request).execute() } catch (e: Exception) { continue }
            val body = response.body.string()
            if (!response.isSuccessful || body.isNullOrEmpty() || body == "[]") continue

            val type = object : TypeToken<List<ComickSearchResult>>() {}.type
            val results: List<ComickSearchResult> = try { gson.fromJson(body, type) } catch (e: Exception) { continue }

            val candidates = mutableListOf<ComickComic>()
            for (result in results) {
                val details = getComicDetails(result.slug ?: continue, useCache = false) ?: continue
                val mu = details.comic?.links?.mu?.trim() ?: continue
                if (mu == muIdNumeric || mu == muIdBase36) {
                    details.comic?.let { candidates.add(it) }
                }
            }
            if (candidates.isNotEmpty()) {
                return@withContext candidates.maxByOrNull { it.user_follow_count ?: 0 }?.slug
            }
        }
        null
    }

    /**
     * Search for comics on Comick by title (for user-initiated search)
     * @param query The search query
     * @param allowAdult If false, filters out pornographic content (only allows "safe" and "suggestive")
     * @return List of matching comics
     */
    suspend fun searchComics(
        query: String?,
        allowAdult: Boolean = true,
        page: Int = 1,
        limit: Int = 25,
        genreSlugs: List<String>? = null,
        excludedGenreSlugs: List<String>? = null,
        tagSlugs: List<String>? = null,
        excludedTagSlugs: List<String>? = null,
        demographic: List<Int>? = null,
        country: List<String>? = null,
        contentRating: List<String>? = null,
        status: Int? = null,
        sort: String? = null,
        time: Int? = null,
        minimum: Int? = null,
        minimumRating: Double? = null,
        fromYear: Int? = null,
        toYear: Int? = null,
        completed: Boolean? = null,
        excludeMyList: Boolean? = null,
        showAll: Boolean? = null,
        categorySlugs: List<String>? = null,
        mediaType: String = MEDIA_TYPE_MANGA,
    ): List<ComickComic>? = withContext(Dispatchers.IO) {
        try {
            val urlBuilder: HttpUrl.Builder = "https://api.comick.dev/v1.0/search/"
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?: return@withContext null

            urlBuilder.addQueryParameter("type", "comic")
            if (mediaType == MEDIA_TYPE_ANIME) urlBuilder.addQueryParameter("media_type", "anime")
            urlBuilder.addQueryParameter("page", page.toString())
            urlBuilder.addQueryParameter("limit", limit.toString())
            urlBuilder.addQueryParameter("showall", "false")
            urlBuilder.addQueryParameter("t", "false")

            query?.trim()?.takeIf { it.isNotBlank() }?.let {
                urlBuilder.addQueryParameter("q", it)
            }
            genreSlugs?.filter { it.isNotBlank() }?.forEach {
                urlBuilder.addQueryParameter("genres", it)
            }
            excludedGenreSlugs?.filter { it.isNotBlank() }?.forEach {
                urlBuilder.addQueryParameter("excludes", it)
            }
            tagSlugs?.filter { it.isNotBlank() }?.forEach {
                urlBuilder.addQueryParameter("tags", it)
            }
            excludedTagSlugs?.filter { it.isNotBlank() }?.forEach {
                urlBuilder.addQueryParameter("excluded-tags", it)
            }
            demographic?.forEach { urlBuilder.addQueryParameter("demographic", it.toString()) }
            country?.filter { it.isNotBlank() }?.forEach { urlBuilder.addQueryParameter("country", it) }
            contentRating?.filter { it.isNotBlank() }?.forEach { urlBuilder.addQueryParameter("content_rating", it) }
            status?.let { urlBuilder.addQueryParameter("status", it.toString()) }
            val sortValue = sort?.takeIf { it.isNotBlank() } ?: "created_at"
            urlBuilder.addQueryParameter("sort", sortValue)
            time?.let { urlBuilder.addQueryParameter("time", it.toString()) }
            minimum?.let { urlBuilder.addQueryParameter("minimum", it.toString()) }
            minimumRating?.let { urlBuilder.addQueryParameter("minimum_rating", it.toString()) }
            fromYear?.let { urlBuilder.addQueryParameter("from", it.toString()) }
            toYear?.let { urlBuilder.addQueryParameter("to", it.toString()) }
            completed?.let { urlBuilder.addQueryParameter("completed", it.toString()) }
            excludeMyList?.let { urlBuilder.addQueryParameter("exclude-mylist", it.toString()) }
            showAll?.let { urlBuilder.addQueryParameter("showall", it.toString()) }
            if (tagSlugs.isNullOrEmpty()) categorySlugs?.filter { it.isNotBlank() }?.forEach {
                urlBuilder.addQueryParameter("tags", it)
            }

            val userPickedRating = !contentRating.isNullOrEmpty()
            if (!allowAdult && !userPickedRating) {
                urlBuilder.addQueryParameter("content_rating", "safe")
                urlBuilder.addQueryParameter("content_rating", "suggestive")
            }

            val url = urlBuilder.build().toString()
            val request = request(url)

            val response = client.newCall(request).execute()
            val body = response.body.string()

            if (!response.isSuccessful) {
                Logger.log("Comick search API error: ${response.code}")
                return@withContext null
            }

            if (body.isNullOrEmpty() || body == "[]") {
                return@withContext emptyList()
            }

            val allResults = gson.fromJson(body, Array<ComickComic>::class.java).toList()

            val results = if (!allowAdult && !userPickedRating) {
                allResults.filter { comic ->
                    val rating = comic.content_rating?.lowercase()
                    rating == "safe" || rating == "suggestive"
                }
            } else {
                allResults
            }

            return@withContext results
        } catch (e: Exception) {
            Logger.log("Error searching Comick: ${e.message}")
            return@withContext null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Anime
    // ---------------------------------------------------------------------------------------

    /**
     * Next.js build id, needed for the lightweight episode route. Scraped from any page's
     * `__NEXT_DATA__` and cached; it changes on every Comick deploy, at which point the data route
     * starts answering 404 and [getAnimePage] re-scrapes it.
     */
    @Volatile
    private var nextBuildId: String? = null

    /**
     * Fetch an anime's detail object together with its episode list.
     *
     * The episode list is deliberately *not* available from the public API: episodes are rows in
     * the chapters table flagged `entry_type = "episode"`, and `/comic/{hid}/chapters` filters
     * them out, returning an empty array for every anime. The only source is the page's
     * server-rendered props, reachable two ways:
     *
     *  1. `/_next/data/{buildId}/anime/{slug}.json` — same payload, roughly a sixth of the size,
     *     but tied to the current build id.
     *  2. The `/anime/{slug}` HTML itself, parsing out `__NEXT_DATA__`. No build id needed, so
     *     this is both the bootstrap for the id and the fallback when it goes stale.
     *
     * Note the payload carries every episode inline with no pagination and a server-side cap of
     * 1000, so a long-running series is a genuinely large response (One Piece is ~1 MB).
     *
     * @return the page's anime entry and episodes (oldest first), or null if the page failed
     */
    suspend fun getAnimePage(slug: String): ComickAnimePage? = withContext(Dispatchers.IO) {
        val cachedId = nextBuildId
        if (cachedId != null) {
            val viaData = runCatching { fetchAnimePageProps(dataRouteUrl(cachedId, slug)) }.getOrNull()
            if (viaData != null) return@withContext parseAnimePage(viaData)
            // Stale build id (404) — drop it so the HTML path below re-derives one.
            nextBuildId = null
        }

        val html = try {
            val response = client.newCall(
                request(webUrl(slug, MEDIA_TYPE_ANIME), accept = "text/html")
            ).execute()
            if (!response.isSuccessful) {
                Logger.log("Comick anime page: HTTP ${response.code} for slug=$slug")
                return@withContext null
            }
            response.body.string()
        } catch (e: Exception) {
            Logger.log("Comick anime page error for slug=$slug: ${e.message}")
            return@withContext null
        }

        val nextData = extractNextData(html) ?: run {
            Logger.log("Comick anime page: no __NEXT_DATA__ for slug=$slug")
            return@withContext null
        }
        nextData.get("buildId")?.takeIf { !it.isJsonNull }?.asString?.let { nextBuildId = it }
        val props = nextData.getAsJsonObject("props")?.getAsJsonObject("pageProps")
            ?: return@withContext null
        parseAnimePage(props)
    }

    /** Episodes only. See [getAnimePage] for why this can't come from the API. */
    suspend fun getEpisodes(slug: String): List<ComickEpisode> =
        getAnimePage(slug)?.episodes ?: emptyList()

    private fun dataRouteUrl(buildId: String, slug: String): String {
        val encoded = URLEncoder.encode(slug, "UTF-8")
        return "https://comick.dev/_next/data/$buildId/anime/$encoded.json?slug=$encoded"
    }

    /** GETs a `_next/data` URL and returns its `pageProps`, or null on any non-200 / bad shape. */
    private fun fetchAnimePageProps(url: String): com.google.gson.JsonObject? {
        val response = client.newCall(request(url)).execute()
        if (!response.isSuccessful) return null
        val body = response.body.string()
        if (body.isBlank()) return null
        return gson.fromJson(body, com.google.gson.JsonObject::class.java)
            ?.getAsJsonObject("pageProps")
    }

    private fun extractNextData(html: String): com.google.gson.JsonObject? = try {
        Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")
            ?.data()
            ?.takeIf { it.isNotBlank() }
            ?.let { gson.fromJson(it, com.google.gson.JsonObject::class.java) }
    } catch (e: Exception) {
        Logger.log("Comick: failed to parse __NEXT_DATA__: ${e.message}")
        null
    }

    private fun parseAnimePage(props: com.google.gson.JsonObject): ComickAnimePage {
        val anime = runCatching {
            props.getAsJsonObject("anime")?.let { gson.fromJson(it, ComickComic::class.java) }
        }.getOrNull()

        val episodes = runCatching {
            props.getAsJsonArray("episodes")
                ?.let { gson.fromJson(it, Array<ComickEpisode>::class.java) }
                ?.toList()
                ?: emptyList()
        }.getOrElse { emptyList() }

        // The payload is newest-first; present episodes in viewing order instead. Entries without
        // a parseable number sort last rather than being dropped — they're still real episodes.
        val ordered = episodes.sortedBy { it.number()?.toDoubleOrNull() ?: Double.MAX_VALUE }
        return ComickAnimePage(anime, ordered)
    }

    /**
     * Fetch a single episode by its hid. Unlike the list, this *is* public.
     * @return the episode, or null on failure
     */
    suspend fun getEpisode(hid: String): ComickEpisode? = withContext(Dispatchers.IO) {
        try {
            val response = client.newCall(request("https://api.comick.dev/chapter/$hid/")).execute()
            if (!response.isSuccessful) {
                Logger.log("Comick episode API error: ${response.code} for hid=$hid")
                return@withContext null
            }
            val body = response.body.string()
            if (body.isBlank()) return@withContext null
            gson.fromJson(body, com.google.gson.JsonObject::class.java)
                ?.getAsJsonObject("chapter")
                ?.let { gson.fromJson(it, ComickEpisode::class.java) }
        } catch (e: Exception) {
            Logger.log("Error fetching Comick episode $hid: ${e.message}")
            null
        }
    }

    /**
     * Community-submitted tags for an entry, most-endorsed first.
     *
     * The response also carries `minVisibleScore`, which is deliberately *not* applied as a
     * filter: on the entries checked it sits above every tag's score, so honouring it would show
     * nothing at all. It appears to gate something else (search indexing, most likely).
     *
     * @param hid The entry's HID
     * @return tags, or empty on failure
     */
    suspend fun getComicTags(hid: String): List<ComickUserTag> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.comick.dev/comic-tags".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("hid", hid)?.build()?.toString()
                ?: return@withContext emptyList()
            val response = client.newCall(request(url)).execute()
            if (!response.isSuccessful) {
                Logger.log("Comick tags API error: ${response.code} for hid=$hid")
                return@withContext emptyList()
            }
            val body = response.body.string()
            if (body.isBlank()) return@withContext emptyList()
            val arr = gson.fromJson(body, com.google.gson.JsonObject::class.java)
                ?.getAsJsonArray("popularTags")
                ?: return@withContext emptyList()
            gson.fromJson(arr, Array<ComickUserTag>::class.java)
                .toList()
                .filter { !it.slug.isNullOrBlank() && !it.title.isNullOrBlank() }
                .sortedByDescending { it.score ?: 0 }
        } catch (e: Exception) {
            Logger.log("Error fetching Comick tags for hid $hid: ${e.message}")
            emptyList()
        }
    }

    /**
     * The current season's weekly broadcast schedule. Each entry carries a real UTC
     * `occurrenceAt` timestamp alongside the JST weekday/time, so no timezone parsing is needed.
     */
    suspend fun getAnimeSchedule(): List<ComickScheduleEntry> = withContext(Dispatchers.IO) {
        try {
            val response = client.newCall(request("https://api.comick.dev/anime/schedule")).execute()
            if (!response.isSuccessful) {
                Logger.log("Comick schedule API error: ${response.code}")
                return@withContext emptyList()
            }
            val body = response.body.string()
            if (body.isBlank()) return@withContext emptyList()
            val root = gson.fromJson(body, JsonElement::class.java) ?: return@withContext emptyList()
            val arr = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject -> listOf("entries", "data", "results", "schedule")
                    .firstNotNullOfOrNull { key ->
                        root.asJsonObject.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
                    } ?: JsonArray()
                else -> JsonArray()
            }
            gson.fromJson(arr, Array<ComickScheduleEntry>::class.java).toList()
        } catch (e: Exception) {
            Logger.log("Error fetching Comick anime schedule: ${e.message}")
            emptyList()
        }
    }

    /**
     * Anime for one season.
     * @param sort "popularity" or "score"
     */
    suspend fun getSeasonalAnime(
        year: Int,
        season: String,
        sort: String? = null
    ): List<ComickComic> = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = "https://api.comick.dev/anime/seasonal".toHttpUrlOrNull()?.newBuilder()
                ?: return@withContext emptyList()
            urlBuilder.addQueryParameter("year", year.toString())
            urlBuilder.addQueryParameter("season", season)
            sort?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("sort", it) }

            val response = client.newCall(request(urlBuilder.build().toString())).execute()
            if (!response.isSuccessful) {
                Logger.log("Comick seasonal API error: ${response.code}")
                return@withContext emptyList()
            }
            val body = response.body.string()
            if (body.isBlank() || body == "[]") return@withContext emptyList()
            val root = gson.fromJson(body, JsonElement::class.java) ?: return@withContext emptyList()
            // Documented as either an array or, with grouped=true, an object wrapping one.
            val arr = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject -> listOf("data", "results")
                    .firstNotNullOfOrNull { key ->
                        root.asJsonObject.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
                    } ?: JsonArray()
                else -> JsonArray()
            }
            gson.fromJson(arr, Array<ComickComic>::class.java).toList()
        } catch (e: Exception) {
            Logger.log("Error fetching Comick seasonal anime: ${e.message}")
            emptyList()
        }
    }

    /**
     * Title search against the anime catalogue. Cheaper than [searchComics] with
     * `media_type=anime` because it returns `anime_profiles` inline, saving a detail call per hit —
     * but it is capped at 100 results and ignores `page`, so it can't back a paged browse.
     */
    suspend fun searchAnime(
        query: String? = null,
        year: Int? = null,
        season: String? = null,
        limit: Int = 30
    ): List<ComickComic> = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = "https://api.comick.dev/anime/".toHttpUrlOrNull()?.newBuilder()
                ?: return@withContext emptyList()
            query?.trim()?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("q", it) }
            year?.let { urlBuilder.addQueryParameter("year", it.toString()) }
            season?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("season", it) }
            urlBuilder.addQueryParameter("limit", limit.coerceIn(1, 100).toString())

            val response = client.newCall(request(urlBuilder.build().toString())).execute()
            if (!response.isSuccessful) {
                Logger.log("Comick anime search API error: ${response.code}")
                return@withContext emptyList()
            }
            val body = response.body.string()
            if (body.isBlank() || body == "[]") return@withContext emptyList()
            gson.fromJson(body, Array<ComickComic>::class.java).toList()
        } catch (e: Exception) {
            Logger.log("Error searching Comick anime: ${e.message}")
            emptyList()
        }
    }
}
