package ani.dantotsu.notifications.unread

import android.content.Context
import android.content.Intent
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.connections.mangaupdates.MUMedia
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

    /**
     * Stores the MangaUpdates half of the unread row.
     *
     * The AniList/MAL half is computed by [UnreadChapterNotificationTask] on a schedule and cached
     * above; the MangaUpdates half is worked out live by the home screen ([MalSyncMu.unreadInfo] over
     * the MU reading list) and used to be thrown away with the fragment. The waiting widget has no
     * viewmodel to ask, so whatever the home screen resolved is persisted here for it to read.
     */
    fun saveMu(context: Context, unreadInfo: Map<Int, UnreadChapterInfo>, media: List<MUMedia>) {
        try {
            PrefManager.init(context)
            PrefManager.setCustomVal("cached_mu_unread_info", HashMap(unreadInfo))
            PrefManager.setCustomVal("cached_mu_unread_media", ArrayList(media))
            Logger.log("UnreadCache: saved MangaUpdates unread (size=${media.size})")
        } catch (e: Exception) {
            Logger.log("UnreadCache: Failed to save MangaUpdates cache: ${e.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun cachedInfo(): Map<Int, UnreadChapterInfo> = read("cached_unread_info") as? Map<Int, UnreadChapterInfo>
        ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun cachedMedia(): List<Media> = read("cached_unread_chapters") as? List<Media> ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    fun cachedMuInfo(): Map<Int, UnreadChapterInfo> =
        read("cached_mu_unread_info") as? Map<Int, UnreadChapterInfo> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun cachedMuMedia(): List<MUMedia> = read("cached_mu_unread_media") as? List<MUMedia> ?: emptyList()

    private fun read(key: String): Any? = try {
        when (key) {
            "cached_unread_info", "cached_mu_unread_info" ->
                PrefManager.getNullableCustomVal(key, null, HashMap::class.java)

            else -> PrefManager.getNullableCustomVal(key, null, ArrayList::class.java)
        }
    } catch (e: Exception) {
        Logger.log("UnreadCache: Failed to read $key: ${e.message}")
        null
    }

    /**
     * Drops a single media from the cached unread row — both the AniList/MAL half and the
     * MangaUpdates half, since the id is the same key ([ani.dantotsu.connections.mangaupdates.muMediaKey])
     * in both — and broadcasts the change so the home row and widget redraw without it.
     *
     * Called when the user marks that entry read from its notification: the scheduled scan would
     * clear it on its next run anyway, this just keeps the row honest in the meantime.
     */
    fun removeEntry(context: Context, mediaId: Int) {
        try {
            PrefManager.init(context)
            var changed = false

            val info = cachedInfo().toMutableMap()
            if (info.remove(mediaId) != null) {
                val media = cachedMedia().filterNot { it.id == mediaId }
                PrefManager.setCustomVal("cached_unread_info", HashMap(info))
                PrefManager.setCustomVal("cached_unread_chapters", ArrayList(media))
                changed = true
            }

            val muInfo = cachedMuInfo().toMutableMap()
            if (muInfo.remove(mediaId) != null) {
                val muMedia = cachedMuMedia().filterNot {
                    ani.dantotsu.connections.mangaupdates.muMediaKey(it.id) == mediaId
                }
                PrefManager.setCustomVal("cached_mu_unread_info", HashMap(muInfo))
                PrefManager.setCustomVal("cached_mu_unread_media", ArrayList(muMedia))
                changed = true
            }

            if (changed) broadcastUpdate(context)
        } catch (e: Exception) {
            Logger.log("UnreadCache: Failed to remove entry $mediaId: ${e.message}")
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
