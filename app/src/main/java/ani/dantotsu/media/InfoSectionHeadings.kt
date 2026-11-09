package ani.dantotsu.media

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import ani.dantotsu.R
import ani.dantotsu.px
import com.google.android.material.color.MaterialColors

/**
 * Makes it clear where an info tab's sections start: every section is headed, each heading has a
 * short accent bar beside it, and the sections stand an even distance apart.
 *
 * Drawn by the column the sections sit in rather than added to them: the renderers find, move
 * and remove their sections by tag, and anything wrapped around them would get in their way. The
 * sections that have no title of their own (the details table and the titles) get one drawn in
 * the room their margin leaves above them.
 */
object InfoSectionHeadings {
    /** The views that can hold a section's title. */
    val TITLE_IDS = setOf(
        R.id.itemTitle, R.id.tagsTitle, R.id.mediaInfoDescriptionText, R.id.genreSectionTitle,
        R.id.mediaInfoTrailerText,
    )

    /** The sections that never draw a title of their own. */
    private val UNTITLED = setOf(InfoSectionKind.DETAILS, InfoSectionKind.TITLES)

    /**
     * Above and below each section: half the space between two of them. Small, as most sections
     * already leave room at their own edges, and a short one followed by much more looks empty.
     */
    private val MARGIN get() = 4f.px

    /** Room above an untitled section for the heading drawn there. */
    private val HEADING get() = 32f.px

    /** Where headings start: where the sections' own titles do (24dp margin, 8dp padding). */
    private val HEADING_START get() = 32f.px

    private val BAR_WIDTH get() = 4f.px
    private val BAR_GAP get() = 8f.px

    /** The text a drawn heading shows in place of the kind's name: a custom tab's own name for it. */
    fun setHeading(view: View, text: CharSequence?) = view.setTag(R.id.info_section_heading, text)

    private fun needsHeading(view: View) = view.infoSectionKind in UNTITLED

    /**
     * Sets [view]'s space from its neighbours, once. Set rather than added to: the sources' own
     * margins were each their own idea of that space, and left in they made every gap a different
     * size. What they were is kept for [unspace].
     */
    fun space(view: View, params: ViewGroup.LayoutParams? = view.layoutParams) {
        if (view.getTag(R.id.info_section_card) != null) return
        val margins = params as? ViewGroup.MarginLayoutParams ?: return
        view.setTag(R.id.info_section_card, intArrayOf(margins.topMargin, margins.bottomMargin))
        margins.topMargin = MARGIN + if (needsHeading(view)) HEADING else 0
        margins.bottomMargin = MARGIN
        if (view.layoutParams === margins) view.layoutParams = margins
    }

    /** Gives back what [space] took, for a section leaving for a column that spaces its own. */
    fun unspace(view: View) {
        val original = view.getTag(R.id.info_section_card) as? IntArray ?: return
        view.setTag(R.id.info_section_card, null)
        val margins = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        margins.topMargin = original[0]
        margins.bottomMargin = original[1]
        view.layoutParams = margins
    }

    /** The visible gap between the end of one section's content and the next one's heading. */
    private val GAP get() = 20f.px

