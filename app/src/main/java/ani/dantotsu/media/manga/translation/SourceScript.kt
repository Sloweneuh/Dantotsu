package ani.dantotsu.media.manga.translation

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import java.util.Locale

/**
 * Works out which recognizer a page should be read with.
 *
 * Nobody is going to choose a script per chapter, so this has to be decided rather than asked. Two
 * pieces of metadata are available and they answer different questions, which is the whole of the
 * design here:
 *
 *  - **`countryOfOrigin`** says what script the work was *written* in. That is exactly the question
 *    a recognizer choice asks, and `JP` / `KR` / `CN` are precisely the manga / manhwa / manhua
 *    split, so it decides [forCountry].
 *  - **the extension source's language** says what language the *scans being read* are in, which is
 *    a different thing — an English scanlation of a Japanese series is `countryOfOrigin = JP` with
 *    Latin pages. That belongs to [pointless], which answers whether translating is worth doing at
 *    all rather than which recognizer to use.
 *
 * A wrong choice is also cheaper than it looks: ML Kit's models are "Japanese **and Latin**",
 * "Korean and Latin", "Chinese and Latin", so every CJK recognizer reads Latin too. The only
 * expensive mistake is confusing the three CJK scripts with each other, and that is the one
 * distinction `countryOfOrigin` draws cleanly.
 */
object SourceScript {

    /** The user's explicit choice, or null where they have left it automatic. */
    fun override(): TextScript? =
        TextScript.entries.getOrNull(PrefManager.getVal(PrefName.OcrSourceScript))

    /**
     * The script to read a page of this media with.
     *
     * Falls back to Japanese when there is nothing to go on — extension-only media with no AniList
     * match — because it is far and away the most common case, and because being wrong about it
     * still reads any Latin text on the page correctly.
     */
    fun resolve(countryOfOrigin: String?): TextScript =
        override() ?: forCountry(countryOfOrigin) ?: TextScript.JAPANESE

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
        val source = sourceLanguage?.lowercase(Locale.ROOT)?.substringBefore('-') ?: return false
        // "all" and "other" are catch-alls used by multi-language sources and say nothing.
        if (source.isBlank() || source == "all" || source == "other") return false
        return source == target.lowercase(Locale.ROOT).substringBefore('-')
    }

    /** The language to translate into: the stored choice, or the device's own. */
    fun targetLanguage(): String =
        PrefManager.getVal<String>(PrefName.OcrTargetLanguage)
            .ifBlank { Locale.getDefault().language }
}
