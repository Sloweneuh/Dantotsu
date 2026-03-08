package ani.dantotsu.connections.mangaupdates

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
    /** Local timestamp (ms) of the last time the user updated progress for this series. */
    val updatedAt: Long? = null
) : Serializable

const val PREF_MU_LAST_READ_PREFIX = "mu_last_read_"

/**
 * Converts a [MUListEntry] to a [MUMedia] instance.
 * Returns null if the entry is missing a series id.
 */
fun MUListEntry.toMUMedia(listId: Int): MUMedia? {
    val series = record?.series ?: return null
    val savedTs = PrefManager.getNullableCustomVal<Long>("$PREF_MU_LAST_READ_PREFIX${series.id}", null, Long::class.java)
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
        updatedAt = savedTs
    )
}
