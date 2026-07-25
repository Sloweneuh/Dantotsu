package ani.dantotsu

import android.view.View
import androidx.core.widget.NestedScrollView

/**
 * Wires this view as a round "scroll to top" button (matching the one used for the
 * anime episode / manga chapter list) for chapter/episode lists built from
 * manually-inflated views inside a [NestedScrollView]: hidden near the top, shown
 * once scrolled past [thresholdPx], tap smooth-scrolls back to the top.
 */
fun View.bindScrollToTop(
    scrollView: NestedScrollView,
    thresholdPx: Int = 400,
) {
    setOnClickListener {
        scrollView.smoothScrollTo(0, 0)
    }
    scrollView.setOnScrollChangeListener(
        NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
            if (scrollY > thresholdPx) {
                translationY = -navBarHeight.toFloat()
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
    )
}
