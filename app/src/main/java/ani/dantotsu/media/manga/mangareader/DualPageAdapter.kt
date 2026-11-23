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

    /** Only a page left alone at the end, shown whole, is a plain image. */
    override fun plainImageAt(position: Int): MangaImage? =
        if (pagePairs != null) pagePairs.getOrNull(position)?.takeIf { it.second == null }
            ?.first?.takeIf { it.single }?.let { chapterImages.getOrNull(it.slices[0].image) }
        else imagePairs.getOrNull(position)?.takeIf { it.second == null }?.first

    /** A merged pair has no single page edge to judge. */
    override fun pageUrl(position: Int): String? = null

    override suspend fun loadBitmap(position: Int, parent: View): Bitmap? {
        if (pagePairs != null) {
            val (first, second) = pagePairs.getOrNull(position) ?: return null
            val zoom = renderZoomOf(parent)
            val bitmap1 = composePage(first, null, zoom) ?: return null
            val bitmap2 = second?.let { composePage(it, null, zoom) ?: return null }
            fun layoutOf(page: ChapterPage, bitmap: Bitmap) = PageLayout.of(bitmap)
                ?: PageLayout.whole(chapterImages[page.slices[0].image].url.url, bitmap)
            return if (bitmap2 != null) {
                merged(bitmap1, layoutOf(first, bitmap1), bitmap2, layoutOf(second, bitmap2))
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
            merged(
                bitmap1, PageLayout.whole(link1.url, bitmap1),
                bitmap2, PageLayout.whole(img2.url.url, bitmap2),
            )
        } else bitmap1
    }

    /**
     * The pair side by side in reading order, with a [PageLayout] saying where each page's images
     * went — [mergeBitmap] centres the shorter page vertically — so their translations land on them.
     */
    private fun merged(bitmap1: Bitmap, layout1: PageLayout, bitmap2: Bitmap, layout2: PageLayout): Bitmap {
        val rtl = settings.direction != LEFT_TO_RIGHT
        val (left, leftLayout) = if (rtl) bitmap2 to layout2 else bitmap1 to layout1
        val (right, rightLayout) = if (rtl) bitmap1 to layout1 else bitmap2 to layout2
        val out = mergeBitmap(left, right)
        val height = maxOf(left.height, right.height).toFloat()
        PageLayout.record(
            out,
            PageLayout(
                out.width,
                PageLayout.shifted(leftLayout, 0f, (height - left.height) / 2) +
                    PageLayout.shifted(rightLayout, left.width.toFloat(), (height - right.height) / 2),
            ),
        )
        return out
    }

    override val pageItemCount: Int get() = pagePairs?.size ?: imagePairs.size
}