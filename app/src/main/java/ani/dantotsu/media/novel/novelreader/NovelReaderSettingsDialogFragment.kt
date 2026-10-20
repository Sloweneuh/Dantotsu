package ani.dantotsu.media.novel.novelreader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetCurrentNovelReaderSettingsBinding
import ani.dantotsu.settings.CurrentNovelReaderSettings
import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.settings.Settings
import ani.dantotsu.settings.SettingsRows
import ani.dantotsu.settings.StepperConfig
import ani.dantotsu.util.customAlertDialog

/**
 * The novel reader's settings sheet: this book's reader settings, applied as they change.
 *
 * Built from the same standard rows as the Reader settings page (see [SettingsRows]), so a setting
 * looks the same in the sheet as in settings.
 */
class NovelReaderSettingsDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetCurrentNovelReaderSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCurrentNovelReaderSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as NovelReaderActivity
        val settings = activity.defaultSettings
        val ctx = requireContext()

        fun switch(name: Int, icon: Int, checked: Boolean, onChange: (Boolean) -> Unit) =
            SettingsRows.switch(ctx, name, icon, checked) {
                onChange(it)
                activity.applySettings()
            }
        fun stepper(name: Int, icon: Int, desc: Int?, value: Float, step: Float, decimals: Int,
                    onChange: (Float) -> Unit) =
            SettingsRows.stepper(ctx, name, icon, desc, StepperConfig(value, step, decimals = decimals) {
                onChange(it)
                activity.applySettings()
            })

        SettingsRows.bindRebuilt(binding.novelLayoutRows) { refresh ->
            listOf(
                // This book's themes: the reader's own plus any the open book adds.
                Settings(
                    type = 1,
                    name = getString(R.string.theme),
                    desc = settings.currentThemeName,
                    icon = R.drawable.ic_palette,
                    onClick = {
                        val names = activity.themes.map { it.name }.toTypedArray()
                        // A name from another book that this one does not offer matches nothing;
                        // the first theme is then the one shown as picked.
                        val current = names.indexOfFirst { it.equals(settings.currentThemeName, true) }
                        ctx.customAlertDialog().apply {
                            setTitle(R.string.theme)
                            singleChoiceItems(names, current.coerceAtLeast(0)) { i ->
                                settings.currentThemeName = names[i]
                                activity.applySettings()
                                refresh()
                            }
                            show()
                        }
                    },
                ),
                SettingsRows.choice(ctx, R.string.layout, R.drawable.ic_round_view_array_24,
                    settings.layout.string, SettingsRows.novelLayoutOptions(), settings.layout.ordinal) { i ->
                    settings.layout = CurrentNovelReaderSettings.Layouts[i]
                        ?: CurrentNovelReaderSettings.Layouts.PAGED
                    activity.applySettings()
                    refresh()
                },
                SettingsRows.choice(ctx, R.string.dual_page, R.drawable.ic_round_import_contacts_24,
                    settings.dualPageMode.toString(), SettingsRows.dualOptions(),
                    settings.dualPageMode.ordinal) { i ->
                    settings.dualPageMode = CurrentReaderSettings.DualPageModes[i]
                        ?: CurrentReaderSettings.DualPageModes.Automatic
                    activity.applySettings()
                    refresh()
                },
            )
        }

        SettingsRows.bindRebuilt(binding.novelTextRows) { refresh ->
            listOf(
                SettingsRows.novelFontRow(ctx, settings.effectiveFont) { font ->
                    settings.font = font
                    activity.applySettings()
                    refresh()
                },
                SettingsRows.stepper(ctx, R.string.novel_text_size, R.drawable.ic_round_format_text_24,
                    R.string.novel_text_size_desc,
                    StepperConfig(settings.effectiveTextSize.toFloat(), step = 10f, min = 50f, max = 300f) {
                        settings.textSize = it.toInt()
                        activity.applySettings()
                    }),
                stepper(R.string.novel_line_height, R.drawable.ic_format_line_spacing_24, null,
                    settings.lineHeight, 0.1f, 1) { settings.lineHeight = it },
                stepper(R.string.novel_margin, R.drawable.ic_margin_24, null,
                    settings.margin, 0.01f, 2) { settings.margin = it },
                stepper(R.string.novel_max_inline_size, R.drawable.ic_width_24, R.string.novel_max_inline_size_desc,
                    settings.maxInlineSize.toFloat(), 10f, 0) { settings.maxInlineSize = it.toInt() },
                stepper(R.string.novel_max_block_size, R.drawable.ic_height_24, R.string.novel_max_block_size_desc,
                    settings.maxBlockSize.toFloat(), 10f, 0) { settings.maxBlockSize = it.toInt() },
                switch(R.string.justify_text, R.drawable.format_align_center_24, settings.justify) {
                    settings.justify = it
                },
                switch(R.string.hyphenation, R.drawable.ic_round_format_text_24, settings.hyphenation) {
                    settings.hyphenation = it
                },
            )
        }

        SettingsRows.bindRebuilt(binding.novelDisplayRows) {
            listOf(
                switch(R.string.use_dark_theme, R.drawable.ic_round_brightness_4_24, settings.useDarkTheme) {
                    settings.useDarkTheme = it
                },
                switch(R.string.use_oled_theme, R.drawable.ic_round_brightness_4_24, settings.useOledTheme) {
                    settings.useOledTheme = it
                },
                switch(R.string.hide_page_numbers, R.drawable.ic_page_numbering,
                    settings.hidePageNumbers) { settings.hidePageNumbers = it },
                switch(R.string.keep_screen_on, R.drawable.ic_baseline_screen_lock_portrait_24,
                    settings.keepScreenOn) { settings.keepScreenOn = it },
                switch(R.string.lock_screen_rotation, R.drawable.ic_round_screen_rotation_24,
                    settings.lockRotation) { settings.lockRotation = it },
            )
        }

        SettingsRows.bindRebuilt(binding.novelControlsRows) {
            listOf(
                switch(R.string.volume_buttons, R.drawable.ic_round_touch_app_24,
                    settings.volumeButtons) { settings.volumeButtons = it },
            )
        }
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }

    companion object {
        fun newInstance() = NovelReaderSettingsDialogFragment()
        const val TAG = "NovelReaderSettingsDialogFragment"
    }
}
