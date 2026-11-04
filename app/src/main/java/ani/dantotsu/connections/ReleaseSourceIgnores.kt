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
 * same truncated key ([muMediaKey]) its unread info is stored under — or, for a title only Comick
 * knows, its Comick HID. Each provider is ignored per page — a series on both an AniList and a
 * MangaUpdates page is set on each.
 *
 * Kept as one string set in [PrefName.IgnoredReleaseSources]: `provider|scope:id` for each provider
 * ignored, and `info|scope:id|muSeriesId|cover|title` naming the series for the management sheet.
 * One set, so the names sync and back up with what they name.
 */
object ReleaseSourceIgnores {

    enum class Provider(val key: String, @StringRes val title: Int) {
        MALSYNC("malsync", R.string.release_source_malsync),
        MANGAUPDATES("mangaupdates", R.string.release_source_mangaupdates),
        COMICK("comick", R.string.release_source_comick),
    }

    /** What the management sheet shows for a series. [muSeriesId] is what opens a MangaUpdates page. */
    data class Series(
        val target: String,
        val title: String?,
        val cover: String?,
        val muSeriesId: Long? = null,
    )

    data class IgnoredSeries(val series: Series, val providers: List<Provider>)

    private const val INFO_PREFIX = "info|"
    private const val COMICK_SCOPE = "ck:"

    /** Above any AniList id, and a truncated MangaUpdates id is all but never below it. */
    private const val LEGACY_ANILIST_ID_LIMIT = 10_000_000

    fun anilist(id: Int) = "al:$id"
    fun mu(muSeriesId: Long) = muKey(muMediaKey(muSeriesId))
    fun muKey(key: Int) = "mu:$key"
    fun comick(hid: String) = "$COMICK_SCOPE$hid"

    fun anilistIdOf(target: String): Int? = target.takeIf { it.startsWith("al:") }?.drop(3)?.toIntOrNull()
    fun comickHidOf(target: String): String? = target.takeIf { it.startsWith(COMICK_SCOPE) }?.drop(COMICK_SCOPE.length)
    fun muKeyOf(target: String): Int? = target.takeIf { it.startsWith("mu:") }?.drop(3)?.toIntOrNull()

    private fun entry(provider: Provider, target: String) = "${provider.key}|$target"

    private fun infoEntry(series: Series) =
        "$INFO_PREFIX${series.target}|${series.muSeriesId ?: ""}|${series.cover.orEmpty()}|${series.title.orEmpty()}"

    private fun Set<String>.withoutInfo(target: String) = filterNot { it.startsWith("$INFO_PREFIX$target|") }.toSet()

    private fun ignored(): Set<String> = PrefManager.getVal(PrefName.IgnoredReleaseSources)

    private fun notifyChanged() {
        (App.instance ?: App.context)?.let(UnreadCache::broadcastUpdate)
    }

    fun isIgnored(provider: Provider, target: String): Boolean = entry(provider, target) in ignored()

    /**
     * Changes one, and has the unread row and its widget redraw without it (or with it again).
     * [series] names the series for the management sheet when it is being ignored.
     */
    fun setIgnored(provider: Provider, target: String, ignore: Boolean, series: Series? = null) {
        val current = ignored()
        val key = entry(provider, target)
        val updated = if (ignore) {
            val named = if (series != null) current.withoutInfo(target) + infoEntry(series) else current
            named + key
        } else {
            val remaining = current - key
            val stillIgnored = Provider.entries.any { entry(it, target) in remaining }
            if (stillIgnored) remaining else remaining.withoutInfo(target)
        }
        PrefManager.setVal(PrefName.IgnoredReleaseSources, updated)
        notifyChanged()
    }

    /** The providers that can speak for [target]; ignoring them all silences the series. */
    private fun providersFor(target: String): List<Provider> = when {
        target.startsWith(COMICK_SCOPE) -> listOf(Provider.COMICK)
        target.startsWith("al:") -> listOf(Provider.MALSYNC, Provider.COMICK)
        else -> Provider.entries
    }

