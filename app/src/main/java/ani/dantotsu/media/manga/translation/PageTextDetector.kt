package ani.dantotsu.media.manga.translation

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.graphics.scale
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reads the text off a manga page.
 *
 * Detection is separated from turning runs into blocks so a caller can re-merge without re-reading
 * — [read] costs a round of recognition, [blocks] costs nothing.
 *
 * The one thing to keep in mind when changing anything here: **ML Kit's answer depends on the size
 * of the image it is given.** Every problem this pipeline was built to solve came back to that. A
 * bubble the page pass dropped entirely was read at 0.85 confidence from a crop; a character
 * invented on a drawing of teeth vanished when the identical box was re-read. Both paths differed
 * from the page pass only in enlarging the image first, which is why [ocrImage] exists and why an
 * elaborate tiled second pass was written, used, and then deleted once the cause was understood.
 */
class PageTextDetector(private val script: TextScript) {

    /** One run of text as the recognizer found it, before merging. */
    class TextRun(
        val box: Rect,
        val lines: List<Text.Line>,
        /** Each line's own bounds in page pixels, carried so a block can be scored line by line. */
        val lineBoxes: List<Rect>,
        val glyphPx: Float,
        val vertical: Boolean,
        /** Read from a box drawn by hand rather than found by the page pass. */
        val synthetic: Boolean = false,
        /** The reader asked for this to be translated whatever the scoring says. */
        val trusted: Boolean = false,
    )

    /**
     * What one region read as, together with what it takes to put the answer back on the page.
     *
     * [readRegion] recognises an enlarged crop, so every rectangle it hands back is in that crop's
     * frame. Carrying the crop and the enlargement is what lets a caller map them home — without
     * them the read is a string and nothing more, which is why a hand-drawn box could never take
     * part in merging.
     */
    data class RegionRead(
        val lines: List<Text.Line>,
        /** How much the crop was enlarged before the recognizer saw it. */
        val scale: Float,
        /** The region actually read, padding included, in page pixels. */
        val crop: Rect,
    )

    /** How many runs the last [read] discarded as sitting outside any bubble. */
    var rejected = 0
        private set

    /** Recognises [page], returning runs with geometry already in page pixels. */
    suspend fun read(page: Bitmap): List<TextRun> {
        rejected = 0
        val (image, scale) = ocrImage(page)
        return try {
            runs(recognize(image), image, scale)
        } finally {
            if (image !== page) image.recycle()
        }
    }

    /**
     * Reads one region on its own, for a block drawn by hand or one being re-read.
     *
     * Padded and enlarged, without which a negative result means nothing: a box drawn tightly by
     * hand leaves the recognizer no quiet space, and a few characters cut out of a page is a very
     * small image. Cropping without scaling asks a harder question than the one already answered.
     */
    suspend fun readRegion(page: Bitmap, rect: Rect): RegionRead {
        val marginX = (rect.width() * CROP_MARGIN).toInt().coerceAtLeast(MIN_CROP_MARGIN)
        val marginY = (rect.height() * CROP_MARGIN).toInt().coerceAtLeast(MIN_CROP_MARGIN)
        val safe = Rect(
            (rect.left - marginX).coerceIn(0, page.width - 1),
            (rect.top - marginY).coerceIn(0, page.height - 1),
            (rect.right + marginX).coerceIn(1, page.width),
            (rect.bottom + marginY).coerceIn(1, page.height),
        )
        if (safe.width() < MIN_CROP || safe.height() < MIN_CROP) {
            return RegionRead(emptyList(), 1f, safe)
        }

        return withContext(Dispatchers.Default) {
            val crop = Bitmap.createBitmap(page, safe.left, safe.top, safe.width(), safe.height())
            val scale = (CROP_TARGET.toFloat() / min(crop.width, crop.height))
                .coerceIn(1f, MAX_CROP_SCALE)
            val scaled = if (scale > 1f) {
                crop.scale((crop.width * scale).toInt(), (crop.height * scale).toInt())
            } else {
                crop
            }
            try {
                RegionRead(recognize(scaled).textBlocks.flatMap { it.lines }, scale, safe)
            } finally {
                if (scaled !== crop) scaled.recycle()
                crop.recycle()
            }
        }
    }

