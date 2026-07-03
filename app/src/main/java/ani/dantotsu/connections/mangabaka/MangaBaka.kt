package ani.dantotsu.connections.mangabaka

import ani.dantotsu.client
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MangaBaka integration.
 *
 * Authentication uses a Personal Access Token (PAT) that the user generates on the MangaBaka
 * website. The token starts with "mb-" and is sent on every authenticated request via the
 * `x-api-key` header (see the `x-api-key` security scheme in the API docs). The token is
 * validated by fetching the signed-in user's profile (`GET /v1/my/profile`).
 */
object MangaBaka {
    private const val API_URL = "https://api.mangabaka.org"
    const val WEB_URL = "https://mangabaka.org"

    var token: String? = null
    var username: String? = null
    var userid: String? = null

    /** Header map used for authenticated requests. Null when not logged in. */
    private val authHeader: Map<String, String>?
        get() = token?.let { mapOf("x-api-key" to it) }

    /**
     * Attempts to log in with the given Personal Access Token.
     * Validates the token against `/v1/my/profile` and, on success, stores it.
     * @return true if the token is valid and the user was logged in.
     */
    suspend fun login(pat: String): Boolean {
        val trimmed = pat.trim()
        if (trimmed.isBlank()) return false
        token = trimmed
        val ok = getUserData()
        if (ok) {
            PrefManager.setVal(PrefName.MangaBakaToken, trimmed)
        } else {
            token = null
        }
        return ok
    }

    /**
     * Fetches the authenticated user's profile and caches it. Requires [token] to be set.
     * @return true on success.
     */
    suspend fun getUserData(): Boolean {
        val header = authHeader ?: return false
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

    /**
     * Restores a previously saved token and refreshes the cached profile.
     * @return true if a valid session is available.
     */
    suspend fun getSavedToken(): Boolean {
        if (!token.isNullOrBlank()) return true
        val saved = PrefManager.getVal(PrefName.MangaBakaToken, null as String?)
            ?.takeIf { it.isNotBlank() } ?: return false
        token = saved
        username = PrefManager.getVal(PrefName.MangaBakaUserName, null as String?)
        userid = PrefManager.getVal(PrefName.MangaBakaUserId, null as String?)
        // If we don't yet have the cached username, refresh it (also validates the token).
        return if (username.isNullOrBlank()) getUserData() else true
    }

    /** Clears the in-memory session and removes the stored token/profile. */
    fun removeSavedToken() {
        token = null
        username = null
        userid = null
        PrefManager.removeVal(PrefName.MangaBakaToken)
        PrefManager.removeVal(PrefName.MangaBakaUserName)
        PrefManager.removeVal(PrefName.MangaBakaUserId)
        Logger.log("MangaBaka: Logged out")
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
