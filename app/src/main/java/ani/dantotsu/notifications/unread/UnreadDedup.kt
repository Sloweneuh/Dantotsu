package ani.dantotsu.notifications.unread

import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.connections.mangaupdates.cachedMuMalId

/**
 * One entry per series wherever new chapters are listed — the home row, its full-screen list, the
 * widget and the notification list — keeping whichever source found the highest chapter.
 *
 * The same series reaches those lists from up to three checks: AniList (through MALSync),
 * MangaUpdates and Comick, each with its own ids and its own idea of the newest chapter. Comick
 * entries carry the AniList or MangaUpdates media they link; a MangaUpdates series is matched to
 * AniList through ids already cached by earlier lookups (MangaBaka's cross-ids, or the series' MAL
 * id against the AniList media's), never by asking anything — these lists redraw often.
 */
object UnreadDedup {

    /** Series keys for one list. [malToAnilist] is the AniList ids of the media at hand by MAL id. */
    class Keys(private val malToAnilist: Map<Int, Int> = emptyMap()) {
        fun anilist(id: Int) = "al:$id"

        /** The AniList key when the series is known to be an AniList entry, else its own. */
        fun mu(muSeriesId: Long): String {
            val anilistId = MangaBakaApi.cachedAnilistIdForMu(muSeriesId)
                ?: cachedMuMalId(muSeriesId)?.let { malToAnilist[it] }
            return anilistId?.let(::anilist) ?: "mu:$muSeriesId"
        }

        fun comick(hid: String) = "ck:$hid"
    }

    /**
     * [items] with one per [key], the one with the highest [latest]; on a tie the earlier one, so
     * callers list their preferred source first. Kept in the order each series first appears.
     */
    fun <T> keepHighest(items: List<T>, key: (T) -> String, latest: (T) -> Int): List<T> {
        val best = LinkedHashMap<String, T>()
        items.forEach { item ->
            val k = key(item)
            val current = best[k]
            if (current == null || latest(item) > latest(current)) best[k] = item
        }
        return best.values.toList()
    }
}
