package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import android.text.util.Linkify
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.restartApp
import ani.dantotsu.databinding.ActivityReaderSettingsBinding
import ani.dantotsu.databinding.DialogUserAgentBinding
import ani.dantotsu.media.novel.novelreader.NovelReaderActivity
import ani.dantotsu.media.novel.novelreader.NovelTtsSettingsBottomSheet
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.Xpandable
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.media.manga.translation.LlmTranslator
import ani.dantotsu.media.manga.translation.MtlChoices
import ani.dantotsu.media.manga.translation.MtlSettings
import ani.dantotsu.spike.MangaOcrSpikeActivity
import ani.dantotsu.media.manga.translation.TranslationEngine
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.launch

class ReaderSettingsActivity : AppCompatActivity() {
    lateinit var binding: ActivityReaderSettingsBinding
    private var defaultSettings = CurrentReaderSettings()
    private var defaultSettingsLN = CurrentNovelReaderSettings()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityReaderSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SettingsRouter.handleHighlight(
            this, binding.settingsRecyclerView, binding.readerMangaTopRows, binding.readerMangaRows,
            binding.readerMtlRows, binding.readerNovelRows, binding.readerGeneralRows,
            binding.readerProgressRows,
        )
        bindLibraryRows()
        Xpandable.consumeRelaunch(Xpandable.SCOPE_READER)

