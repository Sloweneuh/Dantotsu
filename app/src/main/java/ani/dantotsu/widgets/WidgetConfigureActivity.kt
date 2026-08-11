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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityWidgetConfigureBinding
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.widgets.list.ActivityWidget
import ani.dantotsu.widgets.list.RecommendationsWidget
import ani.dantotsu.widgets.list.WaitingWidget
import ani.dantotsu.widgets.statistics.ProfileStat
import ani.dantotsu.widgets.statistics.ProfileStatsCache
import ani.dantotsu.widgets.statistics.ProfileStatsWidget
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

        // The waiting widget mixes anime and manga; so does recommendations, which reuses the exact
        // same home-screen row for both. The other airing widgets are anime by definition.
        val mixesTypes = provider?.className == WaitingWidget::class.java.name ||
            provider?.className == RecommendationsWidget::class.java.name
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

        // Only the activity widget's rows can even be the signed-in account's own.
        val isActivity = provider?.className == ActivityWidget::class.java.name
        binding.hideOwnActivity.isVisible = isActivity
        if (isActivity) {
            binding.hideOwnActivity.isChecked = prefs.hideOwnActivity
            binding.hideOwnActivity.setOnCheckedChangeListener { _, checked ->
                prefs.hideOwnActivity = checked
                renderPreview()
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
    /**
     * The stat picker, which is the preview itself: every cell is a tap target that opens the chooser
     * for that position.
     *
     * This replaced eight dropdowns whose captions had to name grid positions in words ("Row 3, left")
     * precisely because the thing being configured was off in a separate section. Tapping the cell that
     * will change removes that indirection, and the preview already had to render the grid anyway.
     */
    private fun setUpStatsOptions() {
        val isStats = provider?.className == PROFILE_STATS_CLASS
        binding.statsOptions.isVisible = isStats
        if (!isStats) return

        val setters = listOf<(ProfileStat) -> Unit>(
            { prefs.statSlot1 = it }, { prefs.statSlot2 = it },
            { prefs.statSlot3 = it }, { prefs.statSlot4 = it },
            { prefs.statSlot5 = it }, { prefs.statSlot6 = it },
            { prefs.statSlot7 = it }, { prefs.statSlot8 = it }
        )
        statCells().forEachIndexed { index, cell ->
            cell.setOnClickListener { pickStat(index, setters[index]) }
        }
    }

    /** The eight tappable cells of the preview grid, in the same order as [WidgetPrefs.statSlots]. */
    private fun statCells() = with(binding.preview) {
        listOf(
            previewStatCell1, previewStatCell2, previewStatCell3, previewStatCell4,
            previewStatCell5, previewStatCell6, previewStatCell7, previewStatCell8
        )
    }

    private fun pickStat(index: Int, onPicked: (ProfileStat) -> Unit) {
        val options = ProfileStat.entries
        val labels = options.map { getString(it.labelRes) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.widget_stats_pick)
            .setSingleChoiceItems(labels, options.indexOf(prefs.statSlots[index])) { dialog, which ->
                onPicked(options[which])
                dialog.dismiss()
                renderPreview()
            }
            .show()
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
        val isActivity = provider?.className == ActivityWidget::class.java.name
        val isRecommendations = provider?.className == RecommendationsWidget::class.java.name
        style.applyTo(binding.preview.widgetBackground)
        with(binding.preview) {
            widgetTitle.setTextColor(style.title)
            widgetTitle.text = getString(previewTitleRes())

            previewRows.isVisible = !isStats && !isSchedule
            previewCalendarRows.isVisible = isSchedule
            previewStats.isVisible = isStats

            // Row one is the signed-in account's own sample post — the one row hiding it would
            // actually remove, so the toggle's effect shows up in the preview rather than only in
            // what a real refresh would eventually draw.
            previewRowOne.isVisible = !(isActivity && prefs.hideOwnActivity)

            previewTitleOne.setTextColor(style.title)
            previewTitleTwo.setTextColor(style.title)
            previewSubtitleOne.setTextColor(style.subtitle)
            previewSubtitleTwo.setTextColor(style.subtitle)
            previewCoverOne.isVisible = prefs.showCovers
            previewCoverTwo.isVisible = prefs.showCovers

            // Row one's sample swaps with the widget: "counts behind" reads correctly only for the
            // waiting widget, an airing countdown only for upcoming, a built sentence and a relative
            // time only for activity, a bare category only for recommendations. Row two stays Vinland
            // Saga for every case but activity, whose two rows are both sentences of their own.
            previewCoverOne.setImageResource(
                when {
                    isWaiting || isRecommendations -> R.drawable.preview_cover_onepiece
                    else -> R.drawable.preview_cover_frieren
                }
            )
            previewCoverTwo.setImageResource(R.drawable.preview_cover_vinland)
            // An activity row's cover is the media's; the acting user's avatar sits beside the title
            // rather than taking the cover slot over — see item_widget_media.xml.
            previewAvatarOne.isVisible = isActivity && prefs.showCovers
            previewAvatarTwo.isVisible = isActivity && prefs.showCovers
            previewTitleOne.text = getString(
                when {
                    isWaiting || isRecommendations -> R.string.widget_preview_title_three
                    isActivity -> R.string.widget_preview_activity_one
                    else -> R.string.widget_preview_title
                }
            )
            previewTitleTwo.text = getString(
                if (isActivity) R.string.widget_preview_activity_two else R.string.widget_preview_title_two
            )
            // A recommendation's type is the icon+label below, not text here — see mediaRow().
            previewSubtitleOne.text = if (isRecommendations) "" else getString(
                when {
                    isWaiting -> R.string.widget_preview_subtitle_waiting
                    isActivity -> R.string.widget_preview_activity_time_one
                    else -> R.string.widget_preview_subtitle
                }
            )
            previewSubtitleTwo.text = if (isRecommendations) "" else getString(
                when {
                    isWaiting -> R.string.widget_preview_subtitle_waiting_two
                    isActivity -> R.string.widget_preview_activity_time_two
                    else -> R.string.widget_preview_subtitle_two
                }
            )

            // The waiting widget's rows end in the dub/sub icon + code instead of text, a
            // recommendation's in a type icon + label — both share this slot; see MediaListFactory.
            previewLanguageIcon.isVisible = isWaiting || isRecommendations
            previewLanguageCode.isVisible = isWaiting || isRecommendations
            previewLanguageCode.setTextColor(style.subtitle)
            previewLanguageIcon.setColorFilter(style.subtitle)
            if (isRecommendations) {
                previewLanguageIcon.setImageResource(R.drawable.ic_round_import_contacts_24)
                previewLanguageCode.text = getString(R.string.anime)
            }

            for (day in listOf(previewCalendarDayOne, previewCalendarDayTwo)) {
                day.setTextColor(style.accent)
            }
            for (time in listOf(previewCalendarTimeOne, previewCalendarTimeTwo, previewCalendarTimeThree)) {
                time.setTextColor(style.accent)
            }
            for (title in listOf(previewCalendarTitleOne, previewCalendarTitleTwo, previewCalendarTitleThree)) {
                title.setTextColor(style.title)
            }

            // Real numbers, not placeholders: the cached stats are what the widget itself draws, so
            // the preview shows the user their own figures and a picked stat is recognisable at a
            // glance. Falls back to the stat's name alone when nothing is cached yet (signed out, or
            // the first refresh hasn't landed), which is still enough to configure by.
            val stats = ProfileStatsCache.cached(this@WidgetConfigureActivity)
            val visibleRows = ProfileStatsWidget.rowsFor(this@WidgetConfigureActivity, appWidgetId)
            val slots = prefs.statSlots
            val rows = listOf(previewStatRow1, previewStatRow2, previewStatRow3, previewStatRow4)
            val values = listOf(
                previewStatValue1, previewStatValue2, previewStatValue3, previewStatValue4,
                previewStatValue5, previewStatValue6, previewStatValue7, previewStatValue8
            )
            val labels = listOf(
                previewStatLabel1, previewStatLabel2, previewStatLabel3, previewStatLabel4,
                previewStatLabel5, previewStatLabel6, previewStatLabel7, previewStatLabel8
            )
            rows.forEachIndexed { index, row -> row.isVisible = isStats && index < visibleRows }
            slots.forEachIndexed { index, slot ->
                values[index].setTextColor(style.title)
                labels[index].setTextColor(style.subtitle)
                val hidden = slot == ProfileStat.NONE
                values[index].isInvisible = hidden
                if (!hidden) {
                    values[index].text = stats?.let { slot.value(it) } ?: PLACEHOLDER_STAT_VALUE
                    labels[index].text = getString(slot.labelRes)
                } else {
                    // The label still names the empty slot, so it stays tappable and identifiable —
                    // an entirely blank cell would give the user nothing to aim at.
                    labels[index].text = getString(slot.labelRes)
                }
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
        ActivityWidget::class.java.name -> R.string.widget_activity
        RecommendationsWidget::class.java.name -> R.string.widget_recommendations
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

        /** Shown in place of a real figure when no stats have been cached yet. */
        private const val PLACEHOLDER_STAT_VALUE = "—"
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
