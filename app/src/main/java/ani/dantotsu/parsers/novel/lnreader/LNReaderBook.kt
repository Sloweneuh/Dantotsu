package ani.dantotsu.parsers.novel.lnreader

import android.content.Context
import ani.dantotsu.R
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import java.io.File

/**
 * Turns one chapter of a novel into the book the reader opens.
 *
 * A plugin serves a chapter as HTML, and the reader only opens EPUB, so every read passes through
 * here. One chapter per book, always: the reader is a WebView that reloads the whole document when
 * it is handed a new one, so packaging several chapters together buys paging between them but costs
 * a fetch of every chapter before the first word appears — and leaves the reader with a different
 * book, and a different set of positions, each time it moves on.
 *
 * The multi-chapter reader is instead what happens at the *end* of a chapter: see
 * [ani.dantotsu.media.novel.novelreader.NovelReaderActivity]. Finishing one loads the next by
 * itself, which is what [PrefName.ContinuousMultiChapter] promises — "read through all chapters
 * without stopping" — with no extra work up front and nothing to go wrong before anything is shown.
 */
object LNReaderBook {

    /** Whether finishing a chapter should move on to the next one on its own. */
    fun continuous(): Boolean = PrefManager.getVal(PrefName.ContinuousMultiChapter)

    /**
     * Fetches the chapter at [index] and writes it as a book.
     *
     * In continuous mode the chapter closes with a block naming what comes next, so the text does
     * not simply stop — see [LNReaderEpub.transitionFooter].
     */
    suspend fun build(
        context: Context,
        parser: LNReaderParser,
        novel: LNReaderNovel,
        index: Int,
    ): Result<File> = runCatching {
        val chapter = novel.chapters.getOrNull(index)
            ?: throw IllegalStateException("No chapter at $index")
        val html = parser.loadChapterHtml(chapter.path)
        if (html.isBlank()) throw IllegalStateException("Empty chapter")
        LNReaderEpub.buildChapter(
            context = context,
            novelTitle = novel.name,
            chapterTitle = chapter.name,
            bodyHtml = html,
            author = novel.author,
            baseUrl = parser.resolve(chapter.path),
            // The plugin names its language in full ("English"); the document needs the code.
            language = LanguageMapper.getLanguageCode(parser.language).takeIf { it != "all" } ?: "en",
            footerHtml = novel.chapters.getOrNull(index + 1)?.takeIf { continuous() }?.let { next ->
                LNReaderEpub.transitionFooter(
                    endLabel = context.getString(R.string.chapter_transition_end, chapter.name),
                    nextLabel = context.getString(R.string.chapter_transition_next, next.name),
                )
            },
        )
    }
}
