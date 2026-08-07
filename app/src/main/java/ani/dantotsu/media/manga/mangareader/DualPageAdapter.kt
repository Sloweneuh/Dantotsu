package ani.dantotsu.media.manga.mangareader

import android.graphics.Bitmap
import android.view.View
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.settings.CurrentReaderSettings.Directions.LEFT_TO_RIGHT

class DualPageAdapter(
    activity: MangaReaderActivity,
    val chapter: MangaChapter
) : ImageAdapter(activity, chapter) {

    private val pages = chapter.dualPages()

    /** A position holds a pair of pages here, not a single one of [images]. */
    override fun pageKey(position: Int): Any? = pages.getOrNull(position)

    override fun pagesAt(position: Int): List<MangaImage> =
        pages.getOrNull(position)?.let { listOfNotNull(it.first, it.second) }.orEmpty()

    override suspend fun loadBitmap(position: Int, parent: View): Bitmap? {
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

    override fun getItemCount(): Int = pages.size
}