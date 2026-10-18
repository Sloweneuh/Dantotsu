package ani.dantotsu.media

import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import ani.dantotsu.R
import ani.dantotsu.getThemeColor
import com.bumptech.glide.Glide

/**
 * A stack's cover, drawn as a collage of its first covers rather than as cards fanned on top of
 * each other.
 *
 * The fan showed one cover whole and the rest as slivers a few dp wide, with nothing to recognise
 * them by. Laid side by side, every cover shown is shown in full. The cover box is a 2:3 portrait,
 * so a 2×2 grid keeps each quarter at the same 2:3 and covers aren't cropped into strips:
 *
 *  - one cover fills the box;
 *  - two split it down the middle;
 *  - three put the first down the left half and the next two stacked on the right;
 *  - four or more fill the grid, and when the stack holds more than are shown the last tile is
 *    dimmed under a "+N" for the rest.
 *
 * Thin gaps let the card's own background show between tiles, so neighbouring covers don't run
 * together; the card's rounded corners clip the collage as a whole.
 */
object StackCoverCollage {
    private const val GAP_DP = 1.5f

    fun bind(container: FrameLayout, covers: List<String>, entries: Int) {
        container.removeAllViews()
        val shown = covers.filter { it.isNotBlank() }.take(4)
        val gap = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, GAP_DP, container.resources.displayMetrics,
        ).toInt()
        // What the last tile stands in for beyond itself: only a full grid has a tile to give up.
        val more = if (shown.size == 4) entries - 3 else 0

        val collage: View = when (shown.size) {
            0 -> placeholder(container)
            1 -> tile(container, shown[0])
            2 -> row(container, gap, tile(container, shown[0]), tile(container, shown[1]))
            3 -> row(
                container, gap,
                tile(container, shown[0]),
                column(container, gap, tile(container, shown[1]), tile(container, shown[2])),
            )
            else -> row(
                container, gap,
                column(container, gap, tile(container, shown[0]), tile(container, shown[2])),
                column(
                    container, gap,
                    tile(container, shown[1]),
                    if (more > 1) withMore(container, tile(container, shown[3]), more)
                    else tile(container, shown[3]),
                ),
            )
        }
        container.addView(collage, matchParent())
    }

    private fun tile(container: FrameLayout, url: String) = ImageView(container.context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(
            context.getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
        )
        Glide.with(container).load(url).centerCrop().into(this)
    }

    /** A stack with no covers at all: its icon on a plain tile, not an empty box. */
    private fun placeholder(container: FrameLayout) = ImageView(container.context).apply {
        scaleType = ImageView.ScaleType.CENTER
        setImageResource(R.drawable.ic_round_collections_bookmark_24)
        setBackgroundColor(
            context.getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
        )
        imageTintList = ColorStateList.valueOf(
            context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
    }

    /** [cover] dimmed under a "+[count]" for the entries the collage has no room for. */
    private fun withMore(container: FrameLayout, cover: View, count: Int) =
        FrameLayout(container.context).apply {
            addView(cover, matchParent())
            addView(View(context).apply { setBackgroundColor(Color.argb(150, 0, 0, 0)) }, matchParent())
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.stack_more_entries, count)
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
                    gravity = Gravity.CENTER
                },
                matchParent(),
            )
        }

    private fun row(container: FrameLayout, gap: Int, first: View, second: View) =
        split(container, LinearLayout.HORIZONTAL, gap, first, second)

    private fun column(container: FrameLayout, gap: Int, first: View, second: View) =
        split(container, LinearLayout.VERTICAL, gap, first, second)

    /** Two views sharing [orientation] equally, [gap] apart. */
    private fun split(
        container: FrameLayout, orientation: Int, gap: Int, first: View, second: View,
    ) = LinearLayout(container.context).apply {
        this.orientation = orientation
        val horizontal = orientation == LinearLayout.HORIZONTAL
        fun weighted(withGap: Boolean) = LinearLayout.LayoutParams(
            if (horizontal) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
            if (horizontal) ViewGroup.LayoutParams.MATCH_PARENT else 0,
            1f,
        ).apply {
            if (withGap) {
                if (horizontal) marginStart = gap else topMargin = gap
            }
        }
        addView(first, weighted(withGap = false))
        addView(second, weighted(withGap = true))
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    )
}
