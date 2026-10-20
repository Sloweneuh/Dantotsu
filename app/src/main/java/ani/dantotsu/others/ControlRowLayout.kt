package ani.dantotsu.others

import android.content.Context
import android.text.StaticLayout
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import ani.dantotsu.R
import kotlin.math.max

/**
 * The layout of a settings row with a control at its end — a stepper (− / value / +) or a segmented
 * choice: icon, text (title and description), and the control beside the text when there is room
 * for it, under the text when there isn't.
 *
 * "Room" is judged on the text as it would sit beside the control: the title on one line and the
 * description on at most two. Past that the text was being squeezed into a narrow column that
 * wrapped a word or two to a line, so the control moves to a line of its own, under the title, and
 * the text gets the whole width. Which way a row goes is decided per row, on its own words and the
 * width it has: a short title keeps its control beside it on a phone; a long description drops it.
 *
 * Children, in order: the icon, the text block (holding R.id.settingsTitle and R.id.settingsDesc),
 * the control.
 */
class ControlRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val density = resources.displayMetrics.density
    private val iconGap = (24 * density).toInt()
    private val besideGap = (12 * density).toInt()
    private val belowGap = (10 * density).toInt()

    private var beside = true

    private val icon get() = getChildAt(0)
    private val text get() = getChildAt(1)
    private val control get() = getChildAt(2)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        measureChild(icon, unspecified, unspecified)
        measureChild(control, unspecified, unspecified)

        val textStart = icon.measuredWidth + iconGap
        val besideWidth = width - paddingLeft - paddingRight - textStart - control.measuredWidth - besideGap
        beside = besideWidth > 0 && fits(besideWidth)
        val textWidth = if (beside) besideWidth else width - paddingLeft - paddingRight - textStart
        text.measure(MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.EXACTLY), unspecified)

        val content = if (beside) {
            max(max(text.measuredHeight, control.measuredHeight), icon.measuredHeight)
        } else {
            max(text.measuredHeight, icon.measuredHeight) + belowGap + control.measuredHeight
        }
        setMeasuredDimension(width, content + paddingTop + paddingBottom)
    }

    /**
     * Whether the text, [width] wide, keeps its title to one line and its description to two.
     *
     * Worked out from the words and their paint rather than read back from the text views after
     * measuring them: those line counts weren't always current, and a row whose title wrapped and
     * whose description ran to five lines still kept its stepper beside them, clipped at the bottom.
     */
    private fun fits(width: Int): Boolean {
        val title = text.findViewById<TextView>(R.id.settingsTitle)
        val desc = text.findViewById<TextView>(R.id.settingsDesc)
        val titleFits = title == null ||
            title.paint.measureText(title.text.toString()) <= width - title.totalPaddingLeft - title.totalPaddingRight
        val descFits = desc == null || desc.visibility == View.GONE || desc.text.isNullOrEmpty() ||
            lines(desc, width - desc.totalPaddingLeft - desc.totalPaddingRight) <= 2
        return titleFits && descFits
    }

    private fun lines(view: TextView, width: Int): Int {
        if (width <= 0) return Int.MAX_VALUE
        val text = view.text
        return StaticLayout.Builder.obtain(text, 0, text.length, view.paint, width)
            .setLineSpacing(view.lineSpacingExtra, view.lineSpacingMultiplier)
            .setIncludePad(view.includeFontPadding)
            .build()
            .lineCount
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = paddingLeft
        val top = paddingTop
        val textLeft = left + icon.measuredWidth + iconGap
        if (beside) {
            val rowHeight = measuredHeight - paddingTop - paddingBottom
            fun centred(v: View) = top + (rowHeight - v.measuredHeight) / 2
            icon.layout(left, centred(icon), left + icon.measuredWidth, centred(icon) + icon.measuredHeight)
            text.layout(textLeft, centred(text), textLeft + text.measuredWidth, centred(text) + text.measuredHeight)
            val stepperLeft = measuredWidth - paddingRight - control.measuredWidth
            control.layout(stepperLeft, centred(control), stepperLeft + control.measuredWidth,
                centred(control) + control.measuredHeight)
        } else {
            // The icon level with the title's first line, as on the rows whose text wraps.
            val title = text.findViewById<TextView>(R.id.settingsTitle)
            val firstLine = title?.takeIf { it.layout != null }?.let { it.layout.getLineBottom(0) } ?: icon.measuredHeight
            val iconTop = top + max(0, (firstLine - icon.measuredHeight) / 2)
            icon.layout(left, iconTop, left + icon.measuredWidth, iconTop + icon.measuredHeight)
            text.layout(textLeft, top, textLeft + text.measuredWidth, top + text.measuredHeight)
            val stepperTop = top + max(text.measuredHeight, icon.measuredHeight) + belowGap
            control.layout(textLeft, stepperTop, textLeft + control.measuredWidth,
                stepperTop + control.measuredHeight)
        }
    }
}
