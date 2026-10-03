package ani.dantotsu.connections.sync

import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.connections.comick.ComickSync
import ani.dantotsu.connections.kitsu.KitsuSync
import ani.dantotsu.connections.simkl.SimklSync
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Fans a single AniList / MangaUpdates list change out to the newer one-way trackers (Kitsu, Simkl),
 * next to the existing MAL + MangaBaka mirror calls at each edit site.
 *
 * Kept separate from those so the new destinations are wired in one place instead of being repeated
 * across every list-editor. Every push is best-effort and gated by each tracker's own
 * `isEnabled()` — a logged-out or switched-off tracker is a cheap no-op.
 *
 * Anime changes go to Kitsu + Simkl; manga changes go to Kitsu only (Simkl has no manga). Both go
 * to Comick too, which takes status and progress only and never mirrors a removal — see
 * [ComickSync].
 */
object ListSyncMirror {

    suspend fun pushFromAnilist(
        isAnime: Boolean,
        anilistId: Int?,
        malId: Int?,
        status: String?,
        progress: Int?,
        score: Int? = null,
        startDate: FuzzyDate? = null,
        finishDate: FuzzyDate? = null,
        /** The chapter/episode as the reader had it ("12.5"); only Comick can store decimals. */
        exactProgress: String? = null,
    ) = coroutineScope {
        launch {
            KitsuSync.syncFromAnilist(
                isAnime = isAnime, anilistId = anilistId, malId = malId, status = status,
                progress = progress, score = score, startDate = startDate, finishDate = finishDate,
            )
        }
        if (isAnime) launch {
            SimklSync.syncFromAnilist(
                anilistId = anilistId, malId = malId, status = status,
                progress = progress, score = score,
            )
        }
        launch {
            ComickSync.syncFromAnilist(
                isAnime = isAnime, anilistId = anilistId, malId = malId,
                status = status, progress = progress, exactProgress = exactProgress,
            )
        }
    }

    suspend fun deleteFromAnilist(isAnime: Boolean, anilistId: Int?, malId: Int?) = coroutineScope {
        launch { KitsuSync.deleteFromAnilist(isAnime, anilistId, malId) }
        if (isAnime) launch { SimklSync.deleteFromAnilist(anilistId, malId) }
    }

    suspend fun pushMangaFromMangaUpdates(
        muSeriesId: Long?,
        muListId: Int?,
        progress: Int?,
        startDate: FuzzyDate? = null,
        /** See [pushFromAnilist]. */
        exactProgress: String? = null,
    ) = coroutineScope {
        launch {
            KitsuSync.syncFromMangaUpdates(
                muSeriesId = muSeriesId, muListId = muListId, progress = progress, startDate = startDate,
            )
        }
        launch { ComickSync.syncFromMangaUpdates(muSeriesId, muListId, progress, exactProgress) }
    }

    suspend fun deleteMangaFromMangaUpdates(muSeriesId: Long?) {
        KitsuSync.deleteFromMangaUpdates(muSeriesId)
    }
}
