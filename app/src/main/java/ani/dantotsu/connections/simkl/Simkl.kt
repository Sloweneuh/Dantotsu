package ani.dantotsu.connections.simkl

import ani.dantotsu.client
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Simkl account + session, for one-way **anime** list sync (Simkl has no manga API).
 *
 * Authentication is the OAuth2 *PIN / device-code* flow ([SimklLoginDialog]): the app shows a short
 * code, the user approves it at `simkl.com/pin`, and the app polls for the token. No client secret
 * is needed — only a registered `CLIENT_ID` (the Simkl "API key"), obtained at
 * `https://simkl.com/settings/developer`. Access tokens do not expire, so there is no refresh flow.
 *
 * >  A real [CLIENT_ID] must be filled in before Simkl login can work in a build.
 */
object Simkl {
    const val API_URL = "https://api.simkl.com"
    const val WEB_URL = "https://simkl.com"

    const val CLIENT_ID = "6e1794c663d82391d663d1214e5c7c055f8d4a451a78c1c7f9d5d2a9a14974f9"

    fun isConfigured(): Boolean =
        CLIENT_ID.isNotBlank() && !CLIENT_ID.startsWith("PLACEHOLDER")

    var token: String? = null
    var username: String? = null
    var userid: String? = null
    var avatar: String? = null

    val authHeader: Map<String, String>?
        get() = token?.let {
            mapOf(
                "Authorization" to "Bearer $it",
                "simkl-api-key" to CLIENT_ID,
                "Content-Type" to "application/json",
            )
        }

    /** Stores a freshly-issued token and caches the profile. */
    suspend fun onToken(accessToken: String): Boolean {
        token = accessToken
        PrefManager.setVal(PrefName.SimklToken, accessToken)
        val ok = getUserData()
        if (!ok) removeSavedToken()
        return ok
    }

    /** Fetches the signed-in user (`GET /users/settings`) and caches name/id/avatar. */
    suspend fun getUserData(): Boolean {
        val header = authHeader ?: return false
        val res = tryWithSuspend {
            client.get("$API_URL/users/settings", header).parsed<SettingsResponse>()
        } ?: return false

        userid = res.account?.id?.toString()
        username = res.user?.name
        avatar = res.user?.avatar
        PrefManager.setVal(PrefName.SimklUserId, userid ?: "")
        PrefManager.setVal(PrefName.SimklUserName, username ?: "")
        PrefManager.setVal(PrefName.SimklAvatar, avatar ?: "")
        Logger.log("Simkl: Logged in as $username")
        return true
    }

    suspend fun getSavedToken(): Boolean {
        if (!token.isNullOrBlank()) return true
        val saved = PrefManager.getVal(PrefName.SimklToken, null as String?)
            ?.takeIf { it.isNotBlank() } ?: return false
        token = saved
        username = PrefManager.getVal(PrefName.SimklUserName, null as String?)
        userid = PrefManager.getVal(PrefName.SimklUserId, null as String?)
        avatar = PrefManager.getVal(PrefName.SimklAvatar, null as String?)
        return if (username.isNullOrBlank() || userid.isNullOrBlank()) getUserData() else true
    }

    fun removeSavedToken() {
        token = null
        username = null
        userid = null
        avatar = null
        PrefManager.removeVal(PrefName.SimklToken)
        PrefManager.removeVal(PrefName.SimklUserName)
        PrefManager.removeVal(PrefName.SimklUserId)
        PrefManager.removeVal(PrefName.SimklAvatar)
        Logger.log("Simkl: Logged out")
    }

    @Serializable
    data class SettingsResponse(
        val user: SimklUser? = null,
        val account: SimklAccount? = null,
    )

    @Serializable
    data class SimklUser(
        val name: String? = null,
        val avatar: String? = null,
    )

    @Serializable
    data class SimklAccount(
        val id: Long? = null,
        @SerialName("timezone") val timezone: String? = null,
    )
}
