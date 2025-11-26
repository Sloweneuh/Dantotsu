package ani.dantotsu.notifications.unread

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UnreadChapterNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Logger.log("UnreadChapterNotificationReceiver: onReceive")
        CoroutineScope(Dispatchers.IO).launch {
            UnreadChapterNotificationTask().execute(context)
        }
    }
}

