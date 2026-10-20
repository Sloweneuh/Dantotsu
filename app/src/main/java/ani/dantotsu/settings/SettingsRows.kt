package ani.dantotsu.settings

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R

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

    /** A slider row; [format] renders the value beside the title. */
    fun slider(context: Context, name: Int, icon: Int, config: SliderConfig, enabled: Boolean = true) =
        Settings(
            type = 4,
            name = context.getString(name),
            desc = "",
            icon = icon,
            isEnabled = enabled,
            slider = config,
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