    /**
     * Turns runs into blocks, joining the ones that belong to the same bubble when asked.
     *
     * @param page the image the runs were read from, where the caller has it. Given one, two runs
     *   are only joined when what lies between them says they are in the same bubble — see
     *   [BubbleMerger.merge].
     */
    fun blocks(
        runs: List<TextRun>,
        merge: Boolean,
        firstId: Int = 1,
        page: Bitmap? = null,
    ): List<TextBlock> {
        var id = firstId
        return (if (merge) BubbleMerger.merge(runs, page) else runs).map { run ->
            toBlock(
                id++, run.lines, run.box,
                synthetic = run.synthetic, trusted = run.trusted,
                glyphPx = run.glyphPx, lineBoxes = run.lineBoxes,
            )
        }
    }

    /**
     * One region's read as a run in the page's own coordinates, ready to merge with the rest.
     *
     * This is what lets a box drawn by hand join the bubble it belongs to. Until it existed a
     * drawn box could only ever be a block of its own: [readRegion] answers in the frame of an
     * enlarged crop, and a caller with no way home had nothing to offer [BubbleMerger], which
     * works entirely in page pixels. So a reader patching the hole where the page pass dropped a
     * column got a second block beside the bubble rather than the bubble put back together.
     *
     * The box is the extent of the glyphs rather than the rectangle that was drawn. A box drawn by
     * hand has slack in it, and slack on the merging side reads as two runs sitting closer
     * together than the text in them really is.
     */
    fun regionRun(read: RegionRead, trusted: Boolean): TextRun? {
        if (read.lines.isEmpty()) return null
        val box = tightBox(read.lines)?.toPage(read) ?: return null
        return TextRun(
            box = box,
            lines = read.lines,
            lineBoxes = read.lines.mapNotNull { tightBox(listOf(it))?.toPage(read) },
            glyphPx = read.lines.map { glyphSize(it).second }.average().toFloat() / read.scale,
            vertical = read.lines.first().angle > TextBlock.VERTICAL_ANGLE,
            synthetic = true,
            trusted = trusted,
        )
    }

    /** A rectangle in an enlarged crop's frame, brought back into the page's. */
    private fun Rect.toPage(read: RegionRead) = Rect(
        read.crop.left + (left / read.scale).toInt(),
        read.crop.top + (top / read.scale).toInt(),
        read.crop.left + (right / read.scale).toInt(),
        read.crop.top + (bottom / read.scale).toInt(),
    )

    /**
     * Builds a block from lines the caller obtained itself, as [readRegion] returns.
     *
     * [lineBoxes] is left empty by such a caller on purpose: [readRegion]'s lines are in the
     * coordinates of an enlarged crop rather than of the page, and a line box in the wrong frame
     * would be ringed somewhere else entirely. An empty list is what puts [BlockScorer] back on the
     * block's own ring, which is what it used before line boxes existed.
     */
    fun toBlock(
        id: Int,
        lines: List<Text.Line>,
        box: Rect,
        synthetic: Boolean = false,
        trusted: Boolean = false,
        glyphScale: Float = 1f,
        glyphPx: Float? = null,
        fallbackGlyphPx: Float = 0f,
        lineBoxes: List<Rect> = emptyList(),
    ): TextBlock {
        val glyphs = lines.map { glyphSize(it) }
        val (ordered, reordered) = orderedText(lines, lineBoxes)
        return TextBlock(
            id = id,
            text = if (lines.isEmpty()) "" else ordered.replace("\n", " / "),
            box = box,
            confidence = if (lines.isEmpty()) 0f else {
                lines.map { it.confidence }.average().toFloat()
            },
            angle = lines.firstOrNull()?.angle ?: 0f,
            glyphPx = glyphPx ?: if (glyphs.isEmpty()) fallbackGlyphPx else {
                glyphs.map { it.second }.average().toFloat() / glyphScale
            },
            katakanaRatio = Kana.katakanaRatio(ordered),
            kanaOnly = Kana.kanaOnly(ordered),
            lineCount = lines.size,
            reordered = reordered,
            symbolsAvailable = glyphs.isNotEmpty() && glyphs.all { it.first },
            lineBoxes = lineBoxes,
            synthetic = synthetic,
            trusted = trusted,
        )
    }

