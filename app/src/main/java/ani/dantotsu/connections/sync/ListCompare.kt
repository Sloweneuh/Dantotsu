package ani.dantotsu.connections.sync

import ani.dantotsu.asyncMap
import ani.dantotsu.connections.anilist.Anilist
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
    enum class DiffField { STATUS, PROGRESS, VOLUME, SCORE }

    /** `from` is the destination's current value, `to` is the source value we would push. */
    data class FieldDiff(val field: DiffField, val from: String, val to: String)

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
            buildMalDiff(media, isAnime, malById[malId]?.listStatus)
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
        delete = true,
    )

    private fun buildMalDiff(media: Media, isAnime: Boolean, ls: MALListStatus?): DiffEntry? {
        val expectedStatus = MAL.query.convertStatus(isAnime, media.userStatus ?: "CURRENT")
        val expectedProgress = media.userProgress ?: 0
        val expectedScore = media.userScore / 10          // MAL uses a 0..10 score
        val expectedVolume = media.userVolume ?: 0
        val fieldDiffs = mutableListOf<FieldDiff>()

        if (ls == null) {
            fieldDiffs += FieldDiff(DiffField.STATUS, DASH, expectedStatus)
            if (expectedProgress > 0)
                fieldDiffs += FieldDiff(DiffField.PROGRESS, DASH, expectedProgress.toString())
        } else {
            val actualStatus = ls.status ?: ""
            if (actualStatus != expectedStatus)
                fieldDiffs += FieldDiff(DiffField.STATUS, actualStatus.ifEmpty { DASH }, expectedStatus)
            val actualProgress = (if (isAnime) ls.numEpisodesWatched else ls.numChaptersRead) ?: 0
            if (actualProgress != expectedProgress)
                fieldDiffs += FieldDiff(DiffField.PROGRESS, actualProgress.toString(), expectedProgress.toString())
            if (media.userScore > 0 && ls.score != expectedScore)
                fieldDiffs += FieldDiff(DiffField.SCORE, ls.score.toString(), expectedScore.toString())
            if (!isAnime && expectedVolume > 0 && (ls.numVolumesRead ?: 0) != expectedVolume)
                fieldDiffs += FieldDiff(DiffField.VOLUME, (ls.numVolumesRead ?: 0).toString(), expectedVolume.toString())
        }
        if (fieldDiffs.isEmpty()) return null
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
            progress = media.userProgress,
            volume = media.userVolume,
            score = media.userScore.takeIf { it > 0 },
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

        // Prefer matching against the enumerated library (one pass gives each entry's state + cover).
        // Fall back to per-series lookups if the list endpoint didn't return series ids.
        val libBySeriesId = snapshot.entries.mapNotNull { e -> e.resolvedSeriesId()?.let { it to e } }.toMap()
        val canEnumerate = libBySeriesId.isNotEmpty()
        suspend fun currentOf(seriesId: Long): LibraryStateEntry? =
            if (canEnumerate) libBySeriesId[seriesId] else MangaBakaSync.getLibraryEntry(seriesId)

        // AniList manga forward diffs.
        val alProcessed = anilistManga.asyncMap { media ->
            val seriesId = MangaBakaApi.resolveFromAnilist(media.id, media.idMAL)
            val diff = seriesId?.let { buildMangaBakaDiff(media, it, currentOf(it)) }
            Processed(media.userStatus ?: "CURRENT", seriesId, diff)
        }

        // MangaUpdates-only forward diffs (not already represented on AniList by MangaBaka series id).
        val alSeriesIds = alProcessed.mapNotNull { it.seriesId }.toHashSet()
        val muMedia = if (muActive) MangaUpdates.getAllUserLists().values.flatten() else emptyList()
        val muProcessed = muMedia
            .asyncMap { mu -> mu to MangaBakaApi.resolveSeriesId(MangaBakaApi.Source.MANGAUPDATES, mu.id) }
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
        )
        if (fieldDiffs.isEmpty()) return null
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
            fieldDiffs += FieldDiff(DiffField.STATUS, DASH, expectedState)
            if (expectedChapter > 0)
                fieldDiffs += FieldDiff(DiffField.PROGRESS, DASH, expectedChapter.toString())
        } else {
            if (current.state != expectedState)
                fieldDiffs += FieldDiff(DiffField.STATUS, current.state ?: DASH, expectedState)
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
                    entry.status ?: "CURRENT", volume = entry.volume, force = true,
                )
                true
            }
            Tracker.MANGABAKA -> if (entry.anilistId != null || entry.malId != null) {
                MangaBakaSync.syncFromAnilist(
                    anilistId = entry.anilistId, malId = entry.malId, status = entry.status,
                    progressChapter = entry.progress, progressVolume = entry.volume,
                    score = entry.score, rereads = null, isPrivate = null,
                    startDate = null, finishDate = null, force = true,
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

    private fun empty() = SubsectionResult(SideStats(0, emptyMap()), SideStats(0, emptyMap()), emptyList())

    private fun statsOf(statuses: List<String>): SideStats =
        SideStats(statuses.size, statuses.groupingBy { it }.eachCount())

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
