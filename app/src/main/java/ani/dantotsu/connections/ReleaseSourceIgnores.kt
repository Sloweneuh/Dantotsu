package ani.dantotsu.connections

import androidx.annotation.StringRes
import ani.dantotsu.App
import ani.dantotsu.R
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.connections.mangaupdates.muMediaKey
import ani.dantotsu.notifications.unread.ComickUnreadEntry
import ani.dantotsu.notifications.unread.UnreadCache
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * Release-progress providers the user has told the app to stop believing, per series.
 *
 * A series' newest chapter is the highest any of MALSync, MangaUpdates and Comick reports, so one
 * that runs ahead of reality — a mislinked entry, a fan upload numbered as the next chapter — wins
 * everywhere: the media page header, the home unread row and its widget, the list editor's hint,
 * and the unread notifications. Ignoring it here takes it out of all of them for that series.
 *
 * A series is named as its media page names it: an AniList id, or a MangaUpdates series by the
 * same truncated key ([muMediaKey]) its unread info is stored under. Each provider is ignored per
 * page — a series on both an AniList and a MangaUpdates page is set on each.
 *
 * Kept as one string set in [PrefName.IgnoredReleaseSources], entries `provider|scope:id`.
 */
object ReleaseSourceIgnores {

    enum class Provider(val key: String, @StringRes val title: Int) {
        MALSYNC("malsync", R.string.release_source_malsync),
        MANGAUPDATES("mangaupdates", R.string.release_source_mangaupdates),
        COMICK("comick", R.string.release_source_comick),
    }

    fun anilist(id: Int) = "al:$id"
    fun mu(muSeriesId: Long) = muKey(muMediaKey(muSeriesId))
    fun muKey(key: Int) = "mu:$key"

    private fun entry(provider: Provider, target: String) = "${provider.key}|$target"

    private fun ignored(): Set<String> = PrefManager.getVal(PrefName.IgnoredReleaseSources)

    fun isIgnored(provider: Provider, target: String): Boolean = entry(provider, target) in ignored()

    /** Changes one, and has the unread row and its widget redraw without it (or with it again). */
    fun setIgnored(provider: Provider, target: String, ignore: Boolean) {
        val current = ignored()
        val key = entry(provider, target)
        PrefManager.setVal(PrefName.IgnoredReleaseSources, if (ignore) current + key else current - key)
        (App.instance ?: App.context)?.let(UnreadCache::broadcastUpdate)
    }

    /** MALSync's answers for AniList media, without those ignored for their series. */
    fun filterAniList(info: Map<Int, UnreadChapterInfo>): Map<Int, UnreadChapterInfo> {
        val set = ignored()
        if (set.isEmpty()) return info
        return info.filterKeys { entry(Provider.MALSYNC, anilist(it)) !in set }
    }

    /** MALSync's answers for MangaUpdates series (keyed by [muMediaKey]), likewise. */
    fun filterMu(info: Map<Int, UnreadChapterInfo>): Map<Int, UnreadChapterInfo> {
        val set = ignored()
        if (set.isEmpty()) return info
        return info.filterKeys { entry(Provider.MALSYNC, muKey(it)) !in set }
    }

    /** MangaUpdates' own newest chapter for [muSeriesId], or null when it is ignored there. */
    fun muLatest(muSeriesId: Long, latest: Int?): Int? =
        latest?.takeUnless { isIgnored(Provider.MANGAUPDATES, mu(muSeriesId)) }

    /** Whether Comick is ignored on any page this Comick title is shown as. */
    fun comickIgnored(anilistId: Int?, muSeriesIds: Collection<Long>): Boolean {
        val set = ignored()
        if (set.isEmpty()) return false
        if (anilistId != null && entry(Provider.COMICK, anilist(anilistId)) in set) return true
        return muSeriesIds.any { entry(Provider.COMICK, mu(it)) in set }
    }

    fun comickIgnored(entry: ComickUnreadEntry): Boolean =
        comickIgnored(entry.anilistMedia?.id ?: entry.anilistId, listOfNotNull(entry.muMedia?.id))
}

/**
 * Each provider's last answer for a series — its newest chapter or episode, where, and when it was
 * asked — whether or not that put the user behind.
 *
 * The unread caches only hold the series that are behind, so for one the user is caught up on they
 * had nothing to show, and the sources sheet could not tell "caught up" from "never asked". The
 * checks that ask every provider about the whole library write their answers here as they go;
 * the media pages do too, for what they fetch live.
 *
 * One custom val per provider and series (`release_answer_<provider>_<scope:id>`), so a check
 * writes only what changed, and the sheet reads only what it shows.
 */
object ReleaseSourceAnswers {

    data class Answer(val latest: Int, val source: String?, val at: Long)

    private fun key(provider: ReleaseSourceIgnores.Provider, target: String) =
        "release_answer_${provider.key}_$target"

    /** Stores [answers] (series target → newest chapter and its source) for [provider], in one write. */
    fun record(provider: ReleaseSourceIgnores.Provider, answers: Map<String, Pair<Int, String?>>) {
        if (answers.isEmpty()) return
        val now = System.currentTimeMillis()
        try {
            PrefManager.applyCustomVals(
                sets = answers.entries.associate { (target, answer) ->
                    key(provider, target) to "${answer.first}|$now|${answer.second.orEmpty()}"
                }
            )
        } catch (e: Exception) {
            ani.dantotsu.util.Logger.log("ReleaseSourceAnswers: record failed: ${e.message}")
        }
    }

    fun record(provider: ReleaseSourceIgnores.Provider, target: String, latest: Int, source: String?) =
        record(provider, mapOf(target to (latest to source)))

    fun get(provider: ReleaseSourceIgnores.Provider, target: String): Answer? {
        val raw = PrefManager.getNullableCustomVal(key(provider, target), null, String::class.java)
            ?: return null
        val parts = raw.split('|', limit = 3)
        val latest = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return Answer(latest, parts.getOrNull(2)?.takeIf { it.isNotBlank() }, at)
    }
}
