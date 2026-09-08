package ani.dantotsu.media.manga.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.LruCache
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

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
     * @param script    which recognizer to use, from [SourceScript.resolve].
     * @param sourceLanguage the extension source's language code, which decides what the text is
     *                  being translated *from* when the pages are in a Latin-script language.
     * @param before    the page above this one, and [after] the page below, where the reader has
     *                  them decoded. See [Seam].
     * @return null when the page has nothing worth translating, so the caller can say so rather
     *         than showing an empty overlay.
     */
    suspend fun run(
        page: Bitmap,
        script: TextScript,
        sourceLanguage: String? = null,
        before: Bitmap? = null,
        after: Bitmap? = null,
    ): TranslatedPage? {
        val seam = Seam.of(page, before, after)
        try {
            val detector = PageTextDetector(script)
            val runs = detector.read(seam.image)
            val blocks = detector.blocks(runs, merge = true)
            if (blocks.isEmpty()) return null

            val scored = BlockScorer
                .score(blocks, seam.image, DetectionThresholds.fromPrefs())
                .map { seam.assign(it) }
            val wanted = scored.filter { it.verdict.translatable }
            if (wanted.isEmpty()) return null

            val engine = TranslationEngine.fromPref()
            val from = sourceCode(script, sourceLanguage)
            val to = SourceScript.targetLanguage()
            val translations = engine.build(from, to, targetLabel(to)).use { translator ->
                translator.prepare()
                // One call for the whole page. The batch is what lets a language model see the
                // bubbles as a conversation rather than as unrelated fragments.
                translator.translate(wanted.map { it.block.sourceText(script) })
            }

            val byId = wanted.mapIndexed { index, entry ->
                entry.block.id to translations.getOrElse(index) { entry.block.sourceText(script) }
            }.toMap()
            val finished = scored.map { entry ->
                byId[entry.block.id]?.let { entry.copy(block = entry.block.copy(translation = it)) }
                    ?: entry
            }
            val painted = seam.toPage(
                TranslationLayout.paint(
                    finished,
                    seam.image.width,
                    seam.image.height,
                    seam.image,
                ),
                page.width,
                page.height,
            )
            if (painted.isEmpty()) return null
            return TranslatedPage(
                blocks = painted,
                pageWidth = page.width,
                pageHeight = page.height,
            )
        } finally {
            seam.recycle()
        }
    }

    /** The language named in words, which is what the model-based engines put in their prompt. */
    private fun targetLabel(code: String): String =
        Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH)

    /**
     * What the page is being translated *from*.
     *
     * The script answers this for the CJK recognizers, each of which reads exactly one language.
     * Latin does not: it reads Spanish, French and Indonesian alike, and mapping it to English —
     * which is what the recognizer-to-language table has to do on its own — asks the translator to
     * turn English into English and hand back the Spanish it was given. The source extension's
     * language is the thing that knows, so where it says something usable it wins.
     */
    private fun sourceCode(script: TextScript, sourceLanguage: String?): String {
        if (script == TextScript.LATIN) {
            val code = sourceLanguage?.lowercase(Locale.ROOT)?.trim()
                ?.substringBefore('-')?.substringBefore('_')
            if (!code.isNullOrBlank() &&
                MlKitTranslator.supportedTargets().any { it.first == code }
            ) {
                return code
            }
        }
        return MlKitTranslator.translationCodeFor(script.name)
            ?: error("no translation code for $script")
    }

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

    /** Whether pages should translate themselves as they scroll into view. */
    fun auto(): Boolean = enabled() && PrefManager.getVal(PrefName.OcrAutoTranslate)

    /** Whether a page should be read together with a strip of its neighbours. */
    fun stitching(): Boolean = PrefManager.getVal(PrefName.OcrStitchPages)
}

/**
 * A page read together with a strip of the pages either side of it.
 *
 * A source's images are not pages. A longstrip chapter is one tall drawing sliced wherever the
 * packer's size limit fell, and that cut lands in the middle of a speech bubble often enough to be
 * the normal case rather than the exception. Recognising each image alone then reads the top half
 * of a sentence as one block and the bottom half as another, on different images, and hands the
 * translator two fragments — which it will translate, fluently and wrongly, because a fragment of
 * a sentence is still a sentence to a language model.
 *
 * So the recognizer is given the seam. Everything downstream of it — merging, scoring, layout —
 * then works in the composite's coordinates, and only the finished rectangles are brought back into
 * the page's own and clipped to it.
 *
 * Which page a block belongs to is decided by where its middle sits. The page that owns it writes
 * the translation; its neighbour, running this same pass over the same seam, sees the same block
 * fall outside itself and covers its own sliver without text — which is what stops half a Japanese
 * sentence being left showing under the English that replaced the other half.
 */
