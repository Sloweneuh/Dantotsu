package ani.dantotsu.connections.comments

import android.content.Context
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.isOnline
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okio.IOException
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object CommentsAPI {
    private const val API_ADDRESS: String = "https://api.dantotsu.app"
    private const val LOCAL_HOST: String = "https://127.0.0.1"
    private const val TOKEN_LIFETIME_MS = 1000L * 60 * 60 * 24 * 6
    private const val MAX_SIGN_IN_ATTEMPTS = 5
    private const val RATE_LIMIT_WAIT_MS = 60_000L
    private val NO_COMMENTS = CommentResponse(emptyList(), 1)
    private var isOnline: Boolean = true
    /**
     * Read on every use. It was read once, when this object was first touched, so switching comments
     * on in settings left every call pointed at [LOCAL_HOST] until the app restarted, and switching
     * them off left them all still going to the server.
     */
    private val commentsEnabled: Boolean
        get() = PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1
    private val ADDRESS: String get() = if (commentsEnabled) API_ADDRESS else LOCAL_HOST
    var authToken: String? = null
        private set
    var userId: String? = null
    var isBanned: Boolean = false
    var isAdmin: Boolean = false
    var isMod: Boolean = false
    var totalVotes: Int = 0

    suspend fun getCommentsForId(
        id: Int,
        page: Int = 1,
        tag: Int?,
        sort: String?
    ): CommentResponse? {
        val query = listOfNotNull(tag?.let { "tag=$it" }, sort?.let { "sort=$it" })
            .joinToString("&").takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        return getJson("/comments/$id/$page$query", "Failed to fetch comments", NO_COMMENTS)
    }

    suspend fun getRepliesFromId(id: Int, page: Int = 1): CommentResponse? =
        getJson("/comments/parent/$id/$page", "Failed to fetch comments", NO_COMMENTS)

    suspend fun getSingleComment(id: Int): Comment? =
        getJson("/comments/$id", "Failed to fetch comment")

    /**
     * A GET of [path], decoded as [T]: null on a network error, an error answer, or a body that isn't
     * the JSON expected — a Cloudflare error page, say. Nothing there (a 404, or an empty body) gives
     * [missing] instead, so a media with no comments yet isn't mistaken for a failed load.
     *
     * Every read needs a signed-in token — the server answers 401 without one and 403 to one it no
     * longer accepts. So a read signs in first if nothing has yet, and on a 401 or 403 signs in
     * afresh and tries once more, rather than toasting the code: a token can expire with the app
     * open, and without this the comments simply stopped loading until it was restarted.
     */
    private suspend inline fun <reified T> getJson(path: String, failure: String, missing: T? = null): T? {
        if (!commentsEnabled) return null
        if (authToken == null) fetchAuthToken(null)
        var json = try {
            requestBuilder().get("$ADDRESS$path")
        } catch (e: IOException) {
            Logger.log(e)
            errorMessage(failure)
            return null
        }
        if ((json.code == 401 || json.code == 403) && reauthenticate()) {
            json = try {
                requestBuilder().get("$ADDRESS$path")
            } catch (e: IOException) {
                Logger.log(e)
                errorMessage(failure)
                return null
            }
        }
        if (json.code == 404) return missing
        if (json.code != 200) {
            errorReason(json.code, json.text)
            return null
        }
        val body = json.text.trim()
        if (body.isEmpty() || body == "[]" || body == "null") return missing
        if (!body.startsWith("{")) return null
        return try {
            Json.decodeFromString<T>(body)
        } catch (e: Exception) {
            Logger.log(e)
            null
        }
    }

    /** Drops the token in use and signs in again; whether that got a new one. */
    private suspend fun reauthenticate(): Boolean {
        val stale = authToken
        authMutex.withLock {
            // Another caller may have signed in again already while this one waited.
            if (authToken != null && authToken != stale) return true
            clearSession()
        }
        fetchAuthToken(null)
        return authToken != null
    }

    suspend fun vote(commentId: Int, voteType: Int): Boolean {
        val url = "$ADDRESS/comments/vote/$commentId/$voteType"
        val request = requestBuilder()
        val json = try {
            request.post(url)
        } catch (e: IOException) {
            Logger.log(e)
            errorMessage("Failed to vote")
            return false
        }
        val res = json.code == 200
        if (!res) {
            errorReason(json.code, json.text)
        }
        return res
    }

    suspend fun comment(mediaId: Int, parentCommentId: Int?, content: String, tag: Int?): Comment? {
        val url = "$ADDRESS/comments"
        val body = FormBody.Builder()
            .add("user_id", userId ?: return null)
            .add("media_id", mediaId.toString())
            .add("content", content)
        if (tag != null) {
            body.add("tag", tag.toString())
        }
        parentCommentId?.let {
            body.add("parent_comment_id", it.toString())
        }
        val request = requestBuilder()
        val json = try {
            request.post(url, requestBody = body.build())
        } catch (e: IOException) {
            Logger.log(e)
            errorMessage("Failed to comment")
            return null
        }
        val res = json.code == 200
        if (!res) {
            errorReason(json.code, json.text)
            return null
        }
        val parsed = try {
            Json.decodeFromString<ReturnedComment>(json.text)
        } catch (e: Exception) {
            Logger.log(e)
            errorMessage("Failed to parse comment")
            return null
        }
        return Comment(
            parsed.id,
            parsed.userId,
            parsed.mediaId,
            parsed.parentCommentId,
            parsed.content,
            parsed.timestamp,
            parsed.deleted,
            parsed.tag,
            0,
            0,
            null,
            Anilist.username ?: "",
            Anilist.avatar,
            totalVotes = totalVotes
        )
    }

    suspend fun deleteComment(commentId: Int): Boolean {
        val url = "$ADDRESS/comments/$commentId"
        val request = requestBuilder()
        val json = try {
            request.delete(url)
        } catch (e: IOException) {
            Logger.log(e)
            errorMessage("Failed to delete comment")
            return false
        }
        val res = json.code == 200
        if (!res) {
            errorReason(json.code, json.text)
        }
        return res
    }

    suspend fun editComment(commentId: Int, content: String): Boolean {
        val url = "$ADDRESS/comments/$commentId"
        val body = FormBody.Builder()
            .add("content", content)
            .build()
        val request = requestBuilder()
        val json = try {
            request.put(url, requestBody = body)
        } catch (e: IOException) {
            Logger.log(e)
            errorMessage("Failed to edit comment")
            return false
        }
        val res = json.code == 200
        if (!res) {
            errorReason(json.code, json.text)
        }
        return res
    }

    suspend fun banUser(userId: String): Boolean {
        val url = "$ADDRESS/ban/$userId"
        val request = requestBuilder()
        val json = try {
            request.post(url)
        } catch (e: IOException) {
            Logger.log(e)
            errorMessage("Failed to ban user")
            return false
        }
        val res = json.code == 200
        if (!res) {
            errorReason(json.code, json.text)
        }
        return res
    }

    suspend fun reportComment(
        commentId: Int,
        username: String,
        mediaTitle: String,
        reportedId: String
    ): Boolean {
        val url = "$ADDRESS/report/$commentId"
        val body = FormBody.Builder()
            .add("username", username)
            .add("mediaName", mediaTitle)
            .add("reporter", Anilist.username ?: "unknown")
            .add("reportedId", reportedId)
            .build()
        val request = requestBuilder()
        val json = try {
            request.post(url, requestBody = body)
        } catch (e: IOException) {
            Logger.log(e)
            errorMessage("Failed to report comment")
            return false
        }
        val res = json.code == 200
        if (!res) {
            errorReason(json.code, json.text)
        }
        return res
    }

    suspend fun getNotifications(client: OkHttpClient): NotificationResponse? {
        // With comments disabled, ADDRESS points at the unreachable LOCAL_HOST sentinel, so this
        // would otherwise always burn a ~30s connect timeout (logging a stack trace) for a call
        // that was already guaranteed to return null. CommentNotificationTask still needs to run
        // unconditionally since it also delivers DANTOTSU_UPDATE notifications through this same
        // endpoint, so the short-circuit belongs here rather than at the caller.
        if (!commentsEnabled) return null
        val url = "$ADDRESS/notification/reply"
        val request = requestBuilder(client)
        val json = try {
            request.get(url)
        } catch (e: IOException) {
            return null
        }
        if (!json.text.startsWith("{")) return null
        val res = json.code == 200
        if (!res) {
            return null
        }
        val parsed = try {
            Json.decodeFromString<NotificationResponse>(json.text)
        } catch (e: Exception) {
            return null
        }
        return parsed
    }

    /** The account behind [token], or null when the server doesn't accept it (or can't be reached). */
    private suspend fun getUserDetails(client: OkHttpClient?, token: String): User? {
        val url = "$ADDRESS/user"
        val headers = headerBuilder(token)
        val request = Requests(client ?: Injekt.get<NetworkHelper>().client, headers)
        val json = try {
            request.get(url)
        } catch (e: IOException) {
            return null
        }
        if (json.code != 200) return null
        return try {
            Json.decodeFromString<UserResponse>(json.text).user
        } catch (e: Exception) {
            Logger.log(e)
            null
        }
    }

    /** Serialises sign-ins: the app's start-up, the notification check and the comments tab all ask. */
    private val authMutex = Mutex()

    /**
     * Signs in to the comments server, reusing the session kept from last time while the server
     * still accepts it. With [context], also refreshes whether the device is online, which decides
     * whether failures are shown.
     *
     * Nothing is taken as signed in until the server has said so. The kept session used to be put in
     * place first and checked after; the comment-notification check, which runs at start-up and is
     * routinely cancelled there by WorkManager, was cancelled mid-check — leaving a session that had
     * expired weeks before in place as if it were good. Every later sign-in then saw a token and
     * returned, and the comments tab spun forever on requests the server refused.
     */
    suspend fun fetchAuthToken(context: Context?, client: OkHttpClient? = null) {
        context?.let { isOnline = isOnline(it) }
        // Same LOCAL_HOST sentinel as getNotifications(): with comments disabled, ADDRESS is
        // unreachable by design. CommentNotificationTask calls this unconditionally on every
        // cold start (it also delivers unrelated update notifications), and used to pay for a
        // real connect timeout here before getNotifications()'s own guard even ran.
        if (!commentsEnabled) return
        if (authToken != null) return
        authMutex.withLock {
            // Signed in by whoever held the lock before.
            if (authToken != null) return
            val cached = PrefManager.getNullableVal<AuthResponse>(PrefName.CommentAuthResponse, null)
            val cachedValid = PrefManager.getVal<Long>(PrefName.CommentTokenExpiry) > System.currentTimeMillis()
            if (cached != null && cachedValid) {
                val user = getUserDetails(client, cached.authToken)
                if (user != null) {
                    applySession(cached.authToken, user)
                    return
                }
            }
            signIn(client)
        }
    }

    /** Signs in with the AniList token, retrying through rate limits. Holds [authMutex]. */
    private suspend fun signIn(client: OkHttpClient?) {
        val token = PrefManager.getVal(PrefName.AnilistToken, null as String?) ?: return
        val url = "$ADDRESS/authenticate"
        repeat(MAX_SIGN_IN_ATTEMPTS) {
            val json = try {
                authRequest(token, url, client)
            } catch (e: IOException) {
                Logger.log(e)
                errorMessage("Failed to login to comments API")
                return
            }
            when {
                json.code == 200 -> {
                    val parsed = try {
                        if (!json.text.startsWith("{")) throw IllegalStateException("not JSON")
                        Json.decodeFromString<AuthResponse>(json.text)
                    } catch (e: Exception) {
                        Logger.log(e)
                        errorMessage("Failed to login to comments API")
                        return
                    }
                    PrefManager.setVal(PrefName.CommentAuthResponse, parsed)
                    PrefManager.setVal(PrefName.CommentTokenExpiry, System.currentTimeMillis() + TOKEN_LIFETIME_MS)
                    applySession(parsed.authToken, parsed.user)
                    return
                }

                json.code != 429 -> {
                    errorReason(json.code, json.text)
                    return
                }
            }
            kotlinx.coroutines.delay(RATE_LIMIT_WAIT_MS)
        }
        errorMessage("Failed to login after multiple attempts")
    }

    private fun applySession(token: String, user: User) {
        authToken = token
        userId = user.id
        isBanned = user.isBanned ?: false
        isAdmin = user.isAdmin ?: false
        isMod = user.isMod ?: false
        totalVotes = user.totalVotes
    }

    private fun clearSession() {
        authToken = null
        userId = null
        isBanned = false
        isAdmin = false
        isMod = false
        totalVotes = 0
    }

    private fun errorMessage(reason: String) {
        if (commentsEnabled) Logger.log(reason)
        if (isOnline && commentsEnabled) snackString(reason)
    }

    fun logout() {
        PrefManager.removeVal(PrefName.CommentAuthResponse)
        PrefManager.removeVal(PrefName.CommentTokenExpiry)
        clearSession()
    }

    private suspend fun authRequest(
        token: String,
        url: String,
        client: OkHttpClient? = null
    ): NiceResponse {
        val body: FormBody = FormBody.Builder()
            .add("token", token)
            .build()
        val request = if (client != null) requestBuilder(client) else requestBuilder()
        return request.post(url, requestBody = body)
    }

    private fun headerBuilder(token: String? = authToken): Map<String, String> {
        val map = mutableMapOf(
            "appauth" to "6*45Qp%W2RS@t38jkXoSKY588Ynj%n"
        )
        token?.let { map["Authorization"] = it }
        return map
    }

    fun requestBuilder(client: OkHttpClient = Injekt.get<NetworkHelper>().client): Requests {
        return Requests(
            client,
            headerBuilder()
        )
    }

    private fun errorReason(code: Int, reason: String? = null) {
        val error = when (code) {
            429 -> "Rate limited. :("
            else -> "Failed to connect"
        }
        val parsed = try {
            Json.decodeFromString<ErrorResponse>(reason!!)
        } catch (e: Exception) {
            null
        }
        val message = parsed?.message ?: reason ?: error
        val fullMessage = if (code == 500) message else "$code: $message"

        toast(fullMessage)
    }
}

