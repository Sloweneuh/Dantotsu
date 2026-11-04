package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * Display a toast in this context.
 *
 * @param resource the text resource.
 * @param duration the duration of the toast. Defaults to short.
 */
fun Context.toast(
    @StringRes resource: Int,
    duration: Int = Toast.LENGTH_SHORT,
    block: (Toast) -> Unit = {}
): Toast {
    return toast(getString(resource), duration, block)
}

/**
 * Display a message in this context, inside the app like every other ([ani.dantotsu.toast]).
 *
 * Extensions are compiled against this signature, so it still hands back a [Toast], never shown.
 *
 * @param text the text to display.
 * @param duration the duration of the message. Defaults to short.
 */
fun Context.toast(
    text: String?,
    duration: Int = Toast.LENGTH_SHORT,
    block: (Toast) -> Unit = {}
): Toast {
    ani.dantotsu.toast(text.orEmpty(), duration)
    return Toast.makeText(applicationContext, text.orEmpty(), duration).also(block)
}