    // -------------------------------------------------------------------------------------------
    // Recognition
    // -------------------------------------------------------------------------------------------

    private suspend fun recognize(bitmap: Bitmap): Text {
        val recognizer = TextRecognition.getClient(
            when (script) {
                TextScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
                TextScript.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
                TextScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
                TextScript.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
            },
        )
        return try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        } finally {
            recognizer.close()
        }
    }

    /**
     * The page as the recognizer should see it, enlarged when it is small, and by how much.
     *
     * Pages run around 800px wide with glyphs near 18px, which is simply too small for the
     * detector. The page bitmap itself is left alone — it is what gets drawn, ringed and measured
     * against — and results are divided back into its coordinates.
     *
     * The enlargement is capped by area as well as by width, because a longstrip image is not a
     * page: 800x8000 doubled is 25 megapixels, a hundred megabytes of bitmap, for an image whose
     * glyphs were already legible. Width alone cannot see that.
     */
    private fun ocrImage(page: Bitmap): Pair<Bitmap, Float> {
        val pixels = page.width.toLong() * page.height
        val byArea = if (pixels <= 0) MAX_OCR_SCALE else sqrt(MAX_OCR_PIXELS / pixels.toFloat())
        val scale = min(OCR_TARGET_WIDTH.toFloat() / page.width, byArea)
            .coerceIn(1f, MAX_OCR_SCALE)
        if (scale == 1f) return page to 1f
        return page.scale((page.width * scale).toInt(), (page.height * scale).toInt()) to scale
    }

    private fun runs(text: Text, image: Bitmap, scale: Float): List<TextRun> =
        text.textBlocks
            .filter { it.boundingBox != null && it.text.length > 1 && it.lines.isNotEmpty() }
            .mapNotNull { block ->
                val lines = linesInsideBubble(block.lines, image)
                if (lines.isEmpty()) return@mapNotNull null
                val box = tightBox(lines) ?: return@mapNotNull null
                TextRun(
                    box = box.scaledDown(scale),
                    lines = lines,
                    lineBoxes = lines.mapNotNull { tightBox(listOf(it))?.scaledDown(scale) },
                    glyphPx = lines.map { glyphSize(it).second }.average().toFloat() / scale,
                    vertical = lines.first().angle > TextBlock.VERTICAL_ANGLE,
                )
            }

    private fun Rect.scaledDown(scale: Float) = if (scale == 1f) this else Rect(
        (left / scale).toInt(),
        (top / scale).toInt(),
        (right / scale).toInt(),
        (bottom / scale).toInt(),
    )

    // -------------------------------------------------------------------------------------------
    // Geometry
    // -------------------------------------------------------------------------------------------

    /**
     * The extent of the actual glyphs, rather than the boxes the recognizer drew around them.
     *
     * A line's box can be far taller than the characters in it — one measured line reached out of
     * its bubble and across an open mouth — and no judgement of that box could fix it, because the
     * text inside was good and a ring around the result reads uniform, the intruding region being
     * a minority of it. Symbols are the way out: each character carries its own box.
     */
    private fun tightBox(lines: List<Text.Line>): Rect? {
        val glyphs = lines.flatMap { sanedGlyphs(it) }
        val elements = lines.flatMap { line -> line.elements.mapNotNull { it.boundingBox } }
        val boxes = glyphs.ifEmpty { elements }.ifEmpty { lines.mapNotNull { it.boundingBox } }
        if (boxes.isEmpty()) return null
        return boxes.reduce { a, b -> Rect(a).apply { union(b) } }
    }

    /**
     * One line's glyph boxes, with any the recognizer drew far too large cut back to size.
     *
     * A column of seven characters came back with boxes 145, 36, 35, 39, 39, 33 and 19 pixels tall.
     * The first is not a character but one character's box stretched across the drawing above it,
     * and since a block's extent is the union of its glyphs, that single box dragged the bubble's
     * frame out over the artwork. An outlier is put back where its neighbours say it belongs:
     * characters in a line abut, so a box that has grown has grown *away* from the neighbour it
     * touches, and the near edge plus a median glyph is where the character really is.
     */
    private fun sanedGlyphs(line: Text.Line): List<Rect> {
        val boxes = line.elements.flatMap { element ->
            element.symbols.mapNotNull { it.boundingBox }
        }
        if (boxes.size < 3) return boxes
        val vertical = line.angle > TextBlock.VERTICAL_ANGLE
        val sorted = if (vertical) boxes.sortedBy { it.top } else boxes.sortedBy { it.left }
        val extents = sorted.map { if (vertical) it.height() else it.width() }.sorted()
        val median = extents[extents.size / 2]
        if (median <= 0) return sorted

        return sorted.mapIndexed { index, box ->
            val extent = if (vertical) box.height() else box.width()
            if (extent <= median * GLYPH_OUTLIER) return@mapIndexed box
            val next = sorted.getOrNull(index + 1)
            val previous = sorted.getOrNull(index - 1)
            when {
                next != null -> if (vertical) {
                    Rect(box.left, next.top - median, box.right, next.top)
                } else {
                    Rect(next.left - median, box.top, next.left, box.bottom)
                }

                previous != null -> if (vertical) {
                    Rect(box.left, previous.bottom, box.right, previous.bottom + median)
                } else {
                    Rect(previous.right, box.top, previous.right + median, box.bottom)
                }

                else -> box
            }
        }
    }

    /**
     * Drops lines the recognizer found outside anything resembling a bubble.
     *
     * Teeth, hatching and panel borders read as text often enough to matter. Each line is ringed on
     * its own, with its siblings excluded from the sample — a middle column would otherwise measure
     * the columns either side of it — against a threshold far slacker than the block-level one,
     * since a false rejection here silently deletes readable text.
     */
    private fun linesInsideBubble(lines: List<Text.Line>, bitmap: Bitmap): List<Text.Line> {
        val boxes = lines.mapNotNull { it.boundingBox }
        val kept = lines.filter { line ->
            val box = line.boundingBox ?: return@filter false
            val ring = RingSampler.sample(
                bitmap,
                box,
                glyphSize(line).second.toFloat(),
                LINE_RING_PAD,
                boxes.filter { it !== box },
            )
            ring.iqr <= LINE_RING_IQR
        }
        // Rejecting everything means the test is wrong about this block rather than the block being
        // wrong, so the recognizer's own answer stands.
        if (kept.isEmpty()) return lines
        rejected += lines.size - kept.size
        return kept
    }

    /**
     * Glyph size for one line, and whether it came from real symbol boxes.
     *
     * The cross-axis is a line's glyph size whichever way the line runs — the width of a character
     * in a vertical column, the height of one in a horizontal row — so `min(width, height)` of the
     * line box is a sound fallback when symbols come back empty.
     *
     * Where symbols are available it is the *median* of their cross-axes, and both halves of that
     * matter. Taking the short side of one box reads a character like 一, ー, 「 or a comma at a
     * fraction of its real size, because the ink of those is a thin bar rather than a square; and
     * taking it from the first symbol alone lets one such character at the head of a column decide
     * the whole line. A column beginning 一角に measured a glyph of 3px against the 16px of the
     * column beside it, which is past [BubbleMerger.GLYPH_RATIO] — so the two halves of one caption
     * were refused a merge, and each was translated, sized and drawn as if it were a bubble of its
     * own. Everything downstream is measured in glyph widths, so a wrong one is wrong everywhere:
     * the ring's thickness, the gap two runs may be merged across, whether a block is a sound
     * effect, and how much room its translation asks for.
     */
    private fun glyphSize(line: Text.Line): Pair<Boolean, Int> {
        val vertical = line.angle > TextBlock.VERTICAL_ANGLE
        val extents = line.elements
            .flatMap { element -> element.symbols.mapNotNull { it.boundingBox } }
            .map { if (vertical) it.width() else it.height() }
            .sorted()
        if (extents.isNotEmpty()) {
            val median = extents[extents.size / 2]
            if (median > 0) return true to median
        }
        val box = line.boundingBox ?: return false to 0
        return false to min(box.width(), box.height())
    }

    /**
     * The lines in reading order, and whether that differed from the order given.
     *
     * Vertical Japanese runs top to bottom in columns advancing **right to left**, so a bubble's
     * first column is its rightmost. ML Kit hands them back the other way round, and joining them
     * as given turns a sentence inside out — invisibly, because the translator then returns a
     * fluent, confident translation of the scrambled version.
     */
    private fun orderedText(lines: List<Text.Line>, boxes: List<Rect>): Pair<String, Boolean> {
        if (lines.isEmpty()) return "" to false
        if (lines.size < 2) return lines.joinToString("\n") { it.text } to false

        // A line's bounding box is in the frame of whatever image it was recognised in, and a
        // merged run can hold lines from two of them — the page, and the enlarged crop a drawn box
        // was read from. Sorting those together by their own boxes interleaves page coordinates
        // with crop coordinates and turns the bubble inside out. [boxes] is every line's place on
        // the page, which is the one frame they all share.
        val place: (Int) -> Rect? =
            if (boxes.size == lines.size) ({ boxes[it] }) else ({ lines[it].boundingBox })
        val order = lines.indices.sortedWith(
            if (lines.first().angle > TextBlock.VERTICAL_ANGLE) {
                compareByDescending { place(it)?.left ?: 0 }
            } else {
                // Sorted rather than taken as given, because a merged run's lines arrive in
                // whatever order its pieces were joined in, which is nobody's reading order.
                compareBy { place(it)?.top ?: 0 }
            },
        )
        return order.joinToString("\n") { lines[it].text } to (order != lines.indices.toList())
    }

    companion object {
        /** Width the page is enlarged to before the recognizer sees it. */
        const val OCR_TARGET_WIDTH = 1600
        const val MAX_OCR_SCALE = 2.5f

        /**
         * Ceiling on the enlarged bitmap, which is what keeps a tall strip within memory.
         *
         * Eight megapixels is thirty-two of ARGB, on top of the page's own copy and whatever the
         * reader is holding either side of it. It never binds on a page — 800x1200 doubled is 2 —
         * and on a strip it lands near 1x, which is the right answer anyway: an 800-wide strip
         * already has glyphs the recognizer can read.
         */
        const val MAX_OCR_PIXELS = 8_000_000f

        /** How many median glyphs tall a glyph box may be before it is treated as misdrawn. */
        const val GLYPH_OUTLIER = 2f

        /** Ring thickness used when judging a single line, in glyph widths. */
        const val LINE_RING_PAD = 0.5f

        /**
         * Ring spread above which a line is taken to be outside any bubble.
         *
         * Across four pages, in-bubble rings never exceeded 17 while artwork ran 28 and up; 30 sits
         * in that gap with room on the safe side.
         */
        const val LINE_RING_IQR = 30f

        /** Quiet space added around a region before it is read on its own, as a share of its size. */
        const val CROP_MARGIN = 0.25f
        const val MIN_CROP_MARGIN = 12
        const val MIN_CROP = 12
        const val CROP_TARGET = 320
        const val MAX_CROP_SCALE = 4f
    }
}

