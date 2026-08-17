package ani.dantotsu.media.novel.novelreader

import android.content.Context
import android.net.Uri
import ani.dantotsu.parsers.novel.lnreader.LNReaderEpub
import ani.dantotsu.util.Logger
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * One thing the speech engine is asked to say.
 *
 * @param start where this sentence begins, counted in characters from the start of the book. The
 *   reader library measures position as a fraction of the whole, so a character offset is what
 *   converts between "which sentence" and "where on the page" in both directions.
 * @param marker the number this sentence is tagged with in the document, which is what the reader
 *   highlights by. Null for a book this app did not package, where there is nothing tagged to
 *   highlight — the sentence is still spoken, just not lit up.
 */
data class TtsSentence(
    val text: String,
    val start: Int,
    val marker: Int?,
)

/**
 * A whole book, flattened into the order it would be read aloud.
 *
 * Deliberately a plain list rather than anything tied to the reader's own pagination: what a page
 * holds changes with the font size, and speech does not care. The only thing shared with the view
 * is the character offset, which is stable.
 */
class TtsScript(val sentences: List<TtsSentence>, val totalChars: Int) {

    val isEmpty: Boolean get() = sentences.isEmpty()

    val size: Int get() = sentences.size

    operator fun get(index: Int): TtsSentence = sentences[index]

    fun getOrNull(index: Int): TtsSentence? = sentences.getOrNull(index)

    /** How far into the book [index] sits, in the units the reader's slider and `gotoFraction` use. */
    fun fractionOf(index: Int): Double {
        if (totalChars <= 0) return 0.0
        val sentence = sentences.getOrNull(index) ?: return 0.0
        return (sentence.start.toDouble() / totalChars).coerceIn(0.0, 1.0)
    }

    /**
     * The sentence to start at for a reader sitting [fraction] of the way through.
     *
     * Used when speech is started from wherever the user had read to. The mapping is approximate —
     * the reader's fraction comes from laid-out content and this one from characters — but it lands
     * within a paragraph or so, which is close enough to pick up from.
     */
    fun indexAtFraction(fraction: Double): Int {
        if (isEmpty) return 0
        val target = (fraction.coerceIn(0.0, 1.0) * totalChars).toInt()
        val found = sentences.indexOfLast { it.start <= target }
        return found.coerceIn(0, sentences.lastIndex)
    }

    companion object {
        val EMPTY = TtsScript(emptyList(), 0)
    }
}

/**
 * Reads the text out of the book the reader has open.
 *
 * Speech needs the words in reading order, which the reader itself will not hand over: it renders
 * inside a WebView, and the build of foliate bundled with the reader library has no speech support
 * to borrow. Rather than reach into that WebView's internals — undocumented, and unverifiable
 * without driving the UI — the text is taken from the same EPUB the reader was given. Every novel
 * in the app passes through [ani.dantotsu.parsers.novel.lnreader.LNReaderEpub], so that file always
 * exists and its contents are exactly what is on screen.
 */
object NovelTtsText {

    /** Text the engine should not be asked to read: the reader's own end-of-chapter signpost. */
    private val SKIPPED = ".${LNReaderEpub.TRANSITION_CLASS}"

    /** Blocks that read as their own paragraph. */
    private const val BLOCKS = "p, h1, h2, h3, h4, h5, h6, li, blockquote, dd, dt, td, pre, figcaption"

    fun read(context: Context, uri: Uri, locale: Locale): TtsScript = runCatching {
        val documents = context.contentResolver.openInputStream(uri)?.use { readTextEntries(it) }
            ?: return TtsScript.EMPTY
        build(spineOrder(documents), locale)
    }.getOrElse {
        Logger.log("Novel TTS: could not read the book's text — ${it.message}")
        Logger.log(it)
        TtsScript.EMPTY
    }

    fun read(context: Context, file: File, locale: Locale): TtsScript =
        read(context, Uri.fromFile(file), locale)

    /**
     * Pulls the text out of a zip without unpacking it.
     *
     * Only markup is kept. A downloaded novel can carry cover art and illustrations running to
     * megabytes, and none of it can be spoken, so holding it in memory to throw it away would be
     * the one part of this that could not scale with the length of a book.
     */
    private fun readTextEntries(stream: java.io.InputStream): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                val wanted = name.endsWith(".xhtml", true) || name.endsWith(".html", true) ||
                        name.endsWith(".htm", true) || name.endsWith(".opf", true) ||
                        name.endsWith(".xml", true) || name.endsWith(".ncx", true)
                if (wanted) out[name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        return out
    }

