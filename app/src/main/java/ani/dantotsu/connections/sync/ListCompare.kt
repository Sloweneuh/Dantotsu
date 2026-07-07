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
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * One-way list auditing for the "Compare lists" screen.
 *
 * Sync in Dantotsu is one-directional (AniList / MangaUpdates → destination), so "out of date" means
 * an entry present in the **source** that is missing or differs on the **destination**:
 * - **MAL** is compared against **AniList** (anime + manga, kept as separate subsections).
 * - **MangaBaka** is compared against **AniList manga + MangaUpdates** (MU only when actively
 *   contributing — see [muActive]).
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

    /** Full screen state. A null subsection means that tracker isn't logged in. */
    data class CompareResult(
        val malAnime: SubsectionResult?,
        val malManga: SubsectionResult?,
        val mangaBaka: SubsectionResult?,
        val muActive: Boolean,
    )

    /** Canonical status display order (AniList vocabulary). */
    val STATUS_ORDER = listOf("CURRENT", "PLANNING", "COMPLETED", "PAUSED", "DROPPED", "REPEATING")

    /**
     * Runs all comparisons the current logins allow. Requires AniList (the source of truth); returns
     * an all-null result when AniList isn't available.
     */
    suspend fun compareAll(): CompareResult {
        val muActive = MangaUpdates.token != null &&
            PrefManager.getVal(PrefName.MangaUpdatesListEnabled)
        if (Anilist.userid == null) return CompareResult(null, null, null, muActive)

        val malAnime = if (MAL.token != null) compareMal(true) else null
        val malManga = if (MAL.token != null) compareMal(false) else null
        val mangaBaka = if (MangaBaka.token != null) compareMangaBaka(muActive) else null
        return CompareResult(malAnime, malManga, mangaBaka, muActive)
    }

    // ---- MAL vs AniList ----

    private suspend fun compareMal(isAnime: Boolean): SubsectionResult {
        val userId = Anilist.userid ?: return empty()
        val source = Anilist.query.getMediaLists(isAnime, userId)["All"] ?: arrayListOf()
        val malList = MAL.query.getUserList(isAnime)
        val malById: Map<Int, MALListNode> = malList.associateBy { it.node.id }

        val sourceStats = statsOf(source.map { it.userStatus ?: "CURRENT" })
        val destStats = statsOf(malList.map { malToCanon(it.listStatus?.status, it.listStatus.rereading(isAnime)) })

        val diffs = source.mapNotNull { media ->
            val malId = media.idMAL ?: return@mapNotNull null
            buildMalDiff(media, isAnime, malById[malId])
        }

        // Deletions: on MAL but not on AniList (matched by MAL id) → offer to remove from MAL.
        val sourceMalIds = source.mapNotNull { it.idMAL }.toHashSet()
        val deletions = malList.mapNotNull { node ->
            if (node.node.id in sourceMalIds) null else buildMalDeleteDiff(node, isAnime)
        }
        return SubsectionResult(sourceStats, destStats, diffs + deletions)
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
            volume = if (malVolTotal != null) expectedVolume else media.userVolume,
            score = media.userScore.takeIf { it > 0 },
            startDate = media.userStartedAt.takeIf { !it.isEmpty() },
            endDate = media.userCompletedAt.takeIf { !it.isEmpty() },
            detail = detail,
            fromStatusCanon = ls?.let { malToCanon(it.status, it.rereading(isAnime)) },
            toStatusCanon = media.userStatus ?: "CURRENT",
        )
    }

    // ---- MangaBaka vs AniList (+ MangaUpdates) ----

    private class Processed(val status: String, val seriesId: Long?, val diff: DiffEntry?)

    private suspend fun compareMangaBaka(muActive: Boolean): SubsectionResult {
        val userId = Anilist.userid ?: return empty()
        val anilistManga = Anilist.query.getMediaLists(false, userId)["All"] ?: arrayListOf()

        val snapshot = MangaBakaSync.getLibrarySnapshot()
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
            Processed(media.userStatus ?: "CURRENT", seriesId, diff)
        }

        // MangaUpdates-only forward diffs (not already represented on AniList by MangaBaka series id).
        val alSeriesIds = alProcessed.mapNotNull { it.seriesId }.toHashSet()
        val muMedia = if (muActive) MangaUpdates.getAllUserLists().values.flatten() else emptyList()
        val muProcessed = muMedia
            .asyncMap { mu ->
                mu to (byMu[mu.id] ?: MangaBakaApi.resolveSeriesId(MangaBakaApi.Source.MANGAUPDATES, mu.id))
            }
            .filter { (_, seriesId) -> seriesId == null || seriesId !in alSeriesIds }
            .distinctBy { (mu, seriesId) -> seriesId ?: -mu.id }
            .asyncMap { (mu, seriesId) ->
                val diff = seriesId?.let { sid ->
                    val base = buildMuMangaBakaDiff(mu, sid, currentOf(sid)) ?: return@let null
                    // The MangaUpdates list API has no covers; borrow one from the MangaBaka series.
                    if (base.coverUrl == null)
                        base.copy(coverUrl = MangaBakaApi.getSeries(sid)?.cover?.thumbUrl())
                    else base
                }
                Processed(muListToCanon(mu.listId), seriesId, diff)
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

        val sourceStats = statsOf(alProcessed.map { it.status } + muProcessed.map { it.status })
        val diffs = alProcessed.mapNotNull { it.diff } + muProcessed.mapNotNull { it.diff } + deletions
        return SubsectionResult(sourceStats, destStats, diffs)
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
            volume = media.userVolume,
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
        )
        if (fieldDiffs.isEmpty()) return null
        val detail = buildDetail(
            isAnime = false, fieldDiffs.mapTo(HashSet()) { it.field }, onDest = current != null,
            status = expectedState to current?.state,
            progress = (mu.userChapter ?: 0) to (current?.progressChapter ?: 0),
            volume = (mu.userVolume ?: 0) to (current?.progressVolume ?: 0),
            score = (null as Int?) to current?.rating,           // MangaUpdates has no score
            start = (null as FuzzyDate?) to parseDestDate(current?.startDate),
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
            volume = mu.userVolume,
            score = null,
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

    // ---- Sync (explicit user action → force past the on/off toggle) ----

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
            Tracker.MANGABAKA -> if (entry.anilistId != null || entry.malId != null) {
                MangaBakaSync.syncFromAnilist(
                    anilistId = entry.anilistId, malId = entry.malId, status = entry.status,
                    progressChapter = entry.progress, progressVolume = entry.volume,
                    score = entry.score, rereads = null, isPrivate = null,
                    startDate = entry.startDate, finishDate = entry.endDate, force = true,
                )
            } else {
                MangaBakaSync.syncFromMangaUpdates(
                    muSeriesId = entry.muSeriesId, muListId = entry.muListId,
                    progressChapter = entry.progress, progressVolume = entry.volume, force = true,
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

    private fun empty() = SubsectionResult(SideStats(0, emptyMap()), SideStats(0, emptyMap()), emptyList())

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
