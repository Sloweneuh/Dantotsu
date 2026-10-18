package ani.dantotsu.settings.quicktiles

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.getThemeColor

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
    private var separatorPaint: Paint? = null

    /**
     * How strongly the outline is drawn, 0 to 1. The panel fades it in and out as edit mode is
     * entered and left; the list has to be invalidated for a change to show.
     */
    var alpha: Float = 1f

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
        // Room for the separator above the first row of every page after the first. Every tile in
        // that row gets it, or the row's cells would sit at different heights.
        if (row > 0 && row in pageStartRows(grid, count)) {
            outRect.top = (SEPARATOR_GAP_DP * view.resources.displayMetrics.density).toInt()
        }
        if (row == lookup.getSpanGroupIndex(count - 1, grid.spanCount)) {
            outRect.bottom = PADDING_DP
        }
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = adapter.placedCount()
        if (count == 0) return
        val drawable = border ?: ContextCompat
            .getDrawable(parent.context, R.drawable.quick_tile_arrangement_border)
            ?.mutate()?.also { border = it } ?: return

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
        if (!found || alpha <= 0f) return

        // Clamped to the list's own box. Reaching outside it put the stroke off screen, since
        // the horizontal inset lives on this view's parent rather than on the list.
        drawable.setBounds(
            parent.paddingLeft,
            top - PADDING_DP,
            parent.width - parent.paddingRight,
            bottom + PADDING_DP,
        )
        drawable.alpha = (alpha * 255).toInt()
        drawable.draw(canvas)

        drawPageSeparators(canvas, parent, count)
    }

    /**
     * A dashed line across the outline wherever the pager would turn to a new page, so the
     * arrangement — one continuous grid here, so tiles can be dragged anywhere in it — still shows
     * which tiles will share a page. Dashed and inset from the outline's sides, so it reads as a
     * break inside the panel rather than the edge of another one.
     */
    private fun drawPageSeparators(canvas: Canvas, parent: RecyclerView, count: Int) {
        val grid = parent.layoutManager as? GridLayoutManager ?: return
        val starts = pageStartRows(grid, count)
        if (starts.isEmpty()) return
        val density = parent.resources.displayMetrics.density
        val paint = separatorPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            strokeCap = Paint.Cap.ROUND
            pathEffect = DashPathEffect(floatArrayOf(6 * density, 5 * density), 0f)
            color = parent.context.getThemeColor(com.google.android.material.R.attr.colorOutline)
        }.also { separatorPaint = it }
        paint.alpha = (alpha * SEPARATOR_ALPHA * 255).toInt()

        val lookup = grid.spanSizeLookup
        val inset = SEPARATOR_INSET_DP * density
        val left = parent.paddingLeft + inset
        val right = parent.width - parent.paddingRight - inset
        val gap = SEPARATOR_GAP_DP * density
        val drawn = HashSet<Int>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position !in 0 until count) continue
            val row = lookup.getSpanGroupIndex(position, grid.spanCount)
            // One line per break, taken from whichever tile of that row comes first.
            if (row !in starts || !drawn.add(row)) continue
            val y = child.top + child.translationY - gap / 2
            canvas.drawLine(left, y, right, y, paint)
        }
    }

    /**
     * The grid rows on which a new page begins, the first page excluded — worked out with the
     * pager's own pagination, so the editor shows the same breaks the panel will have.
     */
    private fun pageStartRows(grid: GridLayoutManager, count: Int): Set<Int> {
        val pages = TileCatalogue.paginate(
            adapter.placedTiles(), QUICK_TILE_COLUMNS, QUICK_TILE_ROWS,
        )
        if (pages.size < 2) return emptySet()
        val lookup = grid.spanSizeLookup
        val rows = HashSet<Int>()
        var first = 0
        for (page in pages.dropLast(1)) {
            first += page.size
            if (first < count) rows += lookup.getSpanGroupIndex(first, grid.spanCount)
        }
        return rows
    }

    private companion object {
        /** Kept in px at 3x-ish; the exact value only sets how far the outline stands off. */
        const val PADDING_DP = 18

        /** Extra space above a page's first row, with the separator drawn through its middle. */
        const val SEPARATOR_GAP_DP = 12

        /** How far the separator stops short of the outline on either side. */
        const val SEPARATOR_INSET_DP = 20

        /** Quieter than the outline, which marks the arrangement as a whole. */
        const val SEPARATOR_ALPHA = 0.6f
    }
}
