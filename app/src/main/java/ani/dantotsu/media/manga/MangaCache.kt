package ani.dantotsu.media.manga

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.decoder.ImageDecoder
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

data class ImageData(
    val page: Page,
    val source: HttpSource
) {
    /**
     * @param maxWidth bounds the decoded bitmap's memory footprint together with [maxHeight] —
     *                  pass both to presample during decode instead of decoding at native
     *                  resolution first. Left null (as the manga downloader does) to keep a
     *                  full-resolution decode for saved files.
     * @param maxHeight see [maxWidth].
     */
    suspend fun fetchAndProcessImage(
        page: Page,
        httpSource: HttpSource,
        maxWidth: Int? = null,
        maxHeight: Int? = null,
        cacheKey: String? = null,
    ): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val response = httpSource.getImage(page)
                Logger.log("Response: ${response.code} - ${response.message}")
                val bytes = response.body.bytes()
                // Kept encoded as well as decoded, so re-reading this page at a higher
                // resolution when it is zoomed costs a decode rather than another download.
                cacheKey?.let {
                    runCatching { Injekt.get<MangaCache>().putPageBytes(it, bytes) }
                }
                return@withContext decodeImage(bytes, maxWidth, maxHeight)
            } catch (e: CancellationException) {
                // Must propagate, not be treated as a failed page fetch — swallowing this here
                // breaks structured concurrency: the retry loop below would keep retrying (and
                // showing this error toast) instead of stopping, because the coroutine itself
                // never sees that its job was cancelled.
                throw e
            } catch (e: Exception) {
                Logger.log("An error occurred: ${e.message}")
                snackString("An error occurred: ${e.message}")
                return@withContext null
            }
        }
    }

    // Some devices (e.g. WSA) ship an incomplete HEIF codec that can't decode AVIF/HEIF stills via BitmapFactory, so fall back to Mihon's native decoder.
    //
    // When maxWidth/maxHeight are given, the bounds are read first (inJustDecodeBounds) and the
    // real decode is presampled to them via inSampleSize, instead of decoding at native resolution
    // and shrinking afterward — some source images (particularly long webtoon-strip pages some
    // extensions serve unresized) are large enough at native resolution to exhaust the heap on
    // their own decoding a single page, well before any cache or eviction logic gets a say.
    private fun decodeImage(bytes: ByteArray, maxWidth: Int? = null, maxHeight: Int? = null): Bitmap? {
        val options = BitmapFactory.Options()
        if (maxWidth != null && maxHeight != null) {
            options.inJustDecodeBounds = true
            BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                options.inSampleSize =
                    calculateInSampleSize(options.outWidth, options.outHeight, maxWidth, maxHeight)
            }
            options.inJustDecodeBounds = false
        }
        BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, options)?.let { return it }
        return try {
            val decoder = ImageDecoder.newInstance(ByteArrayInputStream(bytes)) ?: return null
            val bitmap = decoder.decode()
            decoder.recycle()
            bitmap
        } catch (e: Throwable) {
            Logger.log("Fallback image decode failed: ${e.message}")
            null
        }
    }

    /**
     * The largest power-of-2 [BitmapFactory.Options.inSampleSize] that keeps the total pixel count
     * within the budget a maxWidth × maxHeight image would use. Pixel count, not either dimension
     * alone, is what actually bounds memory (bytes ≈ width × height × 4 for ARGB_8888), and
     * checking it this way — rather than fitting both width and height individually — is what lets
     * a tall, narrow webtoon-strip page keep its width instead of being crushed by a plain height
     * cap.
     *
     * Deliberately *only* the memory bound, with no separate "fit the width" step. Sampling is not
     * a resize: BitmapFactory averages nothing on a PNG, it keeps every Nth pixel, and
     * point-sampling a halftone screentone beats the dots against the new pixel grid into moiré
     * that is then baked into the bitmap for good — no later resize can lift it back out. A page
     * merely wider than the viewport used to trip the width step into sampling by 2 for no reason
     * the memory budget asked for, which is exactly how a 1688px page reached a 644px viewport as
     * a ruined 844px one. Whatever is still oversized after this is brought down by
     * [ani.dantotsu.media.manga.mangareader.BaseImageAdapter]'s downsample, which actually filters.
     */
    private fun calculateInSampleSize(rawWidth: Int, rawHeight: Int, maxWidth: Int, maxHeight: Int): Int {
        var inSampleSize = 1
        val maxPixels = maxWidth.toLong() * maxHeight.toLong()
        while ((rawWidth.toLong() / inSampleSize) * (rawHeight.toLong() / inSampleSize) > maxPixels) {
            inSampleSize *= 2
        }
        return inSampleSize
    }
}

