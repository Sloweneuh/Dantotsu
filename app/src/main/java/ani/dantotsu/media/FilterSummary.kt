package ani.dantotsu.media

import android.content.Context
import ani.dantotsu.R

/** The lines the filter sheets' cards show under their title while closed. */
object FilterSummary {

    /** "3 selected · 1 excluded", or null when nothing is. */
    fun choices(context: Context, included: Int, excluded: Int = 0): String? = join(
        context.getString(R.string.filter_summary_selected, included).takeIf { included > 0 },
        context.getString(R.string.filter_summary_excluded, excluded).takeIf { excluded > 0 },
    )

    /** "2005–2015", "≥ 50" or "≤ 20"; null with neither bound. */
    fun range(low: Number?, high: Number?): String? = when {
        low != null && high != null -> "$low–$high"
        low != null -> "≥ $low"
        high != null -> "≤ $high"
        else -> null
    }

    /** A year slider's range, unless it is left at its full extent. */
    fun years(values: List<Float>, from: Float, to: Float): String? {
        if (values.size != 2 || (values[0] <= from && values[1] >= to)) return null
        return range(values[0].toInt(), values[1].toInt())
    }

    /** "Chapters: ≥ 50"; null with neither bound. */
    fun labelled(context: Context, label: String, low: Number?, high: Number?): String? =
        range(low, high)?.let { context.getString(R.string.filter_count_range, label, it) }

    fun join(vararg parts: CharSequence?): String? =
        parts.filterNot { it.isNullOrBlank() }.joinToString(" · ").ifBlank { null }
}