    /**
     * After [parent] lays out: evens out the gaps. Sources leave different amounts of empty room
     * at their sections' edges (a status line with a big bottom padding, an adaptation with none),
     * so each section's margins are set from what it leaves empty, for the same visible gap
     * everywhere. Returns whether anything changed, for the caller to lay out again; what a section
     * leaves empty doesn't depend on its margins, so a second pass changes nothing.
     */
    fun settle(parent: ViewGroup, isSection: (View) -> Boolean): Boolean {
        var changed = false
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.visibility != View.VISIBLE || child.height == 0 || !isSection(child)) continue
            if (child.getTag(R.id.info_section_card) == null) continue
            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            val bounds = contentBounds(child, 0)
            if (bounds == null) {
                // Nothing in it (a source with no second title): no heading to draw over nothing.
                child.setTag(R.id.info_section_heading_at, null)
                continue
            }
            val (contentTop, contentBottom) = bounds
            // A drawn heading sits just above the content, wherever in the section that starts.
            child.setTag(R.id.info_section_heading_at, contentTop)
            val top = if (needsHeading(child)) (GAP / 2 + HEADING - contentTop).coerceAtLeast(0)
            else (GAP / 2 - contentTop).coerceAtLeast(0)
            val bottom = (GAP / 2 - (child.height - contentBottom)).coerceAtLeast(0)
            if (margins.topMargin != top || margins.bottomMargin != bottom) {
                margins.topMargin = top
                margins.bottomMargin = bottom
                changed = true
            }
        }
        return changed
    }

    /**
     * Where [view]'s visible content starts and ends, relative to its top, with [offset] being its
     * own top in those terms: the extent of what it shows, less any padding with nothing in it.
     */
    private fun contentBounds(view: View, offset: Int): Pair<Int, Int>? {
        if (view.visibility != View.VISIBLE || view.width == 0 || view.height == 0) return null
        // Something drawn edge to edge (a card, a banner) or a list is content to its bounds.
        val whole = view !is ViewGroup || view.background != null || view is androidx.recyclerview.widget.RecyclerView
        if (whole) {
            return if (view is TextView) offset + view.paddingTop to offset + view.height - view.paddingBottom
            else offset to offset + view.height
        }
        var top: Int? = null
        var bottom: Int? = null
        for (i in 0 until (view as ViewGroup).childCount) {
            val child = view.getChildAt(i)
            val (t, b) = contentBounds(child, offset + child.top) ?: continue
            top = minOf(top ?: t, t)
            bottom = maxOf(bottom ?: b, b)
        }
        return if (top != null && bottom != null) top to bottom else null
    }

    /** What a column needs to draw its headings, made once per column. */
    class Painter(context: Context) {
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics
            )
        }
        val rect = RectF()
    }

    /** Draws the accent bars, and the headings of untitled sections, for the children [isSection] picks. */
    fun draw(parent: ViewGroup, canvas: Canvas, painter: Painter, isSection: (View) -> Boolean) {
        painter.bar.color = MaterialColors.getColor(parent, androidx.appcompat.R.attr.colorPrimary)
        painter.text.color = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnBackground)
        // Layout bounds only: a child's alpha or translation animating doesn't redraw its parent,
        // so a bar following them would be left wherever the animation started.
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.visibility != View.VISIBLE || child.height == 0 || !isSection(child)) continue
            val title = leadingTitle(child)
            if (title != null) {
                val (x, y) = offsetIn(child, title) ?: continue
                val textLeft = child.left + x + title.totalPaddingLeft
                val centre = child.top + y + title.totalPaddingTop +
                        (title.height - title.totalPaddingTop - title.totalPaddingBottom) / 2f
                drawBar(canvas, painter, textLeft.toFloat(), centre, title.textSize)
            } else if (needsHeading(child)) {
                val label = child.getTag(R.id.info_section_heading) as? CharSequence
                    ?: child.infoSectionKind?.let { parent.context.getString(it.labelRes) }
                    ?: continue
                val start = (parent.paddingLeft + HEADING_START).toFloat()
                val contentTop = child.getTag(R.id.info_section_heading_at) as? Int ?: continue
                val baseline = child.top + contentTop - 10f.px - painter.text.descent()
                canvas.drawText(label, 0, label.length, start, baseline, painter.text)
                val centre = baseline + (painter.text.ascent() + painter.text.descent()) / 2
                drawBar(canvas, painter, start, centre, painter.text.textSize)
            }
        }
    }

    /** The bar just before a heading whose text starts at [textLeft], as tall as its letters. */
    private fun drawBar(canvas: Canvas, painter: Painter, textLeft: Float, centre: Float, textSize: Float) {
        val half = textSize * 0.55f
        val right = textLeft - BAR_GAP
        painter.rect.set(right - BAR_WIDTH, centre - half, right, centre + half)
        canvas.drawRoundRect(painter.rect, BAR_WIDTH / 2f, BAR_WIDTH / 2f, painter.bar)
    }

    /** [section]'s title when it is the first thing in it; one further down belongs to a part of it. */
    private fun leadingTitle(section: View): TextView? {
        val title = firstTitle(section) ?: return null
        var at: View = title
        while (at !== section) {
            val group = at.parent as? ViewGroup ?: return null
            val first = (0 until group.childCount).map { group.getChildAt(it) }
                .firstOrNull { it.visibility != View.GONE }
            // A title's own row may hold buttons after it, but nothing before it.
            if (first !== at && !(group is LinearLayout && group.orientation == LinearLayout.HORIZONTAL &&
                        group !== section && first === title)
            ) return null
            at = group
        }
        return title
    }

    private fun firstTitle(view: View): TextView? = when {
        view.visibility != View.VISIBLE -> null
        view is TextView && view.id in TITLE_IDS -> view
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { firstTitle(view.getChildAt(it)) }
        else -> null
    }

    /** [view]'s position within [ancestor]. */
    private fun offsetIn(ancestor: View, view: View): Pair<Int, Int>? {
        var at: View = view
        var x = 0
        var y = 0
        while (at !== ancestor) {
            x += at.left
            y += at.top
            at = at.parent as? View ?: return null
        }
        return x to y
    }
}

/** A plain column of headed info sections: a custom tab's body. */
class InfoSectionColumn @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    private val painter = InfoSectionHeadings.Painter(context)

    init {
        orientation = VERTICAL
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        val checked = if (checkLayoutParams(params)) params else generateLayoutParams(params)
        InfoSectionHeadings.space(child, checked)
        super.addView(child, index, checked)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (InfoSectionHeadings.settle(this) { true }) post { requestLayout() }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        InfoSectionHeadings.draw(this, canvas, painter) { true }
    }
}
