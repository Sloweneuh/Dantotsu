package ani.dantotsu.media.novel.novelreader

import android.content.Context
import ani.dantotsu.parsers.novel.lnreader.LNReaderEpub
import ani.dantotsu.parsers.novel.lnreader.LNReaderNovel
import org.jsoup.Jsoup
import java.io.File
import java.text.Normalizer
import java.util.zip.ZipFile

/**
 * Search through the chapters of a streamed novel that aren't open in the reader.
 *
 * Only the open chapter is in the reader's page, so the others are read from the books already
 * packaged for them (see [LNReaderEpub.cachedChapter]) — the chapters read so far, or fetched for
 * read-aloud. Chapters never loaded have no text on the device and are skipped; the caller says how
 * many were covered.
 *
 * Matching ignores case and accents, as foliate-js does in the page, so the n-th match found here
 * is the n-th the reader finds once the chapter is open — which is how a match is gone to.
 */
object NovelChapterSearch {

    class Hit(val chapterIndex: Int, val occurrence: Int, val pre: String, val match: String, val post: String)

    class ChapterHits(val chapterIndex: Int, val label: String, val hits: List<Hit>)

    private const val CONTEXT = 40
    private const val MAX_PER_CHAPTER = 50

    /** The chapters (by index) that have a cached book to search, other than [skip]. */
    fun searchable(context: Context, novel: LNReaderNovel, skip: Int): List<Int> =
        novel.chapters.indices.filter { i ->
            i != skip && LNReaderEpub.cachedChapter(context, novel.name, novel.chapters[i].name) != null
        }

    /** Searches chapter [index]'s cached book; null when it has none. Blocking: call off the main thread. */
    fun search(context: Context, novel: LNReaderNovel, index: Int, query: String): ChapterHits? {
        val chapter = novel.chapters.getOrNull(index) ?: return null
        val file = LNReaderEpub.cachedChapter(context, novel.name, chapter.name) ?: return null
        val text = runCatching { textOf(file) }.getOrNull() ?: return null
        val hits = find(text, query).take(MAX_PER_CHAPTER).mapIndexed { n, range ->
            Hit(
                chapterIndex = index,
                occurrence = n,
                pre = text.substring((range.first - CONTEXT).coerceAtLeast(0), range.first),
                match = text.substring(range.first, range.last + 1),
                post = text.substring(range.last + 1, (range.last + 1 + CONTEXT).coerceAtMost(text.length)),
            )
        }
        return ChapterHits(index, chapter.name, hits)
    }

    /** The book's text in reading order: every content document's body, as plain text. */
    private fun textOf(file: File): String = ZipFile(file).use { zip ->
        zip.entries().asSequence()
            .filter { (it.name.endsWith(".xhtml") || it.name.endsWith(".html")) && !it.name.contains("nav") }
            .sortedBy { it.name }
            .joinToString("\n") { entry ->
                zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "").body().text() }
            }
    }

    /**
     * Where [query] occurs in [text], ignoring case and accents. Folded a character at a time with
     * a map back, so the ranges are in [text]'s own positions for the excerpts.
     */
    private fun find(text: String, query: String): List<IntRange> {
        val needle = fold(query.trim())
        if (needle.isEmpty()) return emptyList()
        val folded = StringBuilder()
        val origin = ArrayList<Int>()
        text.forEachIndexed { i, c ->
            for (f in fold(c.toString())) {
                folded.append(f)
                origin += i
            }
        }
        val out = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val at = folded.indexOf(needle, from)
            if (at < 0) break
            out += origin[at]..origin[at + needle.length - 1]
            from = at + needle.length
        }
        return out
    }

    private val marks = Regex("\\p{M}+")

    private fun fold(s: String) = marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()
}
