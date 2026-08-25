package ani.dantotsu.settings.quicktiles

import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ViewTilePanelBinding

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
        val adapter = QuickTilePagerAdapter(host, pages, offline) { setEditing(true) }
        binding.quickTilesPager.adapter = adapter
        binding.quickTilesPager.updateLayoutParams {
            height = adapter.maxRows() *
                    quickTileRowHeightPx(binding.root.resources.displayMetrics.density)
        }
        binding.quickTilesIndicator.attachTo(binding.quickTilesPager, pages.size)
    }

    private fun setEditing(value: Boolean) {
        editing = value
        detachEditor()
        updateFooterVisibility()
        binding.quickTilesPager.isVisible = !value
        binding.quickTilesIndicator.isVisible =
            !value && (binding.quickTilesPager.adapter?.itemCount ?: 0) > 1
        binding.quickTilesEdit.isVisible = !value
        binding.quickTilesEditContainer.isVisible = value
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
        binding.quickTilesEditor.addItemDecoration(QuickTileArrangementBorder(editor))
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
