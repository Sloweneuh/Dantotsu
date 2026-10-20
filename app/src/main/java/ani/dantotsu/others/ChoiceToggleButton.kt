package ani.dantotsu.others

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.graphics.drawable.DrawableWrapperCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/**
 * One segment of a choice row drawn as a Material segmented button group
 * ([com.google.android.material.button.MaterialButtonToggleGroup]): the reader's layout, direction
 * and dual-page rows, the episode/chapter view mode, the theme's light/dark/auto.
 *
 * The code driving those rows says which option is chosen by setting `alpha` — 1 for the chosen
 * one, 0.33 for the rest, as the layouts also start them — the same protocol [ChoiceIconButton]
 * reads. This reads it as the button's checked state and stays opaque, so the rows became
 * segmented groups without rewriting any of them. A tap checks the button through the group, and
 * the code's click listener then sets the same alphas, which agree.
 */
class ChoiceToggleButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {

    // False until construction ends: View's constructor applies the layout's alpha through the
    // override below before this class's own fields exist.
    private var ready = false

    init {
        isCheckable = true
        isChecked = super.getAlpha() >= CHOSEN_FROM
        super.setAlpha(1f)
        // No outline, and the quiet colours of the settings tiles rather than the tonal style's own:
        // the chosen segment on a wash of the accent, the rest on a faint neutral fill.
        strokeWidth = 0
        val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant =
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val checked = intArrayOf(android.R.attr.state_checked)
        val hovered = intArrayOf(android.R.attr.state_hovered)
        val checkedHovered = intArrayOf(android.R.attr.state_checked, android.R.attr.state_hovered)
        val other = intArrayOf()
        // Hover deepens the segment's own fill, over the whole segment. The style's hover lift
        // (elevation, with its lighter overlay) is turned off: it drew a square over the icon.
        backgroundTintList = ColorStateList(
            arrayOf(checkedHovered, checked, hovered, other),
            intArrayOf(
                MaterialColors.compositeARGBWithAlpha(primary, 84),
                MaterialColors.compositeARGBWithAlpha(primary, 56),
                MaterialColors.compositeARGBWithAlpha(onSurface, 34),
                MaterialColors.compositeARGBWithAlpha(onSurface, 18),
            ),
        )
        stateListAnimator = null
        elevation = 0f
        iconTint = ColorStateList(
            arrayOf(checked, other),
            intArrayOf(primary, MaterialColors.compositeARGBWithAlpha(onSurfaceVariant, 190)),
        )
        // Pressed only: a mouse resting on a segment (WSA) lit it as though it were chosen.
        rippleColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), other),
            intArrayOf(MaterialColors.compositeARGBWithAlpha(primary, 60), Color.TRANSPARENT),
        )
        // A mirrored or rotated icon (the layouts ask for it with scaleX="-1" or a rotation — the
        // four reading directions are one arrow turned four ways) is turned on its own: turned whole,
        // a segment at either end of the group would take its rounded corners to the wrong side.
        val mirror = scaleX < 0f
        val degrees = rotation
        if (mirror || degrees != 0f) {
            scaleX = 1f
            rotation = 0f
            turnIcon(mirror, degrees)
        }
        ready = true
    }

    /** Mirrors and/or rotates the icon alone, for a button built in code rather than a layout. */
    fun turnIcon(mirror: Boolean, degrees: Float) {
        if (!mirror && degrees == 0f) return
        icon = icon?.let { TurnedDrawable(it, mirror, degrees) }
    }

    /** [wrapped], drawn mirrored and/or rotated about its centre; tint and state still reach it. */
    private class TurnedDrawable(
        wrapped: Drawable,
        private val mirror: Boolean,
        private val degrees: Float,
    ) : DrawableWrapperCompat(wrapped) {
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            canvas.rotate(degrees, cx, cy)
            if (mirror) canvas.scale(-1f, 1f, cx, cy)
            super.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    override fun setAlpha(alpha: Float) {
        if (!ready) {
            super.setAlpha(alpha)
            return
        }
        val chosen = alpha >= CHOSEN_FROM
        if (chosen != isChecked) isChecked = chosen
        super.setAlpha(1f)
    }

    private companion object {
        /** Alpha from which the code means "chosen"; it uses 1 and 0.33. */
        const val CHOSEN_FROM = 0.5f
    }
}
