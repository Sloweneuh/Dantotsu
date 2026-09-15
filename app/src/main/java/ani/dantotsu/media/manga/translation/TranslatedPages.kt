package ani.dantotsu.media.manga.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.LruCache
import ani.dantotsu.util.Logger
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
 * A box the reader drew by hand, and whether they overruled what the scoring made of it.
 *
 * [trusted] is off by default and that is the whole point of it: a drawn box says where to look,
 * not what the answer must be. Somebody framing a bubble the page pass split wants it translated;
 * somebody probing a caption to see whether it reads at all does not want artwork painted over
 * when it turns out it does not. The dialog offers the override on exactly the boxes where the
 * verdict would otherwise stop them — see `translate anyway` there.
 */
data class DrawnRegion(val rect: Rect, val trusted: Boolean = false)

/**
 * Boxes the reader drew by hand over text the page pass missed, keyed by page url.
 *
 * Kept apart from the translations so they survive one: a page translated again — after a change
 * of engine, say — is read with the same boxes, rather than losing the very text somebody took the
 * trouble to point at. Small, so a generous capacity costs nothing.
 */
object DrawnRegions {

    private const val CAPACITY = 200

    private val cache = LruCache<String, List<DrawnRegion>>(CAPACITY)

    operator fun get(pageUrl: String): List<DrawnRegion> = cache.get(pageUrl).orEmpty()

