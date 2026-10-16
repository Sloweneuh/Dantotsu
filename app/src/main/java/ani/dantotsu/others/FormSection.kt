package ani.dantotsu.others

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.content.withStyledAttributes
import androidx.core.view.isVisible
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemSettingsSectionHeaderBinding
import ani.dantotsu.px
import ani.dantotsu.settings.SectionCard

/**
 * One group of a create/edit form's fields, carded like the settings sections.
 *
 * Same chrome as an [Xpandable] or a settings section card — the header, its tinted icon tile, the
 * fill and radius all come from [SectionCard] — so the forms read as the same app as the screens
 * around them. It just never collapses: a form's fields are all meant to be seen, so there is no
 * chevron and the header isn't a button. The layout's own children are the body.
 */
class FormSection @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private var titleText: CharSequence? = null
    private var summaryText: CharSequence? = null
    private var iconRes: Int = 0
    private var header: ItemSettingsSectionHeaderBinding? = null

    init {
        orientation = VERTICAL
        context.withStyledAttributes(attrs, R.styleable.FormSection) {
            titleText = getText(R.styleable.FormSection_title)
            summaryText = getText(R.styleable.FormSection_summary)
            iconRes = getResourceId(R.styleable.FormSection_icon, 0)
        }
        // Unless the layout set its own (a toolbar wants less than a group of fields does).
        if (paddingStart == 0 && paddingTop == 0 && paddingEnd == 0 && paddingBottom == 0) {
            setPadding(16f.px, if (titleText == null) 16f.px else 0, 16f.px, 16f.px)
        }
        background = GradientDrawable().apply {
            cornerRadius = 24f.px.toFloat()
            setColor(SectionCard.fill(this@FormSection, open = false))
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        val title = titleText ?: return
        val header = ItemSettingsSectionHeaderBinding.inflate(LayoutInflater.from(context), this, false)
        header.root.apply {
            isClickable = false
            isFocusable = false
            background = null
            minimumHeight = 0
            // The card's own padding already insets it.
            setPadding(0, 14f.px, 0, 12f.px)
        }
        header.sectionTitle.text = title
        header.sectionSummary.text = summaryText
        header.sectionSummary.isVisible = !summaryText.isNullOrBlank()
        if (iconRes != 0) header.sectionIcon.setImageResource(iconRes)
        header.sectionIconTile.isVisible = iconRes != 0
        header.sectionChevron.isVisible = false
        this.header = header
        addView(header.root, 0)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The closed look of a section card: primary-washed tile, primary icon.
        header?.let { SectionCard.bind(it, open = false, animate = false) {} }
    }

    /** Replaces the summary under the title, e.g. to show a count. */
    fun setSummary(text: CharSequence?) {
        summaryText = text
        header?.sectionSummary?.apply {
            this.text = text
            isVisible = !text.isNullOrBlank()
        }
    }
}
