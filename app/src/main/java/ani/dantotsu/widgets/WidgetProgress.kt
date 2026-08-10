package ani.dantotsu.widgets

import android.content.Context
import ani.dantotsu.util.Logger

/**
 * Progress recorded the moment it changes on this device.
 *
 * The waiting widget works out what is unread from counts MALSync gathered on a schedule, and those
 * carry the progress as it stood *then*. Read three chapters and the widget would go on claiming they
 * are waiting until the next unread check hours later. This is the local correction: whatever
 * [ani.dantotsu.connections.updateProgress] last committed wins over the cached figure.
 *
 * Not a general progress store — only a floor for the widget's arithmetic. It is never read as truth
 * about where the user is, just as "at least this far".
 */
object WidgetProgress {

    private const val PREFS = "ani.dantotsu.widget.progress"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Records progress for a media, keyed the way widget rows are (a MangaUpdates key for MU series). */
    fun record(context: Context, mediaId: Int, progress: Int) {
        try {
            if (progress <= of(context, mediaId)) return
            prefs(context).edit().putInt(mediaId.toString(), progress).apply()
        } catch (e: Exception) {
            Logger.log("WidgetProgress: failed to record $mediaId: ${e.message}")
        }
    }

    /** The furthest this device has seen for [mediaId], or 0 when it has never been read here. */
    fun of(context: Context, mediaId: Int): Int =
        try {
            prefs(context).getInt(mediaId.toString(), 0)
        } catch (e: Exception) {
            0
        }
}
