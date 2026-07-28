package ani.dantotsu.media.screenshot

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.core.content.FileProvider
import ani.dantotsu.BuildConfig.APPLICATION_ID
import ani.dantotsu.R
import ani.dantotsu.snackString
import ani.dantotsu.toast
import java.io.File

/**
 * Save/share for exported clips.
 *
 * The screenshot composer hands finished bitmaps to `saveImageToDownloads`/`shareImage`, but a clip
 * is already a file on disk by the time it's done — re-encoding it through a bitmap isn't possible.
 * These are the file-shaped equivalents, sharing the app's existing FileProvider authority.
 */
object ClipOutput {

    const val MIME_VIDEO = "video/mp4"
    const val MIME_GIF = "image/gif"

    /**
     * Moves [file] into the public Downloads folder as [title], de-duplicating the name. Returns
     * the saved file, or null if it couldn't be written.
     */
    fun saveToDownloads(title: String, extension: String, file: File, context: Context): File? {
        val downloads =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var target = File(downloads, "$title.$extension")
        var counter = 1
        while (target.exists()) {
            target = File(downloads, "${title}_$counter.$extension")
            counter++
        }
        return try {
            downloads.mkdirs()
            file.copyTo(target, overwrite = false)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
            toast(context.getString(R.string.saved_to_path, target.absolutePath))
            target
        } catch (e: Exception) {
            snackString("${context.getString(R.string.clip_save_failed)}: ${e.localizedMessage}")
            null
        }
    }

    /** Opens the system share sheet for [file]. */
    fun share(title: String, file: File, mimeType: String, context: Context) {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "$APPLICATION_ID.provider", file)
        }.getOrNull() ?: run {
            snackString(context.getString(R.string.clip_save_failed)); return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share $title"))
    }

    /** Scratch directory for renders, kept out of the way of the video cache. */
    fun workDir(context: Context): File =
        File(context.cacheDir, "clips").apply { mkdirs() }

    /** Clears anything left behind by a previous session's exports. */
    fun clearWorkDir(context: Context) {
        runCatching { workDir(context).listFiles()?.forEach { it.delete() } }
    }
}
