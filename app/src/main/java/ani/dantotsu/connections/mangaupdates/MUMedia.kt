package ani.dantotsu.connections.mangaupdates

import ani.dantotsu.media.Media
import ani.dantotsu.media.manga.Manga
import ani.dantotsu.settings.saving.PrefManager
import java.io.Serializable

/**
 * Lightweight representation of a MangaUpdates series entry as it appears in a user list.
 */
data class MUMedia(
    val id: Long,
    val title: String?,
    val url: String?,
    /** Cover image URL – populated lazily by [MangaUpdates.getSeriesDetails] */
    val coverUrl: String? = null,
    /** List status: 0=Reading, 1=Planning, 2=Completed, 3=Dropped, 4=Paused */
    val listId: Int,
    val userChapter: Int?,
    val userVolume: Int?,
    val latestChapter: Int?,
    val bayesianRating: Double?,
    val priority: Int?,
    val format: String? = null,
    /** Local timestamp (ms) of the last time the user updated progress for this series. */
    val updatedAt: Long? = null,
    /** MangaUpdates "time added to list" (Unix seconds); maps to the AniList start date on convert. */
    val addedAt: Long? = null
) : Serializable

/** Converts a [MUMedia] into a minimal [Media] suitable for display and random-pick. */
fun MUMedia.toMedia(): Media = Media(
    id = (id and 0x7FFFFFFF).toInt(),
    name = title,
    nameRomaji = title ?: "",
    userPreferredName = title ?: "",
    cover = coverUrl,
    banner = coverUrl,
    isAdult = false,
    // MangaUpdates has no concept of a definitive last/total chapter (latestChapter is
    // just the newest known release), so leave this null to display "~" like AniList.
    manga = Manga(totalChapters = null),
    format = if (format?.contains("novel", ignoreCase = true) == true) "NOVEL" else "MANGA",
    userProgress = userChapter,
    muSeriesId = id,
    muListId = listId,
    muLatestChapter = latestChapter,
)

/**
 * Rebuilds a [MUMedia] from a MangaUpdates-backed [Media] — one carrying a non-null
 * [Media.muSeriesId], which is how the media lists mark a MangaUpdates entry. Returns null for
 * ordinary AniList media. Inverse of [toMedia].
 */
fun Media.toMUMedia(): MUMedia? {
    val seriesId = muSeriesId ?: return null
    return MUMedia(
        id = seriesId,
        title = name ?: userPreferredName,
        url = shareLink ?: "https://www.mangaupdates.com/series/${seriesId.toString(36)}",
        coverUrl = cover,
        // -1 is the "not in the user's list" sentinel MUMediaDetailsActivity expects.
        listId = muListId ?: -1,
        userChapter = userProgress,
        userVolume = null,
        latestChapter = muLatestChapter,
        bayesianRating = null,
        priority = null,
        format = format,
    )
}

const val PREF_MU_LAST_READ_PREFIX = "mu_last_read_"

/**
 * Converts a [MUListEntry] to a [MUMedia] instance.
 * Returns null if the entry is missing a series id.
 */
fun MUListEntry.toMUMedia(listId: Int): MUMedia? {
    val series = record?.series ?: return null
    val savedTs = PrefManager.getNullableCustomVal<Long>("$PREF_MU_LAST_READ_PREFIX${series.id}", null, Long::class.java)
    // Server-side timestamp (Unix seconds → ms); present on all devices after a list fetch
    val serverTs = record.timeAdded?.timestamp?.let { it * 1000L }
    val updatedAt = when {
        savedTs != null && serverTs != null -> maxOf(savedTs, serverTs)
        else -> savedTs ?: serverTs
    }
    return MUMedia(
        id = series.id,
        title = series.title,
        url = series.url,
        listId = listId,
        userChapter = record.status?.chapter,
        userVolume = record.status?.volume,
        latestChapter = metadata?.series?.latestChapter,
        bayesianRating = metadata?.series?.bayesianRating,
        priority = record.priority,
        updatedAt = updatedAt,
        addedAt = record.timeAdded?.timestamp
    )
}
