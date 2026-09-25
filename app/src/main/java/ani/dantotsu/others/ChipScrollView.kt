package ani.dantotsu.others

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView

/**
 * The scroll container for a chip row that can also lay its chips out over several lines.
 *
 * A [HorizontalScrollView] always measures its child with an unbounded width - that is what lets a
 * long row scroll - and a ChipGroup given an unbounded width never wraps, whatever its `singleLine`
 * says. So the expanded state needs the child measured against the viewport instead, which is all
 * [wrapChild] changes; scrolling then has nothing left to scroll.
 *
 * The padding is the row's margin: chips are clipped at it, and fade out as they reach it while
 * there is more to scroll that way. Expanded, [ChipFlowGroup] fades its long chips to match.
 */
class ChipScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    init {
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength((24 * resources.displayMetrics.density).toInt())
    }

    /** True while the row is expanded: measure the child against the viewport so it wraps. */
    var wrapChild: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    private fun viewportSpec(parentWidthMeasureSpec: Int): Int {
        val available = MeasureSpec.getSize(parentWidthMeasureSpec) - paddingLeft - paddingRight
        return MeasureSpec.makeMeasureSpec(available.coerceAtLeast(0), MeasureSpec.AT_MOST)
    }

    override fun measureChild(child: View, parentWidthMeasureSpec: Int, parentHeightMeasureSpec: Int) {
        if (!wrapChild) return super.measureChild(child, parentWidthMeasureSpec, parentHeightMeasureSpec)
        child.measure(
            viewportSpec(parentWidthMeasureSpec),
            ViewGroup.getChildMeasureSpec(parentHeightMeasureSpec, paddingTop + paddingBottom, child.layoutParams.height)
        )
    }

    override fun measureChildWithMargins(
        child: View, parentWidthMeasureSpec: Int, widthUsed: Int,
        parentHeightMeasureSpec: Int, heightUsed: Int,
    ) {
        if (!wrapChild) return super.measureChildWithMargins(
            child, parentWidthMeasureSpec, widthUsed, parentHeightMeasureSpec, heightUsed
        )
        val lp = child.layoutParams as MarginLayoutParams
        val available = MeasureSpec.getSize(parentWidthMeasureSpec) -
                paddingLeft - paddingRight - lp.leftMargin - lp.rightMargin - widthUsed
        child.measure(
            MeasureSpec.makeMeasureSpec(available.coerceAtLeast(0), MeasureSpec.AT_MOST),
            ViewGroup.getChildMeasureSpec(
                parentHeightMeasureSpec,
                paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin + heightUsed,
                lp.height
            )
        )
    }
}
