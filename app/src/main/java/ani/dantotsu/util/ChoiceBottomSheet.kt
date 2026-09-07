package ani.dantotsu.util

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
 * A single-choice bottom sheet, in the style the extensions screen uses for its language picker.
 *
 * The same thing built by hand there, extracted so the next caller does not build a third copy of
 * it. Deliberately not the framework's single-choice alert: a list of a hundred languages in an
 * alert is a cramped scroller in the middle of the screen, where a sheet gets the height and the
 * thumb reach, and the app already made that choice once.
 *
 * Picking dismisses. There is no confirm button because there is nothing to confirm — the choice
 * *is* the action, and an OK button would only add a step in which nothing can change.
 */
fun Context.choiceBottomSheet(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    val sheet = BottomSheetDialog(this)
    val dp = resources.displayMetrics.density
    val scrollView = NestedScrollView(this)
    val onBackground = MaterialColors.getColor(
        scrollView,
        com.google.android.material.R.attr.colorOnBackground,
    )

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bottom_sheet_background)
        val side = (24 * dp).toInt()
        setPadding(side, (20 * dp).toInt(), side, navBarHeight + (16 * dp).toInt())
    }

    container.addView(
        AppCompatTextView(this).apply {
            text = title
            textSize = 18f
            typeface = ResourcesCompat.getFont(this@choiceBottomSheet, R.font.poppins_bold)
            setTextColor(onBackground)
            setPadding(0, 0, 0, (12 * dp).toInt())
        },
    )

    container.addView(
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                .also { it.bottomMargin = (12 * dp).toInt() }
            alpha = 0.12f
            setBackgroundColor(onBackground)
        },
    )

    val group = RadioGroup(this).apply {
        orientation = RadioGroup.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }
    options.forEachIndexed { index, label ->
        MaterialRadioButton(this).apply {
            id = index
            text = label
            textSize = 15f
            typeface = ResourcesCompat.getFont(this@choiceBottomSheet, R.font.poppins_semi_bold)
            isChecked = index == selectedIndex
            minHeight = (48 * dp).toInt()
            layoutParams = RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT,
                RadioGroup.LayoutParams.WRAP_CONTENT,
            )
            group.addView(this)
        }
    }
    group.setOnCheckedChangeListener { _, which ->
        // Only a real change is worth reporting; re-picking what was already set should not make
        // the caller redo work, and setting the initial state above must not fire the callback.
        if (which >= 0 && which != selectedIndex) onSelected(which)
        sheet.dismiss()
    }

    container.addView(group)
    scrollView.addView(container)
    sheet.setContentView(scrollView)
    sheet.show()
}
