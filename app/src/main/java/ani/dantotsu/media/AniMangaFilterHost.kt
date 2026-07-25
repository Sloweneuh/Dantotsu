package ani.dantotsu.media

import ani.dantotsu.connections.anilist.AniMangaSearchResults

/**
 * Minimal contract [SearchFilterBottomDialog] needs from whatever screen shows it —
 * lets screens other than [SearchActivity] (e.g. the airing calendar) reuse the exact
 * same anime filter sheet, active-chip logic, and saved presets without pretending to
 * be a full search screen.
 */
interface AniMangaFilterHost {
    val aniMangaResult: AniMangaSearchResults
    val updateChips: () -> Unit
    fun search()

    /**
     * Bucket key for saved presets — defaults to [aniMangaResult]'s own ANIME/MANGA type.
     * Hosts that hide fields (so their filter shape no longer matches a real anime/manga
     * search) should override this so their presets don't mix with search's.
     */
    val presetsType: String get() = aniMangaResult.type

    /**
     * False to hide the status/season/year-range sections. [aniMangaResult.type] stays
     * "ANIME"/"MANGA" regardless (it still picks the right status/format option lists) —
     * this only controls whether those particular fields are shown/settable at all, for
     * hosts (like the airing calendar) where they don't meaningfully apply.
     */
    val supportsStatusSeasonYear: Boolean get() = true
}
