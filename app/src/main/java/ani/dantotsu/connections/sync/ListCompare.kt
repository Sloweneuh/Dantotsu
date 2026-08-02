package ani.dantotsu.connections.sync

import ani.dantotsu.asyncMap
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mal.MALListNode
import ani.dantotsu.connections.mal.MALListStatus
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.connections.mangabaka.MangaBakaSync
import ani.dantotsu.connections.mangabaka.MangaBakaSync.LibraryStateEntry
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.muStandardListStatus
import ani.dantotsu.connections.mangaupdates.muStartDate
import ani.dantotsu.connections.mangaupdates.resolveMuMalId
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * One-way list auditing for the "Compare lists" screen.
 *
 * Sync in Dantotsu is one-directional (AniList / MangaUpdates → destination), so "out of date" means
 * an entry present in the **source** that is missing or differs on the **destination**:
 * - **MAL** is compared against **AniList** (anime + manga, kept as separate subsections), with
 *   **MangaUpdates** contributing to the manga subsection when active (see [muActive]).
 * - **MangaBaka** is compared against **AniList manga + MangaUpdates** (same condition).
 *
 * The two sources aren't supposed to hold the same media: MangaUpdates support is built so an entry
 * lives on one side or the other. Should one ever turn up on both, AniList wins — it carries scores
 * and dates MangaUpdates has no equivalent for — and the MangaUpdates row is dropped rather than
 * offered as a second push at the same destination entry.
 *
 * Everything here is pure logic (no Android/Context). The screen resolves labels and drives sync.
 */
object ListCompare {

    /** Which destination a [DiffEntry] targets. */
    enum class Tracker { MAL, MANGABAKA }

    /** A single field that differs between source and destination. */
    enum class DiffField { STATUS, PROGRESS, VOLUME, SCORE, START_DATE, END_DATE }

    /** `from` is the destination's current value, `to` is the source value we would push. */
    data class FieldDiff(val field: DiffField, val from: String, val to: String)

    /**
     * One field's values on both sides for the expandable per-row detail. A null value renders as
     * "not set"; [differs] marks the row to highlight (and show `dest → source`) on the dest side.
     */
    data class DetailRow(val field: DiffField, val source: String?, val dest: String?, val differs: Boolean)

    /**
     * One out-of-date media, with the payload needed to reconcile it. When [delete] is true the entry
     * exists on the destination but not in the source, and [sync] removes it; otherwise [sync] pushes
     * the source values.
     */
    data class DiffEntry(
        val title: String,
        val coverUrl: String?,
        val isAnime: Boolean,
        val tracker: Tracker,
        val diffs: List<FieldDiff>,
        val anilistId: Int?,
        val malId: Int?,
        val muSeriesId: Long?,
        val muListId: Int?,
        val mangaBakaSeriesId: Long?,
        val status: String?,   // AniList-style status ("CURRENT", "PLANNING", ...)
        val progress: Int?,
        val volume: Int?,
        val score: Int?,       // AniList POINT_100 (0..100)
        val startDate: FuzzyDate? = null,
        val endDate: FuzzyDate? = null,
        val detail: List<DetailRow> = emptyList(),
        // Canonical dest status before/after a successful sync, used to update the header stats in
        // place (see [applied]). `from` is null when the media isn't on the dest yet (an addition);
        // `to` is null when the entry will be removed (a deletion).
        val fromStatusCanon: String? = null,
        val toStatusCanon: String? = null,
        val delete: Boolean = false,
    )

    /** Totals for one side of a comparison, keyed by canonical status. */
    data class SideStats(val total: Int, val perStatus: Map<String, Int>)

    /** The result of comparing one homogeneous list (e.g. MAL anime, or MangaBaka manga). */
    data class SubsectionResult(
        val source: SideStats,
        val dest: SideStats,
        val diffs: List<DiffEntry>,
    )

    /** One comparison the screen can show, in display order. */
    enum class Section { MAL_ANIME, MAL_MANGA, MANGABAKA }

    /** Both sides' totals for one section, published ahead of its (much slower) diff pass. */
    data class SectionStats(val source: SideStats, val dest: SideStats)

    /** Canonical status display order (AniList vocabulary). */
    val STATUS_ORDER = listOf("CURRENT", "PLANNING", "COMPLETED", "PAUSED", "DROPPED", "REPEATING")

    /** Whether MangaUpdates contributes to the manga comparisons. */
    fun muActive(): Boolean =
        MangaUpdates.token != null && PrefManager.getVal(PrefName.MangaUpdatesListEnabled)

    /**
     * Which sections the current logins allow. AniList is the source of truth, so nothing is
     * comparable without it. Cheap (token checks only): the screen builds its section cards from
     * this before any network work starts.
     */
    fun availableSections(): List<Section> {
        if (Anilist.userid == null) return emptyList()
        return buildList {
            if (MAL.token != null) {
                add(Section.MAL_ANIME)
                add(Section.MAL_MANGA)
            }
            if (MangaBaka.token != null) add(Section.MANGABAKA)
        }
    }

