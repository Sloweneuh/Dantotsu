package ani.dantotsu.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityWidgetConfigureBinding
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.widgets.list.WaitingWidget
import ani.dantotsu.widgets.statistics.ProfileStat
import eltos.simpledialogfragment.SimpleDialog
import eltos.simpledialogfragment.color.SimpleColorDialog

/**
 * The configure screen for every widget.
 *
 * One screen for all four, because they are configured the same way. It replaces a pair of
 * near-identical activities whose entire UI was four full-width buttons — "Top Background Color",
 * "Bottom Background Color", … — each opening a colour wheel, with nothing showing what the result
 * would look like and no way to say "just match my theme".
 *
 * Which widget is being configured is read from the widget id itself, so the system's own configure
 * intent (which carries nothing else) is enough.
 */
class WidgetConfigureActivity : AppCompatActivity(), SimpleDialog.OnDialogResultListener {

    private lateinit var binding: ActivityWidgetConfigureBinding
    private lateinit var prefs: WidgetPrefs
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var provider: ComponentName? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        // Cancelled unless Save is pressed: a widget being added for the first time must not stay on
        // the home screen if the user backs out of configuring it.
        setResult(RESULT_CANCELED)

        binding = ActivityWidgetConfigureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The activity draws edge to edge, so the system bars have to be accounted for: Save and Cancel
        // sit exactly where the navigation bar is and rendered underneath it. Read from the insets
        // rather than the app's navBarHeight global — that is filled in by whichever activity ran
        // initActivity() first, and is still zero when a widget's settings are opened from a launcher.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.updatePadding(top = bars.top)
            binding.widgetActions.updatePadding(bottom = bars.bottom)
            insets
        }

        appWidgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // The system's configure intent carries only the widget id, so the provider is looked up from
        // it; our own tap targets pass the name along as well, which covers the window during which a
        // brand-new widget has no info registered yet.
        provider = AppWidgetManager.getInstance(this)?.getAppWidgetInfo(appWidgetId)?.provider
            ?: intent.extras?.getString(EXTRA_PROVIDER)?.let { ComponentName(this, it) }
        prefs = WidgetPrefs.of(this, appWidgetId)

        setUpTheme()
        setUpColors()
        setUpListOptions()
        setUpStatsOptions()

        binding.widgetSave.setOnClickListener { save() }
        binding.widgetCancel.setOnClickListener { finish() }

        renderPreview()
    }

    // region theme

    private fun setUpTheme() {
        val materialYouAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        binding.themeMaterialYou.isEnabled = materialYouAvailable
        binding.themeMaterialYouUnavailable.isVisible = !materialYouAvailable

        binding.themeGroup.check(
            when (prefs.themeMode) {
                WidgetThemeMode.MATERIAL_YOU ->
                    if (materialYouAvailable) R.id.themeMaterialYou else R.id.themeAppTheme

                WidgetThemeMode.APP_THEME -> R.id.themeAppTheme
                WidgetThemeMode.CUSTOM -> R.id.themeCustom
            }
        )
        binding.themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.themeMode = when (checkedId) {
                R.id.themeAppTheme -> WidgetThemeMode.APP_THEME
                R.id.themeCustom -> WidgetThemeMode.CUSTOM
                else -> WidgetThemeMode.MATERIAL_YOU
            }
            renderPreview()
        }
    }

    /** The colour rows only mean anything in custom mode, so they are only shown there. */
    private fun setUpColors() {
        binding.backgroundColorRow.setOnClickListener {
            pickColor(TAG_BACKGROUND, prefs.backgroundColor, withAlpha = true)
        }
        binding.titleColorRow.setOnClickListener {
            pickColor(TAG_TITLE, prefs.titleColor, withAlpha = false)
        }
        binding.subtitleColorRow.setOnClickListener {
            pickColor(TAG_SUBTITLE, prefs.subtitleColor, withAlpha = false)
        }
        binding.fadeBackground.isChecked = prefs.fadeBackground
        binding.fadeBackground.setOnCheckedChangeListener { _, checked ->
            prefs.fadeBackground = checked
            renderPreview()
        }
    }

    private fun pickColor(tag: String, preset: Int, withAlpha: Boolean) {
        SimpleColorDialog().title(R.string.widget_pick_colour)
            .colorPreset(preset)
            .colors(this, SimpleColorDialog.MATERIAL_COLOR_PALLET)
            .setupColorWheelAlpha(withAlpha)
            .allowCustom(true)
            .showOutline(0x46000000)
            .gridNumColumn(5)
            .choiceMode(SimpleColorDialog.SINGLE_CHOICE)
            .neg()
            .show(this, tag)
    }

    override fun onResult(dialogTag: String, which: Int, extras: Bundle): Boolean {
        if (which != SimpleDialog.OnDialogResultListener.BUTTON_POSITIVE) return false
        val color = extras.getInt(SimpleColorDialog.COLOR)
        when (dialogTag) {
            TAG_BACKGROUND -> prefs.backgroundColor = color
            TAG_TITLE -> prefs.titleColor = color
            TAG_SUBTITLE -> prefs.subtitleColor = color
            else -> return false
        }
        renderPreview()
        return true
    }

    // endregion

    /** Row count, covers and content type — only the list widgets have any of these. */
    private fun setUpListOptions() {
        val isList = provider?.className != PROFILE_STATS_CLASS
        binding.listOptions.isVisible = isList
        if (!isList) return

        binding.itemLimitSlider.valueFrom = 1f
        binding.itemLimitSlider.valueTo = WidgetPrefs.MAX_ITEM_LIMIT.toFloat()
        binding.itemLimitSlider.value = prefs.itemLimit.toFloat()
        binding.itemLimitSlider.addOnChangeListener { _, value, _ ->
            prefs.itemLimit = value.toInt()
            binding.itemLimitValue.text = value.toInt().toString()
        }

        // "Show all" makes the slider meaningless rather than merely inactive, so it's disabled (not
        // just left at whatever number it was on) whenever the switch is checked.
        fun updateItemLimitUi(showAll: Boolean) {
            binding.itemLimitSlider.isEnabled = !showAll
            binding.itemLimitValue.text =
                if (showAll) getString(R.string.widget_rows_all) else prefs.itemLimit.toString()
        }
        binding.showAllItems.isChecked = prefs.showAllItems
        updateItemLimitUi(prefs.showAllItems)
        binding.showAllItems.setOnCheckedChangeListener { _, checked ->
            prefs.showAllItems = checked
            updateItemLimitUi(checked)
        }

        // The schedule widget's rows are always the compact, cover-less calendarRow() — the switch
        // would change nothing on the real widget, so it isn't offered there.
        binding.showCovers.isVisible = provider?.className != SCHEDULE_CLASS
        binding.showCovers.isChecked = prefs.showCovers
        binding.showCovers.setOnCheckedChangeListener { _, checked ->
            prefs.showCovers = checked
            renderPreview()
        }

        // Only the waiting widget mixes types; the airing ones are anime by definition.
        val mixesTypes = provider?.className == WaitingWidget::class.java.name
        binding.contentGroupContainer.isVisible = mixesTypes
        if (mixesTypes) {
            binding.contentGroup.check(
                when (prefs.content) {
                    WidgetContent.ANIME -> R.id.contentAnime
                    WidgetContent.MANGA -> R.id.contentManga
                    WidgetContent.BOTH -> R.id.contentBoth
                }
            )
            binding.contentGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                prefs.content = when (checkedId) {
                    R.id.contentAnime -> WidgetContent.ANIME
                    R.id.contentManga -> WidgetContent.MANGA
                    else -> WidgetContent.BOTH
                }
            }
        }
    }

    /**
     * The four "which stat" dropdowns, shown only for the stats widget.
     *
     * Mirrors the home screen's own configurable stat row (`showHomeStatsPopup`) — same options, same
     * string resources — just persisted per widget instance like everything else here, and with four
     * slots instead of two since the widget's grid has four cells to fill.
     */
    private fun setUpStatsOptions() {
        val isStats = provider?.className == PROFILE_STATS_CLASS
        binding.statsOptions.isVisible = isStats
        if (!isStats) return

        val options = ProfileStat.entries
        val labels = options.map { getString(it.labelRes) }
        val adapter = { ArrayAdapter(this, R.layout.item_dropdown, labels) }

        fun bind(dropdown: AutoCompleteTextView, current: ProfileStat, onPicked: (ProfileStat) -> Unit) {
            dropdown.setAdapter(adapter())
            dropdown.setText(getString(current.labelRes), false)
            dropdown.setOnItemClickListener { _, _, position, _ ->
                onPicked(options[position])
                renderPreview()
            }
        }

        bind(binding.statSlot1Dropdown, prefs.statSlot1) { prefs.statSlot1 = it }
        bind(binding.statSlot2Dropdown, prefs.statSlot2) { prefs.statSlot2 = it }
        bind(binding.statSlot3Dropdown, prefs.statSlot3) { prefs.statSlot3 = it }
        bind(binding.statSlot4Dropdown, prefs.statSlot4) { prefs.statSlot4 = it }
    }

    /**
     * Redraws the sample widget above the options.
     *
     * Painted by [WidgetStyle.applyTo], the same call the real widget makes, so the preview cannot
     * drift from the result.
     */
    private fun renderPreview() {
        val style = WidgetStyle.of(this, prefs)
        binding.customColors.isVisible = prefs.themeMode == WidgetThemeMode.CUSTOM

        val isStats = provider?.className == PROFILE_STATS_CLASS
        val isSchedule = provider?.className == SCHEDULE_CLASS
        val isWaiting = provider?.className == WaitingWidget::class.java.name
        style.applyTo(binding.preview.widgetBackground)
        with(binding.preview) {
            widgetTitle.setTextColor(style.title)
            widgetTitle.text = getString(previewTitleRes())

            previewRows.isVisible = !isStats && !isSchedule
            previewCalendarRows.isVisible = isSchedule
            previewStats.isVisible = isStats
            previewStatsRowTwo.isVisible = isStats

            previewTitleOne.setTextColor(style.title)
            previewTitleTwo.setTextColor(style.title)
            previewSubtitleOne.setTextColor(style.subtitle)
            previewSubtitleTwo.setTextColor(style.subtitle)
            previewCoverOne.isVisible = prefs.showCovers
            previewCoverTwo.isVisible = prefs.showCovers

            // Row one's sample swaps with the widget: "counts behind" reads correctly only for the
            // waiting widget, an airing countdown only for upcoming. Row two stays Vinland Saga either
            // way; only its subtitle needs to change to end in "·" and pair with the icon below.
            previewCoverOne.setImageResource(
                if (isWaiting) R.drawable.preview_cover_onepiece else R.drawable.preview_cover_frieren
            )
            previewTitleOne.text = getString(
                if (isWaiting) R.string.widget_preview_title_three else R.string.widget_preview_title
            )
            previewSubtitleOne.text = getString(
                if (isWaiting) R.string.widget_preview_subtitle_waiting else R.string.widget_preview_subtitle
            )
            previewSubtitleTwo.text = getString(
                if (isWaiting) R.string.widget_preview_subtitle_waiting_two
                else R.string.widget_preview_subtitle_two
            )

            // Only the waiting widget's rows can end in the dub/sub icon + code instead of text — see
            // MediaListFactory.mediaRow().
            previewLanguageIcon.isVisible = isWaiting
            previewLanguageCode.isVisible = isWaiting
            previewLanguageCode.setTextColor(style.subtitle)
            previewLanguageIcon.setColorFilter(style.subtitle)

            for (day in listOf(previewCalendarDayOne, previewCalendarDayTwo)) {
                day.setTextColor(style.accent)
            }
            for (time in listOf(previewCalendarTimeOne, previewCalendarTimeTwo, previewCalendarTimeThree)) {
                time.setTextColor(style.accent)
            }
            for (title in listOf(previewCalendarTitleOne, previewCalendarTitleTwo, previewCalendarTitleThree)) {
                title.setTextColor(style.title)
            }

            // Sample values stay fixed placeholders (no network call from a configure screen); only the
            // label swaps to whatever the matching dropdown is currently set to, so all four slots give
            // live feedback the same way the colour and cover options do.
            for ((value, label, slot) in listOf(
                Triple(previewStatValueOne, previewStatLabelOne, prefs.statSlot1),
                Triple(previewStatValueTwo, previewStatLabelTwo, prefs.statSlot2),
                Triple(previewStatValueThree, previewStatLabelThree, prefs.statSlot3),
                Triple(previewStatValueFour, previewStatLabelFour, prefs.statSlot4)
            )) {
                value.setTextColor(style.title)
                label.setTextColor(style.subtitle)
                val hidden = slot == ProfileStat.NONE
                value.isInvisible = hidden
                label.isInvisible = hidden
                if (!hidden) label.text = getString(slot.labelRes)
            }
        }

        binding.backgroundSwatch.setSwatchColor(style.background)
        binding.titleSwatch.setSwatchColor(style.title)
        binding.subtitleSwatch.setSwatchColor(style.subtitle)
    }

    private fun previewTitleRes(): Int = when (provider?.className) {
        // profile_stats_widget is the picker's *description* string (android:description in
        // statistics_widget_info.xml) — the widget's actual name is its manifest receiver label.
        PROFILE_STATS_CLASS -> R.string.widget_stats_title
        WaitingWidget::class.java.name -> R.string.widget_waiting
        SCHEDULE_CLASS -> R.string.widget_this_week
        else -> R.string.upcoming
    }

    private fun View.setSwatchColor(color: Int) {
        background?.mutate()?.setTint(color or 0xFF000000.toInt())
        alpha = if (Color.alpha(color) < 0x20) 0.35f else 1f
    }

    private fun save() {
        // Every setting was written as it was changed, so saving is just "redraw with them". The
        // provider repaints the frame and colours; notifying the list re-runs the row factory, which is
        // what a changed row count or cover toggle needs.
        provider?.let { component ->
            sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    this.component = component
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
                }
            )
            AppWidgetManager.getInstance(this)
                ?.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetListView)
        }
        WidgetRefresh.sync(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }

    companion object {
        private const val TAG_BACKGROUND = "widget_background"
        private const val TAG_TITLE = "widget_title"
        private const val TAG_SUBTITLE = "widget_subtitle"

        private const val PROFILE_STATS_CLASS = "ani.dantotsu.widgets.statistics.ProfileStatsWidget"
        private const val SCHEDULE_CLASS = "ani.dantotsu.widgets.list.ScheduleWidget"

        /** Launched from a widget's own settings tap target, where the provider is already known. */
        fun intent(context: Context, appWidgetId: Int, provider: Class<*>): Intent =
            Intent(context, WidgetConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(EXTRA_PROVIDER, provider.name)
                // Widget PendingIntents are matched by filterEquals(), which ignores extras — without a
                // distinct data URI every widget would reuse the first instance's configure intent.
                data = android.net.Uri.parse("dantotsu://widget/$appWidgetId")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

        private const val EXTRA_PROVIDER = "provider"
    }
}
