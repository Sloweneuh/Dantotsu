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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

data class ImageData(
    val page: Page,
    val source: HttpSource
) {
    suspend fun fetchAndProcessImage(
        page: Page,
        httpSource: HttpSource
    ): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val response = httpSource.getImage(page)
                Logger.log("Response: ${response.code} - ${response.message}")
                val inputStream = response.body.byteStream()
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                return@withContext bitmap
            } catch (e: Exception) {
                Logger.log("An error occurred: ${e.message}")
                snackString("An error occurred: ${e.message}")
                return@withContext null
            }
        }
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
    }

    @Synchronized
    fun clear() {
        imageDataCache.evictAll()
        bitmapCache.evictAll()
    }

    fun size(): Int = imageDataCache.size()

    @Synchronized
    fun getBitmap(key: String): Bitmap? = bitmapCache.get(key)

    @Synchronized
    fun putBitmap(key: String, bitmap: Bitmap) {
        bitmapCache.put(key, bitmap)
    }
}
