package ani.dantotsu.media

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TableRow
import ani.dantotsu.R

/** A chip a source offers for merging. */
data class InfoChip(
    val label: String,
    /** What follows the name on the chip, such as AniList's "85%". */
    val detail: String? = null,
    @DrawableRes val weightIcon: Int? = null,
    /** MangaBaka's weight rank; null where the source doesn't grade its tags. */
    val weightLevel: Int? = null,
    val isSpoiler: Boolean = false,
    /** [onClick] leaves the app, so a lone tap on the chip has to be confirmed. */
    val opensWebsite: Boolean = false,
    /** Null copies the name. */
    val onLongClick: (() -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
)

/** How the user arranged one source's own tab. */
data class InfoSectionRules(
    val order: List<InfoSectionKind>,
    val hidden: Set<InfoSectionKind>,
    val hiddenFields: Set<String>,
    val fieldOrder: List<String>?,
)

/** Receives a source's sections when they are shown on a custom tab rather than the source's own. */
interface InfoSectionSink {
    fun wantsSection(source: InfoTabType, kind: InfoSectionKind): Boolean
    fun sectionsAdjacent(source: InfoTabType, first: InfoSectionKind, second: InfoSectionKind): Boolean
    fun publishSection(source: InfoTabType, kind: InfoSectionKind, view: View)
    fun retractSection(source: InfoTabType, kind: InfoSectionKind, view: View)
    fun offerMedia(source: InfoTabType, kind: InfoSectionKind, media: List<Media>)
    fun offerChips(source: InfoTabType, kind: InfoSectionKind, chips: List<InfoChip>)
    fun offerRelations(source: InfoTabType, cards: List<InfoExtraSections.RelationCard>)
    fun offerFeed(source: InfoTabType, kind: InfoSectionKind, feed: MergedFeed)

    /** What [source] offers about its match in place of the page it can't show from here. */
    fun offerMatchAction(source: InfoTabType, action: InfoMatchAction)

    /** [source] finished drawing: a section it hasn't handed over by now, it doesn't have. */
    fun sourceRendered(source: InfoTabType)

    /** Runs every source again, after a match changed which entry one of them should show. */
    fun reloadSources()
}

/** A line about a source's match on a custom tab: finding one by hand, or undoing that. */
class InfoMatchAction(
    @StringRes val messageRes: Int,
    @StringRes val buttonRes: Int,
    val onClick: () -> Unit,
)

/**
 * A stats table row the user can hide. Renderers set row visibility as they fill the table, so the
 * choice has to outlive those calls rather than be applied once over them.
 */
class InfoFieldRow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    TableRow(context, attrs) {

    var hiddenByUser = false
        set(value) {
            field = value
            if (value) super.setVisibility(GONE)
        }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(if (hiddenByUser) GONE else visibility)
    }
}

/**
 * The body of an info tab, and the one place every source's sections pass through. Three modes:
 *
 *  - Left alone (every standalone media page) it is a LinearLayout.
 *  - With [applyRules], which every info tab gets, sections land in the order the editor lists
 *    whenever they arrive, and hidden ones are dropped. Index arguments are ignored: the slots and
 *    anchors the renderers keep exist to hold a position, which the order now does for them.
 *  - With [attachSink], sections leave for a custom tab. A stub stays behind for each, carrying its
 *    tag, because the renderers find and remove their earlier views by tag when they draw again.
 */
