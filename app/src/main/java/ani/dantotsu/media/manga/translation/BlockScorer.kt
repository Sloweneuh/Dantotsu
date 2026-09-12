package ani.dantotsu.media.manga.translation

import android.graphics.Bitmap

/**
 * Decides what should happen to each block on a page.
 *
 * Separate from detection because it is cheap and repeatable: the thresholds are calibrated by
 * hand, and re-scoring a page costs one ring sample per block where re-detecting it costs a round
 * of recognition. The OCR screen leans on that — every slider move re-scores and nothing re-reads.
 */
object BlockScorer {

    /**
     * Below this share of the page's median glyph, kana-only text is taken to be furigana.
     *
     * Ruby is conventionally about half the size of the text it annotates. On a measured page body
     * glyphs ran 1.16-1.56% of page height while the three furigana came in at 0.43, 0.69 and
     * 0.94% — the last of which is why this sits at 0.8 rather than nearer the conventional 0.5.
     */
    private const val FURIGANA_RATIO = 0.8f

    /**
     * Share of a block's lines that must ring flat before the block counts as enclosed.
     *
     * A majority rather than all of them, because the column pressed hardest against a bubble's
     * outline is the one that measures worst and every full bubble has one. On a measured page the
     * six columns of one bubble ringed at 1, 1, 2, 4, 5 and 80.
     */
    private const val ENCLOSED_LINES = 0.6f

    fun score(
        blocks: List<TextBlock>,
        page: Bitmap,
        thresholds: DetectionThresholds,
    ): List<ScoredBlock> {
        // Furigana are small *relative to this page*, so the comparison comes from the page rather
        // than a constant: a densely lettered release and a large-print one differ by more than
        // furigana differ from body text within either.
        val bodyGlyph = blocks.map { it.glyphPx }.filter { it > 0f }.sorted()
            .let { if (it.isEmpty()) 0f else it[it.size / 2] }

        return blocks.map { block ->
            val ring = RingSampler.sample(page, block.box, block.glyphPx, thresholds.ringPad)
            // Measured only where the block's own ring has already failed. It answers the same
            // question better, but it costs a sample per line against the block ring's one, and
            // every slider move in the OCR screen re-scores the whole page. A block of one line
            // has nothing to gain either way: its line box is its block box.
            val lineFlat = if (flat(ring, thresholds) || block.lineBoxes.size < 2) {
                null
            } else {
                lineFlatShare(block, page, thresholds)
            }
            val verdict = verdict(block, ring, lineFlat, page.height, bodyGlyph, thresholds)
            ScoredBlock(block, ring, verdict, lineFlat)
        }
    }

    /**
     * Share of a block's lines whose own ring is flat.
     *
     * The block ring asks its question of a rectangle, and a bubble is not one. Text that fills a
     * round bubble hangs the corners of its bounding box over the artwork outside it, and a tall
     * box puts a whole side of its ring out there — a third of the samples on a measured page,
     * which is more than enough to carry a quartile range past any threshold that still rejects a
     * drawing. Asked one line at a time it is answerable: a column is narrow, its ring hugs it, and
     * it stays inside the bubble with the rest of the text.
     *
     * This is the test [PageTextDetector] already uses to drop lines found on teeth and hatching,
     * put to the block's own thresholds. Siblings are excluded from each sample for the same reason
     * it excludes them there: the columns either side of a middle one sit exactly where its ring
     * falls, and counting them makes healthy text read as artwork.
     */
    private fun lineFlatShare(block: TextBlock, page: Bitmap, t: DetectionThresholds): Float {
        val boxes = block.lineBoxes
        val enclosed = boxes.count { box ->
            val ring = RingSampler.sample(
                page,
                box,
                block.glyphPx,
                t.ringPad,
                boxes.filter { it !== box },
            )
            ring.iqr <= t.ringIqr || flat(ring, t)
        }
        return enclosed.toFloat() / boxes.size
    }

    /**
     * Ordered by what makes the difference downstream: a block that is not in a bubble must not be
     * covered at all, a block the recognizer does not believe must not be translated, and only then
     * is it worth asking whether what remains is dialogue or an effect.
     */
    private fun verdict(
        block: TextBlock,
        ring: Ring,
        lineFlat: Float?,
        pageHeight: Int,
        bodyGlyph: Float,
        t: DetectionThresholds,
    ): BlockVerdict {
        // Ahead of the ring test, because furigana sit hard against the kanji they gloss and so
        // often ring as badly as artwork does. Calling them OVER_ART would be right for the wrong
        // reason, and would hide what they are.
        if (block.kanaOnly && bodyGlyph > 0f && block.glyphPx < bodyGlyph * FURIGANA_RATIO) {
            return BlockVerdict.FURIGANA
        }
        // A ring that is nearly all one colour is safe to paint over whatever its spread says, and
        // the two disagree more often than they look like they should. Lettering dropped straight
        // onto a flat sky, a screentone or a black gutter has no bubble to find, so the outline the
        // spread is measuring is the artwork's own edge some way off — high iqr, nothing at risk.
        // The colour it is painted with comes from the same measurement, which is what keeps the
        // patch invisible instead of a grey rectangle in the middle of a blue sky.
        if (!flat(ring, t) && !enclosed(lineFlat)) {
            if (ring.iqr > t.ringIqr) return BlockVerdict.OVER_ART
            if (t.minBrightness > 0f && ring.median < t.minBrightness) return BlockVerdict.OVER_ART
        }
        if (block.confidence < t.minConfidence) return BlockVerdict.LOW_CONF

        val glyphPercent = block.glyphPercent(pageHeight)
        val isSfx = glyphPercent >= t.glyphPercent ||
            (
                block.katakanaRatio * 100f >= t.katakanaPercent &&
                    glyphPercent >= t.glyphPercent / 2f
                )
        return if (isSfx) BlockVerdict.SFX else BlockVerdict.DIALOGUE
    }

    /** Whether a block's surroundings are one colour, near enough to cover without loss. */
    fun flat(ring: Ring, t: DetectionThresholds): Boolean =
        ring.flatShare * 100f >= t.flatPercent

    /** Whether enough of a block's lines ring flat for the block to count as inside something. */
    fun enclosed(lineFlat: Float?): Boolean = lineFlat != null && lineFlat >= ENCLOSED_LINES
}
