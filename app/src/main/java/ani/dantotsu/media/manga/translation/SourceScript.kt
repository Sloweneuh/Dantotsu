package ani.dantotsu.media.manga.translation

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import java.util.Locale

/**
 * Works out which recognizer a page should be read with.
 *
 * Nobody is going to choose a script per chapter, so this has to be decided rather than asked. Two
 * pieces of metadata are available and they are asked in this order:
 *
 *  - **the extension source's language** describes the *scans being read*, which is the question a
 *    recognizer choice actually asks. It is authoritative when it says anything at all.
 *  - **`countryOfOrigin`** says what script the work was *written* in, which is only a proxy for
 *    what is printed on the pages in front of the reader. It answers for a source that names no
 *    language of its own.
 *
 * The order used to be the other way round, and got a Japanese longstrip read as Japanese while the
 * source was serving Korean scans of it — `countryOfOrigin = JP`, pages in hangul, nothing
 * recognised. Origin cannot see a translation; the source's language is the translation.
 *
 * A wrong choice is also cheaper than it looks: ML Kit's models are "Japanese **and Latin**",
 * "Korean and Latin", "Chinese and Latin", so every CJK recognizer reads Latin too. The only
 * expensive mistake is confusing the three CJK scripts with each other, and both signals draw that
 * distinction cleanly.
 */
object SourceScript {

    /** The user's explicit choice, or null where they have left it automatic. */
    fun override(): TextScript? =
        TextScript.entries.getOrNull(PrefManager.getVal(PrefName.OcrSourceScript))

    /**
     * The script to read a page of this media with.
     *
     * @param override the reader's explicit choice for this media, or null to decide from the
     *   metadata — [MtlSettings.script], which is where that choice lives.
     * @param sourceLanguage the extension source's language code, as `ja` / `ko` / `en`.
     * @param countryOfOrigin the work's origin, as AniList reports it.
     */
    fun resolve(override: TextScript?, sourceLanguage: String?, countryOfOrigin: String?): TextScript =
        override ?: detect(sourceLanguage, countryOfOrigin)

    /**
     * What the metadata says, ignoring any override — which is what the settings rows show, so a
     * reader on automatic can see what automatic decided rather than only that it is automatic.
     *
     * Falls back to Japanese when there is nothing to go on — an extension-only media on a
     * multi-language source — because it is far and away the most common case, and because being
     * wrong about it still reads any Latin text on the page correctly.
     */
    fun detect(sourceLanguage: String?, countryOfOrigin: String?): TextScript =
        forLanguage(sourceLanguage) ?: forCountry(countryOfOrigin) ?: TextScript.JAPANESE

    /**
     * The recognizer for a source's language code.
     *
     * Anything that is not one of the three CJK languages is read as Latin. That is a real claim
     * about the wrong cases as well as the right ones: for Cyrillic or Arabic scans no ML Kit
     * recognizer would work at all, so Latin costs nothing there, while for the Latin-script
     * languages a source actually serves — Spanish, French, Portuguese, Indonesian — it is right.
     */
    fun forLanguage(language: String?): TextScript? = when (normalise(language)) {
        null -> null
        "ja" -> TextScript.JAPANESE
        "ko" -> TextScript.KOREAN
        "zh" -> TextScript.CHINESE
        else -> TextScript.LATIN
    }

    fun forCountry(countryOfOrigin: String?): TextScript? =
        when (countryOfOrigin?.uppercase(Locale.ROOT)) {
            "JP" -> TextScript.JAPANESE
            "KR" -> TextScript.KOREAN
            "CN", "TW", "HK" -> TextScript.CHINESE
            else -> null
        }

    /**
     * Whether translating is pointless because the pages are already in the target language.
     *
     * A source serving English scans to someone reading in English should not be OCR'd and
     * translated into what it already is. Answered by the *source's* language rather than the
     * work's origin, since that is what describes the pages in front of the reader.
     */
    fun pointless(sourceLanguage: String?, target: String): Boolean {
        val source = normalise(sourceLanguage) ?: return false
        return source == normalise(target)
    }

    /**
     * A language code reduced to its language, or null where it says nothing.
     *
     * "all" and "other" are the catch-alls multi-language sources use, and a source tagged with
     * either is making no claim about what is on the page.
     */
    private fun normalise(language: String?): String? {
        val code = language?.lowercase(Locale.ROOT)?.trim()
            ?.substringBefore('-')?.substringBefore('_') ?: return null
        if (code.isBlank() || code == "all" || code == "other") return null
        return code
    }

    /** The language to translate into: the stored choice, or the device's own. */
    fun targetLanguage(): String =
        PrefManager.getVal<String>(PrefName.OcrTargetLanguage)
            .ifBlank { Locale.getDefault().language }
}
