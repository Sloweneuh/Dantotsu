package ani.dantotsu.util

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.settings.saving.PrefManager
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.DelicateCoroutinesApi
import tachiyomi.core.util.lang.launchIO
import java.util.concurrent.TimeUnit

/**
 * Why the previous process went away, read back on the next launch.
 *
 * A process that dies in the background leaves nothing behind for the user to report: the app is
 * simply cold the next time they open it, on whatever screen a fresh launch starts at, which reads
 * as "it restarted itself". [ani.dantotsu.util.FinalExceptionHandler] deliberately doesn't raise
 * CrashActivity from the background, so even a genuine crash is silent — and low-memory kills, ANRs
 * and freezer kills never had a report to begin with.
 *
 * The platform records all of them. [ActivityManager.getHistoricalProcessExitReasons] hands back the
 * reason, the process importance at the moment it died, and its memory footprint, which together
 * separate the cases that otherwise look identical from the outside:
 *
 *  - `CRASH` with importance `CACHED` — an exception escaped a background thread. The app's
 *    [tachiyomi.core.util.lang.launchIO] is a bare `GlobalScope.launch`, so anything thrown inside
 *    one reaches the default handler and takes the process with it.
 *  - `LOW_MEMORY`, or `OTHER` with a large `pss` — the process was simply too big to keep cached.
 *  - `FREEZER` — the cached-app freezer killed it, typically over binder traffic it couldn't finish.
 *  - `USER_REQUESTED` — the user swiped it out of recents. Not a bug; logged, never reported.
 *
 * Read once per launch, on a background thread, and watermarked by timestamp so each death is
 * reported exactly once however many times the app is opened afterwards.
 */
object ProcessExitReporter {

    /** Timestamp of the newest exit already reported; everything at or below it is old news. */
    private const val LAST_SEEN_KEY = "processExitLastSeen"

    /** The platform keeps up to 16 per app. More than this is history nobody is going to read. */
    private const val MAX_RECORDS = 8

    private const val TAG = "ProcessExit"

    // Reasons only Android 12/13 can produce, named here rather than referenced so the file needs
    // no API guard beyond the R one below. They are compile-time constants either way.
    private const val REASON_FREEZER = 14              // ApplicationExitInfo, API 31
    private const val REASON_PACKAGE_STATE_CHANGE = 15 // ApplicationExitInfo, API 33
    private const val REASON_PACKAGE_UPDATED = 16      // ApplicationExitInfo, API 33

    /**
     * Deaths that mean something went wrong, and so are worth an event rather than just a log line.
     *
     * The excluded ones are all ordinary lifecycle: the user swiping the app away, the system
     * stopping the user it belongs to, a permission change, an update being installed. Sending
     * those would bury the ones that matter.
     *
     * InlinedApi is the point rather than a hazard here. These constants compile down to the bare
     * ints, so this initializer holds no reference to an API 30 class and is safe to run on the
     * 26..29 devices that reach it — where the set is then never consulted, because the only thing
     * that reads it is behind the version check in [report].
     */
    @SuppressLint("InlinedApi")
    private val REPORTABLE = setOf(
        ApplicationExitInfo.REASON_UNKNOWN,
        ApplicationExitInfo.REASON_EXIT_SELF,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        ApplicationExitInfo.REASON_OTHER,
        REASON_FREEZER,
    )

    /**
     * @param reportRemotely the caller's crash-reporting decision — the same one that gates
     *   Crashlytics collection. An exit reason says what the device was doing when it killed us, so
     *   it goes nowhere when the user has opted out.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun report(context: Context, crashlytics: CrashlyticsInterface, reportRemotely: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // CrashActivity lives in :error_process, which builds its own App and would otherwise
        // report every death a second time and move the watermark out from under the real process.
        if (Application.getProcessName() != context.packageName) return
        launchIO {
            // launchIO is GlobalScope: an exception escaping here would kill the process, which
            // would be an absurd way for the thing that explains process deaths to behave.
            runCatching { collect(context, crashlytics, reportRemotely) }
                .onFailure { Logger.log(Log.WARN, "$TAG: could not be read: ${it.message}", TAG) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun collect(
        context: Context,
        crashlytics: CrashlyticsInterface,
        reportRemotely: Boolean
    ) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val records = manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
        if (records.isEmpty()) return

        val lastSeen = PrefManager.getCustomVal(LAST_SEEN_KEY, 0L)
        // Documented newest-first, but the watermark is the one thing that must not drift if that
        // ever stops being true, so take the maximum rather than the head.
        PrefManager.setCustomVal(LAST_SEEN_KEY, records.maxOf { it.timestamp })

        records.filter { it.timestamp > lastSeen }
            .sortedBy { it.timestamp } // oldest first, so the log reads forwards
            .forEach { describe(it, crashlytics, reportRemotely) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describe(
        info: ApplicationExitInfo,
        crashlytics: CrashlyticsInterface,
        reportRemotely: Boolean
    ) {
        val reason = reasonName(info.reason)
        val importance = importanceName(info.importance)
        val summary = buildString {
            append("$TAG: previous process died — ").append(reason)
            append(" (status ").append(info.status).append(')')
            append(", was ").append(importance)
            append(", pss ").append(info.pss / 1024).append("MB")
            append(", rss ").append(info.rss / 1024).append("MB")
            append(", ").append(ago(info.timestamp)).append(" ago")
            append(", process ").append(info.processName)
            info.description?.takeIf { it.isNotBlank() }?.let { append(", \"").append(it).append('"') }
        }

        Logger.log(Log.WARN, summary, TAG)
        crashlytics.log(summary)

        if (!reportRemotely || info.reason !in REPORTABLE) return
        Sentry.captureMessage(summary, levelFor(info.reason)) { scope ->
            scope.setTag("exit.reason", reason)
            scope.setTag("exit.importance", importance)
            // The distinction the whole exercise turns on: a process killed while cached is one the
            // user was away from, and is what they experience as the app restarting itself.
            scope.setTag(
                "exit.backgrounded",
                (info.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED).toString()
            )
            scope.setTag("exit.pssMb", (info.pss / 1024).toString())
        }
    }

    private fun levelFor(reason: Int): SentryLevel = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> SentryLevel.ERROR

        else -> SentryLevel.WARNING
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        REASON_FREEZER -> "FREEZER"
        REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "TOP_SLEEPING"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "CANT_SAVE_STATE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
        else -> "IMPORTANCE_$importance"
    }

    private fun ago(timestamp: Long): String {
        val elapsed = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
        return when {
            minutes < 1 -> "${TimeUnit.MILLISECONDS.toSeconds(elapsed)}s"
            minutes < 60 -> "${minutes}m"
            else -> "${TimeUnit.MILLISECONDS.toHours(elapsed)}h${minutes % 60}m"
        }
    }
}