class InfoSectionContainer @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    /** Whether its sections are going to a custom tab rather than staying here. */
    val forwardsSections: Boolean get() = sink != null

    /** See [infoSection]. */
    var currentKind: InfoSectionKind? = null

    private var rules: InfoSectionRules? = null
    private var sink: InfoSectionSink? = null
    private var source: InfoTabType? = null
    private var staticsPublished = false
    private val stubs = HashMap<View, View>()

    private class SectionStub(context: Context, val real: View) : FrameLayout(context) {
        init {
            visibility = GONE
        }
    }

    private val headingPainter = InfoSectionHeadings.Painter(context)

    override fun onFinishInflate() {
        super.onFinishInflate()
        // Binding-referenced children of the layout itself: the sections every source has.
        findViewById<View>(R.id.mediaInfoTable)?.infoSectionKind = InfoSectionKind.DETAILS
        findViewById<View>(R.id.mediaInfoNames)?.infoSectionKind = InfoSectionKind.TITLES
        findViewById<View>(R.id.mediaInfoDescriptionSection)?.infoSectionKind =
            InfoSectionKind.DESCRIPTION
        (0 until childCount).map { getChildAt(it) }
            .filter { it.infoSectionKind != null }
            .forEach { InfoSectionHeadings.space(it) }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (sink == null && InfoSectionHeadings.settle(this) { it.infoSectionKind != null }) {
            post { requestLayout() }
        }
    }

    /** Each section's heading marked; anchors and buttons that aren't sections stay as they are. */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (sink == null) {
            InfoSectionHeadings.draw(this, canvas, headingPainter) { it.infoSectionKind != null }
        }
    }

    fun wants(kind: InfoSectionKind): Boolean {
        val s = sink
        val src = source
        if (s != null && src != null) return s.wantsSection(src, kind)
        return rules?.hidden?.contains(kind) != true
    }

    fun adjacent(first: InfoSectionKind, second: InfoSectionKind): Boolean {
        val s = sink
        val src = source
        if (s != null && src != null) return s.sectionsAdjacent(src, first, second)
        val r = rules ?: return true
        val shown = r.order.filterNot { it in r.hidden }
        return shown.indexOf(first) >= 0 && shown.indexOf(second) == shown.indexOf(first) + 1
    }

    fun offerMedia(kind: InfoSectionKind, media: List<Media>) {
        val src = source ?: return
        sink?.offerMedia(src, kind, media)
    }

    fun offerChips(kind: InfoSectionKind, chips: List<InfoChip>) {
        val src = source ?: return
        sink?.offerChips(src, kind, chips)
    }

    fun offerRelations(cards: List<InfoExtraSections.RelationCard>) {
        val src = source ?: return
        sink?.offerRelations(src, cards)
    }

    fun offerFeed(kind: InfoSectionKind, feed: MergedFeed) {
        val src = source ?: return
        sink?.offerFeed(src, kind, feed)
    }

    fun offerMatchAction(action: InfoMatchAction) {
        val src = source ?: return
        sink?.offerMatchAction(src, action)
    }

    fun reloadSources() {
        sink?.reloadSources()
    }

    /** Arranges what is already here and everything still to come. */
    fun applyRules(newRules: InfoSectionRules) {
        rules = newRules
        arrangeFields(newRules.fieldOrder, newRules.hiddenFields)
        val kept = (0 until childCount).map { getChildAt(it) }
            .filter { child -> child.infoSectionKind?.let { it in newRules.hidden } != true }
            .sortedBy { rank(it) }
        removeAllViewsInLayout()
        kept.forEach { child -> super.addView(child, -1, child.layoutParams) }
        requestLayout()
    }

    /** From now on this source's sections go to [target]. */
    fun attachSink(
        type: InfoTabType, target: InfoSectionSink, hiddenFields: Set<String>, fieldOrder: List<String>?,
    ) {
        source = type
        sink = target
        arrangeFields(fieldOrder, hiddenFields)
        // Anything drawn before this point, other than the layout's own sections.
        (0 until childCount).map { getChildAt(it) }
            .filter { it.infoSectionKind != null && !isStaticSection(it) }
            .forEach { child ->
                super.removeView(child)
                addView(child, -1, child.layoutParams)
            }
        if (visibility == VISIBLE) scheduleStatics()
    }

    private fun isStaticSection(view: View) = view.id == R.id.mediaInfoTable ||
            view.id == R.id.mediaInfoNames || view.id == R.id.mediaInfoDescriptionSection

    private fun arrangeFields(order: List<String>?, hidden: Set<String>) {
        val table = findViewById<ViewGroup>(R.id.mediaInfoTable) ?: return
        val rows = (0 until table.childCount).mapNotNull { table.getChildAt(it) as? InfoFieldRow }
        rows.forEach { it.hiddenByUser = it.tag in hidden }
        if (order == null) return
        val keys = orderedInfoFields(order).map { it.key }
        rows.forEach { table.removeView(it) }
        rows.sortedBy { keys.indexOf(it.tag) }.forEach { table.addView(it) }
    }

    private fun rank(view: View): Int {
        val kind = view.infoSectionKind ?: return Int.MAX_VALUE
        val order = rules?.order ?: return Int.MAX_VALUE
        return order.indexOf(kind).let { if (it >= 0) it else order.size }
    }

    /**
     * Sources turn this visible, then fill the table in the same pass, and a source with nothing to
     * show turns it back off in that same pass. So that it means "rendered" only once the pass ends.
     */
    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == VISIBLE && sink != null) scheduleStatics()
    }

    private fun scheduleStatics() {
        post {
            if (visibility != VISIBLE || staticsPublished) return@post
            val s = sink ?: return@post
            val src = source ?: return@post
            staticsPublished = true
            (0 until childCount).map { getChildAt(it) }.filter { isStaticSection(it) }
                .forEach { static ->
                    val kind = static.infoSectionKind ?: return@forEach
                    super.removeView(static)
                    InfoSectionHeadings.unspace(static)
                    if (s.wantsSection(src, kind)) s.publishSection(src, kind, static)
                }
            s.sourceRendered(src)
        }
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        if (child is SectionStub) {
            super.addView(child, index, params)
            return
        }
        val kind = child.infoSectionKind ?: currentKind?.also { child.infoSectionKind = it }
        val s = sink
        val src = source
        val r = rules
        when {
            // The layout's own children while it inflates, and anything that isn't a section (the
            // anchors and unlink buttons) on a tab whose sections are leaving: those stay put.
            s != null && src != null && kind != null -> {
                child.layoutParams = params
                InfoSectionHeadings.unspace(child)
                val stub = SectionStub(context, child).apply { tag = child.tag }
                stubs[child] = stub
                super.addView(stub, -1, generateDefaultLayoutParams())
                if (s.wantsSection(src, kind)) s.publishSection(src, kind, child)
            }

            r != null -> {
                if (kind != null && kind in r.hidden) return
                val childRank = rank(child)
                val position = (0 until childCount)
                    .firstOrNull { rank(getChildAt(it)) > childRank } ?: -1
                val checked = checkedParams(params)
                if (kind != null) InfoSectionHeadings.space(child, checked)
                super.addView(child, position, checked)
            }

            else -> {
                val checked = checkedParams(params)
                if (kind != null) InfoSectionHeadings.space(child, checked)
                super.addView(child, index, checked)
            }
        }
    }

    private fun checkedParams(params: ViewGroup.LayoutParams) =
        if (checkLayoutParams(params)) params else generateLayoutParams(params)

    override fun removeView(view: View) {
        val real = (view as? SectionStub)?.real ?: view
        val stub = stubs.remove(real)
        if (stub != null) {
            super.removeView(stub)
            val kind = real.infoSectionKind
            val src = source
            if (kind != null && src != null) sink?.retractSection(src, kind, real)
            return
        }
        super.removeView(view)
    }
}
