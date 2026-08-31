package ani.dantotsu.others

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.View
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemSettingsSectionHeaderBinding
import ani.dantotsu.px
import ani.dantotsu.settings.SettingsRouter
import ani.dantotsu.settings.saving.PrefManager

/**
 * A collapsible group of settings, styled as a card.
 *
 * The chrome deliberately matches `item_settings_section.xml`, the card
 * [ani.dantotsu.settings.SettingsSectionAdapter] renders — same 22dp radius, surface fill, outline
 * stroke and rotating chevron — because the Player and Reader screens sit beside screens built from
 * that adapter and used to read as a different component entirely.
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

    /** The chevron on a header this view built. Null when the layout supplied its own header. */
    private var chevron: View? = null

    private var stateScope: String? = null

    init {
        context.withStyledAttributes(attrs, R.styleable.Xpandable) {
            expanded = getBoolean(R.styleable.Xpandable_isExpanded, expanded)
            stateKey = getString(R.styleable.Xpandable_stateKey)
            stateScope = getString(R.styleable.Xpandable_stateScope)
            titleText = getText(R.styleable.Xpandable_title)
            summaryText = getText(R.styleable.Xpandable_summary)
            iconRes = getResourceId(R.styleable.Xpandable_icon, 0)
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
        val title = titleText ?: return
        val header = ItemSettingsSectionHeaderBinding.inflate(
            LayoutInflater.from(context), this, false
        )
        header.sectionTitle.text = title
        header.sectionSummary.text = summaryText
        header.sectionSummary.isVisible = !summaryText.isNullOrBlank()
        if (iconRes != 0) header.sectionIcon.setImageResource(iconRes)
        header.sectionIcon.isVisible = iconRes != 0
        chevron = header.sectionChevron
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
            background = ContextCompat.getDrawable(context, R.drawable.bg_settings_section_card)
            clipToOutline = true
            updateLayoutParams<MarginLayoutParams> {
                topMargin = CARD_GAP
                marginStart = CARD_INSET
                marginEnd = CARD_INSET
            }
        }

        val header = getChildAt(0)!!
        setChevronRotated(expanded, animate = false)

        header.setOnClickListener {
            if (expanded) hideAll() else showAll()
            setChevronRotated(!expanded, animate = true)
            postDelayed({
                expanded = !expanded
                persist()
            }, 300)
        }

        if (!expanded) children.forEach {
            if (it != header) {
                it.visibility = GONE
            }
        }
        super.onAttachedToWindow()
    }

    private fun setChevronRotated(open: Boolean, animate: Boolean) {
        val c = chevron ?: return
        val target = if (open) 180f else 0f
        c.animate().cancel()
        if (animate) c.animate().rotation(target).setDuration(200).start() else c.rotation = target
    }

    private fun hideAll() {
        children.forEach {
            if (it != getChildAt(0)) {
                ObjectAnimator.ofFloat(it, "scaleY", 1f, 0.5f).setDuration(200).start()
                ObjectAnimator.ofFloat(it, "translationY", 0f, -32f).setDuration(200).start()
                ObjectAnimator.ofFloat(it, "alpha", 1f, 0f).setDuration(200).start()
                it.postDelayed({
                    it.visibility = GONE
                }, 300)
            }
        }
        postDelayed({
            listeners.forEach {
                it.onRetract()
            }
        }, 300)
    }

    private fun showAll() {
        children.forEach {
            if (it != getChildAt(0)) {
                it.visibility = VISIBLE
                ObjectAnimator.ofFloat(it, "scaleY", 0.5f, 1f).setDuration(200).start()
                ObjectAnimator.ofFloat(it, "translationY", -32f, 0f).setDuration(200).start()
                ObjectAnimator.ofFloat(it, "alpha", 0f, 1f).setDuration(200).start()
            }
        }
        postDelayed({
            listeners.forEach {
                it.onExpand()
            }
        }, 300)
    }

    /**
     * Immediately expands the section (no animation, no-op if already expanded). Used by the
     * settings search to reveal a control before scrolling to and highlighting it.
     */
    fun expand() {
        if (expanded) return
        expanded = true
        persist()
        setChevronRotated(true, animate = false)
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
        /** Matches item_settings_section.xml's layout_marginTop. */
        private val CARD_GAP get() = 10f.px

        /** The horizontal inset the settings lists use, so cards line up across screens. */
        private val CARD_INSET get() = 24f.px

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
    }
}
