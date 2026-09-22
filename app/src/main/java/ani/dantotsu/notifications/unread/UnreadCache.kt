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

    /**
     * Stores a scan's result, merged into what was already cached.
     *
     * Merged, not written over the top, because a MALSync run is not all-or-nothing: a batch can
     * fail to parse, a request can throw and be swallowed, and after three failures the API's own
     * circuit breaker skips everything for a minute
     * ([ani.dantotsu.connections.malsync.MalSyncApi]). Every one of those produces a *smaller*
     * result, not an error — and this used to write that smaller result out as the whole truth, so a
     * partly-failed background run silently deleted entries the last good run had found. The home
     * screen already guards against exactly this when it composes the row, and then the scheduled
     * task clobbered the merged map it had built.
     *
     * What [answeredIds] buys is the distinction the old code could not make. An id MALSync answered
     * for and which produced no unread entry is genuinely caught up, and goes. An id it never
     * answered for is unknown, and the previous answer stands rather than vanishing. Entries are
     * still dropped once they leave [mediaList] — both callers derive that from the same reading
     * list — so nothing accumulates forever.
     *
     * @param answeredIds ids this run got a usable answer for, whether or not anything was unread.
     */
    fun save(
        context: Context,
        unreadInfo: Map<Int, UnreadChapterInfo>,
        mediaList: List<Media>,
        answeredIds: Set<Int>
    ) {
        PrefManager.init(context)
        save(unreadInfo, mediaList, answeredIds)
    }

    /** As above, for callers already running with [PrefManager] initialised. */
    fun save(
        unreadInfo: Map<Int, UnreadChapterInfo>,
        mediaList: List<Media>,
        answeredIds: Set<Int>
    ) {
        try {
            val candidateIds = mediaList.mapTo(HashSet()) { it.id }
            val merged = LinkedHashMap<Int, UnreadChapterInfo>()
            cachedInfo().forEach { (id, info) ->
                if (id !in answeredIds && id in candidateIds) merged[id] = info
            }
            merged.putAll(unreadInfo)
            PrefManager.setCustomVal("cached_unread_info", HashMap(merged))

            // The kept entries are the ones this run had nothing to say about, so their media comes
            // from the previous cache when the current list does not carry it.
            val current = mediaList.associateBy { it.id }
            val previous = cachedMedia().associateBy { it.id }
            val cachedUnreadList = ArrayList<Media>()
            merged.keys.forEach { id ->
                (current[id] ?: previous[id])?.let { cachedUnreadList.add(it) }
            }
            PrefManager.setCustomVal("cached_unread_chapters", cachedUnreadList)
            Logger.log(
                "UnreadCache: saved cached_unread_info (size=${merged.size}, ${unreadInfo.size} fresh, " +
                    "${merged.size - unreadInfo.size} kept unanswered) and cached_unread_chapters (size=${cachedUnreadList.size})"
            )
        } catch (e: Exception) {
            Logger.log("UnreadCache: Failed to save cache: ${e.message}")
        }
    }

    /**
     * Stores an already-complete map, and re-derives the media half from it.
     *
     * For the home screen, which composes its own full picture — cached entries plus this round's
     * MALSync answers, reconciled against live progress — rather than reporting a scan. It wrote
     * only `cached_unread_info`, leaving the media half to whoever wrote it last; but the widget
     * iterates the media and drops anything it has no info for, so an info entry whose media is
     * missing is simply invisible there. Writing both from the one map keeps that from happening.
     *
     * [mediaPool] supplies the media it can; anything else is carried over from what is already
     * stored, which is read before either key is written.
     */
    fun replaceInfo(info: Map<Int, UnreadChapterInfo>, mediaPool: List<Media>) {
        try {
            val current = mediaPool.associateBy { it.id }
            val previous = cachedMedia().associateBy { it.id }
            val media = ArrayList<Media>()
            info.keys.forEach { id -> (current[id] ?: previous[id])?.let(media::add) }
            PrefManager.setCustomVal("cached_unread_info", HashMap(info))
            PrefManager.setCustomVal("cached_unread_chapters", media)
            Logger.log("UnreadCache: replaced cached unread (info=${info.size}, media=${media.size})")
        } catch (e: Exception) {
            Logger.log("UnreadCache: Failed to replace cache: ${e.message}")
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
