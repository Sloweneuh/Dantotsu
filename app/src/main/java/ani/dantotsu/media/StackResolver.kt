package ani.dantotsu.media

import android.content.Context
import android.content.Intent
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mal.MALStackEntry
import ani.dantotsu.connections.malsync.LanguageMapper
import ani.dantotsu.connections.malsync.MalSyncApi
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.connections.malsync.UnreleasedEpisodeInfo
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.toMedia
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
 * Shared resolution for MAL interest stacks: turns a stack's scraped MAL entries into displayable
 * media for [MediaListViewActivity].
 *
 * AniList matches are resolved in a single batch. Manga entries with no AniList equivalent fall back
 * to their MangaUpdates counterpart, resolved through MangaBaka's cross-source mapping, so the stack
 * no longer silently drops titles that don't exist on AniList.
 *
 * MALSync progress/source data is fetched for both halves. The MangaUpdates fallbacks can be looked
 * up like any other entry here, and without a resolution step: a stack is a list of MAL entries, so
 * the MAL id MALSync is keyed on is the very thing they were matched by.
 *
 * Resolving a stack is many round trips (a scrape, an AniList batch, then a MangaBaka + MangaUpdates
 * pair per unmatched manga), so callers [open] the list screen straight away and it does the work
 * behind its own spinner — see [MediaListViewActivity].
 */
object StackResolver {

    /** Everything the list screen needs to show a resolved stack. */
    data class Resolved(
        val media: List<Media>,
        val unread: Map<Int, UnreadChapterInfo>?,
        val unreleased: Map<Int, UnreleasedEpisodeInfo>?,
    )

    /**
     * Opens the list screen for a MAL stack. Callers showing a stack list already have [title] and
     * [description] from the row and pass them straight through; only a caller with no [title] at
     * all (a stack link inside another stack's description) makes the screen scrape them.
     */
    fun open(
        context: Context,
        stackUrl: String,
        isAnime: Boolean,
        title: String?,
        description: String?,
    ) {
        // The screen treats a non-null passedMedia as "already resolved" (that's how it survives a
        // rotation), so clear what a still-live stack screen left behind — opening a stack from a
        // link inside another stack's description would otherwise re-show the first one's list.
        MediaListViewActivity.passedMedia = null
        MediaListViewActivity.passedMuMedia = null
        MediaListViewActivity.passedUnreadInfo = null
        MediaListViewActivity.passedUnreleasedInfo = null
        MediaListViewActivity.passedDescription = description
        context.startActivity(
            Intent(context, MediaListViewActivity::class.java)
                .putExtra("stackUrl", stackUrl)
                .putExtra("title", title)
                .putExtra("isAnime", isAnime)
        )
    }

