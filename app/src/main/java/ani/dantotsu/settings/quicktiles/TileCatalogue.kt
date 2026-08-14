package ani.dantotsu.settings.quicktiles

import androidx.annotation.StringRes
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/** A heading in the edit shelf. An interface so each panel can name its own groups. */
interface TileCategory {
    @get:StringRes
    val label: Int
}

/**
 * One panel's worth of tiles: everything it could show, what it shows now, and where that is kept.
 *
 * Split out from [QuickTiles] so the search sheet can be the same thing with a different catalogue.
 * Everything below the account row — paging, editing, reordering, resizing — is panel-agnostic and
 * works off this; only the tiles themselves and the preference they are stored in differ.
 */
abstract class TileCatalogue(private val orderPref: PrefName) {

    /** Everything this panel could show, in the order the shelf lists it. */
    abstract val all: List<QuickTile>

    /** What the panel holds before anyone rearranges it. */
    protected abstract val defaultIds: List<String>

    /** The user's tiles, in their order and at their sizes. Retired ids are simply skipped. */
    fun placed(): List<PlacedTile> {
        val catalogue = all.associateBy { it.id }
        val saved = PrefManager.getVal<List<String>>(orderPref)
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
            orderPref,
            tiles.map { "${it.tile.id}:${if (it.size == TileSize.SMALL) SMALL_TAG else LARGE_TAG}" },
        )
    }

    /** The shelf's headings, in catalogue order, skipping any group with nothing in it. */
    fun categories(): List<TileCategory> = all.map { it.category }.distinct()

    companion object {
        private const val SMALL_TAG = "s"
        private const val LARGE_TAG = "l"

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
    }
}
