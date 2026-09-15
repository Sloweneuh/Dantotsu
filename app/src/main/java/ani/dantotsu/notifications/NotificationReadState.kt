package ani.dantotsu.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import ani.dantotsu.connections.anilist.api.Notification
import ani.dantotsu.notifications.comment.CommentStore
import ani.dantotsu.notifications.subscription.SubscriptionStore
import ani.dantotsu.notifications.unread.UnreadChapterStore
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger

/**
 * Which notifications the user has not dealt with yet — the one place the badge on the settings
 * sheet / home screen and the "unread" styling in the notification centre both read from.
 *
 * Every notification, whatever its source, is identified by a string key (see the `keyOf`
 * overloads), and the set of *unread* keys is what's persisted. A key goes in when the source
 * task stores / posts the notification, and comes out when the user taps or swipes away its
 * system notification, taps it in the notification centre, or deletes its stored entry. The
 * badge is simply the size of the set.
 *
 * AniList has no per-notification read state of its own — only a count of unread ones and a
 * reset-all switch — so its notifications are tracked here too: every page-1 fetch hands the
 * newest `unreadNotificationCount` ids to [trackAnilist], which files the ones it hasn't seen
 * before as unread and remembers how far it got, so a notification the user already dealt with
 * is never re-filed just because the server count still includes it.
 *
 * This replaced a bare `UnreadCommentNotifications` counter that every task bumped and only
 * "open the notification screen" cleared; [migrateLegacyCounter] turns whatever it held into
 * keys once.
 */
object NotificationReadState {
    /** Intent extra a system notification carries so its tap can be matched back to a key. */
    const val EXTRA_KEY = "notificationReadKey"

    /**
     * Key prefixes, one per tab of the notification centre, so a tab's unread count is a prefix
     * count. AniList keys carry whether the notification is about a media or a user, since the
     * two live on different tabs and nothing else about an id says which.
     */
    private const val ANILIST_PREFIX = "al:"
    const val PREFIX_ANILIST_USER = "al:u:"
    const val PREFIX_ANILIST_MEDIA = "al:m:"
    const val PREFIX_COMMENT = "comment:"
    const val PREFIX_SUBSCRIPTION = "sub:"
    const val PREFIX_CHAPTER = "chap:"

    /**
     * AniList notifications past the first few pages are unreachable in the centre, so unread
     * ids are capped to keep a long-ignored inbox from inflating the badge forever.
     */
    private const val MAX_ANILIST_KEYS = 200

    private val lock = Any()

    // ---- keys --------------------------------------------------------------------------------

    private fun anilistKey(notification: Notification): String {
        val prefix = if (notification.media != null) PREFIX_ANILIST_MEDIA else PREFIX_ANILIST_USER
        return "$prefix${notification.id}"
    }

    /** Local stores stamp their key on the [Notification] they build; AniList's is its id. */
    fun keyOf(notification: Notification): String =
        notification.readKey ?: anilistKey(notification)

    fun keyOf(store: CommentStore) = "$PREFIX_COMMENT${store.commentId ?: 0}:${store.time}"

    fun keyOf(store: SubscriptionStore) = "$PREFIX_SUBSCRIPTION${store.mediaId}:${store.time}"

    fun keyOf(store: UnreadChapterStore) = chapterKey(store.mediaId, store.lastChapter)

    /** Same id space as [UnreadChapterStore.mediaId]: AniList id, or `muMediaKey` for MangaUpdates. */
    fun chapterKey(mediaId: Int, chapter: Int) = "$PREFIX_CHAPTER$mediaId:$chapter"

    private fun anilistId(key: String): Int? =
        if (key.startsWith(ANILIST_PREFIX)) key.substringAfterLast(':').toIntOrNull() else null

    // ---- reading -----------------------------------------------------------------------------

    fun unreadKeys(): Set<String> = PrefManager.getVal<Set<String>>(PrefName.UnreadNotificationKeys)