/** Kana tests, which together with glyph size are what identify ruby. */
internal object Kana {
    private val KATAKANA = '゠'..'ヿ'
    private val KATAKANA_PHONETIC = 'ㇰ'..'ㇿ'
    private val KATAKANA_HALFWIDTH = 'ｦ'..'ﾝ'
    private val HIRAGANA = 'ぁ'..'ゟ'

    private fun isKana(c: Char) =
        c in HIRAGANA || c in KATAKANA || c in KATAKANA_PHONETIC || c in KATAKANA_HALFWIDTH

    /** Katakana as a share of letters — Japanese onomatopoeia is written in it almost exclusively. */
    fun katakanaRatio(text: String): Float {
        var katakana = 0
        var letters = 0
        text.forEach { c ->
            if (!c.isLetter()) return@forEach
            letters++
            if (c in KATAKANA || c in KATAKANA_PHONETIC || c in KATAKANA_HALFWIDTH) katakana++
        }
        return if (letters == 0) 0f else katakana.toFloat() / letters
    }

    /**
     * Whether every letter is kana, with no kanji among them.
     *
     * Half of what identifies furigana, and useless alone — plenty of ordinary dialogue is written
     * entirely in kana, which is why it is only ever used with a glyph size well under the page's.
     */
    fun kanaOnly(text: String): Boolean {
        var letters = 0
        text.forEach { c ->
            if (!c.isLetter()) return@forEach
            letters++
            if (!isKana(c)) return false
        }
        return letters > 0
    }
}

