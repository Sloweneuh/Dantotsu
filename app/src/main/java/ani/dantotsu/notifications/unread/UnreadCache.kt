package ani.dantotsu.notifications.unread

import android.content.Context
import android.content.Intent
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger

object UnreadCache {
    const val ACTION_CACHE_UPDATED = "ani.dantotsu.UNREAD_CACHE_UPDATED"

    fun save(context: Context, unreadInfo: Map<Int, UnreadChapterInfo>, mediaList: List<Media>) {
        try {
            PrefManager.init(context)
            PrefManager.setCustomVal("cached_unread_info", unreadInfo)

            val cachedUnreadList = ArrayList<Media>()
            unreadInfo.keys.forEach { id ->
                mediaList.find { it.id == id }?.let { cachedUnreadList.add(it) }
            }
            PrefManager.setCustomVal("cached_unread_chapters", cachedUnreadList)
            Logger.log("UnreadCache: saved cached_unread_info (size=${unreadInfo.size}) and cached_unread_chapters (size=${cachedUnreadList.size})")
        } catch (e: Exception) {
            Logger.log("UnreadCache: Failed to save cache: ${e.message}")
        }
    }

    fun broadcastUpdate(context: Context) {
        try {
            val intent = Intent(ACTION_CACHE_UPDATED)
            context.sendBroadcast(intent)
            Logger.log("UnreadCache: broadcasted ACTION_CACHE_UPDATED")
        } catch (e: Exception) {
            Logger.log("UnreadCache: Failed to broadcast cache update: ${e.message}")
        }
    }
}
