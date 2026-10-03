package ani.dantotsu.connections.comick

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.edit
import ani.dantotsu.App
import ani.dantotsu.BuildConfig
import ani.dantotsu.R
import ani.dantotsu.currContext
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Comick account connection, through Comick's third-party app API (https://comick.dev/docs).
 *
 * **Permissions.** `library:read` covers followed manga/anime, their follow status and the saved
 * chapter/episode; `library:write` adds following, status and progress changes. Ratings, notes,
 * custom lists, dates and the profile are outside both — so there is no username to show, only
 * "connected". Write access is requested at login, but the consent screen leaves it unticked
 * until the user opts in, so a connection may well be read-only: see [canWrite].
 *
 * **Login is OAuth 2.0 authorization code + S256 PKCE**, as a public client (no secret). It runs in
 * a Custom Tab, never a WebView: Comick's docs forbid loading the authorization page in an
 * embedded WebView. The redirect is a reverse-domain scheme — `<applicationId>:/oauth/comick` —
 * caught by [ComickLogin]; both the release and the `.beta` callback must be registered on the app.
 *
 * **Refresh tokens rotate with no reuse grace**: presenting an old one can revoke the whole login.
 * So refreshes are serialised behind [refreshLock], and the tokens live in their own preferences
 * file rather than [ani.dantotsu.settings.saving.PrefManager]'s Protected one — backups export the
 * latter wholesale, and restoring an already-rotated refresh token is exactly the reuse that
 * revokes a session.
 */
object Comick {
    private const val AUTHORIZE_URL = "https://comick.dev/api/auth/oauth2/authorize"
    private const val TOKEN_URL = "https://comick.dev/api/auth/oauth2/token"
    private const val REVOKE_URL = "https://comick.dev/api/auth/oauth2/revoke"
    private const val LIBRARY_URL = "https://api.comick.dev/integrations/v1/me/library"

    /** The OAuth resource identifier, required on authorize, token and refresh requests alike. */
    private const val RESOURCE = "https://api.comick.dev/integrations/v1"
    private const val SCOPE = "library:read library:write offline_access"
    private val REDIRECT_URI = "${BuildConfig.APPLICATION_ID}:/oauth/comick"

    // TODO: register a public (native) app at https://comick.dev/developers/apps with the callbacks
    //  `ani.dantotsu:/oauth/comick` and `ani.dantotsu.beta:/oauth/comick` and library writes
    //  enabled, and paste its client id.
    private const val CLIENT_ID = "RwDCsicrNEqvvCyTvrWJQfIcyciztJVT"

    /** Library `status` values; the same numbers cover manga (reading…) and anime (watching…). */
    const val STATUS_READING = 1

    /** Refresh this long before the stated expiry, so a token can't lapse mid-request. */
    private const val EXPIRY_MARGIN_MS = 60_000L

    private const val PREFS_NAME = "ani.dantotsu.comick_oauth"
    private const val KEY_TOKEN = "token"
    private const val KEY_VERIFIER = "verifier"
    private const val KEY_STATE = "state"

