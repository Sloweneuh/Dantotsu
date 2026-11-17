package ani.dantotsu.others

import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import ani.dantotsu.R

/**
 * One button that flips between list and grid layouts. It shows the layout currently in use,
 * and spins the icons over when it changes.
 */
fun ImageView.setViewModeIcon(showingGrid: Boolean, animate: Boolean = false) {
    val icon = if (showingGrid) R.drawable.ic_round_grid_view_24 else R.drawable.ic_round_view_list_24
    val label = context.getString(if (showingGrid) R.string.cd_grid_view else R.string.cd_list_view)
    animate().cancel()
    if (!animate || !isLaidOut) {
        setImageResource(icon)
        contentDescription = label
        rotation = 0f; scaleX = 1f; scaleY = 1f; imageAlpha = 255
        return
    }
    contentDescription = label
    animate()
        .rotation(90f).scaleX(0.4f).scaleY(0.4f)
        .setDuration(110)
        .setInterpolator(AccelerateInterpolator())
        .setUpdateListener { imageAlpha = (255 * (1f - it.animatedFraction)).toInt() }
        .withEndAction {
            setImageResource(icon)
            rotation = -90f
            animate()
                .rotation(0f).scaleX(1f).scaleY(1f)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(2f))
                .setUpdateListener { imageAlpha = (255 * it.animatedFraction).toInt().coerceAtMost(255) }
                .withEndAction(null)
                .start()
        }
        .start()
}
