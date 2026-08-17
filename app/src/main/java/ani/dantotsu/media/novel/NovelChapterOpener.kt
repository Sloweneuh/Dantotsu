package ani.dantotsu.media.novel

import androidx.fragment.app.FragmentActivity
import ani.dantotsu.media.Media
import ani.dantotsu.media.manga.mangareader.ChapterLoaderDialog
import ani.dantotsu.parsers.novel.lnreader.LNReaderNovel
import ani.dantotsu.parsers.novel.lnreader.LNReaderParser

/**
 * Opens a chapter, by the same steps the manga reader opens one.
 *
 * Tap a chapter, answer the tracking question if there is one to answer, watch the loading sheet,
 * land in the reader. The question comes first deliberately: it is about whether reading counts
 * towards your list, and the only place it can be asked without interrupting is before the reading
 * starts. [ChapterLoaderDialog.showProgressPopupIfNecessary] owns which cases actually warrant
 * asking — the multi-chapter reader, not in incognito, an account to report to — and is shared with
 * manga so the two cannot drift apart. Cancelling it opens nothing, which is the point of a cancel.
 */
object NovelChapterOpener {

    fun open(
        activity: FragmentActivity,
        parser: LNReaderParser,
        novel: LNReaderNovel,
        index: Int,
        media: Media?,
    ) {
        if (novel.chapters.getOrNull(index) == null) return
        val load = {
            NovelChapterLoaderDialog.show(
                activity.supportFragmentManager, parser, novel, index, media
            )
        }
        // Nothing to track against without a media, so nothing to ask about.
        if (media == null || media.id < 0) load()
        else ChapterLoaderDialog.showProgressPopupIfNecessary(activity, media) { load() }
    }
}
