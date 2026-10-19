package ani.dantotsu.settings.quicktiles

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemQuickTileBinding
import ani.dantotsu.databinding.ViewTilePanelBinding
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.animationsEnabled
import kotlin.math.abs

/** The swap between the tile pages and the editor, at animation speed 1. */
private const val EDIT_SWAP_MS = 300L

/** How much longer the swap takes per dp the panel's height travels, and the most it takes. */
private const val HEIGHT_MS_PER_DP = 0.3f
private const val HEIGHT_MAX_MS = 450f

/**
 * Drives one tile panel: its pages, its editor, and the switch between them.
 *
 * All of it works off a [TileCatalogue], so the quick-settings and search sheets are the same
 * screen with different tiles in it rather than two implementations to keep in step.
 */
class TilePanelController(
    private val binding: ViewTilePanelBinding,
    private val catalogue: TileCatalogue,
    private val host: QuickTileHost,
    /** Unusable tiles are greyed in place rather than dropped; see [QuickTile.isUsable]. */
    private val offline: Boolean,
) {
    private var editing = false

    // The editor is built fresh on every entry into edit mode, and its gesture handling lives on
    // the RecyclerView rather than on the adapter. Held so the previous pass can be taken off:
    // touch listeners, unlike the adapter and the decorations, stack up. A stale pair kept the
    // first claim on every gesture and wrote its own out-of-date arrangement back to the
    // catalogue, which is what made a second visit to the editor stop responding.
    private var editTouchListener: RecyclerView.OnItemTouchListener? = null
    private var editTouchHelper: ItemTouchHelper? = null

    fun attach() {
        showPages()
        binding.quickTilesEdit.setOnClickListener { setEditing(true) }
        binding.quickTilesEditDone.setOnClickListener { setEditing(false) }
    }

    /** Shown under the page dots; only the quick-settings sheet has anything to put here. */
    private var footerText: CharSequence? = null

    fun setFooterText(text: CharSequence?) {
        footerText = text
        binding.quickTilesVersion.text = text
        updateFooterVisibility()
    }

    /**
     * The footer names the app and its version, which belongs under the panel rather than under
     * the editor: while arranging tiles the space is the editor's, and a version number there is
     * one more thing between the shelf and the grid it is dragged onto.
     */
    private fun updateFooterVisibility() {
        binding.quickTilesVersion.isVisible = footerText != null && !editing
    }

    private fun showPages() {
        // Every placed tile is laid out, including ones that cannot work right now. Dropping them
        // would reflow the panel around a missing tile and reflow it back later, which reads far
        // worse than an inert one.
        val pages = TileCatalogue.paginate(
            catalogue.placed(), QUICK_TILE_COLUMNS, QUICK_TILE_ROWS,
        )
        this.pages = pages
        val adapter = QuickTilePagerAdapter(host, pages, offline) { setEditing(true) }
        binding.quickTilesPager.adapter = adapter
        binding.quickTilesPager.updateLayoutParams {
            height = adapter.maxRows() *
                    quickTileRowHeightPx(binding.root.resources.displayMetrics.density)
        }
        binding.quickTilesIndicator.attachTo(binding.quickTilesPager, pages.size)
    }

    /** What the pager is showing, page by page; read to match its tiles up with the editor's. */
    private var pages: List<List<PlacedTile>> = emptyList()

    /** The live editor and its outline, while there is one. */
    private var editorAdapter: QuickTileEditAdapter? = null
    private var arrangementBorder: QuickTileArrangementBorder? = null

    /** True while the pages and the editor are trading places; taps on edit/done wait for it. */
    private var swapping = false

    /**
     * Swaps the pages for the editor, or back, keeping the tiles that are on screen on both sides.
     *
     * A tile on the page being left that also shows on the side arriving — every tile of the current
     * page, going into the editor; the tiles of the page returned to, coming out — never goes away:
     * it slides, resizes and recolours from where it was to where it lands. Only what has no
     * counterpart fades: the other tiles and the shelves, the editor's toolbar and reset button, the
     * footer. Fading the whole panel out and back in made a tile that hadn't changed blink out and
     * reappear a few dozen pixels away.
     *
     * The panel's height moves under all of it, from the first frame and only as far as the sheet
     * visibly changes (see [visibleHeightCap]). It was once animated from above the sheet with a
     * TransitionManager, which flickered — overlays pinned to the screen while the sheet resized,
     * and the sheet's bounds animated against BottomSheetBehavior; only this panel's own views move
     * here, and the sheet, wrapping its content, follows by itself.
     */
    private fun setEditing(value: Boolean) {
        if (value == editing || swapping) return
        val frame = binding.quickTilesPager.parent as ViewGroup
        if (!animationsEnabled || !frame.isLaidOut) {
            // A footer faded out by an animated swap keeps its alpha while hidden.
            footerViews().forEach { it.alpha = 1f }
            prepare(value)
            show(value)
            return
        }
        swapping = true
        val speed = PrefManager.getVal<Float>(PrefName.AnimationSpeed)
        val incoming = if (value) binding.quickTilesEditContainer else binding.quickTilesPager

        // Where each tile on screen is now, before anything changes.
        val leaving = shownTiles()
        val snaps = leaving.mapValues { (_, view) -> TileSnap.of(view, frame) }

        prepare(value)
        incoming.measure(
            View.MeasureSpec.makeMeasureSpec(
                frame.width - frame.paddingLeft - frame.paddingRight, View.MeasureSpec.EXACTLY,
            ),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val fullTo = frame.paddingTop + frame.paddingBottom + incoming.measuredHeight
        // Only the part of the change that shows is animated. The editor with its shelves runs past
        // the screen, the sheet tops out at full height and the rest scrolls — so most of a full
        // collapse happened out of sight, and the sheet edge only began to move at the very end.
        // Past [cap] the sheet is at its tallest; anything beyond it is changed in one step, unseen.
        val cap = visibleHeightCap(frame)
        val from = frame.height.coerceAtMost(cap)
        val to = fullTo.coerceAtMost(cap)
        val travelDp = abs(to - from) / frame.resources.displayMetrics.density
        val totalMs = ((EDIT_SWAP_MS + travelDp * HEIGHT_MS_PER_DP)
            .coerceAtMost(HEIGHT_MAX_MS) * speed).toLong()
        // One clock for all of it. The height runs the whole length and ends with the carried-over
        // tiles, on the same ease, so the sheet edge and the tiles settle on the same frame: the
        // space neither closes after the tiles are home nor finishes early and cuts off a row still
        // on its way back up from where the editor had it.
        val outMs = (totalMs * 0.35f).toLong()
        val inMs = totalMs - outMs
        frame.updateLayoutParams { height = from }
        val heightAnim = ValueAnimator.ofInt(from, to).apply {
            duration = totalMs
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { anim ->
                frame.updateLayoutParams { height = anim.animatedValue as Int }
            }
            doOnEnd {
                frame.updateLayoutParams { height = ViewGroup.LayoutParams.WRAP_CONTENT }
            }
            start()
        }

        // What carries over: everything on the page being left goes into the editor; coming out,
        // the tiles of the page the pager is about to show.
        val staying: Set<String> = if (value) snaps.keys else
            pages.getOrNull(binding.quickTilesPager.currentItem).orEmpty()
                .map { it.tile.id }.toSet()
        val fading = buildList {
            addAll(footerViews().filter { it.isVisible })
            if (!value) {
                addAll(editorChrome())
                leaving.forEach { (id, view) -> if (id !in staying) add(view) }
                addAll(editorRows().filter { row -> leaving.values.none { it === row } })
            }
        }
        fading.forEach {
            it.animate().alpha(0f).setDuration(outMs)
                .setInterpolator(FastOutLinearInInterpolator()).start()
        }
        if (!value) fadeBorder(to = 0f, ms = outMs)

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = outMs
            doOnEnd {
                if (!frame.isAttachedToWindow) {
                    swapping = false
                    return@doOnEnd
                }
                show(value)
                // Hidden now; put them back so the next visit doesn't start with invisible views.
                fading.forEach { it.animate().cancel(); it.alpha = 1f }
                frame.doOnPreDraw {
                    arrive(value, snaps, frame, inMs, heightAnim.takeIf { from != to })
                }
            }
            start()
        }
    }

    /**
     * The incoming side's first frame: carried-over tiles start where they were and travel home,
     * everything else fades up. Runs before that frame is drawn, so nothing shows in between.
     *
     * While the panel's height is still moving ([heightAnim]), the tiles cover what is left of their
     * way in step with what is left of the height, not on a clock of their own. By the time they
     * start, the height has done most of its travel on its fast-out ease, so on their own clock the
     * sheet edge came to rest with the tiles still well short of home: they visibly slid down under
     * a sheet that had already stopped.
     */
    private fun arrive(
        value: Boolean,
        snaps: Map<String, TileSnap>,
        frame: View,
        inMs: Long,
        heightAnim: ValueAnimator?,
    ) {
        val arriving = shownTiles()
        val fadeIn = mutableListOf<View>()
        val unclipped = mutableMapOf<ViewGroup, Pair<Boolean, Boolean>>()
        val morphs = mutableListOf<(Float) -> Unit>()
        arriving.forEach { (id, view) ->
            val snap = snaps[id]
            if (snap != null) {
                unclipUpTo(view, frame, unclipped)
                morphs += snap.morphInto(view, frame)
            } else {
                fadeIn += view
            }
        }
        if (value) {
            fadeIn += editorChrome()
            fadeIn += editorRows().filter { row -> arriving.values.none { it === row } }
            fadeBorder(from = 0f, to = 1f, ms = inMs)
        } else {
            fadeIn += footerViews().filter { it.isVisible }
        }
        fadeIn.distinct().forEach {
            it.animate().cancel()
            it.alpha = 0f
            it.animate().alpha(1f).setDuration(inMs)
                .setInterpolator(FastOutSlowInInterpolator()).start()
        }
        val heightStart = heightAnim?.takeIf { it.isRunning }?.animatedFraction
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = inMs
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { anim ->
                val f = if (heightAnim != null && heightStart != null && heightStart < 1f) {
                    if (heightAnim.isRunning) {
                        ((heightAnim.animatedFraction - heightStart) / (1f - heightStart))
                            .coerceIn(0f, 1f)
                    } else 1f
                } else anim.animatedValue as Float
                morphs.forEach { it(f) }
            }
            doOnEnd {
                morphs.forEach { it(1f) }
                unclipped.forEach { (group, clip) ->
                    group.clipChildren = clip.first
                    group.clipToPadding = clip.second
                }
                swapping = false
            }
            start()
        }
    }

    /** Where a tile sat, relative to the panel, and what it looked like. */
    private class TileSnap(
        val x: Float, val y: Float, val w: Int, val h: Int,
        val background: Int?, val icon: Int?, val text: Int, val alpha: Float,
    ) {
        /**
         * Starts [view] — the same tile on the other side — looking like this snapshot, and returns
         * the step that takes it from there (0) to how it really is (1).
         *
         * Positions are taken against the panel rather than the window: the sheet moves while the
         * height changes, and window coordinates captured before the swap would be off by however
         * far it had travelled, putting a jump into the very tiles meant to stay put.
         */
        fun morphInto(view: View, frame: View): (Float) -> Unit {
            val (x1, y1) = positionIn(view, frame)
            view.animate().cancel()
            view.pivotX = 0f
            view.pivotY = 0f
            val dx = x - x1
            val dy = y - y1
            val sx = if (view.width > 0) w.toFloat() / view.width else 1f
            val sy = if (view.height > 0) h.toFloat() / view.height else 1f

            // A lit toggle on the pages is plain in the editor, a greyed tile is not greyed
            // there: blended rather than switched, or the tile would flash as it changed sides.
            val tile = ItemQuickTileBinding.bind(view)
            val toBackground = tile.quickTileRoot.backgroundTintList?.defaultColor
            val toIcon = tile.quickTileIcon.imageTintList?.defaultColor
            val toText = tile.quickTileLabel.currentTextColor
            val toAlpha = tile.quickTileRoot.alpha
            val argb = ArgbEvaluator()
            val step = { f: Float ->
                val r = 1f - f
                view.translationX = dx * r
                view.translationY = dy * r
                view.scaleX = sx + (1f - sx) * f
                view.scaleY = sy + (1f - sy) * f
                if (background != null && toBackground != null) {
                    tile.quickTileRoot.backgroundTintList = ColorStateList.valueOf(
                        argb.evaluate(f, background, toBackground) as Int
                    )
                }
                if (icon != null && toIcon != null) {
                    tile.quickTileIcon.imageTintList = ColorStateList.valueOf(
                        argb.evaluate(f, icon, toIcon) as Int
                    )
                }
                val textColor = argb.evaluate(f, text, toText) as Int
                tile.quickTileLabel.setTextColor(textColor)
                tile.quickTileState.setTextColor(textColor)
                tile.quickTileRoot.alpha = alpha + (toAlpha - alpha) * f
            }
            step(0f)
            return step
        }

        companion object {
            fun of(view: View, frame: View): TileSnap {
                val (x, y) = positionIn(view, frame)
                val tile = ItemQuickTileBinding.bind(view)
                return TileSnap(
                    x, y, view.width, view.height,
                    tile.quickTileRoot.backgroundTintList?.defaultColor,
                    tile.quickTileIcon.imageTintList?.defaultColor,
                    tile.quickTileLabel.currentTextColor,
                    tile.quickTileRoot.alpha,
                )
            }

            /** [view]'s laid-out top-left in [frame]'s coordinates, ignoring any running animation. */
            fun positionIn(view: View, frame: View): Pair<Float, Float> {
                val a = IntArray(2).also(view::getLocationInWindow)
                val b = IntArray(2).also(frame::getLocationInWindow)
                return (a[0] - b[0] - view.translationX) to (a[1] - b[1] - view.translationY)
            }
        }
    }

    /**
     * The tiles on screen right now, by tile id: the current page's while the pages show, the
     * arrangement's (not the shelf's) while the editor does.
     */
    private fun shownTiles(): Map<String, View> {
        if (editing) {
            val editor = editorAdapter ?: return emptyMap()
            val list = binding.quickTilesEditor
            return list.children.mapNotNull { view ->
                val position = list.getChildAdapterPosition(view)
                if (position !in 0 until editor.placedCount()) return@mapNotNull null
                editor.placedTiles().getOrNull(position)?.let { it.tile.id to view }
            }.toMap()
        }
        val pager = binding.quickTilesPager
        val page = pages.getOrNull(pager.currentItem) ?: return emptyMap()
        val holder = (pager.getChildAt(0) as? RecyclerView)
            ?.findViewHolderForAdapterPosition(pager.currentItem)
            as? QuickTilePagerAdapter.PageHolder ?: return emptyMap()
        val grid = holder.binding.quickTilePageRecycler
        return grid.children.mapNotNull { view ->
            page.getOrNull(grid.getChildAdapterPosition(view))?.let { it.tile.id to view }
        }.toMap()
    }

    /**
     * Lets [view] draw outside its parents, up to and including [frame], while it travels.
     *
     * A carried-over tile starts from where it was on the other side, which can be outside the
     * containers it now lives in: in the editor the arrangement sits a toolbar's height lower than
     * the pages do, so coming back the last row started below the pager's bottom edge and was cut
     * off until it had climbed back inside. Each group's previous settings go into [saved] so they
     * can be put back once the move ends.
     */
    private fun unclipUpTo(view: View, frame: View, saved: MutableMap<ViewGroup, Pair<Boolean, Boolean>>) {
        var parent = view.parent as? ViewGroup
        while (parent != null) {
            if (parent !in saved) {
                saved[parent] = parent.clipChildren to parent.clipToPadding
                parent.clipChildren = false
                parent.clipToPadding = false
            }
            if (parent === frame) break
            parent = parent.parent as? ViewGroup
        }
    }

    /** Every row of the editor's list: arrangement, shelf headers and shelf tiles. */
    private fun editorRows(): List<View> = binding.quickTilesEditor.children.toList()

    /** The editor's own controls around its list: the toolbar row and the reset button. */
    private fun editorChrome(): List<View> =
        binding.quickTilesEditContainer.children.filter { it !== binding.quickTilesEditor }.toList()

    /** Fades the outline round the arrangement, which the list draws rather than a view. */
    private fun fadeBorder(from: Float? = null, to: Float, ms: Long) {
        val border = arrangementBorder ?: return
        ValueAnimator.ofFloat(from ?: border.alpha, to).apply {
            duration = ms
            addUpdateListener {
                border.alpha = it.animatedValue as Float
                binding.quickTilesEditor.invalidate()
            }
            start()
        }
    }

    /**
     * The tallest [frame] can be with every pixel of it changing what's on screen: the height at
     * which the sheet reaches the top of its window. Beyond it the sheet stays put and the extra
     * only adds to what the sheet's scroll view hides.
     */
    private fun visibleHeightCap(frame: View): Int {
        val scroll = binding.root as? ViewGroup ?: return Int.MAX_VALUE
        val content = scroll.getChildAt(0) ?: return Int.MAX_VALUE
        val sheet = scroll.parent as? View ?: return Int.MAX_VALUE
        val window = sheet.parent as? View ?: return Int.MAX_VALUE
        // How far the content already runs past the sheet, or how much room the sheet still has
        // to grow; only one of the two is ever non-zero.
        val overflow = (content.height + scroll.paddingTop + scroll.paddingBottom - scroll.height)
            .coerceAtLeast(0)
        // Edge to edge, the sheet pads itself below the status bar once it reaches the top, so it
        // tops out that much lower than the window's edge. Without this the last stretch of the
        // height ran on unseen after the sheet had stopped, and the tiles still easing home on the
        // same clock looked pushed down only once it had settled.
        val loc = IntArray(2).also(window::getLocationOnScreen)
        // The camera cutout counts as much as the status bar: with the status bar hidden it is all
        // the sheet pads for, and it is what WSA, with neither, never showed.
        val topInset = ViewCompat.getRootWindowInsets(sheet)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )?.top ?: 0
        val padding = ((topInset - loc[1]).coerceAtLeast(0) - sheet.paddingTop).coerceAtLeast(0)
        val room = (window.height - sheet.height - padding).coerceAtLeast(0)
        return frame.height - overflow + room
    }

    private fun footerViews() =
        listOf(binding.quickTilesEdit, binding.quickTilesIndicator, binding.quickTilesVersion)

    /** Swaps which side is showing. Whatever is coming in has already been built by [prepare]. */
    private fun show(value: Boolean) {
        editing = value
        updateFooterVisibility()
        binding.quickTilesPager.isVisible = !value
        binding.quickTilesIndicator.isVisible =
            !value && (binding.quickTilesPager.adapter?.itemCount ?: 0) > 1
        binding.quickTilesEdit.isVisible = !value
        binding.quickTilesEditContainer.isVisible = value
    }

    /** Builds the side about to come in, out of sight, so it can be measured before the swap. */
    private fun prepare(value: Boolean) {
        detachEditor()
        if (!value) {
            // Re-paginate: sizes and membership may both have changed under the editor.
            showPages()
            return
        }

        val editor = QuickTileEditAdapter(
            host,
            catalogue = catalogue,
            initial = catalogue.placed(),
            onArrangementChanged = { catalogue.save(it) },
            // Nothing selected means nothing to remove; Android greys the button out too.
            onSelectionChanged = { binding.quickTilesRemove.isEnabled = it != null },
            onHistoryChanged = { binding.quickTilesUndo.isVisible = it },
        )
        binding.quickTilesRemove.isEnabled = false
        binding.quickTilesUndo.isVisible = false
        binding.quickTilesRemove.setOnClickListener { editor.removeSelected() }
        binding.quickTilesReset.setOnClickListener { editor.reset() }
        binding.quickTilesUndo.setOnClickListener { editor.undo() }

        // Predictive animations off: they run a pre-layout pass over the positions as they were
        // before the change, and a tile whose position cannot be mapped back is assumed to span a
        // single column. With tiles of two widths that guess puts the pre-layout row boundaries
        // somewhere the real ones are not, and the grid is left holding rows it does not need.
        val manager = object : GridLayoutManager(binding.root.context, QUICK_TILE_COLUMNS) {
            override fun supportsPredictiveItemAnimations() = false
        }
        manager.spanSizeLookup = editor.spanSizeLookup()
        binding.quickTilesEditor.layoutManager = manager
        binding.quickTilesEditor.adapter = editor
        while (binding.quickTilesEditor.itemDecorationCount > 0) {
            binding.quickTilesEditor.removeItemDecorationAt(0)
        }
        editorAdapter = editor
        arrangementBorder = QuickTileArrangementBorder(editor).also {
            binding.quickTilesEditor.addItemDecoration(it)
        }
        // Before the drag helper: the first listener to claim a gesture keeps it, and a touch on
        // the resize handle must not turn into a reorder drag.
        editTouchListener = editor.handleTouchListener().also {
            binding.quickTilesEditor.addOnItemTouchListener(it)
        }
        editTouchHelper = editor.touchHelper().also {
            it.attachToRecyclerView(binding.quickTilesEditor)
        }
    }

    /** Unhooks the previous editor's gesture handling, so only the live one sees touches. */
    private fun detachEditor() {
        editTouchListener?.let { binding.quickTilesEditor.removeOnItemTouchListener(it) }
        editTouchListener = null
        editTouchHelper?.attachToRecyclerView(null)
        editTouchHelper = null
    }
}

/** Convenience for a panel whose tiles never need to reach back into the host sheet. */
fun tileHostOf(
    activity: androidx.fragment.app.FragmentActivity,
    dismiss: () -> Unit,
) = QuickTileHost(
    activity = activity,
    dismiss = dismiss,
    // Only the quick-settings panel owns the offline switch; nothing in another catalogue has one.
    setOfflineMode = { },
)