    /**
     * The book's documents in the order the spine gives them.
     *
     * A book with no readable package file still reads: the documents fall back to the order they
     * were stored in, which for anything this app writes is the reading order anyway. That matters
     * for an EPUB downloaded from a source rather than built here, where the packaging is whatever
     * the site produced.
     */
    private fun spineOrder(documents: Map<String, String>): List<String> {
        val fallback = { documents.filterKeys { it.endsWith(".xhtml", true) || it.endsWith(".html", true) }.values.toList() }

        val containerXml = documents["META-INF/container.xml"] ?: return fallback()
        val opfPath = Jsoup.parse(containerXml, "", org.jsoup.parser.Parser.xmlParser())
            .selectFirst("rootfile")?.attr("full-path")?.takeIf { it.isNotBlank() } ?: return fallback()
        val opf = documents[opfPath] ?: return fallback()

        val pkg = Jsoup.parse(opf, "", org.jsoup.parser.Parser.xmlParser())
        val hrefs = pkg.select("manifest > item").associate { it.attr("id") to it.attr("href") }
        val base = opfPath.substringBeforeLast('/', "")
        val ordered = pkg.select("spine > itemref").mapNotNull { ref ->
            val href = hrefs[ref.attr("idref")] ?: return@mapNotNull null
            documents[resolve(base, href)]
        }
        return ordered.ifEmpty { fallback() }
    }

    /** Resolves a manifest href against the directory its package file sits in. */
    private fun resolve(base: String, href: String): String {
        val path = href.substringBefore('#')
        if (base.isEmpty()) return path
        val parts = ArrayDeque<String>()
        base.split('/').filter { it.isNotEmpty() }.forEach { parts.addLast(it) }
        path.split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(it)
            }
        }
        return parts.joinToString("/")
    }

    private fun build(documents: List<String>, locale: Locale): TtsScript {
        val sentences = ArrayList<TtsSentence>()
        var offset = 0

        documents.forEach { html ->
            val doc = Jsoup.parse(html)
            doc.select("script, style, $SKIPPED").remove()
            val marked = doc.body().select("[${LNReaderEpub.SENTENCE_ATTRIBUTE}]")
            val texts =
                if (marked.isNotEmpty()) fromMarkers(marked)
                else fromBlocks(doc.body(), locale)

            texts.forEach { (marker, text) ->
                sentences += TtsSentence(text, offset, marker)
                offset += text.length + 1
            }
        }
        return TtsScript(sentences, offset.coerceAtLeast(1))
    }

    /**
     * Reads the sentences a book was marked up with when it was packaged.
     *
     * The preferred path, and the only one that can be highlighted: the numbers come from the
     * document itself, so the span lit up on the page is by definition the one being spoken. A
     * sentence running through inline markup was split across several spans sharing a number, and
     * is joined back together here.
     */
    private fun fromMarkers(marked: Elements): List<Pair<Int?, String>> {
        val grouped = LinkedHashMap<Int, StringBuilder>()
        marked.forEach { span ->
            val id = span.attr(LNReaderEpub.SENTENCE_ATTRIBUTE).toIntOrNull() ?: return@forEach
            grouped.getOrPut(id) { StringBuilder() }.append(span.wholeText())
        }
        return grouped.entries
            .sortedBy { it.key }
            .map { it.key to NovelSentences.spoken(it.value.toString()) }
            .filter { it.second.isNotBlank() }
    }

    /**
     * Splits a book that carries no markers, which is any EPUB this app did not write.
     *
     * There is nothing on the page to highlight in that case, so these sentences carry no number
     * and the reader simply does not light anything up while they are read.
     */
    private fun fromBlocks(body: Element, locale: Locale): List<Pair<Int?, String>> {
        val blocks = body.select(BLOCKS)
            // `select` matches the element it is called on as well as its descendants, so a block
            // contains another only when something other than itself comes back. Testing for an
            // empty result instead drops every block and silently falls through to the whole body
            // as one — which still speaks, in one long run with no paragraph divisions at all.
            .filter { block -> block.select(BLOCKS).none { it !== block } }
            .map { it.text() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOfNotNull(body.text().takeIf { it.isNotBlank() }) }

        return blocks.flatMap { text ->
            NovelSentences.boundaries(text, locale)
                .map { NovelSentences.spoken(text.substring(it.first, it.last + 1)) }
                .filter { it.isNotBlank() }
                .map { null to it }
        }
    }
}
