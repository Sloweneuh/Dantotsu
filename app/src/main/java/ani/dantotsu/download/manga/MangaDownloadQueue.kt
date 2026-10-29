package ani.dantotsu.download.manga

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import ani.dantotsu.download.DownloadItem
import ani.dantotsu.download.DownloadTracker
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.parsers.DynamicMangaParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Queues manga chapters for [MangaDownloaderService]: fetches a chapter's pages from its source,
 * adds the task, lists it in the download manager, and starts the service when it isn't running.
 * Shared by the chapter list's download buttons and the automatic downloads (AutoDownloads).
 */
object MangaDownloadQueue {

    /** What a chapter is queued as — the key the tracker and the service know it by. */
    fun chapterKey(chapter: MangaChapter): String = chapter.title ?: chapter.number

    /** Whether [chapter] of [media] is already waiting or downloading. */
    fun isQueued(media: Media, chapter: MangaChapter): Boolean {
        val id = DownloadTracker.idOf(MediaType.MANGA, media.mainName(), chapterKey(chapter))
        return DownloadTracker.items.value.any { it.id == id }
    }

    /**
     * Fetches [chapter]'s page list from [parser] and queues it. False when the source has no pages
     * for it (a since-removed chapter), in which case nothing is queued. Must run off the main
     * thread; starts the service from the main one, which needs the app in the foreground.
     */
    suspend fun enqueue(
        context: Context,
        media: Media,
        chapter: MangaChapter,
        parser: DynamicMangaParser,
        asPdf: Boolean,
    ): Boolean {
        val images = parser.imageList(chapter.sChapter)
        if (images.isEmpty()) return false
        val task = MangaDownloaderService.DownloadTask(
            title = media.mainName(),
            chapter = chapterKey(chapter),
            scanlator = chapter.scanlator ?: "Unknown",
            imageData = images,
            sourceMedia = media,
            retries = 25,
            simultaneousDownloads = 2,
            asPdf = asPdf,
            language = parser.extension.sources.getOrNull(parser.sourceLanguage)?.lang,
        )
        enqueue(context, media, task)
        return true
    }

    /** Queues a task already built — the combined "one file" PDF, which carries several chapters. */
    suspend fun enqueue(context: Context, media: Media, task: MangaDownloaderService.DownloadTask) {
        MangaServiceDataSingleton.downloadQueue.offer(task)
        DownloadTracker.enqueue(
            DownloadItem(
                id = DownloadTracker.idOf(MediaType.MANGA, task.title, task.chapter),
                type = MediaType.MANGA,
                mediaId = media.id,
                serviceKey = task.chapter,
                title = task.title,
                coverUrl = media.cover,
                label = task.chapter
            )
        )
        if (!MangaServiceDataSingleton.isServiceRunning) {
            withContext(Dispatchers.Main) {
                ContextCompat.startForegroundService(
                    context, Intent(context, MangaDownloaderService::class.java)
                )
            }
            MangaServiceDataSingleton.isServiceRunning = true
        }
    }
}
