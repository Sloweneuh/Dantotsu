package ani.dantotsu.media.screenshot

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.annotation.RequiresApi
import ani.dantotsu.R

/**
 * Screen capture helpers shared by the anime and manga readers.
 *
 * Manga pages are plain views ([captureView] draws them straight to a bitmap), while the anime
 * frame lives on a [android.view.SurfaceView] that a normal [View.draw] can't reach, so it needs
 * [PixelCopy] (API 24+) to pull the composited pixels — subtitles included — out of the window.
 */
object ScreenshotUtil {

    /** True when the anime frame can be captured on this device (PixelCopy is API 24+). */
    val canCaptureSurface: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    /** The screenshot glyph matching the current orientation (portrait vs landscape frame). */
    fun screenshotIcon(context: Context): Int =
        if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            R.drawable.ic_round_screenshot_frame_2_24
        else
            R.drawable.ic_round_screenshot_frame_24

    /** Draws a laid-out view (e.g. the manga page container) onto an opaque bitmap. */
    fun captureView(view: View): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        return runCatching {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK) // pages rarely fill the view; avoid transparent gaps
            view.draw(canvas)
            bitmap
        }.getOrNull()
    }

    /**
     * Copies the region occupied by [view] out of [window]'s rendered surface. Unlike [captureView]
     * this includes SurfaceView content (the video frame), so it's used for the anime player.
     * Callers should hide the controls before invoking. Result is delivered on the main thread.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun captureFromWindow(window: Window, view: View, onResult: (Bitmap?) -> Unit) {
        if (view.width <= 0 || view.height <= 0) {
            onResult(null); return
        }
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rect = Rect(
            location[0], location[1],
            location[0] + view.width, location[1] + view.height
        )
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, rect, bitmap, { result ->
                onResult(if (result == PixelCopy.SUCCESS) bitmap else null)
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            onResult(null)
        }
    }

    /** Formats a playback position (ms) as `h:mm:ss` or `m:ss`. */
    fun formatTimestamp(positionMs: Long): String {
        val totalSeconds = (positionMs / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0)
            "%d:%02d:%02d".format(hours, minutes, seconds)
        else
            "%d:%02d".format(minutes, seconds)
    }
}
