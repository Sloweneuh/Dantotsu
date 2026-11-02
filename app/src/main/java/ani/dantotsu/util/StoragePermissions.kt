package ani.dantotsu.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import ani.dantotsu.R
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast

class StoragePermissions {
    companion object {
        fun downloadsPermission(activity: AppCompatActivity): Boolean {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) return true
            val permissions = arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )

            val requiredPermissions = permissions.filter {
                ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()

            return if (requiredPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    activity,
                    requiredPermissions,
                    DOWNLOADS_PERMISSION_REQUEST_CODE
                )
                false
            } else {
                true
            }
        }

        fun hasDirAccess(context: Context, path: String): Boolean = hasDirAccess(context, pathToUri(path))

        fun hasDirAccess(context: Context, uri: Uri): Boolean {
            // One of the app's own folders (see useAppStorage): no grant to hold, just a folder to write.
            if (uri.scheme == "file") {
                val dir = java.io.File(uri.path ?: return false)
                return (dir.isDirectory || dir.mkdirs()) && dir.canWrite()
            }
            return context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
        }

        fun hasDirAccess(context: Context): Boolean {
            val path = PrefManager.getVal<String>(PrefName.DownloadsDir)
            return hasDirAccess(context, path)
        }

        fun AppCompatActivity.accessAlertDialog(
            launcher: LauncherWrapper,
            force: Boolean = false,
            complete: (Boolean) -> Unit
        ) {
            if (hasDirAccess(this) && !force) {
                complete(true)
                return
            }
            customAlertDialog().apply {
                setTitle(getString(R.string.dir_access))
                setMessage(getString(R.string.dir_access_msg))
                setPosButton(getString(R.string.ok)) {
                    launcher.registerForCallback(complete)
                    launcher.launch()
                }
                setNegButton(getString(R.string.cancel)) {
                    complete(false)
                }
            }.show()
        }

        /**
         * Keeps downloads in the app's own storage on this device, rather than a folder picked
         * through the system — which many TVs have no picker for. They need no permission there,
         * but go with the app when it's uninstalled.
         */
        fun useAppStorage(context: Context): Boolean {
            val dir = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "Dantotsu")
            if (!dir.isDirectory && !dir.mkdirs()) return false
            PrefManager.setVal(PrefName.DownloadsDir, Uri.fromFile(dir).toString())
            PrefManager.setVal(PrefName.DownloadsDirNested, false)
            DownloadsManager.addNoMedia(context)
            return true
        }

        /** Whether this device can show the system's folder picker at all; many TVs can't. */
        fun canPickFolder(context: Context): Boolean =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(context.packageManager) != null

        private fun pathToUri(path: String): Uri {
            return Uri.parse(path)
        }

        private const val DOWNLOADS_PERMISSION_REQUEST_CODE = 100
    }
}


class LauncherWrapper(
    activity: AppCompatActivity,
    contract: ActivityResultContracts.OpenDocumentTree
) {
    private var launcher: ActivityResultLauncher<Uri?>
    var complete: (Boolean) -> Unit = {}

    init {
        launcher = activity.registerForActivityResult(contract) { uri ->
            if (uri != null) {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                if (StoragePermissions.hasDirAccess(activity, uri)) {
                    val root = DocumentFile.fromTreeUri(activity, uri)
                    val nested = root?.let { DownloadsManager.shouldNestDownloadsFolder(it) } ?: true
                    PrefManager.setVal(PrefName.DownloadsDir, uri.toString())
                    PrefManager.setVal(PrefName.DownloadsDirNested, nested)
                    DownloadsManager.addNoMedia(activity)
                    complete(true)
                } else {
                    toast(activity.getString(R.string.dir_error))
                    complete(false)
                }
            } else {
                toast(activity.getString(R.string.dir_error))
                complete(false)
            }
        }
    }

    fun registerForCallback(callback: (Boolean) -> Unit) {
        complete = callback
    }

    fun launch() {
        launcher.launch(null)
    }
}