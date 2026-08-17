package ani.dantotsu.parsers.novel.lnreader

import android.content.Context
import ani.dantotsu.media.novel.novelreader.NovelSentences
import ani.dantotsu.util.Logger
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages LNReader chapter HTML as EPUB.
 *
 * This is the single point where the two novel content models meet. LNReader plugins return a
 * chapter as an HTML fragment; everything downstream in this app — the reader activity, the
 * offline parser and the downloads library — works in EPUB files. Rather than add a second reader
 * that renders HTML directly, and then maintain two sets of reader settings, themes and paging
 * behaviour, the conversion happens here and nothing downstream has to know.
 *
 * Two shapes come out of it, for two different jobs:
 *
 *  - [buildChapter] writes a one-chapter book to the cache for reading online. A whole novel would
 *    be the more natural EPUB, but building one means fetching every chapter first — routinely
 *    hundreds of requests — before a single word could be read.
 *  - [write] streams an arbitrary run of chapters, which is what a download produces. There the
 *    wait is expected and the payoff is a real spine, so the reader can page between chapters
 *    inside the downloaded book.
 */
object LNReaderEpub {

    /** One chapter's worth of content on its way into a book. */
    data class Chapter(val title: String, val bodyHtml: String)

    /**
     * Writes a book containing [chapters] to [out].
     *
     * @param baseUrl origin the chapter HTML came from, used to absolutise relative links
     * @param language BCP 47 code for the text. Not cosmetic: `hyphens: auto` does nothing until
     *   the browser knows which language's dictionary to hyphenate with, so without this the
     *   reader's Hyphenation switch has no effect whatever it is set to.
     */
    fun write(
        out: OutputStream,
        bookTitle: String,
        chapters: List<Chapter>,
        author: String? = null,
        baseUrl: String? = null,
        language: String = "en",
    ) {
        require(chapters.isNotEmpty()) { "an EPUB needs at least one chapter" }
        // Sentence numbering runs across the whole book, so a marker identifies one sentence no
        // matter which chapter document it lives in — the reader highlights by searching every
        // section it has rendered, and duplicated numbers would light up two of them.
        var marker = 0
        val prepared = chapters.mapIndexed { i, chapter ->
            val (body, next) = markSentences(
                sanitise(chapter.bodyHtml, baseUrl), Locale.forLanguageTag(language), marker
            )
            marker = next
            PreparedChapter(
                fileName = "chapter${i + 1}.xhtml",
                id = "chapter${i + 1}",
                title = chapter.title,
                body = body,
            )
        }

        ZipOutputStream(out.buffered()).use { zip ->
            // The mimetype entry must be first and STORED, uncompressed, or readers reject the
            // file. Everything after it may be deflated normally.
            writeStored(zip, "mimetype", MIMETYPE)
            writeDeflated(zip, "META-INF/container.xml", CONTAINER_XML)
            writeDeflated(zip, "OEBPS/style.css", STYLE_CSS)
            writeDeflated(zip, "OEBPS/content.opf", opf(bookTitle, author, prepared, language))
            writeDeflated(zip, "OEBPS/nav.xhtml", nav(prepared))
            prepared.forEach {
                writeDeflated(
                    zip, "OEBPS/${it.fileName}", chapterXhtml(it.title, it.body, language)
                )
            }
        }
    }

    /**
     * A single chapter written to the cache, for reading online.
     *
     * @param footerHtml an "End of … / Next: …" block for the multi-chapter reader, appended to the
     *   chapter's own body. Deliberately *inside* it rather than added as a second spine entry: a
     *   section of its own is one the library cannot measure, so it reports a null progress
     *   fraction and the reader throws on every relocation. Drawing it over the page instead put it
     *   on top of text that was still being read, and left it invisible to anyone who scrolls by
     *   dragging. As part of the chapter it is simply what comes after the last line.
     */
    fun buildChapter(
        context: Context,
        novelTitle: String,
        chapterTitle: String,
        bodyHtml: String,
        author: String? = null,
        baseUrl: String? = null,
        language: String = "en",
        footerHtml: String? = null,
    ): File {
        val target = File(cacheDir(context), "${slug(novelTitle)}-${slug(chapterTitle)}.epub")
        target.outputStream().use { out ->
            write(
                out = out,
                // The chapter is the book here, so its title is what the reader shows.
                bookTitle = chapterTitle,
                chapters = listOf(Chapter(chapterTitle, bodyHtml + footerHtml.orEmpty())),
                author = author ?: novelTitle,
                baseUrl = baseUrl,
                language = language,
            )
        }
        return target
    }

