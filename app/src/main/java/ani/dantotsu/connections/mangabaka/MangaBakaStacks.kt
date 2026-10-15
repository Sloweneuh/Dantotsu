package ani.dantotsu.connections.mangabaka

import ani.dantotsu.Mapper
import ani.dantotsu.connections.TrackerSessions
import ani.dantotsu.media.InterestStack
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.coroutines.async
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * MangaBaka stacks (`/v2/…stacks…`): MangaBaka's take on MAL interest stacks — a titled, ordered
 * list of series with a note per entry.
 *
 * The browse/read routes are public and send no token, like [MangaBakaApi]. The `/v2/my/…` routes
 * (your stacks, saves, follows, votes, and creating or editing a stack) need the `stack.read` /
 * `stack.write` OAuth scopes. A session from before those scopes were requested gets a 401/403
 * there; [Result.needsRelogin] flags that so the UI can say so instead of a bare error.
 *
 * Every request goes through [MangaBakaApi.execute], which owns the shared rate limit.
 */
object MangaBakaStacks {
    private const val API_URL = MangaBakaApi.API_URL
    private val JSON_MEDIA = "application/json".toMediaTypeOrNull()

    /** The `limit` the stack list routes accept at most. */
    const val PAGE_LIMIT = 50

    val CONTENT_RATINGS = listOf("safe", "suggestive", "erotica", "pornographic")
    val VISIBILITIES = listOf("draft", "unlisted", "public")

    /** `public` and `unlisted` need at least this many series (see `PUT …/visibility`). */
    const val MIN_PUBLISHED_ENTRIES = 5

    /** Sort keys of `GET /v2/series/{id}/stacks`. */
    enum class SeriesSort(val value: String) {
        VOTES("vote_count_desc"),
        SAVES("save_count_desc"),
        UPDATED("updated_at_desc"),
        NEWEST("first_published_at_desc"),
    }

    /** How `GET /v2/series/{id}/stacks` treats stacks marked `is_spoiler`. */
    enum class Spoilers(val value: String) {
        INCLUDE("include"),
        EXCLUDE("exclude"),
        ONLY("only"),
    }

    /** Outcome of a call that can fail in a way the user should hear about. */
    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Error(val code: Int?, val message: String?) : Result<Nothing>() {
            /** Not logged in, or logged in before the stack scopes were requested. */
            val needsRelogin: Boolean get() = code == 401 || code == 403
            /** The stack changed since its `token` was read (`PUT /v2/my/stacks/{id}`). */
            val isConflict: Boolean get() = code == 409
        }

