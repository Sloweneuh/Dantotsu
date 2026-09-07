package ani.dantotsu.spike

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/**
 * ML Kit's on-device translator: free, offline, no key, and the weakest of the four.
 *
 * It earns its place by needing nothing — no account, no network once its models are down — which
 * made it the right engine to prove the pipeline with before any of the others existed. It is not
 * the one to judge the feature by. Google documents it as suitable for casual translation, it
 * pivots non-English pairs through English, and it is the only engine here that cannot see one
 * bubble while translating another, because it has no notion of context at all: [translate] is a
 * loop, and no amount of batching changes that.
 *
 * Models are roughly 30 MB per language, fetched once and then cached by ML Kit across launches.
 */
class MlKitTranslator(
    private val fromLanguage: String,
    private val toLanguage: String,
) : TextTranslator {

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(fromLanguage)
            .setTargetLanguage(toLanguage)
            .build(),
    )

    override suspend fun prepare() {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    override suspend fun translate(texts: List<String>): List<String> =
        texts.map { translator.translate(it).await() }

    override fun close() = translator.close()

    companion object {
        /**
         * Every language ML Kit can translate, as code-to-display-name, ordered for a picker.
         *
         * Read from the library rather than hardcoded, so the list cannot drift from what the
         * installed version actually supports. The other engines all cover a superset of these, so
         * this doubles as the target list for the whole feature.
         */
        fun supportedTargets(): List<Pair<String, String>> =
            TranslateLanguage.getAllLanguages()
                .map { it to Locale.forLanguageTag(it).getDisplayName(Locale.getDefault()) }
                .sortedBy { it.second.lowercase(Locale.getDefault()) }

        /**
         * The translation code for a recognizer script, or null where there is no match.
         *
         * Chinese is the interesting one: the recognizer reads both Han scripts, while the
         * translator offers only `zh`, so a traditional-script page translates as simplified.
         */
        fun translationCodeFor(script: String): String? = when (script) {
            "JAPANESE" -> TranslateLanguage.JAPANESE
            "KOREAN" -> TranslateLanguage.KOREAN
            "CHINESE" -> TranslateLanguage.CHINESE
            "LATIN" -> TranslateLanguage.ENGLISH
            else -> null
        }
    }
}