@Serializable
data class ErrorResponse(
    @SerialName("message")
    val message: String
)

@Serializable
data class NotificationResponse(
    @SerialName("notifications")
    val notifications: List<Notification>
)

@Serializable
data class Notification(
    @SerialName("username")
    val username: String,
    @SerialName("media_id")
    val mediaId: Int,
    @SerialName("comment_id")
    val commentId: Int,
    @SerialName("type")
    val type: Int? = null,
    @SerialName("content")
    val content: String? = null,
    @SerialName("notification_id")
    val notificationId: Int
)


@Serializable
data class AuthResponse(
    @SerialName("authToken")
    val authToken: String,
    @SerialName("user")
    val user: User
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID: Long = 1
    }
}

@Serializable
data class UserResponse(
    @SerialName("user")
    val user: User
)

@Serializable
data class User(
    @SerialName("user_id")
    val id: String,
    @SerialName("username")
    val username: String,
    @SerialName("profile_picture_url")
    val profilePictureUrl: String? = null,
    @SerialName("is_banned")
    @Serializable(with = NumericBooleanSerializer::class)
    val isBanned: Boolean? = null,
    @SerialName("is_mod")
    @Serializable(with = NumericBooleanSerializer::class)
    val isMod: Boolean? = null,
    @SerialName("is_admin")
    @Serializable(with = NumericBooleanSerializer::class)
    val isAdmin: Boolean? = null,
    @SerialName("total_votes")
    val totalVotes: Int,
    @SerialName("warnings")
    val warnings: Int
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID: Long = 1
    }
}