        fun getOrNull(): T? = (this as? Ok)?.value
    }

    // ---- public routes ----

    /** Public stacks that include [seriesId] (`GET /v2/series/{id}/stacks`). */
    suspend fun getSeriesStacks(
        seriesId: Long,
        page: Int = 1,
        limit: Int = 24,
        sort: SeriesSort = SeriesSort.VOTES,
        // Spoiler stacks are listed by default; the cards badge them so they can be skipped.
        spoilers: Spoilers = Spoilers.INCLUDE,
    ): StackPage? = getPage(
        "$API_URL/v2/series/$seriesId/stacks".toHttpUrl().newBuilder()
            .addQueryParameter("sort_by", sort.value)
            .addQueryParameter("spoilers", spoilers.value),
        page, limit, authed = false,
    )

    /** Every public stack, newest first; [featured] limits it to staff picks (`GET /v2/stacks`). */
    suspend fun browse(page: Int = 1, featured: Boolean = false, limit: Int = 24): StackPage? = getPage(
        "$API_URL/v2/stacks".toHttpUrl().newBuilder()
            .apply { if (featured) addQueryParameter("featured", "true") },
        page, limit, authed = false,
    )

    /** A user's public and unlisted stacks (`GET /v2/users/{username}/stacks`). */
    suspend fun getUserStacks(username: String, page: Int = 1, limit: Int = 24): StackPage? = getPage(
        "$API_URL/v2/users".toHttpUrl().newBuilder().addPathSegment(username).addPathSegment("stacks"),
        page, limit, authed = false,
    )

    /**
     * A stack with its entries (`GET /v2/stacks/{id}`). Public and unlisted stacks only — a draft is
     * a 404 here even for its owner, so this falls back to the owner route when logged in.
     */
    suspend fun getStack(id: Long): StackDetail? {
        val public = tryWithSuspend(snackbar = false) {
            val response = MangaBakaApi.execute(Request.Builder().url("$API_URL/v2/stacks/$id").get().build())
            val body = response.body?.string()
            val code = response.code
            response.close()
            if (code !in 200..299 || body.isNullOrBlank()) {
                Logger.log("MangaBaka stack[$id]: HTTP $code")
                null
            } else Mapper.json.decodeFromString<StackDetailResponse>(body).data
        }
        val stack = public ?: getMyStack(id).getOrNull()
        stack?.let { descriptions[id] = it.description ?: "" }
        return stack
    }

    /** Stack id → description ("" for none), filled by [getDescription]. */
    private val descriptions = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private val descriptionLoads = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Deferred<String?>>()

    /** A description [getDescription] already fetched ("" when the stack has none), else null. */
    fun cachedDescription(id: Long): String? = descriptions[id]

    /**
     * The stack's description. List rows don't carry it, so this reads the stack itself — once per
     * stack per session, with concurrent asks for the same stack sharing one request. Null when the
     * stack couldn't be read (and isn't cached then, so a later draw tries again).
     */
    suspend fun getDescription(id: Long): String? {
        descriptions[id]?.let { return it }
        val load = descriptionLoads.getOrPut(id) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
                getStack(id)?.let { (it.description ?: "").also { d -> descriptions[id] = d } }
            }
        }
        return try {
            load.await()
        } finally {
            descriptionLoads.remove(id, load)
        }
    }

    // ---- your stacks ----

    /** Your own stacks, drafts included (`GET /v2/my/stacks`). */
    suspend fun getMyStacks(page: Int = 1, limit: Int = PAGE_LIMIT): StackPage? =
        getPage("$API_URL/v2/my/stacks".toHttpUrl().newBuilder(), page, limit, authed = true)

    /** Stacks you saved (`GET /v2/my/stacks/saved`). */
    suspend fun getSavedStacks(page: Int = 1, limit: Int = PAGE_LIMIT): StackPage? =
        getPage("$API_URL/v2/my/stacks/saved".toHttpUrl().newBuilder(), page, limit, authed = true)

    /** Stacks you follow (`GET /v2/my/stacks/followed`). */
    suspend fun getFollowedStacks(page: Int = 1, limit: Int = PAGE_LIMIT): StackPage? =
        getPage("$API_URL/v2/my/stacks/followed".toHttpUrl().newBuilder(), page, limit, authed = true)

    /** Your stack with every entry and the `token` the next update needs (`GET /v2/my/stacks/{id}`). */
    suspend fun getMyStack(id: Long): Result<StackDetail> =
        call("GET", "$API_URL/v2/my/stacks/$id") { Mapper.json.decodeFromString<StackDetailResponse>(it).data }

    /** Each of your stacks and where [seriesId] sits in it (`GET /v2/my/series/{id}/stacks`). */
    suspend fun getMyStacksForSeries(seriesId: Long): Result<List<MySeriesStack>> =
        call("GET", "$API_URL/v2/my/series/$seriesId/stacks") {
            Mapper.json.decodeFromString<MySeriesStacksResponse>(it).data.orEmpty()
        }

    /** Creates a draft (`POST /v2/my/stacks`). Publishing it is a separate [setVisibility] call. */
    suspend fun createStack(edit: StackEdit): Result<StackDetail> =
        call("POST", "$API_URL/v2/my/stacks", Mapper.json.encodeToString(edit.toCreateBody())) {
            Mapper.json.decodeFromString<StackDetailResponse>(it).data
        }

    /**
     * Replaces the stack's details and its whole entry list (`PUT /v2/my/stacks/{id}`). [token] is
     * the one from the last read or update; a stale one is a 409 ([Result.Error.isConflict]).
     */
    suspend fun updateStack(id: Long, token: String, visibility: String, edit: StackEdit): Result<StackDetail> =
        call("PUT", "$API_URL/v2/my/stacks/$id", Mapper.json.encodeToString(edit.toUpdateBody(token, visibility))) {
            Mapper.json.decodeFromString<StackDetailResponse>(it).data
        }

    /** Deletes the stack for good (`DELETE /v2/my/stacks/{id}`). */
    suspend fun deleteStack(id: Long): Result<Unit> = call("DELETE", "$API_URL/v2/my/stacks/$id") { }

    /** Publishes or unpublishes the stack (`PUT /v2/my/stacks/{id}/visibility`). */
    suspend fun setVisibility(id: Long, visibility: String): Result<Unit> =
        call("PUT", "$API_URL/v2/my/stacks/$id/visibility", Mapper.json.encodeToString(VisibilityBody(visibility))) { }

    /**
     * Adds [seriesId] to the end of the stack (`POST /v2/my/stacks/{id}/entries`). [contentRating]
     * raises the stack's rating when the series is rated above what the stack allows.
     */
    suspend fun addEntry(
        stackId: Long,
        seriesId: Long,
        note: String? = null,
        contentRating: String? = null,
    ): Result<Unit> = call(
        "POST", "$API_URL/v2/my/stacks/$stackId/entries",
        Mapper.json.encodeToString(AddEntryBody(seriesId, note?.takeIf { it.isNotBlank() }, contentRating)),
    ) { }

    // ---- votes / saves / follows ----

    /** Your vote/save/follow state for up to 50 stacks (`GET /v2/my/stacks/status`). */
    suspend fun getStatus(ids: Collection<Long>): Map<Long, StackStatus> {
        if (ids.isEmpty()) return emptyMap()
        val result = HashMap<Long, StackStatus>()
        for (chunk in ids.distinct().chunked(PAGE_LIMIT)) {
            val url = "$API_URL/v2/my/stacks/status".toHttpUrl().newBuilder()
                .apply { chunk.forEach { addQueryParameter("id", it.toString()) } }
                .build().toString()
            call("GET", url) { Mapper.json.decodeFromString<StackStatusListResponse>(it).data.orEmpty() }
                .getOrNull()?.forEach { result[it.id] = it }
        }
        return result
    }

    suspend fun setVoted(id: Long, voted: Boolean): Result<StackStatus?> =
        toggle("votes", id, voted)

    suspend fun setSaved(id: Long, saved: Boolean): Result<StackStatus?> =
        toggle("saved", id, saved)

    suspend fun setFollowed(id: Long, followed: Boolean): Result<StackStatus?> =
        toggle("followed", id, followed)

    private suspend fun toggle(kind: String, id: Long, on: Boolean): Result<StackStatus?> =
        call(if (on) "PUT" else "DELETE", "$API_URL/v2/my/stacks/$kind/$id") {
            // The vote route documents a status body; the others may answer with just `status`.
            runCatching { Mapper.json.decodeFromString<StackStatusResponse>(it).data }.getOrNull()
        }

    // ---- plumbing ----

    /** Content ratings to request, following the app's adult-content setting. */
    private fun HttpUrl.Builder.addContentRatings() {
        if (!PrefManager.getVal<Boolean>(PrefName.AdultOnly)) {
            addQueryParameter("content_rating", "safe")
            addQueryParameter("content_rating", "suggestive")
        }
    }

    private suspend fun getPage(url: HttpUrl.Builder, page: Int, limit: Int, authed: Boolean): StackPage? {
        val built = url
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.coerceIn(1, PAGE_LIMIT).toString())
            .apply { addContentRatings() }
            .build().toString()
        return if (authed) {
            call("GET", built) { Mapper.json.decodeFromString<StackPage>(it) }.getOrNull()
        } else tryWithSuspend(snackbar = false) {
            val response = MangaBakaApi.execute(Request.Builder().url(built).get().build())
            val body = response.body?.string()
            val code = response.code
            response.close()
            if (code !in 200..299 || body.isNullOrBlank()) {
                Logger.log("MangaBaka stacks $built: HTTP $code")
                null
            } else Mapper.json.decodeFromString<StackPage>(body)
        }
    }

    /**
     * One authenticated request. Waits for the saved session to be restored first (see
     * [TrackerSessions]) — read too early, the token looks absent and every call would fail.
     */
    private suspend fun <T> call(
        method: String,
        url: String,
        json: String? = null,
        parse: (String) -> T?,
    ): Result<T> {
        TrackerSessions.await()
        val headers = MangaBaka.authHeaders() ?: return Result.Error(401, null)
        return tryWithSuspend(snackbar = false) {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            val body = json?.toRequestBody(JSON_MEDIA)
            builder.method(method, body ?: if (method == "PUT" || method == "POST") ByteArray(0).toRequestBody(JSON_MEDIA) else null)
            val response = MangaBakaApi.execute(builder.build())
            val text = response.body?.string()
            val code = response.code
            response.close()
            if (code !in 200..299) {
                Logger.log("MangaBaka $method $url: HTTP $code — ${text?.take(300)}")
                Result.Error(code, errorMessage(text))
            } else {
                @Suppress("UNCHECKED_CAST")
                val parsed = if (text.isNullOrBlank()) Unit as T? else parse(text)
                if (parsed == null) Result.Error(code, null) else Result.Ok(parsed)
            }
        } ?: Result.Error(null, null)
    }

    /** Every error response carries a `message` (see the API docs' "Response shape"). */
    private fun errorMessage(body: String?): String? = body?.let {
        runCatching {
            Mapper.json.decodeFromString<JsonObject>(it)["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    // ---- request bodies ----

    /** What the edit screen collects; turned into the create or update body. */
    data class StackEdit(
        val title: String,
        val description: String?,
        val contentRating: String,
        val isSpoiler: Boolean,
        val isCommentsEnabled: Boolean,
        val entries: List<EntryInput>,
    ) {
        fun toCreateBody() = CreateBody(
            title, description?.takeIf { it.isNotBlank() }, contentRating, isSpoiler, isCommentsEnabled, entries,
        )

        fun toUpdateBody(token: String, visibility: String) = UpdateBody(
            title, description?.takeIf { it.isNotBlank() }, contentRating, isSpoiler, isCommentsEnabled,
            token, visibility, entries,
        )
    }

    @Serializable
    data class EntryInput(
        @SerialName("series_id") val seriesId: Long,
        val note: String? = null,
    )

    @Serializable
    data class CreateBody(
        val title: String,
        val description: String?,
        @SerialName("content_rating") val contentRating: String,
        @SerialName("is_spoiler") val isSpoiler: Boolean,
        @SerialName("is_comments_enabled") val isCommentsEnabled: Boolean,
        val entries: List<EntryInput>,
    )

    @Serializable
    data class UpdateBody(
        val title: String,
        val description: String?,
        @SerialName("content_rating") val contentRating: String,
        @SerialName("is_spoiler") val isSpoiler: Boolean,
        @SerialName("is_comments_enabled") val isCommentsEnabled: Boolean,
        val token: String,
        val visibility: String,
        val entries: List<EntryInput>,
    )

    @Serializable
    data class VisibilityBody(val visibility: String)

    @Serializable
    data class AddEntryBody(
        @SerialName("series_id") val seriesId: Long,
        val note: String? = null,
        @SerialName("content_rating") val contentRating: String? = null,
    )

    // ---- response models (partial; unknown keys are ignored by Mapper.json) ----

    @Serializable
    data class Pagination(
        val count: Int? = null,
        val page: Int? = null,
        val limit: Int? = null,
        val next: String? = null,
    )

    @Serializable
    data class StackPage(
        val pagination: Pagination? = null,
        val data: List<StackSummary>? = null,
    )

    @Serializable
    data class StackDetailResponse(val data: StackDetail? = null)

    @Serializable
    data class MySeriesStacksResponse(val data: List<MySeriesStack>? = null)

    @Serializable
    data class StackStatusResponse(val data: StackStatus? = null)

    @Serializable
    data class StackStatusListResponse(val data: List<StackStatus>? = null)

    @Serializable
    data class Owner(val username: String? = null, val name: String? = null)

    @Serializable
    data class StackCover(
        @SerialName("series_id") val seriesId: Long? = null,
        val cover: CoverUrls? = null,
    )

    /**
     * Stack covers come in the flat v2 shape (`x250` is a URL), unlike the series covers of
     * [MangaBakaApi.CoverImage] (`x250` is an object of densities).
     */
    @Serializable
    data class CoverUrls(
        val raw: String? = null,
        val x150: String? = null,
        val x250: String? = null,
        val x350: String? = null,
    ) {
        /** @2x of the 250px size: what the cards draw at. URLs carry their density as `x250@1/`. */
        fun thumbUrl(): String? = (x250 ?: x350 ?: x150)?.replace("@1/", "@2/") ?: raw
    }

    /** A row of any stack list. Also what the stack cards and list screen draw. */
    @Serializable
    data class StackSummary(
        val id: Long,
        val title: String = "",
        @SerialName("canonical_url") val canonicalUrl: String? = null,
        val kind: String? = null,
        val visibility: String? = null,
        @SerialName("content_rating") val contentRating: String? = null,
        @SerialName("is_spoiler") val isSpoiler: Boolean = false,
        @SerialName("is_featured") val isFeatured: Boolean = false,
        val owner: Owner? = null,
        @SerialName("entry_count") val entryCount: Int = 0,
        @SerialName("vote_count") val voteCount: Int = 0,
        @SerialName("save_count") val saveCount: Int = 0,
        @SerialName("updated_at") val updatedAt: String? = null,
        @SerialName("covers") val coverList: List<StackCover>? = null,
        /** Never sent in a list row; filled in when the stack itself has been read. */
        override val description: String? = null,
    ) : InterestStack {
        override val url: String get() = canonicalUrl ?: "${MangaBaka.WEB_URL}/stacks/$id"
        override val name: String get() = title
        override val covers: List<String> get() = coverList.orEmpty().mapNotNull { it.cover?.thumbUrl() }
        override val entries: Int get() = entryCount
        override val mangaBakaId: Long get() = id

        /** Whether the logged-in user made this stack. */
        val isMine: Boolean
            get() = owner?.username != null && owner.username.equals(MangaBaka.username, ignoreCase = true)
    }

    @Serializable
    data class StackDetail(
        val id: Long,
        val title: String = "",
        @SerialName("canonical_url") val canonicalUrl: String? = null,
        val visibility: String? = null,
        @SerialName("content_rating") val contentRating: String? = null,
        @SerialName("is_spoiler") val isSpoiler: Boolean = false,
        val owner: Owner? = null,
        @SerialName("entry_count") val entryCount: Int = 0,
        @SerialName("vote_count") val voteCount: Int = 0,
        @SerialName("save_count") val saveCount: Int = 0,
        val description: String? = null,
        @SerialName("is_comments_enabled") val isCommentsEnabled: Boolean = true,
        @SerialName("is_spoiler_locked") val isSpoilerLocked: Boolean = false,
        @SerialName("min_content_rating") val minContentRating: String? = null,
        /** Only on the owner route; needed for the next update. */
        val token: String? = null,
        val entries: List<StackEntry>? = null,
    ) {
        val isMine: Boolean
            get() = token != null ||
                (owner?.username != null && owner.username.equals(MangaBaka.username, ignoreCase = true))
    }

    /** `kind` is `series`, or `merged`/`deleted` for a placeholder that has only its rank. */
    @Serializable
    data class StackEntry(
        val kind: String? = null,
        val rank: Int? = null,
        val note: String? = null,
        val series: StackSeries? = null,
    )

    /**
     * The v2 series embedded in a stack entry. Not [MangaBakaApi.Series]: v2 flattens the cover
     * sizes to plain URLs and sends chapter/volume counts as numbers.
     */
    @Serializable
    data class StackSeries(
        val id: Long,
        val state: String? = null,
        @SerialName("merged_with") val mergedWith: Long? = null,
        val status: String? = null,
        val type: String? = null,
        @SerialName("content_rating") val contentRating: String? = null,
        val rating: Double? = null,
        @SerialName("total_chapters") val totalChapters: Double? = null,
        val titles: List<MangaBakaApi.TitleEntry>? = null,
        val cover: CoverUrls? = null,
        val source: MangaBakaApi.SeriesSource? = null,
    ) {
        fun displayTitle(): String? = MangaBakaApi.pickPreferredTitle(null, titles)
    }

    @Serializable
    data class MySeriesStack(
        val id: Long,
        val title: String = "",
        val visibility: String? = null,
        @SerialName("entry_count") val entryCount: Int = 0,
        val rank: Int? = null,
        @SerialName("can_add") val canAdd: Boolean = false,
    )

    @Serializable
    data class StackStatus(
        val id: Long,
        @SerialName("has_voted") val hasVoted: Boolean = false,
        @SerialName("has_saved") val hasSaved: Boolean = false,
        @SerialName("has_followed") val hasFollowed: Boolean = false,
        @SerialName("library_count") val libraryCount: Int = 0,
        @SerialName("vote_count") val voteCount: Int = 0,
        @SerialName("save_count") val saveCount: Int = 0,
        @SerialName("follow_count") val followCount: Int = 0,
    )
}
