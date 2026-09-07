package ani.dantotsu.media.manga.translation

import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini and OpenRouter, which differ only in where the request goes and how it is authorised.
 *
 * These are the two engines that can actually read a page. The whole page's bubbles go up in one
 * request as a numbered object, so the model can see that bubble 3 answers bubble 2 and translate
 * them as a conversation — which is the single largest quality difference available here, and
 * something neither ML Kit nor the Google endpoint can do at any price.
 *
 * The reply is keyed by the **same indices that were sent**, and every one is checked on the way
 * back. TachiyomiAT posts a whole chapter and re-attaches results by array position, so a model
 * that drops or merges one entry shifts every later bubble onto the wrong box — and produces a
 * page that looks translated while being quietly wrong throughout. Keys make a dropped entry
 * detectable, and a missing key falls back to the source text, which reads as untranslated rather
 * than as somebody else's line.
 *
 * Both are used with the user's own key on a free tier. Nothing here is billed by the project.
 */
class LlmTranslator private constructor(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    private val targetLabel: String,
    private val gemini: Boolean,
) : TextTranslator {

    private val client = OkHttpClient.Builder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Failures throw rather than returning the input.
     *
     * Returning the source on error was the first version and it was a bad idea: an untranslated
     * page is exactly what a *badly* translated page looks like from the outside, so a wrong key,
     * a dead model name and a filtered response were all indistinguishable from the engine simply
     * being poor. The caller shows what went wrong instead. Only a missing key for one entry falls
     * back to that entry's source, which is a different thing — the request worked, the model just
     * skipped a bubble.
     */
    override suspend fun translate(texts: List<String>): List<String> {
        if (texts.isEmpty()) return texts
        check(apiKey.isNotBlank()) { "No API key set" }
        return withContext(Dispatchers.IO) {
            val request = JSONObject().apply {
                texts.forEachIndexed { index, text -> put(index.toString(), text) }
            }
            val answer = parseContent(post(body(request.toString())))
            texts.mapIndexed { index, original ->
                answer.stringOrNull(index.toString()) ?: original
            }
        }
    }

    private fun body(payload: String): String = if (gemini) {
        JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(part(prompt()))))
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(part(payload)))))
            put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("temperature", TEMPERATURE),
            )
            // Manga dialogue trips the default filters routinely — violence and language in a
            // fight scene are the subject matter, not an abuse of the model — and a blocked
            // response comes back as an empty candidate, i.e. a silently untranslated page.
            put("safetySettings", safetySettings())
        }.toString()
    } else {
        JSONObject().apply {
            put("model", model)
            put("temperature", TEMPERATURE)
            put("response_format", JSONObject().put("type", "json_object"))
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", prompt()))
                    .put(JSONObject().put("role", "user").put("content", payload)),
            )
        }.toString()
    }

    private fun part(text: String) = JSONObject().put("text", text)

    private fun safetySettings() = JSONArray().apply {
        listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
        ).forEach {
            put(JSONObject().put("category", it).put("threshold", "BLOCK_NONE"))
        }
    }

    private fun prompt() = """
        You translate comic pages. The user sends a JSON object whose keys are numbers and whose
        values are the text of the speech bubbles on one page, in reading order.

        Translate every value into $targetLabel. Reply with a JSON object using exactly the same
        keys, and nothing else — no commentary, no markdown fence.

        The bubbles are one page of a conversation, so read them together: pronouns, politeness and
        implied subjects should follow from the surrounding bubbles rather than being guessed at per
        bubble. Keep the register of the original — casual speech stays casual. Where a value is a
        watermark or a site name rather than dialogue, return it unchanged.

        Some values come from imperfect character recognition and may be garbled or truncated. If a
        value cannot be read as meaningful text, return it unchanged rather than inventing a
        plausible line for it.
    """.trimIndent()

    private fun post(payload: String): String {
        val builder = Request.Builder()
            .url(if (gemini) "$endpoint/$model:generateContent?key=$apiKey" else endpoint)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (!gemini) builder.header("Authorization", "Bearer $apiKey")

        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Logger.log("OCR spike LLM HTTP ${response.code}: $body")
                // The provider's own message, not a generic failure: a dead model name and a
                // rejected key are both 4xx and need completely different fixes.
                val detail = "HTTP ${response.code}: ${providerMessage(body) ?: body.take(200)}"
                // A 404 is the provider saying this particular model is not available to this key
                // — retired, batch-only, or never existed. Distinguished by type so the caller can
                // drop the stored choice rather than leaving it to fail identically forever.
                if (response.code == HTTP_NOT_FOUND) throw ModelUnavailable(model, detail)
                error(detail)
            }
            return body
        }
    }

    /** Digs the model's JSON answer out of whichever envelope the provider wrapped it in. */
    private fun parseContent(response: String): JSONObject {
        check(response.isNotBlank()) { "Empty response" }
        val root = JSONObject(response)
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val content = if (gemini) {
            root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.stringOrNull("text")
        } else {
            choice?.optJSONObject("message")?.let {
                // Reasoning models on OpenRouter routinely put everything in `reasoning` and leave
                // `content` null, so the answer is there rather than missing.
                it.stringOrNull("content") ?: it.stringOrNull("reasoning")
            }
        }
        if (content == null) {
            Logger.log("OCR spike LLM gave no content: ${response.take(1000)}")
            // A 200 with no content is nearly always a safety block, an exhausted token budget, or
            // a router that found nothing to route to — and the reason is in the envelope rather
            // than in an error field.
            val reason = root.optJSONArray("candidates")?.optJSONObject(0)?.stringOrNull("finishReason")
                ?: choice?.stringOrNull("finish_reason")
                ?: choice?.stringOrNull("native_finish_reason")
            error(
                when {
                    reason != null -> "No text returned (finish reason: $reason)"
                    providerMessage(response) != null -> providerMessage(response)!!
                    else -> "No text in response"
                },
            )
        }
        // Models still fence their JSON now and then despite being asked not to.
        val cleaned = content.trim().removeSurrounding("```json", "```").trim(' ', '`', '\n')
        return runCatching { JSONObject(cleaned) }.getOrElse {
            Logger.log("OCR spike LLM sent non-JSON: ${cleaned.take(1000)}")
            error("Model did not return JSON: ${cleaned.take(120)}")
        }
    }

    /** The `error.message` both providers use, when there is one. */
    private fun providerMessage(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.stringOrNull("message")
    }.getOrNull()

    override fun close() = Unit

    /**
     * A string field, treating a JSON `null` as absent.
     *
     * `optString` does not: given a JSON `null` it hands back the four-letter string "null",
     * because it stringifies the null sentinel rather than recognising it. That turned a model
     * replying with a null `content` into a "successful" parse of the text `null`, which sailed
     * past an `isNullOrBlank` check and reported itself as "did not return JSON" — while the
     * branch that would have logged the actual response never ran. Worse, the same call reads each
     * translated bubble, so a null value for one bubble would have been painted onto the page as
     * the word "null".
     */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    /** The chosen model is not usable with this key, whatever the catalogue said. */
    class ModelUnavailable(val model: String, message: String) : IllegalStateException(message)

    companion object {
        private const val HTTP_NOT_FOUND = 404
        private const val TEMPERATURE = 0.3
        private const val TIMEOUT_SECONDS = 90L

        /**
         * Asks the provider whether a key is real, returning null when it is or the reason it is
         * not.
         *
         * The cheapest authenticated call each provider offers, chosen so that checking costs the
         * user nothing: Gemini's model list rejects a bad key outright, and OpenRouter has an
         * endpoint whose entire job is describing the key you present. Neither runs a completion,
         * so no quota is spent finding out that a paste went wrong.
         */
        suspend fun verifyKey(engine: TranslationEngine, apiKey: String): String? =
            withContext(Dispatchers.IO) {
                val request = when (engine) {
                    TranslationEngine.GEMINI -> Request.Builder()
                        .url(
                            "https://generativelanguage.googleapis.com/v1beta/models" +
                                "?key=$apiKey&pageSize=1",
                        )
                        .build()

                    TranslationEngine.OPENROUTER -> Request.Builder()
                        .url("https://openrouter.ai/api/v1/key")
                        .header("Authorization", "Bearer $apiKey")
                        .build()

                    else -> return@withContext null
                }
                runCatching {
                    OkHttpClient().newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        when {
                            response.isSuccessful -> null
                            else -> {
                                Logger.log("OCR spike key check ${response.code}: $body")
                                // The provider's own wording: "API key not valid" and "quota
                                // exceeded" are both rejections but only one means a bad paste.
                                runCatching {
                                    JSONObject(body).optJSONObject("error")?.optString("message")
                                }.getOrNull()?.takeIf { it.isNotBlank() }
                                    ?: "HTTP ${response.code}"
                            }
                        }
                    }
                }.getOrElse { it.message ?: it.toString() }
            }

        /**
         * Asks the provider what models it has.
         *
         * Fetched rather than hardcoded because a baked-in list is wrong the moment a provider
         * retires a name, and the symptom of a retired name is a 404 that looks to the user like
         * the feature being broken. Gemini needs the key to answer; OpenRouter's catalogue is
         * public.
         */
        suspend fun fetchModels(engine: TranslationEngine, apiKey: String): List<String> =
            withContext(Dispatchers.IO) {
                val url = when (engine) {
                    TranslationEngine.GEMINI ->
                        "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey" +
                            "&pageSize=200"

                    TranslationEngine.OPENROUTER -> "https://openrouter.ai/api/v1/models"
                    else -> return@withContext emptyList()
                }
                val body = OkHttpClient().newCall(Request.Builder().url(url).build())
                    .execute()
                    .use { response ->
                        val text = response.body?.string().orEmpty()
                        if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(200)}")
                        text
                    }
                if (engine == TranslationEngine.GEMINI) geminiModels(body) else openRouterModels(body)
            }

        private fun geminiModels(body: String): List<String> {
            val models = JSONObject(body).optJSONArray("models") ?: return emptyList()
            return (0 until models.length()).mapNotNull { i ->
                val model = models.optJSONObject(i) ?: return@mapNotNull null
                // Embedding and vision-only models are in the same list and cannot answer this.
                val methods = model.optJSONArray("supportedGenerationMethods")
                val usable = (0 until (methods?.length() ?: 0))
                    .any { methods?.optString(it) == "generateContent" }
                if (!usable) null else model.optString("name").removePrefix("models/")
            }.filter { it.isNotBlank() }.sortedWith(NEWEST_FIRST)
        }

        /**
         * Newest first, which for Gemini is the difference between a working model and a 404.
         *
         * Google keeps retired models in the catalogue and only refuses them at call time — "no
         * longer available to new users" — so the list cannot be trusted to contain only usable
         * entries, and picking the alphabetically first `flash` reliably picked the *oldest* one.
         *
         * Version numbers are compared as numbers rather than as text, or a hypothetical 10.0
         * would sort below 9.0. The `-latest` aliases come first of all: Google repoints them at
         * the current model, so they are the one name here that cannot go stale.
         */
        private val NEWEST_FIRST = compareByDescending<String> { it.endsWith("-latest") }
            .thenByDescending { versionOf(it) }
            .thenBy { it }

        private fun versionOf(id: String): Double =
            Regex("""\d+(?:\.\d+)?""").find(id)?.value?.toDoubleOrNull() ?: 0.0

        private fun openRouterModels(body: String): List<String> {
            val models = JSONObject(body).optJSONArray("data") ?: return emptyList()
            return (0 until models.length())
                .mapNotNull { models.optJSONObject(it)?.optString("id") }
                .filter { it.isNotBlank() }
                // Batch-only variants are in the same catalogue but refuse chat completions with
                // "This model is only available through the Batch API" — a 404 that arrives long
                // after the choice was made. OpenRouter marks them with a suffix, the way it marks
                // free tiers, and at the time of writing 69 of 430 ids carry it.
                .filterNot { it.endsWith(BATCH_SUFFIX) }
                // Free tiers first: they are the reason this engine is offered at all.
                .sortedWith(compareByDescending<String> { it.endsWith(FREE_SUFFIX) }.thenBy { it })
        }

        /**
         * The model to fall back on when nothing is stored and the catalogue is not loaded yet.
         *
         * Prefers a free model, because that is the point of offering OpenRouter at all, and
         * otherwise the first thing on offer. Derived from the fetched list rather than named in
         * code: a hardcoded default rots exactly like a hardcoded list, and the symptom is a 404
         * on the very first attempt.
         */
        fun preferredModel(models: List<String>): String? =
            models.firstOrNull { it.endsWith(FREE_SUFFIX) }
                // Gemini has no free suffix to look for, and its list sorts alphabetically into
                // whatever legacy model happens to come first. A flash model is the right shape
                // for this job anyway: short prompts, one call per page, latency the user waits on.
                ?: models.firstOrNull { it.contains("flash") }
                ?: models.firstOrNull()

        private const val BATCH_SUFFIX = ":batch"
        private const val FREE_SUFFIX = ":free"

        fun gemini(apiKey: String, model: String, targetLabel: String) = LlmTranslator(
            endpoint = "https://generativelanguage.googleapis.com/v1beta/models",
            apiKey = apiKey,
            model = model,
            targetLabel = targetLabel,
            gemini = true,
        )

        fun openRouter(apiKey: String, model: String, targetLabel: String) = LlmTranslator(
            endpoint = "https://openrouter.ai/api/v1/chat/completions",
            apiKey = apiKey,
            model = model,
            targetLabel = targetLabel,
            gemini = false,
        )
    }
}
