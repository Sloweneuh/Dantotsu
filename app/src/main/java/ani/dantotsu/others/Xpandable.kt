package ani.dantotsu.others

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.content.withStyledAttributes
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemSettingsSectionHeaderBinding
import ani.dantotsu.px
import ani.dantotsu.settings.SectionCard
import ani.dantotsu.settings.SettingsRouter
import ani.dantotsu.settings.saving.PrefManager

/**
 * A collapsible group of settings, styled as a card.
 *
 * The chrome deliberately matches `item_settings_section.xml`, the card
 * [ani.dantotsu.settings.SettingsSectionAdapter] renders: same header, radius, fill and motion, all
 * from [SectionCard], because the Player and Reader screens sit beside screens built from that
 * adapter and used to read as a different component entirely.
 *
 * The controls inside stay ordinary views. Converting them to settings rows would mean moving some
 * ninety of them, and the subtitle section holds a live preview that re-renders as its colour,
 * stroke, font and size change — there is no row for that, so it would have stayed XML regardless.
 * Restyling the container gets the consistency without touching a single control.
 */
class Xpandable @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private var expanded: Boolean = false
    private var stateKey: String? = null
    private var listeners: ArrayList<OnChangeListener> = arrayListOf()

    private var titleText: CharSequence? = null
    private var summaryText: CharSequence? = null
    private var iconRes: Int = 0

    /** Whether opening this card closes its siblings; see the `accordion` attribute. */
    var accordion: Boolean = false

    /** Whether the card insets itself from its parent; see the `cardInset` attribute. */
    private var cardInset: Boolean = true

    /** The header this view built. Null when the layout supplied its own. */
    private var header: ItemSettingsSectionHeaderBinding? = null

    /** The card's own fill, recoloured as it opens and closes; null for a nested group. */
    private var fill: GradientDrawable? = null

    /** The height the layout gave this view, handed back after each expand/collapse animation. */
    private var restingHeight = LayoutParams.WRAP_CONTENT

    private var stateScope: String? = null

    /** What the card holds, in a line, for its header while closed; see [summarizeWhenClosed]. */
    private var summaryProvider: (() -> CharSequence?)? = null

    init {
        context.withStyledAttributes(attrs, R.styleable.Xpandable) {
            expanded = getBoolean(R.styleable.Xpandable_isExpanded, expanded)
            stateKey = getString(R.styleable.Xpandable_stateKey)
            stateScope = getString(R.styleable.Xpandable_stateScope)
            titleText = getText(R.styleable.Xpandable_title)
            summaryText = getText(R.styleable.Xpandable_summary)
            iconRes = getResourceId(R.styleable.Xpandable_icon, 0)
            cardInset = getBoolean(R.styleable.Xpandable_cardInset, cardInset)
            accordion = getBoolean(R.styleable.Xpandable_accordion, accordion)
        }
        stateKey?.let { key ->
            if (shouldRestore()) expanded = PrefManager.getCustomVal(prefKey(key), expanded)
            // A plain entry starts from the layout's own default and forgets what was open, so
            // walking in from the settings list always looks the same.
            else PrefManager.setCustomVal(prefKey(key), expanded)
        }
    }

    /**
     * Whether this launch should reopen the groups the user had open.
     *
     * True for a launch aimed at a particular setting — a search result — and for a restart the
     * screen triggered itself, which would otherwise collapse everything the moment a setting that
     * restarts the app is changed. Everything else starts collapsed. Same rule as
     * [ani.dantotsu.settings.SettingsSectionAdapter], so the two kinds of card behave alike.
     */
    private fun shouldRestore(): Boolean {
        val scope = stateScope ?: return false
        if (PrefManager.getCustomVal(relaunchKey(scope), false)) return true
        return hostActivity()?.let { SettingsRouter.hasAnchor(it) } == true
    }

    private fun hostActivity(): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /**
     * Builds the card header, once the layout's own children are in place.
     *
     * Inserted at index 0 because everything else — the click target, the collapse, the search's
     * [expand] — treats the first child as the header and the rest as the body.
     */
    override fun onFinishInflate() {
        super.onFinishInflate()
        buildHeader()
    }

    /**
     * The same card built from code, for sections that only exist at runtime: call before adding
     * the body and before the view is attached. A card made this way sits flush in its parent.
     */
    fun setHeader(title: CharSequence, iconRes: Int = 0) {
        orientation = VERTICAL
        cardInset = false
        titleText = title
        this.iconRes = iconRes
        buildHeader()
    }

    private fun buildHeader() {
        val title = titleText ?: return
        val header = ItemSettingsSectionHeaderBinding.inflate(
            LayoutInflater.from(context), this, false
        )
        header.sectionTitle.text = title
        header.sectionSummary.text = summaryText
        header.sectionSummary.isVisible = !summaryText.isNullOrBlank()
        if (iconRes != 0) header.sectionIcon.setImageResource(iconRes)
        header.sectionIconTile.isVisible = iconRes != 0
        this.header = header
        addView(header.root, 0)
    }

    /** A group inside another group is a sub-section, not a card of its own — the subtitle preview
     *  sits inside Subtitles, and giving it the same chrome would draw a card within a card. */
    private fun isNested(): Boolean {
        var p = parent
        while (p != null) {
            if (p is Xpandable) return true
            p = (p as? android.view.View)?.parent
        }
        return false
    }

    private fun prefKey(key: String) = "settings_expanded_$key"

    private fun persist() {
        stateKey?.let { PrefManager.setCustomVal(prefKey(it), expanded) }
    }

    override fun onAttachedToWindow() {
        if (!isNested()) {
            fill = GradientDrawable().apply { cornerRadius = CARD_RADIUS }
            background = fill
            clipToOutline = true
            if (cardInset) updateLayoutParams<MarginLayoutParams> {
                topMargin = CARD_GAP
                marginStart = CARD_INSET
                marginEnd = CARD_INSET
            }
        }
        restingHeight = layoutParams.height

        val headerView = getChildAt(0)!!
        styleHeader(expanded, animate = false)
        setBodyPadding(expanded)

        headerView.setOnClickListener {
            expanded = !expanded
            persist()
            // Before the animation, so it grows or shrinks to the header as it will end up.
            refreshSummary()
            styleHeader(expanded, animate = true)
            if (expanded) {
                showAll()
                if (accordion) closeSiblings()
            } else hideAll()
        }
        if (!isNested()) SectionCard.pressFeedback(headerView, this)

        if (!expanded) children.forEach {
            if (it != headerView) {
                it.visibility = GONE
            }
        }
        super.onAttachedToWindow()
    }

    /**
     * While the card's height animates, its children are measured at full size and clipped, not
     * squeezed into the height the animation has got to. Squeezed, a wrap-content list measured at
     * nothing lays out no items, and a chip row came back empty after the card was reopened.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fixed = layoutParams?.height ?: LayoutParams.WRAP_CONTENT
        if (fixed < 0 || getTag(R.id.section_height_anim) == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(measuredWidth, resolveSize(fixed, heightMeasureSpec))
    }

    /**
     * Closes, with the usual animation, the open accordion cards at this one's level: the other
     * top-level cards on the screen, or for a sub-group, the other sub-groups of its card. By
     * level rather than by parent, as a screen may split its cards across containers.
     */
    private fun closeSiblings() {
        val owner = enclosingGroup()
        val scope: android.view.ViewGroup = owner ?: (rootView as? android.view.ViewGroup) ?: return
        fun visit(group: android.view.ViewGroup) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is Xpandable) {
                    if (child !== this && child.accordion && child.expanded && child.enclosingGroup() === owner) {
                        child.expanded = false
                        child.persist()
                        child.refreshSummary()
                        child.styleHeader(false, animate = true)
                        child.hideAll()
                    }
                    // Sub-groups belong to their own card's level, not this one's.
                    continue
                }
                if (child is android.view.ViewGroup) visit(child)
            }
        }
        visit(scope)
    }

    /** The card this one is a sub-group of, or null for a top-level card. */
    private fun enclosingGroup(): Xpandable? {
        var p = parent
        while (p != null) {
            if (p is Xpandable) return p
            p = p.parent
        }
        return null
    }

    /**
     * Bottom padding, only while open.
     *
     * The card draws an edge the last control would otherwise sit flush against, and the section
     * cards get this from their body's own margin, but an Xpandable's children come from the layout
     * and most end without one. Applied only when expanded, since padding under a collapsed card's
     * header would make it taller than the header it shows.
     */
    private fun setBodyPadding(open: Boolean) {
        if (isNested()) return
        setPadding(0, 0, 0, if (open) CARD_PAD else 0)
    }

    /** The header's open or closed look and the card's fill, the same as a section card's. */
    private fun styleHeader(open: Boolean, animate: Boolean) {
        val header = header
        if (header == null) {
            fill?.setColor(SectionCard.fill(this, open))
            return
        }
        SectionCard.bind(header, open, animate) { fill?.setColor(it) }
    }

    /** Grows to fit the body, which drifts in as it fades up. */
    private fun showAll() {
        val headerView = getChildAt(0)
        val from = height
        children.forEach {
            if (it != headerView) {
                it.visibility = VISIBLE
                SectionCard.revealContents(it)
            }
        }
        setBodyPadding(true)
        SectionCard.animateHeight(this, from, SectionCard.naturalHeight(this), restingHeight) {
            listeners.forEach { it.onExpand() }
        }
    }

    /** Shrinks back to the header, the body fading as the card closes over it. */
    private fun hideAll() {
        val headerView = getChildAt(0)
        children.forEach { if (it != headerView) SectionCard.hideContents(it) }
        SectionCard.animateHeight(this, height, SectionCard.naturalHeight(headerView), restingHeight) {
            children.forEach {
                if (it != headerView) {
                    it.visibility = GONE
                    it.alpha = 1f
                }
            }
            // Cleared with the children, not before them, so the card does not jump mid-collapse.
            setBodyPadding(false)
            listeners.forEach { it.onRetract() }
        }
    }

    /** Changes the line under the title; blank hides it. For a section that sums up its content. */
    fun setSummary(text: CharSequence?) {
        summaryText = text
        header?.let {
            it.sectionSummary.text = text
            it.sectionSummary.isVisible = !text.isNullOrBlank()
        }
    }

    /**
     * Sums the card up under its title while it is closed, so what is set in it shows without
     * opening it; open, the controls say it themselves and the line is hidden rather than left to
     * go stale. Call [refreshSummary] when the controls change from outside the card, as a reset.
     */
    fun summarizeWhenClosed(summary: () -> CharSequence?) {
        summaryProvider = summary
        refreshSummary()
    }

    fun refreshSummary() {
        val provider = summaryProvider ?: return
        setSummary(if (expanded) null else provider())
    }

    /**
     * Immediately expands the section (no animation, no-op if already expanded). Used by the
     * settings search to reveal a control before scrolling to and highlighting it.
     */
    fun expand() {
        if (expanded) return
        expanded = true
        persist()
        refreshSummary()
        styleHeader(true, animate = false)
        setBodyPadding(true)
        children.forEach {
            if (it != getChildAt(0)) it.visibility = VISIBLE
        }
    }

    @Suppress("unused")
    fun addOnChangeListener(listener: OnChangeListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: OnChangeListener) {
        listeners.remove(listener)
    }

    interface OnChangeListener {
        fun onExpand()
        fun onRetract()
    }

    companion object {
        /** Matches item_settings_section.xml's cardCornerRadius. */
        private val CARD_RADIUS get() = 24f.px.toFloat()

        /** Matches item_settings_section.xml's layout_marginTop. */
        private val CARD_GAP get() = 10f.px

        /** The horizontal inset the settings lists use, so cards line up across screens. */
        private val CARD_INSET get() = 24f.px

        /** Matches the bottom margin item_settings_section.xml gives its body. */
        private val CARD_PAD get() = 10f.px

        private fun relaunchKey(scope: String) = "settings_relaunch_$scope"

        /**
         * Call immediately before a restart the screen triggers itself, so the groups the user has
         * open survive it. [ani.dantotsu.restartApp] starts a fresh Intent carrying no extras, so
         * without this a setting change is indistinguishable from opening the screen cold.
         */
        fun markRelaunch(scope: String) = PrefManager.setCustomVal(relaunchKey(scope), true)

        /**
         * Call once after `setContentView`, when every group has read the marker. Consumed rather
         * than left set, so one relaunch restores once and the entry after it starts collapsed.
         */
        fun consumeRelaunch(scope: String) = PrefManager.setCustomVal(relaunchKey(scope), false)

        const val SCOPE_PLAYER = "player"
        const val SCOPE_READER = "reader"
        const val SCOPE_ANILIST = "anilist"
    }
}
