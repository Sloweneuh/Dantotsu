package ani.dantotsu.media

import android.content.Context
import android.content.Intent
import android.widget.Toast
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
 * media and opens [MediaListViewActivity].
 *
 * AniList matches are resolved in a single batch. Manga entries with no AniList equivalent fall back
 * to their MangaUpdates counterpart, resolved through MangaBaka's cross-source mapping, so the stack
 * no longer silently drops titles that don't exist on AniList.
 *
 * MALSync progress/source data is fetched and displayed for AniList media only — MangaUpdates
 * fallback entries are never sent to MALSync.
 */
object StackResolver {

    suspend fun resolveAndOpen(
        context: Context,
        entries: List<MALStackEntry>,
        isAnime: Boolean,
        title: String,
        description: String?,
    ) {
        val malIds = entries.map { it.id }
        if (malIds.isEmpty()) {
            Toast.makeText(context, "No entries found", Toast.LENGTH_SHORT).show()
            return
        }

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
        if (ordered.isEmpty()) {
            Toast.makeText(context, "No matches found", Toast.LENGTH_SHORT).show()
            return
        }

        // MALSync progress/source data — AniList media only.
        if (PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled) && anilistMedia.isNotEmpty()) {
            val mediaIds = anilistMedia.map { Pair(it.id, it.idMAL) }
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
                if (infoMap.isNotEmpty()) MediaListViewActivity.passedUnreleasedInfo = infoMap
            } else {
                val batchResults = withContext(Dispatchers.IO) {
                    try { MalSyncApi.getBatchProgressByMedia(mediaIds, respectExcludeList = false) } catch (e: Exception) { emptyMap() }
                }
                val infoMap = mutableMapOf<Int, UnreadChapterInfo>()
                for (m in anilistMedia) {
                    val result = batchResults[m.id] ?: continue
                    val lastEp = result.lastEp ?: continue
                    infoMap[m.id] = UnreadChapterInfo(
                        mediaId = m.id,
                        lastChapter = lastEp.total,
                        source = result.source,
                        userProgress = m.userProgress ?: 0
                    )
                }
                if (infoMap.isNotEmpty()) MediaListViewActivity.passedUnreadInfo = infoMap
            }
        }

        MediaListViewActivity.passedMedia = ArrayList(ordered)
        MediaListViewActivity.passedDescription = description
        context.startActivity(
            Intent(context, MediaListViewActivity::class.java)
                .putExtra("title", title)
                .putExtra("isAnime", isAnime)
        )
    }
}
