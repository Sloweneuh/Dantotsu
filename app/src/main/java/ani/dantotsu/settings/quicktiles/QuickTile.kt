package ani.dantotsu.settings.quicktiles

import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ani.dantotsu.R
import ani.dantotsu.connections.handoff.HandoffBottomSheet
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.download.DownloadActivity
import ani.dantotsu.incognitoNotification
import ani.dantotsu.media.CalendarActivity
import ani.dantotsu.profile.activity.FeedActivity
import ani.dantotsu.settings.DiscordDialogFragment
import ani.dantotsu.settings.ExtensionBrowseActivity
import ani.dantotsu.settings.ExtensionsActivity
import ani.dantotsu.settings.PlayerSettingsActivity
import ani.dantotsu.settings.ReaderSettingsActivity
import ani.dantotsu.settings.SettingsActivity
import ani.dantotsu.settings.SettingsBackupSyncActivity
import ani.dantotsu.settings.SettingsConnectionsActivity
import ani.dantotsu.settings.SettingsListSyncActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.AppNotices
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * What the quick-settings sheet can show, and where the user's arrangement of it lives.
 *
 * The sheet used to be a fixed column of two switches and five buttons. Everyone wants a different
 * five, so the contents are now a reorderable grid the user picks from [all] — the same bargain
 * Android's own quick tiles make.
 */
sealed class QuickTile(
    val id: String,
    @DrawableRes val icon: Int,
    /** Which group the tile is filed under in the edit shelf. */
    val category: QuickTileCategory,
    /** Hidden entirely from the live pages, rather than shown in a state that cannot work. */
    val needsNetwork: Boolean,
    /**
     * False when the tile's subject is missing — list sync with no tracker signed in controls
     * nothing and opens an empty screen; Discord RPC has nothing to broadcast to.
     *
     * Such a tile stays in the edit shelf, greyed out and un-addable with [unavailableReason]
     * saying what is needed, rather than vanishing with no explanation. It is kept off the live
     * panel either way.
     */
    val isAvailable: () -> Boolean = { true },
    @StringRes val unavailableReason: Int? = null,
) {
    /** Resolved late so extension tiles can name themselves without a string resource. */
    abstract fun label(host: QuickTileHost): CharSequence

    /**
     * Something that is on or off; the tile renders lit while it is on.
     *
     * State is read and written through lambdas rather than a single [PrefName] because not every
     * switch is one preference — list sync covers a provider each.
     */
    class Toggle(
        id: String,
        @StringRes val labelRes: Int,
        @DrawableRes icon: Int,
        category: QuickTileCategory,
        needsNetwork: Boolean = false,
        isAvailable: () -> Boolean = { true },
        unavailableReason: Int? = null,
        val isOn: () -> Boolean,
        val setOn: (QuickTileHost, Boolean) -> Unit,
        /** Somewhere to configure what the toggle controls, reached by a long press. */
        val onLongClick: ((QuickTileHost) -> Unit)? = null,
    ) : QuickTile(id, icon, category, needsNetwork, isAvailable, unavailableReason) {
        override fun label(host: QuickTileHost): CharSequence = host.activity.getString(labelRes)
    }

    /** Goes somewhere. Never lit, and closes the sheet behind it. */
    class Action(
        id: String,
        @StringRes val labelRes: Int,
        @DrawableRes icon: Int,
        category: QuickTileCategory,
        needsNetwork: Boolean = false,
        isAvailable: () -> Boolean = { true },
        unavailableReason: Int? = null,
        val onClick: (QuickTileHost) -> Unit,
    ) : QuickTile(id, icon, category, needsNetwork, isAvailable, unavailableReason) {
        override fun label(host: QuickTileHost): CharSequence = host.activity.getString(labelRes)
    }

    /**
     * A shortcut straight into one installed extension's browse screen.
     *
     * Built from what is installed rather than declared in [QuickTiles.all], so the catalogue
     * changes as the user adds and removes extensions. An arrangement naming an extension that has
     * since been uninstalled simply loses that tile.
     */
    class Extension(
        val pkgName: String,
        val type: String,
        private val name: String,
    ) : QuickTile(
        idFor(pkgName, type), R.drawable.ic_extension,
        categoryFor(type), needsNetwork = true,
    ) {
        override fun label(host: QuickTileHost): CharSequence = name

        /** The extension's own launcher icon, so tiles look like the extension list. */
        fun loadIcon(host: QuickTileHost): Drawable? = runCatching {
            host.activity.packageManager.getApplicationIcon(pkgName)
        }.getOrNull()

        companion object {
            fun idFor(pkgName: String, type: String) = "$PREFIX$type:$pkgName"

            /** Anime and manga extensions get a shelf section each; the lists are long. */
            private fun categoryFor(type: String) =
                if (type == ExtensionBrowseActivity.TYPE_ANIME) {
                    QuickTileCategory.ANIME_EXTENSIONS
                } else {
                    QuickTileCategory.MANGA_EXTENSIONS
                }

            const val PREFIX = "ext:"
        }
    }
}

