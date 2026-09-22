package ani.dantotsu.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Whether another (non-summary) notification sharing [groupKey] is currently active — i.e.
 * whether the one about to be posted with id [excludeId] will actually render bundled with
 * others, rather than as the sole item in its group (which some launchers show identically to a
 * genuinely standalone notification). Used to decide whether a child needs its own copy of text
 * that a real standalone notification's header would show (subText, next to the app name), since
 * a child expanded inside a same-app group gets just a bare timestamp divider there instead,
 * regardless of subText.
 */
fun Context.hasOtherActiveGroupMembers(groupKey: String, excludeId: Int): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
    return try {
        manager.activeNotifications.any { sbn ->
            sbn.id != excludeId &&
                sbn.notification.group == groupKey &&
                (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
        }
    } catch (e: Exception) {
        false
    }
}
