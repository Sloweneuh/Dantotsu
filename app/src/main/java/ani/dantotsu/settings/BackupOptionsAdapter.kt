package ani.dantotsu.settings

import android.view.View
import androidx.recyclerview.widget.DiffUtil
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemBackupCategoryBinding
import ani.dantotsu.databinding.ItemBackupPrefBinding
import ani.dantotsu.databinding.ItemBackupSubcategoryBinding
import ani.dantotsu.settings.saving.BackupCategory
import ani.dantotsu.settings.saving.BackupItem
import ani.dantotsu.settings.saving.BackupSection
import ani.dantotsu.settings.saving.BackupSubCategory
import ani.dantotsu.settings.saving.BackupTree
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.checkbox.MaterialCheckBox

class BackupOptionsAdapter(
    private val onSelectionChanged: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class CategoryRow(val category: BackupCategory) : Row()
        data class SubCategoryRow(
            val category: BackupCategory,
            val subCategory: BackupSubCategory,
        ) : Row()
        data class ItemRow(
            val category: BackupCategory,
            val subCategory: BackupSubCategory,
            val item: BackupItem,
        ) : Row()
    }

    private val expandedCategories = mutableSetOf<String>()
    private val expandedSubCategories = mutableSetOf<String>()
    /**
     * Held as [BackupItem.id] rather than [PrefName], because not everything a backup carries is a
     * preference — see [BackupSection].
     */
    private val selected: MutableSet<String> = mutableSetOf()
    private var rows: List<Row> = emptyList()

    init {
        rebuildRows()
    }

    /** Only the preference-backed selections; sections are asked for separately. */
    fun selectedPrefs(): Set<PrefName> =
        BackupTree.categories
            .flatMap { it.subCategories }
            .flatMap { it.items }
            .mapNotNull { item -> item.pref?.takeIf { item.id in selected } }
            .toSet()

    fun isSelected(section: BackupSection): Boolean = section.name in selected

    fun hasProtectedSelected(): Boolean =
        BackupTree.categories.any { cat ->
            cat.containsProtected && cat.subCategories.any { sub ->
                sub.items.any { it.id in selected }
            }
        }

    fun selectAll() {
        BackupTree.categories.forEach { cat ->
            cat.subCategories.forEach { sub ->
                sub.items.forEach { selected.add(it.id) }
            }
        }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectNone() {
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun rebuildRows() {
        val newRows = mutableListOf<Row>()
        BackupTree.categories.forEach { cat ->
            newRows.add(Row.CategoryRow(cat))
            if (cat.id in expandedCategories) {
                cat.subCategories.forEach { sub ->
                    newRows.add(Row.SubCategoryRow(cat, sub))
                    if (sub.id in expandedSubCategories) {
                        sub.items.forEach { item ->
                            newRows.add(Row.ItemRow(cat, sub, item))
                        }
                    }
                }
            }
        }
        rows = newRows
    }

    private fun toggleCategoryExpand(catId: String, chevron: View) {
        val open = expandedCategories.add(catId) || !expandedCategories.remove(catId)
        turnChevron(chevron, open)
        rebuildRowsAnimated()
    }

    private fun toggleSubCategoryExpand(subId: String, chevron: View) {
        val open = expandedSubCategories.add(subId) || !expandedSubCategories.remove(subId)
        turnChevron(chevron, open)
        rebuildRowsAnimated()
    }

    private fun turnChevron(chevron: View, open: Boolean) {
        chevron.animate().rotation(if (open) 0f else -90f).setDuration(220).start()
    }

    /**
     * Rebuilds the rows and tells the list exactly which ones came or went, so the item animator
     * slides the opened group's rows in (or the closed one's out) instead of the whole list
     * redrawing in its new shape in one frame. The header itself is unchanged and not rebound,
     * which is why its chevron is turned by hand.
     */
    private fun rebuildRowsAnimated() {
        val old = rows
        rebuildRows()
        val new = rows
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = new.size
            override fun areItemsTheSame(o: Int, n: Int) = rowKey(old[o]) == rowKey(new[n])
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
        }).dispatchUpdatesTo(this)
    }

    private fun rowKey(row: Row): String = when (row) {
        is Row.CategoryRow -> "c:${row.category.id}"
        is Row.SubCategoryRow -> "s:${row.category.id}/${row.subCategory.id}"
        is Row.ItemRow -> "i:${row.subCategory.id}/${row.item.id}"
    }

    private fun categoryItems(cat: BackupCategory): List<BackupItem> =
        cat.subCategories.flatMap { it.items }

    private fun categoryState(cat: BackupCategory): Int {
        val items = categoryItems(cat)
        val checkedCount = items.count { it.id in selected }
        return when {
            checkedCount == 0 -> MaterialCheckBox.STATE_UNCHECKED
            checkedCount == items.size -> MaterialCheckBox.STATE_CHECKED
            else -> MaterialCheckBox.STATE_INDETERMINATE
        }
    }

    private fun subCategoryState(sub: BackupSubCategory): Int {
        val checkedCount = sub.items.count { it.id in selected }
        return when {
            checkedCount == 0 -> MaterialCheckBox.STATE_UNCHECKED
            checkedCount == sub.items.size -> MaterialCheckBox.STATE_CHECKED
            else -> MaterialCheckBox.STATE_INDETERMINATE
        }
    }

    private fun toggleCategory(cat: BackupCategory) {
        val items = categoryItems(cat)
        val allSelected = items.all { it.id in selected }
        if (allSelected) items.forEach { selected.remove(it.id) }
        else items.forEach { selected.add(it.id) }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun toggleSubCategory(sub: BackupSubCategory) {
        val allSelected = sub.items.all { it.id in selected }
        if (allSelected) sub.items.forEach { selected.remove(it.id) }
        else sub.items.forEach { selected.add(it.id) }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun toggleItem(item: BackupItem) {
        if (item.id in selected) selected.remove(item.id)
        else selected.add(item.id)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.CategoryRow -> TYPE_CATEGORY
        is Row.SubCategoryRow -> TYPE_SUBCATEGORY
        is Row.ItemRow -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_CATEGORY -> CategoryVH(
                ItemBackupCategoryBinding.inflate(inflater, parent, false)
            )
            TYPE_SUBCATEGORY -> SubCategoryVH(
                ItemBackupSubcategoryBinding.inflate(inflater, parent, false)
            )
            else -> ItemVH(
                ItemBackupPrefBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.CategoryRow -> (holder as CategoryVH).bind(row.category)
            is Row.SubCategoryRow -> (holder as SubCategoryVH).bind(row.category, row.subCategory)
            is Row.ItemRow -> (holder as ItemVH).bind(row.item)
        }
    }

    override fun getItemCount(): Int = rows.size

    inner class CategoryVH(private val b: ItemBackupCategoryBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(cat: BackupCategory) {
            val ctx = b.root.context
            b.backupCategoryTitle.text = ctx.getString(cat.titleRes)
            if (cat.descRes != null) {
                b.backupCategoryDesc.text = ctx.getString(cat.descRes)
                b.backupCategoryDesc.visibility = android.view.View.VISIBLE
            } else {
                b.backupCategoryDesc.visibility = android.view.View.GONE
            }
            b.backupCategoryCheckbox.checkedState = categoryState(cat)
            val expanded = cat.id in expandedCategories
            b.backupCategoryChevron.rotation = if (expanded) 0f else -90f

            b.backupCategoryRow.setOnClickListener {
                toggleCategoryExpand(cat.id, b.backupCategoryChevron)
            }
            b.backupCategoryCheckbox.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    toggleCategory(cat)
                }
                true
            }
        }
    }

    inner class SubCategoryVH(private val b: ItemBackupSubcategoryBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(cat: BackupCategory, sub: BackupSubCategory) {
            val ctx = b.root.context
            b.backupSubCategoryTitle.text = ctx.getString(sub.titleRes)
            if (sub.descRes != null) {
                b.backupSubCategoryDesc.text = ctx.getString(sub.descRes)
                b.backupSubCategoryDesc.visibility = android.view.View.VISIBLE
            } else {
                b.backupSubCategoryDesc.visibility = android.view.View.GONE
            }
            b.backupSubCategoryCheckbox.checkedState = subCategoryState(sub)
            val expanded = sub.id in expandedSubCategories
            b.backupSubCategoryChevron.rotation = if (expanded) 0f else -90f

            b.backupSubCategoryRow.setOnClickListener {
                toggleSubCategoryExpand(sub.id, b.backupSubCategoryChevron)
            }
            b.backupSubCategoryCheckbox.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    toggleSubCategory(sub)
                }
                true
            }
        }
    }

    inner class ItemVH(private val b: ItemBackupPrefBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(item: BackupItem) {
            val ctx = b.root.context
            b.backupItemTitle.text = item.titleRes?.let { ctx.getString(it) }
                ?: prettifyName(item.id)
            b.backupItemCheckbox.isChecked = item.id in selected
            b.backupItemRow.setOnClickListener { toggleItem(item) }
            b.backupItemCheckbox.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    toggleItem(item)
                }
                true
            }
        }
    }

    companion object {
        private const val TYPE_CATEGORY = 0
        private const val TYPE_SUBCATEGORY = 1
        private const val TYPE_ITEM = 2

        private val camelCaseRegex = Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

        fun prettifyName(name: String): String =
            name.split(camelCaseRegex).joinToString(" ")
    }
}
