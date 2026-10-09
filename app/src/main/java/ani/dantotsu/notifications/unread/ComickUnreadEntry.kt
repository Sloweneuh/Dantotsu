package ani.dantotsu.notifications.unread

import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.muMediaKey
import ani.dantotsu.media.Media
import java.io.Serializable

/**
 * A Comick title with unread chapters, as [ComickUnreadNotificationTask] last found it — kept so
 * the home screen's unread row can show it without a request per title.
 *
 * Shown as the AniList media when Comick links one ([anilistMedia]), else as the MangaUpdates
 * series ([muMedia]), else as itself, opening Comick's page.
 */
data class ComickUnreadEntry(
    val hid: String,
    val slug: String?,
    val title: String,
    val coverUrl: String?,
    /** With the account's list state when the title is on its AniList list. */
    val anilistMedia: Media?,
    /** With the account's list state when the series is on its MangaUpdates lists. */
    val muMedia: MUMedia?,
    /** The furthest saved chapter over Comick, AniList and MangaUpdates. */
    val progress: Int,
    val latestChapter: Int,
    /** The scanlation group of [latestChapter], else "Comick". */
    val source: String,
    val latestChapterAt: Long?,
    /** The notification id, also the key [UnreadCache.removeEntry] is given on "Mark as read". */
    val notifId: Int,
    /**
     * Publication status in AniList's words ("RELEASING", "HIATUS"…), for the dot a Comick-only
     * card wears. Null when unknown — and in entries cached before Comick listed it.
     */
    val publicationStatus: String? = null,
    /** Comick's site-wide score, 0–10, for a Comick-only card's score badge. */
    val bayesianRating: Double? = null,
) : Serializable {

    /** The id this title's unread info is keyed by in the row, matching what it is shown as. */
    val rowKey: Int
        get() = anilistMedia?.id ?: muMedia?.let { muMediaKey(it.id) } ?: notifId

    val webUrl: String get() = ComickApi.webUrl(slug ?: hid)

    fun info(progress: Int = this.progress): UnreadChapterInfo = UnreadChapterInfo(
        mediaId = rowKey,
        lastChapter = latestChapter,
        source = source,
        userProgress = progress,
        latestChapterAt = latestChapterAt,
    )

    companion object {
        // Pinned: see UnreadChapterStore.
        private const val serialVersionUID = 1L

        /**
         * The exclusion-list id of a title known only to Comick. Other titles go by their AniList
         * id or MangaUpdates key, as everywhere else.
         */
        fun excludeId(hid: String) = "comick:$hid"
    }
}
