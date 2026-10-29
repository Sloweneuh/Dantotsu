package ani.dantotsu.media

import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.widget.TextView
import androidx.core.content.ContextCompat
import ani.dantotsu.R

/**
 * Ends a media page's progress line with a small chevron, saying that tapping it opens more —
 * the sheet of release sources ([ReleaseSourcesSheet]). Inline rather than its own view so it
 * sits right after the text, however long, and takes no room of its own.
 *
 * Call after each time the line's text is set; it appends to whatever is there.
 */
fun TextView.addProgressChevron() {
    val icon = ContextCompat.getDrawable(context, R.drawable.ic_round_arrow_forward_ios_new_24)
        ?.mutate() ?: return
    val size = (textSize * 0.7f).toInt()
    icon.setBounds(0, 0, size, size)
    icon.setTint(currentTextColor)
    icon.alpha = 150
    val align = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ImageSpan.ALIGN_CENTER
    else ImageSpan.ALIGN_BASELINE
    text = SpannableStringBuilder(text).apply {
        append(" ")
        val start = length
        append("​")
        setSpan(ImageSpan(icon, align), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
