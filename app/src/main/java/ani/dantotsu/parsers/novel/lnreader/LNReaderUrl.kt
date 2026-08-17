package ani.dantotsu.parsers.novel.lnreader

import org.json.JSONObject
import java.net.URI

/**
 * URL resolution for the `URL` polyfill.
 *
 * QuickJS has no WHATWG `URL`, and plugins use `new URL(relativePath, plugin.site)` to turn the
 * paths they scrape into absolute ones. Relative-reference resolution has enough edge cases
 * (`..` segments, protocol-relative `//host`, query-only refs) that reimplementing it in the shim
 * would be its own bug source, so it is delegated to the JDK.
 */
class LNReaderUrl {

    /** Returns the component bag the JS side exposes, or an `error` key it can throw on. */
    fun parse(input: String, base: String?): String = try {
        val resolved = when {
            base.isNullOrBlank() -> URI(input)
            else -> URI(base).resolve(input)
        }.normalize()

        val port = if (resolved.port == -1) "" else resolved.port.toString()
        val host = resolved.host.orEmpty()
        val origin =
            if (resolved.scheme != null && host.isNotEmpty()) {
                buildString {
                    append(resolved.scheme).append("://").append(host)
                    if (port.isNotEmpty()) append(':').append(port)
                }
            } else ""

        JSONObject()
            .put("href", resolved.toString())
            .put("protocol", resolved.scheme?.let { "$it:" } ?: "")
            .put("hostname", host)
            .put("port", port)
            .put("host", if (port.isEmpty()) host else "$host:$port")
            .put("pathname", resolved.rawPath.ifEmpty { "/" })
            .put("search", resolved.rawQuery?.let { "?$it" } ?: "")
            .put("hash", resolved.rawFragment?.let { "#$it" } ?: "")
            .put("origin", origin)
            .toString()
    } catch (e: Exception) {
        JSONObject().put("error", e.message ?: "invalid url").toString()
    }
}
