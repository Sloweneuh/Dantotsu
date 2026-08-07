package ani.dantotsu.home

import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * The order of the unread-chapters list, over AniList and MangaUpdates entries together.
 *
 * Shared by the home row and its full-screen view, which is the point: they show the same list and
 * so must agree on what it looks like. Each key reads the equivalent field from whichever source
 * an entry came from — MALSync's for AniList media, MangaUpdates' own for the rest — so the two
 * interleave rather than one being concatenated onto the end of the other.
 */
object UnreadOrder {

    /** Sorts per [PrefName.UnreadChaptersSort]. [items] are [Media] and/or [MUMedia]. */
    fun sort(items: List<Any>, info: Map<Int, UnreadChapterInfo>): List<Any> =
        if (PrefManager.getVal<String>(PrefName.UnreadChaptersSort) == "recent")
            items.sortedByDescending { latestChapterAtOf(it, info) }
        else items.sortedBy { unreadCountOf(it, info) }

    /** How far behind an entry is. Unknown counts sort last, as they always have. */
    private fun unreadCountOf(item: Any, info: Map<Int, UnreadChapterInfo>): Int = when (item) {
        is Media -> info[item.id]?.let { it.lastChapter - it.userProgress } ?: Int.MAX_VALUE
        is MUMedia -> (item.latestChapter ?: 0) - (item.userChapter ?: 0)
        else -> Int.MAX_VALUE
    }

    /** When an entry's newest chapter landed, epoch ms. Unknown dates sort last. */
    private fun latestChapterAtOf(item: Any, info: Map<Int, UnreadChapterInfo>): Long = when (item) {
        is Media -> info[item.id]?.latestChapterAt ?: Long.MIN_VALUE
        is MUMedia -> item.latestChapterAt ?: Long.MIN_VALUE
        else -> Long.MIN_VALUE
    }
}
