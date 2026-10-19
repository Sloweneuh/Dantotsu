package ani.dantotsu.others

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView

/**
 * A single-line label drawn with its ink — not its line box — centred in the view: for a count in a
 * small pill or circle. Poppins sets digits off the middle of their own line box (tall ascent, uneven
 * side bearings), so gravity and padding alone left a "1" visibly high and to one side however the
 * box was sized. Only the text moves; the background stays where the view is.
 */
class InkCenteredTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val ink = Rect()

    init {
        // Laid out from the top-left so the text sits exactly where [onDraw] measures it from;
        // TextView's own centring adds an offset it doesn't expose. The centring is done below.
        gravity = Gravity.TOP or Gravity.START
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        val value = text?.toString().orEmpty()
        if (layout == null || value.isEmpty()) {
            super.onDraw(canvas)
            return
        }
        paint.getTextBounds(value, 0, value.length, ink)
        val inkX = totalPaddingLeft + layout.getLineLeft(0) + ink.exactCenterX()
        val inkY = extendedPaddingTop + layout.getLineBaseline(0) + ink.exactCenterY()
        canvas.save()
        canvas.translate(width / 2f - inkX, height / 2f - inkY)
        super.onDraw(canvas)
        canvas.restore()
    }
}
