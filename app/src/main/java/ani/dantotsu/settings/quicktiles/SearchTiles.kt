package ani.dantotsu.settings.quicktiles

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType.Companion.toAnilistString
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.media.SearchActivity
import ani.dantotsu.others.AppShortcuts
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/** The search sheet's shelf headings. */
enum class SearchTileCategory(
    @StringRes override val label: Int,
    @DrawableRes override val icon: Int,
) : TileCategory {
    ANILIST(R.string.search_cat_anilist, R.drawable.ic_anilist),
    SERVICES(R.string.search_cat_services, R.drawable.ic_round_dns_24),
}

/**
 * What the search sheet can offer, as tiles.
 *
 * The sheet used to be a fixed column of nine buttons, most of which any given person never
 * touches. Same bargain as the quick settings: the panel is theirs to arrange, and the ones they
 * do not want move off it into the shelf.
 *
 * Every entry opens [SearchActivity] for one search type, carrying the query the sheet was opened
 * with — so this is all [QuickTile.Action]; nothing here is a toggle.
 */
object SearchTiles : TileCatalogue(PrefName.SearchTileOrder) {

    /**
     * The query to carry into the search, set by the sheet before it builds its tiles.
     *
     * Held here rather than passed through the tile because a tile's click takes only the host,
     * and threading a per-panel payload through the shared machinery for this one case would
     * complicate every other panel. Set on open, read on tap, in that order, on the main thread.
     */
    var pendingQuery: String? = null

    private fun search(id: String, @StringRes label: Int, @DrawableRes icon: Int, type: SearchType) =
        QuickTile.Action(
            id, label, icon, SearchTileCategory.ANILIST, needsNetwork = true,
            onLongClick = pinToHome(label, icon, type),
        ) { host -> open(host, type) }

    private fun service(
        id: String,
        @StringRes label: Int,
        @DrawableRes icon: Int,
        type: SearchType,
        isAvailable: () -> Boolean,
        @StringRes unavailableReason: Int,
    ) = QuickTile.Action(
        id, label, icon, SearchTileCategory.SERVICES, needsNetwork = true,
        isAvailable = isAvailable, unavailableReason = unavailableReason,
        onLongClick = pinToHome(label, icon, type),
    ) { host -> open(host, type) }

    /** Long-press a search tile to drop a standalone icon for that search on the home screen. */
    private fun pinToHome(@StringRes label: Int, @DrawableRes icon: Int, type: SearchType):
            (QuickTileHost) -> Unit = { host ->
        AppShortcuts.pinSearch(
            host.activity, type.toAnilistString(), host.activity.getString(label), icon,
        )
    }

    private fun open(host: QuickTileHost, type: SearchType) {
        val activity = host.activity
        val intent = Intent(activity, SearchActivity::class.java)
            .putExtra("type", type.toAnilistString())

        // Opened from a search screen: carry whatever is already typed rather than the query the
        // sheet was created with, and close the screen behind us so the two do not stack.
        val source = activity as? SearchActivity
        val query = source?.getHeaderSearchText()?.takeIf { it.isNotBlank() }
            ?: pendingQuery?.takeIf { it.isNotBlank() }
        if (query != null) {
            intent.putExtra("query", query)
            intent.putExtra("search", true)
        }

        ContextCompat.startActivity(activity, intent, null)
        host.dismiss()
        source?.finish()
    }

    override val all: List<QuickTile>
        get() = listOf(
            search("anime", R.string.anime, R.drawable.ic_round_movie_filter_24, SearchType.ANIME),
            search("manga", R.string.manga, R.drawable.ic_round_menu_book_24, SearchType.MANGA),
            search("characters", R.string.characters, R.drawable.ic_round_face_24, SearchType.CHARACTER),
            search("staff", R.string.staff, R.drawable.ic_round_group_24, SearchType.STAFF),
            search("studios", R.string.studios, R.drawable.ic_round_movie_edit_24, SearchType.STUDIO),
            search("users", R.string.users, R.drawable.ic_round_person_24, SearchType.USER),

            service(
                "mangaupdates", R.string.mu_series_search, R.drawable.ic_round_mangaupdates_24,
                SearchType.MANGAUPDATES,
                isAvailable = { MangaUpdates.token != null },
                unavailableReason = R.string.search_needs_mangaupdates,
            ),
            // Comick's two catalogues are separate searches: they take different filters and
            // never return each other's entries, so one tile could not serve both.
            service(
                "comick", R.string.comick_manga_search, R.drawable.ic_round_comick_manga_24,
                SearchType.COMICK,
                isAvailable = { PrefManager.getVal(PrefName.ComickEnabled) },
                unavailableReason = R.string.search_needs_connection_enabled,
            ),
            service(
                "comick_anime", R.string.comick_anime_search, R.drawable.ic_round_comick_anime_24,
                SearchType.COMICK_ANIME,
                isAvailable = { PrefManager.getVal(PrefName.ComickEnabled) },
                unavailableReason = R.string.search_needs_connection_enabled,
            ),
            service(
                "mangabaka", R.string.mangabaka, R.drawable.ic_round_mangabaka_24,
                SearchType.MANGABAKA,
                isAvailable = { PrefManager.getVal(PrefName.MangaBakaInfoEnabled) },
                unavailableReason = R.string.search_needs_connection_enabled,
            ),
            // Kitsu / Simkl search need no login (public / api-key). Off the panel by default —
            // they sit in the shelf until the user drags them on.
            service(
                "kitsu", R.string.kitsu_manga_search, R.drawable.ic_kitsu_manga,
                SearchType.KITSU,
                isAvailable = { true },
                unavailableReason = R.string.search_needs_connection_enabled,
            ),
            service(
                "kitsu_anime", R.string.kitsu_anime_search, R.drawable.ic_kitsu_anime,
                SearchType.KITSU_ANIME,
                isAvailable = { true },
                unavailableReason = R.string.search_needs_connection_enabled,
            ),
            service(
                "simkl", R.string.simkl_search, R.drawable.ic_simkl,
                SearchType.SIMKL,
                isAvailable = { true },
                unavailableReason = R.string.search_needs_connection_enabled,
            ),
            // MAL (Jikan) search needs no login either — same off-panel-by-default treatment.
            service(
                "mal", R.string.mal_manga_search, R.drawable.ic_myanimelist_manga,
                SearchType.MAL,
                isAvailable = { true },
                unavailableReason = R.string.search_needs_connection_enabled,
            ),
            service(
                "mal_anime", R.string.mal_anime_search, R.drawable.ic_myanimelist_anime,
                SearchType.MAL_ANIME,
                isAvailable = { true },
                unavailableReason = R.string.search_needs_connection_enabled,
            ),
        )

    /** Everything, in the order the buttons used to run: nothing is hidden until the user hides it. */
    override val defaultIds = listOf(
        "anime", "manga", "users", "characters", "staff", "studios",
        "mangaupdates", "comick", "comick_anime", "mangabaka",
    )
}
