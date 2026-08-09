package ani.dantotsu.media

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.ListAdapter
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * The weight steps MangaBaka grades a series' tags by, and which of them a tag list starts filtered
 * to. Shared by the two screens that render the tag section ([MangaBakaInfoFragment] and
 * [MangaBakaMediaActivity]) and by the setting that picks the starting step, so the dropdown they
 * offer and the option list the setting offers can't drift apart.
 */
object MangaBakaTagWeights {

    /** A step in the dropdown: its label, the chevron that marks it ("All" has none), and the
     *  minimum [weightRank] a tag needs to survive it. */
    data class Option(
        @StringRes val label: Int,
        @DrawableRes val chevron: Int?,
        val threshold: Int,
    )

    /** Ordered loosest to strictest — the order the dropdown and the setting both show. */
    val options = listOf(
        Option(R.string.tag_filter_all, null, 0),
        Option(R.string.tag_filter_incidental, R.drawable.ic_weight_incidental, 1),
        Option(R.string.tag_filter_recurrent, R.drawable.ic_weight_recurrent, 2),
        Option(R.string.tag_filter_defining, R.drawable.ic_weight_defining, 3),
        Option(R.string.tag_filter_core, R.drawable.ic_weight_core, 4),
    )

    /** Incidental+, i.e. everything except the tags MangaBaka never weighted. */
    const val FALLBACK_INDEX = 1

    /**
     * Which option a tag list opens on. Coerced into range so a preference left behind by a shorter
     * option list can't put the dropdown out of bounds.
     */
    fun defaultIndex(): Int =
        PrefManager.getVal<Int>(PrefName.MangaBakaTagWeightFilter).coerceIn(options.indices)

    /**
     * Adapter for a single-choice dialog listing the options: each row is a [CheckedTextView], so
     * the list's own choice mode drives the radio, with the option's chevron as a compound drawable
     * after the label. Rows are laid out by [R.layout.item_tag_weight_choice], which sizes them for
     * a comfortable touch target rather than leaving them at the platform list's fixed height.
     */
    fun choiceAdapter(context: Context): ListAdapter =
        object : ArrayAdapter<Option>(context, R.layout.item_tag_weight_choice, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = super.getView(position, convertView, parent) as CheckedTextView
                val option = options[position]
                row.text = context.getString(option.label)
                // Set on every bind, not only when there is one: a recycled row would otherwise
                // keep the chevron of whichever option it was showing before.
                row.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    0, 0, option.chevron ?: 0, 0
                )
                return row
            }
        }
}
