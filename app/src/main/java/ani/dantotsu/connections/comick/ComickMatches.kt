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

    /**
     * The AniList (else MangaUpdates) page a Comick manga — by HID or slug — is matched to now, or
     * null when it has none. For notifications posted before the match was made: they stored, and
     * their tap carries, Comick's own page, and a title the AniList check has since taken over is
     * never revisited by the Comick check to have it moved.
     */
    fun trackerLink(hidOrSlug: String): String? {
        val target = index().of(hidOrSlug, hidOrSlug) ?: return null
        target.anilistId?.let { return "https://anilist.co/manga/$it" }
        target.muSeriesId?.let { return "https://www.mangaupdates.com/series/${it.toString(36)}" }
        return null
    }

    /** [trackerLink] for a Comick manga page link; null for any other link. */
    fun trackerLinkFor(link: String): String? {
        val uri = runCatching { java.net.URI(link) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (!host.startsWith("comick.")) return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if (segments.getOrNull(0) != "comic") return null
        return segments.getOrNull(1)?.let { trackerLink(it) }
    }

    /** Comick ids (HIDs or slugs) to the one AniList id each was matched to — none for several. */
    private fun unambiguous(matches: List<Pair<Int, List<String>>>): Map<String, Target> =
        matches.flatMap { (anilistId, ids) -> ids.map { it to anilistId } }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (id, anilistIds) -> anilistIds.distinct().singleOrNull()?.let { id to Target(anilistId = it) } }
            .toMap()

    // ---- picks made by hand ---------------------------------------------------------------------

    /**
     * A Comick entry ([slug]) picked by hand for a title: on its media page's Comick tab, or from
     * the unmatched titles. For an AniList title ([anilistId]), or a MangaUpdates series
     * ([muSeriesId], always manga) — of which an older pick knows only the truncated [muKey].
     */
    data class Pick(
        val slug: String,
        val anilistId: Int? = null,
        val muSeriesId: Long? = null,
        val muKey: Int? = null,
        val isAnime: Boolean = false,
    )

    /** Every pick made by hand, manga and anime. Read from the prefs, so cheap enough per visit. */
    fun picks(): List<Pick> {
        val manga = PrefManager.getAllCustomValsForMedia(PICK_PREFIX)
        val muSeries = manga.mapNotNull { (key, value) ->
            if (!key.startsWith(MU_SERIES_PREFIX)) return@mapNotNull null
            val muKey = key.removePrefix(MU_SERIES_PREFIX).toIntOrNull() ?: return@mapNotNull null
            (value as? String)?.toLongOrNull()?.let { muKey to it }
        }.toMap()
        val mangaPicks = manga.mapNotNull { (key, value) ->
            // Only `comick_slug_<digits>`; see [index].
            val id = key.removePrefix(PICK_PREFIX).toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val slug = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            when {
                muSeries[id] != null -> Pick(slug, muSeriesId = muSeries[id], muKey = id)
                id > ANILIST_ID_CEILING -> Pick(slug, muKey = id)
                else -> Pick(slug, anilistId = id)
            }
        }
        val animePrefix = ComickEpisodes.savedSlugKey(0).removeSuffix("0")
        val animePicks = PrefManager.getAllCustomValsForMedia(animePrefix).mapNotNull { (key, value) ->
            val id = key.removePrefix(animePrefix).toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val slug = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Pick(slug, anilistId = id, isAnime = true)
        }
        return mangaPicks + animePicks
    }

    /** Points [pick]'s title at another Comick entry, as picking it on the media page would. */
    fun repick(pick: Pick, slug: String, hid: String): Pick {
        when {
            pick.anilistId != null -> link(pick.anilistId, pick.isAnime, slug, hid)
            pick.muSeriesId != null -> linkMangaUpdates(pick.muSeriesId, slug, hid)
            // An older MangaUpdates pick whose series was never recorded: the pick alone.
            pick.muKey != null -> PrefManager.setCustomVal("$PICK_PREFIX${pick.muKey}", slug)
        }
        return pick.copy(slug = slug)
    }

    /**
     * Takes [pick] back, as unlinking on the media page does — and also forgets the Comick entries
     * sync resolved from it, which it would otherwise keep writing to. The title is matched afresh
     * the next time anything looks.
     */
    fun unpick(pick: Pick) {
        PrefManager.removeCustomVal(pickKey(pick))
        pick.muKey?.let { clearMuPick(it) }
        pick.anilistId?.let {
            IdCache.remove("comick_hids_${mediaType(pick.isAnime)}_$it")
            if (pick.isAnime) ComickEpisodes.clearAutoSlug(it)
        }
        pick.muSeriesId?.let { IdCache.remove(muHidsKey(it)) }
        IdCache.flush()
    }

    /**
     * Puts back a pick [unpick] took — the undo. The entries sync had resolved are found again
     * from the pick itself on its next look, at the cost of one lookup.
     */
    fun restore(pick: Pick) {
        PrefManager.setCustomVal(pickKey(pick), pick.slug)
        if (pick.muKey != null && pick.muSeriesId != null) recordMuPick(pick.muKey, pick.muSeriesId)
    }

    private fun pickKey(pick: Pick) = when {
        pick.anilistId != null -> pinnedKey(pick.anilistId, pick.isAnime)
        else -> "$PICK_PREFIX${pick.muKey ?: pick.muSeriesId?.let { ani.dantotsu.connections.mangaupdates.muMediaKey(it) }}"
    }

    // ---- titles list sync couldn't match ----------------------------------------------------

    /**
     * A title list sync found no Comick entry for: an AniList title ([anilistId]), or a
     * MangaUpdates series AniList doesn't have ([muSeriesId], always manga).
     */
    data class Unmatched(
        val anilistId: Int? = null,
        val muSeriesId: Long? = null,
        val isAnime: Boolean = false,
        /** Marked "not on Comick": listed apart, since Comick may add it later. */
        val notOnComick: Boolean = false,
    )

    /** Where list sync keeps the Comick HIDs (or a "-<time>" miss) of a MangaUpdates-only series. */
    fun muHidsKey(muSeriesId: Long) = "comick_hids_mu_$muSeriesId"

    /**
     * Every title list sync last failed to match ("-<time>" misses), minus those picked by hand
     * since; those marked [markNotOnComick] are flagged rather than left out. Sync keeps retrying
     * all of them, so a marked title Comick adds later and sync confirms stops being a miss and
     * drops off. A miss only exists for titles sync, Compare lists or the Comick checks tried, so
     * this is the user's list, not the whole catalog.
     */
    fun unmatched(): List<Unmatched> {
        val dismissed = PrefManager.getAllCustomValsForMedia(NOT_ON_COMICK_PREFIX).keys
        val anilist = listOf(false, true).flatMap { isAnime ->
            val type = mediaType(isAnime)
            val prefix = "comick_hids_${type}_"
            IdCache.withPrefix(prefix).mapNotNull { (key, value) ->
                val id = key.removePrefix(prefix).toIntOrNull() ?: return@mapNotNull null
                if (!value.startsWith("-")) return@mapNotNull null
                if (pinnedSlug(id, isAnime) != null) return@mapNotNull null
                Unmatched(anilistId = id, isAnime = isAnime, notOnComick = notOnComickKey(id, isAnime) in dismissed)
            }
        }
        val muPrefix = muHidsKey(0).removeSuffix("0")
        val mangaUpdates = IdCache.withPrefix(muPrefix).mapNotNull { (key, value) ->
            val id = key.removePrefix(muPrefix).toLongOrNull() ?: return@mapNotNull null
            if (!value.startsWith("-")) return@mapNotNull null
            if (muPinnedSlug(id) != null) return@mapNotNull null
            Unmatched(muSeriesId = id, notOnComick = muNotOnComickKey(id) in dismissed)
        }
        return anilist + mangaUpdates
    }

    /** Links a MangaUpdates-only series by hand; see [link]. */
    fun linkMangaUpdates(muSeriesId: Long, slug: String, hid: String) {
        val muKey = ani.dantotsu.connections.mangaupdates.muMediaKey(muSeriesId)
        // The same pick its MangaUpdates page's Comick tab makes, with the series recorded.
        PrefManager.setCustomVal("$PICK_PREFIX$muKey", slug)
        recordMuPick(muKey, muSeriesId)
        IdCache.put(muHidsKey(muSeriesId), hid)
        IdCache.flush()
    }

    fun markNotOnComick(unmatched: Unmatched) = PrefManager.setCustomVal(notOnComickKey(unmatched), true)

    fun unmarkNotOnComick(unmatched: Unmatched) = PrefManager.removeCustomVal(notOnComickKey(unmatched))

    private fun notOnComickKey(unmatched: Unmatched) = unmatched.muSeriesId?.let { muNotOnComickKey(it) }
        ?: notOnComickKey(unmatched.anilistId ?: 0, unmatched.isAnime)

    private fun muPinnedSlug(muSeriesId: Long) = PrefManager.getNullableCustomVal(
        "$PICK_PREFIX${ani.dantotsu.connections.mangaupdates.muMediaKey(muSeriesId)}", null, String::class.java
    )?.takeIf { it.isNotBlank() }

    private fun muNotOnComickKey(muSeriesId: Long) = "${NOT_ON_COMICK_PREFIX}mu_$muSeriesId"

    /**
     * Links an AniList title to a Comick entry by hand: the same pick a media page's Comick tab
     * makes, plus the resolved [hid] in place of the miss, so the next sync uses it straight away.
     */
    fun link(anilistId: Int, isAnime: Boolean, slug: String, hid: String) {
        PrefManager.setCustomVal(pinnedKey(anilistId, isAnime), slug)
        IdCache.put("comick_hids_${mediaType(isAnime)}_$anilistId", hid)
        IdCache.flush()
    }

    private fun mediaType(isAnime: Boolean) =
        if (isAnime) ComickApi.MEDIA_TYPE_ANIME else ComickApi.MEDIA_TYPE_MANGA

    private fun pinnedKey(anilistId: Int, isAnime: Boolean) =
        if (isAnime) ComickEpisodes.savedSlugKey(anilistId) else "$PICK_PREFIX$anilistId"

    private fun pinnedSlug(anilistId: Int, isAnime: Boolean) =
        PrefManager.getNullableCustomVal(pinnedKey(anilistId, isAnime), null, String::class.java)
            ?.takeIf { it.isNotBlank() }

    private fun notOnComickKey(anilistId: Int, isAnime: Boolean) =
        "$NOT_ON_COMICK_PREFIX${mediaType(isAnime)}_$anilistId"

    private const val NOT_ON_COMICK_PREFIX = "comick_not_on_comick_"
    private const val PICK_PREFIX = "comick_slug_"
    private const val MU_SERIES_PREFIX = "comick_slug_mu_series_"

    /** Above any AniList id for years to come; see [index]. */
    private const val ANILIST_ID_CEILING = 5_000_000
}
