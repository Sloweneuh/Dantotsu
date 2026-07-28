package ani.dantotsu.media.screenshot

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.annotation.RequiresApi
import ani.dantotsu.R

/**
 * Screen capture helpers shared by the anime and manga readers.
 *
 * Manga pages are plain views ([captureView] draws them straight to a bitmap). The anime frame
 * lives on a [SurfaceView], which a normal [View.draw] can't reach and which a window-level
 * [PixelCopy] doesn't see either: the SurfaceView is composited as its own layer *behind* the
 * window, and the window's own buffer just holds a transparent punch-out hole where the video
 * shows through. Capturing the window therefore yields the letterbox bars and an empty middle,
 * so [captureVideoFrame] reads the video surface directly and composites the overlays itself.
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
     * Grabs the current video frame off [videoSurface] and draws [overlays] (libass, subtitles)
     * on top of it. The result is cropped to the video itself, so there are no letterbox bars.
     * Callers should hide the controls before invoking. Result is delivered on the main thread.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun captureVideoFrame(
        videoSurface: View?,
        overlays: List<View>,
        onResult: (Bitmap?) -> Unit,
    ) {
        if (videoSurface == null || videoSurface.width <= 0 || videoSurface.height <= 0) {
            onResult(null); return
        }
        when (videoSurface) {
            // A TextureView is part of the view hierarchy, so its content is readable directly.
            is TextureView -> {
                val frame = runCatching { videoSurface.bitmap }.getOrNull()
                onResult(frame?.also { drawOverlays(videoSurface, overlays, it) })
            }

            is SurfaceView -> {
                val bitmap = Bitmap.createBitmap(
                    videoSurface.width, videoSurface.height, Bitmap.Config.ARGB_8888
                )
                try {
                    PixelCopy.request(videoSurface, bitmap, { result ->
                        if (result != PixelCopy.SUCCESS) {
                            onResult(null); return@request
                        }
                        drawOverlays(videoSurface, overlays, bitmap)
                        onResult(bitmap)
                    }, Handler(Looper.getMainLooper()))
                } catch (e: Exception) {
                    onResult(null)
                }
            }

            else -> onResult(null)
        }
    }

    /**
     * Composites [overlays] onto [dest], positioned relative to [videoSurface]. Overlays that sit
     * outside the video area (subtitles rendered over the letterbox) are simply clipped away.
     */
    private fun drawOverlays(videoSurface: View, overlays: List<View>, dest: Bitmap) {
        if (overlays.isEmpty()) return
        val canvas = Canvas(dest)
        val base = IntArray(2).also { videoSurface.getLocationInWindow(it) }
        val location = IntArray(2)
        overlays.forEach { overlay ->
            if (!overlay.isShown || overlay.width <= 0 || overlay.height <= 0) return@forEach
            overlay.getLocationInWindow(location)
            val save = canvas.save()
            canvas.translate((location[0] - base[0]).toFloat(), (location[1] - base[1]).toFloat())
            // The libass overlay is a TextureView, whose content draw() can't reach either.
            val texture = (overlay as? TextureView)?.let { runCatching { it.bitmap }.getOrNull() }
            if (texture != null) canvas.drawBitmap(texture, 0f, 0f, null)
            else runCatching { overlay.draw(canvas) }
            canvas.restoreToCount(save)
        }
    }

    /** Formats a clip's span as `12:04 – 12:34`, for the media info row on a clip card. */
    fun formatInterval(startMs: Long, endMs: Long): String =
        "${formatTimestamp(startMs)} – ${formatTimestamp(endMs)}"

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
