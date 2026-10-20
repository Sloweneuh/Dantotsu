package ani.dantotsu.settings

import android.content.Context
import android.content.Intent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.media.manga.mangareader.TapZoneEditorActivity
import ani.dantotsu.media.novel.novelreader.NovelFonts
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog
import kotlin.math.roundToInt

/**
 * Builders for the standard settings rows the reader screens share: the Reader settings page and
 * the manga and novel sheets opened while reading show the same settings, so they are built the
 * same way and look the same — icon, title, description and control in one row.
 */
object SettingsRows {

    fun switch(
        context: Context,
        name: Int,
        icon: Int,
        checked: Boolean,
        desc: Int? = null,
        enabled: Boolean = true,
        visible: Boolean = true,
        onChange: (Boolean) -> Unit,
    ) = Settings(
        type = 2,
        name = context.getString(name),
        desc = desc?.let { context.getString(it) } ?: "",
        icon = icon,
        isChecked = checked,
        isEnabled = enabled,
        isVisible = visible,
        switch = { isChecked, _ -> onChange(isChecked) },
    )

    fun choice(
        context: Context,
        name: Int,
        icon: Int,
        desc: String,
        options: List<ChoiceOption>,
        selected: Int,
        visible: Boolean = true,
        onSelect: (Int) -> Unit,
    ) = Settings(
        type = 5,
        name = context.getString(name),
        desc = desc,
        icon = icon,
        isVisible = visible,
        choice = ChoiceConfig(options, selected, onSelect),
    )

    fun stepper(context: Context, name: Int, icon: Int, desc: Int?, config: StepperConfig) = Settings(
        type = 6,
        name = context.getString(name),
        desc = desc?.let { context.getString(it) } ?: "",
        icon = icon,
        stepper = config,
    )

    /** A slider row; [SliderConfig.format] renders the value beside the title. */
    fun slider(
        context: Context,
        name: Int,
        icon: Int,
        config: SliderConfig,
        enabled: Boolean = true,
        desc: Int? = null,
    ) = Settings(
        type = 4,
        name = context.getString(name),
        desc = desc?.let { context.getString(it) } ?: "",
        icon = icon,
        isEnabled = enabled,
        slider = config,
    )

    /**
     * The manga page filters — dimmer, warm tint, greyscale, invert — as rows. They are global
     * preferences; [onChange] runs after each is saved (the reader's sheet re-applies them live).
     */
    fun pageFilterRows(context: Context, onChange: () -> Unit): List<Settings> {
        fun percent(v: Float) = "${(v * 100).roundToInt()}%"
        return listOf(
            slider(context, R.string.reader_dim, R.drawable.ic_round_brightness_medium_24, SliderConfig(
                from = 0f, to = 0.8f, stepSize = 0.05f,
                value = PrefManager.getVal<Float>(PrefName.ReaderDim).coerceIn(0f, 0.8f),
                onChange = { PrefManager.setVal(PrefName.ReaderDim, it); onChange() },
                format = ::percent,
            ), desc = R.string.reader_dim_desc),
            slider(context, R.string.reader_warmth, R.drawable.ic_round_brightness_high_24, SliderConfig(
                from = 0f, to = 1f, stepSize = 0.05f,
                value = PrefManager.getVal<Float>(PrefName.ReaderWarmth).coerceIn(0f, 1f),
                onChange = { PrefManager.setVal(PrefName.ReaderWarmth, it); onChange() },
                format = ::percent,
            ), desc = R.string.reader_warmth_desc),
            switch(context, R.string.reader_greyscale, R.drawable.ic_palette,
                PrefManager.getVal(PrefName.ReaderGreyscale)) {
                PrefManager.setVal(PrefName.ReaderGreyscale, it); onChange()
            },
            switch(context, R.string.reader_invert, R.drawable.ic_round_brightness_4_24,
                PrefManager.getVal(PrefName.ReaderInvert), R.string.reader_invert_desc) {
                PrefManager.setVal(PrefName.ReaderInvert, it); onChange()
            },
        )
    }

