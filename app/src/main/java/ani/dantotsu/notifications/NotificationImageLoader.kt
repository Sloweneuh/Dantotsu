package ani.dantotsu.notifications

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import ani.dantotsu.util.Logger
import java.net.URL

/**
 * Fetches a bitmap for a system notification's large icon (media cover, user avatar, ...).
 * Blocks on network I/O, so callers must run this off the main thread.
 */
object NotificationImageLoader {
    fun loadBitmap(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            URL(url).openStream().use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Logger.log("NotificationImageLoader: failed to load $url: ${e.message}")
            null
        }
    }
}
