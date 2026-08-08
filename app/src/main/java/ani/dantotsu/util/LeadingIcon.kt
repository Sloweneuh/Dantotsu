package ani.dantotsu.util

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat

/**
 * Puts [res] in front of a line of text, as part of the label rather than beside it.
 *
 * Two things have to be corrected for, which is why this isn't just `drawableStart` in a layout.
 * The icons are authored at 24dp, and next to a 10-13sp line that reads as a button; and a compound
 * drawable, unlike an `ImageView`, carries no tint of its own, so a stock Material vector would draw
 * in the white it was filled with — invisible on a light surface.
 *
 * @param sizeDp the box the icon is drawn in, chosen against the text size rather than the drawable.
 * @param tint defaults to the view's own text colour, which is what keeps the icon reading as part
 *   of the label; pass one only where the icon is deliberately saying something the text isn't.
 */
fun TextView.setLeadingIcon(
    @DrawableRes res: Int,
    sizeDp: Float,
    tint: Int = currentTextColor,
    gapDp: Float = 6f,
) {
    val density = resources.displayMetrics.density
    val icon = ContextCompat.getDrawable(context, res)?.mutate() ?: return
    val px = (sizeDp * density).toInt()
    icon.setBounds(0, 0, px, px)
    TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(tint))
    setCompoundDrawablesRelative(icon, null, null, null)
    compoundDrawablePadding = (gapDp * density).toInt()
}
