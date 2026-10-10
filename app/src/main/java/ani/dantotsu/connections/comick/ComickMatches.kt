package ani.dantotsu.connections.comick

import ani.dantotsu.connections.IdCache
import ani.dantotsu.settings.saving.PrefManager

/**
 * The AniList media and MangaUpdates series Comick entries have been matched to in the app, read
 * backwards from where those matches are kept: by AniList id or MangaUpdates key, not by Comick
 * entry. For a Comick entry whose catalog links leave one out, this is what it stands for.
 *
 * Three sources, in the order they win:
 *  1. the entry picked by hand on a media page's Comick tab (`comick_slug_<id>`), the user's word;
 *  2. the HIDs [ComickSync.resolveFromAnilist] settled on for an AniList id;
 *  3. the slugs the media page's own matching validated ([ComickApi.matchedSlugKey]).
 *
 * The automatic ones are only taken when they point one way: a Comick entry matched to two AniList
 * ids says nothing about which it is.
 */
object ComickMatches {

    /**
     * What a Comick entry was matched to. [muKey] alone is a pick made on a MangaUpdates page
     * before [muPickKey] was recorded: only the truncated key is known, which a MangaUpdates list
     * entry can still be recognised by ([ani.dantotsu.connections.mangaupdates.muMediaKey]).
     */
    data class Target(val anilistId: Int? = null, val muSeriesId: Long? = null, val muKey: Int? = null)

    class Index internal constructor(
        private val pickedBySlug: Map<String, Target>,
        private val matchedByHid: Map<String, Target>,
        private val matchedBySlug: Map<String, Target>,
    ) {
        fun of(hid: String, slug: String?): Target? =
            slug?.let { pickedBySlug[it] } ?: matchedByHid[hid] ?: slug?.let { matchedBySlug[it] }
    }

    /**
     * Pref key holding the full MangaUpdates series id behind a pick made on its page — the pick
     * itself is keyed by the truncated [ani.dantotsu.connections.mangaupdates.muMediaKey], which
     * can't be turned back into the id.
     */
    fun muPickKey(muKey: Int) = "comick_slug_mu_series_$muKey"

    /** Records the series a MangaUpdates page's Comick pick is for; see [muPickKey]. */
    fun recordMuPick(muKey: Int, muSeriesId: Long) {
        if (PrefManager.getNullableCustomVal(muPickKey(muKey), null, String::class.java) == muSeriesId.toString()) return
        PrefManager.setCustomVal(muPickKey(muKey), muSeriesId.toString())
    }

    fun clearMuPick(muKey: Int) = PrefManager.removeCustomVal(muPickKey(muKey))

    /**
     * Everything known, gathered once — a scan of the custom prefs and of [IdCache], so built per
     * pass rather than per title. Manga only: anime picks and matches are kept under other keys.
     */
    fun index(): Index {
        val picked = PrefManager.getAllCustomValsForMedia(PICK_PREFIX)
        val muSeries = picked.mapNotNull { (key, value) ->
            val muKey = key.removePrefix(MU_SERIES_PREFIX).takeIf { key.startsWith(MU_SERIES_PREFIX) }
                ?.toIntOrNull() ?: return@mapNotNull null
            val id = (value as? String)?.toLongOrNull() ?: return@mapNotNull null
            muKey to id
        }.toMap()
        val pickedBySlug = picked.mapNotNull { (key, value) ->
            // Only `comick_slug_<digits>`: the series records above share the prefix.
            val id = key.removePrefix(PICK_PREFIX).toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val slug = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val target = when {
                muSeries[id] != null -> Target(muSeriesId = muSeries[id], muKey = id)
                // A MangaUpdates key is a series id cut to 31 bits, so it lands almost anywhere in
                // Int range, while AniList ids are still in the hundreds of thousands. Only an older
                // pick, made before its series was recorded, is told apart this way.
                id > ANILIST_ID_CEILING -> Target(muKey = id)
                else -> Target(anilistId = id)
            }
            slug to target
        }.toMap()

        val hidsPrefix = "comick_hids_${ComickApi.MEDIA_TYPE_MANGA}_"
        val matchedByHid = unambiguous(
            IdCache.withPrefix(hidsPrefix).mapNotNull { (key, value) ->
                val anilistId = key.removePrefix(hidsPrefix).toIntOrNull() ?: return@mapNotNull null
                // "-<time>" is a remembered miss.
                if (value.startsWith("-")) return@mapNotNull null
                anilistId to value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
        )
        // [ComickApi.matchedSlugKey]'s keys.
        val slugsPrefix = "comick_slug_${ComickApi.MEDIA_TYPE_MANGA}_"
        val matchedBySlug = unambiguous(
            IdCache.withPrefix(slugsPrefix).mapNotNull { (key, value) ->
                val anilistId = key.removePrefix(slugsPrefix).toIntOrNull() ?: return@mapNotNull null
                anilistId to value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
        )
        return Index(pickedBySlug, matchedByHid, matchedBySlug)
    }

    /** Comick ids (HIDs or slugs) to the one AniList id each was matched to — none for several. */
    private fun unambiguous(matches: List<Pair<Int, List<String>>>): Map<String, Target> =
        matches.flatMap { (anilistId, ids) -> ids.map { it to anilistId } }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (id, anilistIds) -> anilistIds.distinct().singleOrNull()?.let { id to Target(anilistId = it) } }
            .toMap()

    private const val PICK_PREFIX = "comick_slug_"
    private const val MU_SERIES_PREFIX = "comick_slug_mu_series_"

    /** Above any AniList id for years to come; see [index]. */
    private const val ANILIST_ID_CEILING = 5_000_000
}