    /** Stops every provider counting for this series: a notification's "Mute". */
    fun ignoreAll(series: Series) {
        val keys = providersFor(series.target).map { entry(it, series.target) }
        PrefManager.setVal(
            PrefName.IgnoredReleaseSources,
            ignored().withoutInfo(series.target) + infoEntry(series) + keys,
        )
        notifyChanged()
    }

    fun malSyncIgnoredForAniList(id: Int): Boolean = isIgnored(Provider.MALSYNC, anilist(id))

    /** Whether nothing is left to report on this AniList manga. */
    fun anilistSilenced(id: Int): Boolean {
        val set = ignored()
        return providersFor(anilist(id)).all { entry(it, anilist(id)) in set }
    }

    /** Every series with an ignored provider, for the management sheet. Unnamed ones have no title. */
    fun ignoredSeries(): List<IgnoredSeries> {
        val set = ignored()
        val info = HashMap<String, Series>()
        val providers = LinkedHashMap<String, MutableList<Provider>>()
        set.forEach { raw ->
            if (raw.startsWith(INFO_PREFIX)) {
                val parts = raw.removePrefix(INFO_PREFIX).split('|', limit = 4)
                if (parts.size == 4) {
                    info[parts[0]] = Series(
                        target = parts[0],
                        title = parts[3].takeIf { it.isNotBlank() },
                        cover = parts[2].takeIf { it.isNotBlank() },
                        muSeriesId = parts[1].toLongOrNull(),
                    )
                }
            } else {
                val provider = Provider.entries.firstOrNull { raw.startsWith("${it.key}|") } ?: return@forEach
                providers.getOrPut(raw.substringAfter('|')) { mutableListOf() } += provider
            }
        }
        return providers.map { (target, ignoredProviders) ->
            IgnoredSeries(
                info[target] ?: Series(target, null, null),
                ignoredProviders.sortedBy { it.ordinal },
            )
        }
    }

    /** Stores names found for series that had none, leaving what is ignored as it is. */
    fun rememberSeries(found: Collection<Series>) {
        if (found.isEmpty()) return
        var set = ignored()
        found.forEach { series -> set = set.withoutInfo(series.target) + infoEntry(series) }
        PrefManager.setVal(PrefName.IgnoredReleaseSources, set)
    }

    /**
     * Moves the retired unread exclusion list ([PrefName.MalSyncExcludeList]) here: an excluded
     * series had every source shut out, so each provider is ignored for it. Runs at every start, as
     * a device on an older build or an old backup can hand the list back at any time.
     *
     * The list held AniList ids and truncated MangaUpdates ids alike and never told them apart.
     */
    fun migrateLegacyExclusions() {
        val legacy: Set<String> = PrefManager.getVal(PrefName.MalSyncExcludeList)
        if (legacy.isEmpty()) return
        var set = ignored()
        legacy.forEach { raw ->
            val parts = raw.split("||", limit = 3)
            val id = parts[0]
            val target = if (id.startsWith("comick:")) {
                comick(id.removePrefix("comick:"))
            } else {
                val number = id.toIntOrNull() ?: return@forEach
                if (number < LEGACY_ANILIST_ID_LIMIT) anilist(number) else muKey(number)
            }
            val series = Series(
                target = target,
                title = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                cover = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
            )
            set = set.withoutInfo(target) + infoEntry(series) + providersFor(target).map { entry(it, target) }
        }
        PrefManager.setVal(PrefName.IgnoredReleaseSources, set)
        PrefManager.setVal(PrefName.MalSyncExcludeList, emptySet<String>())
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

    /** Whether Comick is ignored on any page this Comick title is shown as, or as itself. */
    fun comickIgnored(anilistId: Int?, muSeriesIds: Collection<Long>, hids: Collection<String>): Boolean {
        val set = ignored()
        if (set.isEmpty()) return false
        if (anilistId != null && entry(Provider.COMICK, anilist(anilistId)) in set) return true
        if (hids.any { entry(Provider.COMICK, comick(it)) in set }) return true
        return muSeriesIds.any { entry(Provider.COMICK, mu(it)) in set }
    }

    fun comickIgnored(entry: ComickUnreadEntry): Boolean =
        comickIgnored(entry.anilistMedia?.id ?: entry.anilistId, listOfNotNull(entry.muMedia?.id), listOf(entry.hid))
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
