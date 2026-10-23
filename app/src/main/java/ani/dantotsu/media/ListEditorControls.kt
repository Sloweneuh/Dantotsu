package ani.dantotsu.media

import android.view.Gravity
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.FuzzyDate
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * A list editor's score, as a slider with a typed field beside it, in the user's AniList score
 * format, over AniList's 0–100 raw scale the editors already save (null = no score).
 *
 * The slider runs 0–10 and its step follows the format: whole points for 10-point, two for 5-star,
 * halves otherwise — coarse enough for its step marks to show. The field takes any score the format
 * allows (7.3, 73) and the slider follows it to the nearest step; whichever was used last is what
 * [score] gives back. Untouched, it gives back the score as it was, so opening and saving the editor
 * never changes it.
 */
class ScoreSlider(
    private val slider: Slider,
    private val field: TextInputEditText,
    fieldLayout: TextInputLayout,
    initial: Int?,
) {
    private val format = Anilist.scoreFormat
    /** The raw 0–100 score to save, null for none. */
    var score: Int? = initial
        private set
    /** Set while one control updates the other, so the update isn't taken for an edit. */
    private var syncing = false

    init {
        val step = when (format) {
            "POINT_10" -> 1f
            "POINT_5" -> 2f
            else -> 0.5f
        }
        slider.valueFrom = 0f
        slider.valueTo = 10f
        slider.stepSize = step
        moveSlider(initial)
        slider.setLabelFormatter { label(it) }
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || syncing) return@addOnChangeListener
            score = if (value > 0f) (value * 10).roundToInt() else null
            showInField(score)
        }

        fieldLayout.suffixText = when (format) {
            "POINT_100" -> "/100"
            "POINT_5" -> "/5"
            else -> "/10"
        }
        // Material pins the suffix to the box's top in a fixed-height field; centre it like the text.
        fieldLayout.suffixTextView.updateLayoutParams { height = ViewGroup.LayoutParams.MATCH_PARENT }
        fieldLayout.suffixTextView.gravity = Gravity.CENTER
        showInField(initial)
        field.doAfterTextChanged { text ->
            if (syncing) return@doAfterTextChanged
            score = parse(text?.toString().orEmpty())
            moveSlider(score)
        }
    }

    /** The field's text in the format's units, as raw 0–100; out of range counts as no score. */
    private fun parse(text: String): Int? {
        val value = text.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f } ?: return null
        val raw = when (format) {
            "POINT_100" -> value
            "POINT_5" -> value * 20
            else -> value * 10
        }
        return raw.roundToInt().takeIf { it in 1..100 }
    }

    private fun showInField(raw: Int?) {
        syncing = true
        field.setText(raw?.let { inFormat(it) } ?: "")
        field.setSelection(field.text?.length ?: 0)
        syncing = false
    }

    private fun moveSlider(raw: Int?) {
        syncing = true
        val step = slider.stepSize
        slider.value = (((raw ?: 0) / 10f / step).roundToInt() * step).coerceIn(0f, 10f)
        syncing = false
    }

    /** A raw 0–100 score written in the format's own units. */
    private fun inFormat(raw: Int): String = when (format) {
        "POINT_100" -> "$raw"
        "POINT_5" -> "${(raw / 20f).roundToInt()}"
        "POINT_10" -> "${(raw / 10f).roundToInt()}"
        else -> (raw / 10f).let { if (it % 1f == 0f) "${it.toInt()}" else "%.1f".format(it) }
    }

    /** The slider's bubble while dragging. */
    private fun label(value: Float): String =
        if (value <= 0f) slider.context.getString(R.string.list_editor_no_score)
        else inFormat((value * 10).roundToInt())
}

/**
 * A list editor's start or completion date: Material's date picker opened from [field], and a clear
 * icon at the field's end while there's a date, standing in for the old picker's Remove button.
 * [date] is the date the editor saves; [set] updates it and the field together.
 */
class ListDateField(
    private val host: Fragment,
    private val layout: TextInputLayout,
    private val field: AutoCompleteTextView,
    initial: FuzzyDate,
    private val tag: String,
) {
    var date: FuzzyDate = initial
        private set

    init {
        layout.setEndIconOnClickListener { set(FuzzyDate()) }
        field.setOnClickListener { open() }
        field.setOnFocusChangeListener { _, focused -> if (focused) open() }
        set(initial)
    }

    fun set(value: FuzzyDate) {
        date = value
        field.setText(value.toStringOrEmpty())
        layout.isEndIconVisible = !value.isEmpty()
    }

    private fun open() {
        val fm = host.childFragmentManager
        if (fm.findFragmentByTag(tag) != null) return
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(layout.hint)
            .setSelection(date.toUtcMillis() ?: MaterialDatePicker.todayInUtcMilliseconds())
            .build()
        picker.addOnPositiveButtonClickListener { millis -> set(fuzzyDateOf(millis)) }
        // The field keeps focus once the picker closes; let it go, so tapping it again reopens.
        picker.addOnDismissListener { field.clearFocus() }
        picker.show(fm, tag)
    }

    private fun FuzzyDate.toUtcMillis(): Long? {
        val y = year ?: return null
        return utc().apply {
            clear()
            set(y, (month ?: 1) - 1, day ?: 1)
        }.timeInMillis
    }

    private fun fuzzyDateOf(millis: Long): FuzzyDate {
        val c = utc().apply { timeInMillis = millis }
        return FuzzyDate(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun utc(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
}

/** Steps a number field down by one, never below zero. */
internal fun AutoCompleteTextView.decrement() {
    val current = text.toString().toIntOrNull() ?: return
    if (current > 0) setText("${current - 1}")
}
