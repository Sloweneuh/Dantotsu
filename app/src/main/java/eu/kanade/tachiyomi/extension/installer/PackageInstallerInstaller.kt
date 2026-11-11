package eu.kanade.tachiyomi.extension.installer

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentSanitizer
import ani.dantotsu.R
import ani.dantotsu.snackString
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.util.lang.use
import eu.kanade.tachiyomi.util.system.getParcelableExtraCompat
import eu.kanade.tachiyomi.util.system.getUriSize

class PackageInstallerInstaller(private val service: Service) : Installer(service) {

    private val packageInstaller = service.packageManager.packageInstaller

    private val packageActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // A session let go of (see returnCheck) can still report, after the next one started.
            val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            if (sessionId != -1 && activeSession != null && sessionId != activeSession?.second) return
            userAction = null
            when (intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
            )) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Routine rather than exceptional: the system refuses a silent replace whenever
                    // this app is not the installer of record for the package, which is the normal
                    // state for anything installed by a different build of Dantotsu. When nobody
                    // asked for this install, that is where it stops — the extension keeps its old
                    // version and its pending update, and the caller decides what to surface.
                    if (getActiveEntry()?.unattended == true) {
                        activeSession?.let { (_, sessionId) ->
                            runCatching { packageInstaller.abandonSession(sessionId) }
                        }
                        continueQueue(InstallStep.RequiresUserAction)
                        return
                    }
                    val userAction =
                        intent.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT)?.run {
                            IntentSanitizer.Builder()
                                .allowAction(this.action!!)
                                .allowExtra(PackageInstaller.EXTRA_SESSION_ID) { id -> id == activeSession?.second }
                                .allowAnyComponent()
                                .allowPackage {
                                    // There is no way to check the actual installer name so allow all.
                                    true
                                }
                                .build()
                                .sanitizeByFiltering(this)
                        }
                    if (userAction == null) {
                        Logger.log("Fatal error for $intent")
                        continueQueue(InstallStep.Error)
                        return
                    }
                    userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activeSession?.let { (_, sessionId) ->
                        this@PackageInstallerInstaller.userAction = PendingUserAction(sessionId, userAction)
                    }
                    service.startActivity(userAction)
                }

                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    continueQueue(InstallStep.Idle)
                }

                PackageInstaller.STATUS_SUCCESS -> continueQueue(InstallStep.Installed)
                PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                    Logger.log("Failed to install extension due to conflict")
                    toast(context.getString(R.string.failed_ext_install_conflict))
                    continueQueue(InstallStep.Error)
                }
                else -> {
                    Logger.log("Fatal error for $intent")
                    Logger.log("Status: ${intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)}")
                    continueQueue(InstallStep.Error)
                }
            }
        }
    }

    private var activeSession: Pair<Entry, Int>? = null

    /** The confirmation the system asked for and this put on screen, while its answer is outstanding. */
    private class PendingUserAction(
        val sessionId: Int,
        val intent: Intent,
        var reprompted: Boolean = false,
        /** The system started installing it: the confirmation was answered, its report is on its way. */
        var installing: Boolean = false,
    )

    private var userAction: PendingUserAction? = null

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Back in the app with the confirmation unanswered. Granting "install unknown apps" from the
     * confirmation sends the user to Settings, and some installers (Android TV's, for one) close
     * there without ever reporting on the session: nothing then moves the queue, and every later
     * install waits behind it until the process dies. So the confirmation comes back once, now
     * that it can go through, and if the user returns from that one without an answer too, the
     * session is let go.
     */
    private val returnCheck = Runnable {
        val pending = userAction ?: return@Runnable
        if (pending.sessionId != activeSession?.second) return@Runnable
        // Answered, and the install is under way or done but its report is late (a slow install
        // takes well over the wait). Showing the confirmation again then opens a session that's
        // used up, and the system calls the file broken while the install itself goes through.
        val info = runCatching { packageInstaller.getSessionInfo(pending.sessionId) }.getOrNull()
        if (info == null || pending.installing) {
            handler.postDelayed(lateReport, 10_000)
            return@Runnable
        }
        if (!pending.reprompted) {
            pending.reprompted = true
            runCatching { service.startActivity(pending.intent) }
                .onFailure { Logger.log("Couldn't show the install confirmation again: $it") }
            return@Runnable
        }
        Logger.log("Install confirmation for session ${pending.sessionId} never answered, abandoning it")
        userAction = null
        runCatching { packageInstaller.abandonSession(pending.sessionId) }
        continueQueue(InstallStep.Idle)
    }

    /**
     * The install went ahead but its report never came: the queue moves on rather than wait
     * forever. Idle, not an error — the extension list reads what's installed from the system.
     */
    private val lateReport = Runnable {
        val pending = userAction ?: return@Runnable
        if (pending.sessionId != activeSession?.second) return@Runnable
        Logger.log("No report for install session ${pending.sessionId} after it went ahead, moving on")
        userAction = null
        continueQueue(InstallStep.Idle)
    }

    /** Tells an answered confirmation (the install starts moving) from one left open. */
    private val sessionCallback = object : PackageInstaller.SessionCallback() {
        private fun moved(sessionId: Int) {
            userAction?.takeIf { it.sessionId == sessionId }?.installing = true
        }

        override fun onCreated(sessionId: Int) {}
        override fun onBadgingChanged(sessionId: Int) {}
        override fun onActiveChanged(sessionId: Int, active: Boolean) = if (active) moved(sessionId) else Unit
        override fun onProgressChanged(sessionId: Int, progress: Float) = moved(sessionId)
        override fun onFinished(sessionId: Int, success: Boolean) = moved(sessionId)
    }

    private val returnWatcher = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (userAction == null) return
            // Long enough for the answer of a confirmation that did go through to arrive first.
            handler.removeCallbacks(returnCheck)
            handler.removeCallbacks(lateReport)
            handler.postDelayed(returnCheck, 2000)
        }

        override fun onActivityPaused(activity: Activity) = handler.removeCallbacks(returnCheck)
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    // Always ready
    override var ready = true

    override fun processEntry(entry: Entry) {
        super.processEntry(entry)
        activeSession = null
        userAction = null
        try {
            val installParams =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                installParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val fileSize = service.getUriSize(entry.uri) ?: throw IllegalStateException()
            // Before createSession, not after: the session takes its copy of the params when it is
            // created, so a setter called afterwards is silently dropped.
            installParams.setSize(fileSize)
            activeSession = entry to packageInstaller.createSession(installParams)

            val inputStream =
                service.contentResolver.openInputStream(entry.uri) ?: throw IllegalStateException()
            val session = packageInstaller.openSession(activeSession!!.second)
            val outputStream = session.openWrite(entry.downloadId.toString(), 0, fileSize)
            session.use {
                arrayOf(inputStream, outputStream).use {
                    inputStream.copyTo(outputStream)
                    session.fsync(outputStream)
                }

                val intentSender = PendingIntent.getBroadcast(
                    service,
                    activeSession!!.second,
                    Intent(INSTALL_ACTION).setPackage(service.packageName),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else 0
                ).intentSender
                session.commit(intentSender)
            }
        } catch (e: Exception) {
            Logger.log("Failed to install extension ${entry.downloadId} ${entry.uri}\n$e")
            snackString(R.string.failed_to_install_extension, null, "${entry.downloadId} ${entry.uri}")
            activeSession?.let { (_, sessionId) ->
                packageInstaller.abandonSession(sessionId)
            }
            continueQueue(InstallStep.Error)
        }
    }

    override fun cancelEntry(entry: Entry): Boolean {
        activeSession?.let { (activeEntry, sessionId) ->
            if (activeEntry == entry) {
                packageInstaller.abandonSession(sessionId)
                return false
            }
        }
        return true
    }

    override fun onDestroy() {
        service.unregisterReceiver(packageActionReceiver)
        service.application.unregisterActivityLifecycleCallbacks(returnWatcher)
        handler.removeCallbacks(returnCheck)
        handler.removeCallbacks(lateReport)
        packageInstaller.unregisterSessionCallback(sessionCallback)
        super.onDestroy()
    }

    init {
        ContextCompat.registerReceiver(
            service,
            packageActionReceiver,
            IntentFilter(INSTALL_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        service.application.registerActivityLifecycleCallbacks(returnWatcher)
        packageInstaller.registerSessionCallback(sessionCallback, handler)
    }
}

private const val INSTALL_ACTION = "PackageInstallerInstaller.INSTALL_ACTION"
