package ani.dantotsu.settings.quicktiles

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemQuickTileBinding
import ani.dantotsu.databinding.ItemQuickTileHeaderBinding
import ani.dantotsu.databinding.ItemQuickTilePageBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.loadImage
import ani.dantotsu.settings.saving.PrefManager

const val QUICK_TILE_COLUMNS = 4
const val QUICK_TILE_ROWS = 4

/** Height one grid row occupies, tile plus its margins, used to size the pager. */
fun quickTileRowHeightPx(density: Float) = (80 * density).toInt()

/**
 * Paints a tile. Shared because the live pages and the editor draw the same thing, differing only
 * in the affordances laid over it and what a tap means.
 */
internal fun ItemQuickTileBinding.paintQuickTile(
    host: QuickTileHost,
    placed: PlacedTile,
    editing: Boolean,
    /** Shelf entries already on the panel are shown greyed out and inert. */
    dimmed: Boolean,
) {
    val tile = placed.tile
    val context = root.context
    val large = placed.size == TileSize.LARGE

    // A live resize drag writes an explicit pixel width here, and LayoutParams belong to the view
    // rather than the tile in it. Left alone, the next tile recycled into this view inherits that
    // width — which is what pulled the selection ring away from the resize handle after a reorder,
    // since the handle is positioned against the cell and the ring follows this.
    quickTileRoot.updateLayoutParams { width = ViewGroup.LayoutParams.MATCH_PARENT }

    val extension = tile as? QuickTile.Extension
    val extensionIcon = extension?.loadIcon(host)
    val remoteIcon = extension?.iconUrl?.takeIf { extensionIcon == null && it.isNotBlank() }
    when {
        extensionIcon != null -> quickTileIcon.setImageDrawable(extensionIcon)
        // The generic glyph goes in first so a recycled view is never left showing the previous
        // tile's icon while this one's fetch is in flight, or if it never arrives.
        remoteIcon != null -> {
            quickTileIcon.setImageResource(tile.icon)
            quickTileIcon.loadImage(remoteIcon)
        }
        else -> quickTileIcon.setImageResource(tile.icon)
    }

    sizeIcon(large, extensionIcon != null || remoteIcon != null)
    quickTileText.isVisible = large
    quickTileRoot.gravity = if (large) Gravity.CENTER_VERTICAL else Gravity.CENTER
    if (large) {
        quickTileLabel.text = tile.label(host)
        // No state on a tile the user has not placed yet: "On" next to something that isn't on
        // their panel reads as a claim about the tile rather than the setting.
        val state = (tile as? QuickTile.Toggle)?.takeIf { !dimmed }?.let {
            if (it.isOn()) R.string.quick_tile_on else R.string.quick_tile_off
        }
        quickTileState.isVisible = state != null
        state?.let { quickTileState.setText(it) }
    }

    // A tile reads as "on" only when it is a toggle whose preference is set, and never while
    // editing — there the lit colour would fight the add/remove affordances.
    val lit = !editing && tile is QuickTile.Toggle && tile.isOn()
    val background = context.getThemeColor(
        if (lit) com.google.android.material.R.attr.colorPrimary
        else com.google.android.material.R.attr.colorSurfaceVariant
    )
    val foreground = context.getThemeColor(
        if (lit) com.google.android.material.R.attr.colorOnPrimary
        else com.google.android.material.R.attr.colorOnSurfaceVariant
    )
    quickTileRoot.backgroundTintList = ColorStateList.valueOf(background)
    // An extension's own icon is artwork, not a glyph, so it keeps its colours — including a
    // plugin's, which arrives from a URL rather than a package. Tinting it flattened the artwork
    // into a solid block of the foreground colour, which reads as an icon that never loaded.
    quickTileIcon.imageTintList =
        if (extensionIcon != null || remoteIcon != null) null
        else ColorStateList.valueOf(foreground)
    quickTileLabel.setTextColor(foreground)
    quickTileState.setTextColor(foreground)
    // Already on the panel: the shelf shows it dimmed and inert so the catalogue stays complete.
    quickTileRoot.alpha = if (dimmed) 0.4f else 1f

    quickTileBadge.isVisible = false
    quickTileResize.isVisible = false
    // Cleared with the width above: a stretch drags the handle along, and the offset would
    // otherwise ride the recycled view onto the next tile.
    quickTileResize.translationX = 0f
    quickTileRoot.foreground = null
}

/**
 * An extension's launcher icon is artwork rather than a 24dp glyph, so it needs the room to read
 * as one — especially on a small tile, where it is the only thing in the box.
 */
