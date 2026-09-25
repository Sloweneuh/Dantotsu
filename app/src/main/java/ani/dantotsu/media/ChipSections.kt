package ani.dantotsu.media

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import ani.dantotsu.R
import ani.dantotsu.others.ChipScrollView
import com.google.android.material.chip.ChipGroup

/**
 * Shared behaviour for the titled chip rows (tags, genres, categories, …) across every info
 * screen: one line by default, expanded to as many lines as the chips need on request.
 *
 * A single scrolling line keeps a long tag list from swallowing the page, but it hides how much
 * there is and makes finding one tag a lot of swiping - and the screens that wrapped their chips
 * instead just moved the problem, since a list that starts as a block of forty chips buries
 * everything under it. The chevron lets the reader choose, and only appears when the chips
 * actually overflow the line.
 */
object ChipSections {

    /**
     * Wires a chevron to its chip row. Sections don't call this themselves - their root
     * [ani.dantotsu.others.ChipSectionLayout] does it as the layout inflates.
     */
    fun collapsible(
        scroll: ChipScrollView,
        group: ChipGroup,
        chevron: ImageView,
        expanded: Boolean = false,
    ) {
        var isExpanded = expanded

        fun apply() {
            group.isSingleLine = !isExpanded
            scroll.wrapChild = isExpanded
            if (!isExpanded) scroll.scrollX = 0
            chevron.animate().rotation(if (isExpanded) 180f else 0f).setDuration(200).start()
            chevron.contentDescription =
                chevron.context.getString(if (isExpanded) R.string.collapse else R.string.expand)
        }

        apply()

        // The chevron earns its place only when there is something off-screen to reveal. That is
        // known once the chips are laid out, and again whenever the row is re-measured (a rotation,
        // or chips arriving from a request after this call). Once expanded it stays, since it is
        // then the way back - and the visibility change is posted, so it never re-enters layout.
        group.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (isExpanded) return@addOnLayoutChangeListener
            val viewport = scroll.width - scroll.paddingStart - scroll.paddingEnd
            // A lone chip has nothing to wrap onto a second line - it just scrolls.
            val chips = (0 until group.childCount).count { group.getChildAt(it).visibility != View.GONE }
            val overflows = chips > 1 && viewport > 0 && group.width > viewport
            if (overflows != chevron.isVisible) chevron.post { chevron.isVisible = overflows }
        }

        chevron.setOnClickListener {
            isExpanded = !isExpanded
            apply()
        }
    }

    /**
     * Turns [action] into the "Show spoilers" / "Hide spoilers" toggle for a section whose chips
     * mask their spoilers. [onChanged] is handed the new state, so a section that rebuilds its
     * chips (MangaBaka's weight filter) can keep them in step with what the toggle last said.
     */
    fun spoilerToggle(action: TextView, showing: Boolean = false, onChanged: (Boolean) -> Unit) {
        var state = showing
        fun sync() {
            action.setText(if (state) R.string.hide_spoiler_tags else R.string.show_spoiler_tags)
        }
        action.visibility = View.VISIBLE
        sync()
        action.setOnClickListener {
            state = !state
            onChanged(state)
            sync()
        }
    }

    /** Blocks of a length that hints at the tag's without giving its shape away exactly. */
    fun mask(name: String) = "▓".repeat(name.length.coerceIn(3, 12))
}
