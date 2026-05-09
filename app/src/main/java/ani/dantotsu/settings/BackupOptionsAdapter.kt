package ani.dantotsu.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemBackupCategoryBinding
import ani.dantotsu.databinding.ItemBackupPrefBinding
import ani.dantotsu.databinding.ItemBackupSubcategoryBinding
import ani.dantotsu.settings.saving.BackupCategory
import ani.dantotsu.settings.saving.BackupItem
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
    private val selected: MutableSet<PrefName> = mutableSetOf()
    private var rows: List<Row> = emptyList()

    init {
        rebuildRows()
    }

    fun selectedPrefs(): Set<PrefName> = selected.toSet()

    fun hasProtectedSelected(): Boolean =
        BackupTree.categories.any { cat ->
            cat.containsProtected && cat.subCategories.any { sub ->
                sub.items.any { it.pref in selected }
            }
        }

    fun selectAll() {
        BackupTree.categories.forEach { cat ->
            cat.subCategories.forEach { sub ->
                sub.items.forEach { selected.add(it.pref) }
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

    private fun toggleCategoryExpand(catId: String) {
        if (catId in expandedCategories) expandedCategories.remove(catId)
        else expandedCategories.add(catId)
        rebuildRows()
        notifyDataSetChanged()
    }

    private fun toggleSubCategoryExpand(subId: String) {
        if (subId in expandedSubCategories) expandedSubCategories.remove(subId)
        else expandedSubCategories.add(subId)
        rebuildRows()
        notifyDataSetChanged()
    }

    private fun categoryItems(cat: BackupCategory): List<BackupItem> =
        cat.subCategories.flatMap { it.items }

    private fun categoryState(cat: BackupCategory): Int {
        val items = categoryItems(cat)
        val checkedCount = items.count { it.pref in selected }
        return when {
            checkedCount == 0 -> MaterialCheckBox.STATE_UNCHECKED
            checkedCount == items.size -> MaterialCheckBox.STATE_CHECKED
            else -> MaterialCheckBox.STATE_INDETERMINATE
        }
    }

    private fun subCategoryState(sub: BackupSubCategory): Int {
        val checkedCount = sub.items.count { it.pref in selected }
        return when {
            checkedCount == 0 -> MaterialCheckBox.STATE_UNCHECKED
            checkedCount == sub.items.size -> MaterialCheckBox.STATE_CHECKED
            else -> MaterialCheckBox.STATE_INDETERMINATE
        }
    }

    private fun toggleCategory(cat: BackupCategory) {
        val items = categoryItems(cat)
        val allSelected = items.all { it.pref in selected }
        if (allSelected) items.forEach { selected.remove(it.pref) }
        else items.forEach { selected.add(it.pref) }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun toggleSubCategory(sub: BackupSubCategory) {
        val allSelected = sub.items.all { it.pref in selected }
        if (allSelected) sub.items.forEach { selected.remove(it.pref) }
        else sub.items.forEach { selected.add(it.pref) }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun toggleItem(item: BackupItem) {
        if (item.pref in selected) selected.remove(item.pref)
        else selected.add(item.pref)
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

            b.backupCategoryRow.setOnClickListener { toggleCategoryExpand(cat.id) }
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

            b.backupSubCategoryRow.setOnClickListener { toggleSubCategoryExpand(sub.id) }
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
                ?: prettifyName(item.pref.name)
            b.backupItemCheckbox.isChecked = item.pref in selected
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