    fun unreadCount(): Int = unreadKeys().size

    fun isUnread(key: String): Boolean = key in unreadKeys()

    /** Unread count of one tab — see the `PREFIX_*` constants. */
    fun unreadCount(prefix: String, keys: Set<String> = unreadKeys()): Int =
        keys.count { it.startsWith(prefix) }

    /** Where the unread-only AniList list can stop paging; null when nothing is unread. */
    fun oldestUnreadAnilistId(): Int? = unreadKeys().mapNotNull(::anilistId).minOrNull()

    // ---- writing -----------------------------------------------------------------------------

    fun markUnread(key: String) = markUnread(listOf(key))

    fun markUnread(keys: Collection<String>) {
        if (keys.isEmpty()) return
        synchronized(lock) {
            val set = unreadKeys().toMutableSet()
            if (!set.addAll(keys)) return
            capAnilist(set)
            save(set)
        }
    }

    fun markRead(key: String) = markRead(listOf(key))

    /** The "mark all as read" of one tab: drops every unread key with [prefix]. */
    fun markAllRead(prefix: String) {
        synchronized(lock) {
            val set = unreadKeys().toMutableSet()
            if (set.removeAll { it.startsWith(prefix) }) save(set)
        }
    }

    fun markRead(keys: Collection<String>) {
        if (keys.isEmpty()) return
        synchronized(lock) {
            val set = unreadKeys().toMutableSet()
            var changed = false
            keys.forEach { key ->
                changed = set.remove(key) or changed
                // A new-chapter notification is replaced in place when a newer chapter lands, so
                // its older siblings never get their own tap or swipe: opening chapter 12 is
                // taken as having seen 11 and 10 as well.
                if (key.startsWith(PREFIX_CHAPTER)) {
                    val (mediaId, chapter) = parseChapterKey(key) ?: return@forEach
                    set.removeAll { other ->
                        other != key && parseChapterKey(other)?.let { (m, c) ->
                            m == mediaId && c <= chapter
                        } == true
                    }.also { if (it) changed = true }
                }
            }
            if (changed) save(set)
        }
    }

    /**
     * Drops every unread key of [prefix] that no longer has a stored entry. Called by whoever
     * trims a store, so an evicted or deleted entry doesn't keep counting.
     */
    private fun reconcile(prefix: String, existing: Collection<String>) {
        synchronized(lock) {
            val set = unreadKeys().toMutableSet()
            val keep = existing.toHashSet()
            if (set.removeAll { it.startsWith(prefix) && it !in keep }) save(set)
        }
    }

    fun reconcileComments(store: List<CommentStore>) =
        reconcile(PREFIX_COMMENT, store.map(::keyOf))

    fun reconcileSubscriptions(store: List<SubscriptionStore>) =
        reconcile(PREFIX_SUBSCRIPTION, store.map(::keyOf))

    fun reconcileChapters(store: List<UnreadChapterStore>) =
        reconcile(PREFIX_CHAPTER, store.map(::keyOf))

    /**
     * Files the unread AniList notifications from a first page of results.
     *
     * [serverUnread] is AniList's own count, which always refers to the newest notifications;
     * of those, only ids above the high-water mark are new to us — anything at or below it was
     * filed on an earlier pass and may since have been read, so it must not come back. The mark
     * then moves to the top of the page, whether or not the count covered it: a notification
     * the server already considers read (the user cleared it on the website, say) is read here
     * too.
     */
    fun trackAnilist(page: List<Notification>, serverUnread: Int) {
        if (page.isEmpty()) return
        synchronized(lock) {
            val trackedUpTo = PrefManager.getVal<Int>(PrefName.AnilistUnreadTrackedUpToId)
            val newest = page.sortedByDescending { it.id }
            val fresh = newest.take(serverUnread.coerceAtLeast(0))
                .filter { it.id > trackedUpTo }
                .map(::anilistKey)
            if (fresh.isNotEmpty()) {
                val set = unreadKeys().toMutableSet()
                set.addAll(fresh)
                capAnilist(set)
                save(set)
                Logger.log("NotificationReadState: filed ${fresh.size} unread AniList notifications")
            }
            val top = newest.first().id
            if (top > trackedUpTo) PrefManager.setVal(PrefName.AnilistUnreadTrackedUpToId, top)
        }
    }

