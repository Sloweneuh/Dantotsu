package ani.dantotsu.connections.handoff

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ani.dantotsu.App
import ani.dantotsu.R
import ani.dantotsu.connections.handoff.transport.NearbyTransport
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.Serializable

/**
 * Keeps this device discoverable and able to receive handoffs while the app is in the foreground
 * (started/stopped from [ani.dantotsu.App]'s activity lifecycle callbacks). AirDrop-style: the
 * user doesn't need the receive screen open.
 *
 * Because receiving only runs while a screen is up, an incoming handoff is surfaced as an in-app
 * dialog on the current activity — this is reliable (no runtime POST_NOTIFICATIONS dependency,
 * which silently drops notifications on Android 13+/WSA). A notification is only the fallback for
 * the rare moment no activity is resumed.
 */
object GlobalHandoffReceiver {

    private var manager: HandoffManager? = null
    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var notificationId = 7100

    private val main = Handler(Looper.getMainLooper())
    private var pendingStop: Runnable? = null
    // How long to stay up after the last activity stops. Activity transitions (navigation,
    // rotation, screen off/on) fire stop→start back-to-back; tearing the transports down and
    // recreating them churns the Nearby/NSD sessions and can leave the device undiscoverable
    // until the process is killed. Riding out a brief gap avoids that.
    private const val STOP_DEBOUNCE_MS = 2_000L

    fun start(context: Context) {
        // A start cancels a pending debounced stop from a transient transition.
        pendingStop?.let { main.removeCallbacks(it); pendingStop = null }
        // Respect the user's "local discovery" setting and skip unsupported (WSA/emulator) devices;
        // QR/sharing-code receiving is independent of this.
        if (!HandoffManager.localDiscoveryActive(context)) return
        if (manager != null) return
        appContext = context.applicationContext
        manager = HandoffManager(appContext!!).also {
            it.startReceiving(object : HandoffManager.Listener {
                override fun onReceived(payload: HandoffPayload) = onHandoff(payload)
                override fun onError(message: String) {}
            })
        }
    }

    fun stop() {
        pendingStop?.let { main.removeCallbacks(it) }
        pendingStop = Runnable {
            pendingStop = null
            manager?.stop()
            manager = null
        }.also { main.postDelayed(it, STOP_DEBOUNCE_MS) }
    }

    /**
     * Immediately tears down and restarts receiving (no debounce). Used when the relevant state
     * changes while the app is foreground — the discovery setting is toggled, or Bluetooth/Wi-Fi
     * is switched on from the handoff sheet — so advertising picks up the newly available radio.
     */
    fun restart(context: Context) {
        pendingStop?.let { main.removeCallbacks(it); pendingStop = null }
        manager?.stop()
        manager = null
        start(context)
    }

    private fun onHandoff(payload: HandoffPayload) {
        val activity = App.currentActivity()
        if (activity != null && !activity.isFinishing) {
            // Self-dismissing banner on the current screen (doesn't block the rest of the UI).
            HandoffIncomingBanner.show(activity, payload) {
                scope.launch { HandoffNavigator.navigate(activity.applicationContext, payload) }
            }
        } else appContext?.let { notify(it, payload) }
    }

    private fun notify(context: Context, payload: HandoffPayload) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val openIntent = Intent(context, HandoffDeepLinkActivity::class.java).apply {
            // Pass the full payload in-process (keeps sourceMedia, unlike a deep link) so tapping
            // opens the exact source entry — same fidelity as the in-app banner.
            putExtra(HandoffDeepLinkActivity.EXTRA_PAYLOAD, payload as Serializable)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context, payload.mediaId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_APP_GLOBAL)
            .setSmallIcon(R.drawable.ic_round_cast_24)
            .setContentTitle(context.getString(R.string.handoff_incoming_title))
            .setContentText(context.getString(R.string.handoff_incoming_text, payload.title ?: ""))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(notificationId++, notification) }
    }

    /** True once Nearby permissions are granted; LAN receiving works regardless. */
    fun hasNearbyPermissions(context: Context): Boolean =
        NearbyTransport.requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}
