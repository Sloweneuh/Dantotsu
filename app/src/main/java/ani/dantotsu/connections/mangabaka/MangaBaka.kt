package ani.dantotsu.connections.mangabaka

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import ani.dantotsu.client
import ani.dantotsu.snackString
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * MangaBaka integration.
 *
 * **Login is OAuth2** (authorization code + PKCE) against `mangabaka.org/auth/oauth2`. The user is
 * sent to the browser, approves, and comes back to [Login] via the `dantotsu://mangabaka` redirect.
 * The access token is sent as `Authorization: Bearer …` and refreshed with the `offline_access`
 * refresh token when it expires.
 *
 * **Legacy Personal Access Tokens still work.** A token stored under [PrefName.MangaBakaToken] (the
 * old `mb-…` PAT) is sent via the `x-api-key` header as before — new logins just don't create one.
 */
object MangaBaka {
    private const val API_URL = "https://api.mangabaka.org"
    const val WEB_URL = "https://mangabaka.org"

    private const val AUTHORIZE_URL = "$WEB_URL/auth/oauth2/authorize"
    private const val TOKEN_URL = "$WEB_URL/auth/oauth2/token"
    const val REDIRECT_URI = "dantotsu://mangabaka"
    private const val SCOPE = "openid profile library.read library.write offline_access"

    // TODO: register a "Dantotsu" OAuth app at mangabaka.org and paste its client id here. Leave the
    //  secret blank for a public (PKCE-only) client; set it for a confidential one.
    const val CLIENT_ID = "HpcODpPkOAYREzQwYpntmRcBFLQPsmkN"
    private const val CLIENT_SECRET = ""

    fun isConfigured(): Boolean =
        CLIENT_ID.isNotBlank() && !CLIENT_ID.startsWith("PLACEHOLDER")

    /** In-memory session. [token] is the bearer access token (OAuth) or the raw PAT (legacy). */
    var token: String? = null
    var username: String? = null
    var userid: String? = null

    /** True while [token] is a legacy PAT rather than an OAuth access token. */
    private var isPat: Boolean = false

    // ---- OAuth login ----

    fun loginIntent(context: Context) {
        if (!isConfigured()) {
            snackString("MangaBaka login isn't configured in this build")
            return
        }
        val verifier = randomUrlSafe(64)
        PrefManager.setVal(PrefName.MangaBakaCodeVerifier, verifier)
        val challenge = s256(verifier)
        val url = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        // Runs in [MangaBakaOAuthActivity]'s WebView rather than the system browser: the
        // `dantotsu://mangabaka` redirect can't come back from a browser running on the Windows host
        // (WSA), and the WebView also gives us a real Chrome UA + persistent cookies for Cloudflare.
        // Kept in the caller's task (no NEW_TASK) so the post-login app restart works on WSA.
        context.startActivity(
            Intent(context, MangaBakaOAuthActivity::class.java).putExtra("url", url.toString()),
        )
    }

    /** Exchanges the authorization code from the redirect for tokens, then caches the profile. */
    suspend fun handleAuthCode(code: String): Boolean {
        val verifier = PrefManager.getVal(PrefName.MangaBakaCodeVerifier, null as String?)
        if (verifier.isNullOrBlank()) {
            Logger.log("MangaBaka: token exchange skipped — no stored PKCE verifier")
            return false
        }
        val res = tryWithSuspend {
            val resp = client.post(TOKEN_URL, data = buildMap {
                put("grant_type", "authorization_code")
                put("code", code)
                put("redirect_uri", REDIRECT_URI)
                put("client_id", CLIENT_ID)
                put("code_verifier", verifier)
                if (CLIENT_SECRET.isNotBlank()) put("client_secret", CLIENT_SECRET)
            })
            if (resp.code !in 200..299) {
                Logger.log("MangaBaka token exchange: HTTP ${resp.code} — ${resp.text.take(500)}")
            }
            resp.parsed<ResponseToken>()
        } ?: return false
        PrefManager.removeVal(PrefName.MangaBakaCodeVerifier)
        saveResponse(res)
        token = res.accessToken
        isPat = false
        val ok = getUserData()
        if (!ok) removeSavedToken()
        return ok
    }