internal fun quickTileIconDp(large: Boolean, isExtension: Boolean) = when {
    isExtension && !large -> 40
    isExtension -> 30
    large -> 24
    else -> 30
}

private fun ItemQuickTileBinding.sizeIcon(large: Boolean, isExtension: Boolean) {
    val density = root.resources.displayMetrics.density
    val dp = quickTileIconDp(large, isExtension)
    quickTileIcon.updateLayoutParams {
        width = (dp * density).toInt()
        height = (dp * density).toInt()
    }
}

/** Runs a tile's action, or flips its preference and relights it. */
private fun activateTile(host: QuickTileHost, tile: QuickTile, relight: () -> Unit) {
    when (tile) {
        is QuickTile.Action -> tile.onClick(host)
        is QuickTile.Extension -> {
            host.activity.startActivity(
                android.content.Intent(
                    host.activity, ani.dantotsu.settings.ExtensionBrowseActivity::class.java
                )
                    .putExtra(
                        ani.dantotsu.settings.ExtensionBrowseActivity.EXTRA_PKG, tile.pkgName
                    )
                    .putExtra(
                        ani.dantotsu.settings.ExtensionBrowseActivity.EXTRA_TYPE, tile.type
                    )
            )
            host.dismiss()
        }

        is QuickTile.Toggle -> {
            tile.setOn(host, !tile.isOn())
            // The offline tile tears the sheet down as part of switching pages; every other
            // toggle just relights in place.
            relight()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Live pages
// ---------------------------------------------------------------------------------------------

/** One swipeable page of the user's tiles. */
class QuickTileGridAdapter(
    private val host: QuickTileHost,
    private val tiles: List<PlacedTile>,
    private val offline: Boolean,
    private val onLongPress: () -> Unit,
) : RecyclerView.Adapter<QuickTileGridAdapter.Holder>() {

    inner class Holder(val binding: ItemQuickTileBinding) : RecyclerView.ViewHolder(binding.root)

    fun spanSizeLookup() = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int) =
            tiles.getOrNull(position)?.size?.columns ?: QUICK_TILE_COLUMNS
    }

    override fun getItemCount() = tiles.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemQuickTileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val placed = tiles[position]
        // Greyed and inert rather than missing when there is nothing behind it — no connection, or
        // no account linked. It keeps its place, and comes back to life on its own.
        val usable = placed.tile.isUsable(offline)
        holder.binding.paintQuickTile(host, placed, editing = false, dimmed = !usable)
        holder.binding.quickTileRoot.isClickable = usable
        holder.binding.quickTileRoot.setOnClickListener {
            if (usable) activateTile(host, placed.tile) { notifyItemChanged(position) }
        }
        holder.binding.quickTileRoot.setOnLongClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            // A tile with its own long-press action — a toggle's config screen, a search tile's
            // "pin to home screen" — runs that; anything else falls through to rearranging the
            // panel. Rearranging works either way, so an unusable tile still answers a long press.
            val custom = when (val tile = placed.tile) {
                is QuickTile.Toggle -> tile.onLongClick
                is QuickTile.Action -> tile.onLongClick
                else -> null
            }?.takeIf { usable }
            if (custom != null) custom(host) else onLongPress()
            true
        }
    }
}

/** The pager itself: one grid per page. */
class QuickTilePagerAdapter(
    private val host: QuickTileHost,
    private val pages: List<List<PlacedTile>>,
    private val offline: Boolean,
    private val onLongPress: () -> Unit,
) : RecyclerView.Adapter<QuickTilePagerAdapter.PageHolder>() {

    class PageHolder(val binding: ItemQuickTilePageBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageHolder(
        ItemQuickTilePageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val adapter = QuickTileGridAdapter(host, pages[position], offline, onLongPress)
        val manager = GridLayoutManager(holder.itemView.context, QUICK_TILE_COLUMNS)
        manager.spanSizeLookup = adapter.spanSizeLookup()
        holder.binding.quickTilePageRecycler.layoutManager = manager
        holder.binding.quickTilePageRecycler.adapter = adapter
    }

    /** Rows the tallest page needs, so the pager can be given a height that fits every page. */
    fun maxRows(): Int = pages.maxOfOrNull { page ->
        var rows = 1
        var used = 0
        page.forEach { placed ->
            if (used + placed.size.columns > QUICK_TILE_COLUMNS) {
                rows++
                used = 0
            }
            used += placed.size.columns
        }
        rows
    } ?: 1
}

// ---------------------------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------------------------

