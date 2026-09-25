package ani.dantotsu.offline

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import ani.dantotsu.App
import ani.dantotsu.R
import ani.dantotsu.currActivity
import ani.dantotsu.isOnline
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.startMainActivity
import ani.dantotsu.util.AppNotices
import ani.dantotsu.util.Logger
import ani.dantotsu.util.TopBanner

/**
 * Offers the way back online once the connection that dropped out from under offline mode returns.
 *
 * A dropped connection switches offline mode on for real (see MainActivity), and it stays on: going
 * back online tears down whatever screen the user is on, which is theirs to decide, not the
 * network's. What they should not have to do is notice the connection came back and go hunting for
 * the switch — so this says so, and the action is the switch.
 *
 * Offline by choice never raises it; that is what [PrefName.OfflineModeAuto] tells apart. Dismissing
 * turns the automatic offline mode into a chosen one, so the notice does not come back every time
 * the user changes screens.
 */
object BackOnlineNotice {

    const val ID = "back_online"

    fun isPending(): Boolean {
        val ctx = App.context ?: return false
        return PrefManager.getVal<Boolean>(PrefName.OfflineMode) &&
                PrefManager.getVal<Boolean>(PrefName.OfflineModeAuto) &&
                isOnline(ctx)
    }

    fun spec(activity: Activity) = TopBanner.Spec(
        id = ID,
        iconRes = R.drawable.ic_round_wifi_24,
        title = activity.getString(R.string.back_online),
        subtitle = activity.getString(R.string.back_online_hint),
        actionLabel = activity.getString(R.string.go_online),
        onAction = { current ->
            PrefManager.setVal(PrefName.OfflineMode, false)
            PrefManager.setVal(PrefName.OfflineModeAuto, false)
            // The offline pages have no online counterpart to swap to in place, so start over
            // from the top, the same as coming back from a restore.
            startMainActivity(current)
        },
        onDismiss = { PrefManager.setVal(PrefName.OfflineModeAuto, false) },
    )

    /**
     * Notices are otherwise only raised when a screen resumes, and the connection returning is not
     * one of those moments: the user can sit on the offline home the whole time. Registered once,
     * for the life of the process.
     */
    fun watch(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val main = Handler(Looper.getMainLooper())
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                // Both on a binder thread, and both only matter while offline mode is automatic.
                override fun onAvailable(network: Network) {
                    main.post {
                        if (!PrefManager.getVal<Boolean>(PrefName.OfflineModeAuto)) return@post
                        currActivity()?.let { AppNotices.showPending(it) }
                    }
                }

                override fun onLost(network: Network) {
                    main.post { AppNotices.dismissStale() }
                }
            })
        }.onFailure { Logger.log("BackOnlineNotice: could not watch the network: ${it.message}") }
    }
}
