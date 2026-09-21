package ani.dantotsu.connections.discord

import android.annotation.SuppressLint
import android.app.Application.getProcessName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.connections.discord.Discord.saveToken
import ani.dantotsu.MainActivity
import ani.dantotsu.databinding.ActivityDiscordBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

class Login : AppCompatActivity() {

    private val discordAppPattern = Regex("https://discord\\.com/(app|channels)")
    private var tokenExtracted = false
    private lateinit var binding: ActivityDiscordBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val process = getProcessName()
            if (packageName != process) WebView.setDataDirectorySuffix(process)
        }
        binding = ActivityDiscordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.discordWebviewToolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        binding.discordWebview.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.discordWebviewTitle.text = "Discord Login"

        val webView = binding.discordWebview

        webView.apply {
            settings.javaScriptEnabled = true
            @Suppress("DEPRECATION")
            settings.databaseEnabled = true
            settings.domStorageEnabled = true
        }
        WebView.setWebContentsDebuggingEnabled(true)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.discordWebviewProgress.isVisible = true
                view?.evaluateJavascript(
                    """window.LOCAL_STORAGE = localStorage""".trimIndent()
                ) {}
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.discordWebviewProgress.isVisible = false

                // Extract token only once when Discord app/channels page loads
                if (!tokenExtracted && url != null && discordAppPattern.containsMatchIn(url)) {
                    tokenExtracted = true
                    view?.evaluateJavascript(
                        """
                        (function() {
                            return window.LOCAL_STORAGE.getItem('token');
                        })();
                        """.trimIndent()
                    ) { result ->
                        val token = result?.let {
                            runCatching {
                                JSONObject("{\"token\":$it}").getString("token")
                            }.getOrNull()
                        }?.trim('"')?.takeIf {
                            it.isNotEmpty() && it != "null" && it.length > 69
                        }

                        if (token != null) {
                            login(token)
                        } else {
                            tokenExtracted = false // retry on next page
                        }
                    }
                }
            }
        }

        webView.loadUrl("https://discord.com/login")

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        binding.discordWebviewBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.discordWebviewReload.setOnClickListener { webView.reload() }
    }

    private fun login(token: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            var loginSuccessful = false

            try {
                // 1. Fetch user details FIRST (fast, validates the token)
                val client = DiscordHttpClient.instance
                val request = Request.Builder()
                    .url("https://discord.com/api/v9/users/@me")
                    .header("Authorization", token)
                    .build()

                val userResponse = client.newCall(request).execute()
                if (!userResponse.isSuccessful) {
                    throw IllegalStateException("Failed to fetch user: ${userResponse.code}")
                }

                userResponse.body?.string()?.let { jsonString ->
                    val json = JSONObject(jsonString)
                    PrefManager.setVal(PrefName.DiscordId, json.optString("id"))
                    PrefManager.setVal(PrefName.DiscordUserName, json.optString("username"))
                    PrefManager.setVal(PrefName.DiscordAvatar, json.optString("avatar"))
                }

                // 2. Pre-fetch OAuth2 Bearer token (can take 3-5s for PKCE)
                TokenManager(
                    authToken = token,
                    filesDir = File(filesDir, "discord")
                ).getToken()

                // 3. Both succeeded
                loginSuccessful = true

            } catch (e: Exception) {
                Logger.log("Login: Login failed — ${e.message}")
            }

            withContext(Dispatchers.Main) {
                if (loginSuccessful) {
                    saveToken(token)
                    Toast.makeText(this@Login, "Logged in successfully", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@Login, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } else {
                    Toast.makeText(this@Login, "Login failed. Please try again.", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }
    }

    override fun onDestroy() {
        binding.discordWebview.destroy()
        super.onDestroy()
    }
}
