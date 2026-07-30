package ani.dantotsu.connections.mangaupdates

import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.settings.saving.PrefManager

private const val PREF_MU_MAL_ID_PREFIX = "mu_mal_id_"

/**
 * Resolves the MyAnimeList id for a MangaUpdates series, so the MAL info tab has an entry to load.
 *
 * MangaUpdates exposes no MAL id of its own, so it has to come from a cross-source mapping — the
 * same two sources, in the same order, that the AniList-equivalent detection in
 * [MUMediaDetailsActivity] uses:
 *  1. **MangaBaka** — one lookup keyed by the series' MangaUpdates slug. Authoritative when present,
 *     and usually already cached by the AniList detection, which shares that lookup.
 *  2. **Comick** — fallback for series MangaBaka doesn't link to MAL: a title search matched strictly
 *     on `links.mu`, then `links.mal` off the matched comic. Pass [comickSlug] when the screen has
 *     already matched one, to skip the search.
 *
 * A resolved id is cached in prefs; a miss isn't, since it can simply mean the fallback had no slug
 * to work with yet.
 */
suspend fun resolveMuMalId(
    muSeriesId: Long,
    titles: List<String>,
    comickSlug: String? = null,
): Int? {
    val cacheKey = "$PREF_MU_MAL_ID_PREFIX$muSeriesId"
    PrefManager.getCustomVal(cacheKey, 0).takeIf { it > 0 }?.let { return it }

    val malId = MangaBakaApi.getCrossIdsFromMangaUpdates(muSeriesId).malId
        ?: comickMalId(muSeriesId, titles, comickSlug)
    if (malId != null) PrefManager.setCustomVal(cacheKey, malId)
    return malId
}

/** MAL id off the Comick entry for a MangaUpdates series, matched on `links.mu`. */
private suspend fun comickMalId(muSeriesId: Long, titles: List<String>, comickSlug: String?): Int? {
    val slug = comickSlug?.takeIf { it.isNotBlank() }
        ?: ComickApi.searchAndMatchComicByMuId(titles, muSeriesId)
        ?: return null
    return ComickApi.getComicDetails(slug)?.comic?.links?.mal?.trim()?.toIntOrNull()?.takeIf { it > 0 }
}
