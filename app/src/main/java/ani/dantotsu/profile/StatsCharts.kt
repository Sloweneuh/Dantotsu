package ani.dantotsu.profile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import ani.dantotsu.R
import ani.dantotsu.getThemeColor
import ani.dantotsu.px
import ani.dantotsu.util.ColorEditor
import java.text.NumberFormat
import java.util.Locale

/** One label's value per person shown; null where that person has none, which isn't a zero. */
class StatRow(val label: String, val values: List<Double?>)

/** How a measure's numbers read: whole, except a mean score. */
fun formatStat(value: Double?, decimal: Boolean): String = when {
    value == null -> "–"
    decimal -> String.format(Locale.getDefault(), "%.1f", value)
    else -> NumberFormat.getIntegerInstance().format(value.toLong())
}

/** A person's colour: the accent for the first, for the second its opposite, legible on the card. */
fun statPersonColor(context: Context, person: Int): Int {
    val primary = context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
    if (person == 0) return primary
    val card = context.getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
    return ColorEditor.adjustColorForContrast(ColorEditor.oppositeColor(primary), card)
}

/** The colours both charts share. */
private class StatPalette(context: Context) {
    private val first = statPersonColor(context, 0)
    private val second = statPersonColor(context, 1)
    val text = context.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
    val subtle = context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    val track = (text and 0x00FFFFFF) or (0x1F shl 24)

    fun series(index: Int) = if (index == 0) first else second
}

private fun statTextPaint(context: Context, spSize: Float, color: Int, font: Int): TextPaint =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = spSize * context.resources.displayMetrics.scaledDensity
        typeface = runCatching { ResourcesCompat.getFont(context, font) }.getOrNull() ?: Typeface.DEFAULT
    }

/**
 * A ranked list: each row its label above a bar with the value at its end, one bar per person.
 * Labels get the full width, so a long tag or studio name never has to be turned or cut short.
 */
class StatRankedBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val palette = StatPalette(context)
    private val labelPaint = statTextPaint(context, 13f, palette.text, R.font.poppins_semi_bold)
    private val valuePaint = statTextPaint(context, 12f, palette.subtle, R.font.poppins)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val labelHeight = 22f.px
    private val barHeight = 8f.px
    private val barGap = 6f.px
    private val rowGap = 14f.px
    private val valueGap = 8f.px

    private var rows: List<StatRow> = emptyList()
    private var decimal = false
    private var series = 1
    private var scale = 1.0
    private var valueWidth = 0f
    private var labels: List<CharSequence> = emptyList()

    fun show(rows: List<StatRow>, decimal: Boolean) {
        this.rows = rows
        this.decimal = decimal
        series = rows.firstOrNull()?.values?.size ?: 1
        scale = rows.flatMap { it.values }.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
        valueWidth = rows.flatMap { it.values }.maxOfOrNull { valuePaint.measureText(formatStat(it, decimal)) } ?: 0f
        contentDescription = rows.joinToString(", ") { row ->
            row.label + " " + row.values.joinToString(" / ") { formatStat(it, decimal) }
        }
        fitLabels(width)
        requestLayout()
        invalidate()
    }

    private val rowHeight get() = labelHeight + series * barHeight + (series - 1) * barGap + rowGap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = paddingTop + paddingBottom + rows.size * rowHeight - if (rows.isEmpty()) 0 else rowGap
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fitLabels(w)

    private fun fitLabels(viewWidth: Int) {
        val available = (viewWidth - paddingLeft - paddingRight).toFloat()
        labels = if (available <= 0) emptyList()
        else rows.map { TextUtils.ellipsize(it.label, labelPaint, available, TextUtils.TruncateAt.END) }
    }

    override fun onDraw(canvas: Canvas) {
        if (labels.size != rows.size) return
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val barEnd = right - valueWidth - valueGap
        val radius = barHeight / 2f
        var top = paddingTop.toFloat()
        rows.forEachIndexed { index, row ->
            val label = labels[index]
            canvas.drawText(label, 0, label.length, left, top + labelPaint.textSize, labelPaint)
            var y = top + labelHeight
            row.values.forEachIndexed { person, value ->
                rect.set(left, y, barEnd, y + barHeight)
                barPaint.color = palette.track
                canvas.drawRoundRect(rect, radius, radius, barPaint)
                if (value != null && value > 0) {
                    val fill = ((value / scale).toFloat() * (barEnd - left)).coerceAtLeast(barHeight.toFloat())
                    rect.set(left, y, left + fill, y + barHeight)
                    barPaint.color = palette.series(person)
                    canvas.drawRoundRect(rect, radius, radius, barPaint)
                }
                val text = formatStat(value, decimal)
                val textY = y + barHeight / 2f - (valuePaint.ascent() + valuePaint.descent()) / 2f
                canvas.drawText(text, right - valuePaint.measureText(text), textY, valuePaint)
                y += barHeight + barGap
            }
            top += rowHeight
        }
    }
}

