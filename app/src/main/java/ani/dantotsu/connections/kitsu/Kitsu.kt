package ani.dantotsu.connections.kitsu

import ani.dantotsu.client
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kitsu account + session, for one-way list sync.
 *
 * Authentication is OAuth2 *password grant* against `https://kitsu.io/api/oauth/token` — the user
 * types their Kitsu email/username and password into a dialog ([KitsuLoginDialog]) and Dantotsu
 * exchanges them for a bearer token. The `clientId`/`clientSecret` below are the public app
 * credentials shipped with every Kitsu client (they carry no user-specific privilege), the same way
 * [ani.dantotsu.connections.mal.MAL.clientId] is embedded here.
 *
 * This is the *account* side (token handling, current user). Media-id resolution lives in
 * [KitsuApi] and list writes in [KitsuSync], mirroring the MangaBaka split.
 *
 * Not to be confused with [ani.dantotsu.others.Kitsu], which is an unauthenticated GraphQL helper
 * used only for episode thumbnails/titles.
 */
object Kitsu {
    const val API_URL = "https://kitsu.io/api/edge"
    const val WEB_URL = "https://kitsu.io"
    private const val TOKEN_URL = "https://kitsu.io/api/oauth/token"

    // Public client credentials shared by Kitsu clients (present in every shipped app).
    private const val CLIENT_ID =
        "dd031b32d2f56c990b1425efe6c42ad847e7fe3ab46bf1299f05ecd856bdb7dd"
    private const val CLIENT_SECRET =
        "54d7307928f63414defd96399fc31ba847961ceaecef3a5fd93144e960c0e151"

    var token: String? = null
    var username: String? = null
    var userid: String? = null
    var slug: String? = null
    var avatar: String? = null

    val authHeader: Map<String, String>?
        get() = token?.let {
            mapOf(
                "Authorization" to "Bearer $it",
                "Accept" to "application/vnd.api+json",
                "Content-Type" to "application/vnd.api+json",
            )
        }

    /** Exchanges an email/username + password for a token, then caches the profile. */
    suspend fun login(user: String, pass: String): Boolean {
        val res = tryWithSuspend {
            client.post(
                TOKEN_URL,
                data = mapOf(
                    "grant_type" to "password",
                    "username" to user.trim(),
                    "password" to pass,
                    "client_id" to CLIENT_ID,
                    "client_secret" to CLIENT_SECRET,
                ),
            ).parsed<ResponseToken>()
        } ?: return false
        saveResponse(res)
        token = res.accessToken
        val ok = getUserData()
        if (!ok) {
            removeSavedToken()
        }
        return ok
    }

    private suspend fun refreshToken(): ResponseToken? = tryWithSuspend {
        val saved = PrefManager.getNullableVal<ResponseToken>(PrefName.KitsuToken, null)
            ?: return@tryWithSuspend null
        val res = client.post(
            TOKEN_URL,
            data = mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to saved.refreshToken,
                "client_id" to CLIENT_ID,
                "client_secret" to CLIENT_SECRET,
            ),
        ).parsed<ResponseToken>()
        saveResponse(res)
        res
    }

    /** Fetches the signed-in user (`GET /users?filter[self]=true`) and caches name/slug/avatar. */
    suspend fun getUserData(): Boolean {
        val header = authHeader ?: return false
        val res = tryWithSuspend {
            client.get("$API_URL/users?filter%5Bself%5D=true", header).parsed<UsersResponse>()
        }?.data?.firstOrNull() ?: return false

        userid = res.id
        username = res.attributes?.name ?: res.attributes?.slug
        slug = res.attributes?.slug
        avatar = res.attributes?.avatar?.original ?: res.attributes?.avatar?.large
            ?: res.attributes?.avatar?.medium
        PrefManager.setVal(PrefName.KitsuUserId, userid ?: "")
        PrefManager.setVal(PrefName.KitsuUserName, username ?: "")
        PrefManager.setVal(PrefName.KitsuSlug, slug ?: "")
        PrefManager.setVal(PrefName.KitsuAvatar, avatar ?: "")
        Logger.log("Kitsu: Logged in as $username")
        return true
    }

    /** Restores a saved token (refreshing if expired) and the cached profile. */
    suspend fun getSavedToken(): Boolean {
        if (!token.isNullOrBlank()) return true
        var saved = PrefManager.getNullableVal<ResponseToken>(PrefName.KitsuToken, null)
            ?: return false
        if (System.currentTimeMillis() > saved.expiresIn) {
            saved = refreshToken() ?: return false
        }
        token = saved.accessToken
        username = PrefManager.getVal(PrefName.KitsuUserName, null as String?)
        userid = PrefManager.getVal(PrefName.KitsuUserId, null as String?)
        slug = PrefManager.getVal(PrefName.KitsuSlug, null as String?)
        avatar = PrefManager.getVal(PrefName.KitsuAvatar, null as String?)
        return if (username.isNullOrBlank() || userid.isNullOrBlank()) getUserData() else true
    }

    fun removeSavedToken() {
        token = null
        username = null
        userid = null
        slug = null
        avatar = null
        PrefManager.removeVal(PrefName.KitsuToken)
        PrefManager.removeVal(PrefName.KitsuUserName)
        PrefManager.removeVal(PrefName.KitsuUserId)
        PrefManager.removeVal(PrefName.KitsuSlug)
        PrefManager.removeVal(PrefName.KitsuAvatar)
        Logger.log("Kitsu: Logged out")
    }

    private fun saveResponse(res: ResponseToken) {
        // Kitsu reports a lifetime in seconds; store the absolute expiry like MAL does.
        res.expiresIn = System.currentTimeMillis() + res.expiresIn * 1000
        PrefManager.setVal(PrefName.KitsuToken, res)
    }

    @Serializable
    data class ResponseToken(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String = "",
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("expires_in") var expiresIn: Long = 0,
    ) : java.io.Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    @Serializable
    data class UsersResponse(val data: List<UserData>? = null)

    @Serializable
    data class UserData(
        val id: String,
        val attributes: UserAttributes? = null,
    )

    @Serializable
    data class UserAttributes(
        val name: String? = null,
        val slug: String? = null,
        val avatar: Avatar? = null,
    )

    @Serializable
    data class Avatar(
        val original: String? = null,
        val large: String? = null,
        val medium: String? = null,
    )
}