/**
 * Joins the runs that belong to the same speech bubble.
 *
 * The recognizer splits a bubble wherever it likes — one page came back as 24 runs for perhaps nine
 * bubbles — and every stage downstream suffers. Fragments of one sentence get separate boxes
 * competing for the same width so each stays narrow; they are drawn in no particular order, so the
 * sentence scatters across the page; and each fragment reaches the translator alone, which is
 * exactly the input that turns a mediocre translator into a confidently wrong one.
 *
 * The rule is orientation-aware, which is where it departs from TachiyomiAT's. Theirs assumes
 * horizontal text; for vertical every axis transposes, and what marks two runs as one bubble is a
 * small *horizontal* gap between runs sharing most of their *vertical* extent.
 */
internal object BubbleMerger {

    /** Largest glyph-size ratio two runs may differ by and still be one bubble. */
    private const val GLYPH_RATIO = 1.6f

    /** Largest gap between two runs of one bubble, in glyph widths. */
    private const val GAP_GLYPHS = 1.8f

    /**
     * How far that stretches across a gap that turns out to be full of text.
     *
     * [GAP_GLYPHS] is column spacing, and column spacing is all it should be. But the page pass
     * drops a whole column often enough to be the normal case, and a bubble with its middle column
     * missing has a hole in it two glyph widths across — a measured one came to 2.08, just past
     * the limit, so the two halves of one bubble were translated, sized and drawn separately. A
     * gap that wide is only ever crossable when something is *in* it; see [MISSED_INK].
     */
    private const val MISSED_GAP_GLYPHS = 3f