/** What a tile is allowed to do to the sheet that hosts it. */
class QuickTileHost(
    val activity: FragmentActivity,
    val dismiss: () -> Unit,
    /** Offline mode has to unwind to a page that exists offline, which only the sheet knows. */
    val setOfflineMode: (Boolean) -> Unit,
)

object QuickTiles {

    private fun QuickTileHost.open(target: Class<*>) {
        ContextCompat.startActivity(activity, Intent(activity, target), null)
        dismiss()
    }

    /**
     * Only settings that take hold the moment they are flipped are toggles here. Anything whose
     * settings-screen row ends in restartApp() — the theme variants, immersive mode, small view,
     * banner blur and animations, adult content — would make a "quick" tile that appears to do
     * nothing until the app is killed, so those stay in Settings where the restart is expected.
     */
    private fun prefToggle(
        id: String,
        labelRes: Int,
        icon: Int,
        pref: PrefName,
        category: QuickTileCategory,
        needsNetwork: Boolean = false,
        isAvailable: () -> Boolean = { true },
        unavailableReason: Int? = null,
        after: ((QuickTileHost, Boolean) -> Unit)? = null,
        onLongClick: ((QuickTileHost) -> Unit)? = null,
    ) = QuickTile.Toggle(
        id, labelRes, icon, category, needsNetwork, isAvailable, unavailableReason,
        isOn = { PrefManager.getVal(pref) },
        setOn = { host, on ->
            PrefManager.setVal(pref, on)
            after?.invoke(host, on)
        },
        onLongClick = onLongClick,
    )