    /**
     * Runs every available comparison, reporting each section as it gets there rather than holding
     * everything back until the slowest one finishes: [onStats] fires once that section's lists are
     * in (with its final totals), [onSection] when its per-entry pass has produced the diffs.
     * Sections are independent — a slow tracker never blocks another one from filling in.
     *
     * The source lists are still fetched once each and shared: both manga sections read the same
     * AniList manga list and MangaUpdates snapshot, the two heaviest fetches on the screen. They're
     * handed over as [Result]s so a failed fetch surfaces on the sections that need it ([onError])
     * instead of cancelling the whole screen.
     *
     * Callbacks run on whatever dispatcher this was called on; the caller marshals to the UI.
     */
    suspend fun compareStreaming(
        onStats: suspend (Section, SectionStats) -> Unit,
        onSection: suspend (Section, SubsectionResult) -> Unit,
        onError: suspend (Section, Throwable) -> Unit,
    ): Unit = coroutineScope {
        val userId = Anilist.userid ?: return@coroutineScope
        val onMal = MAL.token != null
        val onMangaBaka = MangaBaka.token != null
        val muActive = muActive()

        val anilistAnime = async { runCatching { if (onMal) anilistList(true, userId) else emptyList() } }
        val anilistManga =
            async { runCatching { if (onMal || onMangaBaka) anilistList(false, userId) else emptyList() } }
        val muMedia = async {
            runCatching {
                if (muActive && (onMal || onMangaBaka)) MangaUpdates.getAllUserLists().values.flatten()
                else emptyList()
            }
        }

        /** Runs one section, keeping its failure to itself. */
        fun section(id: Section, block: suspend () -> SubsectionResult) = launch {
            try {
                onSection(id, block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(id, e)
            }
        }

        if (onMal) {
            section(Section.MAL_ANIME) {
                compareMal(true, anilistAnime, null) { onStats(Section.MAL_ANIME, it) }
            }
            section(Section.MAL_MANGA) {
                compareMal(false, anilistManga, muMedia) { onStats(Section.MAL_MANGA, it) }
            }
        }
        if (onMangaBaka) section(Section.MANGABAKA) {
            compareMangaBaka(anilistManga, muMedia) { onStats(Section.MANGABAKA, it) }
        }
    }

    /** The user's whole AniList list for one media type. */
    private suspend fun anilistList(isAnime: Boolean, userId: Int): List<Media> =
        Anilist.query.getMediaLists(isAnime, userId)["All"] ?: arrayListOf()

    // ---- MAL vs AniList (+ MangaUpdates) ----

    /** [muList] is null for anime and whenever MangaUpdates isn't contributing. */
    private suspend fun compareMal(
        isAnime: Boolean,
        sourceList: Deferred<Result<List<Media>>>,
        muList: Deferred<Result<List<MUMedia>>>?,
        onStats: suspend (SectionStats) -> Unit,
    ): SubsectionResult = coroutineScope {
        // The destination list is fetched alongside the source rather than after it, so the header
        // stats can be published as soon as the lists are in — everything below is per-entry work.
        val malListAsync = async { MAL.query.getUserList(isAnime) }
        val source = sourceList.await().getOrThrow()
        val malList = malListAsync.await()
        val muMedia = muList?.await()?.getOrThrow().orEmpty()
        val malById: Map<Int, MALListNode> = malList.associateBy { it.node.id }

        val destStats = statsOf(malList.map { malToCanon(it.listStatus?.status, it.listStatus.rereading(isAnime)) })
        // Final totals, not a first approximation: the sources aren't supposed to overlap, so every
        // entry on either side counts and no per-entry resolution is needed to tell them apart. (An
        // overlap that slipped through would be counted on both sides here, while the diff pass
        // below keeps only the AniList row for it.)
        val sourceStats = statsOf(
            source.map { it.userStatus ?: "CURRENT" } + muMedia.map { muListToCanon(it.listId) }
        )
        onStats(SectionStats(sourceStats, destStats))

        val diffs = source.mapNotNull { media ->
            val malId = media.idMAL ?: return@mapNotNull null
            buildMalDiff(media, isAnime, malById[malId])
        }

        // MangaUpdates forward diffs. Each entry needs a MAL id, which MangaUpdates doesn't carry;
        // MangaBaka's mapping supplies it (already cached for anything opened in the app). Comick's
        // title-search fallback is off here — one search plus a details call per candidate, per
        // entry, is far too much for a whole-list pass.
        val anilistMalIds = source.mapNotNull { it.idMAL }.toHashSet()
        val muResolved = muMedia
            .asyncMap { mu -> mu to resolveMuMalId(mu.id, listOfNotNull(mu.title), comickFallback = false) }
            // Defensive: the sources aren't supposed to overlap, but if AniList does already cover
            // this MAL entry it wins, rather than both offering to push to it.
            .filter { (_, malId) -> malId == null || malId !in anilistMalIds }
            // Two MangaUpdates entries can map to one MAL entry; unresolved ones stay distinct.
            .distinctBy { (mu, malId) -> malId?.toLong() ?: -mu.id }
        val muDiffs = muResolved.mapNotNull { (mu, malId) ->
            malId?.let { buildMuMalDiff(mu, it, malById[it]) }
        }

        // Deletions: on MAL but in neither source (matched by MAL id) → offer to remove from MAL.
        val sourceMalIds = anilistMalIds + muResolved.mapNotNull { it.second }
        val deletions = malList.mapNotNull { node ->
            if (node.node.id in sourceMalIds) null else buildMalDeleteDiff(node, isAnime)
        }

        SubsectionResult(sourceStats, destStats, diffs + muDiffs + deletions)
    }

    private fun buildMalDeleteDiff(node: MALListNode, isAnime: Boolean): DiffEntry = DiffEntry(
        title = node.node.title,
        coverUrl = node.node.mainPicture?.large ?: node.node.mainPicture?.medium,
        isAnime = isAnime,
        tracker = Tracker.MAL,
        diffs = emptyList(),
        anilistId = null,
        malId = node.node.id,
        muSeriesId = null,
        muListId = null,
        mangaBakaSeriesId = null,
        status = null,
        progress = null,
        volume = null,
        score = null,
        fromStatusCanon = malToCanon(node.listStatus?.status, node.listStatus.rereading(isAnime)),
        toStatusCanon = null,
        delete = true,
    )

    private fun buildMalDiff(media: Media, isAnime: Boolean, node: MALListNode?): DiffEntry? {
        val ls = node?.listStatus
        val expectedStatus = MAL.query.convertStatus(isAnime, media.userStatus ?: "CURRENT")
        val completed = expectedStatus == "completed"
        val rawProgress = media.userProgress ?: 0
        // AniList's own progress is the truth we mirror. The one exception: a *completed* entry
        // recorded with 0 progress is a data glitch (you can't finish something at 0), so fall back to
        // the media total there. A completed entry with a real count (e.g. 32/39) is kept exactly as
        // AniList has it — forcing it to the total would invent a diff and desync MAL.
        val aniTotal = (if (isAnime) media.anime?.totalEpisodes else media.manga?.totalChapters)
            ?.takeIf { it > 0 }
        val repairCompletedZero = completed && rawProgress == 0 && aniTotal != null
        // MAL refuses a user's progress beyond the title's own total — e.g. a manga MAL lists as
        // finished at 6 official chapters while AniList counts 66 unofficial ones, or a movie later
        // split into streaming episodes. Clamp what we expect and push to MAL's total (0 = unknown, so
        // not clamped) so the count converges instead of showing an unfixable diff forever.
        val malTotal = (if (isAnime) node?.node?.numEpisodes else node?.node?.numChapters)?.takeIf { it > 0 }
        val malVolTotal = if (!isAnime) node?.node?.numVolumes?.takeIf { it > 0 } else null
        var expectedProgress = if (repairCompletedZero) aniTotal!! else rawProgress
        if (malTotal != null) expectedProgress = expectedProgress.coerceAtMost(malTotal)
        var expectedVolume = media.userVolume ?: 0
        if (malVolTotal != null) expectedVolume = expectedVolume.coerceAtMost(malVolTotal)
        val expectedScore = media.userScore / 10          // MAL uses a 0..10 score
        val actualStatus = ls?.status ?: ""
        val actualProgress = ls?.let { if (isAnime) it.numEpisodesWatched else it.numChaptersRead } ?: 0
        val actualVolume = ls?.numVolumesRead ?: 0
        val fieldDiffs = mutableListOf<FieldDiff>()

        if (ls == null) {
            fieldDiffs += FieldDiff(DiffField.STATUS, DASH, formatStatus(expectedStatus) ?: DASH)
            if (expectedProgress > 0)
                fieldDiffs += FieldDiff(DiffField.PROGRESS, DASH, expectedProgress.toString())
        } else {
            if (actualStatus != expectedStatus)
                fieldDiffs += FieldDiff(DiffField.STATUS, formatStatus(actualStatus) ?: DASH, formatStatus(expectedStatus) ?: DASH)
            if (actualProgress != expectedProgress)
                fieldDiffs += FieldDiff(DiffField.PROGRESS, actualProgress.toString(), expectedProgress.toString())
            if (media.userScore > 0 && ls.score != expectedScore)
                fieldDiffs += FieldDiff(DiffField.SCORE, formatScore(ls.score * 10) ?: DASH, formatScore(media.userScore) ?: DASH)
            if (!isAnime && expectedVolume > 0 && actualVolume != expectedVolume)
                fieldDiffs += FieldDiff(DiffField.VOLUME, actualVolume.toString(), expectedVolume.toString())
        }
        dateDiff(DiffField.START_DATE, media.userStartedAt, ls?.startDate)?.let { fieldDiffs += it }
        dateDiff(DiffField.END_DATE, media.userCompletedAt, ls?.finishDate)?.let { fieldDiffs += it }
        if (fieldDiffs.isEmpty()) return null
        val detail = buildDetail(
            isAnime, fieldDiffs.mapTo(HashSet()) { it.field }, onDest = ls != null,
            status = expectedStatus to actualStatus,
            progress = expectedProgress to actualProgress,
            volume = if (!isAnime) expectedVolume to actualVolume else null,
            score = media.userScore to ls?.score?.let { it * 10 },   // both on the 0..100 scale
            start = media.userStartedAt to parseDestDate(ls?.startDate),
            end = media.userCompletedAt to parseDestDate(ls?.finishDate),
        )
        return DiffEntry(
            title = media.userPreferredName,
            coverUrl = media.cover,
            isAnime = isAnime,
            tracker = Tracker.MAL,
            diffs = fieldDiffs,
            anilistId = media.id,
            malId = media.idMAL,
            muSeriesId = null,
            muListId = null,
            mangaBakaSeriesId = null,
            status = media.userStatus ?: "CURRENT",
            // Push the clamped/repaired value when a cap or the completed-zero repair applies;
            // otherwise mirror AniList's own count as-is.
            progress = if (malTotal != null || repairCompletedZero) expectedProgress else media.userProgress,
            // Never push a 0 volume. A VOLUME diff is only ever raised for a non-zero source count
            // (see above), so a 0 here means AniList simply doesn't track volumes for this entry —
            // pushing it would silently wipe a count MAL has without the diff list ever showing it.
            volume = expectedVolume.takeIf { it > 0 },
            score = media.userScore.takeIf { it > 0 },
            startDate = media.userStartedAt.takeIf { !it.isEmpty() },
            endDate = media.userCompletedAt.takeIf { !it.isEmpty() },
            detail = detail,
            fromStatusCanon = ls?.let { malToCanon(it.status, it.rereading(isAnime)) },
            toStatusCanon = media.userStatus ?: "CURRENT",
        )
    }

    /**
     * A MangaUpdates entry against its MAL counterpart. MangaUpdates tracks status, chapter, volume
     * and the date the series was added — which is the start date, under the rules in [muStartDate].
     * Score and finish date have no equivalent and are never diffed; they'd read as "clear what MAL
     * has", which one-way sync must not do.
     */
    private fun buildMuMalDiff(mu: MUMedia, malId: Int, node: MALListNode?): DiffEntry? {
        val canonStatus = muStandardListStatus(mu.listId) ?: return null
        val ls = node?.listStatus
        val expectedStatus = MAL.query.convertStatus(false, canonStatus)
        // Same clamping and completed-at-zero repair as the AniList side ([buildMalDiff]), except the
        // total can only come from MAL itself — MangaUpdates has no notion of a final chapter count.
        val malTotal = node?.node?.numChapters?.takeIf { it > 0 }
        val malVolTotal = node?.node?.numVolumes?.takeIf { it > 0 }
        val rawProgress = mu.userChapter ?: 0
        val repairCompletedZero = expectedStatus == "completed" && rawProgress == 0 && malTotal != null
        var expectedProgress = if (repairCompletedZero) malTotal!! else rawProgress
        if (malTotal != null) expectedProgress = expectedProgress.coerceAtMost(malTotal)
        var expectedVolume = mu.userVolume ?: 0
        if (malVolTotal != null) expectedVolume = expectedVolume.coerceAtMost(malVolTotal)
        val actualProgress = ls?.numChaptersRead ?: 0
        val actualVolume = ls?.numVolumesRead ?: 0

        val fieldDiffs = mutableListOf<FieldDiff>()
        if (ls == null) {
            fieldDiffs += FieldDiff(DiffField.STATUS, DASH, formatStatus(expectedStatus) ?: DASH)
            if (expectedProgress > 0)
                fieldDiffs += FieldDiff(DiffField.PROGRESS, DASH, expectedProgress.toString())
        } else {
            if (ls.status != expectedStatus)
                fieldDiffs += FieldDiff(DiffField.STATUS, formatStatus(ls.status) ?: DASH, formatStatus(expectedStatus) ?: DASH)
            if (actualProgress != expectedProgress)
                fieldDiffs += FieldDiff(DiffField.PROGRESS, actualProgress.toString(), expectedProgress.toString())
            if (expectedVolume > 0 && actualVolume != expectedVolume)
                fieldDiffs += FieldDiff(DiffField.VOLUME, actualVolume.toString(), expectedVolume.toString())
        }
        val expectedStart = muStartDate(mu.listId, mu.addedAt)
        expectedStart?.let { dateDiff(DiffField.START_DATE, it, ls?.startDate) }
            ?.let { fieldDiffs += it }
        if (fieldDiffs.isEmpty()) return null
        val detail = buildDetail(
            isAnime = false, fieldDiffs.mapTo(HashSet()) { it.field }, onDest = ls != null,
            status = expectedStatus to ls?.status,
            progress = expectedProgress to actualProgress,
            volume = expectedVolume to actualVolume,
            score = (null as Int?) to ls?.score?.let { it * 10 },   // MangaUpdates has no score
            start = expectedStart to parseDestDate(ls?.startDate),
            end = (null as FuzzyDate?) to parseDestDate(ls?.finishDate),
        )
        return DiffEntry(
            // The MangaUpdates list API has no covers; MAL's own picture fills in when it has the entry.
            title = mu.title ?: "",
            coverUrl = mu.coverUrl ?: node?.node?.mainPicture?.large ?: node?.node?.mainPicture?.medium,
            isAnime = false,
            tracker = Tracker.MAL,
            diffs = fieldDiffs,
            anilistId = null,
            malId = malId,
            muSeriesId = mu.id,
            muListId = mu.listId,
            mangaBakaSeriesId = null,
            status = canonStatus,
            progress = expectedProgress,
            volume = expectedVolume.takeIf { it > 0 },   // same reasoning as the AniList side
            score = null,
            startDate = expectedStart,
            detail = detail,
            fromStatusCanon = ls?.let { malToCanon(it.status, it.rereading(false)) },
            toStatusCanon = muListToCanon(mu.listId),
        )
    }

    // ---- MangaBaka vs AniList (+ MangaUpdates) ----

    private class Processed(val seriesId: Long?, val diff: DiffEntry?)

    /** [muList] is null whenever MangaUpdates isn't contributing. */
    private suspend fun compareMangaBaka(
        sourceList: Deferred<Result<List<Media>>>,
        muList: Deferred<Result<List<MUMedia>>>?,
        onStats: suspend (SectionStats) -> Unit,
    ): SubsectionResult = coroutineScope {
        // Fetched alongside the source list so the header stats land before the per-entry pass.
        val snapshotAsync = async { MangaBakaSync.getLibrarySnapshot() }
        val anilistManga = sourceList.await().getOrThrow()
        val snapshot = snapshotAsync.await()
        val muMedia = muList?.await()?.getOrThrow().orEmpty()
        // Destination totals come from per-state counts (exact even when a state can't be fully
        // enumerated). Several MangaBaka states fold into one canonical status, so aggregate.
        val destPerStatus = LinkedHashMap<String, Int>()
        var destTotal = 0
        for ((state, count) in snapshot.counts) {
            val canon = mbToCanon(state)
            destPerStatus[canon] = (destPerStatus[canon] ?: 0) + count
            destTotal += count
        }
        val destStats = SideStats(destTotal, destPerStatus)
        // Final totals, not a first approximation: the sources aren't supposed to overlap, so every
        // entry on either side counts and no per-entry resolution is needed to tell them apart. (An
        // overlap that slipped through would be counted on both sides here, while the diff pass
        // below keeps only the AniList row for it.)
        val sourceStats = statsOf(
            anilistManga.map { it.userStatus ?: "CURRENT" } + muMedia.map { muListToCanon(it.listId) }
        )
        onStats(SectionStats(sourceStats, destStats))

        // Prefer matching against the enumerated library (one pass gives each entry's state, cover and,
        // via the embedded series, its cross-source ids). Fall back to per-series lookups only if the
        // list endpoint didn't return series ids.
        val libBySeriesId = snapshot.entries.mapNotNull { e -> e.resolvedSeriesId()?.let { it to e } }.toMap()
        val canEnumerate = libBySeriesId.isNotEmpty()
        suspend fun currentOf(seriesId: Long): LibraryStateEntry? =
            if (canEnumerate) libBySeriesId[seriesId] else MangaBakaSync.getLibraryEntry(seriesId)

        // Reverse index over the enumerated library, keyed by the cross-source ids embedded in each
        // entry's series. Media already in the library resolve to their MangaBaka series id from this
        // map with zero network calls; only media missing from the library fall through to the
        // per-item `/v1/source` route (which the server rate-limits). This is what keeps large lists
        // from tripping HTTP 429 on a cold cache.
        val byAnilist = HashMap<Int, Long>()
        val byMal = HashMap<Int, Long>()
        val byMu = HashMap<Long, Long>()
        for (entry in snapshot.entries) {
            val sid = entry.resolvedSeriesId() ?: continue
            val src = entry.series?.source ?: continue
            src.anilist?.id?.let { byAnilist[it] = sid }
            src.myAnimeList?.id?.let { byMal[it] = sid }
            src.mangaUpdates?.toMuSeriesId()?.let { byMu[it] = sid }
        }

        // AniList manga forward diffs.
        val alProcessed = anilistManga.asyncMap { media ->
            val seriesId = byAnilist[media.id]
                ?: media.idMAL?.let { byMal[it] }
                ?: MangaBakaApi.resolveFromAnilist(media.id, media.idMAL)
            val diff = seriesId?.let { buildMangaBakaDiff(media, it, currentOf(it)) }
            Processed(seriesId, diff)
        }

        // MangaUpdates forward diffs.
        val alSeriesIds = alProcessed.mapNotNull { it.seriesId }.toHashSet()
        val muProcessed = muMedia
            .asyncMap { mu ->
                mu to (byMu[mu.id] ?: MangaBakaApi.resolveSeriesId(MangaBakaApi.Source.MANGAUPDATES, mu.id))
            }
            // Defensive: the sources aren't supposed to overlap, but if AniList already reached this
            // MangaBaka series it wins, rather than both offering to push to it.
            .filter { (_, seriesId) -> seriesId == null || seriesId !in alSeriesIds }
            // Two MangaUpdates entries can map to one MangaBaka series; unresolved ones stay distinct.
            .distinctBy { (mu, seriesId) -> seriesId ?: -mu.id }
            .asyncMap { (mu, seriesId) ->
                // These rows carry no cover — the MangaUpdates list API has none. The adapter fills
                // that in from MangaBaka when a row is actually shown, so nothing is fetched here.
                val diff = seriesId?.let { buildMuMangaBakaDiff(mu, it, currentOf(it)) }
                Processed(seriesId, diff)
            }

        // Deletions: library entries not represented in the source (only when we could enumerate).
        // Match on the library entry's own declared source ids (most reliable) and, as a fallback, on
        // series ids we resolved from the source — so a single failed resolve never falsely deletes.
        val sourceAnilistIds = anilistManga.map { it.id }.toHashSet()
        val sourceMalIds = anilistManga.mapNotNull { it.idMAL }.toHashSet()
        val sourceMuIds = muMedia.map { it.id }.toHashSet()
        val sourceSeriesIds =
            (alProcessed.mapNotNull { it.seriesId } + muProcessed.mapNotNull { it.seriesId }).toHashSet()
        val deletions = if (canEnumerate) {
            snapshot.entries.mapNotNull { entry ->
                val sid = entry.resolvedSeriesId() ?: return@mapNotNull null
                val src = entry.series?.source
                val inSource = sid in sourceSeriesIds ||
                    (src?.anilist?.id?.let { it in sourceAnilistIds } == true) ||
                    (src?.myAnimeList?.id?.let { it in sourceMalIds } == true) ||
                    (src?.mangaUpdates?.toMuSeriesId()?.let { it in sourceMuIds } == true)
                if (inSource) null else buildMangaBakaDeleteDiff(entry, sid)
            }
        } else emptyList()

        val diffs = alProcessed.mapNotNull { it.diff } + muProcessed.mapNotNull { it.diff } + deletions
        SubsectionResult(sourceStats, destStats, diffs)
    }

    private fun buildMangaBakaDiff(media: Media, seriesId: Long, current: LibraryStateEntry?): DiffEntry? {
        val expectedState = MangaBakaSync.mapAnilistStatus(media.userStatus) ?: return null
        val fieldDiffs = stateDiffs(
            current, expectedState,
            expectedChapter = media.userProgress ?: 0,
            expectedVolume = media.userVolume ?: 0,
        ).toMutableList()
        // MangaBaka ratings use the same 0..100 scale as AniList, so compare directly.
        val expectedScore = media.userScore
        if (expectedScore > 0 && (current?.rating ?: 0) != expectedScore)
            fieldDiffs += FieldDiff(DiffField.SCORE, formatScore(current?.rating) ?: DASH, formatScore(expectedScore) ?: DASH)
        dateDiff(DiffField.START_DATE, media.userStartedAt, current?.startDate)?.let { fieldDiffs += it }
        dateDiff(DiffField.END_DATE, media.userCompletedAt, current?.finishDate)?.let { fieldDiffs += it }
        if (fieldDiffs.isEmpty()) return null
        val detail = buildDetail(
            isAnime = false, fieldDiffs.mapTo(HashSet()) { it.field }, onDest = current != null,
            status = expectedState to current?.state,
            progress = (media.userProgress ?: 0) to (current?.progressChapter ?: 0),
            volume = (media.userVolume ?: 0) to (current?.progressVolume ?: 0),
            score = expectedScore to current?.rating,
            start = media.userStartedAt to parseDestDate(current?.startDate),
            end = media.userCompletedAt to parseDestDate(current?.finishDate),
        )
        return DiffEntry(
            title = media.userPreferredName,
            coverUrl = media.cover ?: current?.coverUrl(),
            isAnime = false,
            tracker = Tracker.MANGABAKA,
            diffs = fieldDiffs,
            anilistId = media.id,
            malId = media.idMAL,
            muSeriesId = null,
            muListId = null,
            mangaBakaSeriesId = seriesId,
            status = media.userStatus,
            progress = media.userProgress,
            volume = media.userVolume?.takeIf { it > 0 },   // same reasoning as the MAL side
            score = media.userScore.takeIf { it > 0 },
            startDate = media.userStartedAt.takeIf { !it.isEmpty() },
            endDate = media.userCompletedAt.takeIf { !it.isEmpty() },
            detail = detail,
            fromStatusCanon = current?.let { mbToCanon(it.state) },
            toStatusCanon = media.userStatus,
        )
    }

    private fun buildMuMangaBakaDiff(mu: MUMedia, seriesId: Long, current: LibraryStateEntry?): DiffEntry? {
        val expectedState = MangaBakaSync.mapMangaUpdatesList(mu.listId) ?: return null
        val fieldDiffs = stateDiffs(
            current, expectedState,
            expectedChapter = mu.userChapter ?: 0,
            expectedVolume = mu.userVolume ?: 0,
        ).toMutableList()
        val expectedStart = muStartDate(mu.listId, mu.addedAt)
        expectedStart?.let { dateDiff(DiffField.START_DATE, it, current?.startDate) }
            ?.let { fieldDiffs += it }
        if (fieldDiffs.isEmpty()) return null
        val detail = buildDetail(
            isAnime = false, fieldDiffs.mapTo(HashSet()) { it.field }, onDest = current != null,
            status = expectedState to current?.state,
            progress = (mu.userChapter ?: 0) to (current?.progressChapter ?: 0),
            volume = (mu.userVolume ?: 0) to (current?.progressVolume ?: 0),
            score = (null as Int?) to current?.rating,           // MangaUpdates has no score
            start = expectedStart to parseDestDate(current?.startDate),
            end = (null as FuzzyDate?) to parseDestDate(current?.finishDate),
        )
        return DiffEntry(
            title = mu.title ?: "",
            coverUrl = mu.coverUrl ?: current?.coverUrl(),
            isAnime = false,
            tracker = Tracker.MANGABAKA,
            diffs = fieldDiffs,
            anilistId = null,
            malId = null,
            muSeriesId = mu.id,
            muListId = mu.listId,
            mangaBakaSeriesId = seriesId,
            status = null,
            progress = mu.userChapter,
            volume = mu.userVolume?.takeIf { it > 0 },      // same reasoning as the MAL side
            score = null,
            startDate = expectedStart,
            detail = detail,
            fromStatusCanon = current?.let { mbToCanon(it.state) },
            toStatusCanon = muListToCanon(mu.listId),
        )
    }

    private fun buildMangaBakaDeleteDiff(entry: LibraryStateEntry, seriesId: Long): DiffEntry = DiffEntry(
        // The library list doesn't embed series info; a blank title tells the adapter to fetch it lazily.
        title = entry.title() ?: "",
        coverUrl = entry.coverUrl(),
        isAnime = false,
        tracker = Tracker.MANGABAKA,
        diffs = emptyList(),
        anilistId = null,
        malId = null,
        muSeriesId = null,
        muListId = null,
        mangaBakaSeriesId = seriesId,
        status = null,
        progress = null,
        volume = null,
        score = null,
        fromStatusCanon = mbToCanon(entry.state),
        toStatusCanon = null,
        delete = true,
    )

    private fun stateDiffs(
        current: LibraryStateEntry?,
        expectedState: String,
        expectedChapter: Int,
        expectedVolume: Int,
    ): List<FieldDiff> {
        val fieldDiffs = mutableListOf<FieldDiff>()
        if (current == null) {
            fieldDiffs += FieldDiff(DiffField.STATUS, DASH, formatStatus(expectedState) ?: DASH)
            if (expectedChapter > 0)
                fieldDiffs += FieldDiff(DiffField.PROGRESS, DASH, expectedChapter.toString())
        } else {
            if (current.state != expectedState)
                fieldDiffs += FieldDiff(DiffField.STATUS, formatStatus(current.state) ?: DASH, formatStatus(expectedState) ?: DASH)
            if ((current.progressChapter ?: 0) != expectedChapter)
                fieldDiffs += FieldDiff(DiffField.PROGRESS, (current.progressChapter ?: 0).toString(), expectedChapter.toString())
            if (expectedVolume > 0 && (current.progressVolume ?: 0) != expectedVolume)
                fieldDiffs += FieldDiff(DiffField.VOLUME, (current.progressVolume ?: 0).toString(), expectedVolume.toString())
        }
        return fieldDiffs
    }

    /** Title + cover for a MangaBaka series, used to flesh out deletion rows lazily (throttled). */
    suspend fun mangaBakaSeriesInfo(seriesId: Long): Pair<String?, String?>? =
        MangaBakaApi.getSeries(seriesId)?.let { it.title to it.cover?.thumbUrl() }

    /**
     * Title + cover for a MangaUpdates series, borrowed from MangaBaka: the MangaUpdates list API
     * returns no covers, so rows sourced from it have none of their own. Used to fill rows in lazily.
     */
    suspend fun mangaUpdatesSeriesInfo(muSeriesId: Long): Pair<String?, String?>? =
        MangaBakaApi.getSeriesFromSource(MangaBakaApi.Source.MANGAUPDATES, muSeriesId)
            ?.let { it.title to it.cover?.thumbUrl() }

    // ---- Sync (explicit user action → force past the on/off toggle) ----

    /**
     * How many destination writes to keep in flight during a bulk sync. Modest on purpose: one entry
     * can already be several requests (MangaBaka PATCHes, then POSTs when the entry is new) and MAL
     * has no rate limiter of its own, so the gap between this and "all at once" isn't worth risking a
     * 429. MangaBaka requests throttle themselves further inside [MangaBakaApi.execute].
     */
    private const val SYNC_CONCURRENCY = 4

    /**
     * Reconciles many entries, a few at a time, returning each with its result in the order given.
     *
     * Neither MyAnimeList nor MangaBaka exposes a bulk list-write route — MAL edits are a PUT per
     * `/manga/{id}/my_list_status` and MangaBaka a PATCH/POST per `/my/library/{series_id}` — so every
     * entry is its own round trip and overlapping them is the only thing that makes "sync all" quick.
     */
    suspend fun syncAll(entries: List<DiffEntry>): List<Pair<DiffEntry, Boolean>> = coroutineScope {
        val limiter = Semaphore(SYNC_CONCURRENCY)
        entries
            .map { entry -> async(Dispatchers.IO) { entry to limiter.withPermit { sync(entry) } } }
            .awaitAll()
    }

    /** Reconciles a single diff entry with its destination (push, or remove when [DiffEntry.delete]). */
    suspend fun sync(entry: DiffEntry): Boolean {
        if (entry.delete) return when (entry.tracker) {
            Tracker.MAL -> {
                MAL.query.deleteList(entry.isAnime, entry.malId, force = true)
                true
            }
            Tracker.MANGABAKA -> MangaBakaSync.deleteById(entry.mangaBakaSeriesId, force = true)
        }
        return when (entry.tracker) {
            Tracker.MAL -> {
                MAL.query.editList(
                    entry.malId, entry.isAnime, entry.progress, entry.score,
                    entry.status ?: "CURRENT", volume = entry.volume,
                    start = entry.startDate, end = entry.endDate, force = true,
                )
                true
            }
            // No status on the destination means the entry isn't in the library, so create it
            // outright instead of paying for a PATCH that can only 404 first.
            Tracker.MANGABAKA -> if (entry.anilistId != null || entry.malId != null) {
                MangaBakaSync.syncFromAnilist(
                    anilistId = entry.anilistId, malId = entry.malId, status = entry.status,
                    progressChapter = entry.progress, progressVolume = entry.volume,
                    score = entry.score, rereads = null, isPrivate = null,
                    startDate = entry.startDate, finishDate = entry.endDate,
                    preferCreate = entry.fromStatusCanon == null, force = true,
                )
            } else {
                MangaBakaSync.syncFromMangaUpdates(
                    muSeriesId = entry.muSeriesId, muListId = entry.muListId,
                    progressChapter = entry.progress, progressVolume = entry.volume,
                    startDate = entry.startDate,
                    preferCreate = entry.fromStatusCanon == null, force = true,
                )
            }
        }
    }

    // ---- helpers ----

    private const val DASH = "—"

    private fun FuzzyDate.isComplete(): Boolean = year != null && month != null && day != null

    /** Parses a destination date (`YYYY-MM-DD` from MAL or an ISO date-time from MangaBaka). */
    private fun parseDestDate(s: String?): FuzzyDate? {
        val d = s?.take(10)?.takeIf { it.isNotBlank() } ?: return null
        val p = d.split("-")
        val y = p.getOrNull(0)?.toIntOrNull() ?: return null
        return FuzzyDate(y, p.getOrNull(1)?.toIntOrNull(), p.getOrNull(2)?.toIntOrNull())
    }

    /** App-standard date display (e.g. "13 April 2026"), or null when unset. */
    private fun FuzzyDate?.display(): String? = this?.takeIf { !it.isEmpty() }?.toStringOrEmpty()

    /**
     * A start/end date diff, emitted only when the AniList (source) date is complete (year+month+day)
     * and differs from the destination's date. We never clear a date the source doesn't have, so an
     * empty source date is ignored. Values are shown in the app's standard date format.
     */
    private fun dateDiff(field: DiffField, source: FuzzyDate, dest: String?): FieldDiff? {
        if (!source.isComplete()) return null
        val destDate = parseDestDate(dest)
        if (source.toMALString() == (destDate?.toMALString() ?: "")) return null
        return FieldDiff(field, destDate.display() ?: DASH, source.toStringOrEmpty())
    }

    /**
     * Formats a POINT_100 score (0..100) in the viewer's AniList scoring system, so scores read the
     * same as everywhere else in the app. Returns null when unset (0). Destination scores must be
     * normalised to 0..100 before being passed here (MAL's 0..10 ×10; MangaBaka is already 0..100).
     */
    /** Readable status label: underscores to spaces, first letter capitalised (e.g. "plan_to_read"
     *  → "Plan to read", "completed" → "Completed"). Null/blank stays null. */
    private fun formatStatus(s: String?): String? =
        s?.takeIf { it.isNotBlank() }?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }

