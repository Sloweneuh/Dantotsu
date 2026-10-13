package ani.dantotsu.others

import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.DrawableWrapper
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import ani.dantotsu.R
import ani.dantotsu.util.animationsEnabled
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.DeterminateDrawable
import com.google.android.material.progressindicator.IndeterminateDrawable
import com.google.android.material.progressindicator.LinearProgressIndicatorSpec
import com.google.android.material.transition.MaterialFadeThrough

/**
 * The app's ProgressBar, drawn with Material 3 indicators: the M3 circular spinner, or for a
 * horizontal bar (`style="?android:attr/progressBarStyleHorizontal"`) the Expressive wavy linear
 * indicator. It stays a platform ProgressBar underneath, so sizing, placement and the progress
 * API are exactly the platform's; only the drawables are Material's. A tint set in the layout
 * becomes the indicator colour.
 *
 * Hiding it (loading done) also fades through whatever its parent shows next, so content
 * doesn't pop in; `app:fadeContentOnHide="false"` opts a spinner out (reader and player pages).
 */
class MaterialProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.progressBarStyle,
) : ProgressBar(context, attrs, defStyleAttr) {

    private var fadeContentOnHide = true
    private var linearSpec: LinearProgressIndicatorSpec? = null
    private var circularSpec: CircularProgressIndicatorSpec? = null

    init {
        val tint = (progressTintList ?: indeterminateTintList)?.defaultColor
        progressTintList = null
        indeterminateTintList = null
        if (isHorizontal(attrs, defStyleAttr)) {
            linearSpec = LinearProgressIndicatorSpec(
                context, null, 0, com.google.android.material.R.style.Widget_Material3Expressive_LinearProgressIndicator_Wavy
            ).apply { tint?.let { indicatorColors = intArrayOf(it) } }
        } else {
            circularSpec = CircularProgressIndicatorSpec(context, null)
                .apply { tint?.let { indicatorColors = intArrayOf(it) } }
        }
        applySpecs()
        context.obtainStyledAttributes(attrs, R.styleable.MaterialProgressBar).apply {
            fadeContentOnHide = getBoolean(R.styleable.MaterialProgressBar_fadeContentOnHide, true)
            recycle()
        }
    }

    /**
     * Colours for the indicator and (horizontal bars) the track. Use this rather than the
     * platform tints: a tint colours the whole Material drawable, track included.
     */
    fun setIndicatorColors(indicator: Int, track: Int? = null) {
        linearSpec?.apply {
            indicatorColors = intArrayOf(indicator)
            track?.let { trackColor = it }
        }
        circularSpec?.apply { indicatorColors = intArrayOf(indicator) }
        applySpecs()
    }

    private fun applySpecs() {
        linearSpec?.let {
            progressDrawable = DeterminateDrawable.createLinearDrawable(context, it)
            indeterminateDrawable = Spinning(IndeterminateDrawable.createLinearDrawable(context, it))
        }
        circularSpec?.let {
            indeterminateDrawable = Spinning(IndeterminateDrawable.createCircularDrawable(context, it))
        }
    }

    /**
     * Gives the platform ProgressBar an Animatable it can drive. It starts a spinner by calling
     * start() (on attach, on becoming visible, after a rebind), but Material's start() shows the
     * drawable without animating, and on API 23+ that path cancels the spin and never restarts
     * it: the spinner froze on its first frame. Showing it animated is what runs the spin.
     */
    private class Spinning(private val inner: IndeterminateDrawable<*>) : DrawableWrapper(inner), Animatable {
        private var running = false

        override fun start() {
            running = true
            inner.setVisible(true, true, true)
        }

        override fun stop() {
            running = false
            inner.setVisible(false, true, false)
        }

        override fun isRunning() = running
    }

    override fun setVisibility(visibility: Int) {
        // Runs before the change, so the transition captures the "still loading" state; the
        // content's own visibility change in the same frame is part of it.
        if (fadeContentOnHide && animationsEnabled && getVisibility() == VISIBLE && visibility != VISIBLE &&
            isAttachedToWindow && isLaidOut
        ) {
            // Lists fade in whole, never item by item: a transition animating RecyclerView items
            // can leave one it recycles mid-fade invisible.
            (parent as? ViewGroup)?.let {
                TransitionManager.beginDelayedTransition(
                    it, MaterialFadeThrough().excludeChildren(RecyclerView::class.java, true)
                )
            }
        }
        super.setVisibility(visibility)
    }

    private fun isHorizontal(attrs: AttributeSet?, defStyleAttr: Int): Boolean {
        if (defStyleAttr == android.R.attr.progressBarStyleHorizontal) return true
        val style = attrs?.styleAttribute ?: 0
        if (style == 0) return false
        return runCatching { resources.getResourceEntryName(style) }
            .getOrNull()?.contains("Horizontal", ignoreCase = true) == true
    }
}