        initActivity(this)
        binding.readerSettingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }

        binding.readerSettingsBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // The on/off settings of every group, as standard rows; see bindRows.
        bindRows()
        // OCR & MTL; delete with ani.dantotsu.spike
        refreshMtlRows()
    }

    /**
     * Every group's on/off settings as standard settings rows — icon, title, description and switch
     * in one row, as on the Player page — in the lists each group holds between its own controls.
     * Each row reads and writes the same preference, and the same [defaultSettings] /
     * [defaultSettingsLN] field, its hand-built switch did.
     */
    private fun bindRows() {
        fun row(
            name: Int,
            icon: Int,
            checked: Boolean,
            desc: Int? = null,
            enabled: Boolean = true,
            onChange: (Boolean) -> Unit,
        ) = Settings(
            type = 2,
            name = getString(name),
            desc = desc?.let { getString(it) } ?: "",
            icon = icon,
            isChecked = checked,
            isEnabled = enabled,
            switch = { isChecked, _ -> onChange(isChecked) },
        )

        fun stepper(name: Int, icon: Int, desc: Int?, config: StepperConfig) = Settings(
            type = 6,
            name = getString(name),
            desc = desc?.let { getString(it) } ?: "",
            icon = icon,
            stepper = config,
        )

        fun choice(
            name: Int,
            icon: Int,
            desc: String,
            options: List<ChoiceOption>,
            selected: Int,
            onSelect: (Int) -> Unit,
        ) = Settings(
            type = 5,
            name = getString(name),
            desc = desc,
            icon = icon,
            choice = ChoiceConfig(options, selected, onSelect),
        )

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

        /**
         * A list whose rows show values that change from inside it (a choice's current option under
         * its title): [build] is handed the refresh that rebuilds it, to call after a change.
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
            list.adapter = adapter
            refresh()
        }

        fun bind(list: RecyclerView, rows: List<Settings>) {
            list.adapter = SettingsAdapter(ArrayList(rows), popIn = false)
        }

        bindRebuilt(binding.readerMangaTopRows) { refresh ->
            val layouts = resources.getStringArray(R.array.manga_layouts)
            val directions = resources.getStringArray(R.array.manga_directions)
            listOf(
                row(R.string.auto_detect_webtoon, R.drawable.ic_round_auto_awesome_24,
                    PrefManager.getVal(PrefName.AutoDetectWebtoon), R.string.auto_detect_webtoon_info) {
                    PrefManager.setVal(PrefName.AutoDetectWebtoon, it)
                },
                choice(R.string.layout, R.drawable.ic_round_view_array_24,
                    layouts[defaultSettings.layout.ordinal],
                    listOf(
                        ChoiceOption(R.drawable.ic_round_amp_stories_24, layouts[0], flipHorizontally = true),
                        ChoiceOption(R.drawable.ic_round_view_array_24, layouts[1]),
                        ChoiceOption(R.drawable.ic_round_view_column_24, layouts[2]),
                    ),
                    defaultSettings.layout.ordinal,
                ) { i ->
                    defaultSettings.layout =
                        CurrentReaderSettings.Layouts[i] ?: CurrentReaderSettings.Layouts.CONTINUOUS
                    PrefManager.setVal(PrefName.LayoutReader, defaultSettings.layout.ordinal)
                    refresh()
                },
                // One arrow turned four ways, in Directions order — picked directly, not cycled to.
                choice(R.string.direction, R.drawable.ic_round_swipe_vertical_24,
                    directions[defaultSettings.direction.ordinal],
                    listOf(180f, 270f, 0f, 90f).mapIndexed { i, degrees ->
                        ChoiceOption(R.drawable.ic_round_swipe_up_alt_24, directions[i], rotation = degrees)
                    },
                    defaultSettings.direction.ordinal,
                ) { i ->
                    defaultSettings.direction = CurrentReaderSettings.Directions[i]
                        ?: CurrentReaderSettings.Directions.TOP_TO_BOTTOM
                    PrefManager.setVal(PrefName.Direction, defaultSettings.direction.ordinal)
                    refresh()
                },
                choice(R.string.dual_page, R.drawable.ic_round_import_contacts_24,
                    "${defaultSettings.dualPageMode}\n${getString(R.string.dual_page_info)}",
                    dualOptions(),
                    defaultSettings.dualPageMode.ordinal,
                ) { i ->
                    defaultSettings.dualPageMode = CurrentReaderSettings.DualPageModes[i]
                        ?: CurrentReaderSettings.DualPageModes.Automatic
                    PrefManager.setVal(PrefName.DualPageModeReader, defaultSettings.dualPageMode.ordinal)
                    refresh()
                },
            )
        }

        // Clamped to the slider's range: a stored speed from an older range can't sit off its ends.
        val autoscrollSpeed = PrefManager.getVal<Float>(PrefName.AutoScrollSpeed).coerceIn(0.5f, 10f)
        bind(binding.readerMangaRows, listOf(
            row(R.string.over_scroll, R.drawable.ic_round_swipe_vertical_24, defaultSettings.overScrollMode) {
                defaultSettings.overScrollMode = it
                PrefManager.setVal(PrefName.OverScrollMode, it)
            },
            row(R.string.true_colors, R.drawable.ic_round_high_quality_24, defaultSettings.trueColors,
                R.string.true_colors_info) {
                defaultSettings.trueColors = it
                PrefManager.setVal(PrefName.TrueColors, it)
            },
            row(R.string.image_rotation, R.drawable.ic_round_screen_rotation_alt_24, defaultSettings.rotation) {
                defaultSettings.rotation = it
                PrefManager.setVal(PrefName.Rotation, it)
            },
            row(R.string.crop_borders, R.drawable.ic_round_auto_awesome_24, defaultSettings.cropBorders) {
                defaultSettings.cropBorders = it
                PrefManager.setVal(PrefName.CropBorders, it)
            },
            row(R.string.spaced_pages, R.drawable.ic_round_space_bar_24, defaultSettings.padding) {
                defaultSettings.padding = it
                PrefManager.setVal(PrefName.Padding, it)
            },
            row(R.string.smart_spacing, R.drawable.ic_round_auto_awesome_24,
                PrefManager.getVal(PrefName.SmartPadding), R.string.smart_spacing_info) {
                PrefManager.setVal(PrefName.SmartPadding, it)
            },
            row(R.string.hide_scroll_bar, R.drawable.ic_round_no_scroll_bar, defaultSettings.hideScrollBar) {
                defaultSettings.hideScrollBar = it
                PrefManager.setVal(PrefName.HideScrollBar, it)
            },
            row(R.string.hide_page_numbers, R.drawable.ic_page_numbering, defaultSettings.hidePageNumbers) {
                defaultSettings.hidePageNumbers = it
                PrefManager.setVal(PrefName.HidePageNumbers, it)
            },
            row(R.string.horizontal_scroll_bar, R.drawable.ic_round_straighten_24,
                defaultSettings.horizontalScrollBar) {
                defaultSettings.horizontalScrollBar = it
                PrefManager.setVal(PrefName.HorizontalScrollBar, it)
            },
            row(R.string.keep_screen_on, R.drawable.ic_baseline_screen_lock_portrait_24,
                defaultSettings.keepScreenOn) {
                defaultSettings.keepScreenOn = it
                PrefManager.setVal(PrefName.KeepScreenOn, it)
            },
            row(R.string.lock_screen_rotation, R.drawable.ic_round_screen_rotation_24,
                defaultSettings.lockRotation) {
                defaultSettings.lockRotation = it
                PrefManager.setVal(PrefName.LockRotation, it)
            },
            row(R.string.autoscroll, R.drawable.ic_round_play_arrow_24,
                PrefManager.getVal(PrefName.AutoScrollEnabled)) {
                PrefManager.setVal(PrefName.AutoScrollEnabled, it)
            },
            Settings(
                type = 4,
                name = getString(R.string.autoscroll_speed),
                desc = "",
                icon = R.drawable.ic_round_slow_motion_video_24,
                slider = SliderConfig(
                    from = 0.5f,
                    to = 10f,
                    stepSize = 0.5f,
                    value = autoscrollSpeed,
                    onChange = { PrefManager.setVal(PrefName.AutoScrollSpeed, it) },
                    format = { "%.1f".format(it) },
                ),
            ),
            row(R.string.volume_buttons, R.drawable.ic_round_touch_app_24, defaultSettings.volumeButtons) {
                defaultSettings.volumeButtons = it
                PrefManager.setVal(PrefName.VolumeButtonsReader, it)
            },
            row(R.string.wrap_images, R.drawable.ic_round_fullscreen_24, defaultSettings.wrapImages,
                R.string.wrap_images_info) {
                defaultSettings.wrapImages = it
                PrefManager.setVal(PrefName.WrapImages, it)
            },
            row(R.string.image_long_clicking, R.drawable.ic_round_touch_app_24,
                defaultSettings.longClickImage) {
                defaultSettings.longClickImage = it
                PrefManager.setVal(PrefName.LongClickImage, it)
            },
            stepper(R.string.preload_amount, R.drawable.ic_round_art_track_24, R.string.preload_amount_info,
                StepperConfig(defaultSettings.preloadAmount.toFloat(), step = 1f, min = 4f, max = 20f) {
                    defaultSettings.preloadAmount = it.toInt()
                    PrefManager.setVal(PrefName.PreloadAmount, it.toInt())
                }),
        ))

        binding.readerMtlRows.adapter = SettingsAdapter(mtlRows, popIn = false)

        bindRebuilt(binding.readerNovelRows) { refresh -> listOf(
            // The same themes the reader's own sheet offers, minus whatever theme the open book adds
            // — there is no book here.
            Settings(
                type = 1,
                name = getString(R.string.theme),
                desc = defaultSettingsLN.currentThemeName,
                icon = R.drawable.ic_palette,
                onClick = {
                    val names = NovelReaderActivity.THEME_NAMES.toTypedArray()
                    val current = names.indexOfFirst { it.equals(defaultSettingsLN.currentThemeName, true) }
                    customAlertDialog().apply {
                        setTitle(R.string.theme)
                        singleChoiceItems(names, current.coerceAtLeast(0)) { i ->
                            defaultSettingsLN.currentThemeName = names[i]
                            PrefManager.setVal(PrefName.CurrentThemeName, names[i])
                            refresh()
                        }
                        show()
                    }
                },
            ),
            choice(R.string.layout, R.drawable.ic_round_view_array_24, defaultSettingsLN.layout.string,
                listOf(
                    ChoiceOption(R.drawable.ic_round_amp_stories_24,
                        CurrentNovelReaderSettings.Layouts.PAGED.string, flipHorizontally = true),
                    ChoiceOption(R.drawable.ic_round_view_column_24,
                        CurrentNovelReaderSettings.Layouts.SCROLLED.string),
                ),
                defaultSettingsLN.layout.ordinal,
            ) { i ->
                defaultSettingsLN.layout =
                    CurrentNovelReaderSettings.Layouts[i] ?: CurrentNovelReaderSettings.Layouts.PAGED
                PrefManager.setVal(PrefName.LayoutNovel, defaultSettingsLN.layout.ordinal)
                refresh()
            },
            choice(R.string.dual_page, R.drawable.ic_round_import_contacts_24,
                defaultSettingsLN.dualPageMode.toString(),
                dualOptions(),
                defaultSettingsLN.dualPageMode.ordinal,
            ) { i ->
                defaultSettingsLN.dualPageMode = CurrentReaderSettings.DualPageModes[i]
                    ?: CurrentReaderSettings.DualPageModes.Automatic
                PrefManager.setVal(PrefName.DualPageModeNovel, defaultSettingsLN.dualPageMode.ordinal)
                refresh()
            },
            stepper(R.string.novel_line_height, R.drawable.ic_format_line_spacing_24, null,
                StepperConfig(defaultSettingsLN.lineHeight, step = 0.1f, decimals = 1) {
                    defaultSettingsLN.lineHeight = it
                    PrefManager.setVal(PrefName.LineHeight, it)
                }),
            stepper(R.string.novel_margin, R.drawable.ic_margin_24, null,
                StepperConfig(defaultSettingsLN.margin, step = 0.01f, decimals = 2) {
                    defaultSettingsLN.margin = it
                    PrefManager.setVal(PrefName.Margin, it)
                }),
            stepper(R.string.novel_max_inline_size, R.drawable.ic_width_24, R.string.novel_max_inline_size_desc,
                StepperConfig(defaultSettingsLN.maxInlineSize.toFloat(), step = 10f) {
                    defaultSettingsLN.maxInlineSize = it.toInt()
                    PrefManager.setVal(PrefName.MaxInlineSize, it.toInt())
                }),
            stepper(R.string.novel_max_block_size, R.drawable.ic_height_24, R.string.novel_max_block_size_desc,
                StepperConfig(defaultSettingsLN.maxBlockSize.toFloat(), step = 10f) {
                    defaultSettingsLN.maxBlockSize = it.toInt()
                    PrefManager.setVal(PrefName.MaxBlockSize, it.toInt())
                }),
            row(R.string.justify_text, R.drawable.format_align_center_24, defaultSettingsLN.justify) {
                defaultSettingsLN.justify = it
                PrefManager.setVal(PrefName.Justify, it)
            },
            row(R.string.hyphenation, R.drawable.ic_round_format_text_24, defaultSettingsLN.hyphenation) {
                defaultSettingsLN.hyphenation = it
                PrefManager.setVal(PrefName.Hyphenation, it)
            },
            row(R.string.use_dark_theme, R.drawable.ic_round_brightness_4_24, defaultSettingsLN.useDarkTheme) {
                defaultSettingsLN.useDarkTheme = it
                PrefManager.setVal(PrefName.UseDarkThemeNovel, it)
            },
            row(R.string.use_oled_theme, R.drawable.ic_round_brightness_4_24, defaultSettingsLN.useOledTheme) {
                defaultSettingsLN.useOledTheme = it
                PrefManager.setVal(PrefName.UseOledThemeNovel, it)
            },
            row(R.string.lock_screen_rotation, R.drawable.ic_round_screen_rotation_24,
                defaultSettingsLN.lockRotation) {
                defaultSettingsLN.lockRotation = it
                PrefManager.setVal(PrefName.LockRotationNovel, it)
            },
            row(R.string.hide_page_numbers, R.drawable.ic_page_numbering, defaultSettingsLN.hidePageNumbers) {
                defaultSettingsLN.hidePageNumbers = it
                PrefManager.setVal(PrefName.HidePageNumbersNovel, it)
            },
            row(R.string.keep_screen_on, R.drawable.ic_baseline_screen_lock_portrait_24,
                defaultSettingsLN.keepScreenOn) {
                defaultSettingsLN.keepScreenOn = it
                PrefManager.setVal(PrefName.KeepScreenOnNovel, it)
            },
            row(R.string.volume_buttons, R.drawable.ic_round_touch_app_24, defaultSettingsLN.volumeButtons) {
                defaultSettingsLN.volumeButtons = it
                PrefManager.setVal(PrefName.VolumeButtonsNovel, it)
            },
            Settings(
                type = 1,
                name = getString(R.string.novel_tts),
                desc = getString(R.string.novel_tts_desc),
                icon = R.drawable.ic_round_headphones_24,
                onClick = {
                    NovelTtsSettingsBottomSheet.newInstance()
                        .show(supportFragmentManager, NovelTtsSettingsBottomSheet.TAG)
                },
            ),
        ) }

        bind(binding.readerGeneralRows, listOf(
            row(R.string.source_info, R.drawable.ic_round_info_24, PrefManager.getVal(PrefName.ShowSource)) {
                PrefManager.setVal(PrefName.ShowSource, it)
            },
            row(R.string.continuous_multi_chapter, R.drawable.ic_round_view_column_24,
                PrefManager.getVal(PrefName.ContinuousMultiChapter), R.string.continuous_multi_chapter_desc) {
                PrefManager.setVal(PrefName.ContinuousMultiChapter, it)
            },
            row(R.string.show_system_bars, R.drawable.ic_round_smart_button_24,
                PrefManager.getVal(PrefName.ShowSystemBars)) {
                PrefManager.setVal(PrefName.ShowSystemBars, it)
            },
        ))

        // Asking on every chapter makes the chapter-zero question moot, so that row is greyed out
        // while it's on: rebuilt whole, like the Player page's subtitle rows.
        val progressRows = ArrayList<Settings>()
        val progressAdapter = SettingsAdapter(progressRows, popIn = false)
        fun buildProgressRows(): List<Settings> {
            val askEach = PrefManager.getVal<Boolean>(PrefName.AskIndividualReader)
            return listOf(
                row(R.string.ask_update_progress_manga, R.drawable.ic_anilist, askEach,
                    R.string.ask_update_progress_info_chap) {
                    PrefManager.setVal(PrefName.AskIndividualReader, it)
                    progressRows.clear()
                    progressRows.addAll(buildProgressRows())
                    @Suppress("NotifyDataSetChanged") // The enabled state changes across the rows.
                    progressAdapter.notifyDataSetChanged()
                },
                row(R.string.ask_update_progress_chapter_zero, R.drawable.ic_round_assist_walker_24,
                    PrefManager.getVal(PrefName.ChapterZeroReader), R.string.ask_update_progress_info_zero,
                    enabled = !askEach) {
                    PrefManager.setVal(PrefName.ChapterZeroReader, it)
                },
                row(R.string.ask_update_progress_hentai_manga, R.drawable.ic_round_auto_awesome_24,
                    PrefManager.getVal(PrefName.UpdateForHReader)) {
                    PrefManager.setVal(PrefName.UpdateForHReader, it)
                    if (it) snackString(getString(R.string.very_bold))
                },
            )
        }
        progressRows.addAll(buildProgressRows())
        binding.readerProgressRows.adapter = progressAdapter
    }


    /**
     * What used to be the Manga screen.
     *
     * That screen held two rows, one of which was a link to this one. These apply before a chapter
     * is opened rather than while reading it, so they sit above the reader's own groups.
     */
    private fun bindLibraryRows() {
        binding.settingsRecyclerView.adapter = SettingsAdapter(
            arrayListOf(
                Settings(
                    type = 2,
                    name = getString(R.string.include_list),
                    desc = getString(R.string.include_list_desc),
                    icon = R.drawable.view_list_24,
                    anchorKey = "include_manga_list",
                    isChecked = PrefManager.getVal(PrefName.IncludeMangaList),
                    switch = { isChecked, _ ->
                        PrefManager.setVal(PrefName.IncludeMangaList, isChecked)
                        Xpandable.markRelaunch(Xpandable.SCOPE_READER)
                        restartApp()
                    }
                ),
                // Was a bare tri-toggle in the Manga layout with no label of its own; as a choice
                // row it keeps the buttons and gains the title and description it lacked.
                Settings(
                    type = 5,
                    name = getString(R.string.default_chp_view),
                    desc = getString(R.string.default_chp_view_desc),
                    icon = R.drawable.ic_round_view_list_24,
                    anchorKey = "default_chp_view",
                    choice = ChoiceConfig(
                        options = listOf(
                            ChoiceOption(R.drawable.ic_round_view_list_24, getString(R.string.list)),
                            ChoiceOption(R.drawable.ic_round_view_comfy_24, getString(R.string.compact)),
                        ),
                        selected = PrefManager.getVal(PrefName.MangaDefaultView),
                        onSelect = { PrefManager.setVal(PrefName.MangaDefaultView, it) },
                    ),
                ),
            )
        )
        binding.settingsRecyclerView.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
    }

    // -----------------------------------------------------------------------------------------
    // OCR & MTL keys; delete with ani.dantotsu.spike
    // -----------------------------------------------------------------------------------------

    /** The OCR & MTL group's rows, rebuilt by [refreshMtlRows] whenever a choice or key changes. */
    private val mtlRows = ArrayList<Settings>()

    /**
     * The OCR & MTL group as standard rows: the detection test, the two translate switches, then
     * engine, model, target language and source script, and each provider's key — every picker row
     * showing its current value as its description.
     *
     * The choices live here rather than in the calibration screen because they are what the *reader*
     * translates with; that screen tunes detection thresholds. They are the defaults a manga starts
     * from; each manga then keeps its own copy, edited from the reader's sheet. Re-read on every
     * rebuild rather than held, so a picker always starts from what was last saved.
     */
    private fun refreshMtlRows() {
        val settings = MtlSettings.fromPrefs()
        fun save(changed: MtlSettings) {
            changed.saveToPrefs()
            refreshMtlRows()
        }
        fun pick(name: Int, icon: Int, value: String, visible: Boolean = true, onClick: () -> Unit) =
            Settings(
                type = 1, name = getString(name), desc = value, icon = icon,
                isVisible = visible, onClick = { onClick() },
            )
        mtlRows.clear()
        mtlRows.addAll(listOf(
            Settings(
                type = 1,
                name = getString(R.string.ocr_spike),
                desc = getString(R.string.ocr_spike_desc),
                icon = R.drawable.ic_round_science_24,
                onClick = { startActivity(Intent(this, MangaOcrSpikeActivity::class.java)) },
            ),
            Settings(
                type = 2,
                name = getString(R.string.mtl_enable),
                desc = getString(R.string.mtl_enable_desc),
                icon = R.drawable.ic_round_translate_24,
                isChecked = PrefManager.getVal(PrefName.OcrTranslateEnabled),
                switch = { on, _ -> PrefManager.setVal(PrefName.OcrTranslateEnabled, on) },
            ),
            Settings(
                type = 2,
                name = getString(R.string.mtl_novel),
                desc = getString(R.string.mtl_novel_desc),
                icon = R.drawable.ic_round_translate_24,
                isChecked = PrefManager.getVal(PrefName.NovelTranslateEnabled),
                switch = { on, _ -> PrefManager.setVal(PrefName.NovelTranslateEnabled, on) },
            ),
            pick(R.string.ocr_spike_engine, R.drawable.ic_round_translate_24,
                MtlChoices.engineLabel(settings)) {
                MtlChoices.pickEngine(this, MtlSettings.fromPrefs(), ::save)
            },
            pick(R.string.ocr_spike_model, R.drawable.ic_round_tune_24,
                MtlChoices.modelLabel(this, settings), visible = settings.engine.needsKey) {
                MtlChoices.pickModel(this, lifecycleScope, MtlSettings.fromPrefs(), ::save)
            },
            pick(R.string.ocr_spike_target_language, R.drawable.ic_round_language_24,
                MtlChoices.targetLabel(settings)) {
                MtlChoices.pickTarget(this, MtlSettings.fromPrefs(), ::save)
            },
            pick(R.string.ocr_source_script, R.drawable.ic_round_format_text_24,
                MtlChoices.scriptLabel(this, settings)) {
                MtlChoices.pickScript(this, MtlSettings.fromPrefs(), onChanged = ::save)
            },
            keyRow(TranslationEngine.GEMINI, R.string.ocr_key_gemini),
            keyRow(TranslationEngine.OPENROUTER, R.string.ocr_key_openrouter),
        ))
        @Suppress("NotifyDataSetChanged") // Values, and the model row's visibility, change at once.
        binding.readerMtlRows.adapter?.notifyDataSetChanged()
    }

    /**
     * One provider's key row.
     *
     * A key is write-once: entered, checked against the provider, and from then on only removable.
     * There is no editing step because there is nothing to edit — a key is opaque, so "changing"
     * one means pasting a different one, which is a remove and an add. Offering a pre-filled box
     * instead would put the existing secret back on screen for no gain, and invite a half-edited
     * key being saved without ever being checked.
     */
    private fun keyRow(engine: TranslationEngine, name: Int): Settings {
        val stored = engine.storedKey().isNotBlank()
        return Settings(
            type = 1,
            name = getString(name),
            desc = getString(if (stored) R.string.ocr_key_set else R.string.ocr_key_not_set),
            icon = R.drawable.vpn_key_24,
            onClick = {
                if (engine.storedKey().isNotBlank()) {
                    customAlertDialog().apply {
                        setTitle(R.string.ocr_key_remove_title)
                        setMessage(getString(R.string.ocr_key_remove_message, engine.label))
                        setPosButton(R.string.remove) {
                            engine.keyPref?.let { PrefManager.removeVal(it) }
                            refreshMtlRows()
                            snackString(getString(R.string.ocr_key_removed))
                        }
                        setNegButton(R.string.cancel)
                        show()
                    }
                } else {
                    promptForOcrKey(engine, ::refreshMtlRows)
                }
            },
        )
    }

    private fun promptForOcrKey(engine: TranslationEngine, onSaved: () -> Unit) {
        val dialogView = DialogUserAgentBinding.inflate(layoutInflater)
        dialogView.subtitle.isVisible = true
        // Before the text, not after: TextView linkifies while setting, so assigning the mask
        // afterwards leaves the url as plain characters.
        dialogView.subtitle.autoLinkMask = Linkify.WEB_URLS
        dialogView.subtitle.text = buildString {
            append(getString(R.string.ocr_key_prompt, engine.label))
            // Where to get one, in the dialog that asks for it: someone who has not got a key yet
            // is exactly the person standing in front of this box, and sending them off to find
            // the right console page themselves is the step where they give up. Spelled out in
            // full rather than hidden behind a link, so it can also be typed on another device.
            engine.keyUrl?.let { append("\n\n").append(getString(R.string.ocr_key_get_at, it)) }
        }
        dialogView.userAgentTextBox.hint = getString(R.string.ocr_key_hint)

        customAlertDialog().apply {
            setTitle(engine.label)
            setCustomView(dialogView.root)
            setPosButton(R.string.ok) {
                val key = dialogView.userAgentTextBox.text?.toString()?.trim().orEmpty()
                if (key.isBlank()) return@setPosButton
                verifyAndSaveOcrKey(engine, key, onSaved)
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    /**
     * Stores the key only once the provider has agreed it works.
     *
     * Saving first and discovering later is the alternative, and it fails badly here: a mistyped
     * key would sit in settings looking configured, and the first sign of trouble would be a page
     * that quietly refuses to translate. One cheap authenticated call settles it while the user is
     * still looking at the dialog they pasted into.
     */
    private fun verifyAndSaveOcrKey(engine: TranslationEngine, key: String, onSaved: () -> Unit) {
        snackString(getString(R.string.ocr_key_checking))
        lifecycleScope.launch {
            val failure = LlmTranslator.verifyKey(engine, key)
            if (failure == null) {
                engine.keyPref?.let { PrefManager.setVal(it, key) }
                onSaved()
                snackString(getString(R.string.ocr_key_saved))
            } else {
                snackString(getString(R.string.ocr_key_invalid, failure))
            }
        }
    }
}
