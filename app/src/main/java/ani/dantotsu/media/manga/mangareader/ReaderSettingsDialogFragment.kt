package ani.dantotsu.media.manga.mangareader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetCurrentReaderSettingsBinding
import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.settings.CurrentReaderSettings.Directions
import ani.dantotsu.settings.CurrentReaderSettings.Layouts
import ani.dantotsu.settings.SettingsRows
import ani.dantotsu.settings.SliderConfig
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * The manga reader's settings sheet: this series' reader settings, applied as they change.
 *
 * Built from the same standard rows as the Reader settings page (see [SettingsRows]), so a setting
 * looks the same in the sheet as in settings. Rows that depend on another (spaced pages and
 * autoscroll on the layout) are rebuilt when it changes.
 */
class ReaderSettingsDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetCurrentReaderSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCurrentReaderSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MangaReaderActivity
        val settings = activity.defaultSettings
        val ctx = requireContext()
        // Dual page and overscroll don't apply across a multi-chapter strip.
        val isMultiChapter = PrefManager.getVal<Boolean>(PrefName.ContinuousMultiChapter)

        val refreshers = mutableListOf<() -> Unit>()
        fun refreshAll() = refreshers.forEach { it() }
        fun bind(list: androidx.recyclerview.widget.RecyclerView, build: () -> List<ani.dantotsu.settings.Settings>) =
            SettingsRows.bindRebuilt(list) { refresh ->
                if (refresh !in refreshers) refreshers += refresh
                build()
            }
        fun switch(name: Int, icon: Int, checked: Boolean, desc: Int? = null, enabled: Boolean = true,
                   visible: Boolean = true, onChange: (Boolean) -> Unit) =
            SettingsRows.switch(ctx, name, icon, checked, desc, enabled, visible) {
                onChange(it)
                activity.applySettings()
            }

        bind(binding.readerLayoutRows) {
            val layouts = resources.getStringArray(R.array.manga_layouts)
            val directions = resources.getStringArray(R.array.manga_directions)
            listOf(
                SettingsRows.choice(ctx, R.string.layout, R.drawable.ic_round_view_array_24,
                    layouts[settings.layout.ordinal], SettingsRows.mangaLayoutOptions(ctx),
                    settings.layout.ordinal) { i ->
                    settings.layout = Layouts[i] ?: Layouts.CONTINUOUS
                    activity.applySettings()
                    // Autoscroll only runs on the continuous layout.
                    if (settings.layout != Layouts.CONTINUOUS) {
                        PrefManager.setVal(PrefName.AutoScrollEnabled, false)
                        activity.stopAutoscroll()
                    }
                    refreshAll()
                },
                SettingsRows.choice(ctx, R.string.direction, R.drawable.ic_round_swipe_vertical_24,
                    directions[settings.direction.ordinal], SettingsRows.directionOptions(ctx),
                    settings.direction.ordinal) { i ->
                    settings.direction = Directions[i] ?: Directions.TOP_TO_BOTTOM
                    activity.applySettings()
                    refreshAll()
                },
                SettingsRows.choice(ctx, R.string.dual_page, R.drawable.ic_round_import_contacts_24,
                    "${settings.dualPageMode}\n${getString(R.string.dual_page_info)}",
                    SettingsRows.dualOptions(), settings.dualPageMode.ordinal,
                    visible = !isMultiChapter) { i ->
                    settings.dualPageMode = CurrentReaderSettings.DualPageModes[i]
                        ?: CurrentReaderSettings.DualPageModes.Automatic
                    activity.applySettings()
                    refreshAll()
                },
            )
        }

        bind(binding.readerDisplayRows) {
            listOf(
                switch(R.string.over_scroll, R.drawable.ic_round_swipe_vertical_24, settings.overScrollMode,
                    visible = !isMultiChapter) { settings.overScrollMode = it },
                switch(R.string.true_colors, R.drawable.ic_round_high_quality_24, settings.trueColors,
                    R.string.true_colors_info) { settings.trueColors = it },
                switch(R.string.image_rotation, R.drawable.ic_round_screen_rotation_alt_24,
                    settings.rotation) { settings.rotation = it },
                switch(R.string.crop_borders, R.drawable.ic_round_auto_awesome_24,
                    settings.cropBorders) { settings.cropBorders = it },
                // Pages have no gaps to space in the paged layout.
                switch(R.string.spaced_pages, R.drawable.ic_round_space_bar_24, settings.padding,
                    enabled = settings.layout != Layouts.PAGED) { settings.padding = it },
                // A global preference, not a per-series one: it is how gaps are judged, not where they go.
                switch(R.string.smart_spacing, R.drawable.ic_round_auto_awesome_24,
                    PrefManager.getVal(PrefName.SmartPadding), R.string.smart_spacing_info) {
                    PrefManager.setVal(PrefName.SmartPadding, it)
                },
                switch(R.string.hide_scroll_bar, R.drawable.ic_round_no_scroll_bar,
                    settings.hideScrollBar) { settings.hideScrollBar = it },
                switch(R.string.hide_page_numbers, R.drawable.ic_page_numbering,
                    settings.hidePageNumbers) { settings.hidePageNumbers = it },
                switch(R.string.horizontal_scroll_bar, R.drawable.ic_round_straighten_24,
                    settings.horizontalScrollBar) { settings.horizontalScrollBar = it },
            )
        }

        bind(binding.readerControlsRows) {
            val continuous = settings.layout == Layouts.CONTINUOUS
            listOf(
                switch(R.string.keep_screen_on, R.drawable.ic_baseline_screen_lock_portrait_24,
                    settings.keepScreenOn) { settings.keepScreenOn = it },
                switch(R.string.lock_screen_rotation, R.drawable.ic_round_screen_rotation_24,
                    settings.lockRotation) { settings.lockRotation = it },
                switch(R.string.volume_buttons, R.drawable.ic_round_touch_app_24,
                    settings.volumeButtons) { settings.volumeButtons = it },
                switch(R.string.wrap_images, R.drawable.ic_round_fullscreen_24, settings.wrapImages,
                    R.string.wrap_images_info) { settings.wrapImages = it },
                switch(R.string.image_long_clicking, R.drawable.ic_round_touch_app_24,
                    settings.longClickImage) { settings.longClickImage = it },
                // Autoscroll only runs on the continuous layout, so it is greyed out on the others.
                SettingsRows.switch(ctx, R.string.autoscroll, R.drawable.ic_round_play_arrow_24,
                    continuous && PrefManager.getVal<Boolean>(PrefName.AutoScrollEnabled),
                    R.string.autoscroll_only_continuous.takeIf { !continuous }, enabled = continuous) { on ->
                    PrefManager.setVal(PrefName.AutoScrollEnabled, on)
                    if (on) activity.startAutoscroll() else activity.stopAutoscroll()
                },
                SettingsRows.slider(ctx, R.string.autoscroll_speed, R.drawable.ic_round_slow_motion_video_24,
                    SliderConfig(
                        from = 0.5f,
                        to = 10f,
                        stepSize = 0.5f,
                        value = PrefManager.getVal<Float>(PrefName.AutoScrollSpeed).coerceIn(0.5f, 10f),
                        onChange = {
                            PrefManager.setVal(PrefName.AutoScrollSpeed, it)
                            activity.updateAutoscrollSpeed(it)
                        },
                        format = { "%.1f".format(it) },
                    )),
            )
        }
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }

    companion object {
        fun newInstance() = ReaderSettingsDialogFragment()
    }
}