private class Seam(
    val image: Bitmap,
    /** Where the page itself begins within [image]. */
    private val offset: Int,
    private val pageHeight: Int,
    private val composite: Boolean,
) {

    /** Marks a block that belongs to a neighbouring page, so this page only covers its sliver. */
    fun assign(entry: ScoredBlock): ScoredBlock {
        if (!composite || !entry.verdict.translatable) return entry
        val centre = entry.block.box.centerY() - offset
        if (centre in 0 until pageHeight) return entry
        return entry.copy(verdict = BlockVerdict.SEAM)
    }

    /** Brings finished rectangles back into the page's own coordinates, dropping what missed it. */
    fun toPage(blocks: List<PaintedBlock>, width: Int, height: Int): List<PaintedBlock> {
        if (!composite) return blocks
        return blocks.mapNotNull { block ->
            val top = block.rect.top - offset
            val bottom = block.rect.bottom - offset
            if (bottom <= 0 || top >= height) return@mapNotNull null
            block.copy(
                rect = Rect(
                    block.rect.left.coerceIn(0, width),
                    top.coerceIn(0, height),
                    block.rect.right.coerceIn(0, width),
                    bottom.coerceIn(0, height),
                ),
            ).takeUnless { it.rect.isEmpty }
        }
    }

    fun recycle() {
        if (composite) image.recycle()
    }

    companion object {
        /** How much of a neighbour is read, as a share of this page's height. */
        private const val SHARE = 0.12f

        /**
         * Ceiling on that, in page pixels.
         *
         * A longstrip image can be eight thousand pixels tall, and a share of that would be a strip
         * bigger than most pages. A bubble is a few hundred pixels at most, so nothing past this
         * could be part of one straddling the seam — it would only be pixels to recognise twice.
         */
        private const val MAX = 420

        /**
         * Ceiling on the composite, past which the page is read on its own.
         *
         * A continuous page can be a whole scroll of webtoon — the reader decodes them up to four
         * screens tall — and a copy of one costs tens of megabytes at a moment when the page cache
         * is already holding a quarter of the heap. Ten megapixels is forty of ARGB and still
         * covers an 800-wide strip of eight thousand rows, which is the case this exists for.
         */
        private const val MAX_COMPOSITE_PIXELS = 10_000_000L

        fun of(page: Bitmap, before: Bitmap?, after: Bitmap?): Seam {
            if (!PageTranslationPipeline.stitching() || (before == null && after == null)) {
                return Seam(page, 0, page.height, composite = false)
            }
            val depth = min((page.height * SHARE).toInt(), MAX).coerceAtLeast(0)
            if (depth <= 0) return Seam(page, 0, page.height, composite = false)

            val topSource = before?.let { strip(it, page.width, depth, fromTop = false) }
            val bottomSource = after?.let { strip(it, page.width, depth, fromTop = true) }
            val topHeight = topSource?.second ?: 0
            val bottomHeight = bottomSource?.second ?: 0
            if (topHeight == 0 && bottomHeight == 0) {
                return Seam(page, 0, page.height, composite = false)
            }

            val height = topHeight + page.height + bottomHeight
            if (page.width.toLong() * height > MAX_COMPOSITE_PIXELS) {
                return Seam(page, 0, page.height, composite = false)
            }
            // Reading the seam is an improvement, not a requirement, so it gives way rather than
            // failing the page: on a device already near its limit the allocation is the largest
            // single thing this feature asks for, and a page translated without its neighbours is
            // very much better than a page that reports an out-of-memory error.
            val composite = runCatching {
                Bitmap.createBitmap(page.width, height, Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: return Seam(page, 0, page.height, composite = false)
            val canvas = Canvas(composite)
            topSource?.let { (src, height) ->
                canvas.drawBitmap(before!!, src, Rect(0, 0, page.width, height), null)
            }
            canvas.drawBitmap(page, 0f, topHeight.toFloat(), null)
            bottomSource?.let { (src, height) ->
                canvas.drawBitmap(
                    after!!,
                    src,
                    Rect(0, topHeight + page.height, page.width, topHeight + page.height + height),
                    null,
                )
            }
            return Seam(composite, topHeight, page.height, composite = true)
        }

        /**
         * The region of a neighbour to take, and how tall it lands once fitted to this page's width.
         *
         * Sources do serve images of differing widths within one chapter, and drawing a strip of one
         * width into a composite of another without accounting for it would squash the very glyphs
         * this exists to read.
         */
        private fun strip(
            neighbour: Bitmap,
            width: Int,
            depth: Int,
            fromTop: Boolean,
        ): Pair<Rect, Int>? {
            if (neighbour.width <= 0 || neighbour.height <= 0 || width <= 0) return null
            val scale = neighbour.width.toFloat() / width
            val sourceHeight = min((depth * scale).toInt(), neighbour.height)
            if (sourceHeight <= 0) return null
            val source = if (fromTop) {
                Rect(0, 0, neighbour.width, sourceHeight)
            } else {
                Rect(0, neighbour.height - sourceHeight, neighbour.width, neighbour.height)
            }
            return source to max(1, (sourceHeight / scale).toInt())
        }
    }
}
