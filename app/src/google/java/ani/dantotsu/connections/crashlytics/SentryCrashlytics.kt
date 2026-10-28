package ani.dantotsu.connections.crashlytics

import android.content.Context
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User

/**
 * Crash reporting through Sentry. Sentry's manifest auto-init is off, so nothing is sent until
 * [setCrashlyticsCollectionEnabled] says reports are allowed; until then, and when they're
 * disabled, every Sentry call here is a no-op.
 */
class SentryCrashlytics : CrashlyticsInterface {

    private lateinit var appContext: Context

    override fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override fun logException(e: Throwable) {
        Sentry.captureException(e)
    }

    override fun log(message: String) {
        Sentry.addBreadcrumb(Breadcrumb(message))
    }

    override fun setUserId(id: String) {
        Sentry.setUser(User().apply { this.id = id })
    }

    override fun setCustomKey(key: String, value: String) {
        Sentry.setExtra(key, value)
    }

    /**
     * Must run before [ani.dantotsu.util.FinalExceptionHandler] is installed: Sentry's own
     * uncaught-exception handler then sits under it, and records the crash once, as fatal, when
     * that handler passes it on.
     */
    override fun setCrashlyticsCollectionEnabled(enabled: Boolean) {
        if (enabled) {
            // The rest of the options (DSN, screenshots, tracing) come from the manifest.
            if (!Sentry.isEnabled()) SentryAndroid.init(appContext)
        } else {
            Sentry.close()
        }
    }
}
