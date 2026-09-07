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
            ScoredBlock(block, ring, verdict(block, ring, page.height, bodyGlyph, thresholds))
        }
    }

    /**
     * Ordered by what makes the difference downstream: a block that is not in a bubble must not be
     * covered at all, a block the recognizer does not believe must not be translated, and only then
     * is it worth asking whether what remains is dialogue or an effect.
     */
    private fun verdict(
        block: TextBlock,
        ring: Ring,
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
        if (ring.iqr > t.ringIqr) return BlockVerdict.OVER_ART
        if (t.minBrightness > 0f && ring.median < t.minBrightness) return BlockVerdict.OVER_ART
        if (block.confidence < t.minConfidence) return BlockVerdict.LOW_CONF

        val glyphPercent = block.glyphPercent(pageHeight)
        val isSfx = glyphPercent >= t.glyphPercent ||
            (
                block.katakanaRatio * 100f >= t.katakanaPercent &&
                    glyphPercent >= t.glyphPercent / 2f
                )
        return if (isSfx) BlockVerdict.SFX else BlockVerdict.DIALOGUE
    }
}