    /**
     * Where a tap turns the page in the paged layout, and whether the sides are swapped. Global
     * preferences, read at each tap; [refresh] rebuilds the list so the chosen layout shows.
     */
    fun tapZoneRows(context: Context, refresh: () -> Unit): List<Settings> {
        val names = listOf(
            R.string.reader_tap_zones_edges, R.string.reader_tap_zones_thirds,
            R.string.reader_tap_zones_l, R.string.reader_tap_zones_off,
            R.string.reader_tap_zones_custom,
        ).map { context.getString(it) }.toTypedArray()
        val current = PrefManager.getVal<Int>(PrefName.ReaderTapZones).coerceIn(0, names.size - 1)
        return listOf(
            Settings(
                type = 1,
                name = context.getString(R.string.reader_tap_zones),
                desc = context.getString(R.string.reader_tap_zones_paged_only, names[current]),
                icon = R.drawable.ic_round_grid_view_24,
                onClick = {
                    context.customAlertDialog().apply {
                        setTitle(R.string.reader_tap_zones)
                        singleChoiceItems(names, current) { i ->
                            PrefManager.setVal(PrefName.ReaderTapZones, i)
                            refresh()
                        }
                        show()
                    }
                },
            ),
            // Only while the custom layout is the one in use: editing zones nothing reads would
            // look like it should change something.
            Settings(
                type = 1,
                name = context.getString(R.string.reader_tap_zones_edit),
                desc = context.getString(R.string.reader_tap_zones_edit_desc),
                icon = R.drawable.ic_round_grid_view_24,
                isVisible = current == 4,
                onClick = {
                    context.startActivity(Intent(context, TapZoneEditorActivity::class.java))
                },
            ),
            switch(context, R.string.reader_tap_zones_invert, R.drawable.ic_round_touch_app_24,
                PrefManager.getVal(PrefName.ReaderTapZonesInverted), enabled = current != 3) {
                PrefManager.setVal(PrefName.ReaderTapZonesInverted, it)
            },
        )
    }

    /** The novel reader's typeface ([NovelFonts] index [current]), picked from a list. */
    fun novelFontRow(context: Context, current: Int, onSelect: (Int) -> Unit) = Settings(
        type = 1,
        name = context.getString(R.string.novel_font),
        desc = context.getString(NovelFonts.name(current)),
        icon = R.drawable.ic_round_font_size_24,
        onClick = {
            val names = NovelFonts.names.map { context.getString(it) }.toTypedArray()
            context.customAlertDialog().apply {
                setTitle(R.string.novel_font)
                singleChoiceItems(names, (current - 1).coerceIn(0, names.size - 1)) { i -> onSelect(i + 1) }
                show()
            }
        },
    )

    /**
     * Fills [list] with rows whose values change from inside it (a choice's current option under
     * its title, a row greyed out by another): [build] is handed the refresh that rebuilds the list,
     * to call after a change.
     */
    fun bindRebuilt(list: RecyclerView, build: (refresh: () -> Unit) -> List<Settings>) {
        val rows = ArrayList<Settings>()
        val adapter = SettingsAdapter(rows, popIn = false)
        lateinit var refresh: () -> Unit
        refresh = {
            rows.clear()
            rows.addAll(build(refresh))
            @Suppress("NotifyDataSetChanged") // A value and its description change together.
            adapter.notifyDataSetChanged()
        }
        if (list.layoutManager == null) list.layoutManager = LinearLayoutManager(list.context)
        list.adapter = adapter
        refresh()
    }

    /** Manga layouts, in Layouts order. */
    fun mangaLayoutOptions(context: Context): List<ChoiceOption> {
        val names = context.resources.getStringArray(R.array.manga_layouts)
        return listOf(
            ChoiceOption(R.drawable.ic_round_amp_stories_24, names[0], flipHorizontally = true),
            ChoiceOption(R.drawable.ic_round_view_array_24, names[1]),
            ChoiceOption(R.drawable.ic_round_view_column_24, names[2]),
        )
    }

    /** Reading directions, in Directions order: one arrow turned four ways. */
    fun directionOptions(context: Context): List<ChoiceOption> {
        val names = context.resources.getStringArray(R.array.manga_directions)
        return listOf(180f, 270f, 0f, 90f).mapIndexed { i, degrees ->
            ChoiceOption(R.drawable.ic_round_swipe_up_alt_24, names[i], rotation = degrees)
        }
    }

    /** Dual page's three modes, in DualPageModes order. */
    fun dualOptions() = CurrentReaderSettings.DualPageModes.entries.map { mode ->
        ChoiceOption(
            when (mode) {
                CurrentReaderSettings.DualPageModes.No -> R.drawable.ic_round_close_24
                CurrentReaderSettings.DualPageModes.Automatic -> R.drawable.ic_round_screen_rotation_24
                CurrentReaderSettings.DualPageModes.Force -> R.drawable.ic_round_menu_book_24
            },
            mode.toString(),
            flipHorizontally = mode == CurrentReaderSettings.DualPageModes.No,
        )
    }

    /** Novel layouts, in Layouts order. */
    fun novelLayoutOptions() = listOf(
        ChoiceOption(R.drawable.ic_round_amp_stories_24,
            CurrentNovelReaderSettings.Layouts.PAGED.string, flipHorizontally = true),
        ChoiceOption(R.drawable.ic_round_view_column_24,
            CurrentNovelReaderSettings.Layouts.SCROLLED.string),
    )
}
