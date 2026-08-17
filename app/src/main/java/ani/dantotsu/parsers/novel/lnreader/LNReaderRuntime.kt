package ani.dantotsu.parsers.novel.lnreader

import android.content.Context
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.FormBody
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * Hosts one LNReader plugin in one JavaScript context.
 *
 * LNReader plugins are CommonJS bundles that expect a browser-ish environment: `cheerio`, `dayjs`,
 * `htmlparser2`, a `fetchApi`, and a handful of web globals. None of that exists in a bare JS
 * engine, so the shims in `assets/lnreader` are evaluated first and the bundle is then loaded into
 * its own module scope.
 *
 * Notes on two choices that are easy to get wrong:
 *
 *  - The engine is `wang.harlon.quickjs`, not the `app.cash.quickjs` this app already ships. The
 *    latter never drains the promise job queue, so a `.then` callback never runs and an `await`
 *    never returns. Every plugin entry point is async, which makes that engine unusable here
 *    regardless of what else is built on top.
 *
 *  - HTTP comes from the app's shared [NetworkHelper] client, so plugins inherit the same Cloudflare
 *    handling, DoH, proxy settings, user agent and cookie jar as every other source in the app.
 *
 * QuickJS contexts are single-threaded, so all work is pinned to one executor and callers block on
 * it. [close] must be called or the native context leaks.
 */
