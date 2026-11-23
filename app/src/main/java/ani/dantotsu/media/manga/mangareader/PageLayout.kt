package ani.dantotsu.media.manga.mangareader

import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import ani.dantotsu.R
import java.util.Collections
import java.util.WeakHashMap

/** Rows [from] to [to] (fractions of its height) of image [url], drawn at [dest] in a page bitmap's pixels. */
data class PagePlacement(val url: String, val from: Float, val to: Float, val dest: RectF)

/**
 * What a page bitmap built from more than one image — a real page drawn from slices
 * ([ChapterPages.compose]), or two pages side by side — is made of, in its [width]'s pixels.
 *
 * Translations are read and kept per image (see [ani.dantotsu.media.manga.translation.TranslatedPages]),
 * so painting them over such a page means moving each image's boxes to where that image was drawn.
 * The layout is recorded against the bitmap it describes when it is built, and moved onto the page
 * view only once a load has won the view, so a stale load can't leave another page's layout behind.
 */
class PageLayout(val width: Int, val placements: List<PagePlacement>) {

    companion object {
        private val byBitmap = Collections.synchronizedMap(WeakHashMap<Bitmap, PageLayout>())

        fun record(bitmap: Bitmap, layout: PageLayout) {
            byBitmap[bitmap] = layout
        }

        /** [bitmap]'s layout, or null for a bitmap that is one whole image. */
        fun of(bitmap: Bitmap): PageLayout? = byBitmap[bitmap]

        /** [bitmap] is one whole image, [url]: the layout to use when it is paired with another. */
        fun whole(url: String, bitmap: Bitmap) = PageLayout(
            bitmap.width,
            listOf(PagePlacement(url, 0f, 1f, RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))),
        )

        /** [layout] with every placement moved by ([dx], [dy]). */
        fun shifted(layout: PageLayout, dx: Float, dy: Float): List<PagePlacement> =
            layout.placements.map { it.copy(dest = RectF(it.dest).apply { offset(dx, dy) }) }

        /** Puts the layout of what [parent] now shows on it; null for a plain page. */
        fun attach(parent: View, layout: PageLayout?) = parent.setTag(R.id.page_layout, layout)

        fun on(parent: View): PageLayout? = parent.getTag(R.id.page_layout) as? PageLayout

        /** Whether [parent]'s bitmap was redrawn with gaps at its stitched page breaks. */
        fun markGapped(parent: View, gapped: Boolean) = parent.setTag(R.id.page_strip_gapped, gapped)

        fun gapped(parent: View): Boolean = parent.getTag(R.id.page_strip_gapped) == true
    }
}
