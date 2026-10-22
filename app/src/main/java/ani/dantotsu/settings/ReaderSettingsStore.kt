package ani.dantotsu.settings

import android.content.Context
import ani.dantotsu.R
import ani.dantotsu.currContext
import ani.dantotsu.snackString
import ani.dantotsu.tryWith
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * The readers' per-series settings files: a serialized settings object in the app's private files,
 * one per series (`<id>_current_settings`). Shared by the manga and novel readers, which each had
 * their own copy of these two functions.
 */
object ReaderSettingsStore {

    @Suppress("UNCHECKED_CAST")
    fun <T> load(fileName: String, context: Context? = null, toast: Boolean = true): T? {
        val a = context ?: currContext()
        try {
            if (a != null && fileName in a.fileList()) {
                a.openFileInput(fileName).use { fileIS ->
                    ObjectInputStream(fileIS).use { return it.readObject() as T }
                }
            }
        } catch (e: Exception) {
            // A settings class that has grown a field since the file was written is not corruption,
            // just a file from an older build: it is replaced from the defaults without a word.
            if (toast && e !is InvalidClassException) {
                snackString(a?.getString(R.string.error_loading_data, fileName))
            }
            try {
                a?.deleteFile(fileName)
            } catch (e: Exception) {
                Injekt.get<CrashlyticsInterface>().log("Failed to delete file $fileName")
                Injekt.get<CrashlyticsInterface>().logException(e)
            }
            e.printStackTrace()
        }
        return null
    }

    fun save(fileName: String, data: Any?, context: Context? = null) {
        tryWith {
            val a = context ?: currContext() ?: return@tryWith
            a.openFileOutput(fileName, Context.MODE_PRIVATE).use { fos ->
                ObjectOutputStream(fos).use { it.writeObject(data) }
            }
        }
    }
}