    /**
     * Share of a gap that must be off its own field before it counts as holding missed text.
     *
     * The gap where a column had gone missing measured 0.30 against 0.05 and 0.06 for the clean
     * white between two separate bubbles on the same page — one of those only 2.5 glyphs wide,
     * which is to say a plain distance threshold could not have told them apart at all.
     */
    private const val MISSED_INK = 0.15f

    /**
     * Share of one line down a gap that must be off the field for that line to be a rule.
     *
     * A rule is opaque along its whole length and measures 1.0. The nearest thing to a false
     * positive is a dotted divider drawn inside a bubble, at 0.63.
     */
    private const val RULED_SHARE = 0.85f

    /**
     * How much ink may lie *beside* a ruled line before it is not a rule after all.
     *
     * One line at 1.0 settles nothing on its own, because kanji have long straight strokes: the
     * gap holding a whole missed column measured 1.00 on its strongest line, exactly as the panel
     * rule did. What tells them apart is everything else in the gap. Beside the rule there was
     * nothing — 0.00, the gutter being clean white — while beside the missed column's strongest
     * stroke lay the rest of the column, at 0.27. A rule accounts for the ink in its gap; a column
     * of text cannot.
     */
    private const val RULE_BESIDE_INK = 0.10f

    /** How much of their shared axis two runs must overlap on to be one bubble. */
    private const val SPAN_OVERLAP = 0.5f

