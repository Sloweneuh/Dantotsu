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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.InputStream
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

    private val cacheSize = (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt()
    private val bitmapCache = LruCache<String, Bitmap>(cacheSize)

    /**
     * @param rounded applies [roundCorners] at the source bitmap's own resolution. Callers that size
     *   the bitmap themselves want this off and should use [roundedCover] instead — see why there.
     */
    fun downloadImageAsBitmap(imageUrl: String, rounded: Boolean = true): Bitmap? {
        var bitmap: Bitmap? = null

        runBlocking(Dispatchers.IO) {
            val cacheName = imageUrl.substringAfterLast("/")
            bitmap = bitmapCache[cacheName]
            if (bitmap != null) return@runBlocking
            var inputStream: InputStream? = null
            var urlConnection: HttpURLConnection? = null
            try {
                val url = URL(imageUrl)
                urlConnection = url.openConnection() as HttpURLConnection
                urlConnection.requestMethod = "GET"
                urlConnection.connect()

                if (urlConnection.responseCode == HttpURLConnection.HTTP_OK) {
                    inputStream = urlConnection.inputStream
                    bitmap = BitmapFactory.decodeStream(inputStream)
                    bitmap?.let { bitmapCache.put(cacheName, it) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                inputStream?.close()
                urlConnection?.disconnect()
            }
        }
        return bitmap?.let { if (rounded) roundCorners(it) else it }
    }
}