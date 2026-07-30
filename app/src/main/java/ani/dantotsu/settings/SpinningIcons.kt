package ani.dantotsu.settings

import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.graphics.drawable.RotateDrawable
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import ani.dantotsu.R
import com.google.android.material.button.MaterialButton

/**
 * Spins an image view for as long as the action it stands for is running, so a long operation reads
 * as busy rather than stuck. Idempotent: re-asserting the current state leaves a running animation
 * alone instead of restarting it into a stutter.
 */
fun ImageView.setSpinning(spinning: Boolean) {
    if (spinning) {
        if (animation == null) {
            startAnimation(AnimationUtils.loadAnimation(context, R.anim.rotate_indefinite))
        }
    } else {
        clearAnimation()
    }
}

// Matches res/anim/rotate_indefinite.xml, so everything in the app turns the same way at the same
// speed whichever of the two mechanisms drives it.
private const val SPIN_DURATION_MS = 700L
private const val SPIN_DEGREES = -360f
private const val MAX_LEVEL = 10000

/**
 * [setSpinning] for a button's icon.
 *
 * A button can't use the animation above: it would rotate the whole view, and these are tonal
 * buttons, so the filled pill would spin along with the glyph. Only the icon turns here — it's
 * wrapped in a [RotateDrawable] whose level a [ValueAnimator] drives.
 *
 * Idempotent like [setSpinning], and it restores the icon it replaced when it stops, so it's safe to
 * call from a RecyclerView bind. Do reset it *before* changing the icon, though: swapping [icon]
 * out from under a running spin would leave the animator turning a drawable nothing is showing.
 */
fun MaterialButton.setIconSpinning(spinning: Boolean) {
    val running = getTag(R.id.spin_animator) as? ValueAnimator
    if (spinning == (running != null)) return
    if (spinning) {
        val original = icon ?: return
        val rotating = RotateDrawable().apply {
            drawable = original
            fromDegrees = 0f
            toDegrees = SPIN_DEGREES
        }
        icon = rotating
        setTag(R.id.spin_original_icon, original)
        setTag(
            R.id.spin_animator,
            ValueAnimator.ofInt(0, MAX_LEVEL).apply {
                duration = SPIN_DURATION_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { rotating.level = it.animatedValue as Int }
                start()
            }
        )
    } else {
        running?.cancel()
        setTag(R.id.spin_animator, null)
        (getTag(R.id.spin_original_icon) as? Drawable)?.let { icon = it }
        setTag(R.id.spin_original_icon, null)
    }
}
