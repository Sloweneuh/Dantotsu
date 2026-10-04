package ani.dantotsu.connections.comick

import ani.dantotsu.connections.IdCache
import ani.dantotsu.connections.TrackerSessions
import ani.dantotsu.connections.malsync.MalSyncApi
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-way list synchronisation to Comick (manga and anime).
 *
 * Whenever the user updates an entry on AniList (or MangaUpdates), its status and chapter/episode
 * progress are pushed to their Comick library. Only those two exist on Comick's side: scores,
 * dates, privacy and reread counts have no field there and are left out.
 *
 * Two rules keep a push from damaging what is already on Comick, both from Comick's own tracker
 * guidance:
 *  - **Progress never goes down.** Comick may well be ahead — the user reads there too — so a push
 *    only ever raises it, and leaves alone progress Comick holds that isn't a plain number.
 *  - **Removals aren't mirrored.** Unfollowing on Comick also deletes the entry's notes, rating
 *    and custom-list memberships, which is too much to do on the strength of an edit made
 *    elsewhere.
 *
 * Needs a connection that granted `library:write` ([Comick.canWrite]). The Comick entries are found
 * from the AniList id alone — see [resolveFromAnilist] — so a series that can't be resolved that
 * way is skipped rather than searched for by title. A series Comick lists more than once is
 * handled by [pushToSeries].
 */
object ComickSync {

    /**
     * Waits for the session restore first, like the other trackers — see [TrackerSessions].
     * [force] bypasses the sync switch for an explicit user action (Compare lists), so it only
     * needs a connection with write access.
     */
    suspend fun isEnabled(force: Boolean = false): Boolean {
        TrackerSessions.await()
        val connected = Comick.token != null
        val canWrite = Comick.canWrite()
        val switchedOn = force || PrefManager.getVal<Boolean>(PrefName.ComickListSyncEnabled)
        if (!(connected && canWrite && switchedOn)) {
            Logger.log("ComickSync: off (connected=$connected, canWrite=$canWrite, switch=$switchedOn)")
        }
        return connected && canWrite && switchedOn
    }

    /** Comick's library status (1 reading … 5 plan to read) in AniList's vocabulary. */
    fun toCanon(status: Int?): String = when (status) {
        2 -> "COMPLETED"
        3 -> "PAUSED"
        4 -> "DROPPED"
        5 -> "PLANNING"
        else -> "CURRENT"
    }

    /**
     * What an AniList status becomes on Comick, back in AniList's vocabulary: everything but a
     * reread, which Comick has no status for and records as reading.
     */
    fun comparableCanon(anilistStatus: String?): String = toCanon(mapAnilistStatus(anilistStatus))

    /**
     * @param progress the whole chapter/episode AniList holds.
     * @param exactProgress the number as the reader had it ("12.5"), when known. Comick keeps
     *   decimals where AniList can't, so this wins over [progress] whenever it is given.
     */
    suspend fun syncFromAnilist(
        isAnime: Boolean,
        anilistId: Int?,
        malId: Int?,
        status: String?,
        progress: Int?,
        exactProgress: String? = null,
        force: Boolean = false,
    ): Boolean {
        Logger.log(
            "ComickSync: AniList ${if (isAnime) "anime" else "manga"} $anilistId → " +
                "status=$status progress=$progress exact=$exactProgress"
        )
        if (!isEnabled(force)) return false
        val hids = resolveFromAnilist(isAnime, anilistId ?: return false, malId)
        Logger.log("ComickSync: AniList $anilistId resolved to $hids")
        return pushToSeries(hids, mapAnilistStatus(status), exactProgress ?: progress?.toString(), isAnime)
    }

    /** See [syncFromAnilist] for [exactProgress]. */
    suspend fun syncFromMangaUpdates(
        muSeriesId: Long?,
        muListId: Int?,
        progress: Int?,
        exactProgress: String? = null,
        force: Boolean = false,
    ): Boolean {
        Logger.log("ComickSync: MangaUpdates $muSeriesId → list=$muListId progress=$progress exact=$exactProgress")
        if (!isEnabled(force)) return false
        val cross = MangaBakaApi.getCrossIdsFromMangaUpdates(muSeriesId ?: return false)
        val hids = resolveFromAnilist(false, cross.anilistId ?: run {
            Logger.log("ComickSync: no AniList id for MangaUpdates $muSeriesId")
            return false
        }, cross.malId)
        Logger.log("ComickSync: MangaUpdates $muSeriesId resolved to $hids")
        return pushToSeries(hids, mapMangaUpdatesList(muListId), exactProgress ?: progress?.toString(), isAnime = false)
    }

