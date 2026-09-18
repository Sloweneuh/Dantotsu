package ani.dantotsu.others

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import ani.dantotsu.media.ChipSections
import com.google.android.material.chip.ChipGroup

/**
 * The root of a titled chip row, wiring its own expand chevron as it inflates.
 *
 * Every chip row in the app - tags, genres, categories, synonyms, external links, studios - wants
 * the same thing, so binding it here rather than at each of the forty-odd places that inflate one
 * means none of them can forget, and a section added later gets it for free. The chevron shows
 * itself only when the chips overflow the line, so a row of three synonyms still looks like a row
 * of three synonyms.
 */
class ChipSectionLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    override fun onFinishInflate() {
        super.onFinishInflate()
        // Found by tag rather than id: the two section layouts name their views differently, and
        // the code that fills them addresses those ids by name.
        val chevron = findViewWithTag<ImageView>(EXPAND_TAG) ?: return
        val scroll = findChipScroll(this) ?: return
        val group = scroll.getChildAt(0) as? ChipGroup ?: return
        ChipSections.collapsible(scroll, group, chevron)
    }

    private fun findChipScroll(view: android.view.View): ChipScrollView? = when {
        view is ChipScrollView -> view
        view is android.view.ViewGroup -> (0 until view.childCount)
            .firstNotNullOfOrNull { findChipScroll(view.getChildAt(it)) }

        else -> null
    }

    private companion object {
        const val EXPAND_TAG = "chipExpand"
    }
}
