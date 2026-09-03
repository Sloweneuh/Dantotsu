package ani.dantotsu.download.manage

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
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
    /** On-disk size, or null while it is still being measured. */
    val sizeBytes: Long? = null,
    /** Anime only: the episode's real title, from its media.json entry when it has one. */
    val episodeTitle: String? = null,
    /** Anime only: the episode's synopsis, from its media.json entry when it has one. */
    val episodeDesc: String? = null,
    /** Anime only: the episode's own thumbnail, when one was downloaded for it. */
    val episodeThumbUri: Uri? = null,
)

/** A downloaded media with its children and total on-disk size. */
data class DownloadMediaGroup(
    val type: MediaType,
    val titleName: String,
    val title: String,
    val coverUri: Uri?,
    val children: List<DownloadChild>,
    /** True chapter/episode total: one-file PDF bundles count all their contained chapters. */
    val itemCount: Int,
    /** Sum of the children's sizes, or null while they are still being measured. */
    val sizeBytes: Long? = null,
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
     * Builds the grouped download list (offline-safe), without any sizes. Still touches the disk
     * for each title's `media.json`/cover, so call from a background dispatcher.
     *
     * Sizes are deliberately not part of this pass. Measuring one means a recursive
     * `DocumentFile` walk of every chapter folder, and each such call is a slow IPC round trip to
     * the DocumentsProvider — with many downloaded titles that is what made the screen sit on a
     * spinner for seconds. The list can be shown without them, so it is: [loadSizes] fills them
     * in afterwards and the rows update in place.
     */
    suspend fun load(context: Context): List<DownloadMediaGroup> = coroutineScope {
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

        titleEntries.map { (type, titleName, entries) ->
            async(Dispatchers.IO) { loadGroup(context, type, titleName, entries) }
        }.awaitAll().sortedBy { it.title.lowercase() }
    }

    /**
     * Measures every group from [load] and returns them with their sizes filled in, plus the
     * aggregate totals. Titles and their children are sized concurrently, and a title's total is
     * the sum of its already-measured children rather than a second walk of the same tree.
     */
    suspend fun loadSizes(
        context: Context,
        groups: List<DownloadMediaGroup>
    ): Pair<List<DownloadMediaGroup>, DownloadTotals> = coroutineScope {
        val sized = groups.map { group ->
            async(Dispatchers.IO) {
                val children = group.children.map { child ->
                    async(Dispatchers.IO) {
                        child.copy(
                            sizeBytes = DownloadsManager.getDirSize(
                                context, child.type, child.titleName, child.chapterName
                            )
                        )
                    }
                }.awaitAll()
                group.copy(
                    children = children,
                    sizeBytes = children.sumOf { it.sizeBytes ?: 0L }
                )
            }
        }.awaitAll()

        val totals = DownloadTotals(
            manga = sized.filter { it.type == MediaType.MANGA }.sumOf { it.sizeBytes ?: 0L },
            anime = sized.filter { it.type == MediaType.ANIME }.sumOf { it.sizeBytes ?: 0L },
            novel = sized.filter { it.type == MediaType.NOVEL }.sumOf { it.sizeBytes ?: 0L },
        )
        sized to totals
    }

    private fun loadGroup(
        context: Context,
        type: MediaType,
        titleName: String,
        entries: List<DownloadedType>
    ): DownloadMediaGroup {
        // Per-episode title/desc/thumb live in the title's own media.json (AnimeDownloaderService
        // writes them into media.anime.episodes[number] as each episode finishes). Manga/novel
        // chapters carry no such per-entry metadata today, so this stays anime-only.
        val episodes = if (type == MediaType.ANIME) {
            OfflineMediaLoader.loadMedia(context, type, titleName)?.anime?.episodes
        } else null

        val children = entries.map { e ->
            val episode = episodes?.get(e.chapterName)
            DownloadChild(
                type = type,
                titleName = titleName,
                chapterName = e.chapterName,
                scanlator = e.scanlator,
                episodeTitle = episode?.title?.takeIf { it.isNotBlank() },
                episodeDesc = episode?.desc?.takeIf { it.isNotBlank() },
                episodeThumbUri = episode?.thumb?.url?.let {
                    runCatching { it.toUri() }.getOrNull()
                },
            )
        }.sortedBy {
            // Sort by the actual chapter/episode number, not lexicographically
            // ("1" < "10" < "2" would otherwise be wrong).
            MediaNameAdapter.findChapterNumber(it.chapterName) ?: Float.MAX_VALUE
        }
        val meta = OfflineMediaLoader.load(context, type, titleName)
        // Manga one-file bundles hold several chapters in one entry; count them all.
        val itemCount = if (type == MediaType.MANGA) {
            children.sumOf { child ->
                if (child.chapterName.contains(" - "))
                    bundleChapterCount(context, type, titleName, child.chapterName)
                else 1
            }
        } else children.size

        return DownloadMediaGroup(
            type = type,
            titleName = titleName,
            title = meta.title,
            coverUri = meta.coverUri,
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
