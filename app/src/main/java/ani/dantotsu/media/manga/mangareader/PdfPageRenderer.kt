package ani.dantotsu.media.manga.mangareader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import ani.dantotsu.util.Logger

/** Filename of the sidecar describing the chapters bundled inside a one-file PDF download. */
const val PDF_CHAPTERS_FILE = "chapters.json"

/** Describes the chapters bundled inside a single "one file" PDF download. */
data class PdfChapterMetadata(val chapters: List<PdfChapterEntry>)

/**
 * One chapter inside a one-file PDF: its [title], [scanlator] and the PDF page indices
 * ([pages]) that make up its content. The scanlator keeps chapters that share a number but
 * come from different scanlators distinct. Embedded transition/divider pages are excluded so
 * the in-app reader shows only real content (and adds its own transitions in continuous mode).
 * [scanlator] defaults to "Unknown" so bundles written before it was added still parse.
 */
data class PdfChapterEntry(
    val title: String,
    val pages: List<Int>,
    val scanlator: String = "Unknown"
)

/**
 * Renders pages of a downloaded PDF chapter on demand so they can be displayed by the
 * manga reader exactly like normal downloaded page images.
 *
 * A PDF page is referenced through a [MangaImage] whose url uses the [SCHEME] format
 * `pdfpage://<pageIndex>/<url-encoded pdf content uri>`.
 */
object PdfPageRenderer {
    private const val SCHEME = "pdfpage://"
    private const val CHAPTER_SCHEME = "pdfchapter://"

    /** Builds a reader url pointing at page [pageIndex] of the PDF at [pdfUri]. */
    fun encode(pdfUri: Uri, pageIndex: Int): String =
        "$SCHEME$pageIndex/${Uri.encode(pdfUri.toString())}"

    fun isPdfPage(url: String): Boolean = url.startsWith(SCHEME)

    /**
     * Builds an offline chapter link that maps a chapter to a specific set of [pages] inside
     * the shared one-file PDF at [pdfUri]. Consumed by the offline parser's loadImages.
     */
    fun encodeChapter(pdfUri: Uri, pages: List<Int>): String =
        "$CHAPTER_SCHEME${Uri.encode(pdfUri.toString())}#${pages.joinToString(",")}"

    fun isPdfChapter(link: String): Boolean = link.startsWith(CHAPTER_SCHEME)

    fun decodeChapter(link: String): Pair<Uri, List<Int>>? {
        if (!isPdfChapter(link)) return null
        val body = link.removePrefix(CHAPTER_SCHEME)
        val hash = body.lastIndexOf('#')
        if (hash < 0) return null
        val uri = Uri.parse(Uri.decode(body.substring(0, hash)))
        val pages = body.substring(hash + 1)
            .split(",")
            .mapNotNull { it.toIntOrNull() }
        return uri to pages
    }

    private fun decode(url: String): Pair<Uri, Int>? {
        if (!isPdfPage(url)) return null
        val rest = url.removePrefix(SCHEME)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val page = rest.substring(0, slash).toIntOrNull() ?: return null
        val uri = Uri.parse(Uri.decode(rest.substring(slash + 1)))
        return uri to page
    }

    /** Number of pages in the PDF at [uri], or 0 if it can't be opened. */
    fun pageCount(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer -> renderer.pageCount }
            } ?: 0
        } catch (e: Exception) {
            Logger.log("Failed to read PDF page count: ${e.message}")
            0
        }
    }

    // PdfRenderer is not thread-safe and only allows one page open at a time, so all
    // renders across the reader are serialized.
    private val renderLock = Any()

    /**
     * Renders the page referenced by [url] to a bitmap, scaled so its width is at most
     * [targetWidth]; the height follows the page aspect ratio. Very tall pages (long-strip
     * chapters) are additionally capped in height to keep memory usage bounded.
     */
    fun render(context: Context, url: String, targetWidth: Int): Bitmap? {
        val (uri, pageIndex) = decode(url) ?: return null
        return try {
            synchronized(renderLock) {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (pageIndex !in 0 until renderer.pageCount) return null
                        renderer.openPage(pageIndex).use { page ->
                            val scale = targetWidth.toFloat() / page.width
                            var width = targetWidth.coerceAtLeast(1)
                            var height = (page.height * scale).toInt().coerceAtLeast(1)
                            val maxHeight = 12000
                            if (height > maxHeight) {
                                val shrink = maxHeight.toFloat() / height
                                width = (width * shrink).toInt().coerceAtLeast(1)
                                height = maxHeight
                            }
                            val bitmap =
                                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            // PDF pages can be transparent; paint white so the page reads
                            // like a scanned page rather than showing the app background.
                            bitmap.eraseColor(Color.WHITE)
                            page.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )
                            bitmap
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.log("Failed to render PDF page: ${e.message}")
            null
        }
    }
}
