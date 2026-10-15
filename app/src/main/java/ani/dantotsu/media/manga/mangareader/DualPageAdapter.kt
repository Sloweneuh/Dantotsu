package ani.dantotsu.media.manga.mangareader

import android.graphics.Bitmap
import android.view.View
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.settings.CurrentReaderSettings.Directions.LEFT_TO_RIGHT

class DualPageAdapter(
    activity: MangaReaderActivity,
    val chapter: MangaChapter,
    pageMap: ChapterPageMap? = null,
) : ImageAdapter(activity, chapter, pageMap) {

    private val imagePairs = chapter.dualPages()

    /** Real pages paired up, when the adapter goes by real pages. */
    private val pagePairs = pageMap?.pages?.chunked(2)?.map { it[0] to it.getOrNull(1) }

    /** A position holds a pair of pages here, not a single one of [images]. */
    override fun pageKey(position: Int): Any? =
        if (pagePairs != null) pagePairs.getOrNull(position) else imagePairs.getOrNull(position)

    override fun pagesAt(position: Int): List<MangaImage> =
        if (pagePairs != null) pagePairs.getOrNull(position)
            ?.let { listOfNotNull(it.first, it.second) }.orEmpty()
            .flatMap { page -> page.slices.mapNotNull { chapterImages.getOrNull(it.image) } }
            .distinct()
        else imagePairs.getOrNull(position)?.let { listOfNotNull(it.first, it.second) }.orEmpty()

    override fun translatableAt(position: Int): List<MangaImage> =
        if (pagePairs != null) emptyList() else pagesAt(position)

    /** A merged pair has no single page edge to judge. */
    override fun pageUrl(position: Int): String? = null

    override suspend fun loadBitmap(position: Int, parent: View): Bitmap? {
        if (pagePairs != null) {
            val (first, second) = pagePairs.getOrNull(position) ?: return null
            val zoom = renderZoomOf(parent)
            val bitmap1 = composePage(first, null, zoom) ?: return null
            val bitmap2 = second?.let { composePage(it, null, zoom) ?: return null }
            return if (bitmap2 != null) {
                if (settings.direction != LEFT_TO_RIGHT) mergeBitmap(bitmap2, bitmap1)
                else mergeBitmap(bitmap1, bitmap2)
            } else bitmap1
        }
        val pages = imagePairs
        val img1 = pages[position].first
        val link1 = img1.url
        if (link1.url.isEmpty()) return null

        val img2 = pages[position].second
        val link2 = img2?.url
        if (link2?.url?.isEmpty() == true) return null

        val bitmap1 = activity.loadBitmap(link1, activity.pageTransforms(img1)) ?: return null
        val bitmap2 = img2?.let {
            activity.loadBitmap(it.url, activity.pageTransforms(it)) ?: return null
        }

        return if (bitmap2 != null) {
            if (settings.direction != LEFT_TO_RIGHT)
                mergeBitmap(bitmap2, bitmap1)
            else mergeBitmap(bitmap1, bitmap2)
        } else bitmap1
    }

    override fun getItemCount(): Int = pagePairs?.size ?: imagePairs.size
}