    private suspend fun refresh(): ResponseToken? = tryWithSuspend {
        val saved = PrefManager.getNullableVal<ResponseToken>(PrefName.MangaBakaOAuthToken, null)
            ?: return@tryWithSuspend null
        val rt = saved.refreshToken?.takeIf { it.isNotBlank() } ?: return@tryWithSuspend null
        val res = client.post(TOKEN_URL, data = buildMap {
            put("grant_type", "refresh_token")
            put("refresh_token", rt)
            put("client_id", CLIENT_ID)
            if (CLIENT_SECRET.isNotBlank()) put("client_secret", CLIENT_SECRET)
        }).parsed<ResponseToken>()
        // Some providers omit the refresh token on refresh — keep the old one.
        val merged = if (res.refreshToken.isNullOrBlank()) res.copy(refreshToken = rt) else res
        saveResponse(merged)
        merged
    }

    // ---- headers / session ----

    /**
     * Headers for an authenticated request, refreshing an expired OAuth token first. Null when not
     * logged in. Legacy PATs go out as `x-api-key`; OAuth tokens as `Authorization: Bearer`.
     */
    suspend fun authHeaders(): Map<String, String>? {
        if (isPat) return token?.let { mapOf("x-api-key" to it) }
        var t = token
        val saved = PrefManager.getNullableVal<ResponseToken>(PrefName.MangaBakaOAuthToken, null)
        if (saved != null && System.currentTimeMillis() > saved.expiresIn) {
            t = refresh()?.accessToken
            token = t
        }
        return t?.let { mapOf("Authorization" to "Bearer $it") }
    }

    suspend fun getUserData(): Boolean {
        val header = authHeaders() ?: return false
        val res = tryWithSuspend {
            client.get("$API_URL/v1/my/profile", header).parsed<ProfileResponse>()
        }?.data ?: return false

        userid = res.id
        username = res.preferredUsername ?: res.nickname ?: res.id
        PrefManager.setVal(PrefName.MangaBakaUserId, res.id)
        PrefManager.setVal(PrefName.MangaBakaUserName, username ?: "")
        Logger.log("MangaBaka: Logged in as $username")
        return true
    }

    /** Restores a saved session — OAuth token first (refreshing if stale), then a legacy PAT. */
    suspend fun getSavedToken(): Boolean {
        if (!token.isNullOrBlank()) return true

        val oauth = PrefManager.getNullableVal<ResponseToken>(PrefName.MangaBakaOAuthToken, null)
        if (oauth != null) {
            isPat = false
            token = if (System.currentTimeMillis() > oauth.expiresIn) refresh()?.accessToken
            else oauth.accessToken
            if (token == null) return false
        } else {
            val pat = PrefManager.getVal(PrefName.MangaBakaToken, null as String?)
                ?.takeIf { it.isNotBlank() } ?: return false
            token = pat
            isPat = true
        }
        username = PrefManager.getVal(PrefName.MangaBakaUserName, null as String?)
        userid = PrefManager.getVal(PrefName.MangaBakaUserId, null as String?)
        return if (username.isNullOrBlank() || userid.isNullOrBlank()) getUserData() else true
    }

    fun removeSavedToken() {
        token = null
        username = null
        userid = null
        isPat = false
        PrefManager.removeVal(PrefName.MangaBakaToken)
        PrefManager.removeVal(PrefName.MangaBakaOAuthToken)
        PrefManager.removeVal(PrefName.MangaBakaUserName)
        PrefManager.removeVal(PrefName.MangaBakaUserId)
        Logger.log("MangaBaka: Logged out")
    }

    private fun saveResponse(res: ResponseToken) {
        res.expiresIn = System.currentTimeMillis() + res.expiresIn * 1000
        PrefManager.setVal(PrefName.MangaBakaOAuthToken, res)
    }

    // ---- PKCE helpers ----

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun s256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    @Serializable
    data class ResponseToken(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("expires_in") var expiresIn: Long = 3600,
        val scope: String? = null,
    ) : java.io.Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    @Serializable
    data class ProfileResponse(
        val status: Int? = null,
        val data: Profile? = null,
    )

    @Serializable
    data class Profile(
        val id: String,
        val nickname: String? = null,
        @SerialName("preferred_username") val preferredUsername: String? = null,
        val role: String? = null,
        @SerialName("auth_type") val authType: String? = null,
        val scopes: List<String>? = null,
    )
}