    private val builtIn: List<QuickTile> = listOf(
        prefToggle(
            "incognito", R.string.incognito_mode, R.drawable.ic_incognito_24,
            PrefName.Incognito, QuickTileCategory.MODES,
            after = { host, _ -> incognitoNotification(host.activity) },
        ),
        QuickTile.Toggle(
            "offline", R.string.offline_mode, R.drawable.ic_signal_wifi_off_24,
            QuickTileCategory.MODES,
            isOn = { PrefManager.getVal(PrefName.OfflineMode) },
            // Writing the preference is the sheet's job here: it has to leave the current page
            // first, and half of them do not exist on the other side of the switch.
            setOn = { host, on -> host.setOfflineMode(on) },
        ),
        prefToggle(
            "discord_rpc", R.string.quick_tile_discord_rpc, R.drawable.ic_discord,
            PrefName.rpcEnabled, QuickTileCategory.CONNECTIONS, needsNetwork = true,
            // Nothing to broadcast a presence to without an account linked.
            isAvailable = { Discord.token != null },
            unavailableReason = R.string.quick_tile_needs_discord,
            onLongClick = { host ->
                val fm = host.activity.supportFragmentManager
                host.dismiss()
                DiscordDialogFragment().show(fm, "dialog")
            },
        ),
        prefToggle(
            "cloud_sync", R.string.cloud_sync, R.drawable.ic_round_cloud_sync_24,
            PrefName.CloudSyncEnabled, QuickTileCategory.CONNECTIONS, needsNetwork = true,
            // Turning it off can otherwise strand a sync banner with nothing to re-evaluate it.
            after = { _, _ -> AppNotices.dismissStale() },
            // The backup screen is what this switch is a shortcut into, so it does not also need
            // a tile of its own.
            onLongClick = { it.open(SettingsBackupSyncActivity::class.java) },
        ),
        QuickTile.Toggle(
            "list_sync", R.string.quick_tile_list_sync, R.drawable.ic_round_sync_24,
            QuickTileCategory.CONNECTIONS, needsNetwork = true,
            // Both switches write preferences that only a signed-in tracker reads, and the screen
            // behind the long press is empty without one.
            isAvailable = { MAL.token != null || MangaBaka.token != null },
            unavailableReason = R.string.quick_tile_needs_tracker,
            // One switch over both providers: the tile is a master control, and the per-provider
            // choice lives behind the long press.
            isOn = {
                PrefManager.getVal<Boolean>(PrefName.MalListSyncEnabled) ||
                        PrefManager.getVal<Boolean>(PrefName.MangaBakaListSyncEnabled)
            },
            setOn = { _, on ->
                PrefManager.setVal(PrefName.MalListSyncEnabled, on)
                PrefManager.setVal(PrefName.MangaBakaListSyncEnabled, on)
            },
            onLongClick = { it.open(SettingsListSyncActivity::class.java) },
        ),

        QuickTile.Action(
            "activity", R.string.activities, R.drawable.inbox_empty,
            QuickTileCategory.LIBRARY, needsNetwork = true,
        ) { it.open(FeedActivity::class.java) },
        QuickTile.Action(
            "calendar", R.string.release_calendar, R.drawable.ic_round_calendar_today_24,
            QuickTileCategory.LIBRARY, needsNetwork = true,
        ) { it.open(CalendarActivity::class.java) },
        QuickTile.Action(
            "downloads", R.string.downloads, R.drawable.ic_download_24,
            QuickTileCategory.LIBRARY,
        ) { it.open(DownloadActivity::class.java) },

        QuickTile.Action(
            "handoff", R.string.receive_from_another_device, R.drawable.ic_round_cast_24,
            QuickTileCategory.CONNECTIONS, needsNetwork = true,
        ) { host ->
            // Show on the activity's manager so the sheet survives this one being dismissed.
            val fm = host.activity.supportFragmentManager
            host.dismiss()
            HandoffBottomSheet.receive().show(fm, "handoff")
        },
        QuickTile.Action(
            "connections", R.string.connections_settings, R.drawable.network_node_24,
            QuickTileCategory.CONNECTIONS, needsNetwork = true,
        ) { it.open(SettingsConnectionsActivity::class.java) },

        QuickTile.Action(
            "extensions", R.string.extension_settings, R.drawable.ic_extension,
            QuickTileCategory.SETTINGS, needsNetwork = true,
        ) { it.open(ExtensionsActivity::class.java) },
        QuickTile.Action(
            "player_settings", R.string.player_settings, R.drawable.ic_round_video_settings_24,
            QuickTileCategory.SETTINGS,
        ) { it.open(PlayerSettingsActivity::class.java) },
        QuickTile.Action(
            "reader_settings", R.string.reader_settings, R.drawable.ic_round_reader_settings,
            QuickTileCategory.SETTINGS,
        ) { it.open(ReaderSettingsActivity::class.java) },
        QuickTile.Action(
            "settings", R.string.settings, R.drawable.ic_round_settings_24,
            QuickTileCategory.SETTINGS,
        ) { it.open(SettingsActivity::class.java) },
    )

    /** One shortcut per installed extension, rebuilt each time the sheet opens. */
    private fun extensionTiles(): List<QuickTile> = runCatching {
        // Alphabetical within each section: install order is meaningless to anyone hunting for a
        // particular source in a list that runs to dozens.
        val anime = Injekt.get<AnimeExtensionManager>().installedExtensionsFlow.value
            .sortedBy { it.name.lowercase() }
            .map { QuickTile.Extension(it.pkgName, ExtensionBrowseActivity.TYPE_ANIME, it.name) }
        val manga = Injekt.get<MangaExtensionManager>().installedExtensionsFlow.value
            .sortedBy { it.name.lowercase() }
            .map { QuickTile.Extension(it.pkgName, ExtensionBrowseActivity.TYPE_MANGA, it.name) }
        anime + manga
    }.getOrDefault(emptyList())

