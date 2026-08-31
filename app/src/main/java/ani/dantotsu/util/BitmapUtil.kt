package ani.dantotsu.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.collection.LruCache
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL

object BitmapUtil {
    private fun roundCorners(bitmap: Bitmap, cornerRadius: Float = 20f): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()
        paint.isAntiAlias = true
        paint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

        return output
    }

    /**
     * Centre-crops to exactly [targetWidth] x [targetHeight] pixels, then rounds to [cornerRadius].
     *
     * Both halves matter, and doing them in this order is the point. [roundCorners] rounds the source
     * bitmap at its own resolution — an AniList cover is several times wider than the view that shows
     * it, so a radius in source pixels shrinks to a fraction of itself once scaled down. Worse, the
     * cover's 2:3 aspect doesn't match the row's slot, so the `centerCrop` that follows would slice the
     * rounded corners off the top and bottom anyway. Sizing first and rounding last means the radius is
     * the radius that actually gets drawn.
     */
    fun roundedCover(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        cornerRadius: Float
    ): Bitmap {
        if (targetWidth <= 0 || targetHeight <= 0) return bitmap
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

        // The same geometry ImageView's centerCrop uses: fill the box, overflow on the longer axis.
        val scale = maxOf(targetWidth / bitmap.width.toFloat(), targetHeight / bitmap.height.toFloat())
        val dx = (targetWidth - bitmap.width * scale) / 2f
        val dy = (targetHeight - bitmap.height * scale) / 2f
        paint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            })
        }
        canvas.drawRoundRect(
            RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat()),
            cornerRadius, cornerRadius, paint
        )
        return output
    }

    /**
     * Full circular crop, for the small avatar badge a widget activity row overlays on a cover —
     * [roundCorners]'s fixed radius reads as barely-rounded once shrunk to badge size, not as an avatar.
     */
    fun toCircularBitmap(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val radius = minOf(bitmap.width, bitmap.height) / 2f
        canvas.drawCircle(bitmap.width / 2f, bitmap.height / 2f, radius, paint)
        return output
    }

    // A sixteenth of the heap, in KB — and the sizeOf() override is what makes that arithmetic mean
    // what it says. LruCache's default sizeOf() counts 1 per entry, so without it this "budget" was
    // a cap of 32768 covers, which is no bound at all: widget covers accumulated for the life of the
    // process at up to several MB apiece and nothing was ever evicted. MangaCache's own bitmap cache
    // is sized the same way, for the same reason.
    private val maxCacheKb = (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt()
    private val bitmapCache = object : LruCache<String, Bitmap>(maxCacheKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Drops every cached cover. Called when the process is asked to hand memory back. */
    fun evictAll() = bitmapCache.evictAll()

    /**
     * @param rounded applies [roundCorners] at the source bitmap's own resolution. Callers that size
     *   the bitmap themselves want this off and should use [roundedCover] instead — see why there.
     *
     * Blocks the calling thread, and every caller is already off the main thread. It used to hop onto
     * [kotlinx.coroutines.Dispatchers.IO] through `runBlocking` and wait there — pointless, since the
     * work below is synchronous either way, and actively harmful in a widget: the app-widget host
     * interrupts its own worker once a collection fetch runs past the host's deadline, and
     * `runBlocking` answers an interrupt by throwing InterruptedException *out* of the call, past the
     * catch below and up through RemoteViewsFactory.getViewAt() into a crash. Done inline, an
     * interrupt arrives as the InterruptedIOException the catch already handles and the row just
     * renders without its cover.
     */
    fun downloadImageAsBitmap(imageUrl: String, rounded: Boolean = true): Bitmap? {
        val cacheName = imageUrl.substringAfterLast("/")
        var bitmap: Bitmap? = bitmapCache[cacheName]
        if (bitmap == null) {
            var inputStream: InputStream? = null
            var urlConnection: HttpURLConnection? = null
            try {
                val url = URL(imageUrl)
                urlConnection = url.openConnection() as HttpURLConnection
                urlConnection.requestMethod = "GET"
                // Bounded so a stalled connection can't hold a widget's row fetch open long enough
                // for the host to give up on it in the first place.
                urlConnection.connectTimeout = TIMEOUT_MS
                urlConnection.readTimeout = TIMEOUT_MS
                urlConnection.connect()

                if (urlConnection.responseCode == HttpURLConnection.HTTP_OK) {
                    inputStream = urlConnection.inputStream
                    bitmap = BitmapFactory.decodeStream(inputStream)
                    bitmap?.let { bitmapCache.put(cacheName, it) }
                }
            } catch (e: Exception) {
                // An interrupt reaches us as InterruptedIOException, having cleared the thread's
                // interrupt flag; put it back so whoever owns the thread still sees the request.
                if (e is InterruptedIOException) Thread.currentThread().interrupt()
                e.printStackTrace()
            } finally {
                runCatching { inputStream?.close() }
                urlConnection?.disconnect()
            }
        }
        return bitmap?.let { if (rounded) roundCorners(it) else it }
    }

    private const val TIMEOUT_MS = 10_000
}
