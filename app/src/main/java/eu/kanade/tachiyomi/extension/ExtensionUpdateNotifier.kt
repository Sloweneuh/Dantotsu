package eu.kanade.tachiyomi.extension

import android.content.Context
import androidx.core.app.NotificationCompat
import ani.dantotsu.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notify

class ExtensionUpdateNotifier(private val context: Context) {

    fun promptUpdates(names: List<String>) {
        context.notify(
            Notifications.ID_UPDATES_TO_EXTS,
            Notifications.CHANNEL_EXTENSIONS_UPDATE,
        ) {
            setContentTitle(
                context.getString(R.string.extension_updates_available)
            )
            val extNames = names.joinToString(", ")
            setContentText(extNames)
            setStyle(NotificationCompat.BigTextStyle().bigText(extNames))
            setSmallIcon(R.drawable.ic_round_favorite_24)
            setContentIntent(NotificationReceiver.openExtensionsPendingActivity(context))
            setAutoCancel(true)
        }
    }

    /**
     * Reports what a scheduled update run managed to do.
     *
     * [needsConfirmation] is not a failure: the system refuses a silent replace of any package this
     * build is not the installer of record for, so those extensions are waiting on a tap and
     * nothing more. They are named separately because the fix differs — running the update from the
     * extensions screen once is enough, and from then on they update quietly like the rest.
     */
    fun notifyAutoUpdateResult(updated: List<String>, needsConfirmation: List<String>) {
        if (updated.isEmpty() && needsConfirmation.isEmpty()) return

        val lines = buildList {
            if (updated.isNotEmpty()) {
                add(context.getString(R.string.extensions_auto_updated, updated.size))
                add(updated.joinToString(", "))
            }
            if (needsConfirmation.isNotEmpty()) {
                add(context.getString(R.string.extensions_need_confirmation, needsConfirmation.size))
                add(needsConfirmation.joinToString(", "))
            }
        }

        context.notify(
            Notifications.ID_EXTENSION_AUTO_UPDATE_RESULT,
            Notifications.CHANNEL_EXTENSIONS_UPDATE,
        ) {
            setContentTitle(
                if (updated.isNotEmpty()) {
                    context.getString(R.string.extensions_auto_updated, updated.size)
                } else {
                    context.getString(R.string.extensions_need_confirmation, needsConfirmation.size)
                }
            )
            val body = lines.joinToString("\n")
            setContentText(body)
            setStyle(NotificationCompat.BigTextStyle().bigText(body))
            setSmallIcon(R.drawable.ic_round_sync_24)
            setContentIntent(NotificationReceiver.openExtensionsPendingActivity(context))
            setAutoCancel(true)
        }
    }
}
