package ani.dantotsu.download.manage

import android.content.Context
import android.net.Uri
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.OfflineMediaLoader
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.manga.mangareader.PDF_CHAPTERS_FILE
import ani.dantotsu.media.manga.mangareader.PdfChapterMetadata
import ani.dantotsu.util.Logger
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
     */
    fun load(context: Context): Pair<List<DownloadMediaGroup>, DownloadTotals> {
        val dm = Injekt.get<DownloadsManager>()
        val groups = mutableListOf<DownloadMediaGroup>()
        var mangaTotal = 0L
        var animeTotal = 0L
        var novelTotal = 0L

        for (type in listOf(MediaType.MANGA, MediaType.ANIME, MediaType.NOVEL)) {
            val types = when (type) {
                MediaType.MANGA -> dm.mangaDownloadedTypes
                MediaType.ANIME -> dm.animeDownloadedTypes
                MediaType.NOVEL -> dm.novelDownloadedTypes
            }
            types.groupBy { it.titleName }.forEach { (titleName, entries) ->
                val children = entries.map { e ->
                    DownloadChild(
                        type = type,
                        titleName = titleName,
                        chapterName = e.chapterName,
                        scanlator = e.scanlator,
                        sizeBytes = DownloadsManager.getDirSize(
                            context, type, titleName, e.chapterName
                        )
                    )
                }.sortedBy {
                    // Sort by the actual chapter/episode number, not lexicographically
                    // ("1" < "10" < "2" would otherwise be wrong).
                    MediaNameAdapter.findChapterNumber(it.chapterName) ?: Float.MAX_VALUE
                }
                val mediaSize = DownloadsManager.getDirSize(context, type, titleName)
                val meta = OfflineMediaLoader.load(context, type, titleName)
                // Manga one-file bundles hold several chapters in one entry; count them all.
                val itemCount = if (type == MediaType.MANGA) {
                    children.sumOf { child ->
                        if (child.chapterName.contains(" - "))
                            bundleChapterCount(context, type, titleName, child.chapterName)
                        else 1
                    }
                } else children.size
                groups.add(
                    DownloadMediaGroup(
                        type = type,
                        titleName = titleName,
                        title = meta.title,
                        coverUri = meta.coverUri,
                        sizeBytes = mediaSize,
                        children = children,
                        itemCount = itemCount
                    )
                )
                when (type) {
                    MediaType.MANGA -> mangaTotal += mediaSize
                    MediaType.ANIME -> animeTotal += mediaSize
                    MediaType.NOVEL -> novelTotal += mediaSize
                }
            }
        }
        return groups.sortedBy { it.title.lowercase() } to
                DownloadTotals(mangaTotal, animeTotal, novelTotal)
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