    /**
     * A notification's "Mark as read" on a Comick-only title. An explicit action, so it skips the
     * list-sync switch — but still needs write access, and still never lowers progress.
     */
    suspend fun markRead(hid: String, progress: String): Boolean {
        TrackerSessions.await()
        if (Comick.token == null || !Comick.canWrite()) return false
        val current = Comick.getEntry(hid) ?: return false
        return push(hid, current, status = null, progress = progress, isAnime = false)
    }

    /**
     * Pushes to the Comick entries of one series — [hids], the preferred one first.
     *
     * Comick often lists a series more than once (an official release beside a scanlation), and a
     * user follows whichever they read. So every entry already followed is kept in step, and only
     * when none is does the preferred one get followed: following them all would fill the library
     * with duplicates.
     */
    private suspend fun pushToSeries(hids: List<String>, status: Int?, progress: String?, isAnime: Boolean): Boolean {
        if (hids.isEmpty()) return false
        val lookups = hids.map { it to Comick.getEntry(it) }
        Logger.log("ComickSync: library entries ${lookups.map { (hid, l) -> "$hid=${l ?: "lookup failed"}" }}")
        val followed = lookups.filter { it.second is Comick.EntryLookup.Followed }
        val targets = followed.ifEmpty {
            // Only safe to follow the preferred entry once every lookup answered: one that
            // failed may be the entry the user actually follows.
            if (lookups.any { it.second == null }) return false
            lookups.take(1)
        }
        var ok = true
        for ((hid, lookup) in targets) {
            if (!push(hid, lookup ?: continue, status, progress, isAnime)) ok = false
        }
        return ok
    }

    /**
     * Follows or updates one entry, applying the rules in the class doc.
     *
     * Manga progress is recorded against Comick's own chapter when it has one by that number —
     * a bare number is stored, but Comick's site shows no progress without a chapter behind it.
     * For the same reason a push at the number already saved still goes out when the saved one
     * has no chapter and this can supply it. Anime episodes keep the plain number.
     */
    private suspend fun push(
        hid: String,
        current: Comick.EntryLookup,
        status: Int?,
        progress: String?,
        isAnime: Boolean,
    ): Boolean {
        val followed = (current as? Comick.EntryLookup.Followed)?.entry
        // A title isn't followed without a status to follow it under.
        if (followed == null && status == null) return false

        // Compared as numbers, so "12" from an AniList edit can't overwrite "12.5" read here.
        val remote = followed?.progress
        val remoteNumber = remote?.number?.toDoubleOrNull()
        val local = progress?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
        val number = local?.let { formatNumber(it, progress) }
        val raises = local != null && (remote?.number == null || (remoteNumber != null && local > remoteNumber))
        val unlinkedSame = local != null && remoteNumber == local && remote.hid == null
        val chapterHid = number?.takeIf { !isAnime && (raises || unlinkedSame) }
            ?.let { ComickApi.findChapterHid(hid, it) }
        val sendProgress = number?.takeIf { raises || (unlinkedSame && chapterHid != null) }
        // Only what differs — though a new follow always carries its status.
        val sendStatus = if (followed == null) status else status?.takeIf { it != followed.status }
        Logger.log(
            "ComickSync: $hid saved=${remote?.number}/${remote?.hid} local=$number raises=$raises " +
                "unlinkedSame=$unlinkedSame chapter=$chapterHid → status=$sendStatus progress=$sendProgress"
        )
        if (sendStatus == null && sendProgress == null) return true

        return Comick.putEntry(hid, sendStatus, sendProgress, chapterHid.takeIf { sendProgress != null })
            .also { ok -> if (!ok) Logger.log("ComickSync: push failed for $hid") }
    }

    /** "12.0" → "12"; anything with a real fraction is sent the way the source wrote it. */
    private fun formatNumber(value: Double, original: String): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else original.trim()