    /**
     * Narrowest gap worth measuring, in page pixels.
     *
     * A band one pixel wide pressed between two boxes is mostly the antialiasing of the glyphs
     * either side of it, which would read as a rule on the cleanest bubble on the page. Runs that
     * close together are touching, which is its own answer.
     */
    private const val MIN_GAP_PX = 2

    /**
     * @param page the image the runs came from, where the caller has it. Without it the gap test
     *   is skipped and the rule is geometry alone, which merged a speech bubble with the
     *   advertising column printed alongside the panel — adjacent, similarly sized vertical text,
     *   and nothing in the numbers to say otherwise.
     */
    fun merge(
        input: List<PageTextDetector.TextRun>,
        page: Bitmap? = null,
    ): List<PageTextDetector.TextRun> {
        val work = input.toMutableList()
        while (true) {
            val pair = findPair(work, page, input) ?: break
            val (i, j) = pair
            val joined = join(work[i], work[j])
            // Higher index first, or removing the lower shifts the higher out from under us.
            work.removeAt(j)
            work.removeAt(i)
            work.add(joined)
        }
        return work
    }

    private fun findPair(
        work: List<PageTextDetector.TextRun>,
        page: Bitmap?,
        found: List<PageTextDetector.TextRun>,
    ): Pair<Int, Int>? {
        for (i in work.indices) {
            for (j in i + 1 until work.size) {
                if (shouldMerge(work[i], work[j], page, found)) return i to j
            }
        }
        return null
    }

    private fun shouldMerge(
        a: PageTextDetector.TextRun,
        b: PageTextDetector.TextRun,
        page: Bitmap?,
        found: List<PageTextDetector.TextRun>,
    ): Boolean {
        if (a.vertical != b.vertical) return false
        if (a.glyphPx <= 0f || b.glyphPx <= 0f) return false
        // Text of visibly different size is not one bubble — this is what stops a sound effect
        // being absorbed into the dialogue beside it.
        if (max(a.glyphPx, b.glyphPx) / min(a.glyphPx, b.glyphPx) > GLYPH_RATIO) return false

        val glyph = (a.glyphPx + b.glyphPx) / 2f
        val distance: Float
        val span: Float
        if (a.vertical) {
            distance = gap(a.box.left, a.box.right, b.box.left, b.box.right)
            span = overlap(a.box.top, a.box.bottom, b.box.top, b.box.bottom)
        } else {
            distance = gap(a.box.top, a.box.bottom, b.box.top, b.box.bottom)
            span = overlap(a.box.left, a.box.right, b.box.left, b.box.right)
        }
        if (span < SPAN_OVERLAP) return false
        if (distance > glyph * MISSED_GAP_GLYPHS) return false

        // Touching, or no page to look at: the geometry is all there is to go on.
        val band = between(a.box, b.box, a.vertical)
        if (page == null || band == null) return distance <= glyph * GAP_GLYPHS

        val divider = RingSampler.divider(page, band, a.vertical)
        // A rule drawn the length of the gap divides what is either side of it however close the
        // two sit: merging across one put a speech bubble and the advertising strip printed beside
        // the panel into a single box.
        if (ruled(divider)) return false
        // Ordinary column spacing.
        if (distance <= glyph * GAP_GLYPHS) return true
        // Or a gap only this wide because a column went missing out of the middle of the bubble.
        return divider.ink >= MISSED_INK &&
            lostColumn(band, max(a.glyphPx, b.glyphPx), found)
    }

