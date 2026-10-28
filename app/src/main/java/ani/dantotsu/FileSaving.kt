package ani.dantotsu

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import ani.dantotsu.BuildConfig.APPLICATION_ID
import ani.dantotsu.settings.saving.internal.PreferenceKeystore
import ani.dantotsu.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

// Writing images and preference backups out to storage, and sharing them.

fun openInFileManager(context: Context, uri: Uri) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        snackString(context.getString(R.string.no_file_manager_found))
    } catch (e: Exception) {
        Logger.log(e)
    }
}

fun saveImageToDownloads(title: String, bitmap: Bitmap, context: Activity) {
    FileProvider.getUriForFile(
        context,
        "$APPLICATION_ID.provider",
        saveImage(
            bitmap,
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
            title
        ) ?: return
    )
}

fun savePrefsToDownloads(
    title: String,
    serialized: String,
    context: Activity,
    password: CharArray? = null
) {
    FileProvider.getUriForFile(
        context,
        "$APPLICATION_ID.provider",
        if (password != null) {
            savePrefs(
                serialized,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
                title,
                context,
                password
            ) ?: return
        } else {
            savePrefs(
                serialized,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
                title,
                context
            ) ?: return
        }
    )
}

@SuppressLint("StringFormatMatches")
fun savePrefs(serialized: String, path: String, title: String, context: Context): File? {
    var file = File(path, "$title.ani")
    var counter = 1
    while (file.exists()) {
        file = File(path, "${title}_${counter}.ani")
        counter++
    }

    return try {
        file.writeText(serialized)
        scanFile(file.absolutePath, context)
        toast(String.format(context.getString(R.string.saved_to_path, file.absolutePath)))
        file
    } catch (e: Exception) {
        snackString("Failed to save settings: ${e.localizedMessage}")
        null
    }
}

@SuppressLint("StringFormatMatches")
fun savePrefs(
    serialized: String,
    path: String,
    title: String,
    context: Context,
    password: CharArray
): File? {
    var file = File(path, "$title.sani")
    var counter = 1
    while (file.exists()) {
        file = File(path, "${title}_${counter}.sani")
        counter++
    }

    return try {
        file.writeBytes(PreferenceKeystore.encryptBackup(password, serialized))
        scanFile(file.absolutePath, context)
        toast(String.format(context.getString(R.string.saved_to_path, file.absolutePath)))
        file
    } catch (e: Exception) {
        snackString("Failed to save settings: ${e.localizedMessage}")
        null
    }
}

fun shareImage(title: String, bitmap: Bitmap, context: Context) {

    val contentUri = FileProvider.getUriForFile(
        context,
        "$APPLICATION_ID.provider",
        saveImage(bitmap, context.cacheDir.absolutePath, title) ?: return
    )

    val intent = Intent(Intent.ACTION_SEND)
    intent.type = "image/png"
    intent.putExtra(Intent.EXTRA_STREAM, contentUri)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share $title"))
}

@SuppressLint("StringFormatMatches")
fun saveImage(image: Bitmap, path: String, imageFileName: String): File? {
    val imageFile = File(path, "$imageFileName.png")
    return try {
        val fOut: OutputStream = FileOutputStream(imageFile)
        image.compress(Bitmap.CompressFormat.PNG, 0, fOut)
        fOut.close()
        scanFile(imageFile.absolutePath, currContext()!!)
        toast(String.format(currContext()!!.getString(R.string.saved_to_path, path)))
        imageFile
    } catch (e: Exception) {
        snackString("Failed to save image: ${e.localizedMessage}")
        null
    }
}

private fun scanFile(path: String, context: Context) {
    MediaScannerConnection.scanFile(context, arrayOf(path), null) { p, _ ->
        Logger.log("Finished scanning $p")
    }
}