    /** Resolves a stack's scraped entries into media, in the stack's own order. */
    suspend fun resolve(
        entries: List<MALStackEntry>,
        isAnime: Boolean,
    ): Resolved {
        val malIds = entries.map { it.id }
        if (malIds.isEmpty()) return Resolved(emptyList(), null, null)

        // AniList matches (one batch call, keyed by MAL id).
        val anilistMedia = withContext(Dispatchers.IO) {
            try {
                Anilist.query.getMediaBatch(malIds, mal = true, mediaType = if (isAnime) "ANIME" else "MANGA")
            } catch (e: Exception) {
                emptyList()
            }
        }
        val anilistByMal = anilistMedia.associateBy { it.idMAL }
        anilistMedia.forEach { m -> entries.find { it.id == m.idMAL }?.let { m.malIntro = it.intro } }

        // MangaUpdates fallback (manga only): for entries AniList doesn't have, resolve the
        // MangaUpdates equivalent via MangaBaka and build a MU-backed Media (opens the MU screen).
        val muByMal: Map<Int, Media> = if (isAnime) emptyMap() else withContext(Dispatchers.IO) {
            val unmatched = entries.filter { it.id !in anilistByMal.keys }
            // Same rate limit MUDetailsCache uses, so a large stack doesn't hammer the APIs.
            val semaphore = Semaphore(5)
            coroutineScope {
                unmatched.map { entry ->
                    async {
                        semaphore.withPermit {
                            try {
                                val series = MangaBakaApi.getSeriesForMedia(null, null, entry.id)
                                val muId = series?.source?.mangaUpdates?.toMuSeriesId()
                                if (series == null || muId == null) return@withPermit null
                                // MangaUpdates' own latest_chapter is the newest released chapter —
                                // the same value the MU unread-chapter tracking uses. Fall back to
                                // MangaBaka's total_chapters, which doubles as the latest while a
                                // series is ongoing. The total itself stays "~" either way.
                                val muDetails = try {
                                    MangaUpdates.getSeriesDetails(muId)
                                } catch (e: Exception) {
                                    null
                                }
                                val latest = muDetails?.latest_chapter?.toInt()
                                    ?: series.totalChapters
                                        ?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()
                                // MangaUpdates' status is free text like "132 Chapters (Ongoing)";
                                // pull the parenthetical and map it onto the AniList status tokens
                                // MediaAdaptor's ongoing/hiatus dot indicator already recognizes.
                                val status = muDetails?.status?.let { full ->
                                    val paren = Regex("""\(([^)]+)\)""").find(full)
                                        ?.groupValues?.getOrNull(1) ?: full
                                    when {
                                        paren.contains("ongoing", ignoreCase = true) -> "RELEASING"
                                        paren.contains("hiatus", ignoreCase = true) -> "HIATUS"
                                        paren.contains("complete", ignoreCase = true) -> "FINISHED"
                                        else -> null
                                    }
                                }
                                val media = MUMedia(
                                    id = muId,
                                    title = series.title ?: series.romanizedTitle,
                                    url = "https://www.mangaupdates.com/series/${muId.toString(36)}",
                                    coverUrl = series.cover?.thumbUrl(),
                                    listId = -1,
                                    userChapter = null,
                                    userVolume = null,
                                    latestChapter = latest,
                                    bayesianRating = null,
                                    priority = null,
                                    format = series.type,
                                    status = status,
                                ).toMedia()
                                media.malIntro = entry.intro
                                entry.id to media
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull().toMap()
            }
        }

        // Preserve the stack's ordering; prefer the AniList match, else the MangaUpdates fallback.
        val ordered = entries.mapNotNull { anilistByMal[it.id] ?: muByMal[it.id] }
        if (ordered.isEmpty()) return Resolved(emptyList(), null, null)

        var unread: Map<Int, UnreadChapterInfo>? = null
        var unreleased: Map<Int, UnreleasedEpisodeInfo>? = null

        // MangaUpdates fallbacks need no id resolution here: the stack entry they were built from
        // *is* a MAL entry, so its id is the one MALSync wants. They're keyed by their media id —
        // [muMediaKey], the same key the fallback Media carries — and never by an AniList id.
        val muMalIds: List<Pair<Media, Int>> = if (isAnime) emptyList()
        else entries.mapNotNull { entry -> muByMal[entry.id]?.let { it to entry.id } }
        val malSyncMedia = anilistMedia + muMalIds.map { it.first }

        // MALSync progress/source data.
        if (PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled) && malSyncMedia.isNotEmpty()) {
            val mediaIds = anilistMedia.map { Pair(it.id, it.idMAL) } +
                    muMalIds.map { (media, malId) -> Pair(media.id, malId) }
            if (isAnime) {
                val batchResults = withContext(Dispatchers.IO) {
                    try { MalSyncApi.getBatchAnimeEpisodes(mediaIds, respectExcludeList = false) } catch (e: Exception) { emptyMap() }
                }
                val infoMap = mutableMapOf<Int, UnreleasedEpisodeInfo>()
                for (m in anilistMedia) {
                    val result = batchResults[m.id] ?: continue
                    val lastEp = result.lastEp ?: continue
                    val langOption = LanguageMapper.mapLanguage(result.id)
                    infoMap[m.id] = UnreleasedEpisodeInfo(
                        mediaId = m.id,
                        lastEpisode = lastEp.total,
                        languageId = result.id,
                        languageDisplay = langOption.displayName,
                        userProgress = m.userProgress ?: 0
                    )
                }
                if (infoMap.isNotEmpty()) unreleased = infoMap
            } else {
                val batchResults = withContext(Dispatchers.IO) {
                    try { MalSyncApi.getBatchProgressByMedia(mediaIds, respectExcludeList = false) } catch (e: Exception) { emptyMap() }
                }
                val infoMap = mutableMapOf<Int, UnreadChapterInfo>()
                for (m in malSyncMedia) {
                    val result = batchResults[m.id] ?: continue
                    val lastEp = result.lastEp ?: continue
                    infoMap[m.id] = UnreadChapterInfo(
                        mediaId = m.id,
                        lastChapter = lastEp.total,
                        source = result.source,
                        userProgress = m.userProgress ?: 0,
                        latestChapterAt = lastEp.timestampMillis()
                    )
                }
                if (infoMap.isNotEmpty()) unread = infoMap
            }
        }

        return Resolved(ordered, unread, unreleased)
    }
}
