package ani.dantotsu.parsers.novel.lnreader

import android.content.Context
import ani.dantotsu.R
import ani.dantotsu.media.novel.translation.NovelTranslation
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

        // The plugin names its language in full ("English"); the document needs the code.
        val sourceCode = LanguageMapper.getLanguageCode(parser.language)
            .takeIf { it != "all" } ?: "en"

        // Machine translation, where it is switched on and there is anything to gain by it. Done
        // here rather than in the reader because the reader is a WebView over a packaged book: this
        // is the last point at which the words are still ours to change, and changing them here
        // leaves pagination, theming and the speech markers working as they always did.
        val translated = NovelTranslation.worthDoing(sourceCode)
        val body = NovelTranslation.translate(
            context = context,
            // Plugin plus chapter path: the chapter's own address within the source it came from,
            // which is the same string on every launch and different for every chapter.
            chapterId = "${parser.plugin.id}:${chapter.path}",
            bodyHtml = html,
            sourceLanguage = sourceCode,
        ) {
            // Only on a real translation, never on a cache hit. Said on the main thread
            // explicitly: this whole function runs on IO in both of its callers, and a Snackbar
            // built off it is a crash the helper only swallows.
            say(context.getString(R.string.mtl_novel_translating))
        }
        // Identity, not equality: `translate` hands back the very string it was given when it gave
        // up, which is the only way to tell "nothing to change" from "changed nothing".
        if (translated && body === html) say(context.getString(R.string.mtl_novel_untranslated))

        LNReaderEpub.buildChapter(
            context = context,
            novelTitle = novel.name,
            chapterTitle = chapter.name,
            bodyHtml = body,
            author = novel.author,
            baseUrl = parser.resolve(chapter.path),
            // The document's language decides which dictionary the reader hyphenates with, so a
            // translated chapter has to say what it now is rather than what it was.
            language = if (translated && body !== html) NovelTranslation.target() else sourceCode,
            footerHtml = novel.chapters.getOrNull(index + 1)?.takeIf { continuous() }?.let { next ->
                LNReaderEpub.transitionFooter(
                    endLabel = context.getString(R.string.chapter_transition_end, chapter.name),
                    nextLabel = context.getString(R.string.chapter_transition_next, next.name),
                )
            },
        )
    }

    private suspend fun say(message: String) = withContext(Dispatchers.Main) {
        snackString(message)
    }
}
