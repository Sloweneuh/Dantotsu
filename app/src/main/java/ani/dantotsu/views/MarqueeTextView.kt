package ani.dantotsu.views

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * A TextView that reports itself as focused so marquee (ellipsize="marquee") runs without actual focus.
 * Now includes a small start delay so marquee doesn't begin immediately.
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    companion object {
        // Small delay before starting marquee, in milliseconds
        private const val START_DELAY_MS = 800L
    }

    private var marqueeActive = false
    private val startRunnable = Runnable {
        marqueeActive = true
        // Trigger a state change so marquee is evaluated again
        invalidate()
        requestLayout()
    }

    override fun isFocused(): Boolean {
        // Return the controlled flag instead of always true so we can delay marquee start
        return marqueeActive
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Ensure marquee is off initially and schedule it to start after a short delay
        marqueeActive = false
        removeCallbacks(startRunnable)
        postDelayed(startRunnable, START_DELAY_MS)
    }

    override fun onDetachedFromWindow() {
        // Clean up any pending callbacks to avoid leaks
        removeCallbacks(startRunnable)
        super.onDetachedFromWindow()
    }
}