@Serializable
data class CommentResponse(
    @SerialName("comments")
    val comments: List<Comment>,
    @SerialName("totalPages")
    val totalPages: Int
)

@Serializable
data class Comment(
    @SerialName("comment_id")
    val commentId: Int,
    @SerialName("user_id")
    val userId: String,
    @SerialName("media_id")
    val mediaId: Int,
    @SerialName("parent_comment_id")
    val parentCommentId: Int?,
    @SerialName("content")
    var content: String,
    @SerialName("timestamp")
    var timestamp: String,
    @SerialName("deleted")
    @Serializable(with = NumericBooleanSerializer::class)
    val deleted: Boolean?,
    @SerialName("tag")
    val tag: Int?,
    @SerialName("upvotes")
    var upvotes: Int,
    @SerialName("downvotes")
    var downvotes: Int,
    @SerialName("user_vote_type")
    var userVoteType: Int?,
    @SerialName("username")
    val username: String,
    @SerialName("profile_picture_url")
    val profilePictureUrl: String?,
    @SerialName("is_mod")
    @Serializable(with = NumericBooleanSerializer::class)
    val isMod: Boolean? = null,
    @SerialName("is_admin")
    @Serializable(with = NumericBooleanSerializer::class)
    val isAdmin: Boolean? = null,
    @SerialName("reply_count")
    val replyCount: Int? = null,
    @SerialName("total_votes")
    val totalVotes: Int
)

@Serializable
data class ReturnedComment(
    @SerialName("id")
    var id: Int,
    @SerialName("comment_id")
    var commentId: Int?,
    @SerialName("user_id")
    val userId: String,
    @SerialName("media_id")
    val mediaId: Int,
    @SerialName("parent_comment_id")
    val parentCommentId: Int? = null,
    @SerialName("content")
    val content: String,
    @SerialName("timestamp")
    val timestamp: String,
    @SerialName("deleted")
    @Serializable(with = NumericBooleanSerializer::class)
    val deleted: Boolean?,
    @SerialName("tag")
    val tag: Int? = null,
)

object NumericBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NumericBoolean", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeInt(if (value) 1 else 0)
    }

    override fun deserialize(decoder: Decoder): Boolean {
        return decoder.decodeInt() != 0
    }
}