    /**
     * The "End of … / Next: …" block that closes a chapter in the multi-chapter reader.
     *
     * Centred on each element rather than on the wrapper, since the reader's own stylesheet sets
     * paragraph alignment and a rule on the element beats alignment inherited from a parent. No
     * colours of its own either — the page is painted from the user's chosen theme, and anything
     * fixed here would be invisible on half of them.
     *
     * Carries [TRANSITION_CLASS] so text-to-speech can leave it out: it is a signpost for the eye,
     * and read aloud between two chapters it only says what the next chapter is about to say.
     */
    fun transitionFooter(endLabel: String, nextLabel: String): String = document(
        """<div class="$TRANSITION_CLASS">""",
        """<hr style="width:64px; margin:3em auto 1.4em; border:0; border-top:1px solid currentColor; opacity:0.4" />""",
        """<p style="text-align:center; font-size:0.9em; opacity:0.6; margin:0">""" +
                escape(endLabel) + "</p>",
        """<p style="text-align:center; font-size:1.15em; font-weight:bold; margin:0.4em 0 2em">""" +
                escape(nextLabel) + "</p>",
        "</div>",
    )

    /** Marks the block written by [transitionFooter]; see [ani.dantotsu.media.novel.novelreader.NovelTtsText]. */
    const val TRANSITION_CLASS = "ln-transition"

    /**
     * A whole book written to the cache, for reading a run that was downloaded as HTML.
     *
     * The reader only opens EPUB, so a saved HTML run is repackaged on the way in. The result is
     * cached under the run's name and reused, so paging back into a book does not rebuild it.
     */
    fun buildBook(
        context: Context,
        bookTitle: String,
        chapters: List<Chapter>,
        author: String? = null,
    ): File {
        val target = File(cacheDir(context), "${slug(bookTitle)}-book.epub")
        target.outputStream().use { out ->
            write(out = out, bookTitle = bookTitle, chapters = chapters, author = author)
        }
        return target
    }

    /**
     * The same content as a standalone HTML document, for downloads saved in that format.
     *
     * EPUB is what the reader opens, but it is an archive: a reader that wants the text in anything
     * else — a browser, an editor, a text-to-speech tool — has to unpack it first. HTML downloads
     * exist for that, so this is a plain file anything can open.
     *
     * Each chapter is wrapped in a marked `<section>` so [chaptersFrom] can read the document back
     * apart. That makes the saved file self-describing: nothing else has to record what is in it,
     * and a run downloaded as HTML still opens in the reader.
     */
    fun htmlDocument(bookTitle: String, chapters: List<Chapter>, baseUrl: String? = null): String {
        val sections = chapters.joinToString("\n") { chapter ->
            document(
                """<section class="chapter" data-chapter-title="${escape(chapter.title)}">""",
                "<h1>${escape(chapter.title)}</h1>",
                sanitise(chapter.bodyHtml, baseUrl),
                "</section>",
            )
        }
        // Built line by line for the same reason the EPUB documents are; see [document].
        return document(
            "<!DOCTYPE html>",
            """<html lang="en">""",
            "<head>",
            """<meta charset="utf-8" />""",
            """<meta name="viewport" content="width=device-width, initial-scale=1" />""",
            "<title>${escape(bookTitle)}</title>",
            "<style>",
            STYLE_CSS,
            "</style>",
            "</head>",
            "<body>",
            sections,
            "</body>",
            "</html>",
        )
    }

    /**
     * Reads a document written by [htmlDocument] back into chapters.
     *
     * Falls back to treating the whole body as one chapter, so a file that has been edited by hand
     * — or one written by an older version — still opens rather than coming up empty.
     */
    fun chaptersFrom(html: String, fallbackTitle: String): List<Chapter> {
        val doc = Jsoup.parse(html)
        val sections = doc.select("section.chapter")
        if (sections.isEmpty()) {
            val body = doc.body().html()
            return if (body.isBlank()) emptyList()
            else listOf(Chapter(doc.title().ifBlank { fallbackTitle }, body))
        }
        return sections.map { section ->
            val title = section.attr("data-chapter-title")
                .ifBlank { section.selectFirst("h1")?.text().orEmpty() }
                .ifBlank { fallbackTitle }
            // The heading is re-added when the chapter is written back out, so drop it here or a
            // round trip through HTML would double it.
            section.selectFirst("h1")?.remove()
            Chapter(title, section.html())
        }
    }

    /** Cached books are disposable; clearing keeps the directory from growing without bound. */
    fun clearCache(context: Context) {
        cacheDir(context).listFiles()?.forEach { it.delete() }
    }

    private fun cacheDir(context: Context) =
        File(context.cacheDir, "lnreader_chapters").apply { if (!exists()) mkdirs() }

    private data class PreparedChapter(
        val fileName: String,
        val id: String,
        val title: String,
        val body: String,
    )

