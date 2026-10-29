package ani.dantotsu.settings.saving

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ani.dantotsu.settings.saving.internal.Location
import ani.dantotsu.util.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * A local backup on a schedule, as a safety net beside cloud sync: the same archive the manual
 * backup writes, to `Downloads/Dantotsu Backups`, keeping the newest [KEEP].
 *
 * Unattended, so nothing that would need a password goes in — account tokens and extension
 * settings (which can hold source logins) are left out, and the file is a plain `.ani`. Restoring
 * one is the manual restore, unchanged.
 */
object AutoBackup {

    /** Indices of [PrefName.AutoBackupInterval]; the settings row's choices are in this order. */
    const val OFF = 0
    const val DAILY = 1
    const val WEEKLY = 2

    private const val KEEP = 5
    private const val PREFIX = "DantotsuAutoBackup_"
    private const val WORK_NAME = "ani.dantotsu.settings.saving.AutoBackup"

    fun directory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "Dantotsu Backups"
    )

    /**
     * Brings the scheduled work in line with the setting. [changed]: the user just picked a new
     * interval, so the period is replaced; otherwise (app start) an existing schedule is kept.
     */
    fun schedule(context: Context, changed: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        val interval = PrefManager.getVal<Int>(PrefName.AutoBackupInterval)
        if (interval == OFF) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val days = if (interval == DAILY) 1L else 7L
        wm.enqueueUniquePeriodicWork(
            WORK_NAME,
            if (changed) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Worker>(days, TimeUnit.DAYS).build()
        )
    }

    /** Writes one backup now and prunes the old ones. Returns the file, or null on failure. */
    fun run(context: Context): File? = try {
        val keys = BackupTree.categories
            .flatMap { it.subCategories }
            .flatMap { it.items }
            .mapNotNull { it.pref }
            .filter { it.data.prefLocation != Location.Protected }
            .map { it.name }
            .toSet()
        val archive = BackupArchive.pack(
            PrefManager.exportSelectedPrefs(BackupTree.involvedLocations, keys),
            null,
        )
        val dir = directory().apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val file = File(dir, "$PREFIX$stamp.ani")
        file.writeText(archive)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".ani") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
        Logger.log("AutoBackup: wrote ${file.name}")
        file
    } catch (e: Exception) {
        Logger.log("AutoBackup: failed: ${e.message}")
        Logger.log(e)
        null
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            if (run(applicationContext) != null) Result.success() else Result.retry()
    }
}
