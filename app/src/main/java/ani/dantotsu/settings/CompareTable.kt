package ani.dantotsu.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.ImageViewCompat
import ani.dantotsu.R
import ani.dantotsu.getThemeColor
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors

/**
 * The three-column tables on the Compare lists screen — a label, then the source list's value, then
 * the tracker's — used for a card's per-status counts and for an entry's field-by-field detail.
 * The two value columns line up under a row of service marks, and where the sides disagree the
 * tracker's value is set in the accent colour (see [Row]).
 */
object CompareTable {

    /**
     * One table row. [differs] sets the tracker's value in the accent colour and, with [band], lays
     * the row on a faint accent wash; [strong] sets it in bold (a totals row). The counts table
     * passes no band: totals naturally differ when the sources are merged, and a band on nearly
     * every row picks out nothing.
     */
    class Row(
        val label: String,
        val source: String,
        val dest: String,
        val differs: Boolean = false,
        val strong: Boolean = false,
        val band: Boolean = true,
    )

    /** The accent at this alpha, out of 255: a wash light enough to read any theme's text over. */
    private const val BAND_ALPHA = 30

    /** The light accent wash buttons on this screen sit on, with the accent for their content. */
    fun tintAccentButton(button: com.google.android.material.button.MaterialButton) {
        val accent = button.context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        button.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.compositeARGBWithAlpha(accent, BAND_ALPHA + 10)
        )
        button.iconTint = ColorStateList.valueOf(accent)
        button.setTextColor(accent)
    }

    /** Replaces [table]'s contents with a header of [sourceIcons] / [destIcons] and [rows]. */
    fun fill(table: LinearLayout, sourceIcons: List<Int>, destIcons: List<Int>, rows: List<Row>) {
        val ctx = table.context
        table.removeAllViews()
        table.addView(headerRow(ctx, sourceIcons, destIcons))
        rows.forEach { table.addView(row(ctx, it)) }
    }

    private fun headerRow(ctx: Context, sourceIcons: List<Int>, destIcons: List<Int>): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 4))
            // No height of its own: a bare View sized to wrap fills whatever it's offered, and while
            // a card opens that was the whole table, pushing every row out of sight until it ended.
            addView(View(ctx), LinearLayout.LayoutParams(0, 0, LABEL_WEIGHT))
            addView(icons(ctx, sourceIcons), cell(VALUE_WEIGHT))
            addView(icons(ctx, destIcons), cell(VALUE_WEIGHT))
        }

    private fun icons(ctx: Context, res: List<Int>): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        val size = dp(ctx, 18)
        val tint = ColorStateList.valueOf(ctx.getThemeColor(androidx.appcompat.R.attr.colorPrimary))
        res.forEachIndexed { i, icon ->
            addView(ImageView(ctx).apply {
                setImageResource(icon)
                ImageViewCompat.setImageTintList(this, tint)
            }, LinearLayout.LayoutParams(size, size).apply { if (i > 0) marginStart = dp(ctx, 4) })
        }
    }

    private fun row(ctx: Context, r: Row): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 2) }
        setPadding(dp(ctx, 8), dp(ctx, 5), dp(ctx, 8), dp(ctx, 5))
        val accent = ctx.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        if (r.differs && r.band) {
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 8).toFloat()
                setColor(MaterialColors.compositeARGBWithAlpha(accent, BAND_ALPHA))
            }
        }

        val muted = ctx.getThemeColor(MaterialR.attr.colorOnSurfaceVariant)
        val onSurface = ctx.getThemeColor(MaterialR.attr.colorOnSurface)
        addView(text(ctx, r.label, if (r.strong) onSurface else muted, r.strong, Gravity.START),
            cell(LABEL_WEIGHT))
        addView(text(ctx, r.source, onSurface, r.strong, Gravity.END), cell(VALUE_WEIGHT))
        addView(text(ctx, r.dest, if (r.differs) accent else onSurface, r.strong || r.differs, Gravity.END),
            cell(VALUE_WEIGHT))
    }

    private fun text(ctx: Context, value: String, color: Int, bold: Boolean, gravity: Int) =
        TextView(ctx).apply {
            text = value
            textSize = 12.5f
            setTextColor(color)
            this.gravity = gravity
            maxLines = 2
            typeface = ResourcesCompat.getFont(
                ctx, if (bold) R.font.poppins_bold else R.font.poppins_semi_bold
            ) ?: Typeface.DEFAULT
        }

    private fun cell(weight: Float) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    private const val LABEL_WEIGHT = 1.3f
    private const val VALUE_WEIGHT = 1f
}