fun saveImage(
    bitmap: Bitmap,
    contentResolver: ContentResolver,
    filename: String,
    format: Bitmap.CompressFormat,
    quality: Int
) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/${format.name.lowercase()}")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/Dantotsu/Manga"
                )
            }

            val uri: Uri? =
                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    bitmap.compress(format, quality, os)
                } ?: throw FileNotFoundException("Failed to open output stream for URI: $uri")
            }
        } else {
            val directory =
                File("${Environment.getExternalStorageDirectory()}${File.separator}Dantotsu${File.separator}Manga")
            if (!directory.exists()) {
                directory.mkdirs()
            }

            val file = File(directory, filename)

            if (file.exists()) {
                println("File already exists: ${file.absolutePath}")
                return
            }

            FileOutputStream(file).use { outputStream ->
                bitmap.compress(format, quality, outputStream)
            }
        }
    } catch (e: FileNotFoundException) {
        println("File not found: ${e.message}")
    } catch (e: Exception) {
        println("Exception while saving image: ${e.message}")
    }
}

class MangaCache {
    // ImageData is tiny (two object refs), 1000 entries is more than enough for any chapter
    private val imageDataCache = LruCache<String, ImageData>(1000)

    // Bitmap cache sized by actual byte count (1/4 of max heap in KB)
    private val maxBitmapCacheKb = (Runtime.getRuntime().maxMemory() / 1024 / 4).toInt()
    private val bitmapCache = object : LruCache<String, Bitmap>(maxBitmapCacheKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /**
     * The pages as downloaded, still encoded.
     *
     * A decoded page is one resolution for ever, so zooming into it can only stretch what is
     * there. Keeping the bytes means the page can be decoded again at the size a zoom actually
     * asks for, and cheaply — a page is a couple of MB compressed against ~16MB expanded, so this
     * whole cache costs less than three decoded pages.
     */
    private val pageBytesCache = object : LruCache<String, ByteArray>(32 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size / 1024
    }

    @Synchronized
    fun putPageBytes(key: String, bytes: ByteArray) {
        pageBytesCache.put(key, bytes)
    }

    @Synchronized
    fun getPageBytes(key: String): ByteArray? = pageBytesCache.get(key)

    @Synchronized
    fun put(key: String, imageData: ImageData) {
        imageDataCache.put(key, imageData)
    }

    @Synchronized
    fun get(key: String): ImageData? = imageDataCache.get(key)

    @Synchronized
    fun remove(key: String) {
        imageDataCache.remove(key)
        bitmapCache.remove(key)
        pageBytesCache.remove(key)
    }

    /**
     * Drops the decoded pixels but keeps [imageDataCache].
     *
     * [bitmapCache] is budgeted at a quarter of the heap, and because this is an application-scoped
     * singleton it goes on holding that quarter for the life of the process — long after the reader
     * that filled it has been destroyed. That is what leaves the app a couple of hundred MB down
     * while the user is browsing somewhere else entirely, until some unrelated allocation on some
     * unrelated thread is the one that finally fails.
     *
     * Everything dropped here is recoverable: the [ImageData] kept alongside each bitmap is exactly
     * what `BaseImageAdapter.loadBitmap` re-decodes from, so this costs a redecode on a revisit
     * rather than breaking the reload path the reader deliberately keeps this cache warm for.
     * [clear] drops both halves and is for when neither is wanted.
     */
    @Synchronized
    fun evictBitmaps() {
        bitmapCache.evictAll()
        pageBytesCache.evictAll()
    }

    @Synchronized
    fun clear() {
        imageDataCache.evictAll()
        bitmapCache.evictAll()
        pageBytesCache.evictAll()
    }

    fun size(): Int = imageDataCache.size()

    @Synchronized
    fun getBitmap(key: String): Bitmap? = bitmapCache.get(key)

    @Synchronized
    fun putBitmap(key: String, bitmap: Bitmap) {
        bitmapCache.put(key, bitmap)
    }
}
