package ani.dantotsu.media

import android.widget.TextView
import androidx.core.content.ContextCompat
import ani.dantotsu.R
import ani.dantotsu.px

/**
 * Ends a media page's progress line with a small chevron, saying that tapping it opens more —
 * the sheet of release sources ([ReleaseSourcesSheet]). A compound drawable rather than an inline
 * span: a span is a run of its own that line breaking can leave alone on a second line.
 */
fun TextView.addProgressChevron() {
    val icon = ContextCompat.getDrawable(context, R.drawable.ic_round_arrow_forward_ios_24)
        ?.mutate() ?: return
    val size = (textSize * 0.7f).toInt()
    icon.setBounds(0, 0, size, size)
    icon.setTint(currentTextColor)
    icon.alpha = 150
    compoundDrawablePadding = 3f.px
    setCompoundDrawablesRelative(null, null, icon, null)
}
