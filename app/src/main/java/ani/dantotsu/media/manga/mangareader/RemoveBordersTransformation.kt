package ani.dantotsu.media.manga.mangareader

import android.graphics.Bitmap
import android.graphics.Color
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

class RemoveBordersTransformation(private val white: Boolean, private val threshHold: Int) :
    BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        val width = toTransform.width
        val height = toTransform.height

        var left = 0
        var top = 0
        var right = width - 1
        var bottom = height - 1

        // Read one column/row at a time — vastly faster than individual getPixel() calls
        // since each getPixels() is a single JNI call instead of one per pixel.
        val col = IntArray(height)
        val row = IntArray(width)

        leftScan@ for (x in 0 until width) {
            toTransform.getPixels(col, 0, 1, x, 0, 1, height)
            for (pixel in col) {
                if (isPixelNotWhite(pixel)) { left = x; break@leftScan }
            }
        }

        rightScan@ for (x in width - 1 downTo left) {
            toTransform.getPixels(col, 0, 1, x, 0, 1, height)
            for (pixel in col) {
                if (isPixelNotWhite(pixel)) { right = x; break@rightScan }
            }
        }

        topScan@ for (y in 0 until height) {
            toTransform.getPixels(row, 0, width, 0, y, width, 1)
            for (pixel in row) {
                if (isPixelNotWhite(pixel)) { top = y; break@topScan }
            }
        }

        bottomScan@ for (y in height - 1 downTo top) {
            toTransform.getPixels(row, 0, width, 0, y, width, 1)
            for (pixel in row) {
                if (isPixelNotWhite(pixel)) { bottom = y; break@bottomScan }
            }
        }

        return Bitmap.createBitmap(
            toTransform,
            left,
            top,
            right - left + 1,
            bottom - top + 1
        )
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(
            "RemoveBordersTransformation(${white}_$threshHold)".toByteArray()
        )
    }

    private fun isPixelNotWhite(pixel: Int): Boolean {
        val brightness = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
        return if (white) brightness < (255 - threshHold) else brightness > threshHold
    }
}
