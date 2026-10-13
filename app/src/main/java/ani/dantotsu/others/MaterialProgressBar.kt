package ani.dantotsu.others

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.ProgressBar
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

    init {
        val tint = (progressTintList ?: indeterminateTintList)?.defaultColor
        progressTintList = null
        indeterminateTintList = null
        if (isHorizontal(attrs, defStyleAttr)) {
            val spec = LinearProgressIndicatorSpec(
                context, null, 0, com.google.android.material.R.style.Widget_Material3Expressive_LinearProgressIndicator_Wavy
            )
            tint?.let { spec.indicatorColors = intArrayOf(it) }
            progressDrawable = DeterminateDrawable.createLinearDrawable(context, spec)
            indeterminateDrawable = IndeterminateDrawable.createLinearDrawable(context, spec)
        } else {
            val spec = CircularProgressIndicatorSpec(context, null)
            tint?.let { spec.indicatorColors = intArrayOf(it) }
            indeterminateDrawable = IndeterminateDrawable.createCircularDrawable(context, spec)
        }
        context.obtainStyledAttributes(attrs, R.styleable.MaterialProgressBar).apply {
            fadeContentOnHide = getBoolean(R.styleable.MaterialProgressBar_fadeContentOnHide, true)
            recycle()
        }
    }

    override fun setVisibility(visibility: Int) {
        // Runs before the change, so the transition captures the "still loading" state; the
        // content's own visibility change in the same frame is part of it.
        if (fadeContentOnHide && animationsEnabled && getVisibility() == VISIBLE && visibility != VISIBLE &&
            isAttachedToWindow && isLaidOut
        ) {
            (parent as? ViewGroup)?.let { TransitionManager.beginDelayedTransition(it, MaterialFadeThrough()) }
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
