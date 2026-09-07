package ani.dantotsu.media.manga.translation

import android.graphics.Bitmap
import android.util.LruCache
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/** A page's finished translation, ready to draw. */
data class TranslatedPage(
    val blocks: List<PaintedBlock>,
    val pageWidth: Int,
    val pageHeight: Int,
)

/**
 * What has already been translated, keyed by page url.
 *
 * The url is the right key and it makes the multi-chapter reader a non-problem: a page is uniquely
 * identified by where it came from regardless of which chapter it belongs to, so nothing here needs
 * to know about chapters, boundaries, or which of them are currently loaded. It is the same key
 * [ani.dantotsu.media.manga.MangaCache] uses for the page's pixels.
 *
 * Caching is not an optimisation here. Language models do not return the same words twice — the
 * same bubble translated again comes back differently worded — so re-requesting on a re-read would
 * make a chapter say something slightly different each time through. Keeping the first answer is
 * what makes it read consistently, and it happens to save the quota as well.
 */
object TranslatedPages {

    /** Pages held before the oldest is dropped. Text and rectangles, so this is small. */
    private const val CAPACITY = 60

    private val cache = LruCache<String, TranslatedPage>(CAPACITY)

    operator fun get(pageUrl: String): TranslatedPage? = cache.get(pageUrl)

    fun put(pageUrl: String, page: TranslatedPage) {
        cache.put(pageUrl, page)
    }

    fun remove(pageUrl: String) {
        cache.remove(pageUrl)
    }

    fun clear() = cache.evictAll()
}

/**
 * Runs a page all the way from pixels to blocks ready to paint.
 *
 * The single entry point the reader needs; everything it strings together — detection, scoring,
 * translation, layout — is the same code the calibration screen exercises, on the same
 * preferences.
 */
object PageTranslationPipeline {

    /**
     * @param script  which recognizer to use, from [SourceScript.resolve].
     * @return null when the page has nothing worth translating, so the caller can say so rather
     *         than showing an empty overlay.
     */
    suspend fun run(page: Bitmap, script: TextScript): TranslatedPage? {
        val detector = PageTextDetector(script)
        val runs = detector.read(page)
        val blocks = detector.blocks(runs, merge = true)
        if (blocks.isEmpty()) return null

        val scored = BlockScorer.score(blocks, page, DetectionThresholds.fromPrefs())
        val wanted = scored.filter { it.verdict.translatable }
        if (wanted.isEmpty()) return null

        val engine = TranslationEngine.fromPref()
        val from = MlKitTranslator.translationCodeFor(script.name)
            ?: error("no translation code for $script")
        val to = SourceScript.targetLanguage()
        val translations = engine.build(from, to, targetLabel(to)).use { translator ->
            translator.prepare()
            // One call for the whole page. The batch is what lets a language model see the bubbles
            // as a conversation rather than as unrelated fragments.
            translator.translate(wanted.map { it.block.sourceText(script) })
        }

        val byId = wanted.mapIndexed { index, entry ->
            entry.block.id to translations.getOrElse(index) { entry.block.sourceText(script) }
        }.toMap()
        val finished = scored.map { entry ->
            byId[entry.block.id]?.let { entry.copy(block = entry.block.copy(translation = it)) }
                ?: entry
        }
        return TranslatedPage(
            blocks = TranslationLayout.paint(finished, page.width, page.height),
            pageWidth = page.width,
            pageHeight = page.height,
        )
    }

    /** The language named in words, which is what the model-based engines put in their prompt. */
    private fun targetLabel(code: String): String =
        java.util.Locale.forLanguageTag(code).getDisplayName(java.util.Locale.ENGLISH)

    /**
     * The block's text as one run, for the translator.
     *
     * [TextBlock.text] carries " / " between lines so a report stays on one line, which would reach
     * the translator as punctuation inside the sentence. A bubble is one sentence broken wherever
     * its columns ended, so the separators come back out — with nothing between them for the CJK
     * scripts, which do not space their words, and a space for Latin, which does.
     */
    fun TextBlock.sourceText(script: TextScript): String =
        text.replace(" / ", if (script == TextScript.LATIN) " " else "")

    /** Whether an engine that needs a key has one, so the caller can explain rather than fail. */
    fun ready(): Boolean {
        val engine = TranslationEngine.fromPref()
        return !engine.needsKey || engine.storedKey().isNotBlank()
    }

    /** Whether the reader should offer this at all. */
    fun enabled(): Boolean = PrefManager.getVal(PrefName.OcrTranslateEnabled)
}