    /**
     * Whether the text filling a gap could be a column these two runs lost, rather than one they
     * are both the reading of.
     *
     * The two look identical from the outside and the difference is everything. Ruby is set beside
     * the column it reads, so the readings either side of a line of kanji stand exactly one body
     * column apart — which is precisely the shape of a column gone missing, full of ink and a
     * little too wide to be ordinary spacing. Bridging those merged two readings across the very
     * kanji they belonged to: the block spanned the body text without containing it, and was
     * either translated as the nonsense its two readings run together make and painted over the
     * bubble, or covered as ruby and painted out over the kanji.
     *
     * The recognizer settles it. Ink it never found can only be a column it lost; ink it *did*
     * find, in type larger than ours, is what we are the reading of.
     */
    private fun lostColumn(
        band: Rect,
        glyph: Float,
        found: List<PageTextDetector.TextRun>,
    ): Boolean = found.none {
        it.glyphPx > glyph * GLYPH_RATIO && Rect.intersects(it.box, band)
    }

    /**
     * Whether a rule is drawn down a gap, as opposed to text standing in it.
     *
     * Both reach [RULED_SHARE] on their strongest line, so the strongest line is not the question;
     * what is beside it is. See [RULE_BESIDE_INK].
     */
    private fun ruled(divider: Divider): Boolean {
        if (divider.lines.none { it >= RULED_SHARE }) return false
        val beside = divider.lines.filter { it < RULED_SHARE }
        return beside.isEmpty() || beside.average() < RULE_BESIDE_INK
    }

    /**
     * The band lying between two boxes, across the extent they share, or null where they touch.
     *
     * Runs that overlap on the axis the gap would be measured along have no gap to measure, and a
     * rect of negative width samples nothing.
     */
    private fun between(a: Rect, b: Rect, vertical: Boolean): Rect? {
        val rect = if (vertical) {
            Rect(
                min(a.right, b.right),
                max(a.top, b.top),
                max(a.left, b.left),
                min(a.bottom, b.bottom),
            )
        } else {
            Rect(
                max(a.left, b.left),
                min(a.bottom, b.bottom),
                min(a.right, b.right),
                max(a.top, b.top),
            )
        }
        val thickness = if (vertical) rect.width() else rect.height()
        return rect.takeUnless { it.isEmpty || thickness < MIN_GAP_PX }
    }

    /** Distance between two spans on one axis; zero where they touch or overlap. */
    private fun gap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Float =
        max(0, max(aStart, bStart) - min(aEnd, bEnd)).toFloat()

    /** How much of the shorter of two spans the two share, as a fraction. */
    private fun overlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Float {
        val shared = min(aEnd, bEnd) - max(aStart, bStart)
        val shorter = min(aEnd - aStart, bEnd - bStart)
        return if (shorter <= 0) 0f else (shared.toFloat() / shorter).coerceIn(0f, 1f)
    }

    private fun join(a: PageTextDetector.TextRun, b: PageTextDetector.TextRun) =
        PageTextDetector.TextRun(
            box = Rect(a.box).apply { union(b.box) },
            // Left unordered: orderedText sorts every line of the joined run into reading order,
            // which is the point of merging in the first place.
            lines = a.lines + b.lines,
            lineBoxes = a.lineBoxes + b.lineBoxes,
            glyphPx = (a.glyphPx + b.glyphPx) / 2f,
            vertical = a.vertical,
            // A bubble half of which somebody pointed at is still a bubble somebody pointed at.
            synthetic = a.synthetic || b.synthetic,
            trusted = a.trusted || b.trusted,
        )
}
