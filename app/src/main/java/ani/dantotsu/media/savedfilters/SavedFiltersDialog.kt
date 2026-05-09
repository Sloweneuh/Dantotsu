package ani.dantotsu.media.savedfilters

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.snackString
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** A single saved-filter row: stable name plus the full per-filter chip list. */
data class SavedFilterEntry(val name: String, val chips: List<String>)

/**
 * Reusable popup that lists saved filter presets and lets the user save the
 * current filter state as a new preset. Each row collapses to "{N} filters"
 * and expands to show all filters as chips with an in-row Apply button.
 *
 * Decoupled from any specific preset shape so the same UI works for AniList,
 * MU, Comick, ListFilters, and per-source extension presets.
 */
object SavedFiltersDialog {

    fun show(
        context: Context,
        loadPresets: () -> List<SavedFilterEntry>,
        onSaveCurrent: (name: String) -> Unit,
        onApply: (name: String) -> Unit,
        onDelete: (name: String) -> Unit,
        onRename: (oldName: String, newName: String) -> Unit,
    ): Dialog {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_saved_filters, null)
        val recycler = view.findViewById<RecyclerView>(R.id.savedFiltersList)
        val emptyText = view.findViewById<TextView>(R.id.savedFiltersEmpty)
        val saveCurrentRow = view.findViewById<View>(R.id.savedFiltersSaveCurrent)

        val dialog = MaterialAlertDialogBuilder(context, R.style.MyPopup)
            .setTitle(R.string.saved_filters_title)
            .setView(view)
            .setNegativeButton(R.string.close, null)
            .create()

        val adapter = SavedFiltersAdapter(
            onApply = { name ->
                onApply(name)
                dialog.dismiss()
            },
            onDelete = { name ->
                confirmDelete(context, name) {
                    onDelete(name)
                    refreshList(recycler, emptyText, loadPresets())
                }
            },
            onRename = { oldName ->
                val others = loadPresets().mapNotNull { if (it.name == oldName) null else it.name }
                promptForName(
                    context = context,
                    titleRes = R.string.saved_filters_rename_title,
                    initial = oldName,
                    existing = others,
                ) { newName ->
                    if (newName == oldName) return@promptForName
                    onRename(oldName, newName)
                    refreshList(recycler, emptyText, loadPresets())
                }
            },
        )
        recycler.adapter = adapter
        refreshList(recycler, emptyText, loadPresets())

        saveCurrentRow.setOnClickListener {
            promptForName(
                context = context,
                titleRes = R.string.saved_filters_save_title,
                initial = "",
                // Allow duplicates here — caller resolves with an overwrite confirmation.
                existing = emptyList(),
            ) { newName ->
                val existing = loadPresets().any { it.name.equals(newName, ignoreCase = true) }
                if (existing) {
                    confirmOverwrite(context, newName) {
                        onSaveCurrent(newName)
                        snackString(
                            context.getString(R.string.saved_filters_updated, newName)
                        )
                        refreshList(recycler, emptyText, loadPresets())
                    }
                } else {
                    onSaveCurrent(newName)
                    snackString(context.getString(R.string.saved_filters_saved, newName))
                    refreshList(recycler, emptyText, loadPresets())
                }
            }
        }