    /** The full catalogue for this moment: fixed tiles plus whatever extensions are installed. */
    val all: List<QuickTile> get() = builtIn + extensionTiles()

    /** What the sheet held before it was customisable, so nobody's muscle memory breaks. */
    private val defaultIds = listOf(
        "incognito", "offline", "activity", "extensions", "downloads", "handoff", "settings",
    )

    /** The user's tiles, in their order and at their sizes. Retired ids are simply skipped. */
    fun placed(): List<PlacedTile> {
        val catalogue = all.associateBy { it.id }
        val saved = PrefManager.getVal<List<String>>(PrefName.QuickTileOrder)
        if (saved.isEmpty()) return defaults()

        return saved.mapNotNull { entry ->
            // Extension ids carry colons of their own, so the size tag is the last one.
            val size = if (entry.substringAfterLast(':', "") == SMALL_TAG) {
                TileSize.SMALL
            } else {
                TileSize.LARGE
            }
            val id = entry.substringBeforeLast(':')
            // Unavailable tiles stay on the panel, greyed out by the grid — dropping them would
            // silently rewrite the arrangement the next time it was saved.
            catalogue[id]?.let { PlacedTile(it, size) }
        }
    }

    /** Everything the user has not placed, in catalogue order. Offered at large size. */
    fun available(): List<PlacedTile> {
        val chosen = placed().map { it.tile.id }.toSet()
        return all.filterNot { it.id in chosen }.map { PlacedTile(it, TileSize.LARGE) }
    }

    /** The arrangement a fresh install would have, used by the editor's reset. */
    fun defaults(): List<PlacedTile> {
        val catalogue = all.associateBy { it.id }
        return defaultIds.mapNotNull { catalogue[it] }
            .filter { it.isAvailable() }
            .map { PlacedTile(it, TileSize.LARGE) }
    }

    fun save(tiles: List<PlacedTile>) {
        PrefManager.setVal(
            PrefName.QuickTileOrder,
            tiles.map { "${it.tile.id}:${if (it.size == TileSize.SMALL) SMALL_TAG else LARGE_TAG}" },
        )
    }

    /**
     * Splits tiles across pages the way the grid will lay them out: four columns, at most four
     * rows, and a large tile never straddles a row boundary.
     */
    fun paginate(tiles: List<PlacedTile>, columns: Int, rows: Int): List<List<PlacedTile>> {
        if (tiles.isEmpty()) return listOf(emptyList())
        val pages = mutableListOf<MutableList<PlacedTile>>()
        var page = mutableListOf<PlacedTile>()
        var row = 0
        var used = 0
        for (placed in tiles) {
            val width = placed.size.columns
            if (used + width > columns) {
                row++
                used = 0
            }
            if (row >= rows) {
                pages += page
                page = mutableListOf()
                row = 0
                used = 0
            }
            page += placed
            used += width
        }
        pages += page
        return pages
    }

    private const val SMALL_TAG = "s"
    private const val LARGE_TAG = "l"
}

/** Groups the edit shelf files tiles under, in the order they are shown. */
enum class QuickTileCategory(@StringRes val label: Int) {
    MODES(R.string.quick_tiles_cat_modes),
    LIBRARY(R.string.quick_tiles_cat_library),
    CONNECTIONS(R.string.quick_tiles_cat_connections),
    SETTINGS(R.string.quick_tiles_cat_settings),
    ANIME_EXTENSIONS(R.string.quick_tiles_cat_anime_extensions),
    MANGA_EXTENSIONS(R.string.quick_tiles_cat_manga_extensions),
}

/** Android's two quick-tile shapes: icon only, or icon with a label and its state. */
enum class TileSize(val columns: Int) { SMALL(1), LARGE(2) }

/** A tile as the user arranged it. */
data class PlacedTile(val tile: QuickTile, var size: TileSize)

/**
 * Whether a tile can actually do anything right now. Unusable ones are shown greyed rather than
 * removed: a tile vanishing because the network dropped, and reappearing later somewhere else in
 * the grid, is harder to read than one that is plainly inert.
 */
fun QuickTile.isUsable(offline: Boolean) = isAvailable() && !(offline && needsNetwork)
