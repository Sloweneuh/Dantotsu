package ani.dantotsu.settings.quicktiles

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemQuickTileBinding
import ani.dantotsu.databinding.ItemQuickTileHeaderBinding
import ani.dantotsu.getThemeColor
import kotlin.math.abs

/**
 * The edit surface, following Android's own: the panel's arrangement on top, then a shelf holding
 * the whole catalogue grouped by category.
 *
 * A tile in the arrangement is dragged to move it and tapped to select it; the selected one grows
 * a ring and a resize handle, and the toolbar's remove button takes it off. A tile in the shelf
 * carries a "+" while it is unplaced, and is dimmed and inert once it is on the panel — the shelf
 * stays a complete, stably ordered catalogue rather than a shrinking pile.
 */
class QuickTileEditAdapter(
    private val host: QuickTileHost,
    private val catalogue: TileCatalogue,
    initial: List<PlacedTile>,
    private val onArrangementChanged: (List<PlacedTile>) -> Unit,
    /** Lets the toolbar enable its remove button only when there is something to remove. */
    private val onSelectionChanged: (PlacedTile?) -> Unit,
    /** Lets the toolbar show its undo button only once there is something to undo. */
    private val onHistoryChanged: (Boolean) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val placed = initial.map { PlacedTile(it.tile, it.size) }.toMutableList()
    private var selected: PlacedTile? = null

    /**
     * Snapshots taken before each edit. The arrangement is written as you go, so without this a
     * mis-tapped Remove or Reset would be gone for good the moment the sheet closed.
     */
    private val history = ArrayDeque<List<PlacedTile>>()

    private fun snapshot() {
        history.addLast(placed.map { PlacedTile(it.tile, it.size) })
        if (history.size > HISTORY_LIMIT) history.removeFirst()
        onHistoryChanged(true)
    }

    fun canUndo() = history.isNotEmpty()

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        placed.clear()
        placed += previous
        select(null)
        onArrangementChanged(placed)
        onHistoryChanged(history.isNotEmpty())
        rebuild()
    }

    /** A size change waiting for its tile to be rebound, so the new binding can animate into it. */
    private class Resize(val tile: PlacedTile, val from: TileSize, val durationMs: Long)

    private var animateNext: Resize? = null

    /** Held so a resize can set how long the grid takes to reflow around it. */
    private var recycler: RecyclerView? = null

    // Live resize-drag state. On the adapter rather than the touch listener because each size
    // change rebinds the holder and replaces that listener mid-gesture.
    private var dragStartX = 0f
    private var dragStartSize: TileSize? = null
    private var dragMoved = false
    private var dragStretch = 0f
    private var dragBaseWidth = 0

    /**
     * Live resize, copied from a recording of the system panel: the tile's width follows the
     * finger continuously rather than jumping a whole column at a threshold, and the label fades
     * in and out in step with how far along the change is.
     *
     * Only the dragged tile and the ones after it on the same row move. A grid of integer spans
     * cannot show a half-column tile, so this stretches the tile's own background past its cell —
     * hence clipChildren="false" on both the item and the list — and slides its neighbours by the
     * same amount, then hands over to the real span the moment the finger lifts.
     */
    private fun stretch(position: Int, target: PlacedTile, dx: Float) {
        val rv = recycler ?: return
        val holder = rv.findViewHolderForAdapterPosition(position) as? TileHolder ?: return
        val column = (rv.width - rv.paddingLeft - rv.paddingRight) / QUICK_TILE_COLUMNS.toFloat()
        if (column <= 0f) return

        // Only one direction means anything: a large tile can shrink, a small one can grow.
        val travel = when (dragStartSize ?: target.size) {
            TileSize.SMALL -> dx.coerceIn(0f, column)
            TileSize.LARGE -> dx.coerceIn(-column, 0f)
        }
        dragStretch = travel
        val fraction = abs(travel) / column
        val growing = (dragStartSize ?: target.size) == TileSize.SMALL

        with(holder.binding) {
            if (dragBaseWidth == 0) dragBaseWidth = quickTileRoot.width
            val stretched = dragBaseWidth + travel
            quickTileRoot.updateLayoutParams { width = stretched.toInt() }
            // The label is laid out for the wide shape throughout, and simply dissolves; that is
            // what the recording shows, rather than it popping in at the end.
            quickTileText.isVisible = true
            quickTileText.alpha = if (growing) fraction else 1f - fraction
            // Pinned to the wide layout for the whole gesture, so nothing re-lays-out under the
            // finger. The icon is then placed by hand below, which is the only way it can travel
            // smoothly rather than jumping the moment the gravity changes.
            quickTileRoot.gravity = Gravity.CENTER_VERTICAL

            // How far along the change is, as "how large does this look": 0 small, 1 large.
            val largeness = if (growing) fraction else 1f - fraction
            val isExtension = target.tile is QuickTile.Extension
            val fromDp = quickTileIconDp(!growing, isExtension)
            val toDp = quickTileIconDp(growing, isExtension)
            val density = root.resources.displayMetrics.density

            // Between centred in the tile (small) and sitting at the start padding (large).
            val iconWidth = fromDp * density
            val centred = (stretched - iconWidth) / 2f
            val start = quickTileRoot.paddingStart.toFloat()
            quickTileIcon.translationX = (centred - start) * (1f - largeness)
            val scale = 1f + (toDp.toFloat() / fromDp - 1f) * fraction
            quickTileIcon.scaleX = scale
            quickTileIcon.scaleY = scale

            // The handle is a sibling anchored to the cell, and the cell does not move while
            // the tile stretches inside it. Carry it along by hand or it sits at the old edge
            // until the finger lifts.
            quickTileResize.translationX = travel
        }
        shiftRowAfter(rv, position, travel)
    }

    /** Slides the tiles sharing a row with the one being stretched, so nothing overlaps. */
    private fun shiftRowAfter(rv: RecyclerView, position: Int, travel: Float) {
        val anchor = rv.findViewHolderForAdapterPosition(position)?.itemView ?: return
        for (i in position + 1 until placed.size) {
            val view = rv.findViewHolderForAdapterPosition(i)?.itemView ?: continue
            if (view.top != anchor.top) break
            view.translationX = travel
        }
    }

    /**
     * Ends a live resize: snaps to whichever size the finger is nearest and clears the temporary
     * stretch, so the grid takes over from a clean state.
     *
     * @return whether the tile ended up at a different size than it started.
     */
    private fun endStretch(target: PlacedTile, commit: Boolean): Boolean {
        val rv = recycler
        val start = dragStartSize
        val travel = dragStretch
        dragStretch = 0f
        dragBaseWidth = 0
        if (rv != null) {
            for (i in 0 until rv.childCount) rv.getChildAt(i).translationX = 0f
        }
        if (start == null) return false

        val column = if (rv != null && rv.width > 0) {
            (rv.width - rv.paddingLeft - rv.paddingRight) / QUICK_TILE_COLUMNS.toFloat()
        } else 1f
        val past = abs(travel) / column > 0.5f
        val wanted = if (commit && past) {
            if (start == TileSize.SMALL) TileSize.LARGE else TileSize.SMALL
        } else start

        // Rebind unconditionally: the stretched width and the label's part-way alpha are set
        // straight on the views and only a fresh bind puts them back.
        if (target.size != wanted) {
            applySize(target, wanted, DRAG_SETTLE_MS, record = true)
            return true
        }
        rebuild()
        return false
    }

    /**
     * Moves a tile to [size] and animates the grid around it. Nothing is persisted here — a resize
     * drag can cross back and forth, and only the size the finger is left on should be written.
     */
    private fun applySize(target: PlacedTile, size: TileSize, duration: Long, record: Boolean = false) {
        if (target.size == size) return
        if (record) snapshot()
        recycler?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val from = target.size
        target.size = size
        animateNext = Resize(target, from, duration)
        // The tiles that get pushed along should keep pace with the tile being resized, so a drag
        // reflows quickly and a tap unhurriedly.
        (recycler?.itemAnimator as? SimpleItemAnimator)?.apply {
            changeDuration = duration
            moveDuration = duration
        }
        // notifyItemChanged, not a full rebuild: the surrounding tiles then slide to their new
        // places instead of blinking there.
        val position = rows.indexOfFirst { it is Row.Placed && it.placed === target }
        rows = buildRows()
        if (position >= 0) {
            notifyItemChanged(position)
            refreshDecorations()
        } else rebuild()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
        // Change animations off so a resize rebinds the same ViewHolder instead of cross-fading
        // into a new one: a resize drag has to keep receiving touches on the very view it started
        // on, and a swapped-out view would drop the gesture halfway through. Move animations are
        // unaffected, so the tiles around it still slide.
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
    }

    private sealed interface Row {
        class Placed(val placed: PlacedTile) : Row
        class Category(val category: TileCategory) : Row
        class Shelf(val tile: QuickTile) : Row
    }

    private var rows: List<Row> = buildRows()

    private fun buildRows(): List<Row> = buildList {
        placed.forEach { add(Row.Placed(it)) }
        // Fixed catalogue order, so a tile never moves around the shelf as the panel changes.
        catalogue.categories().forEach { category ->
            val tiles = catalogue.all.filter { it.category == category }
            if (tiles.isEmpty()) return@forEach
            add(Row.Category(category))
            tiles.forEach { add(Row.Shelf(it)) }
        }
    }

    /**
     * Re-asks the border decoration for its insets.
     *
     * Only a rebind refreshes an item's cached decoration insets, and a move or a span change
     * rebinds nothing — so the tile that used to open or close the arrangement kept the padding
     * that belongs to the first and last rows, leaving a gap in the middle of the grid and none
     * at its edge. A full [rebuild] rebinds everything and needs no help.
     */
    private fun refreshDecorations() {
        recycler?.invalidateItemDecorations()
    }

    private fun rebuild() {
        rows = buildRows()
        @Suppress("NotifyDataSetChanged") // Spans change with size and membership; diffing buys nothing.
        notifyDataSetChanged()
    }

    fun placedTiles(): List<PlacedTile> = placed

    /** How many leading rows belong to the arrangement, for the border decoration. */
    fun placedCount() = placed.size

    /** Takes the selected tile off the panel; no-op when nothing is selected. */
    fun removeSelected() {
        val target = selected ?: return
        snapshot()
        placed.remove(target)
        select(null)
        onArrangementChanged(placed)
        rebuild()
    }

    /** Back to the arrangement a fresh install would have. */
    fun reset() {
        snapshot()
        placed.clear()
        placed += catalogue.defaults().map { PlacedTile(it.tile, it.size) }
        select(null)
        onArrangementChanged(placed)
        rebuild()
    }

    private fun select(target: PlacedTile?) {
        selected = target
        onSelectionChanged(target)
    }

    fun spanSizeLookup() = object : GridLayoutManager.SpanSizeLookup() {
        init {
            // Sizes and membership change under the same positions, so a cached index goes stale.
            isSpanIndexCacheEnabled = false
        }

        override fun getSpanSize(position: Int) = when (val row = rows.getOrNull(position)) {
            is Row.Placed -> row.placed.size.columns
            // Shelf tiles are always full width: the shelf is a catalogue, not a preview.
            is Row.Shelf -> TileSize.LARGE.columns
            else -> QUICK_TILE_COLUMNS
        }
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) =
        if (rows[position] is Row.Category) TYPE_HEADER else TYPE_TILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemQuickTileHeaderBinding.inflate(inflater, parent, false))
        } else {
            TileHolder(ItemQuickTileBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Category -> (holder as HeaderHolder).binding.quickTileHeader
                .setText(row.category.label)

            is Row.Placed -> (holder as TileHolder).bindPlaced(row.placed)
            is Row.Shelf -> (holder as TileHolder).bindShelf(row.tile)
        }
    }

    private class HeaderHolder(val binding: ItemQuickTileHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    /**
     * Grows or shrinks a tile's contents into their new shape.
     *
     * The grid itself is animated by the RecyclerView — [notifyItemChanged] on a span change slides
     * the following tiles across rather than snapping them. What that cannot do is the inside of
     * the tile, since the rebind has already applied the new icon size and label visibility: this
     * puts them back where they were for one frame and animates forward from there.
     */
    private fun ItemQuickTileBinding.playResizeAnimation(target: PlacedTile, large: Boolean) {
        val pending = animateNext?.takeIf { it.tile === target }
        // Recycled holders inherit whatever the last tile was mid-animation.
        quickTileIcon.animate().cancel()
        quickTileText.animate().cancel()
        if (pending == null) {
            quickTileIcon.scaleX = 1f
            quickTileIcon.scaleY = 1f
            quickTileIcon.translationX = 0f
            quickTileText.alpha = 1f
            return
        }
        animateNext = null

        val isExtension = target.tile is QuickTile.Extension
        val fromDp = quickTileIconDp(pending.from == TileSize.LARGE, isExtension)
        val toDp = quickTileIconDp(large, isExtension)
        val startScale = fromDp.toFloat() / toDp
        quickTileIcon.scaleX = startScale
        quickTileIcon.scaleY = startScale

        // The icon also changes where it sits — centred on a small tile, at the start padding on a
        // large one — and the rebind has already moved it. Put it back at the old spot for a frame
        // so it travels rather than teleporting. Measured on the pre-draw pass because the new
        // width is not known until this layout runs.
        val interpolator =
            if (pending.durationMs >= TAP_RESIZE_MS) OvershootInterpolator(1.4f)
            else DecelerateInterpolator()
        root.doOnPreDraw {
            val start = quickTileRoot.paddingStart.toFloat()
            val centred = (quickTileRoot.width - quickTileIcon.width) / 2f
            quickTileIcon.translationX = if (large) centred - start else start - centred
            quickTileIcon.animate()
                .translationX(0f)
                .setDuration(pending.durationMs)
                .setInterpolator(interpolator)
                .start()
        }

        quickTileIcon.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(pending.durationMs)
            .setInterpolator(interpolator)
            .start()

        if (large) {
            quickTileText.alpha = 0f
            quickTileText.animate()
                .alpha(1f)
                .setDuration(pending.durationMs * 2 / 3)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            // The rebind has already hidden the label. Put it back for the length of the fade and
            // hold the wide layout's gravity, so the text dissolves as the tile narrows instead of
            // vanishing on the first frame; the icon settles to centre once it is gone.
            quickTileText.alpha = 1f
            quickTileText.isVisible = true
            quickTileRoot.gravity = Gravity.CENTER_VERTICAL
            quickTileText.animate()
                .alpha(0f)
                .setDuration(pending.durationMs * 2 / 3)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    quickTileText.isVisible = false
                    quickTileText.alpha = 1f
                    quickTileRoot.gravity = Gravity.CENTER
                }
                .start()
        }
    }

    private inner class TileHolder(val binding: ItemQuickTileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("ClickableViewAccessibility") // The tap path delegates to performClick().
        fun bindPlaced(target: PlacedTile) = with(binding) {
            paintQuickTile(host, target, editing = true, dimmed = false)
            val isSelected = target === selected
            quickTileRoot.foreground = if (isSelected) {
                ContextCompat.getDrawable(root.context, R.drawable.quick_tile_selected_outline)
            } else null
            val large = target.size == TileSize.LARGE
            quickTileResize.isVisible = isSelected
            quickTileBadge.isVisible = false
            // Keep the label clear of the handle rather than running underneath it.
            quickTileText.updatePaddingRelative(
                end = if (isSelected) 30.dpToPx(root).toInt() else 0
            )
            playResizeAnimation(target, large)

            quickTileRoot.isClickable = true
            quickTileRoot.setOnClickListener {
                select(if (isSelected) null else target)
                rebuild()
            }
            // Let ItemTouchHelper pick the long-press up as the start of a drag.
            quickTileRoot.setOnLongClickListener { false }
        }

        fun bindShelf(tile: QuickTile) = with(binding) {
            val alreadyPlaced = placed.any { it.tile.id == tile.id }
            val available = tile.isAvailable()
            val addable = !alreadyPlaced && available
            paintQuickTile(
                host, PlacedTile(tile, TileSize.LARGE),
                editing = true, dimmed = !addable,
            )
            // Say why it cannot be added rather than leaving a greyed tile with no explanation.
            if (!available && tile.unavailableReason != null) {
                quickTileState.isVisible = true
                quickTileState.setText(tile.unavailableReason)
            }
            quickTileResize.isVisible = false
            quickTileBadge.isVisible = addable
            quickTileBadge.imageTintList = ColorStateList.valueOf(
                root.context.getThemeColor(com.google.android.material.R.attr.colorOnPrimary)
            )
            quickTileRoot.isClickable = addable
            quickTileRoot.setOnTouchListener(null)

            val add = {
                root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                snapshot()
                placed += PlacedTile(tile, TileSize.LARGE)
                onArrangementChanged(placed)
                rebuild()
            }
            quickTileBadge.setOnClickListener { add() }
            quickTileRoot.setOnClickListener { if (addable) add() }
            quickTileRoot.setOnLongClickListener { false }
        }
    }

    /**
     * Claims touches near the selected tile's resize handle before anything else sees them.
     *
     * The handle is a small view on a tile's edge, so aiming at it lands either on the tile itself
     * — which picks it up for a reorder drag — or on the neighbour across the gutter, whose cell
     * owns those pixels and which no view inside this tile can ever receive. The list is the only
     * coordinate space wide enough to cover both sides of that border, and claiming the gesture
     * here also stops [ItemTouchHelper] from starting a drag.
     *
     * Must be registered before the drag helper: the first listener to claim a gesture wins.
     */
    fun handleTouchListener() = object : RecyclerView.OnItemTouchListener {
        private var target: PlacedTile? = null
        private var targetPosition = -1

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (e.actionMasked != MotionEvent.ACTION_DOWN) return target != null
            target = null
            val chosen = selected ?: return false
            val position = placed.indexOf(chosen).takeIf { it >= 0 } ?: return false
            val view = rv.findViewHolderForAdapterPosition(position)?.itemView ?: return false

            // The pill's centre: the tile's trailing edge, halfway down.
            val centreX = view.right - GUTTER_DP.dpToPx(rv)
            val centreY = view.top + view.height / 2f
            // Kept to the tile's own row: a reach past it would answer for touches on the row
            // below, which owns those pixels.
            if (abs(e.y - centreY) > view.height / 2f) return false
            if (abs(e.x - centreX) > HANDLE_REACH_DP.dpToPx(rv)) return false

            target = chosen
            targetPosition = position
            dragStartX = e.rawX
            dragStartSize = chosen.size
            dragMoved = false
            dragStretch = 0f
            dragBaseWidth = 0
            rv.parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            val chosen = target ?: return
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - dragStartX
                    if (abs(dx) > ViewConfiguration.get(rv.context).scaledTouchSlop) {
                        dragMoved = true
                    }
                    stretch(targetPosition, chosen, dx)
                }

                MotionEvent.ACTION_UP -> {
                    if (dragMoved) {
                        if (endStretch(chosen, commit = true)) onArrangementChanged(placed)
                    } else {
                        // A tap on the handle flips the size too. The system panel does not offer
                        // that, but it costs nothing and saves a deliberate drag.
                        endStretch(chosen, commit = false)
                        val next =
                            if (chosen.size == TileSize.LARGE) TileSize.SMALL else TileSize.LARGE
                        applySize(chosen, next, TAP_RESIZE_MS, record = true)
                        onArrangementChanged(placed)
                    }
                    target = null
                    dragStartSize = null
                }

                MotionEvent.ACTION_CANCEL -> {
                    endStretch(chosen, commit = false)
                    target = null
                    dragStartSize = null
                }
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) = Unit
    }

    /**
     * Drag to reorder inside the arrangement, or drag a tile down onto the shelf to take it off
     * the panel.
     *
     * The removal is deferred to [ItemTouchHelper.Callback.clearView] rather than done the moment
     * the shelf is crossed: pulling the ViewHolder being dragged out from under ItemTouchHelper
     * mid-gesture leaves it animating a view that no longer has a position.
     */
    fun touchHelper() = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        private var dropOntoShelf: PlacedTile? = null
        private var reorderRecorded = false

        override fun isLongPressDragEnabled() = true

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ): Int {
            val draggable = viewHolder.bindingAdapterPosition in placed.indices
            return makeMovementFlags(
                if (draggable) {
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                            ItemTouchHelper.START or ItemTouchHelper.END
                } else 0,
                0,
            )
        }

        // Shelf rows included, so that hovering one registers as "drop here to remove".
        override fun canDropOver(
            recyclerView: RecyclerView,
            current: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ) = true

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from !in placed.indices) return false
            if (to !in placed.indices) {
                // Over the shelf: remember it, leave the grid alone until the finger lifts.
                dropOntoShelf = placed.getOrNull(from)
                return false
            }
            dropOntoShelf = null
            // Once per drag, before the first move: undo should step back over the whole gesture
            // rather than each hop of it, and the arrangement is written out as the finger lifts.
            if (!reorderRecorded) {
                snapshot()
                reorderRecorded = true
            }
            placed.add(to, placed.removeAt(from))
            rows = buildRows()
            notifyItemMoved(from, to)
            refreshDecorations()
            return true
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            val removed = dropOntoShelf
            dropOntoShelf = null
            if (removed != null && !reorderRecorded) snapshot()
            val reordered = reorderRecorded
            reorderRecorded = false
            if (removed != null && placed.remove(removed)) {
                viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (selected === removed) select(null)
                rebuild()
            } else if (reordered) {
                // Lay the grid out from scratch once the finger is up.
                //
                // Each hop of a drag is a notifyItemMoved, which leaves the row heights to be
                // patched up from the previous layout rather than recomputed. A drag that crosses
                // between two rows repeatedly compounds those patches, and the rows drift further
                // apart the longer it goes on. A rebuild costs one relayout at the end of a
                // gesture and puts the grid back on the geometry the arrangement actually implies.
                rebuild()
            }
            onArrangementChanged(placed)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
    })

    private fun Int.dpToPx(view: View) = this * view.resources.displayMetrics.density

    private companion object {
        const val TYPE_TILE = 0
        const val TYPE_HEADER = 1

        const val HISTORY_LIMIT = 20

        /** How far the handle must travel before a press counts as a resize instead of a tap. */
        const val RESIZE_DRAG_DP = 28

        /** Half the item's 8dp gutter: the distance from a tile's edge to its cell's edge. */
        const val GUTTER_DP = 4

        /**
         * How far from the pill a touch still counts as grabbing it. Deliberately larger than the
         * pill and reaching past the tile into the gutter and the neighbour's edge, which is the
         * whole reason this is measured against the list rather than the handle view.
         *
         * It has to stop well short of the neighbour's middle, though: the list claims the whole
         * gesture here, so anything this box covers can no longer be tapped to select or dragged
         * to reorder. At 40dp it took most of the tile beside the selected one with it.
         */
        const val HANDLE_REACH_DP = 22

        // Timed off a screen recording of the Android 17 panel resizing a tile: the bulk of the
        // motion runs ~300ms, followed by a long sub-pixel settle that gives away a spring. The
        // overshoot below stands in for that settle without pulling in a physics animator.
        const val TAP_RESIZE_MS = 300L

        // A drag has the finger to follow, so the only animation left is the snap on release.
        const val DRAG_RESIZE_MS = 130L
        const val DRAG_SETTLE_MS = 160L
    }
}
