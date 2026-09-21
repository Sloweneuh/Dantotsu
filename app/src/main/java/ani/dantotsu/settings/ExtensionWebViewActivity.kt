package ani.dantotsu.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import ani.dantotsu.databinding.ActivityExtensionWebviewBinding
import ani.dantotsu.defaultHeaders
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A plain in-app browser for an extension's own site.
 *
 * Extensions parse a source's HTML, they don't render it, so there's normally no way to just look
 * at the site — e.g. to solve a Cloudflare challenge, log into an account the extension needs, or
 * check whether a listing looks right. This loads it with the app's own UA and cookie jar, so
 * anything gained here (a cleared challenge, a session cookie) is visible to the extension's own
 * requests afterwards.
 */
class ExtensionWebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
    }

    private lateinit var binding: ActivityExtensionWebviewBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityExtensionWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        binding.extensionWebViewToolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        binding.extensionWebView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.extensionWebViewTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: url

        val webView = binding.extensionWebView
        webView.setDefaultSettings()
        webView.settings.userAgentString = defaultHeaders["User-Agent"]
        val cookieManager = Injekt.get<NetworkHelper>().cookieJar.manager
        cookieManager?.setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, loadedUrl: String, favicon: Bitmap?) {
                super.onPageStarted(view, loadedUrl, favicon)
                binding.extensionWebViewProgress.isVisible = true
            }

            override fun onPageFinished(view: WebView, loadedUrl: String) {
                super.onPageFinished(view, loadedUrl)
                binding.extensionWebViewProgress.isVisible = false
                binding.extensionWebViewTitle.text = view.title?.takeIf { it.isNotBlank() } ?: loadedUrl
            }
        }
        webView.loadUrl(url)

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        binding.extensionWebViewBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.extensionWebViewReload.setOnClickListener { webView.reload() }
        binding.extensionWebViewOpenExternal.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView.url ?: url)))
            } catch (e: Exception) {
                snackString("Failed to open link: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        binding.extensionWebView.destroy()
        super.onDestroy()
    }
}
