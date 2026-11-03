package ani.dantotsu.connections.discord

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.TextView
import ani.dantotsu.R
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.tryWith
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

object Discord {

    var token: String? = null
    var userid: String? = null
    var avatar: String? = null

    fun getSavedToken(): Boolean {
        token = PrefManager.getVal(PrefName.DiscordToken, null as String?)
        return token != null
    }

    fun saveToken(newToken: String) {
        PrefManager.setVal(PrefName.DiscordToken, newToken)
        token = newToken  // keep in-memory token in sync
    }

    /**
     * Signs in with an account token however it was got (the login page, or a scan from the phone's
     * Discord app): the account's name and avatar first, which also proves the token good, then the
     * presence token made from it, then saved. False, and nothing kept, if any of it fails.
     */
    suspend fun completeLogin(context: Context, token: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val response = DiscordHttpClient.instance.newCall(
                Request.Builder().url("https://discord.com/api/v9/users/@me").header("Authorization", token).build()
            ).execute()
            if (!response.isSuccessful) throw IllegalStateException("Failed to fetch user: ${response.code}")
            response.body?.string()?.let { body ->
                val json = JSONObject(body)
                PrefManager.setVal(PrefName.DiscordId, json.optString("id"))
                PrefManager.setVal(PrefName.DiscordUserName, json.optString("username"))
                PrefManager.setVal(PrefName.DiscordAvatar, json.optString("avatar"))
            }
            // The OAuth2 bearer token presence is set with (PKCE, a few seconds).
            TokenManager(authToken = token, filesDir = File(context.filesDir, "discord")).getToken()
            saveToken(token)
            true
        } catch (e: Exception) {
            ani.dantotsu.util.Logger.log("Discord: login failed — ${e.message}")
            false
        }
    }

    /** The signed-in account's avatar, for showing it; null without one. */
    fun avatarUrl(): String? {
        val id = PrefManager.getVal(PrefName.DiscordId, null as String?)?.takeIf { it.isNotBlank() } ?: return null
        val hash = PrefManager.getVal(PrefName.DiscordAvatar, null as String?)?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        return "https://cdn.discordapp.com/avatars/$id/$hash.png?size=128"
    }

    fun removeSavedToken(context: Context) {
        PrefManager.removeVal(PrefName.DiscordToken)
        PrefManager.removeVal(PrefName.DiscordId)
        PrefManager.removeVal(PrefName.DiscordUserName)
        PrefManager.removeVal(PrefName.DiscordAvatar)
        token = null
        userid = null
        avatar = null
        RPCManager.reset()
        tryWith(true) {
            // Clear the actual Discord tokens cached by TokenManager
            val discordDir = File(context.filesDir, "discord")
            if (discordDir.deleteRecursively())
                toast(context.getString(R.string.discord_logout_success))
                
            // Clear WebView cookies and storage on the main thread so auto-login doesn't happen
            Handler(Looper.getMainLooper()).post {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            }
        }
    }

    fun warning(context: Context) = CustomBottomDialog().apply {
        title = context.getString(R.string.warning)
        val md = context.getString(R.string.discord_warning)
        addView(TextView(context).apply {
            val markWon =
                Markwon.builder(context).usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
            markWon.setMarkdown(this, md)
        })

        setNegativeButton(context.getString(R.string.cancel)) {
            dismiss()
        }

        setPositiveButton(context.getString(R.string.login)) {
            dismiss()
            loginIntent(context)
        }
    }

    private fun loginIntent(context: Context) {
        val intent = Intent(context, Login::class.java)
        context.startActivity(intent)
    }

    const val application_Id = "1163925779692912771"
    const val small_Image: String =
        "https://cdn.discordapp.com/emojis/1167344924874784828.webp?size=128&quality=lossless"
    const val small_Image_AniList: String =
        "https://anilist.co/img/icons/android-chrome-512x512.png"
    const val small_Image_MAL: String =
        "https://cdn.myanimelist.net/img/sp/icon/apple-touch-icon-256.png"
    const val small_Image_Simkl: String =
        "https://eu.simkl.in/img_favicon/v2/favicon-192x192.png"
    // Kitsu's own icons carry a build hash in their file name, so its GitHub organisation's
    // avatar — the same fox — is the address that will still work after its next deploy.
    const val small_Image_Kitsu: String =
        "https://avatars.githubusercontent.com/u/7648832?s=256"
    const val small_Image_MangaBaka: String =
        "https://mangabaka.org/images/maomao/180x180.png"
    const val small_Image_Comick: String =
        "https://comick.dev/static/icons/unicorn-256_maskable.png"
    const val small_Image_MangaUpdates: String =
        "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQ8HPq8qdXYifvxu8XdsbuSiE4vjlLYw4k8kg&s"
}