    /**
     * Turns an arbitrary fragment into XHTML the reader will accept.
     *
     * Two things matter here. EPUB content is XHTML, so unclosed tags that browsers forgive will
     * break rendering — Jsoup's XML output settings fix that. And plugin HTML carries scripts,
     * iframes, ads and site chrome that have no business in a book, so those are dropped and
     * relative sources are made absolute while the origin is still known.
     */
    /** The attribute a sentence is tagged with, and what the reader highlights by. */
    const val SENTENCE_ATTRIBUTE = "data-tts"

    /** Blocks that read as their own paragraph, and so are split into sentences separately. */
    private const val BLOCKS =
        "p, h1, h2, h3, h4, h5, h6, li, blockquote, dd, dt, td, pre, figcaption"

    /**
     * Tags every sentence in [bodyHtml] so it can be highlighted while it is spoken.
     *
     * Done here, when the book is written, rather than by matching text at playback: the words on
     * the page and the words in the voice then cannot disagree, because they are the same span.
     * Matching spoken text back against the document would have to cope with a paragraph that lays
     * its whitespace out differently from how it reads, and with a sentence the engine chunked.
     *
     * @return the marked-up body, and the first sentence number still free after it
     */
    private fun markSentences(
        bodyHtml: String,
        locale: Locale,
        startIndex: Int,
    ): Pair<String, Int> = runCatching {
        val doc = Jsoup.parseBodyFragment(bodyHtml)
        doc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
            .prettyPrint(false)

        var next = startIndex
        doc.body().select(BLOCKS)
            // `select` matches the element it is called on as well as its descendants, so a block
            // holds another only when something other than itself comes back. Getting this wrong
            // discards every block, which is not obvious from the result: the text is still there,
            // it has simply stopped being divided into paragraphs.
            .filter { block -> block.select(BLOCKS).none { it !== block } }
            // The end-of-chapter signpost is not read aloud, so it has nothing to highlight.
            .filter { it.closest(".$TRANSITION_CLASS") == null }
            .forEach { next = markBlock(it, locale, next) }

        doc.body().html() to next
    }.getOrElse {
        // Marking is an enhancement; a book that cannot be marked is still a book to read.
        Logger.log("LNReader: could not mark sentences for speech — ${it.message}")
        bodyHtml to startIndex
    }

    /**
     * Wraps each sentence of one paragraph in its own span.
     *
     * Works on the paragraph's text nodes rather than its text, because a sentence routinely runs
     * through inline markup — emphasis, a link, a ruby annotation — and cutting the text alone
     * would mean rebuilding that markup. Splitting the text nodes instead leaves the markup exactly
     * where it was; a sentence spanning three of them simply becomes three spans sharing a number,
     * which highlights as one because the reader selects them all.
     */
    private fun markBlock(block: Element, locale: Locale, startIndex: Int): Int {
        val nodes = ArrayList<TextNode>()
        NodeTraversor.traverse({ node, _ -> if (node is TextNode) nodes.add(node) }, block)
        if (nodes.isEmpty()) return startIndex

        val full = nodes.joinToString("") { it.wholeText }
        if (full.isBlank()) return startIndex
        val ranges = NovelSentences.boundaries(full, locale)
        if (ranges.isEmpty()) return startIndex

        var position = 0
        // The ranges cover the paragraph end to end and the nodes are walked in order, so the
        // sentence a given character belongs to only ever moves forwards.
        var cursor = 0
        nodes.forEach { node ->
            val length = node.wholeText.length
            val nodeStart = position
            position += length
            if (length == 0) return@forEach

            var taken = 0
            var current = node
            while (taken < length) {
                while (cursor < ranges.lastIndex && nodeStart + taken > ranges[cursor].last) cursor++
                // Where this sentence stops inside this node, or the end of the node.
                val stop = minOf(length, ranges[cursor].last + 1 - nodeStart)
                val piece: TextNode
                if (stop < length) {
                    val tail = current.splitText(stop - taken)
                    piece = current
                    current = tail
                } else {
                    piece = current
                }
                // Whitespace between two paragraphs' markup belongs to no sentence worth marking,
                // and wrapping it would put an empty highlight between them.
                if (piece.wholeText.isNotBlank()) {
                    piece.wrap("""<span $SENTENCE_ATTRIBUTE="${startIndex + cursor}"></span>""")
                }
                taken = stop
            }
        }
        return startIndex + ranges.size
    }

    private fun sanitise(fragment: String, baseUrl: String?): String {
        val doc = Jsoup.parseBodyFragment(fragment, baseUrl.orEmpty())
        doc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
            .prettyPrint(false)

        doc.select("script, style, iframe, noscript, form, input, button, ins, object, embed")
            .remove()

        if (!baseUrl.isNullOrBlank()) {
            doc.select("img[src]").forEach { it.attr("src", it.absUrl("src").ifBlank { it.attr("src") }) }
            doc.select("a[href]").forEach { it.attr("href", it.absUrl("href").ifBlank { it.attr("href") }) }
        }
        // An image with no usable source renders as a broken frame; the alt text is more use.
        doc.select("img").forEach { if (it.attr("src").isBlank()) it.remove() }

        return doc.body().html()
    }

