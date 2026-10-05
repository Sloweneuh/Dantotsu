package ani.dantotsu.connections.comick

import ani.dantotsu.connections.IdCache
import ani.dantotsu.connections.TrackerSessions
import ani.dantotsu.connections.malsync.MalSyncApi
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * One-way list synchronisation to Comick (manga and anime).
 *
 * Whenever the user updates an entry on AniList (or MangaUpdates), its status, chapter/episode
 * progress and (from AniList) score are pushed to their Comick library. Dates, privacy and reread
 * counts have no field there and are left out.
 *
 * Two rules keep a push from damaging what is already on Comick, both from Comick's own tracker
 * guidance:
 *  - **Progress never goes down.** Comick may well be ahead — the user reads there too — so a push
 *    only ever raises it, and leaves alone progress Comick holds that isn't a plain number.
 *  - **Ratings are never rounded.** Comick takes whole 1–10 ratings; an AniList score with no
 *    whole equivalent (75 out of 100) isn't sent, and a fractional rating already on Comick (one
 *    imported from elsewhere) stays until AniList holds a whole score to replace it — see
 *    [ratingToSend].
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
     * @param score AniList's score out of 100: 0 is unrated, and clears Comick's rating; null
     *   leaves the rating out of the push altogether.
     */
    suspend fun syncFromAnilist(
        isAnime: Boolean,
        anilistId: Int?,
        malId: Int?,
        status: String?,
        progress: Int?,
        exactProgress: String? = null,
        score: Int? = null,
        force: Boolean = false,
    ): Boolean {
        Logger.log(
            "ComickSync: AniList ${if (isAnime) "anime" else "manga"} $anilistId → " +
                "status=$status progress=$progress exact=$exactProgress score=$score"
        )
        if (!isEnabled(force)) return false
        val hids = resolveFromAnilist(isAnime, anilistId ?: return false, malId)
        Logger.log("ComickSync: AniList $anilistId resolved to $hids")
        return pushToSeries(hids, mapAnilistStatus(status), exactProgress ?: progress?.toString(), isAnime, score)
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
     * The new-chapter check found the AniList or MangaUpdates list out of step with a followed
     * title: ahead of its saved chapter ([progress]), or holding it under another status
     * ([status], in AniList's words). A tracker → Comick push like any other, so it follows the
     * sync switch and never lowers progress. [entry] is the library row just fetched, so no lookup
     * is needed.
     */
    suspend fun catchUp(entry: ComickLibraryEntry, progress: Int?, status: String?): Boolean {
        if (!isEnabled()) return false
        return push(
            entry.hid, Comick.EntryLookup.Followed(entry), mapAnilistStatus(status),
            progress?.toString(), isAnime = false
        )
    }

    /**
     * Records that the followed manga [hid] is the AniList entry [anilistId] — as Comick itself
     * links it — so [resolveFromAnilist] finds it without asking MALSync. Added after any entries
     * already known, which keep their place; replaces a remembered miss.
     */
    fun rememberMatch(anilistId: Int, hid: String) {
        val hidsKey = "comick_hids_${ComickApi.MEDIA_TYPE_MANGA}_$anilistId"
        val known = IdCache[hidsKey]?.takeIf { !it.startsWith(MISS_PREFIX) }
            ?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        if (hid in known) return
        IdCache.put(hidsKey, (known + hid).joinToString(","))
    }

    /**
     * The library as read for a bulk sync, by hid, and the media types it covers — null outside
     * one. See [withLibrarySnapshot].
     */
    @Volatile
    private var snapshot: Pair<Set<String>, ConcurrentHashMap<String, ComickLibraryEntry>>? = null

    /**
     * Runs [block] — a bulk sync — with the library for [mediaTypes] read up front: one request per
     * hundred titles, where looking each entry up cost one request apiece, plus one more for every
     * extra Comick entry of the series. Pushes inside keep it current, so a series two diff entries
     * point at isn't compared against what it held before the first of them wrote.
     *
     * Falls back to per-entry lookups when the library can't be read.
     */
    suspend fun <T> withLibrarySnapshot(mediaTypes: Set<String>, block: suspend () -> T): T {
        val library = ConcurrentHashMap<String, ComickLibraryEntry>()
        for (type in mediaTypes) {
            val page = Comick.getLibrary(type, null) ?: run {
                Logger.log("ComickSync: library unavailable for $type, looking entries up one by one")
                return block()
            }
            page.forEach { library[it.hid] = it }
        }
        snapshot = mediaTypes to library
        return try {
            block()
        } finally {
            snapshot = null
        }
    }

    /** The user's entry for [hid]: from the bulk sync's snapshot when it covers the type, else asked. */
    private suspend fun lookup(hid: String, isAnime: Boolean): Comick.EntryLookup? {
        val (types, library) = snapshot ?: return Comick.getEntry(hid)
        val type = if (isAnime) ComickApi.MEDIA_TYPE_ANIME else ComickApi.MEDIA_TYPE_MANGA
        if (type !in types) return Comick.getEntry(hid)
        return library[hid]?.let { Comick.EntryLookup.Followed(it) } ?: Comick.EntryLookup.NotFollowed
    }

    /**
     * Pushes to the Comick entries of one series — [hids], the preferred one first.
     *
     * Comick often lists a series more than once (an official release beside a scanlation), and a
     * user follows whichever they read. So every entry already followed is kept in step, and only
     * when none is does the preferred one get followed: following them all would fill the library
     * with duplicates.
     */
    private suspend fun pushToSeries(
        hids: List<String>,
        status: Int?,
        progress: String?,
        isAnime: Boolean,
        score: Int? = null,
    ): Boolean {
        if (hids.isEmpty()) return false
        val lookups = hids.map { it to lookup(it, isAnime) }
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
            if (!push(hid, lookup ?: continue, status, progress, isAnime, score)) ok = false
        }
        return ok
    }

    /**
     * Follows or updates one entry, applying the rules in the class doc.
     *
     * Manga progress is recorded against Comick's own chapter when it has one by that number —
     * a bare number is stored, but Comick's site shows no progress without a chapter behind it.
     * For the same reason a push at the number already saved still goes out when the saved one
     * has no chapter and this can supply it — unless the push changes the status anyway. Anime episodes keep the plain number.
     */
    private suspend fun push(
        hid: String,
        current: Comick.EntryLookup,
        status: Int?,
        progress: String?,
        isAnime: Boolean,
        score: Int? = null,
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
        // Only what differs — though a new follow always carries its status.
        val sendStatus = if (followed == null) status else status?.takeIf { it != followed.status }
        // Attaching a chapter to a bare number already saved is a repair, worth its lookup (one or
        // two requests) only when nothing else is being sent: a status change at the same chapter
        // — the bulk of a "Sync all" — would otherwise pay it for progress it leaves alone.
        val unlinkedSame = local != null && remoteNumber == local && remote.hid == null && sendStatus == null
        val chapterHid = number?.takeIf { !isAnime && (raises || unlinkedSame) }
            ?.let { ComickApi.findChapterHid(hid, it) }
        val sendProgress = number?.takeIf { raises || (unlinkedSame && chapterHid != null) }
        val sendRating = ratingToSend(score, followed?.rating)
        Logger.log(
            "ComickSync: $hid saved=${remote?.number}/${remote?.hid} local=$number raises=$raises " +
                "unlinkedSame=$unlinkedSame chapter=$chapterHid rating=${followed?.rating}/$score → " +
                "status=$sendStatus progress=$sendProgress rating=${sendRating?.value ?: if (sendRating != null) "clear" else null}"
        )
        if (sendStatus == null && sendProgress == null && sendRating == null) return true

        return Comick.putEntry(
            hid, sendStatus, sendProgress, chapterHid.takeIf { sendProgress != null }, sendRating,
        ).also { ok ->
            if (!ok) Logger.log("ComickSync: push failed for $hid")
            // What was written, so the bulk sync's snapshot doesn't go stale under it.
            else snapshot?.second?.let { library ->
                val before = followed ?: ComickLibraryEntry(hid = hid)
                library[hid] = before.copy(
                    status = sendStatus ?: before.status,
                    progress = sendProgress?.let { ComickLibraryProgress(hid = chapterHid, number = it) }
                        ?: before.progress,
                    rating = if (sendRating != null) sendRating.value?.toDouble() else before.rating,
                )
            }
        }
    }

    /**
     * What an AniList [score] (out of 100; 0 unrated, null not part of the change) makes of the
     * [remote] Comick rating — null when it stays as it is:
     *  - a whole score (10, 20 … 100) becomes its 1–10 rating;
     *  - one without a whole equivalent (75) isn't rounded: the rating is left alone;
     *  - unrated clears the rating — except a fractional one, which Comick can only have imported
     *    from elsewhere and which stays until a whole score replaces it.
     */
    fun ratingToSend(score: Int?, remote: Double?): Comick.RatingWrite? {
        score ?: return null
        if (score <= 0) {
            val wholeRemote = remote != null && remote % 1.0 == 0.0
            return if (wholeRemote) Comick.RatingWrite(null) else null
        }
        val rating = toComickRating(score) ?: return null
        return if (remote == rating.toDouble()) null else Comick.RatingWrite(rating)
    }

    /** [score] (out of 100) as a Comick rating, or null when it has no whole 1–10 equivalent. */
    fun toComickRating(score: Int?): Int? =
        score?.takeIf { it in 10..100 && it % 10 == 0 }?.div(10)

    /** "12.0" → "12"; anything with a real fraction is sent the way the source wrote it. */
    private fun formatNumber(value: Double, original: String): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else original.trim()

    /**
     * Every Comick entry for an AniList entry, the preferred one first — empty when none is known.
     * Found without a title search:
     *  1. HIDs resolved before;
     *  2. the slug the user pinned on the media page, then those its id-validated match found;
     *  3. MALSync's Comick links, kept only when Comick lists this AniList or MAL id itself;
     *  4. given [titles], a title search, validated the same way ([ComickApi.searchAndMatchComic],
     *     the media page's own matching) — for Compare lists, where a title nothing above knows
     *     would otherwise be missing from the comparison altogether.
     */
    suspend fun resolveFromAnilist(
        isAnime: Boolean,
        anilistId: Int,
        malId: Int?,
        titles: List<String> = emptyList(),
    ): List<String> {
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
        val recentMiss = cached?.takeIf { it.startsWith(MISS_PREFIX) }?.removePrefix(MISS_PREFIX)
            ?.toLongOrNull()?.let { System.currentTimeMillis() - it < MISS_TTL_MS } == true
        // The title search keeps its own: a miss recorded before it existed — or by a caller
        // passing no titles — says nothing about whether a search would find it.
        val searchMissKey = "comick_search_miss_${mediaType}_$anilistId"
        val recentSearchMiss = IdCache[searchMissKey]?.toLongOrNull()
            ?.let { System.currentTimeMillis() - it < MISS_TTL_MS } == true
        val searchTitles = titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .takeIf { !recentSearchMiss }.orEmpty()
        if (recentMiss && searchTitles.isEmpty()) return emptyList()

        // No timeout of its own: MALSync is paced to one request every few seconds, so during a
        // list-wide pass most of the wait is the queue, and cutting that short only left the title
        // unanswered — retried every run, with the costlier title search paid for meanwhile. A hung
        // request is still bounded by the HTTP client's own timeouts.
        val quicklinks = if (recentMiss) null else MalSyncApi.getQuicklinks(anilistId, malId, mediaType)
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

        // Last, as the costliest: a search plus a detail fetch per candidate. A match records its
        // slugs under the same key the media page's does, so it's step 2 from then on.
        if (searchTitles.isNotEmpty()) {
            val matched = ComickApi.searchAndMatchComic(searchTitles, anilistId, malId, mediaType = mediaType)
            if (matched != null) {
                val slugs = IdCache[ComickApi.matchedSlugKey(mediaType, anilistId)]?.split(',')
                    ?.filter { it.isNotBlank() }?.ifEmpty { null } ?: listOf(matched)
                val found = slugs.mapNotNull { slug ->
                    ComickApi.getComicDetails(slug, mediaType = mediaType)?.comic?.hid
                }.distinct()
                if (found.isNotEmpty()) {
                    Logger.log("ComickSync: AniList $mediaType $anilistId found by title search: $found")
                    return found.also {
                        IdCache.put(hidsKey, it.joinToString(","))
                        IdCache.flushThrottled(SEARCH_FLUSH_INTERVAL_MS)
                    }
                }
            }
            IdCache.put(searchMissKey, System.currentTimeMillis().toString())
        }
        // Whatever a search found or ruled out is worth keeping past a kill mid-pass: a big list's
        // compare runs for many minutes, and each answer cost several requests.
        if (searchTitles.isNotEmpty()) IdCache.flushThrottled(SEARCH_FLUSH_INTERVAL_MS)

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

    private const val SEARCH_FLUSH_INTERVAL_MS = 30_000L
    private const val MISS_PREFIX = "-"
    private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000
}