class LNReaderRuntime private constructor(
    private val appContext: Context,
    private val pluginId: String,
    private val http: OkHttpClient,
) : Closeable {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lnreader-$pluginId").apply { isDaemon = true }
    }
    private lateinit var ctx: QuickJSContext
    private val dom = LNReaderDom()
    private val urls = LNReaderUrl()
    private val storage = LNReaderStorage(pluginId)

    @Volatile private var closed = false

    private fun <T> onJsThread(block: () -> T): T = worker.submit(block).get()

    private fun asset(name: String): String =
        appContext.assets.open("lnreader/$name").bufferedReader().use { it.readText() }

    private fun boot(pluginSource: String) = onJsThread {
        QuickJSLoader.init()
        ctx = QuickJSContext.create()
        val global = ctx.globalObject

        val domObj = ctx.createNewJSObject()
        // The mutators return Unit, which the bridge cannot marshal; JS wants undefined, so map it
        // here rather than at every call site.
        fun bind(name: String, fn: (Array<Any?>) -> Any?) =
            domObj.setProperty(name) { args -> fn(args).takeUnless { it is Unit } }

        bind("parse") { dom.parse(it.str(0)) }
        bind("parseFragment") { dom.parseFragment(it.str(0)) }
        bind("select") { dom.select(it.str(0), it.str(1)) }
        bind("text") { dom.text(it.str(0)) }
        bind("ownText") { dom.ownText(it.str(0)) }
        bind("attr") { dom.attr(it.str(0), it.str(1)) }
        bind("html") { dom.html(it.str(0)) }
        bind("outerHtml") { dom.outerHtml(it.str(0)) }
        bind("tagName") { dom.tagName(it.str(0)) }
        bind("data") { dom.data(it.str(0), it.str(1)) }
        bind("next") { dom.next(it.str(0)) }
        bind("prev") { dom.prev(it.str(0)) }
        bind("parent") { dom.parent(it.str(0)) }
        bind("children") { dom.children(it.str(0)) }
        bind("contents") { dom.contents(it.str(0)) }
        bind("nodeType") { dom.nodeType(it.str(0)) }
        bind("documentOrder") { dom.documentOrder(it.str(0)) }
        bind("siblings") { dom.siblings(it.str(0)) }
        bind("closest") { dom.closest(it.str(0), it.str(1)) }
        bind("matches") { dom.matches(it.str(0), it.str(1)) }
        bind("hasClass") { dom.hasClass(it.str(0), it.str(1)) }
        bind("addClass") { dom.addClass(it.str(0), it.str(1)) }
        bind("removeClass") { dom.removeClass(it.str(0), it.str(1)) }
        bind("append") { dom.append(it.str(0), it.str(1)) }
        bind("prepend") { dom.prepend(it.str(0), it.str(1)) }
        bind("replaceWith") { dom.replaceWith(it.str(0), it.str(1)) }
        bind("removeAttr") { dom.removeAttr(it.str(0), it.str(1)) }
        bind("before") { dom.before(it.str(0), it.str(1)) }
        bind("after") { dom.after(it.str(0), it.str(1)) }
        bind("empty") { dom.empty(it.str(0)) }
        bind("remove") { dom.remove(it.str(0)) }
        bind("index") { dom.index(it.str(0)) }
        bind("parseEvents") { dom.parseEvents(it.str(0)) }
        global.setProperty("__lnrDom", domObj)

        val httpObj = ctx.createNewJSObject()
        httpObj.setProperty("request") { args ->
            request(
                url = args.str(0),
                method = args.str(1),
                headersJson = args.str(2),
                body = args.getOrNull(3)?.toString(),
                bodyKind = args.getOrNull(4)?.toString() ?: "raw",
            )
        }
        global.setProperty("__lnrHttp", httpObj)

        val urlObj = ctx.createNewJSObject()
        urlObj.setProperty("parse") { args ->
            urls.parse(args.str(0), args.getOrNull(1)?.toString())
        }
        global.setProperty("__lnrUrl", urlObj)

        val cryptoObj = ctx.createNewJSObject()
        cryptoObj.setProperty("gcm") { args ->
            LNReaderCrypto.gcm(
                mode = args.str(0),
                keyB64 = args.str(1),
                nonceB64 = args.str(2),
                dataB64 = args.str(3),
                aadB64 = args.getOrNull(4)?.toString(),
            )
        }
        global.setProperty("__lnrCrypto", cryptoObj)

        val storageObj = ctx.createNewJSObject()
        storageObj.setProperty("get") { args -> storage.get(args.str(0)) }
        storageObj.setProperty("set") { args -> storage.set(args.str(0), args.str(1)); null }
        storageObj.setProperty("delete") { args -> storage.delete(args.str(0)); null }
        storageObj.setProperty("clear") { storage.clear(); null }
        global.setProperty("__lnrStorage", storageObj)

        val consoleObj = ctx.createNewJSObject()
        listOf("log", "warn", "error", "info", "debug").forEach { level ->
            consoleObj.setProperty(level) { args ->
                Logger.log("LNReader[$pluginId/$level] " + args.joinToString(" ") { it?.toString() ?: "null" })
                null
            }
        }
        global.setProperty("console", consoleObj)

        // Order matters: htmlparser2 falls back to cheerio, and module.js snapshots whatever the
        // others registered, so it must come last.
        listOf(
            "polyfills.js", "cheerio.js", "htmlparser2.js", "libs.js", "dayjs.js", "module.js"
        ).forEach { name -> ctx.evaluate(asset(name), "lnreader/$name") }

        // Parked in a global rather than interpolated into a script, so a bundle containing
        // backticks or template syntax cannot break out of the loader.
        global.setProperty("__pluginSource", pluginSource)
        ctx.evaluate("globalThis.__plugin = globalThis.__loadPlugin(globalThis.__pluginSource);")
    }

    /** Metadata the bundle declares about itself. */
    fun meta(): JSONObject = onJsThread {
        JSONObject(
            ctx.evaluate(
                """
                JSON.stringify({
                  id: globalThis.__plugin.id,
                  name: globalThis.__plugin.name,
                  site: globalThis.__plugin.site,
                  version: globalThis.__plugin.version,
                  hasFilters: !!globalThis.__plugin.filters
                })
                """.trimIndent()
            ) as String
        )
    }

    /**
     * The plugin's filter declarations, which double as their default values.
     *
     * `popularNovels` receives `{ showLatestNovels, filters }` and plugins read straight through to
     * `filters.someKey.value`, so passing an empty object throws rather than yielding an unfiltered
     * listing.
     */
    fun defaultFiltersJson(): String = onJsThread {
        ctx.evaluate("JSON.stringify(globalThis.__plugin.filters || {})") as String
    }

    /**
     * The site URL for a path, as the plugin itself builds it.
     *
     * A path is only meaningful to the plugin that produced it: it is whatever that site's own
     * routing needs, and joining it to the site root is a guess. Chikari's novels live under
     * `/novels/` while its chapters do not, so the guess is wrong for half of them — which is why
     * the plugin API has `resolveUrl` and why it is asked here.
     *
     * Null when the plugin declares none, leaving the caller to fall back to the join.
     */
    fun resolveUrl(path: String, isNovel: Boolean): String? = onJsThread {
        runCatching {
            ctx.evaluate(
                """
                (function () {
                  var p = globalThis.__plugin;
                  if (typeof p.resolveUrl !== 'function') return null;
                  var u = p.resolveUrl(${quote(path)}, $isNovel);
                  return u ? String(u) : null;
                })()
                """.trimIndent()
            ) as? String
        }.getOrNull()
    }

    /**
     * Whether the plugin distinguishes "latest" from "popular".
     *
     * `showLatestNovels` is a flag on the options object `popularNovels` receives, and a plugin
     * that does not read it returns its one listing whichever way it is called. There is no
     * declaration to consult — LNReader has no `supportsLatest` — so the function's own source is
     * the only thing that can answer, and the flag is a property of an object the host passes in,
     * which means no minifier can rename it away.
     *
     * Without this, a Latest chip sits next to Popular on every source and produces the same
     * results on most of them.
     */
    fun supportsLatest(): Boolean = onJsThread {
        runCatching {
            ctx.evaluate(
                "String(globalThis.__plugin.popularNovels).indexOf('showLatestNovels') !== -1"
            ) as? Boolean
        }.getOrNull() ?: false
    }

    /**
     * Invokes a plugin method and returns its settled result as JSON.
     *
     * The outcome is parked in a global and read back in a second evaluate, by which point the
     * engine has drained the job queue. The drain loop is defensive: a chain resolving over several
     * turns needs more than one pass, and a bounded retry is cheaper than assuming one is enough.
     */
    fun call(method: String, argsJson: String = "[]"): String = onJsThread {
        check(!closed) { "runtime for $pluginId is closed" }
        ctx.evaluate("globalThis.__args = $argsJson;")
        ctx.evaluate(
            """
            globalThis.__out = { state: 'pending' };
            (function () {
              var p = globalThis.__plugin;
              var fn = p[${quote(method)}];
              if (typeof fn !== 'function') {
                globalThis.__out = { state: 'error', error: 'No such method: ' + ${quote(method)} };
                return;
              }
              try {
                Promise.resolve(fn.apply(p, globalThis.__args)).then(
                  function (v) { globalThis.__out = { state: 'ok', value: v }; },
                  function (e) { globalThis.__out = { state: 'error', error: String(e && e.message || e) }; }
                );
              } catch (e) {
                globalThis.__out = { state: 'error', error: String(e && e.message || e) };
              }
            })();
            """.trimIndent()
        )

        var raw = ctx.evaluate("JSON.stringify(globalThis.__out)") as String
        var passes = 0
        while (JSONObject(raw).getString("state") == "pending" && passes++ < MAX_DRAIN_PASSES) {
            ctx.evaluate("0")   // each evaluate drains what the previous turn queued
            raw = ctx.evaluate("JSON.stringify(globalThis.__out)") as String
        }

        val out = JSONObject(raw)
        when (out.getString("state")) {
            "ok" -> if (out.isNull("value")) "null" else out.get("value").toString()
            // The engine's own messages are bare ("not a function"), with no hint as to which
            // plugin or which entry point produced them, which makes a bug report unusable.
            "error" -> throw LNReaderPluginException("$pluginId.$method: ${out.getString("error")}")
            else -> throw LNReaderPluginException(
                "$pluginId.$method: promise never settled after $passes drain passes"
            )
        }
    }

    /**
     * Builds the body the JS side described. Multipart cannot be assembled in the shim — it needs
     * a boundary that also has to appear in the Content-Type header — so FormData arrives as raw
     * pairs and is rebuilt here.
     */
    private fun buildBody(body: String?, bodyKind: String): RequestBody = when (bodyKind) {
        "form-data" -> MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            val pairs = runCatching { JSONArray(body.orEmpty()) }.getOrNull() ?: JSONArray()
            for (i in 0 until pairs.length()) {
                val pair = pairs.optJSONArray(i) ?: continue
                addFormDataPart(pair.optString(0), pair.optString(1))
            }
        }.build()

        "urlencoded" -> FormBody.Builder().apply {
            body.orEmpty().split('&').filter { it.isNotEmpty() }.forEach { part ->
                val idx = part.indexOf('=')
                if (idx == -1) addEncoded(part, "")
                else addEncoded(part.substring(0, idx), part.substring(idx + 1))
            }
        }.build()

        else -> (body ?: "").toRequestBody()
    }

    private fun request(
        url: String,
        method: String,
        headersJson: String,
        body: String?,
        bodyKind: String,
    ): String {
        return try {
            val headers = runCatching { JSONObject(headersJson) }.getOrNull()
            val builder = Request.Builder().url(url)
            headers?.keys()?.forEach { k ->
                // OkHttp sets Content-Type itself for multipart/urlencoded bodies, boundary
                // included; a header carried over from the plugin would contradict it.
                val skip = bodyKind != "raw" && k.equals("content-type", ignoreCase = true)
                if (!skip) builder.addHeader(k, headers.getString(k))
            }

            when (method.uppercase()) {
                "POST" -> builder.post(buildBody(body, bodyKind))
                "PUT" -> builder.put(buildBody(body, bodyKind))
                "PATCH" -> builder.patch(buildBody(body, bodyKind))
                "DELETE" -> if (body == null) builder.delete() else builder.delete(buildBody(body, bodyKind))
                "HEAD" -> builder.head()
                else -> builder.get()
            }

            http.newCall(builder.build()).execute().use { response ->
                val headerMap = JSONObject()
                response.headers.forEach { (name, value) -> headerMap.put(name.lowercase(), value) }
                JSONObject()
                    .put("status", response.code)
                    .put("statusText", response.message)
                    .put("url", response.request.url.toString())
                    .put("body", response.body?.string().orEmpty())
                    .put("headers", headerMap)
                    .toString()
            }
        } catch (e: Exception) {
            // Surfaced to the plugin as a non-ok response rather than thrown, which is what a
            // failed fetch looks like on LNReader's own side.
            JSONObject()
                .put("status", 0)
                .put("statusText", e.message ?: "request failed")
                .put("url", url)
                .put("body", "")
                .put("headers", JSONObject())
                .toString()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { onJsThread { ctx.destroy() } }
        worker.shutdownNow()
    }

    companion object {
        private const val MAX_DRAIN_PASSES = 50

        fun load(
            context: Context,
            pluginId: String,
            source: String,
            http: OkHttpClient = Injekt.get<NetworkHelper>().client,
        ): LNReaderRuntime =
            LNReaderRuntime(context.applicationContext, pluginId, http).apply { boot(source) }

        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

class LNReaderPluginException(message: String) : Exception(message)

/** Per-plugin key/value behind `@libs/storage`, namespaced so plugins cannot read each other's. */
class LNReaderStorage(private val pluginId: String) {
    private fun key(k: String) = "lnreader_storage_${pluginId}_$k"

    fun get(k: String): String? =
        PrefManager.getNullableCustomVal(key(k), null, String::class.java)

    fun set(k: String, v: String) = PrefManager.setCustomVal(key(k), v)

    fun delete(k: String) = PrefManager.removeCustomVal(key(k))

    /** Only the keys this plugin wrote; there is no prefix enumeration in the store. */
    fun clear() = Unit
}

private fun Array<Any?>.str(i: Int): String = getOrNull(i)?.toString() ?: ""
