package ani.dantotsu.media.novel.ebook

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream


/**
 * The ebook reader: foliate-js (assets/ebook-reader) in a WebView, the book served to it from a
 * local file server. Forked from VipulOG/ebook-reader 0.1.6, archived upstream, so that the bridge
 * can be fixed and extended here; the app's additions are setExtraStyles, highlightSentence, search and bookmarks.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
class EbookReaderView : WebView {
    private val fileServer: FileServer = FileServer()
    private var listener: EbookReaderEventListener? = null
    private val scope = CoroutineScope(Main)

    private val domain = "appassets.androidplatform.net"
    private val readerUrl = "https://$domain/assets/ebook-reader/reader.html"

    private val navigationStack = ArrayDeque<String>()


    constructor(context: Context) : super(context) {
        init()
    }


    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }


    private fun init() {
        setupWebViewSettings()
    }


    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        fileServer.stop()
    }


    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewSettings() {
        settings.javaScriptEnabled = true
        // Debug builds only: lets the page be inspected from a desktop browser over adb.
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            setWebContentsDebuggingEnabled(true)
        }
        setBackgroundColor(Color.TRANSPARENT)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()

        webViewClient = object : WebViewClientCompat() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val sanitizedUrl = Uri.parse(url.toString().replace("blob:", ""))
                if (!sanitizedUrl.host.equals(domain, ignoreCase = true)) {
                    val intent = Intent(Intent.ACTION_VIEW, url)
                    view.context.startActivity(intent)
                    return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        // The page's errors, in the app's log: without this a script failing in the reader shows
        // only as a book that never finishes loading.
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    ani.dantotsu.util.Logger.log(
                        "Ebook reader: ${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                    )
                }
                return true
            }
        }

        addJavascriptInterface(JavaScriptInterface(), "AndroidInterface")

        setOnLongClickListener {
            val result = hitTestResult
            if (result.type == HitTestResult.IMAGE_TYPE) {
                val imageUrl = result.extra
                processJavascript(
                    "getBlobAsBase64('$imageUrl', (res) => { AndroidInterface.onImageSelected(res) })"
                )
                return@setOnLongClickListener true
            } else {
                return@setOnLongClickListener false
            }
        }
    }


    fun setEbookReaderListener(listener: EbookReaderEventListener?) {
        this.listener = listener
    }


    suspend fun openBook(uri: Uri) {
        val fileName = "book.epub"
        val outputFile = File("${context.cacheDir}/ebookreader", fileName)
        val url = outputFile.toURI().toString()

        if (!outputFile.exists()) outputFile.parentFile?.mkdirs()
        withContext(IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
        }

        // A new page: it has none of the extra styles until they are sent again.
        sentExtraCss = null
        val bookUrl = "http://localhost:${fileServer.listeningPort}/?url=$url"
        val uriBuilder = Uri.parse(readerUrl).buildUpon().appendQueryParameter("url", bookUrl)
        val bookReaderUrl = uriBuilder.build().toString()
        loadUrl(bookReaderUrl)
    }


    fun goto(locator: String) {
        processJavascript("goto('$locator')")
    }


    fun gotoFraction(fraction: Double) {
        processJavascript("gotoFraction($fraction)")
    }


    fun next() {
        processJavascript("next()")
    }


    fun prev() {
        processJavascript("prev()")
    }


    override fun canGoBack(): Boolean {
        return navigationStack.size > 1
    }


    override fun goBack() {
        if (canGoBack()) {
            navigationStack.removeLast()
            val cfi = navigationStack.last()
            processJavascript("goto('$cfi')")
            navigationStack.removeLast()
        }
    }


    fun getAppearance(callback: (ReaderTheme) -> Unit) {
        processJavascript("getAppearance()") {
            callback(Json.decodeFromString(it))
        }
    }


    /** CSS the page was last given through [setExtraStyles]; null until it has had any. */
    private var sentExtraCss: String? = null
    private var extraCss = ""

    /**
     * CSS added after the reader's own in every chapter — the app's font choice. Sent only when it
     * changes, since a font file can travel inside it, and again whenever a book loads into a
     * fresh page (see [JavaScriptInterface.onBookLoaded]).
     */
    fun setExtraStyles(css: String) {
        extraCss = css
        if (css == sentExtraCss) return
        sentExtraCss = css
        processJavascript("setExtraStyles(${org.json.JSONObject.quote(css)})")
    }

    /**
     * Marks the elements matching [selector] in every rendered chapter with [className] and a
     * [colour] background, clearing the previous ones; a null selector only clears.
     */
    fun highlightSentence(selector: String?, className: String, colour: String) {
        val quote = org.json.JSONObject::quote
        processJavascript(
            "highlightSentence(${selector?.let(quote) ?: "null"}, ${quote(className)}, ${quote(colour)})"
        )
    }

    /** Each chapter's search matches as they are found; see [search]. */
    var onSearchResults: ((SearchResults) -> Unit)? = null

    /** The search running has gone through the whole book. */
    var onSearchDone: (() -> Unit)? = null

    /** The font in the extra styles couldn't be loaded by the web view. */
    var onFontFailed: (() -> Unit)? = null

    /** Whether the page on screen has a bookmark, when [setBookmarks] changes the answer. */
    var onBookmarkState: ((Boolean) -> Unit)? = null

    /**
     * Searches the book for [query]: matches come to [onSearchResults] chapter by chapter, and are
     * outlined on the pages, until [onSearchDone]. A new search or [clearSearch] ends the last one.
     */
    fun search(query: String) {
        processJavascript("searchBook(${org.json.JSONObject.quote(query)})")
    }

    /** Ends a search and takes its outlines off the pages. */
    fun clearSearch() {
        processJavascript("clearSearch()")
    }

    /**
     * The positions bookmarked in this book, so each page can say whether it has one
     * ([RelocationInfo.bookmarked]); answers for the page on screen at once, through [onBookmarkState].
     */
    fun setBookmarks(cfis: List<String>) {
        processJavascript("setBookmarks(${org.json.JSONArray(cfis)})")
    }

    /** Which of the bookmarks given to [setBookmarks] are on the page on screen. */
    fun bookmarksOnPage(callback: (List<String>) -> Unit) {
        evaluateJavascript("bookmarksOnPage()") { result ->
            val cfis = runCatching {
                val array = org.json.JSONArray(result)
                List(array.length()) { array.getString(it) }
            }.getOrDefault(emptyList())
            callback(cfis)
        }
    }

    fun setAppearance(theme: ReaderTheme) {
        val json = Json { encodeDefaults = true }
        val themeJson = json.encodeToString(theme)
        processJavascript("setAppearance($themeJson)")
    }


    private fun processJavascript(script: String, callback: ((String) -> Unit)? = null) {
        evaluateJavascript(script) {
            val pattern = "^\"(.*)\"$".toRegex()
            val matchResult = pattern.find(it)
            val out = matchResult?.groupValues?.get(1) ?: it
            callback?.invoke(out)
        }
    }


    private inner class JavaScriptInterface {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        @JavascriptInterface
        fun onBookLoaded(bookJson: String) {
            scope.launch {
                // Whatever was sent before the page was ready went nowhere; it is sent again now.
                sentExtraCss = null
                if (extraCss.isNotEmpty()) setExtraStyles(extraCss)
                // A book whose details can't be read is a failed load with a reason, not an
                // exception thrown at whatever scope this callback runs in.
                val book = runCatching { json.decodeFromString<Book>(bookJson) }.getOrElse {
                    listener?.onBookLoadFailed(ReaderError(message = "Could not read the book's details: ${it.message}"))
                    return@launch
                }
                listener?.onBookLoaded(book)
            }
        }

        @JavascriptInterface
        fun onBookLoadFailed(error: String) {
            scope.launch {
                listener?.onBookLoadFailed(Json.decodeFromString(error))
            }
        }

        @JavascriptInterface
        fun onRelocated(relocationInfoJson: String) {
            scope.launch {
                val relocationInfo: RelocationInfo = json.decodeFromString(relocationInfoJson)
                navigationStack.add(relocationInfo.cfi)
                listener?.onProgressChanged(relocationInfo)
            }
        }

        @JavascriptInterface
        fun onSearchResults(resultsJson: String) {
            scope.launch {
                runCatching { json.decodeFromString<SearchResults>(resultsJson) }
                    .onSuccess { onSearchResults?.invoke(it) }
            }
        }

        @JavascriptInterface
        fun onSearchDone() {
            scope.launch { onSearchDone?.invoke() }
        }

        @JavascriptInterface
        fun onFontFailed() {
            scope.launch { onFontFailed?.invoke() }
        }

        @JavascriptInterface
        fun onBookmarkState(bookmarked: Boolean) {
            scope.launch { onBookmarkState?.invoke(bookmarked) }
        }

        @JavascriptInterface
        fun onImageSelected(imageBase64: String) {
            scope.launch {
                listener?.onImageSelected(imageBase64)
            }
        }

        @JavascriptInterface
        fun onSelectionStart() {
            scope.launch {
                listener?.onTextSelectionModeChange(true)
            }
        }

        @JavascriptInterface
        fun onSelectionEnd() {
            scope.launch {
                listener?.onTextSelectionModeChange(false)
            }
        }
    }
}
