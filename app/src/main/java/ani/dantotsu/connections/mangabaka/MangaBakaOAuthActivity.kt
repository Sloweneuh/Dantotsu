package ani.dantotsu.connections.mangabaka

import android.annotation.SuppressLint
import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-app OAuth for MangaBaka.
 *
 * A system-browser redirect back to `dantotsu://mangabaka` only works when the browser runs *inside*
 * Android — on WSA (Windows host browser) the custom-scheme redirect has nowhere to land. So the
 * whole flow runs in this WebView instead: it carries a real Chrome UA + persistent cookies (so
 * Cloudflare's challenge passes and stays passed), and intercepts the `dantotsu://mangabaka?code=…`
 * navigation directly.
 */
class MangaBakaOAuthActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()

        val url = intent.getStringExtra("url")
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val process = Application.getProcessName()
            if (packageName != process) WebView.setDataDirectorySuffix(process)
        }
        setContentView(R.layout.activity_discord)
        val webView = findViewById<WebView>(R.id.discordWebview)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/122.0.0.0 Mobile Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                if (u.scheme == "dantotsu" && u.host == "mangabaka") {
                    complete(u)
                    return true
                }
                return false
            }
        }
        webView.loadUrl(url)
    }

    private fun complete(redirect: Uri) {
        val error = redirect.getQueryParameter("error")
        val code = redirect.getQueryParameter("code")
        Logger.log("MangaBaka OAuth redirect: $redirect")
        if (code == null) {
            snackString("MangaBaka login failed" + (error?.let { ": $it" } ?: ""))
            finish()
            return
        }
        snackString("Logging in to MangaBaka…")
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = MangaBaka.handleAuthCode(code)
            withContext(Dispatchers.Main) {
                snackString(if (ok) "Successfully logged into MangaBaka" else "MangaBaka login failed")
                // Just pop back to the Accounts screen; it refreshes in onResume. No app restart —
                // MangaBaka is a list-sync tracker, the token is already live in memory, and the
                // full restart chokes WSA's task manager.
                finish()
            }
        }
    }
}