    /**
     * Every Comick entry for an AniList entry, the preferred one first — empty when none is known.
     * Found without a title search:
     *  1. HIDs resolved before;
     *  2. the slug the user pinned on the media page, then those its id-validated match found;
     *  3. MALSync's Comick links, kept only when Comick lists this AniList or MAL id itself.
     */
    suspend fun resolveFromAnilist(isAnime: Boolean, anilistId: Int, malId: Int?): List<String> {
        val mediaType = if (isAnime) ComickApi.MEDIA_TYPE_ANIME else ComickApi.MEDIA_TYPE_MANGA
        val hidsKey = "comick_hids_${mediaType}_$anilistId"
        val cached = IdCache[hidsKey]
        // "-<time>" marks a recent miss; anything else is the HIDs found.
        cached?.takeIf { !it.startsWith(MISS_PREFIX) }
            ?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val pinnedKey = if (isAnime) ComickEpisodes.savedSlugKey(anilistId) else "comick_slug_$anilistId"
        val knownSlugs = buildList {
            PrefManager.getNullableCustomVal<String>(pinnedKey, null, String::class.java)?.let { add(it) }
            IdCache[ComickApi.matchedSlugKey(mediaType, anilistId)]?.split(',')?.let { addAll(it) }
        }.filter { it.isNotBlank() }.distinct()
        // Already validated (or chosen by the user), so taken as they are.
        val known = knownSlugs.mapNotNull { slug ->
            ComickApi.getComicDetails(slug, mediaType = mediaType)?.comic?.hid
        }.distinct()
        if (known.isNotEmpty()) return known.also { IdCache.put(hidsKey, it.joinToString(",")) }

        // A miss is remembered for a while: Compare lists and the automatic pass resolve the whole
        // list every run, and asking MALSync about every unmatched title each time would cost a
        // request apiece for nothing. Checked only after the known slugs above, so opening the
        // media page (which records its match) still links a title straight away.
        cached?.takeIf { it.startsWith(MISS_PREFIX) }?.removePrefix(MISS_PREFIX)?.toLongOrNull()
            ?.takeIf { System.currentTimeMillis() - it < MISS_TTL_MS }
            ?.let { return emptyList() }

        val quicklinks = withTimeoutOrNull(MALSYNC_TIMEOUT_MS) {
            MalSyncApi.getQuicklinks(anilistId, malId, mediaType)
        }
        val malSyncSlugs = quicklinks?.Sites?.entries
            ?.firstOrNull { it.key.contains("comick", ignoreCase = true) }
            ?.value?.values?.mapNotNull { it.identifier }
            .orEmpty()
        val validated = malSyncSlugs.mapNotNull { slug ->
            ComickApi.getComicDetails(slug, mediaType = mediaType)?.comic?.takeIf { comic ->
                comic.links?.al == anilistId.toString() ||
                    (malId != null && comic.links?.mal == malId.toString())
            }
        }
            // Preferred entry first: the most followed, as the media page's own match picks.
            .sortedByDescending { it.user_follow_count ?: 0 }
            .mapNotNull { it.hid }
            .distinct()
        if (validated.isNotEmpty()) return validated.also { IdCache.put(hidsKey, it.joinToString(",")) }

        Logger.log("ComickSync: no Comick entry known for AniList $mediaType $anilistId")
        // Only a real answer is a miss; a MALSync that didn't answer leaves it to the next try.
        if (quicklinks != null) IdCache.put(hidsKey, "$MISS_PREFIX${System.currentTimeMillis()}")
        return emptyList()
    }

    /** AniList status → Comick's 1 reading, 2 completed, 3 on hold, 4 dropped, 5 plan to read. */
    private fun mapAnilistStatus(status: String?): Int? = when (status) {
        // Comick has no rereading status; a reread is still being read.
        "CURRENT", "REPEATING" -> 1
        "COMPLETED" -> 2
        "PAUSED" -> 3
        "DROPPED" -> 4
        "PLANNING" -> 5
        else -> null
    }

    /** MangaUpdates list id (0 reading, 1 wish, 2 complete, 3 unfinished, 4 on hold) → Comick. */
    private fun mapMangaUpdatesList(listId: Int?): Int? = when (listId) {
        0 -> 1
        1 -> 5
        2 -> 2
        3 -> 4
        4 -> 3
        else -> null
    }

    private const val MALSYNC_TIMEOUT_MS = 10_000L
    private const val MISS_PREFIX = "-"
    private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000
}
