package ani.dantotsu.spike

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder

/**
 * Google Translate through the endpoint its own web widget uses.
 *
 * Free and keyless, and markedly better than ML Kit on Japanese, but undocumented and unofficial:
 * Google can change or rate-limit it without notice, and heavy use from one address will be
 * throttled. That is worth stating plainly rather than burying — it is the only engine here that
 * could stop working without anything in the app changing.
 *
 * Requests go out [CONCURRENCY] at a time rather than one after another. There is no batch form of
 * this endpoint that survives contact with reality: the obvious trick of joining bubbles with a
 * separator and splitting the result assumes the translator preserves the separator, which it does
 * not reliably do, and a lost separator silently shifts every later bubble onto the wrong box.
 * Concurrency gets the latency back without betting correctness on that.
 *
 * Like ML Kit, this sees one bubble at a time and so cannot use the rest of the page as context.
 */
class GoogleTranslator(
    private val fromLanguage: String,
    private val toLanguage: String,
) : TextTranslator {

    private val client = OkHttpClient()

    override suspend fun translate(texts: List<String>): List<String> = coroutineScope {
        val gate = Semaphore(CONCURRENCY)
        texts.map { text ->
            async(Dispatchers.IO) {
                gate.withPermit { runCatching { fetch(text) }.getOrDefault(text) }
            }
        }.awaitAll()
    }

    private suspend fun fetch(text: String): String = withContext(Dispatchers.IO) {
        val url = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=$fromLanguage&tl=$toLanguage&dt=t&q=" +
            URLEncoder.encode(text, "UTF-8")
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        val body = response.use { it.body?.string() }.orEmpty()
        parse(body).ifBlank { text }
    }

    /**
     * Pulls the translation out of the endpoint's nested-array reply.
     *
     * The shape is `[[[translated, source, ...], ...], ...]`, and the first array holds one entry
     * per *sentence* rather than one per request, so a two-sentence bubble arrives in two pieces
     * that have to be put back together. Reading only the first entry — which is the obvious
     * mistake — silently truncates every bubble containing a full stop.
     */
    private fun parse(body: String): String {
        if (body.isBlank()) return ""
        val sentences = JSONArray(body).optJSONArray(0) ?: return ""
        return buildString {
            for (i in 0 until sentences.length()) {
                append(sentences.optJSONArray(i)?.optString(0).orEmpty())
            }
        }
    }

    override fun close() = Unit

    private companion object {
        /** In-flight requests. Enough to hide latency, few enough not to invite throttling. */
        const val CONCURRENCY = 4
    }
}
