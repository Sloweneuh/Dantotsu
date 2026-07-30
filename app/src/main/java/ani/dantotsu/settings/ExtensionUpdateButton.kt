package ani.dantotsu.settings

import android.view.animation.AnimationUtils
import android.widget.ImageView
import ani.dantotsu.R

/**
 * Binds an extension row's update button, spinning it while that extension is being updated.
 *
 * Must be called on every bind, including for rows that aren't updating: the button belongs to a
 * recycled view, so a leftover animation has to be cleared explicitly.
 */
fun ImageView.bindUpdateButton(updating: Boolean, onClick: () -> Unit) {
    if (updating) {
        setOnClickListener(null)
        isClickable = false
        // Rebinding an already spinning button would restart the animation and make it stutter.
        if (animation == null) {
            startAnimation(AnimationUtils.loadAnimation(context, R.anim.rotate_indefinite))
        }
    } else {
        clearAnimation()
        setOnClickListener { onClick() }
    }
}
