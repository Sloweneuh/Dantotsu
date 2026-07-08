package ani.dantotsu.media

import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/** One kind of info tab that can appear on a media details screen. */
enum class InfoTabType(val key: String, val iconRes: Int, val labelRes: Int) {
    ANILIST("anilist", R.drawable.ic_anilist, R.string.anilist),
    MAL("mal", R.drawable.ic_myanimelist, R.string.myanimelist),
    COMICK("comick", R.drawable.ic_round_comick_24, R.string.comick),
    MANGAUPDATES("mangaupdates", R.drawable.ic_round_mangaupdates_24, R.string.mangaupdates),
    MANGABAKA("mangabaka", R.drawable.ic_round_mangabaka_24, R.string.mangabaka);

    /**
     * Whether this connection's data fetching is enabled. This is intentionally independent from
     * tab visibility/order below: e.g. MangaBaka lookups must still run to detect an AniList
     * equivalent even when the user has hidden the MangaBaka tab itself.
     */
    val fetchEnabled: Boolean
        get() = when (this) {
            ANILIST -> true
            MAL -> PrefManager.getVal(PrefName.MalEnabled)
            COMICK -> PrefManager.getVal(PrefName.ComickEnabled)
            MANGAUPDATES -> PrefManager.getVal(PrefName.MangaUpdatesEnabled)
            MANGABAKA -> PrefManager.getVal(PrefName.MangaBakaInfoEnabled)
        }
}

/** The media contexts that each get their own customizable info-tab order/visibility. */
enum class InfoTabContext(
    val orderPref: PrefName,
    val visibilityPref: PrefName,
    val tabs: List<InfoTabType>
) {
    ANILIST_ANIME(
        PrefName.InfoTabOrderAnilistAnime, PrefName.InfoTabVisibilityAnilistAnime,
        listOf(InfoTabType.ANILIST, InfoTabType.MAL)
    ),
    ANILIST_MANGA(
        PrefName.InfoTabOrderAnilistManga, PrefName.InfoTabVisibilityAnilistManga,
        listOf(
            InfoTabType.ANILIST, InfoTabType.MAL, InfoTabType.COMICK,
            InfoTabType.MANGAUPDATES, InfoTabType.MANGABAKA
        )
    ),
    MANGAUPDATES_MANGA(
        PrefName.InfoTabOrderMangaUpdates, PrefName.InfoTabVisibilityMangaUpdates,
        listOf(InfoTabType.MANGAUPDATES, InfoTabType.COMICK, InfoTabType.MANGABAKA)
    );

    /** User-saved tab order (indices into [tabs]), healed to the current [tabs] size if stale. */
    fun savedOrder(): List<Int> {
        val saved = PrefManager.getVal<List<Int>>(orderPref)
        return if (saved.size == tabs.size && saved.toSet() == tabs.indices.toSet()) saved
        else tabs.indices.toList()
    }

    /** User-saved per-tab visibility (aligned to [tabs] indices), healed if stale. */
    fun savedVisibility(): List<Boolean> {
        val saved = PrefManager.getVal<List<Boolean>>(visibilityPref)
        return if (saved.size == tabs.size) saved else tabs.indices.map { true }
    }

    /**
     * Final ordered list of tabs to actually display: user-visible AND fetch-enabled. Data
     * fetching for a given [InfoTabType] must never be gated on whether it appears here - see
     * [InfoTabType.fetchEnabled].
     */
    fun visibleOrderedTabs(): List<InfoTabType> {
        val order = savedOrder()
        val visibility = savedVisibility()
        return order.mapNotNull { idx ->
            val type = tabs.getOrNull(idx) ?: return@mapNotNull null
            if (visibility.getOrNull(idx) != true) return@mapNotNull null
            if (!type.fetchEnabled) return@mapNotNull null
            type
        }
    }
}