    // ---- system notifications ----------------------------------------------------------------

    /**
     * Marks read whatever system notification launched [intent]. Wired once, from the
     * application's activity-created callback, so it covers every tap target (media page,
     * comments, the notification centre…) without each of them knowing about it.
     */
    fun consumeLaunchIntent(intent: Intent?) {
        val key = runCatching { intent?.getStringExtra(EXTRA_KEY) }.getOrNull() ?: return
        markRead(key)
    }

    /** `setDeleteIntent` target: swiping the notification away counts as having seen it. */
    fun dismissIntent(context: Context, key: String): PendingIntent {
        val intent = Intent(context, NotificationDismissReceiver::class.java).apply {
            action = NotificationDismissReceiver.ACTION
            // Extras don't distinguish PendingIntents; the data URI does.
            data = Uri.fromParts("dantotsu-notification", key, null)
            putExtra(EXTRA_KEY, key)
        }
        return PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---- migration ---------------------------------------------------------------------------

    /**
     * One-time carry-over from the old counter: the N most recent stored entries across the
     * local stores become the unread ones, so upgrading neither zeroes the badge nor flags a
     * whole history as new.
     */
    fun migrateLegacyCounter() {
        val legacy = PrefManager.getVal<Int>(PrefName.UnreadCommentNotifications)
        if (legacy <= 0) return
        try {
            val comments = PrefManager.getNullableVal<List<CommentStore>>(
                PrefName.CommentNotificationStore, null
            ) ?: emptyList()
            val subscriptions = PrefManager.getNullableVal<List<SubscriptionStore>>(
                PrefName.SubscriptionNotificationStore, null
            ) ?: emptyList()
            val chapters = PrefManager.getNullableVal<List<UnreadChapterStore>>(
                PrefName.UnreadChapterNotificationStore, null
            ) ?: emptyList()
            val newest = (comments.map { it.time to keyOf(it) } +
                    subscriptions.map { it.time to keyOf(it) } +
                    chapters.map { it.time to keyOf(it) })
                .sortedByDescending { it.first }
                .take(legacy)
                .map { it.second }
            markUnread(newest)
            Logger.log("NotificationReadState: migrated legacy counter $legacy -> ${newest.size} keys")
        } catch (e: Exception) {
            Logger.log("NotificationReadState: migration failed: ${e.message}")
        } finally {
            PrefManager.setVal(PrefName.UnreadCommentNotifications, 0)
        }
    }

    // ---- internals ---------------------------------------------------------------------------

    private fun save(set: Set<String>) {
        PrefManager.setVal(PrefName.UnreadNotificationKeys, set.toSet())
    }

    private fun capAnilist(set: MutableSet<String>) {
        val anilist = set.filter { it.startsWith(ANILIST_PREFIX) }
        if (anilist.size <= MAX_ANILIST_KEYS) return
        anilist.sortedBy { anilistId(it) ?: 0 }
            .take(anilist.size - MAX_ANILIST_KEYS)
            .forEach { set.remove(it) }
    }

    private fun parseChapterKey(key: String): Pair<Int, Int>? {
        if (!key.startsWith(PREFIX_CHAPTER)) return null
        val parts = key.removePrefix(PREFIX_CHAPTER).split(':')
        if (parts.size != 2) return null
        val mediaId = parts[0].toIntOrNull() ?: return null
        val chapter = parts[1].toIntOrNull() ?: return null
        return mediaId to chapter
    }
}
