package ani.dantotsu.notifications.unread

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException

class UnreadChapterNotificationWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Logger.log("UnreadChapterNotificationWorker: doWork")
        if (System.currentTimeMillis() - lastCheck < 60000) {
            Logger.log("UnreadChapterNotificationWorker: doWork skipped (too soon)")
            return Result.success()
        }

        // Right after a device wakes, the active network reports VALIDATED before the system
        // DNS resolver is actually serving queries, so every host fails with a transient
        // UnknownHostException (EAI_NODATA). Defer to WorkManager's backoff retry instead of
        // running the whole check, failing, and spamming the log with stack traces.
        if (!isResolverReady(applicationContext)) {
            Logger.log("UnreadChapterNotificationWorker: DNS not ready yet (device likely just woke); retrying later")
            return Result.retry()
        }

        lastCheck = System.currentTimeMillis()
        return if (UnreadChapterNotificationTask().execute(applicationContext)) {
            Result.success()
        } else {
            Logger.log("UnreadChapterNotificationWorker: doWork failed")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "ani.dantotsu.notifications.unread.UnreadChapterNotificationWorker"
        private var lastCheck = 0L
    }
}

/**
 * Probes whether the system DNS resolver can actually answer queries yet.
 *
 * The active network can be flagged VALIDATED a few hundred milliseconds before the resolver
 * is ready right after a device wake, producing transient `UnknownHostException` for every
 * host. Callers (the unread notification workers) use this to defer work until DNS is warm
 * rather than failing the check and logging a wall of stack traces.
 *
 * When a DoH provider is selected, resolution goes through the OkHttp client rather than the
 * system resolver, so probing `InetAddress` would be misleading — in that case this returns
 * true and lets the request proceed (the DohErrorInterceptor handles DoH failures).
 */
suspend fun isResolverReady(
    context: Context,
    probeHost: String = "graphql.anilist.co",
    attempts: Int = 3,
    delayMs: Long = 1500
): Boolean = withContext(Dispatchers.IO) {
    PrefManager.init(context)
    if (PrefManager.getVal<Int>(PrefName.DohProvider) != 0) return@withContext true
    repeat(attempts) { attempt ->
        try {
            InetAddress.getByName(probeHost)
            return@withContext true
        } catch (_: UnknownHostException) {
            if (attempt < attempts - 1) delay(delayMs)
        } catch (_: Exception) {
            // Any other resolver hiccup: don't block the check on it.
            return@withContext true
        }
    }
    false
}
