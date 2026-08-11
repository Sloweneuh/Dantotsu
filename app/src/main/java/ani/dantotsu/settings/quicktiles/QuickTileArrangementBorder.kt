package ani.dantotsu.settings.quicktiles

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R

/**
 * Draws a rounded outline around the tiles that are actually on the panel.
 *
 * A decoration rather than a wrapper view because the arrangement and the catalogue shelf share one
 * grid — they have to, so a tile can be dragged between them — and nesting the top part in its own
 * container would break that into two scrolling areas.
 */
class QuickTileArrangementBorder(
    private val adapter: QuickTileEditAdapter,
) : RecyclerView.ItemDecoration() {

    private var border: Drawable? = null

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        val count = adapter.placedCount()
        if (position !in 0 until count) return
        val grid = parent.layoutManager as? GridLayoutManager ?: return

        // Breathing room inside the outline, on the first and last rows only. Asking whether the
        // position is below the column count would call the first two rows "first", since a row
        // of large tiles is two items wide, and the doubled inset showed up as a gap under row one.
        val lookup = grid.spanSizeLookup
        val row = lookup.getSpanGroupIndex(position, grid.spanCount)
        if (row == 0) outRect.top = PADDING_DP
        if (row == lookup.getSpanGroupIndex(count - 1, grid.spanCount)) {
            outRect.bottom = PADDING_DP
        }
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = adapter.placedCount()
        if (count == 0) return
        val drawable = border ?: ContextCompat
            .getDrawable(parent.context, R.drawable.quick_tile_arrangement_border)
            ?.also { border = it } ?: return

        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        var found = false
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (parent.getChildAdapterPosition(child) !in 0 until count) continue
            found = true
            top = minOf(top, child.top)
            bottom = maxOf(bottom, child.bottom)
        }
        if (!found) return

        // Clamped to the list's own box. Reaching outside it put the stroke off screen, since
        // the horizontal inset lives on this view's parent rather than on the list.
        drawable.setBounds(
            parent.paddingLeft,
            top - PADDING_DP,
            parent.width - parent.paddingRight,
            bottom + PADDING_DP,
        )
        drawable.draw(canvas)
    }

    private companion object {
        /** Kept in px at 3x-ish; the exact value only sets how far the outline stands off. */
        const val PADDING_DP = 18
    }
}
