package ani.dantotsu.media.novel.novelreader

import java.text.BreakIterator
import java.util.Locale

/**
 * Where one spoken sentence ends and the next begins.
 *
 * Shared deliberately. The book is marked up with one span per sentence when it is packaged, and
 * the same sentences are read back out of those spans when it is spoken — highlighting only lines
 * up with the voice because both sides derive from this. Two implementations that agreed today
 * would disagree the first time either was adjusted.
 *
 * Blocks that this covers are the ones a reader would call paragraphs; splitting *between* them is
 * the caller's business.
 */
object NovelSentences {

    /** Longest string a speech engine accepts in one call; longer sentences are broken up. */
    private const val MAX_UTTERANCE = 3500

    /**
     * Splits [text] into contiguous ranges, together covering all of it.
     *
     * Contiguous rather than trimmed: the ranges are used to cut a paragraph's text nodes into
     * spans, and a gap between two of them would be text belonging to no sentence — which is text
     * that could never be highlighted, and would go missing if it were ever read back. Whitespace
     * therefore rides along with the sentence before it, and is trimmed when the words are wanted.
     *
     * [BreakIterator] rather than punctuation matching: what ends a sentence is a property of the
     * language, and a novel translated from Japanese is full of punctuation a full-stop rule reads
     * wrongly.
     */
    fun boundaries(text: String, locale: Locale): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val iterator = BreakIterator.getSentenceInstance(locale)
        iterator.setText(text)

        val ranges = ArrayList<IntRange>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            chunk(start, end).forEach { ranges += it }
            start = end
            end = iterator.next()
        }
        // BreakIterator ends at the last character; anything after it would otherwise be orphaned.
        if (start < text.length) chunk(start, text.length).forEach { ranges += it }
        return ranges
    }

    /**
     * Splits a range no engine would accept into ones it will.
     *
     * `TextToSpeech` drops an utterance over its input limit rather than truncating it, so a single
     * unpunctuated block — which novel sources do produce — would otherwise go silently missing.
     * Cut on length alone: there is no punctuation to aim for, that being the reason it is here.
     */
    private fun chunk(start: Int, end: Int): List<IntRange> {
        if (end - start <= MAX_UTTERANCE) return listOf(start until end)
        val out = ArrayList<IntRange>()
        var from = start
        while (end - from > MAX_UTTERANCE) {
            val to = from + MAX_UTTERANCE
            out += from until to
            from = to
        }
        if (from < end) out += from until end
        return out
    }

    /** Collapses the whitespace a document is laid out with, leaving the words to be spoken. */
    fun spoken(raw: String): String = raw.replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")
}