        dialog.show()
        return dialog
    }

    private fun refreshList(
        recycler: RecyclerView,
        empty: TextView,
        entries: List<SavedFilterEntry>,
    ) {
        val sorted = entries.sortedBy { it.name.lowercase() }
        (recycler.adapter as? SavedFiltersAdapter)?.submit(sorted)
        empty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmOverwrite(context: Context, name: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(context, R.style.MyPopup)
            .setTitle(R.string.saved_filters_overwrite_title)
            .setMessage(context.getString(R.string.saved_filters_overwrite_message, name))
            .setPositiveButton(R.string.saved_filters_overwrite) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(context: Context, name: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(context, R.style.MyPopup)
            .setTitle(R.string.saved_filters_delete_title)
            .setMessage(context.getString(R.string.saved_filters_delete_message, name))
            .setPositiveButton(R.string.delete) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptForName(
        context: Context,
        titleRes: Int,
        initial: String,
        existing: List<String>,
        onConfirm: (String) -> Unit,
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (context.resources.displayMetrics.density * 16).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val inputLayout = TextInputLayout(context).apply {
            hint = context.getString(R.string.name)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val edit = TextInputEditText(inputLayout.context).apply {
            maxLines = 1
            isSingleLine = true
            setText(initial)
            setSelection(initial.length)
        }
        inputLayout.addView(
            edit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            inputLayout,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val dlg = MaterialAlertDialogBuilder(context, R.style.MyPopup)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dlg.setOnShowListener {
            val positive = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val name = edit.text?.toString()?.trim().orEmpty()
                when {
                    name.isEmpty() -> inputLayout.error =
                        context.getString(R.string.saved_filters_error_empty_name)
                    existing.any { it.equals(name, ignoreCase = true) } ->
                        inputLayout.error =
                            context.getString(R.string.saved_filters_error_duplicate)
                    else -> {
                        dlg.dismiss()
                        onConfirm(name)
                    }
                }
            }
        }
        dlg.show()
        edit.requestFocus()
    }

    private class SavedFiltersAdapter(
        val onApply: (String) -> Unit,
        val onDelete: (String) -> Unit,
        val onRename: (String) -> Unit,
    ) : RecyclerView.Adapter<SavedFiltersAdapter.VH>() {

        private val items = mutableListOf<SavedFilterEntry>()
        // Persist expanded rows across re-renders (e.g. after rename/delete) by name.
        private val expanded = mutableSetOf<String>()

        fun submit(list: List<SavedFilterEntry>) {
            items.clear()
            items.addAll(list)
            expanded.retainAll(items.map { it.name }.toSet())
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_saved_filter, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            val ctx = holder.itemView.context
            holder.name.text = entry.name
            holder.count.text = ctx.getString(
                if (entry.chips.size == 1) R.string.saved_filters_count_one
                else R.string.saved_filters_count_other,
                entry.chips.size,
            )
            val isExpanded = expanded.contains(entry.name)
            holder.expanded.visibility = if (isExpanded) View.VISIBLE else View.GONE
            holder.chevron.rotation = if (isExpanded) 180f else 0f
            if (isExpanded) populateChips(holder.chipGroup, entry.chips)
            // Tap on the row body applies; chevron is the only expand affordance
            // so users who memorise their preset names keep one-tap apply.
            holder.header.setOnClickListener { onApply(entry.name) }
            holder.chevron.setOnClickListener {
                val nowExpanded = !expanded.contains(entry.name)
                if (nowExpanded) expanded += entry.name else expanded -= entry.name
                notifyItemChanged(holder.bindingAdapterPosition)
            }
            holder.edit.setOnClickListener { onRename(entry.name) }
            holder.delete.setOnClickListener { onDelete(entry.name) }
        }

        override fun getItemCount() = items.size

        private fun populateChips(group: ChipGroup, chips: List<String>) {
            group.removeAllViews()
            val ctx = group.context
            chips.forEach { label ->
                val chip = Chip(ctx).apply {
                    text = label
                    isClickable = false
                    isCheckable = false
                    isFocusable = false
                    isCloseIconVisible = false
                    minHeight = 0
                    chipMinHeight = (ctx.resources.displayMetrics.density * 28)
                    setEnsureMinTouchTargetSize(false)
                }
                group.addView(chip)
            }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val header: View = v.findViewById(R.id.savedFilterHeader)
            val name: TextView = v.findViewById(R.id.savedFilterName)
            val count: TextView = v.findViewById(R.id.savedFilterCount)
            val chevron: ImageButton = v.findViewById(R.id.savedFilterChevron)
            val edit: ImageButton = v.findViewById(R.id.savedFilterEdit)
            val delete: ImageButton = v.findViewById(R.id.savedFilterDelete)
            val expanded: View = v.findViewById(R.id.savedFilterExpanded)
            val chipGroup: ChipGroup = v.findViewById(R.id.savedFilterChipGroup)
        }
    }
}
