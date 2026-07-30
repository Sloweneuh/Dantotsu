package ani.dantotsu.settings

import android.widget.ImageView

/**
 * Binds an extension row's update button, spinning it while that extension is being updated.
 *
 * Must be called on every bind, including for rows that aren't updating: the button belongs to a
 * recycled view, so a leftover animation has to be cleared explicitly.
 */
fun ImageView.bindUpdateButton(updating: Boolean, onClick: () -> Unit) {
    setSpinning(updating)
    if (updating) {
        setOnClickListener(null)
        isClickable = false
    } else {
        setOnClickListener { onClick() }
    }
}
