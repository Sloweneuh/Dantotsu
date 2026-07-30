package ani.dantotsu.connections.mangaupdates

import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

private const val PREF_MU_MAL_ID_PREFIX = "mu_mal_id_"

/** Series this session failed to link, so repeated progress updates don't re-run the search. */
private val unlinkable = HashSet<Long>()

/**
 * Resolves the MyAnimeList id for a MangaUpdates series, so the MAL info tab has an entry to load
 * and [syncMuToMal] has something to write to.
 *
 * MangaUpdates exposes no MAL id of its own, so it has to come from a cross-source mapping — the
 * same two sources, in the same order, that the AniList-equivalent detection in
 * [MUMediaDetailsActivity] uses:
 *  1. **MangaBaka** — one lookup keyed by the series' MangaUpdates slug. Authoritative when present,
 *     and usually already cached by the AniList detection, which shares that lookup.
 *  2. **Comick** — fallback for series MangaBaka doesn't link to MAL: a title search matched strictly
 *     on `links.mu`, then `links.mal` off the matched comic. Pass [comickSlug] when the screen has
 *     already matched one, to skip the search. Set [comickFallback] false where that search is too
 *     expensive to run per entry (it costs a search plus a details call for each candidate) — the
 *     list-compare screen walks whole lists and stays on the MangaBaka mapping for that reason.
 *
 * A resolved id is cached in prefs. A miss is only remembered for the session, since it can mean
 * nothing worse than "the fallback had no slug to work with yet".
 */
suspend fun resolveMuMalId(
    muSeriesId: Long,
    titles: List<String>,
    comickSlug: String? = null,
    comickFallback: Boolean = true,
): Int? {
    val cacheKey = "$PREF_MU_MAL_ID_PREFIX$muSeriesId"
    PrefManager.getCustomVal(cacheKey, 0).takeIf { it > 0 }?.let { return it }
    if (muSeriesId in unlinkable) return null

    val malId = MangaBakaApi.getCrossIdsFromMangaUpdates(muSeriesId).malId
        ?: comickMalId(muSeriesId, titles, comickSlug, comickFallback)
    if (malId != null) PrefManager.setCustomVal(cacheKey, malId)
    // Only a complete attempt settles a series as unlinkable. A [comickFallback]-less pass never
    // tried Comick, so recording its miss would stop the info tab and progress updates from doing
    // the search they're willing to pay for.
    else if (comickFallback) unlinkable.add(muSeriesId)
    return malId
}

/** MAL id off the Comick entry for a MangaUpdates series, matched on `links.mu`. */
private suspend fun comickMalId(
    muSeriesId: Long,
    titles: List<String>,
    comickSlug: String?,
    comickFallback: Boolean,
): Int? {
    val slug = comickSlug?.takeIf { it.isNotBlank() }
        ?: (if (comickFallback) ComickApi.searchAndMatchComicByMuId(titles, muSeriesId) else null)
        ?: return null
    return ComickApi.getComicDetails(slug)?.comic?.links?.mal?.trim()?.toIntOrNull()?.takeIf { it > 0 }
}

/**
 * Mirrors a MangaUpdates list entry to MyAnimeList, the counterpart of
 * [ani.dantotsu.connections.mangabaka.MangaBakaSync.syncFromMangaUpdates]. Returns false when the
 * series can't be linked to a MAL entry, which is the common case for series MAL simply doesn't
 * carry — nothing is pushed then.
 *
 * MangaUpdates tracks status, chapter, volume and [startDate] (the date the series was added, as
 * derived by [muStartDate]) — score, rereads and the finish date have no equivalent and are left to
 * whatever MAL already holds. Statuses go through [muStandardListStatus] because [MAL.query.editList]
 * speaks AniList's vocabulary — and custom lists, having no MAL equivalent, are skipped.
 *
 * [force] bypasses the list-sync toggle for explicit user actions (the list-compare screen).
 */
suspend fun syncMuToMal(
    muSeriesId: Long,
    muListId: Int,
    titles: List<String>,
    chapter: Int?,
    volume: Int?,
    startDate: FuzzyDate? = null,
    force: Boolean = false,
): Boolean {
    if (MAL.token == null) return false
    // editList applies this toggle itself; checking here too keeps an unwanted sync from paying for
    // the id lookup that precedes it.
    if (!force && !PrefManager.getVal<Boolean>(PrefName.MalListSyncEnabled)) return false
    val status = muStandardListStatus(muListId) ?: return false
    val malId = resolveMuMalId(muSeriesId, titles) ?: return false
    MAL.query.editList(
        idMAL = malId,
        isAnime = false,
        progress = chapter,
        score = null,
        status = status,
        volume = volume,
        start = startDate,
        force = force,
    )
    return true
}