    /**
     * Joins the lines of a generated document, with no indentation of its own.
     *
     * Deliberately not a raw string with `trimIndent()`. That runs on the *finished* string, after
     * interpolation, and removes the smallest indent it finds across every line — including the
     * lines of whatever was interpolated in. Inject a multi-line value indented less than the
     * template around it and the template keeps the difference, which leaves the XML declaration
     * with whitespace in front of it. A declaration that is not the very first thing in the file
     * makes the document unparseable, and an EPUB whose `content.opf` will not parse does not open.
     *
     * That is not hypothetical: it is why a book with one chapter worked and a book with two did
     * not. One manifest entry is interpolated inline and shares the template's indent; two arrive
     * as separate lines carrying their own, smaller one.
     */
    private fun document(vararg lines: String) = lines.joinToString("\n")

    private fun chapterXhtml(title: String, body: String, language: String) = document(
        """<?xml version="1.0" encoding="utf-8"?>""",
        "<!DOCTYPE html>",
        """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops""" +
                """ lang="${escape(language)}" xml:lang="${escape(language)}">""",
        "<head>",
        "<title>${escape(title)}</title>",
        """<meta charset="utf-8" />""",
        """<link rel="stylesheet" type="text/css" href="style.css" />""",
        "</head>",
        "<body>",
        """<section epub:type="chapter">""",
        "<h1>${escape(title)}</h1>",
        body,
        "</section>",
        "</body>",
        "</html>",
    )

    private fun nav(chapters: List<PreparedChapter>) = document(
        """<?xml version="1.0" encoding="utf-8"?>""",
        "<!DOCTYPE html>",
        """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">""",
        """<head><title>Contents</title><meta charset="utf-8" /></head>""",
        "<body>",
        """<nav epub:type="toc" id="toc">""",
        "<ol>",
        chapters.joinToString("\n") {
            """<li><a href="${it.fileName}">${escape(it.title)}</a></li>"""
        },
        "</ol>",
        "</nav>",
        "</body>",
        "</html>",
    )

    private fun opf(
        bookTitle: String,
        author: String?,
        chapters: List<PreparedChapter>,
        language: String,
    ) = document(
        """<?xml version="1.0" encoding="utf-8"?>""",
        """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">""",
        """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""",
        """<dc:identifier id="bookid">${escape(slug(bookTitle))}</dc:identifier>""",
        "<dc:title>${escape(bookTitle)}</dc:title>",
        "<dc:creator>${escape(author ?: bookTitle)}</dc:creator>",
        "<dc:language>${escape(language)}</dc:language>",
        """<meta property="dcterms:modified">1970-01-01T00:00:00Z</meta>""",
        "</metadata>",
        "<manifest>",
        """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav" />""",
        """<item id="css" href="style.css" media-type="text/css" />""",
        chapters.joinToString("\n") {
            """<item id="${it.id}" href="${it.fileName}" media-type="application/xhtml+xml" />"""
        },
        "</manifest>",
        "<spine>",
        chapters.joinToString("\n") { """<itemref idref="${it.id}" />""" },
        "</spine>",
        "</package>",
    )

    /**
     * Intentionally minimal: the reader applies the user's font, size, theme, margins, alignment
     * and hyphenation on top, so anything opinionated here would fight those settings — and win,
     * since this is linked from the document while the reader's own rules are injected into it.
     * A `p { text-align: justify }` rule lived here and did exactly that: the Justify Text switch
     * appeared to do nothing, because turning it off left this behind.
     */
    private val STYLE_CSS = """
        body { margin: 0 1em; }
        h1 { font-size: 1.2em; margin: 1em 0; }
        img { max-width: 100%; height: auto; }
    """.trimIndent()

    /**
     * Marks the one sentence being spoken; see [SENTENCE_ATTRIBUTE].
     *
     * Only a handle for finding it again — the colour is applied inline by the reader, so that
     * highlighting does not depend on this stylesheet having loaded. See
     * [ani.dantotsu.media.novel.novelreader.NovelReaderActivity.highlightSentence].
     */
    const val CURRENT_SENTENCE_CLASS = "tts-current"

    private const val MIMETYPE = "application/epub+zip"

    private val CONTAINER_XML = """
        <?xml version="1.0" encoding="utf-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml" />
          </rootfiles>
        </container>
    """.trimIndent()

    private fun writeStored(zip: ZipOutputStream, name: String, content: String) {
        val bytes = content.toByteArray()
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zip.setMethod(ZipOutputStream.STORED)
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
        zip.setMethod(ZipOutputStream.DEFLATED)
    }

    private fun writeDeflated(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun slug(s: String) =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifBlank { "chapter" }
}
