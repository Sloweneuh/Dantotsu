package ani.dantotsu.others

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageButton
import ani.dantotsu.px
import com.google.android.material.color.MaterialColors

/**
 * One option of an icon toggle row — the reader's layout and dual-page choices, the chapter
 * list's view mode — drawn the way a chosen option is drawn elsewhere: the chosen one on a tile
 * washed in the theme's primary, the others plain.
 *
 * The rows were built from plain image buttons, and their code says which one is chosen by setting
 * `alpha` — 1 for the chosen one, 0.33 for the rest, which is also what the layouts start them at.
 * Fading was the whole of the "unselected" look. Rather than rewrite every such row, this reads
 * that same alpha as the choice, and shows it as a state: the button itself always stays opaque.
 * A button on its own (direction, sort) is always "chosen", which reads as a tonal action.
 */
class ChoiceIconButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.imageButtonStyle,
) : AppCompatImageButton(context, attrs, defStyleAttr) {

    // Nullable on purpose: View's constructor applies the layout's alpha before this class's own
    // fields exist, through the override below.
    private var fill: GradientDrawable? = null
    private var chosen = true

    init {
        val tile = GradientDrawable().apply { cornerRadius = 14f.px.toFloat() }
        fill = tile
        val ripple = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        background = RippleDrawable(
            ColorStateList.valueOf(MaterialColors.compositeARGBWithAlpha(ripple, 60)),
            tile,
            GradientDrawable().apply { cornerRadius = 14f.px.toFloat(); setColor(Color.WHITE) },
        )
        // What the layout asked for, read back now that there is somewhere to draw it.
        chosen = super.getAlpha() >= CHOSEN_FROM
        super.setAlpha(1f)
        applyState()
    }

    override fun setAlpha(alpha: Float) {
        if (fill == null) {
            // Still constructing: remember it the plain way; init reads it back.
            super.setAlpha(alpha)
            return
        }
        val nowChosen = alpha >= CHOSEN_FROM
        if (nowChosen != chosen) {
            chosen = nowChosen
            applyState()
        }
        super.setAlpha(1f)
    }

    private fun applyState() {
        val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        val onSurfaceVariant =
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        fill?.setColor(if (chosen) MaterialColors.compositeARGBWithAlpha(primary, 52) else Color.TRANSPARENT)
        imageTintList = ColorStateList.valueOf(
            if (chosen) primary else MaterialColors.compositeARGBWithAlpha(onSurfaceVariant, 170)
        )
    }

    private companion object {
        /** Alpha from which the code means "chosen"; it uses 1 and 0.33. */
        const val CHOSEN_FROM = 0.5f
    }
}