/**
 * Values in their natural order, scores and years, as columns with the value above and the label
 * below. As wide as its parent where they fit, wider where they don't, to scroll sideways in.
 */
class StatColumnsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val palette = StatPalette(context)
    private val labelPaint = statTextPaint(context, 11f, palette.text, R.font.poppins).apply {
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = statTextPaint(context, 10f, palette.subtle, R.font.poppins).apply {
        textAlign = Paint.Align.CENTER
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint().apply {
        color = palette.track
        strokeWidth = 1f.px.toFloat()
    }
    private val rect = RectF()

    private val chartHeight = 170f.px
    private val labelArea = 22f.px
    private val valueArea = 16f.px
    private val columnGap = 4f.px

    private var rows: List<StatRow> = emptyList()
    private var decimal = false
    private var series = 1
    private var scale = 1.0

    fun show(rows: List<StatRow>, decimal: Boolean) {
        this.rows = rows
        this.decimal = decimal
        series = rows.firstOrNull()?.values?.size ?: 1
        scale = rows.flatMap { it.values }.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
        contentDescription = rows.joinToString(", ") { row ->
            row.label + " " + row.values.joinToString(" / ") { formatStat(it, decimal) }
        }
        requestLayout()
        invalidate()
    }

    private val minSlot get() = (if (series > 1) 64f else 40f).px

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = paddingLeft + paddingRight + rows.size * minSlot
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) available
        else maxOf(wanted, suggestedMinimumWidth)
        setMeasuredDimension(width, paddingTop + paddingBottom + chartHeight)
    }

    override fun onDraw(canvas: Canvas) {
        if (rows.isEmpty()) return
        val left = paddingLeft.toFloat()
        val slot = (width - paddingLeft - paddingRight).toFloat() / rows.size
        val base = (height - paddingBottom - labelArea).toFloat()
        val tallest = base - paddingTop - valueArea
        canvas.drawLine(left, base, (width - paddingRight).toFloat(), base, linePaint)
        rows.forEachIndexed { index, row ->
            val centre = left + slot * index + slot / 2f
            val column = ((slot - columnGap * (series + 1)) / series).coerceAtMost(28f.px.toFloat())
            val group = column * series + columnGap * (series - 1)
            row.values.forEachIndexed { person, value ->
                val x = centre - group / 2f + person * (column + columnGap)
                val heightOf = if (value == null || value <= 0) 0f
                else ((value / scale).toFloat() * tallest).coerceAtLeast(2f.px.toFloat())
                if (heightOf > 0) {
                    rect.set(x, base - heightOf, x + column, base)
                    barPaint.color = palette.series(person)
                    canvas.drawRoundRect(rect, 4f.px.toFloat(), 4f.px.toFloat(), barPaint)
                }
                canvas.drawText(formatStat(value, decimal), x + column / 2f, base - heightOf - 4f.px, valuePaint)
            }
            canvas.drawText(row.label, centre, base + labelArea - 6f.px, labelPaint)
        }
    }
}
