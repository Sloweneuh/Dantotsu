package ani.dantotsu.util

import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import ani.dantotsu.openLinkInBrowser
import kotlin.math.abs

/**
 * Touch handling for a TextView whose text carries link spans and which also has a click listener
 * of its own (the usual "tap the synopsis to expand it").
 *
 * [TextView.onTouchEvent] runs the view's click before the movement method gets to the span, so
 * with a plain [android.text.method.LinkMovementMethod] a tap on a link both expands the synopsis
 * and follows the link. Handling links here, ahead of the view, gives them precedence: a tap
 * follows the link ([ClickableSpan.onClick], i.e. the markwon link resolver — in-app for the
 * screens the app has), a long press hands the URL to [onLongPress] — by default the browser, for
 * the links that would otherwise never get there — and touches that don't land on a link fall
 * through to the view as before.
 */
class LinkTouchListener(
    private val onLongPress: (url: String) -> Unit = { openLinkInBrowser(it) },
) : View.OnTouchListener {

    /** Set on a link press and kept until the gesture ends, so no other handler sees its tail. */
    private var tracking = false

    /** The pressed link while the gesture still counts as a tap; null once it moved too far. */
    private var span: ClickableSpan? = null
    private var longPress: Runnable? = null
    private var downX = 0f
    private var downY = 0f

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val textView = v as? TextView ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pressed = linkAt(textView, event) ?: return false
                tracking = true
                span = pressed
                downX = event.x
                downY = event.y
                val url = (pressed as? URLSpan)?.url
                if (!url.isNullOrBlank()) {
                    longPress = Runnable {
                        longPress = null
                        span = null
                        textView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onLongPress(url)
                    }.also {
                        textView.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val slop = ViewConfiguration.get(textView.context).scaledTouchSlop
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                    // A drag, not a press: the scroll parent takes over from here (and cancels
                    // us), but should it not, neither the link nor the long press may fire.
                    cancelLongPress(textView)
                    span = null
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                val tapped = span
                reset(textView)
                tapped?.onClick(textView)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                reset(textView)
                return true
            }
        }
        return tracking
    }

    private fun cancelLongPress(textView: TextView) {
        longPress?.let { textView.removeCallbacks(it) }
        longPress = null
    }

    private fun reset(textView: TextView) {
        cancelLongPress(textView)
        span = null
        tracking = false
    }

    private fun linkAt(textView: TextView, event: MotionEvent): ClickableSpan? {
        val text = textView.text as? Spanned ?: return null
        val layout = textView.layout ?: return null
        val x = event.x - textView.totalPaddingLeft + textView.scrollX
        val y = (event.y - textView.totalPaddingTop + textView.scrollY).toInt()
        if (y < 0 || y > layout.height) return null
        val line = layout.getLineForVertical(y)
        // getOffsetForHorizontal clamps to the nearest character, which would make the empty
        // space after a short last line act as whatever link ends it.
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x)
        return text.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()
    }
}
