package ani.dantotsu.connections.malsync

import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.cachedMuMalId
import ani.dantotsu.connections.mangaupdates.muMediaKey
import ani.dantotsu.connections.mangaupdates.resolveMuMalId
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * MALSync for MangaUpdates media.
 *
 * MALSync is keyed on MyAnimeList and MangaUpdates carries no MAL id of its own, so a MangaUpdates
 * series can only be looked up once one has been resolved for it through
 * [ani.dantotsu.connections.mangaupdates.resolveMuMalId]. Series that MAL simply doesn't carry get
 * nothing — every entry point here answers null/empty for them, leaving the caller on whatever
 * MangaUpdates itself said.
 *
 * What none of this may do is fall back to the *media key*: a MangaUpdates-backed
 * [ani.dantotsu.media.Media] carries a synthetic id (see [muMediaKey]) that MALSync's `anilist:`
 * route would read as an unrelated series. That's why the calls below pass `anilistId = null` and
 * why the batch one drops entries whose MAL id didn't resolve rather than letting
 * [MalSyncApi.getBatchProgressByMedia] key them by AniList id.
 */
object MalSyncMu {

    /** Concurrency for per-series id resolution, matching the limit the MU caches use. */
    private const val RESOLVE_CONCURRENCY = 5

    private const val MISS_PREFIX = "malsync_mu_unlinked_"

    /** How long a whole-list pass leaves a series it couldn't link alone. */
    private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Whether a list pass should skip resolving this series again.
     *
     * [ani.dantotsu.connections.mangaupdates.resolveMuMalId] caches the ids it finds but only
     * remembers a miss for the session — deliberately, since a miss there can mean "the fallback had
     * nothing to search with yet". That is the right call for a screen the user opened; it is the
     * wrong one for a list walked on a schedule in a fresh process, where every unlinkable series
     * costs a MangaBaka lookup on every single run. Most series simply have no MAL entry and never
     * will, so a pass writes the miss down and leaves them alone for a week.
     */
    private fun recentlyUnlinkable(muSeriesId: Long): Boolean {
        // A screen may have linked it since — through the Comick fallback a list pass doesn't run —
        // in which case the id is already there for the taking and the old miss means nothing.
        if (cachedMuMalId(muSeriesId) != null) return false
        val at = PrefManager.getNullableCustomVal("$MISS_PREFIX$muSeriesId", 0L, Long::class.java)
            ?: 0L
        return at > 0L && System.currentTimeMillis() - at < MISS_TTL_MS
    }

    private fun recordUnlinkable(muSeriesId: Long, unlinkable: Boolean) {
        val key = "$MISS_PREFIX$muSeriesId"
        if (unlinkable) PrefManager.setCustomVal(key, System.currentTimeMillis())
        else PrefManager.removeCustomVal(key)
    }

    /**
     * Whether MALSync should be consulted for manga at all — the same gate the AniList side applies,
     * since the user's MALSync toggle and check mode aren't per-source.
     */
    fun enabledForManga(): Boolean {
        if (!PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled)) return false
        val mode = PrefManager.getVal<String>(PrefName.MalSyncCheckMode) ?: "both"
        return mode != "anime"
    }

    /**
     * The MAL id MALSync should be asked about for a MangaUpdates series, or null when the series
     * has no MAL entry (or MALSync is off for manga, in which case there's no point resolving one).
     *
     * [comickFallback] carries [resolveMuMalId]'s meaning: leave it off where a per-entry Comick
     * search is too expensive to pay for, such as walking a whole list.
     */
    suspend fun malId(
        muSeriesId: Long,
        titles: List<String>,
        comickSlug: String? = null,
        comickFallback: Boolean = true,
    ): Int? {
        if (!enabledForManga()) return null
        return withContext(Dispatchers.IO) {
            try {
                resolveMuMalId(muSeriesId, titles, comickSlug, comickFallback)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** MALSync's latest chapter for a MangaUpdates series. Null when it has no MAL entry. */
    suspend fun lastChapter(
        muSeriesId: Long,
        titles: List<String>,
        comickSlug: String? = null,
    ): MalSyncResponse? {
        val malId = malId(muSeriesId, titles, comickSlug) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                MalSyncApi.getLastChapter(anilistId = null, malId = malId)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** MALSync's quicklinks for a MangaUpdates series. Null when it has no MAL entry. */
    suspend fun quicklinks(
        muSeriesId: Long,
        titles: List<String>,
        comickSlug: String? = null,
    ): QuicklinksResponse? {
        val malId = malId(muSeriesId, titles, comickSlug) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                MalSyncApi.getQuicklinks(anilistId = null, malId = malId, mediaType = "manga")
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * MALSync progress for a list of MangaUpdates entries, keyed by [muMediaKey] so it merges with
     * the AniList half of the unread row.
     *
     * Ids are resolved through MangaBaka's mapping only — the Comick fallback costs a search plus a
     * details call per entry, which is the same reason the list-compare screen skips it. Entries
     * that don't resolve are left out, so callers keep MangaUpdates' own chapter count for them.
     */
    suspend fun unreadInfo(items: List<MUMedia>): Map<Int, UnreadChapterInfo> {
        if (!enabledForManga() || items.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            val semaphore = Semaphore(RESOLVE_CONCURRENCY)
            val attempted = coroutineScope {
                items.filterNot { recentlyUnlinkable(it.id) }.map { item ->
                    async {
                        item to semaphore.withPermit {
                            try {
                                resolveMuMalId(
                                    item.id,
                                    listOfNotNull(item.title),
                                    comickFallback = false,
                                )
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }.awaitAll()
            }
            val withMalIds = attempted.mapNotNull { (item, malId) -> malId?.let { item to it } }

            // A miss is only written down when this pass proves MangaBaka was actually answering —
            // its lookup reports an unreachable host the same way it reports "no such series", and a
            // week of silence for the whole list is not a thing to conclude from one failed request.
            if (withMalIds.isNotEmpty()) {
                attempted.forEach { (item, malId) -> recordUnlinkable(item.id, malId == null) }
            }
            if (withMalIds.isEmpty()) return@withContext emptyMap()

            val results = try {
                MalSyncApi.getBatchProgressByMedia(
                    withMalIds.map { (item, malId) -> muMediaKey(item.id) to malId }
                )
            } catch (e: Exception) {
                emptyMap()
            }

            withMalIds.mapNotNull { (item, _) ->
                val key = muMediaKey(item.id)
                val result = results[key] ?: return@mapNotNull null
                val lastEp = result.lastEp ?: return@mapNotNull null
                key to UnreadChapterInfo(
                    mediaId = key,
                    lastChapter = lastEp.total,
                    source = result.source,
                    userProgress = item.userChapter ?: 0,
                    latestChapterAt = lastEp.timestampMillis(),
                )
            }.toMap()
        }
    }

    /**
     * The chapter count to show for a MangaUpdates entry: the further along of what MangaUpdates
     * knows and what MALSync knows.
     *
     * Neither is authoritative on its own — MangaUpdates' `latest_chapter` trails the scanlation
     * sites it aggregates, and MALSync only sees the sites it tracks — so taking the higher is what
     * stops adding MALSync from *hiding* chapters an entry already showed.
     */
    fun latestChapter(muLatest: Int?, malSyncLatest: Int?): Int? = when {
        muLatest == null -> malSyncLatest
        malSyncLatest == null -> muLatest
        else -> maxOf(muLatest, malSyncLatest)
    }
}
