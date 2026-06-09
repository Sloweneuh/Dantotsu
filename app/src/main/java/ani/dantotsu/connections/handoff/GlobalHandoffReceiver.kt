package ani.dantotsu.connections.handoff

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.LayoutInflater
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import ani.dantotsu.App
import ani.dantotsu.R
import ani.dantotsu.connections.handoff.transport.NearbyTransport
import ani.dantotsu.databinding.DialogHandoffIncomingBinding
import ani.dantotsu.loadImage
import ani.dantotsu.util.customAlertDialog
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    fun start(context: Context) {
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
        manager?.stop()
        manager = null
    }

    private fun onHandoff(payload: HandoffPayload) {
        val activity = App.currentActivity()
        if (activity != null && !activity.isFinishing) showDialog(activity, payload)
        else appContext?.let { notify(it, payload) }
    }

    private fun showDialog(activity: Activity, payload: HandoffPayload) {
        val view = DialogHandoffIncomingBinding.inflate(LayoutInflater.from(activity)).apply {
            handoffIncomingFrom.text =
                activity.getString(R.string.handoff_incoming_from, payload.senderName)
            handoffIncomingTitle.text = payload.title
            handoffIncomingCover.loadImage(payload.cover)
            val info = progressLine(activity, payload)
            handoffIncomingInfo.isVisible = info != null
            handoffIncomingInfo.text = info
        }
        activity.customAlertDialog().apply {
            setTitle(activity.getString(R.string.handoff_incoming_title))
            setCustomView(view.root)
            setPosButton(R.string.handoff_open) {
                scope.launch {
                    HandoffNavigator.navigate(activity.applicationContext, payload)
                }
            }
            setNegButton(R.string.cancel) {}
            show()
        }
    }

    /** "Episode 5 • 12:34" / "Chapter 12 • Page 8", or null for a media-only handoff. */
    private fun progressLine(context: Context, payload: HandoffPayload): String? {
        val number = payload.number?.takeIf { payload.hasProgress } ?: return null
        return if (payload.isAnime) {
            val ep = context.getString(R.string.handoff_episode_label, number)
            payload.positionMs?.takeIf { it > 0 }?.let { "$ep • ${formatTime(it)}" } ?: ep
        } else {
            val ch = context.getString(R.string.handoff_chapter_label, number)
            payload.page?.takeIf { it > 0 }
                ?.let { "$ch • ${context.getString(R.string.handoff_page_label, it.toString())}" } ?: ch
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun notify(context: Context, payload: HandoffPayload) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val openIntent = Intent(context, HandoffDeepLinkActivity::class.java).apply {
            data = Uri.parse(payload.toDeepLink())
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