    private fun formatScore(score100: Int?): String? {
        val s = score100?.takeIf { it > 0 } ?: return null
        return when (Anilist.scoreFormat) {
            "POINT_100" -> s.toString()
            "POINT_10" -> ((s + 5) / 10).coerceIn(1, 10).toString()
            "POINT_5" -> ((s + 10) / 20).coerceIn(1, 5).toString() + "★"
            "POINT_3" -> if (s <= 35) "🙁" else if (s <= 60) "😐" else "🙂"
            else -> "${s / 10}.${s % 10}"   // POINT_10_DECIMAL and app default
        }
    }

    /**
     * Builds the ordered both-side field values shown when a diff row is expanded. Values are given
     * in the destination's own vocabulary (e.g. MAL status words); scores are pre-normalised to the
     * 0..100 scale and rendered in the viewer's scoring system. [source] of each pair is the value we
     * would push, [dest] the current value. A 0 score / empty date shows as "not set" (null); when
     * [onDest] is false the media isn't on the destination yet, so every dest value is "not set".
     */
    private fun buildDetail(
        isAnime: Boolean,
        diffs: Set<DiffField>,
        onDest: Boolean,
        status: Pair<String?, String?>,
        progress: Pair<Int, Int>,
        volume: Pair<Int, Int>?,
        score: Pair<Int?, Int?>,
        start: Pair<FuzzyDate?, FuzzyDate?>,
        end: Pair<FuzzyDate?, FuzzyDate?>,
    ): List<DetailRow> {
        fun dst(v: String?) = if (onDest) v else null
        return buildList {
            add(DetailRow(DiffField.STATUS, formatStatus(status.first), dst(formatStatus(status.second)), DiffField.STATUS in diffs))
            add(DetailRow(DiffField.PROGRESS, progress.first.toString(), dst(progress.second.toString()), DiffField.PROGRESS in diffs))
            if (!isAnime && volume != null)
                add(DetailRow(DiffField.VOLUME, volume.first.takeIf { it > 0 }?.toString(), dst(volume.second.takeIf { it > 0 }?.toString()), DiffField.VOLUME in diffs))
            add(DetailRow(DiffField.SCORE, formatScore(score.first), dst(formatScore(score.second)), DiffField.SCORE in diffs))
            add(DetailRow(DiffField.START_DATE, start.first.display(), dst(start.second.display()), DiffField.START_DATE in diffs))
            add(DetailRow(DiffField.END_DATE, end.first.display(), dst(end.second.display()), DiffField.END_DATE in diffs))
        }
    }

