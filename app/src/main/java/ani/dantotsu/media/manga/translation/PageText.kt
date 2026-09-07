package ani.dantotsu.media.manga.translation

import android.graphics.Rect
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * One run of text on a page, in page pixels.
 *
 * Everything here is measured rather than assumed, and every field earned its place by being the
 * thing that decided a real case: [glyphPx] separates sound effects and furigana from dialogue,
 * [confidence] separates text the recognizer believes from text it does not, [kanaOnly] pairs with
 * glyph size to spot ruby, and [reordered] records that a vertical block's columns had to be put
 * back into reading order — without which a bubble reaches the translator inside out.
 */
data class TextBlock(
    val id: Int,
    val text: String,
    val box: Rect,
    val confidence: Float,
    val angle: Float,
    val glyphPx: Float,
    val katakanaRatio: Float,
    val kanaOnly: Boolean,
    val lineCount: Int,
    val reordered: Boolean,
    val symbolsAvailable: Boolean,
    /** Drawn by hand rather than found by the page pass. */
    val synthetic: Boolean = false,
    /** Empty until a [TextTranslator] has run. */
    val translation: String = "",
) {
    /** Glyph height as a share of page height — the page-size-independent form. */
    fun glyphPercent(pageHeight: Int) = glyphPx / pageHeight * 100f

    /** True for a vertical column, which reads top to bottom with columns advancing right to left. */
    val vertical get() = angle > VERTICAL_ANGLE

    companion object {
        /** Above this line angle a run is treated as a vertical column. */
        const val VERTICAL_ANGLE = 85f
    }
}

/** What a block should have done to it. */
enum class BlockVerdict {
    /** In a bubble, ordinary glyph size, legible — cover it and write the translation in. */
    DIALOGUE,

    /** In a bubble but reads as an effect — covering is safe, translating may not be wanted. */
    SFX,

    /** Recognised, but the recognizer does not believe it. Translating this yields nonsense. */
    LOW_CONF,

    /** Not inside anything uniform. Covering this is what destroys artwork. */
    OVER_ART,

    /**
     * Ruby glossing the kanji beside it. Covered but never translated: it says the same thing as
     * the characters it annotates, so translating duplicates the bubble, while leaving it visible
     * strands Japanese against the English that replaced what it was glossing.
     */
    FURIGANA,
    ;

    /** Whether a translation should be requested for this block. */
    val translatable get() = this == DIALOGUE || this == SFX

    /** Whether the page beneath this block should be painted over. */
    val covered get() = translatable || this == FURIGANA
}

/**
 * What the ring sampled around a block looks like.
 *
 * [median] and [iqr] are what verdicts use; [mean] and [stdDev] are carried for comparison, and
 * have earned it — a block reading `iqr 1 (sd 85)` is the signature of a box overlapping something
 * dark and small, which no quartile range can see by design.
 *
 * [median] doubles as the colour a block should be painted with, which is the right answer for an
 * inverted or toned bubble as much as a white one.
 */
data class Ring(
    val median: Float,
    val iqr: Float,
    val mean: Float,
    val stdDev: Float,
)

/** A block with everything that depends on the current thresholds. */
data class ScoredBlock(
    val block: TextBlock,
    val ring: Ring,
    val verdict: BlockVerdict,
)

/**
 * The tunable part of the verdict, calibrated in the OCR screen and read by the reader.
 *
 * Defaults live on the preferences rather than here, so the two callers cannot drift apart.
 */
data class DetectionThresholds(
    val glyphPercent: Float,
    val minConfidence: Float,
    val katakanaPercent: Float,
    val minBrightness: Float,
    val ringIqr: Float,
    val ringPad: Float,
) {
    companion object {
        fun fromPrefs() = DetectionThresholds(
            glyphPercent = PrefManager.getVal(PrefName.OcrSfxGlyphPercent),
            minConfidence = PrefManager.getVal(PrefName.OcrMinConfidence),
            katakanaPercent = PrefManager.getVal(PrefName.OcrKatakanaPercent),
            minBrightness = PrefManager.getVal(PrefName.OcrMinRingMedian),
            ringIqr = PrefManager.getVal(PrefName.OcrRingIqr),
            ringPad = PrefManager.getVal(PrefName.OcrRingPad),
        )
    }
}

/** The scripts the recognizer can be asked for. */
enum class TextScript(val label: String) {
    JAPANESE("Japanese"),
    KOREAN("Korean"),
    CHINESE("Chinese"),
    LATIN("Latin"),
}
