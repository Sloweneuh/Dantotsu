package ani.dantotsu.media.novel.novelreader

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.NestedScrollView
import ani.dantotsu.R
import ani.dantotsu.navBarHeight
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.radiobutton.MaterialRadioButton

/**
 * The novel font picker: the same sheet as the app font's, every row drawn in the font it offers.
 * Fonts imported there are offered here too; importing one is left to that picker.
 */
object NovelFontPicker {

    fun show(context: Context, current: String, onSelect: (String) -> Unit) {
        val options = NovelFonts.options(context)
        val selected = options.indexOfFirst { it.key == current }.coerceAtLeast(0)
        val dp = context.resources.displayMetrics.density
        val onBg = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnBackground, 0)
        val fallback = ResourcesCompat.getFont(context, R.font.poppins_semi_bold)
        val sheet = BottomSheetDialog(context)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bottom_sheet_background)
            val h = (24 * dp).toInt()
            setPadding(h, (20 * dp).toInt(), h, navBarHeight + (16 * dp).toInt())
        }
        container.addView(AppCompatTextView(context).apply {
            setText(R.string.novel_font)
            textSize = 18f
            typeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
            setTextColor(onBg)
            setPadding(0, 0, 0, (12 * dp).toInt())
        })
        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                .also { it.bottomMargin = (12 * dp).toInt() }
            alpha = 0.12f
            setBackgroundColor(onBg)
        })

        val group = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        options.forEachIndexed { i, option ->
            group.addView(MaterialRadioButton(context).apply {
                id = i
                text = option.label
                textSize = 15f
                typeface = option.preview ?: fallback
                isChecked = i == selected
                minHeight = (48 * dp).toInt()
            }, RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        group.setOnCheckedChangeListener { _, which ->
            if (which in options.indices && which != selected) onSelect(options[which].key)
            sheet.dismiss()
        }
        container.addView(group)

        sheet.setContentView(NestedScrollView(context).apply { addView(container) })
        sheet.show()
    }
}
