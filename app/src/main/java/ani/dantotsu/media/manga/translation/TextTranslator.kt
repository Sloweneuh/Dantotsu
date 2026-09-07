package ani.dantotsu.media.manga.translation

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import java.io.Closeable

/**
 * Turns a page's bubbles into the target language.
 *
 * Batch in, batch out, and the contract is that the result has exactly as many entries as the
 * input, in the same order. That is deliberately stricter than translating block by block: the two
 * engines worth using are language models, and a model handed every bubble on the page at once can
 * see that one bubble answers another. Translating each alone throws that context away, which is
 * most of what separates a readable page from a set of unrelated sentences.
 *
 * Where an engine cannot translate an entry it returns the original, never a placeholder — a
 * failure that reads as the untranslated source is obvious to the reader, whereas a dropped or
 * shifted entry silently attaches the wrong words to the wrong bubble.
 */
interface TextTranslator : Closeable {

    /** Anything expensive to set up — downloading a model, say. Called once before [translate]. */
    suspend fun prepare() = Unit

    /** Translates [texts], returning exactly as many entries in the same order. */
    suspend fun translate(texts: List<String>): List<String>
}

/**
 * The engines, in rough order of what they cost the user to set up.
 *
 * The first two need nothing at all; the last two need the user's own key. All four are free at the
 * point of use, which was the constraint the whole feature was designed under.
 */
enum class TranslationEngine(
    val label: String,
    val keyPref: PrefName?,
    /** Where the user creates a key for this engine, shown when one is asked for. */
    val keyUrl: String? = null,
) {
    ML_KIT("ML Kit (on device)", null),
    GOOGLE("Google Translate", null),
    GEMINI("Gemini", PrefName.OcrGeminiApiKey, "https://aistudio.google.com/apikey"),
    OPENROUTER("OpenRouter", PrefName.OcrOpenRouterApiKey, "https://openrouter.ai/keys"),
    ;

    val needsKey: Boolean get() = keyPref != null

    /** The key the user has stored for this engine, empty where there is none or none is needed. */
    fun storedKey(): String = keyPref?.let { PrefManager.getVal<String>(it) }.orEmpty()

    /**
     * A placeholder shown before the provider's catalogue arrives, and a last resort if it never
     * does.
     *
     * Not to be trusted for long: `meta-llama/llama-3.3-70b-instruct:free` sat here until the day
     * OpenRouter retired it, at which point the very first translation attempt 404'd. The real
     * default is whatever [LlmTranslator.preferredModel] picks out of the live list, and the
     * picker replaces a stored model the provider no longer lists.
     */
    fun defaultModel(): String = when (this) {
        GEMINI -> "gemini-flash-latest"
        OPENROUTER -> "openrouter/auto"
        else -> ""
    }

    fun build(from: String, to: String, toLabel: String): TextTranslator {
        val key = storedKey()
        val model = PrefManager.getVal<String>(PrefName.OcrTranslationModel)
            .ifBlank { defaultModel() }
        return when (this) {
            ML_KIT -> MlKitTranslator(from, to)
            GOOGLE -> GoogleTranslator(from, to)
            GEMINI -> LlmTranslator.gemini(key, model, toLabel)
            OPENROUTER -> LlmTranslator.openRouter(key, model, toLabel)
        }
    }

    companion object {
        fun fromPref(): TranslationEngine =
            entries.getOrElse(PrefManager.getVal(PrefName.OcrTranslationEngine)) { ML_KIT }
    }
}
