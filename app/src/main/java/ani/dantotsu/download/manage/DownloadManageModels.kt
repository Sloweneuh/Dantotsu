package ani.dantotsu.download.manage

import android.content.Context
import android.net.Uri
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.OfflineMediaLoader
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.manga.mangareader.PDF_CHAPTERS_FILE
import ani.dantotsu.media.manga.mangareader.PdfChapterMetadata
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** A single downloaded chapter / episode / one-file batch within a media group. */
data class DownloadChild(
    val type: MediaType,
    val titleName: String,
    val chapterName: String,
    val scanlator: String,
    val sizeBytes: Long,
)

/** A downloaded media with its children and total on-disk size. */
data class DownloadMediaGroup(
    val type: MediaType,
    val titleName: String,
    val title: String,
    val coverUri: Uri?,
    val sizeBytes: Long,
    val children: List<DownloadChild>,
    /** True chapter/episode total: one-file PDF bundles count all their contained chapters. */
    val itemCount: Int,
)

/** Aggregate totals shown above the management list. */
data class DownloadTotals(
    val manga: Long,
    val anime: Long,
    val novel: Long,
) {
    val total: Long get() = manga + anime + novel
}

object DownloadManageLoader {
    /**
     * Builds the grouped download list (offline-safe). Reads sizes recursively from disk, so
     * call from a background dispatcher. One-file bundles appear as a single "batch" child
     * (they are registered as one DownloadedType under the range name).
     *
     * Every size comes from a SAF `DocumentFile` walk, and each such call is a slow IPC round
     * trip to the DocumentsProvider — with many downloaded titles/chapters this used to add up
     * to a serial multi-second (or worse) load. Two changes bring that down: titles and their
     * children are sized concurrently (`async`/`awaitAll`) instead of one after another, and a
     * title's total size is now the sum of its already-computed children instead of a second,
     * fully redundant walk of the same directory tree.
     */
    suspend fun load(context: Context): Pair<List<DownloadMediaGroup>, DownloadTotals> =
        coroutineScope {
            val dm = Injekt.get<DownloadsManager>()

            val titleEntries = listOf(MediaType.MANGA, MediaType.ANIME, MediaType.NOVEL)
                .flatMap { type ->
                    val types = when (type) {
                        MediaType.MANGA -> dm.mangaDownloadedTypes
                        MediaType.ANIME -> dm.animeDownloadedTypes
                        MediaType.NOVEL -> dm.novelDownloadedTypes
                    }
                    types.groupBy { it.titleName }
                        .map { (titleName, entries) -> Triple(type, titleName, entries) }
                }

            val groups = titleEntries.map { (type, titleName, entries) ->
                async(Dispatchers.IO) { loadGroup(context, type, titleName, entries) }
            }.awaitAll()

            val totals = DownloadTotals(
                manga = groups.filter { it.type == MediaType.MANGA }.sumOf { it.sizeBytes },
                anime = groups.filter { it.type == MediaType.ANIME }.sumOf { it.sizeBytes },
                novel = groups.filter { it.type == MediaType.NOVEL }.sumOf { it.sizeBytes },
            )
            groups.sortedBy { it.title.lowercase() } to totals
        }

    private suspend fun loadGroup(
        context: Context,
        type: MediaType,
        titleName: String,
        entries: List<DownloadedType>
    ): DownloadMediaGroup = coroutineScope {
        val children = entries.map { e ->
            async(Dispatchers.IO) {
                DownloadChild(
                    type = type,
                    titleName = titleName,
                    chapterName = e.chapterName,
                    scanlator = e.scanlator,
                    sizeBytes = DownloadsManager.getDirSize(context, type, titleName, e.chapterName)
                )
            }
        }.awaitAll().sortedBy {
            // Sort by the actual chapter/episode number, not lexicographically
            // ("1" < "10" < "2" would otherwise be wrong).
            MediaNameAdapter.findChapterNumber(it.chapterName) ?: Float.MAX_VALUE
        }
        // The title's total is just the sum of its children's sizes — avoids re-walking the
        // whole directory tree a second time (which duplicated all the file listings above).
        val mediaSize = children.sumOf { it.sizeBytes }
        val meta = OfflineMediaLoader.load(context, type, titleName)
        // Manga one-file bundles hold several chapters in one entry; count them all.
        val itemCount = if (type == MediaType.MANGA) {
            children.sumOf { child ->
                if (child.chapterName.contains(" - "))
                    bundleChapterCount(context, type, titleName, child.chapterName)
                else 1
            }
        } else children.size

        DownloadMediaGroup(
            type = type,
            titleName = titleName,
            title = meta.title,
            coverUri = meta.coverUri,
            sizeBytes = mediaSize,
            children = children,
            itemCount = itemCount
        )
    }

    /** Number of chapters bundled inside a one-file PDF entry (1 if it isn't a bundle). */
    private fun bundleChapterCount(
        context: Context,
        type: MediaType,
        titleName: String,
        chapterName: String
    ): Int {
        val folder = DownloadsManager.getSubDirectory(context, type, false, titleName, chapterName)
            ?: return 1
        val meta = folder.findFile(PDF_CHAPTERS_FILE) ?: return 1
        return try {
            context.contentResolver.openInputStream(meta.uri)?.use {
                val json = it.readBytes().toString(Charsets.UTF_8)
                gson.fromJson(json, PdfChapterMetadata::class.java)?.chapters?.size ?: 1
            } ?: 1
        } catch (e: Exception) {
            Logger.log("Failed to read bundle chapter count: ${e.message}")
            1
        }
    }

    private val gson = com.google.gson.Gson()
}
