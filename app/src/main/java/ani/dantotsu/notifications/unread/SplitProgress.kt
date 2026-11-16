package ani.dantotsu.notifications.unread

import ani.dantotsu.settings.saving.PrefManager
import kotlin.math.floor

/**
 * Where a series is inside a split chapter ("45.1" while the trackers count 45): only Comick and
 * the reader know it, the trackers keep whole numbers. Gathered from what was read here and from
 * Comick's own progress (the last unread check's, or a media page's live lookup), so the media
 * page, the chapter list and the home row agree on it.
 */
object SplitProgress {
    private fun key(id: Int) = "split_progress_$id"

    /** Bumped on every change, for screens that keep the answer between redraws. */
    @Volatile
    var version = 0
        private set

    /** Posts the media id whose split progress just moved, for the screens showing it. */
    val changes = androidx.lifecycle.MutableLiveData<Int>()

    /**
     * Notes chapter [number] of media [id] as read. Only a part of a split chapter is kept, and
     * only past what's already known; a whole number needs no keeping (the trackers have it).
     * The home row's cached Comick copy is raised with it too.
     */
    fun record(id: Int, number: String?, alsoIds: List<Int> = emptyList()) {
        val text = number?.trim() ?: return
        val exact = text.toDoubleOrNull()?.takeIf { it > 0 } ?: return
        if (exact % 1.0 == 0.0) return
        val stored = stored(id)
        if (stored == null || floor(stored).toInt() != floor(exact).toInt() || exact > stored) {
            PrefManager.setCustomVal(key(id), text)
            version++
            changes.postValue(id)
        }
        (listOf(id) + alsoIds).distinct().forEach { UnreadCache.raiseComickProgressFor(it, text) }
    }

    private fun stored(id: Int): Double? =
        PrefManager.getNullableCustomVal(key(id), null, String::class.java)?.toDoubleOrNull()

    /**
     * The part of split chapter [whole] media [id] is read to ("45.1"), or null when it isn't in
     * one — or has moved past it. [live] is Comick's answer from the page asking, when it has one.
     */
    fun label(id: Int, whole: Int?, live: String? = null): String? {
        whole ?: return null
        val known = listOfNotNull(
            PrefManager.getNullableCustomVal(key(id), null, String::class.java),
            live,
        ) + UnreadCache.cachedComick()
            .filter { it.rowKey == id || (it.anilistMedia?.id ?: it.anilistId) == id }
            .mapNotNull { it.progressExact }
        return known.map { it.trim() }
            .filter { s -> s.toDoubleOrNull()?.let { it % 1.0 != 0.0 && floor(it).toInt() == whole } == true }
            .maxByOrNull { it.toDouble() }
    }

    /** [label] as a number. */
    fun exact(id: Int, whole: Int?, live: String? = null): Double? = label(id, whole, live)?.toDouble()
}
