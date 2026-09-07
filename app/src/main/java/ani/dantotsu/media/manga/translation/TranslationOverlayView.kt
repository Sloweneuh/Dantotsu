package ani.dantotsu.media.manga.translation

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/**
 * Draws a page's translations over the page in the reader.
 *
 * Sits as a sibling of the reader's image inside the `GestureFrameLayout` that owns pan and zoom,
 * which is what makes this cheap here. Dantotsu's reader disables the image view's own gestures and
 * transforms the *parent* instead, so an overlay in that parent is panned and zoomed along with the
 * page for free — no transform to track, no listener to keep in step. Mihon's equivalent has to
 * chase the image view's matrix continuously because its reader zooms the image itself.
 *
 * Geometry arriving here is in **page pixels**; the view scales it by its own width, the same way
 * the page beneath it is scaled to fit.
 */
class TranslationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val painter = BlockPainter(resources.displayMetrics.density)
    private var blocks: List<PaintedBlock> = emptyList()
    private var pageWidth = 0

    /**
     * What to draw, and the width of the page it was measured against.
     *
     * Passing the page width rather than reading it off the image keeps this independent of how the
     * page is displayed — the reader hands the same numbers whether the page is fit to width, in a
     * long strip, or paired with another.
     */
    fun setBlocks(blocks: List<PaintedBlock>, pageWidth: Int) {
        this.blocks = blocks
        this.pageWidth = pageWidth
        painter.reset()
        invalidate()
    }

    fun clear() {
        if (blocks.isEmpty()) return
        blocks = emptyList()
        painter.reset()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Fitted type sizes are in view pixels, so a width change invalidates every one of them.
        if (w != oldw) painter.reset()
    }

    override fun onDraw(canvas: Canvas) {
        if (blocks.isEmpty() || pageWidth <= 0 || width <= 0) return
        painter.draw(canvas, blocks, width.toFloat() / pageWidth)
    }
}
