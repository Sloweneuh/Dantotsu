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
    /**
     * The AniList id Comick links, also when the title is shown as [muMedia] — so progress saved
     * on AniList since the check still counts. Null in entries cached before it was kept.
     */
    val anilistId: Int? = null,
    /**
     * [latestChapter] is a newer part of the split chapter [progress] is in (progress 60.1 on
     * Comick, 60.2 out): unread although the whole numbers are equal. False in older entries.
     */
    val split: Boolean = false,
    /** [latestChapter] as Comick numbers it, kept for a [split] one ("60.2") so it reads as that. */
    val latestExact: String? = null,
    /**
     * [progress] as Comick numbers it, kept when it's part of a split chapter ("45.1") so it reads
     * as that. Null otherwise, and in older entries.
     */
    val progressExact: String? = null,
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
        lastChapterLabel = latestLabel,
        userProgressLabel = progressLabel(progress),
    )

    /**
     * The saved chapter as the row shows it: Comick's exact part of a split chapter while [shown]
     * (the progress the row has, maybe saved on AniList since) is still that whole chapter.
     */
    fun progressLabel(shown: Int = progress): String? =
        progressExact?.takeIf { it.toDoubleOrNull()?.let { exact -> kotlin.math.floor(exact).toInt() } == shown }

    /** The newest chapter as the row shows it: exactly, for a split one. */
    val latestLabel: String? get() = latestExact?.takeIf { split }

    companion object {
        // Pinned: see UnreadChapterStore.
        private const val serialVersionUID = 1L
    }
}
