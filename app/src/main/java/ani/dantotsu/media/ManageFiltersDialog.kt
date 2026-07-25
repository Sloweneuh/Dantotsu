package ani.dantotsu.media

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import ani.dantotsu.R
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * One active filter, decoupled from any specific filter-state shape.
 * [icon]/[iconTint] are optional so callers that don't have a per-filter
 * source icon (most screens) can leave the chip icon-less.
 */
data class ActiveFilterChip(
    val label: String,
    val icon: Drawable? = null,
    val iconTint: ColorStateList? = null,
    val onRemove: () -> Unit,
)

/**
 * Reusable "manage all active filters" popup: lists every active filter as a
 * removable chip plus a clear-all action, so filters aren't only visible by
 * scrolling a single-line chip row. Decoupled from any specific filter-state
 * shape so the same UI works for AniList/MU/Comick/MangaBaka search and the
 * library list filters, mirroring the pattern used by SavedFiltersDialog.
 */
object ManageFiltersDialog {

    fun show(
        context: Context,
        chips: List<ActiveFilterChip>,
        onClearAll: () -> Unit,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_manage_filters, null)
        val clearAllRow = view.findViewById<View>(R.id.manageFiltersClearAll)
        val empty = view.findViewById<TextView>(R.id.manageFiltersEmpty)
        val chipGroup = view.findViewById<ChipGroup>(R.id.manageFiltersChipGroup)

        var dialog: android.app.AlertDialog? = null

        fun onEmptied() {
            empty.visibility = View.VISIBLE
            clearAllRow.visibility = View.GONE
        }

        chips.forEach { item ->
            val chip = LayoutInflater.from(context)
                .inflate(R.layout.item_chip, chipGroup, false) as Chip
            chip.apply {
                text = item.label.replace("_", " ")
                isCloseIconVisible = true
                if (item.icon != null) {
                    isChipIconVisible = true
                    chipIcon = item.icon
                    chipIconTint = item.iconTint
                }
                setOnCloseIconClickListener {
                    item.onRemove()
                    chipGroup.removeView(this)
                    if (chipGroup.childCount == 0) onEmptied()
                }
            }
            chipGroup.addView(chip)
        }

        if (chips.isEmpty()) onEmptied()

        clearAllRow.setOnClickListener {
            onClearAll()
            dialog?.dismiss()
        }

        context.customAlertDialog().apply {
            setTitle(context.getString(R.string.manage_filters))
            setCustomView(view)
            setNegButton(context.getString(R.string.close))
            attach { dialog = it }
        }.show()
    }
}