    private val gson = Gson()
    private val JSON_MEDIA = "application/json".toMediaType()
    private val refreshLock = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Comick turns away a request with no Accept header — which is OkHttp's default — with a
        // 403 "Use the website authentication bridge", the token exchange included.
        .addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(
                if (request.header("Accept") != null) request
                else request.newBuilder().header("Accept", "application/json").build()
            )
        }
        .build()

    /** The current access token, or null when not connected. Restored by [getSavedToken]. */
    @Volatile
    var token: String? = null
        private set

    fun isConfigured(): Boolean = CLIENT_ID.isNotBlank()

    /**
     * Whether the connection may change the library. Users can approve reading alone, and a grant
     * never gains writes later: changing that takes a fresh login.
     */
    fun canWrite(): Boolean =
        readSaved()?.scope?.split(' ')?.contains("library:write") == true

    private fun prefs(): SharedPreferences? =
        (App.context ?: currContext())?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- login ----

    fun loginIntent(context: Context) {
        if (!isConfigured()) {
            snackString(context.getString(R.string.comick_login_not_configured))
            return
        }
        val verifier = randomUrlSafe(32)
        val state = randomUrlSafe(16)
        prefs()?.edit(commit = true) {
            putString(KEY_VERIFIER, verifier)
            putString(KEY_STATE, state)
        }
        val url = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("resource", RESOURCE)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", s256(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        try {
            CustomTabsIntent.Builder().build().launchUrl(context, url)
        } catch (_: ActivityNotFoundException) {
            openLinkInBrowser(url.toString())
        }
    }

    enum class LoginResult { SUCCESS, DENIED, FAILED }

    /**
     * Completes a login from the redirect [ComickLogin] caught. The state is checked — and the
     * pending attempt consumed — before anything else, error callbacks included, so a stale or
     * replayed redirect can't be exchanged.
     */
    suspend fun handleRedirect(redirect: Uri?): LoginResult = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext LoginResult.FAILED
        val expectedState = prefs.getString(KEY_STATE, null)
        val verifier = prefs.getString(KEY_VERIFIER, null)
        prefs.edit(commit = true) { remove(KEY_STATE); remove(KEY_VERIFIER) }

        val state = redirect?.getQueryParameter("state")
        if (expectedState.isNullOrBlank() || verifier.isNullOrBlank() || state != expectedState) {
            Logger.log("Comick: login redirect rejected (no pending attempt, or state mismatch)")
            return@withContext LoginResult.FAILED
        }
        redirect.getQueryParameter("error")?.let { error ->
            Logger.log("Comick: login returned error=$error")
            return@withContext if (error == "access_denied") LoginResult.DENIED else LoginResult.FAILED
        }
        val code = redirect.getQueryParameter("code") ?: return@withContext LoginResult.FAILED

        // A code is single-use: on any failure here the attempt is over, never retried.
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", verifier)
            .add("resource", RESOURCE)
            .add("client_id", CLIENT_ID)
            .build()
        val saved = try {
            http.newCall(Request.Builder().url(TOKEN_URL).post(body).build()).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) {
                    Logger.log("Comick token exchange: HTTP ${resp.code} — ${text.take(300)}")
                    return@withContext LoginResult.FAILED
                }
                saveResponse(gson.fromJson(text, TokenResponse::class.java))
            }
        } catch (e: Exception) {
            Logger.log("Comick token exchange failed: ${e.message}")
            return@withContext LoginResult.FAILED
        } ?: return@withContext LoginResult.FAILED

        token = saved.accessToken
        if (saved.refreshToken == null) {
            Logger.log("Comick: offline access declined — background checks stop when this token expires")
        }
        Logger.log("Comick: connected (scope: ${saved.scope})")
        LoginResult.SUCCESS
    }

    // ---- session ----

    /** Loads a saved connection into memory. No network: an expired token refreshes on first use. */
    fun getSavedToken(): Boolean {
        token?.let { return true }
        token = readSaved()?.accessToken
        return token != null
    }

    /**
     * `Authorization` header value for a library request, refreshing first when the access token
     * has expired. Null when not connected, or when the connection could not be renewed.
     */
    suspend fun authHeader(): String? {
        val saved = readSaved() ?: return null
        if (System.currentTimeMillis() < saved.expiresAt - EXPIRY_MARGIN_MS) {
            return "Bearer ${saved.accessToken}"
        }
        return refresh(saved.accessToken)?.let { "Bearer $it" }
    }

    /**
     * Exchanges the refresh token for a new pair. [staleAccessToken] is the token the caller found
     * unusable: if the stored one differs once the lock is held, another caller already refreshed
     * and that result is returned instead of rotating again.
     */
    private suspend fun refresh(staleAccessToken: String?): String? = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val saved = readSaved() ?: return@withContext null
            if (saved.accessToken != staleAccessToken &&
                System.currentTimeMillis() < saved.expiresAt - EXPIRY_MARGIN_MS
            ) return@withContext saved.accessToken

            val refreshToken = saved.refreshToken ?: run {
                Logger.log("Comick: access token expired and no refresh token (offline access declined)")
                clearLocal()
                return@withContext null
            }
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("resource", RESOURCE)
                .add("client_id", CLIENT_ID)
                .build()
            try {
                http.newCall(Request.Builder().url(TOKEN_URL).post(body).build()).execute().use { resp ->
                    val text = resp.body.string()
                    when {
                        resp.isSuccessful -> {
                            val renewed = saveResponse(gson.fromJson(text, TokenResponse::class.java))
                            token = renewed?.accessToken
                            renewed?.accessToken
                        }
                        // invalid_grant & co: revoked, expired, or the authorizing session ended.
                        resp.code in 400..401 -> {
                            Logger.log("Comick: refresh rejected (HTTP ${resp.code}) — ${text.take(200)}")
                            clearLocal()
                            null
                        }
                        // Maintenance / rate limit: the token was never consumed, so keep it.
                        else -> {
                            Logger.log("Comick: refresh unavailable (HTTP ${resp.code}), keeping session")
                            null
                        }
                    }
                }
            } catch (e: IOException) {
                // Only a request that never left the device is safe to retry later. Anything else
                // may have rotated the token server-side, and reusing the old one would revoke the
                // whole login — so that connection is dropped and the user reconnects.
                if (e is UnknownHostException || e is ConnectException) {
                    Logger.log("Comick: refresh not sent (${e.javaClass.simpleName}), keeping session")
                } else {
                    Logger.log("Comick: refresh outcome unknown (${e.message}), disconnecting")
                    clearLocal()
                }
                null
            } catch (e: Exception) {
                Logger.log("Comick: refresh failed: ${e.message}")
                null
            }
        }
    }

    /** Disconnects: revokes the refresh token server-side (best effort), then forgets everything. */
    suspend fun removeSavedToken() {
        val refreshToken = readSaved()?.refreshToken
        clearLocal()
        if (refreshToken == null || !isConfigured()) return
        withContext(Dispatchers.IO) {
            try {
                val body = FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("token", refreshToken)
                    .add("token_type_hint", "refresh_token")
                    .build()
                http.newCall(Request.Builder().url(REVOKE_URL).post(body).build()).execute().close()
            } catch (e: Exception) {
                Logger.log("Comick: token revocation failed: ${e.message}")
            }
        }
    }

    private fun clearLocal() {
        token = null
        prefs()?.edit(commit = true) { remove(KEY_TOKEN) }
    }

    private fun readSaved(): SavedToken? = runCatching {
        prefs()?.getString(KEY_TOKEN, null)?.let { gson.fromJson(it, SavedToken::class.java) }
    }.getOrNull()?.takeIf { !it.accessToken.isNullOrBlank() }

    /** Persists a token response — committed synchronously, since the old refresh token is now dead. */
    private fun saveResponse(res: TokenResponse?): SavedToken? {
        val access = res?.accessToken?.takeIf { it.isNotBlank() } ?: return null
        val saved = SavedToken(
            accessToken = access,
            refreshToken = res.refreshToken?.takeIf { it.isNotBlank() },
            expiresAt = System.currentTimeMillis() + (res.expiresIn ?: 900L) * 1000L,
            scope = res.scope,
        )
        prefs()?.edit(commit = true) { putString(KEY_TOKEN, gson.toJson(saved)) }
        return saved
    }

    // ---- library ----

    /**
     * The connected user's library, every page, filtered by [mediaType] (manga/anime, null for
     * both) and [status] (1–5, null for all). Null when not connected or a page fails — never a
     * partial list, which would read as titles having been unfollowed.
     */
    suspend fun getLibrary(mediaType: String?, status: Int?): List<ComickLibraryEntry>? =
        withContext(Dispatchers.IO) {
            val all = mutableListOf<ComickLibraryEntry>()
            var cursor: String? = null
            do {
                val url = LIBRARY_URL.toHttpUrl().newBuilder().apply {
                    addQueryParameter("limit", "100")
                    mediaType?.let { addQueryParameter("media_type", it) }
                    status?.let { addQueryParameter("status", it.toString()) }
                    cursor?.let { addQueryParameter("cursor", it) }
                }.build()
                val page = fetchLibraryPage(url.toString()) ?: return@withContext null
                all += page.data.orEmpty()
                cursor = page.next_cursor
            } while (cursor != null)
            all
        }

    /** One page of the library, or null on any failure. */
    private suspend fun fetchLibraryPage(url: String): ComickLibraryPage? {
        val res = call(url) ?: return null
        if (res.code in 200..299) {
            return runCatching { gson.fromJson(res.body, ComickLibraryPage::class.java) }.getOrNull()
        }
        logFailure("library", res)
        return null
    }

    /** What [getEntry] found. A null result instead means the lookup itself failed. */
    sealed class EntryLookup {
        data class Followed(val entry: ComickLibraryEntry) : EntryLookup()
        data object NotFollowed : EntryLookup()
    }

    /** The connected user's entry for the title [hid]. */
    suspend fun getEntry(hid: String): EntryLookup? {
        val res = call(entryUrl(hid)) ?: return null
        return when (res.code) {
            in 200..299 -> runCatching {
                gson.fromJson(res.body, ComickLibraryEntryResponse::class.java)?.data
            }.getOrNull()?.let { EntryLookup.Followed(it) }
            404 -> EntryLookup.NotFollowed
            else -> {
                logFailure("entry", res)
                null
            }
        }
    }

    /**
     * Follows [hid] or updates its existing entry (`PUT`). [status] is required when the title
     * isn't followed yet; anything left null is preserved on Comick. [progressNumber] is a
     * chapter/episode number as text ("12", "12.5"), never an identifier.
     */
    suspend fun putEntry(hid: String, status: Int?, progressNumber: String?): Boolean =
        write("PUT", hid, status, progressNumber)

    /** Updates an entry that already exists (`PATCH`); false when the title isn't followed. */
    suspend fun patchEntry(hid: String, status: Int?, progressNumber: String?): Boolean =
        write("PATCH", hid, status, progressNumber)

    private suspend fun write(method: String, hid: String, status: Int?, progressNumber: String?): Boolean {
        if (!canWrite()) return false
        val body = JsonObject().apply {
            status?.let { addProperty("status", it) }
            progressNumber?.let { number ->
                add("progress", JsonObject().apply { addProperty("number", number) })
            }
        }
        if (body.size() == 0) return true
        val res = call(entryUrl(hid), method, gson.toJson(body)) ?: return false
        if (res.code in 200..299) return true
        logFailure("$method entry", res)
        return false
    }

    private fun entryUrl(hid: String) = "$LIBRARY_URL/${Uri.encode(hid)}"

    private class ApiResponse(val code: Int, val body: String)

    /**
     * An authorised request. On a 401 it refreshes once and retries once, as the docs prescribe.
     * Null when not connected, or when the request couldn't be made at all.
     */
    private suspend fun call(url: String, method: String = "GET", json: String? = null): ApiResponse? =
        withContext(Dispatchers.IO) {
            repeat(2) { attempt ->
                val header = authHeader() ?: return@withContext null
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", header)
                    .method(method, json?.toRequestBody(JSON_MEDIA))
                    .build()
                try {
                    http.newCall(request).execute().use { resp ->
                        val text = resp.body.string()
                        if (resp.code == 401 && attempt == 0) {
                            refresh(header.removePrefix("Bearer "))
                            return@repeat
                        }
                        return@withContext ApiResponse(resp.code, text)
                    }
                } catch (e: Exception) {
                    Logger.log("Comick $method request failed: ${e.message}")
                    return@withContext null
                }
            }
            null
        }

    private fun logFailure(what: String, res: ApiResponse) {
        val message = runCatching {
            gson.fromJson(res.body, JsonObject::class.java)?.get("message")?.asString
        }.getOrNull()
        Logger.log("Comick $what: HTTP ${res.code}${message?.let { " — $it" } ?: ""}")
    }

    // ---- PKCE ----

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun s256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private data class TokenResponse(
        @SerializedName("access_token") val accessToken: String?,
        @SerializedName("refresh_token") val refreshToken: String?,
        @SerializedName("expires_in") val expiresIn: Long?,
        val scope: String?,
    )

    private data class SavedToken(
        val accessToken: String?,
        val refreshToken: String?,
        /** Epoch millis. */
        val expiresAt: Long,
        val scope: String?,
    )
}
