package ani.dantotsu.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ani.dantotsu.settings.saving.PrefManager

/**
 * Fired when the user swipes a Dantotsu notification away (its `deleteIntent`, built by
 * [NotificationReadState.dismissIntent]). Dismissing counts as having seen it, so the badge drops.
 */
class NotificationDismissReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "ani.dantotsu.notifications.DISMISSED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        PrefManager.init(context)
        NotificationReadState.consumeLaunchIntent(intent)
    }
}
