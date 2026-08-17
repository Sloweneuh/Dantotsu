package ani.dantotsu.parsers.novel.lnreader

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile

/**
 * Naming for saved runs of LNReader chapters.
 *
 * The fetching and packaging itself lives in
 * [ani.dantotsu.download.novel.NovelDownloaderService]: a run is a request per chapter and can take
 * minutes, so it belongs to a foreground service rather than whichever screen started it. What is
 * left here is the one decision both the service and its callers have to agree on — what the run is
 * filed under, since that name is the folder it lands in and the row the downloads library shows.
 */
object LNReaderDownloader {

    fun entryNameFor(chapters: List<LNReaderChapter>): String = when {
        chapters.isEmpty() -> "Chapters"
        chapters.size == 1 -> chapters.first().name.take(80)
        else -> "${chapters.first().name.take(35)} - ${chapters.last().name.take(35)}"
    }

    /** True if [directory] holds a saved run in either format. */
    fun isRun(directory: DocumentFile?): Boolean =
        directory?.findFile("0.epub") != null || htmlPartsIn(directory).isNotEmpty()

    /**
     * A URI the reader can open for a run saved in [directory], whichever format it is in.
     *
     * An EPUB run opens as it is. An HTML one is repackaged into a cached EPUB first: the format
     * exists so the text can be taken elsewhere, but a download that could not be read back in the
     * app that made it would be a strange thing to offer. The parts are recombined in file order,
     * which is the order they were fetched in, and each carries its own chapter title.
     *
     * Runs on a background thread — it reads and rewrites the whole book.
     */
    fun readableUri(context: Context, directory: DocumentFile?, runName: String): Uri? {
        directory?.findFile("0.epub")?.uri?.let { return it }

        val parts = htmlPartsIn(directory)
        if (parts.isEmpty()) return null
        val chapters = parts.flatMap { part ->
            val html = runCatching {
                context.contentResolver.openInputStream(part.uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull().orEmpty()
            if (html.isBlank()) emptyList() else LNReaderEpub.chaptersFrom(html, runName)
        }
        if (chapters.isEmpty()) return null

        val book = LNReaderEpub.buildBook(context, runName, chapters)
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", book)
    }

    /** The `0.html`, `1.html`… parts of a run, in numeric order rather than the listing's order. */
    private fun htmlPartsIn(directory: DocumentFile?): List<DocumentFile> =
        directory?.listFiles()
            ?.filter { it.name?.endsWith(".html", ignoreCase = true) == true }
            ?.sortedBy { it.name?.substringBeforeLast('.')?.toIntOrNull() ?: Int.MAX_VALUE }
            ?: emptyList()
}
