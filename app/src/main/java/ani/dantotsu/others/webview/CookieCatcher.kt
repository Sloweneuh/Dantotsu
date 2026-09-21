package ani.dantotsu.others.webview

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityDiscordBinding
import ani.dantotsu.defaultHeaders
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.getSerializableExtraCompat
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Opens a source's own site so the user can clear a Cloudflare challenge or log into an account
 * the extension needs — reachable from the anime/manga "Options" sheet as "Open website". Shares
 * the app's cookie jar and UA, so whatever gets cleared here is what the extension's own requests
 * see afterwards.
 */
class CookieCatcher : AppCompatActivity() {

    private lateinit var binding: ActivityDiscordBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityDiscordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        val url = intent.getStringExtra("url") ?: getString(R.string.cursed_yt)
        val headers: Map<String, String> =
            intent.getSerializableExtraCompat("headers") as? Map<String, String> ?: emptyMap()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val process = Application.getProcessName()
            if (packageName != process) WebView.setDataDirectorySuffix(process)
        }

        binding.discordWebviewToolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        binding.discordWebview.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.discordWebviewTitle.text = Uri.parse(url).host ?: url
        binding.discordWebviewOpenExternal.isVisible = true

        val webView = binding.discordWebview
        webView.setDefaultSettings()
        webView.settings.userAgentString = defaultHeaders["User-Agent"]

        val cookies: CookieManager? = Injekt.get<NetworkHelper>().cookieJar.manager
        cookies?.setAcceptThirdPartyCookies(webView, true)

        WebView.setWebContentsDebuggingEnabled(true)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun onPageStarted(view: WebView?, loadedUrl: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, loadedUrl, favicon)
                binding.discordWebviewProgress.isVisible = true
            }

            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                super.onPageFinished(view, loadedUrl)
                binding.discordWebviewProgress.isVisible = false
                binding.discordWebviewTitle.text = view?.title?.takeIf { it.isNotBlank() }
                    ?: loadedUrl?.let { Uri.parse(it).host } ?: url
            }
        }

        webView.loadUrl(url, headers)

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        binding.discordWebviewBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.discordWebviewReload.setOnClickListener { webView.reload() }
        binding.discordWebviewOpenExternal.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView.url ?: url)))
            } catch (e: Exception) {
                snackString("Failed to open link: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        binding.discordWebview.destroy()
        super.onDestroy()
    }
}
