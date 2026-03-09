package ani.dantotsu.views

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatAutoCompleteTextView

/**
 * An [AppCompatAutoCompleteTextView] that strips rich-text formatting on paste,
 * preventing oversized or otherwise styled text from appearing in search bars.
 */
class PlainTextAutoCompleteTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.autoCompleteTextViewStyle,
) : AppCompatAutoCompleteTextView(context, attrs, defStyleAttr) {

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return super.onTextContextMenuItem(android.R.id.pasteAsPlainText)
            }
            // Fallback for API < 23: extract plain text from clipboard manually
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val plainText = clip.getItemAt(0).coerceToText(context).toString()
                val start = selectionStart.coerceAtLeast(0)
                val end = selectionEnd.coerceAtLeast(start)
                editableText.replace(start, end, plainText)
            }
            return true
        }
        return super.onTextContextMenuItem(id)
    }
}
