package ani.dantotsu.parsers

import android.app.Application
import androidx.documentfile.provider.DocumentFile
import ani.dantotsu.download.DownloadCompat.Companion.loadChaptersCompat
import ani.dantotsu.download.DownloadCompat.Companion.loadImagesCompat
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.DownloadsManager.Companion.getSubDirectory
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.manga.mangareader.PDF_CHAPTERS_FILE
import ani.dantotsu.media.manga.mangareader.PdfChapterMetadata
import ani.dantotsu.media.manga.mangareader.PdfPageRenderer
import ani.dantotsu.util.Logger
import com.google.gson.Gson
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import me.xdrop.fuzzywuzzy.FuzzySearch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class OfflineMangaParser : MangaParser() {
    private val downloadManager = Injekt.get<DownloadsManager>()
    private val context = Injekt.get<Application>()

    override val hostUrl: String = "Offline"
    override val name: String = "Offline"
    override val saveName: String = "Offline"
    override suspend fun loadChapters(
        mangaLink: String,
        extra: Map<String, String>?,
        sManga: SManga
    ): List<MangaChapter> {
        val directory = getSubDirectory(context, MediaType.MANGA, false, mangaLink)
        //get all of the folders and add them to the list
        val chapters = mutableListOf<MangaChapter>()
        if (directory?.exists() == true) {
            directory.listFiles().filter { it.isDirectory }.forEach { folder ->
                val name = folder.name ?: return@forEach
                val scanlator = downloadManager.mangaDownloadedTypes.find { items ->
                    items.titleName == mangaLink &&
                            items.chapterName == name
                }?.scanlator ?: "Unknown"

                // One-file PDF downloads bundle several chapters into a single PDF; expose
                // each bundled chapter as its own instance backed by a page range.
                val metadata = readPdfChapterMetadata(folder)
                val pdfFile = folder.listFiles().firstOrNull {
                    it.isFile && it.name?.endsWith(".pdf", ignoreCase = true) == true
                }
                if (metadata != null && pdfFile != null) {
                    metadata.chapters.forEach { entry ->
                        chapters.add(
                            MangaChapter(
                                entry.title,
                                PdfPageRenderer.encodeChapter(pdfFile.uri, entry.pages),
                                entry.title,
                                null,
                                // Per-chapter scanlator keeps same-numbered chapters from
                                // different scanlators distinct in the offline list.
                                entry.scanlator,
                                SChapter.create()
                            )
                        )
                    }
                } else {
                    chapters.add(
                        MangaChapter(
                            name,
                            "$mangaLink/$name",
                            name,
                            null,
                            scanlator,
                            SChapter.create()
                        )
                    )
                }
            }
        }
        chapters.addAll(loadChaptersCompat(mangaLink, extra, sManga))
        return chapters.sortedBy { MediaNameAdapter.findChapterNumber(it.number) }
    }

    private fun readPdfChapterMetadata(folder: DocumentFile): PdfChapterMetadata? {
        val metaFile = folder.listFiles().firstOrNull {
            it.isFile && it.name == PDF_CHAPTERS_FILE
        } ?: return null
        return try {
            context.contentResolver.openInputStream(metaFile.uri)?.use { input ->
                val text = input.readBytes().toString(Charsets.UTF_8)
                Gson().fromJson(text, PdfChapterMetadata::class.java)
            }
        } catch (e: Exception) {
            Logger.log("Failed to read PDF chapter metadata: ${e.message}")
            null
        }
    }

    override suspend fun loadImages(chapterLink: String, sChapter: SChapter): List<MangaImage> {
        // A chapter bundled inside a one-file PDF: render only its content pages.
        if (PdfPageRenderer.isPdfChapter(chapterLink)) {
            val (pdfUri, pages) = PdfPageRenderer.decodeChapter(chapterLink) ?: return emptyList()
            return pages.map { page ->
                MangaImage(PdfPageRenderer.encode(pdfUri, page), false, null)
            }
        }
        val title = chapterLink.split("/").first()
        val chapter = chapterLink.split("/").last()
        val directory = getSubDirectory(context, MediaType.MANGA, false, title, chapter)
        val images = mutableListOf<MangaImage>()
        val imageNumberRegex = Regex("""(\d+)\.jpg$""")
        if (directory?.exists() == true) {
            // Chapters downloaded as PDF expose each PDF page as an image.
            val pdfFile = directory.listFiles().firstOrNull {
                it.isFile && it.name?.endsWith(".pdf", ignoreCase = true) == true
            }
            if (pdfFile != null) {
                val pageCount = PdfPageRenderer.pageCount(context, pdfFile.uri)
                return (0 until pageCount).map { page ->
                    MangaImage(PdfPageRenderer.encode(pdfFile.uri, page), false, null)
                }
            }
            directory.listFiles().forEach {
                if (it.isFile) {
                    val image = MangaImage(it.uri.toString(), false, null)
                    images.add(image)
                }
            }
            for (image in images) {
                Logger.log("imageNumber: ${image.url.url}")
            }
        }
        return if (images.isNotEmpty()) {
            images.sortBy { image ->
                val matchResult = imageNumberRegex.find(image.url.url)
                matchResult?.groups?.get(1)?.value?.toIntOrNull() ?: Int.MAX_VALUE
            }
            images
        } else {
            loadImagesCompat(chapterLink, sChapter)
        }
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val titles = downloadManager.mangaDownloadedTypes.map { it.titleName }.distinct()
        val returnTitlesPair: MutableList<Pair<String, Int>> = mutableListOf()
        for (title in titles) {
            val score = FuzzySearch.ratio(title.lowercase(), query.lowercase())
            if (score > 80) {
                returnTitlesPair.add(Pair(title, score))
            }
        }
        val returnTitles = returnTitlesPair.sortedByDescending { it.second }.map { it.first }
        val returnList: MutableList<ShowResponse> = mutableListOf()
        for (title in returnTitles) {
            returnList.add(ShowResponse(title, title, title))
        }
        return returnList
    }

}