    private fun statsOf(statuses: List<String>): SideStats =
        SideStats(statuses.size, statuses.groupingBy { it }.eachCount())

    /**
     * Returns [stats] updated for one successfully-synced [entry], so the destination totals can be
     * refreshed in place without re-running the whole comparison. Moves the entry between status
     * buckets for a status change, adds it (from `null`) for a new entry, and removes it (to `null`)
     * for a deletion; a progress/score-only change leaves the buckets untouched.
     */
    fun applied(stats: SideStats, entry: DiffEntry): SideStats {
        val perStatus = LinkedHashMap(stats.perStatus)
        var total = stats.total
        entry.fromStatusCanon?.let { s ->
            perStatus[s] = (perStatus[s] ?: 1) - 1
            if (entry.toStatusCanon == null) total--   // removed from the destination
        }
        entry.toStatusCanon?.let { s ->
            perStatus[s] = (perStatus[s] ?: 0) + 1
            if (entry.fromStatusCanon == null) total++ // added to the destination
        }
        return SideStats(total.coerceAtLeast(0), perStatus.filterValues { it > 0 })
    }

    private fun MALListStatus?.rereading(isAnime: Boolean): Boolean =
        if (isAnime) this?.isRewatching == true else this?.isRereading == true

    private fun malToCanon(status: String?, rereading: Boolean): String = when {
        rereading -> "REPEATING"
        status == "watching" || status == "reading" -> "CURRENT"
        status == "plan_to_watch" || status == "plan_to_read" -> "PLANNING"
        status == "completed" -> "COMPLETED"
        status == "on_hold" -> "PAUSED"
        status == "dropped" -> "DROPPED"
        else -> "CURRENT"
    }

    private fun mbToCanon(state: String?): String = when (state) {
        "reading" -> "CURRENT"
        "plan_to_read", "considering" -> "PLANNING"
        "completed" -> "COMPLETED"
        "paused" -> "PAUSED"
        "dropped" -> "DROPPED"
        "rereading" -> "REPEATING"
        else -> "CURRENT"
    }

    private fun muListToCanon(listId: Int): String = when (listId) {
        0 -> "CURRENT"
        1 -> "PLANNING"
        2 -> "COMPLETED"
        3 -> "DROPPED"
        4 -> "PAUSED"
        else -> "CURRENT"
    }
}