    fun put(pageUrl: String, regions: List<DrawnRegion>) {
        if (regions.isEmpty()) cache.remove(pageUrl) else cache.put(pageUrl, regions)
    }
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
     * @param settings  the engine, model and target to translate with, and whether to read across
     *                  the seam — the reader's own for the media open, never the preferences.
     * @param before    the page above this one, and [after] the page below, where the reader has
     *                  them decoded. See [Seam].
     * @param regions   boxes drawn by hand over text the page pass missed, in page pixels. Each is
     *                  read on its own, then joins the page's own runs and is merged, scored and
     *                  laid out with them — so a box over the column the pass dropped completes
     *                  its bubble instead of standing beside it. One marked [DrawnRegion.trusted]
     *                  is translated whatever the scoring said. One that reads as nothing stands
     *                  aside rather than taking the page pass' own block with it.
     * @return null when the page has nothing worth translating, so the caller can say so rather
     *         than showing an empty overlay.
     */
    suspend fun run(
        page: Bitmap,
        script: TextScript,
        sourceLanguage: String? = null,
        settings: MtlSettings = MtlSettings.fromPrefs(),
        before: Bitmap? = null,
        after: Bitmap? = null,
        regions: List<DrawnRegion> = emptyList(),
    ): TranslatedPage? {
        val seam = Seam.of(page, before, after, settings.stitch)
        try {
            val detector = PageTextDetector(script)
            val runs = detector.read(seam.image)
            val (hand, taken) = handRuns(detector, seam, regions)
            // What the page pass found inside a drawn box gives way to what the box itself read:
            // both are the same words, and carrying two copies into the merge would put the
            // bubble's line in twice. A region that read nothing takes nothing with it — that is
            // how a bubble the pass *had* found went missing from the very page somebody was
            // drawing boxes on to improve.
            val kept = runs.filterNot { superseded(it.box, taken) }
            // Drawn and found alike, merged together. A box drawn over the column the page pass
            // dropped is then simply the missing piece of its bubble, rather than a second block
            // standing beside it — which is what it was for as long as merging ran before the
            // drawn regions were read at all.
            val blocks = detector.blocks(kept + hand, merge = true, page = seam.image)
            if (blocks.isEmpty()) return null

            val scored = BlockScorer
                .score(blocks, seam.image, DetectionThresholds.fromPrefs())
                .map { seam.assign(it) }
                .map { trustDrawn(it) }
            val wanted = scored.filter { it.verdict.translatable }
            if (wanted.isEmpty()) return null

            val from = sourceCode(script, sourceLanguage)
            val to = settings.targetLanguage()
            val translations = settings.engine
                .build(from, to, targetLabel(to), model = settings.modelName())
                .use { translator ->
                translator.prepare()
                // One call for the whole page. The batch is what lets a language model see the
                // bubbles as a conversation rather than as unrelated fragments.
                translator.translate(wanted.map { it.block.sourceText(script) })
            }

            val byId = wanted.mapIndexed { index, entry ->
                val source = entry.block.sourceText(script)
                val answer = translations.getOrElse(index) { source }
                // An engine that cannot translate an entry returns it unchanged — the comic prompt
                // asks for exactly that where a value reads as garbled or as a watermark, the
                // keyless engines fall back to it on a failed request, and a missing key in a
                // model's reply falls back to it here. Painting that back over the bubble it came
                // from replaces the artist's lettering with system type saying the same words, and
                // hides the original while doing it. Treated as no translation, so the block is
                // left alone — which is what the blank case already does and for the same reason.
                entry.block.id to if (answer.trim() == source.trim()) "" else answer
            }.toMap()
            // A block the scorer wanted translated and that has no words to show for it is not
            // painted at all, which from the outside is a bubble the pass plainly found and then
            // did nothing about. Silent, and the one symptom hardest to tell from a detection
            // failure, so it is said here rather than guessed at from a screenshot.
            val silent = wanted.count { byId[it.block.id].isNullOrBlank() }
            if (silent > 0) {
                Logger.log("MTL: $silent of ${wanted.size} blocks came back untranslated")
            }
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

    /**
     * Reads and scores a page without translating it, for showing what the page pass would do.
     *
     * Detection **including the seam**, because the point is to show what the translation is going
     * to act on. Reading the page alone here was cheaper and was the whole trouble: ML Kit reads an
     * image as a whole, so prepending a strip of the neighbour moves where it puts its block
     * boundaries, and the two passes came back with different answers on the same page. One
     * reported bubble was a single merged box in the dialog and two separate ones in the finished
     * render, each holding a third of a word per line. A preview that disagrees with the thing it
     * previews is worse than no preview: it is where the boxes are drawn.
     *
     * Results are brought back into the page's own coordinates, since that is what the reader sees
     * and draws on. Blocks belonging wholly to a neighbour fall away with them.
     */
    suspend fun detect(
        page: Bitmap,
        script: TextScript,
        regions: List<DrawnRegion>,
        settings: MtlSettings = MtlSettings.fromPrefs(),
        before: Bitmap? = null,
        after: Bitmap? = null,
    ): List<ScoredBlock> {
        val seam = Seam.of(page, before, after, settings.stitch)
        try {
            val detector = PageTextDetector(script)
            val boxes = regions.map { seam.toImage(it.rect) }
            val blocks = detector
                .blocks(detector.read(seam.image), merge = true, page = seam.image)
                .filterNot { superseded(it.box, boxes) }
            return BlockScorer.score(blocks, seam.image, DetectionThresholds.fromPrefs())
                .map { seam.assign(it) }
                .mapNotNull { seam.toPage(it, page.width, page.height) }
        } finally {
            seam.recycle()
        }
    }

    /**
     * Whether a block the page pass found sits under a box the reader drew.
     *
     * The drawn box wins, where it is doing anything at all. It is how a reader says "this, as one
     * bubble" over a page pass that split it, or "this, correctly" over one that framed it wrong,
     * and translating both would put two translations on one piece of text. A box that is not
     * going to be covered has nothing to win with — see the ordering in [run].
     */
    fun superseded(box: Rect, regions: List<Rect>): Boolean =
        regions.any { it.contains(box.centerX(), box.centerY()) }

    /**
     * The boxes the reader drew, read one region at a time, as runs the merger can take.
     *
     * Padded and enlarged by [PageTextDetector.readRegion], which is the whole point: text the
     * page pass dropped usually reads perfectly from a crop, since the recognizer's answer depends
     * on the size it is shown. A box that reads as nothing is left out rather than painted blank —
     * and, because it is left out, it does not supersede anything either.
     *
     * @return the runs, and the rectangles that earned the right to replace what lies under them.
     */
    private suspend fun handRuns(
        detector: PageTextDetector,
        seam: Seam,
        regions: List<DrawnRegion>,
    ): Pair<List<PageTextDetector.TextRun>, List<Rect>> {
        if (regions.isEmpty()) return emptyList<PageTextDetector.TextRun>() to emptyList()
        val runs = ArrayList<PageTextDetector.TextRun>(regions.size)
        val taken = ArrayList<Rect>(regions.size)
        regions.forEach { region ->
            val rect = seam.toImage(region.rect)
            val read = detector.readRegion(seam.image, rect)
            val run = detector.regionRun(read, region.trusted) ?: return@forEach
            runs.add(run)
            // The rectangle that was drawn, not the run's own tight box: what the reader pointed
            // at is what they meant to take responsibility for, slack and all.
            taken.add(rect)
        }
        return runs to taken
    }

    /**
     * A drawn box the reader stood behind is translated whatever the ring or the confidence say.
     *
     * Those tests exist to keep the page pass from painting over artwork and translating noise,
     * and a box drawn by hand does not exempt itself from them merely by existing — that was the
     * old rule, and it meant a box probing a caption that turned out to sit on artwork got the
     * artwork painted over for its trouble. Asking instead is cheap: the dialog shows what the
     * scoring made of each box and offers the override on the ones it would otherwise stop.
     *
     * Overriding the verdict rather than skipping the tests keeps the calibration screen honest,
     * where a hand-drawn box is there precisely to see what the tests make of it.
     *
     * A block counts as drawn when any part of it was: a bubble somebody put back together by
     * pointing at its missing column is a bubble they stood behind.
     */
    private fun trustDrawn(entry: ScoredBlock): ScoredBlock =
        if (entry.block.synthetic && entry.block.trusted &&
            !entry.verdict.translatable && entry.verdict != BlockVerdict.SEAM
        ) {
            entry.copy(verdict = BlockVerdict.DIALOGUE)
        } else {
            entry
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

    /**
     * Whether the reader should offer this at all.
     *
     * The one translation switch that is not per media: it decides whether the feature exists,
     * and everything a series can choose for itself is in [MtlSettings].
     */
    fun enabled(): Boolean = PrefManager.getVal(PrefName.OcrTranslateEnabled)
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

    /** A rect in the page's own coordinates, moved into the composite's. */
    fun toImage(rect: Rect): Rect = if (!composite) rect else Rect(rect).apply { offset(0, offset) }

    /** A rect in the composite's coordinates brought back into the page's, or null if it missed. */
    fun toPage(rect: Rect, width: Int, height: Int): Rect? {
        if (!composite) return rect
        val top = rect.top - offset
        val bottom = rect.bottom - offset
        if (bottom <= 0 || top >= height) return null
        return Rect(
            rect.left.coerceIn(0, width),
            top.coerceIn(0, height),
            rect.right.coerceIn(0, width),
            bottom.coerceIn(0, height),
        ).takeUnless { it.isEmpty }
    }

    /**
     * A scored block in the page's own coordinates, or null where it belongs to a neighbour.
     *
     * Every rect it carries moves, not only its bounds: a line box left in the composite's frame
     * would be ringed somewhere else entirely if anything re-scored the block later.
     */
    fun toPage(entry: ScoredBlock, width: Int, height: Int): ScoredBlock? {
        if (!composite) return entry
        val box = toPage(entry.block.box, width, height) ?: return null
        return entry.copy(
            block = entry.block.copy(
                box = box,
                lineBoxes = entry.block.lineBoxes.mapNotNull { toPage(it, width, height) },
            ),
        )
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

        fun of(page: Bitmap, before: Bitmap?, after: Bitmap?, stitch: Boolean): Seam {
            if (!stitch || (before == null && after == null)) {
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
