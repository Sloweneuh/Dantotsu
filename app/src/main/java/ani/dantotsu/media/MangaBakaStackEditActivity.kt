package ani.dantotsu.media

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.connections.mangabaka.MangaBakaStacks
import ani.dantotsu.databinding.ActivityMangabakaStackEditBinding
import ani.dantotsu.databinding.ItemStackEditEntryBinding
import ani.dantotsu.databinding.ItemStackEditHeaderBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.settings.SectionCard
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.materialDialog
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Creates or edits a MangaBaka stack: title, Markdown description, content rating, visibility,
 * spoiler and comment flags, and the ordered series list — drag to reorder, tap to write a note,
 * search to add.
 *
 * An edit sends the whole stack back in one `PUT` with the `token` from when it was read; if the
 * stack changed elsewhere since (a 409), the user is offered a reload rather than overwriting it.
 * A new stack is created as a draft and then published separately when another visibility was
 * picked, since that's how the API splits it.
 */
class MangaBakaStackEditActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMangabakaStackEditBinding
    private lateinit var header: ItemStackEditHeaderBinding

    private var stackId: Long? = null
    private var token: String? = null
    private var minRating: String? = null
    private var dirty = false
    private var saving = false

    private val entries = ArrayList<EditEntry>()
    private val entriesAdapter = EntriesAdapter()

    /** One row of the series list, with what's needed to draw it and send it back. */
    data class EditEntry(val seriesId: Long, val title: String, val cover: String?, var note: String?)

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityMangabakaStackEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)
        binding.stackEditBar.root.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = statusBarHeight }
        binding.stackEditRecycler.updatePadding(bottom = navBarHeight + 24f.px)

        // Before the header is inflated against the RecyclerView: its layout params come from the
        // LayoutManager, and inflating without one throws.
        binding.stackEditRecycler.layoutManager = LinearLayoutManager(this)
        header = ItemStackEditHeaderBinding.inflate(layoutInflater, binding.stackEditRecycler, false)
        stackId = intent.getLongExtra(EXTRA_STACK_ID, -1L).takeIf { it > 0 }
        binding.stackEditBar.formBarTitle.setText(if (stackId == null) R.string.stack_new else R.string.stack_edit)
        binding.stackEditBar.formBarPrimary.apply {
            setText(if (stackId == null) R.string.form_action_create else R.string.save)
            setIconResource(R.drawable.ic_round_check_24)
        }

        setUpChips(rating = "safe", visibility = "draft")
        header.stackEditName.doAfterTextChanged {
            dirty = true
            header.stackEditNameLayout.error = null
        }
        header.stackEditDescription.doAfterTextChanged { dirty = true }
        header.stackEditSpoiler.setOnCheckedChangeListener { _, _ -> dirty = true }
        header.stackEditComments.setOnCheckedChangeListener { _, _ -> dirty = true }
        header.stackEditAddSeries.setOnClickListener { showSeriesSearch() }

        binding.stackEditRecycler.adapter = entriesAdapter
        touchHelper.attachToRecyclerView(binding.stackEditRecycler)

        binding.stackEditBar.formBarClose.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.stackEditBar.formBarPrimary.setOnClickListener { save() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!dirty) {
                    finish()
                    return
                }
                materialDialog()
                    .setTitle(R.string.stack_discard_title)
                    .setMessage(R.string.stack_discard_message)
                    .setPositiveButton(R.string.stack_discard) { _, _ -> finish() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        })

        val id = stackId
        if (id != null) {
            load(id)
        } else {
            val seriesId = intent.getLongExtra(EXTRA_SERIES_ID, -1L)
            if (seriesId > 0) {
                entries.add(
                    EditEntry(seriesId, intent.getStringExtra(EXTRA_SERIES_TITLE) ?: "", intent.getStringExtra(EXTRA_SERIES_COVER), null)
                )
            }
            updateEntriesTitle()
        }
    }

    private fun load(id: Long) {
        binding.stackEditProgress.isVisible = true
        binding.stackEditRecycler.isVisible = false
        lifecycleScope.launch {
            when (val result = MangaBakaStacks.getMyStack(id)) {
                is MangaBakaStacks.Result.Ok -> fill(result.value)
                is MangaBakaStacks.Result.Error -> {
                    MangaBakaStackUi.reportError(result)
                    finish()
                }
            }
        }
    }

    private fun fill(stack: MangaBakaStacks.StackDetail) {
        token = stack.token
        minRating = stack.minContentRating
        header.stackEditName.setText(stack.title)
        header.stackEditDescription.setText(stack.description.orEmpty())
        header.stackEditSpoiler.isChecked = stack.isSpoiler || stack.isSpoilerLocked
        header.stackEditSpoiler.isEnabled = !stack.isSpoilerLocked
        header.stackEditComments.isChecked = stack.isCommentsEnabled
        setUpChips(stack.contentRating ?: "safe", stack.visibility ?: "draft")
        val size = entries.size
        entries.clear()
        entriesAdapter.notifyItemRangeRemoved(HEADER_ROWS, size)
        // Merged/deleted placeholders have no series; they can't be sent back, so they drop out.
        stack.entries.orEmpty().forEach { e ->
            val s = e.series ?: return@forEach
            entries.add(EditEntry(s.id, s.displayTitle() ?: "#${s.id}", s.cover?.thumbUrl(), e.note))
        }
        entriesAdapter.notifyItemRangeInserted(HEADER_ROWS, entries.size)
        updateEntriesTitle()
        dirty = false
        binding.stackEditProgress.isVisible = false
        binding.stackEditRecycler.isVisible = true
    }

    private fun setUpChips(rating: String, visibility: String) {
        val minIndex = MangaBakaStacks.CONTENT_RATINGS.indexOf(minRating).coerceAtLeast(0)
        header.stackEditRatingChips.removeAllViews()
        MangaBakaStacks.CONTENT_RATINGS.forEachIndexed { i, value ->
            header.stackEditRatingChips.addView(chip(MangaBakaStackUi.contentRatingLabel(value), value, value == rating).apply {
                // A moderator's floor: the stack can't be rated below it.
                isEnabled = i >= minIndex
            })
        }
        header.stackEditVisibilityChips.removeAllViews()
        MangaBakaStacks.VISIBILITIES.forEach { value ->
            header.stackEditVisibilityChips.addView(
                chip(MangaBakaStackUi.visibilityLabel(value), value, value == visibility)
            )
        }
    }

    private fun chip(label: Int, value: String, checked: Boolean) = (layoutInflater.inflate(
        R.layout.item_form_choice_chip, header.stackEditRatingChips, false
    ) as Chip).apply {
        setText(label)
        tag = value
        isCheckable = true
        isChecked = checked
        setOnCheckedChangeListener { _, _ -> dirty = true }
    }

    private fun checkedValue(group: com.google.android.material.chip.ChipGroup, fallback: String): String =
        group.findViewById<Chip>(group.checkedChipId)?.tag as? String ?: fallback

    private fun updateEntriesTitle() {
        header.stackEditEntriesTitle.text = getString(R.string.stack_series_count, entries.size)
    }

    private fun save() {
        if (saving) return
        val title = header.stackEditName.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            header.stackEditNameLayout.error = getString(R.string.stack_title_required)
            binding.stackEditRecycler.scrollToPosition(0)
            return
        }
        val visibility = checkedValue(header.stackEditVisibilityChips, "draft")
        if (visibility != "draft" && entries.size < MangaBakaStacks.MIN_PUBLISHED_ENTRIES) {
            snackString(R.string.stack_publish_hint)
            return
        }
        val edit = MangaBakaStacks.StackEdit(
            title = title,
            description = header.stackEditDescription.text?.toString(),
            contentRating = checkedValue(header.stackEditRatingChips, "safe"),
            isSpoiler = header.stackEditSpoiler.isChecked,
            isCommentsEnabled = header.stackEditComments.isChecked,
            entries = entries.map { MangaBakaStacks.EntryInput(it.seriesId, it.note?.takeIf { n -> n.isNotBlank() }) },
        )
        setSaving(true)
        lifecycleScope.launch {
            val id = stackId
            val tok = token
            if (id != null && tok != null) {
                when (val result = MangaBakaStacks.updateStack(id, tok, visibility, edit)) {
                    is MangaBakaStacks.Result.Ok -> {
                        snackString(R.string.stack_saved)
                        setResult(RESULT_OK)
                        finish()
                    }

                    is MangaBakaStacks.Result.Error -> {
                        setSaving(false)
                        if (result.isConflict) showConflict(id) else MangaBakaStackUi.reportError(result)
                    }
                }
            } else {
                when (val result = MangaBakaStacks.createStack(edit)) {
                    is MangaBakaStacks.Result.Ok -> {
                        val created = result.value
                        if (visibility != "draft") {
                            (MangaBakaStacks.setVisibility(created.id, visibility) as? MangaBakaStacks.Result.Error)
                                ?.let { MangaBakaStackUi.reportError(it) }
                        }
                        snackString(R.string.stack_created)
                        setResult(RESULT_OK)
                        StackResolver.openMangaBaka(this@MangaBakaStackEditActivity, created.id, created.title, created.description)
                        finish()
                    }

                    is MangaBakaStacks.Result.Error -> {
                        setSaving(false)
                        MangaBakaStackUi.reportError(result)
                    }
                }
            }
        }
    }

    private fun setSaving(on: Boolean) {
        saving = on
        binding.stackEditBar.formBarPrimary.isEnabled = !on
        binding.stackEditProgress.isVisible = on
    }

    private fun showConflict(id: Long) {
        materialDialog()
            .setTitle(R.string.stack_conflict_title)
            .setMessage(R.string.stack_conflict_message)
            .setPositiveButton(R.string.stack_reload) { _, _ -> load(id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editNote(position: Int) {
        val entry = entries.getOrNull(position) ?: return
        val (layout, field) = textField(R.string.stack_field_note, entry.note.orEmpty(), multiLine = true)
        field.filters = arrayOf(android.text.InputFilter.LengthFilter(5000))
        materialDialog()
            .setTitle(entry.title)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                entry.note = field.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                dirty = true
                entriesAdapter.notifyItemChanged(position + HEADER_ROWS)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun textField(hint: Int, text: String, multiLine: Boolean): Pair<View, TextInputEditText> {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24f.px, 8f.px, 24f.px, 0)
        }
        val layout = TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply { this.hint = getString(hint) }
        val field = TextInputEditText(layout.context).apply {
            setText(text)
            if (multiLine) {
                inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
                    EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 3
            } else {
                inputType = EditorInfo.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                isSingleLine = true
            }
        }
        layout.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(layout)
        return container to field
    }

    /** A search box over MangaBaka's catalogue; tapping a result adds it (the dialog stays open). */
    private fun showSeriesSearch() {
        val (container, field) = textField(R.string.search, "", multiLine = false)
        val results = ArrayList<MangaBakaApi.Series>()
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MangaBakaStackEditActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 360f.px)
        }
        (container as LinearLayout).addView(list)
        val adapter = SearchAdapter(results) { series ->
            if (entries.any { it.seriesId == series.id }) {
                toast(R.string.stack_contains_series)
                return@SearchAdapter
            }
            entries.add(EditEntry(series.id, series.displayTitle() ?: "#${series.id}", series.cover?.thumbUrl(), null))
            entriesAdapter.notifyItemInserted(entries.size - 1 + HEADER_ROWS)
            updateEntriesTitle()
            dirty = true
            toast(getString(R.string.stack_series_added, series.displayTitle() ?: ""))
        }
        list.adapter = adapter

        var job: Job? = null
        fun search(query: String, debounce: Boolean) {
            job?.cancel()
            if (query.isBlank()) return
            job = lifecycleScope.launch {
                if (debounce) delay(450)
                val page = MangaBakaApi.searchSeries(
                    query, limit = 25, allowAdult = PrefManager.getVal(PrefName.AdultOnly)
                )
                val size = results.size
                results.clear()
                adapter.notifyItemRangeRemoved(0, size)
                results.addAll(page?.results.orEmpty())
                adapter.notifyItemRangeInserted(0, results.size)
            }
        }
        field.doAfterTextChanged { search(it?.toString().orEmpty(), debounce = true) }
        field.setOnEditorActionListener { v, _, _ ->
            search(v.text?.toString().orEmpty(), debounce = false)
            true
        }
        materialDialog()
            .setTitle(R.string.stack_add_series)
            .setView(container)
            .setPositiveButton(R.string.stack_done, null)
            .setOnDismissListener { job?.cancel() }
            .show()
        field.requestFocus()
    }

    // ---- adapters ----

    /**
     * The form as row 0 (one fixed view, so its fields keep their state) and the series below it;
     * entry `i` is at adapter position `i + HEADER_ROWS`.
     */
    private inner class EntriesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class VH(val b: ItemStackEditEntryBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemViewType(position: Int) = if (position < HEADER_ROWS) VIEW_HEADER else VIEW_ENTRY

        /** The entry index of [vh], or null while it isn't bound to one. */
        private fun entryIndex(vh: RecyclerView.ViewHolder): Int? =
            vh.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                ?.minus(HEADER_ROWS)?.takeIf { it in entries.indices }

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == VIEW_HEADER) {
                (header.root.parent as? ViewGroup)?.removeView(header.root)
                return object : RecyclerView.ViewHolder(header.root) {}
            }
            val vh = VH(ItemStackEditEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            // Filled like the form's sections above, so the list reads as part of the form.
            vh.b.root.setCardBackgroundColor(SectionCard.fill(vh.b.root, open = false))
            vh.b.stackEntryHandle.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(vh)
                false
            }
            vh.b.root.setOnClickListener { entryIndex(vh)?.let { editNote(it) } }
            vh.b.stackEntryRemove.setOnClickListener {
                val i = entryIndex(vh) ?: return@setOnClickListener
                entries.removeAt(i)
                notifyItemRemoved(i + HEADER_ROWS)
                notifyItemRangeChanged(i + HEADER_ROWS, entries.size - i)
                updateEntriesTitle()
                dirty = true
            }
            return vh
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is VH) return
            val i = position - HEADER_ROWS
            val e = entries[i]
            holder.b.stackEntryRank.text = (i + 1).toString()
            holder.b.stackEntryTitle.text = e.title
            holder.b.stackEntryCover.loadImage(e.cover)
            holder.b.stackEntryNote.text = e.note ?: getString(R.string.stack_add_note)
        }

        override fun getItemCount() = entries.size + HEADER_ROWS
    }

    private class SearchAdapter(
        private val items: List<MangaBakaApi.Series>,
        private val onPick: (MangaBakaApi.Series) -> Unit,
    ) : RecyclerView.Adapter<SearchAdapter.VH>() {
        class VH(val b: ItemStackEditEntryBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val vh = VH(ItemStackEditEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            vh.b.stackEntryHandle.visibility = View.GONE
            vh.b.stackEntryRank.visibility = View.GONE
            // Flat in the dialog, which is a surface of its own.
            vh.b.root.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            vh.b.root.updateLayoutParams<ViewGroup.MarginLayoutParams> { marginStart = 0; marginEnd = 0 }
            vh.b.stackEntryRemove.setIconResource(R.drawable.ic_add)
            val pick = View.OnClickListener {
                items.getOrNull(vh.bindingAdapterPosition)?.let(onPick)
            }
            vh.b.root.setOnClickListener(pick)
            vh.b.stackEntryRemove.setOnClickListener(pick)
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            holder.b.stackEntryTitle.text = s.displayTitle()
            holder.b.stackEntryCover.loadImage(s.cover?.thumbUrl())
            holder.b.stackEntryNote.text = listOfNotNull(
                s.type?.replaceFirstChar { it.uppercase() },
                s.published?.startDate?.take(4),
            ).joinToString(" · ")
        }

        override fun getItemCount() = items.size
    }

    /** Drag-to-reorder, from the handle only (the row itself opens the note). */
    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun isLongPressDragEnabled() = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            if (viewHolder.itemViewType == VIEW_ENTRY) super.getMovementFlags(recyclerView, viewHolder) else 0

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            if (target.itemViewType != VIEW_ENTRY) return false
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < HEADER_ROWS || to < HEADER_ROWS) return false
            entries.add(to - HEADER_ROWS, entries.removeAt(from - HEADER_ROWS))
            entriesAdapter.notifyItemMoved(from, to)
            dirty = true
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            // Ranks shown on the rows are positions; renumber once the drag settles.
            entriesAdapter.notifyItemRangeChanged(HEADER_ROWS, entries.size)
        }
    })

    companion object {
        private const val EXTRA_STACK_ID = "stackId"
        private const val EXTRA_SERIES_ID = "seriesId"
        private const val EXTRA_SERIES_TITLE = "seriesTitle"
        private const val EXTRA_SERIES_COVER = "seriesCover"
        private const val VIEW_HEADER = 1
        private const val VIEW_ENTRY = 2
        private const val HEADER_ROWS = 1

        fun editIntent(context: Context, stackId: Long): Intent =
            Intent(context, MangaBakaStackEditActivity::class.java).putExtra(EXTRA_STACK_ID, stackId)

        /** A new stack, optionally starting with one series. */
        fun createIntent(context: Context, seriesId: Long?, seriesTitle: String?, coverUrl: String?): Intent =
            Intent(context, MangaBakaStackEditActivity::class.java).apply {
                if (seriesId != null) {
                    putExtra(EXTRA_SERIES_ID, seriesId)
                    putExtra(EXTRA_SERIES_TITLE, seriesTitle)
                    putExtra(EXTRA_SERIES_COVER, coverUrl)
                }
            }
    }
}
