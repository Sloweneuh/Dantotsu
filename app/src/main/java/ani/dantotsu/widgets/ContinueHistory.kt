package ani.dantotsu.widgets

import ani.dantotsu.settings.saving.PrefManager

/**
 * What the user last actually watched or read, across both types.
 *
 * The app already keeps `continueAnimeList` and `continueMangaList` — appended when an episode starts
 * playing or a chapter opens — but they are two separate lists with no shared ordering, so "the last
 * five things I opened" cannot be answered from them. This keeps one combined list alongside, and
 * falls back to interleaving the two legacy lists for users who have not opened anything since.
 *
 * Note what this is *not*: the AniList CURRENT list. That can hold a thousand releasing series, nearly
 * all of which the user has never opened in the app; ordering by real usage is what keeps a "continue"
 * widget to the handful of things actually in progress.
 */
object ContinueHistory {

    /**
     * @param mediaId the AniList id, or for a MangaUpdates series the truncated key
     *   ([ani.dantotsu.connections.mangaupdates.muMediaKey]) — which is never an AniList id.
     * @param muSeriesId set for MangaUpdates series, whose details cannot be fetched from AniList.
     */
    data class Entry(val mediaId: Int, val isAnime: Boolean, val muSeriesId: Long? = null)

    private const val KEY = "continueRecentList"
    private const val LEGACY_ANIME = "continueAnimeList"
    private const val LEGACY_MANGA = "continueMangaList"
    private const val MAX_ENTRIES = 50

    /**
     * Records that [mediaId] was just opened. Most recent wins; the entry moves to the front.
     *
     * [muSeriesId] must be passed for a MangaUpdates series. Without it the entry is indistinguishable
     * from an AniList one, and its truncated id would be sent to AniList — which resolves to nothing
     * at best, and to an unrelated series at worst.
     */
    fun record(mediaId: Int, isAnime: Boolean, muSeriesId: Long? = null) {
        val token = token(mediaId, isAnime, muSeriesId)
        val updated = (stored().toMutableList().apply { remove(token) })
            .let { listOf(token) + it }
            .take(MAX_ENTRIES)
        PrefManager.setCustomVal(KEY, updated)
    }

    /** The [limit] most recently opened media, newest first. */
    fun recent(limit: Int): List<Entry> {
        val combined = stored().mapNotNull(::parse)
        return (if (combined.isEmpty()) legacyInterleaved() else combined).take(limit)
    }

    @Suppress("UNCHECKED_CAST")
    private fun stored(): List<String> = try {
        PrefManager.getNullableCustomVal(KEY, listOf<String>(), List::class.java) as? List<String>
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun legacyList(key: String): List<Int> = try {
        (PrefManager.getNullableCustomVal(key, listOf<Int>(), List::class.java) as? List<Int>)
            ?.reversed() // the legacy lists append the newest at the end
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Newest-first from both legacy lists, taken in turns. Their relative order is unknowable — no
     * timestamps were ever stored — so alternating is the least wrong thing available.
     */
    private fun legacyInterleaved(): List<Entry> {
        val anime = legacyList(LEGACY_ANIME).map { Entry(it, isAnime = true) }
        val manga = legacyList(LEGACY_MANGA).map { Entry(it, isAnime = false) }
        val merged = mutableListOf<Entry>()
        for (i in 0 until maxOf(anime.size, manga.size)) {
            anime.getOrNull(i)?.let(merged::add)
            manga.getOrNull(i)?.let(merged::add)
        }
        return merged
    }

    /** `a:123`, `m:123`, or `u:123:456789` for a MangaUpdates series (key, then real MU id). */
    private fun token(mediaId: Int, isAnime: Boolean, muSeriesId: Long?): String = when {
        muSeriesId != null -> "u:$mediaId:$muSeriesId"
        isAnime -> "a:$mediaId"
        else -> "m:$mediaId"
    }

    private fun parse(token: String): Entry? {
        val parts = token.split(':')
        val id = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return when (parts.firstOrNull()) {
            "u" -> Entry(id, isAnime = false, muSeriesId = parts.getOrNull(2)?.toLongOrNull())
            "a" -> Entry(id, isAnime = true)
            else -> Entry(id, isAnime = false)
        }
    }
}
