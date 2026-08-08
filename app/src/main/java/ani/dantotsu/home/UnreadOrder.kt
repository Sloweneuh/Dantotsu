package ani.dantotsu.home

import ani.dantotsu.connections.malsync.MalSyncMu
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.connections.mangaupdates.MUDetailsCache
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.muMediaKey
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.CoroutineScope

/**
 * The order of the unread-chapters list, over AniList and MangaUpdates entries together.
 *
 * Shared by the home row and its full-screen view, which is the point: they show the same list and
 * so must agree on what it looks like. Each key reads the equivalent field from whichever source
 * an entry came from — MALSync's for AniList media, MangaUpdates' own for the rest — so the two
 * interleave rather than one being concatenated onto the end of the other.
 */
object UnreadOrder {

    /** The order currently chosen, for callers that redraw when it changes underneath them. */
    fun current(): String = PrefManager.getVal(PrefName.UnreadChaptersSort)

    /** True when the chosen order needs each entry's release date rather than its unread count. */
    fun sortsByRecency(): Boolean = current() == "recent"

    /**
     * Makes sure every MangaUpdates entry's release date is known before the list is drawn.
     *
     * Those dates come from a per-series endpoint, so they arrive after the list does. Drawing
     * first put every MangaUpdates entry at the bottom — where an unknown date sorts — and then
     * visibly reshuffled them as the dates landed, which read as the order being wrong. Holding
     * the section on its spinner for one round of fetches is the smaller cost.
     *
     * Only applies to the by-most-recent order; the unread-count order needs nothing fetched.
     *
     * @return true when the list can be drawn now. When false, [onReady] runs once every date has
     *   been answered — including the ones that come back empty or fail, so this cannot hang.
     */
    fun awaitReleaseDates(
        scope: CoroutineScope,
        items: List<Any>,
        onReady: () -> Unit,
    ): Boolean {
        if (!sortsByRecency()) return true
        val pending = items.filterIsInstance<MUMedia>()
            .map { it.id }
            .distinct()
            .filterNot { MUDetailsCache.hasRelease(it) }
        if (pending.isEmpty()) return true
        val remaining = pending.toMutableSet()
        MUDetailsCache.prefetch(scope, pending, withReleases = true) { id ->
            remaining -= id
            if (remaining.isEmpty()) onReady()
        }
        return false
    }

    /** Sorts per [PrefName.UnreadChaptersSort]. [items] are [Media] and/or [MUMedia]. */
    fun sort(items: List<Any>, info: Map<Int, UnreadChapterInfo>): List<Any> =
        if (sortsByRecency()) items.sortedByDescending { latestChapterAtOf(it, info) }
        else items.sortedBy { unreadCountOf(it, info) }

    /** How far behind an entry is. Unknown counts sort last, as they always have. */
    private fun unreadCountOf(item: Any, info: Map<Int, UnreadChapterInfo>): Int = when (item) {
        is Media -> info[item.id]?.let { it.lastChapter - it.userProgress } ?: Int.MAX_VALUE
        is MUMedia -> (latestChapterOf(item, info) ?: 0) - (item.userChapter ?: 0)
        else -> Int.MAX_VALUE
    }

    /**
     * The newest chapter a MangaUpdates entry has, over both sources that can know: MangaUpdates'
     * own count and — for series linked to a MAL entry — MALSync's. See
     * [ani.dantotsu.connections.malsync.MalSyncMu.latestChapter].
     */
    private fun latestChapterOf(item: MUMedia, info: Map<Int, UnreadChapterInfo>): Int? =
        MalSyncMu.latestChapter(item.latestChapter, info[muMediaKey(item.id)]?.lastChapter)

    /**
     * When an entry's newest chapter landed, epoch ms. Unknown dates sort last.
     *
     * MangaUpdates keeps this on the series' newest release, which only the releases endpoint
     * carries, so it arrives asynchronously through [MUDetailsCache] rather than with the list —
     * see [ani.dantotsu.connections.mangaupdates.MUDetailsCache.Detail.latestReleaseAt] for why
     * the list's own `last_updated` is the wrong field despite looking like the right one.
     */
    private fun latestChapterAtOf(item: Any, info: Map<Int, UnreadChapterInfo>): Long = when (item) {
        is Media -> info[item.id]?.latestChapterAt ?: Long.MIN_VALUE
        // Whichever source has the newer chapter also has the date that goes with it: a MALSync
        // entry that is ahead of MangaUpdates is ahead *because* something released there that
        // MangaUpdates' newest release predates.
        is MUMedia -> {
            val malSync = info[muMediaKey(item.id)]
            val muDate = MUDetailsCache.get(item.id)?.latestReleaseAt
            if (malSync != null && malSync.lastChapter > (item.latestChapter ?: 0)) {
                malSync.latestChapterAt ?: muDate ?: Long.MIN_VALUE
            } else muDate ?: malSync?.latestChapterAt ?: Long.MIN_VALUE
        }
        else -> Long.MIN_VALUE
    }
}
