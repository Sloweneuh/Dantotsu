package ani.dantotsu.notifications

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import ani.dantotsu.util.Logger
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a bitmap for a system notification's large icon (media cover, user avatar, ...).
 * Blocks on network I/O, so callers must run this off the main thread.
 *
 * Runs in background checks, often on a device that has only just woken, so one slow or dropped
 * fetch is normal rather than rare — and a cover that fails takes the whole cover layout with it.
 * So it has timeouts (a plain openStream could hang a check indefinitely), identifies itself the
 * way the app's other requests do, and tries once more before giving up.
 */
object NotificationImageLoader {
    private const val ATTEMPTS = 2
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    fun loadBitmap(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        repeat(ATTEMPTS) { attempt ->
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        Logger.log("NotificationImageLoader: HTTP $code for $url (attempt ${attempt + 1})")
                    } else {
                        val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) }
                        if (bitmap != null) return bitmap
                        Logger.log("NotificationImageLoader: could not decode $url (attempt ${attempt + 1})")
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Logger.log("NotificationImageLoader: failed to load $url (attempt ${attempt + 1}): ${e.message}")
            }
        }
        return null
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
}
