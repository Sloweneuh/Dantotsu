package ani.dantotsu.settings

import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import androidx.recyclerview.widget.GridLayoutManager
import ani.dantotsu.getThemeColor
import androidx.core.view.marginBottom
import androidx.core.view.doOnLayout
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityPlayerSettingsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.media.Media
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.Xpandable
import ani.dantotsu.others.getSerialized
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.restartApp
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.choiceBottomSheet
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.slider.Slider.OnChangeListener
import eltos.simpledialogfragment.SimpleDialog
import eltos.simpledialogfragment.color.SimpleColorWheelDialog
import kotlin.math.roundToInt
import com.google.android.material.color.MaterialColors
import ani.dantotsu.media.anime.PlayerHomeAction
import android.text.style.RelativeSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.Spanned
import android.text.SpannableStringBuilder

class PlayerSettingsActivity :
    AppCompatActivity(),
    SimpleDialog.OnDialogResultListener {
    interface ColorPickerCallback {
        fun onColorSelected(color: Int)
    }

    private var colorPickerCallback: ColorPickerCallback? = null

    lateinit var binding: ActivityPlayerSettingsBinding
    private val player = "player_settings"

    var media: Media? = null
    var subtitle: Subtitle? = null

    private val Int.toSP
        get() =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                this.toFloat(),
                Resources.getSystem().displayMetrics,
            )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityPlayerSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SettingsRouter.handleHighlight(this, binding.settingsRecyclerView, binding.subtitleSettingsRecycler)
        bindLibraryRows()
        // Every group has now read the relaunch marker; consume it so one restart restores once.
        Xpandable.consumeRelaunch(Xpandable.SCOPE_PLAYER)

        initActivity(this)

        onBackPressedDispatcher.addCallback(this) {
            // Not a plain finish, which skipped the return transition and its icon flight.
            finishAfterTransition()
        }

        try {
            media = intent.getSerialized("media")
            subtitle = intent.getSerialized("subtitle")
        } catch (e: Exception) {
            toast(e.toString())
        }

        binding.playerSettingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }

        SettingsSearchButton.attach(this@PlayerSettingsActivity, binding.playerSettingsBack)
        binding.playerSettingsBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Video

        val speeds =
            arrayOf(
                0.25f,
                0.33f,
                0.5f,
                0.66f,
                0.75f,
                1f,
                1.15f,
                1.25f,
                1.33f,
                1.5f,
                1.66f,
                1.75f,
                2f,
            )
        val cursedSpeeds = arrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f, 10f, 25f, 50f)
        var curSpeedArr = if (PrefManager.getVal(PrefName.CursedSpeeds)) cursedSpeeds else speeds
        var speedsName = curSpeedArr.map { "${it}x" }.toTypedArray()
        binding.playerSettingsSpeed.text =
            getString(
                R.string.default_playback_speed,
                speedsName[PrefManager.getVal(PrefName.DefaultSpeed)],
            )
        binding.playerSettingsSpeed.setOnClickListener {
            customAlertDialog().apply {
                setTitle(getString(R.string.default_speed))
                singleChoiceItems(
                    speedsName,
                    PrefManager.getVal(PrefName.DefaultSpeed),
                ) { i ->
                    PrefManager.setVal(PrefName.DefaultSpeed, i)
                    binding.playerSettingsSpeed.text =
                        getString(R.string.default_playback_speed, speedsName[i])
                }
                show()
            }
        }

        binding.playerSettingsCursedSpeeds.isChecked = PrefManager.getVal(PrefName.CursedSpeeds)
        binding.playerSettingsCursedSpeeds.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.CursedSpeeds, isChecked)
            curSpeedArr = if (isChecked) cursedSpeeds else speeds
            val newDefaultSpeed = if (isChecked) 0 else 5
            PrefManager.setVal(PrefName.DefaultSpeed, newDefaultSpeed)
            speedsName = curSpeedArr.map { "${it}x" }.toTypedArray()
            binding.playerSettingsSpeed.text =
                getString(
                    R.string.default_playback_speed,
                    speedsName[PrefManager.getVal(PrefName.DefaultSpeed)],
                )
        }

        // Time Stamp
        binding.playerSettingsTimeStamps.isChecked = PrefManager.getVal(PrefName.TimeStampsEnabled)
        binding.playerSettingsTimeStamps.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.TimeStampsEnabled, isChecked)
            binding.playerSettingsAutoSkipOpEd.isEnabled = isChecked
        }

        binding.playerSettingsTimeStampsProxy.isChecked =
            PrefManager.getVal(PrefName.UseProxyForTimeStamps)
        binding.playerSettingsTimeStampsProxy.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.UseProxyForTimeStamps, isChecked)
        }

        binding.playerSettingsShowTimeStamp.isChecked =
            PrefManager.getVal(PrefName.ShowTimeStampButton)
        binding.playerSettingsShowTimeStamp.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.ShowTimeStampButton, isChecked)
            binding.playerSettingsTimeStampsAutoHide.isEnabled = isChecked
        }

        binding.playerSettingsTimeStampsAutoHide.isChecked =
            PrefManager.getVal(PrefName.AutoHideTimeStamps)
        binding.playerSettingsTimeStampsAutoHide.isEnabled =
            binding.playerSettingsShowTimeStamp.isChecked
        binding.playerSettingsTimeStampsAutoHide.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.AutoHideTimeStamps, isChecked)
        }

        // Auto
        binding.playerSettingsAutoSkipOpEd.isChecked = PrefManager.getVal(PrefName.AutoSkipOPED)
        binding.playerSettingsAutoSkipOpEd.isEnabled = binding.playerSettingsTimeStamps.isChecked
        binding.playerSettingsAutoSkipOpEd.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.AutoSkipOPED, isChecked)
        }

        binding.playerSettingsAutoSkipRecap.isChecked = PrefManager.getVal(PrefName.AutoSkipRecap)
        binding.playerSettingsAutoSkipRecap.isEnabled = binding.playerSettingsTimeStamps.isChecked
        binding.playerSettingsAutoSkipRecap.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.AutoSkipRecap, isChecked)
        }

        binding.playerSettingsAutoPlay.isChecked = PrefManager.getVal(PrefName.AutoPlay)
        binding.playerSettingsAutoPlay.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.AutoPlay, isChecked)
        }

        binding.playerSettingsAutoSkip.isChecked = PrefManager.getVal(PrefName.AutoSkipFiller)
        binding.playerSettingsAutoSkip.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.AutoSkipFiller, isChecked)
        }

        // Update Progress
        binding.playerSettingsAskUpdateProgress.isChecked =
            PrefManager.getVal(PrefName.AskIndividualPlayer)
        binding.playerSettingsAskUpdateProgress.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.AskIndividualPlayer, isChecked)
            binding.playerSettingsAskChapterZero.isEnabled = !isChecked
        }
        binding.playerSettingsAskChapterZero.isChecked =
            PrefManager.getVal(PrefName.ChapterZeroPlayer)
        binding.playerSettingsAskChapterZero.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.ChapterZeroPlayer, isChecked)
        }
        binding.playerSettingsAskUpdateHentai.isChecked =
            PrefManager.getVal(PrefName.UpdateForHPlayer)
        binding.playerSettingsAskUpdateHentai.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.UpdateForHPlayer, isChecked)
            if (isChecked) snackString(getString(R.string.very_bold))
        }
        binding.playerSettingsCompletePercentage.value =
            (PrefManager.getVal<Float>(PrefName.WatchPercentage) * 100).roundToInt().toFloat()
        binding.playerSettingsCompletePercentage.addOnChangeListener { _, value, _ ->
            PrefManager.setVal(PrefName.WatchPercentage, value / 100)
        }

        // Behaviour
        binding.playerSettingsAlwaysContinue.isChecked = PrefManager.getVal(PrefName.AlwaysContinue)
        binding.playerSettingsAlwaysContinue.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.AlwaysContinue, isChecked)
        }

        binding.playerSettingsPauseVideo.isChecked = PrefManager.getVal(PrefName.FocusPause)
        binding.playerSettingsPauseVideo.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.FocusPause, isChecked)
        }

        bindHomeAction()

        binding.playerSettingsBackgroundPlay.isChecked = PrefManager.getVal(PrefName.PlayerBackgroundPlay)
        binding.playerSettingsBackgroundPlay.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.PlayerBackgroundPlay, isChecked)
        }

        binding.playerSettingsVerticalGestures.isChecked = PrefManager.getVal(PrefName.Gestures)
        binding.playerSettingsVerticalGestures.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.Gestures, isChecked)
        }

        binding.playerSettingsDoubleTap.isChecked = PrefManager.getVal(PrefName.DoubleTap)
        binding.playerSettingsDoubleTap.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.DoubleTap, isChecked)
        }
        binding.playerSettingsFastForward.isChecked = PrefManager.getVal(PrefName.FastForward)
        binding.playerSettingsFastForward.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.FastForward, isChecked)
        }
        binding.playerSettingsSeekTime.value = PrefManager.getVal<Int>(PrefName.SeekTime).toFloat()
        binding.playerSettingsSeekTime.addOnChangeListener { _, value, _ ->
            PrefManager.setVal(PrefName.SeekTime, value.toInt())
        }

        binding.exoSkipTime.setText(PrefManager.getVal<Int>(PrefName.SkipTime).toString())
        binding.exoSkipTime.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.exoSkipTime.clearFocus()
            }
            false
        }
        binding.exoSkipTime.addTextChangedListener {
            val time =
                binding.exoSkipTime.text
                    .toString()
                    .toIntOrNull()
            if (time != null) {
                PrefManager.setVal(PrefName.SkipTime, time)
            }
        }

        // Other
        binding.playerSettingsCast.isChecked = PrefManager.getVal(PrefName.Cast)
        binding.playerSettingsCast.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.Cast, isChecked)
        }

        binding.playerSettingsRotate.isChecked = PrefManager.getVal(PrefName.RotationPlayer)
        binding.playerSettingsRotate.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.RotationPlayer, isChecked)
        }

        binding.playerSettingsInternalCast.isChecked = PrefManager.getVal(PrefName.UseInternalCast)
        binding.playerSettingsInternalCast.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.UseInternalCast, isChecked)
        }

        binding.playerSettingsAdditionalCodec.isChecked =
            PrefManager.getVal(PrefName.UseAdditionalCodec)
        binding.playerSettingsAdditionalCodec.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.UseAdditionalCodec, isChecked)
        }

        val resizeModes = arrayOf("Original", "Zoom", "Stretch")
        binding.playerResizeMode.setOnClickListener {
            customAlertDialog().apply {
                setTitle(getString(R.string.default_resize_mode))
                singleChoiceItems(
                    resizeModes,
                    PrefManager.getVal<Int>(PrefName.Resize),
                ) { count ->
                    PrefManager.setVal(PrefName.Resize, count)
                }
                show()
            }
        }

        bindSubtitleSection()
    }

    private fun showColorPicker(
        originalColor: Int,
        title: String,
        callback: ColorPickerCallback,
    ) {
        colorPickerCallback = callback

        SimpleColorWheelDialog()
            .title(title)
            .color(originalColor)
            .alpha(true)
            .neg()
            .theme(R.style.MyPopup)
            .show(this, "colorPicker")
    }

    override fun onResult(
        dialogTag: String,
        which: Int,
        extras: Bundle,
    ): Boolean {
        if (dialogTag == "colorPicker" && which == SimpleDialog.OnDialogResultListener.BUTTON_POSITIVE) {
            val color = extras.getInt(SimpleColorWheelDialog.COLOR)
            colorPickerCallback?.onColorSelected(color)

            return true
        }
        return false
    }

    /** The Home button row: its title over the current choice, which a tap picks from. */
    private fun bindHomeAction() {
        val names = PlayerHomeAction.names.map { getString(it) }
        val row = binding.playerSettingsHomeAction
        fun render() {
            val title = getString(R.string.player_home_action)
            row.text = SpannableStringBuilder(title).append("\n").apply {
                val start = length
                append(names[PlayerHomeAction.current()])
                setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(
                    ForegroundColorSpan(
                        MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurfaceVariant)
                    ),
                    start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        render()
        row.setOnClickListener {
            choiceBottomSheet(getString(R.string.player_home_action), names, PlayerHomeAction.current()) { index ->
                PlayerHomeAction.set(index)
                render()
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Subtitles
    // -----------------------------------------------------------------------------------------

    private val subtitleRows = ArrayList<Settings>()
    private lateinit var subtitleAdapter: SettingsAdapter
    private lateinit var styleTiles: SubtitleStyleTileAdapter

    private val subtitleLanguages = listOf(
        "Albanian", "Arabic", "Bosnian", "Bulgarian", "Chinese", "Croatian", "Czech", "Danish",
        "Dutch", "English", "Estonian", "Finnish", "French", "Georgian", "German", "Greek",
        "Hebrew", "Hindi", "Indonesian", "Irish", "Italian", "Japanese", "Korean", "Lithuanian",
        "Luxembourgish", "Macedonian", "Mongolian", "Norwegian", "Polish", "Portuguese", "Punjabi",
        "Romanian", "Russian", "Serbian", "Slovak", "Slovenian", "Spanish", "Turkish", "Ukrainian",
        "Urdu", "Vietnamese",
    )

    private val subtitleFonts = listOf(
        "Poppins Semi Bold", "Poppins Bold", "Poppins", "Poppins Thin", "Century Gothic",
        "Levenim MT Bold", "Blocky",
    )

    private val subtitleFontFaces = listOf(
        R.font.poppins_semi_bold, R.font.poppins_bold, R.font.poppins, R.font.poppins_thin,
        R.font.century_gothic_regular, R.font.levenim_mt_bold, R.font.blocky,
    )

    private fun outlineStyles() = listOf(
        getString(R.string.sub_outline_outline),
        getString(R.string.sub_outline_shine),
        getString(R.string.sub_outline_shadow),
        getString(R.string.sub_outline_none),
    )

    /** A slider tile's range, how it reads and writes its preference, and how it shows a value. */
    private class SliderSpec(
        val from: Float,
        val to: Float,
        val step: Float,
        val read: () -> Float,
        val write: (Float) -> Unit,
        val format: (Float) -> String,
    )

    private val sliderSpecs: Map<String, SliderSpec> by lazy {
        mapOf(
            TILE_SIZE to SliderSpec(
                SUB_SIZE_MIN, SUB_SIZE_MAX, 1f,
                read = { PrefManager.getVal<Int>(PrefName.FontSize).toFloat() },
                write = { PrefManager.setVal(PrefName.FontSize, it.roundToInt()) },
                format = { getString(R.string.sub_size_value, it.roundToInt()) },
            ),
            TILE_THICKNESS to SliderSpec(
                0f, 30f, 1f,
                read = { PrefManager.getVal(PrefName.SubStroke) },
                write = { PrefManager.setVal(PrefName.SubStroke, it) },
                format = { it.roundToInt().toString() },
            ),
            TILE_OPACITY to SliderSpec(
                0f, 1f, 0.1f,
                read = { PrefManager.getVal(PrefName.SubAlpha) },
                write = { PrefManager.setVal(PrefName.SubAlpha, it) },
                format = { "${(it * 100).roundToInt()}%" },
            ),
            // The player lifts the text by value/50 of the screen's height.
            TILE_POSITION to SliderSpec(
                0f, 50f, 1f,
                read = { PrefManager.getVal(PrefName.SubBottomMargin) },
                write = { PrefManager.setVal(PrefName.SubBottomMargin, it) },
                format = { "${(it * 2).roundToInt()}%" },
            ),
        )
    }

    /**
     * The subtitle group: a live preview, the few rows that switch subtitles on and decide how
     * they're drawn, and the look itself as a grid of tiles.
     *
     * The look used to be ten rows, one after the other — a long scroll during which the preview
     * went off the top of the screen. As tiles, each shows what it's set to, so the whole style
     * reads at a glance beside the preview, and a tap opens its editor right under the grid rather
     * than away from it: a slider for the amounts, a row of choices for the font (each written in
     * its own face) and the outline style, the colour picker for a colour.
     */
    private fun bindSubtitleSection() {
        subtitleAdapter = SettingsAdapter(subtitleRows, popIn = false)
        binding.subtitleSettingsRecycler.adapter = subtitleAdapter
        styleTiles = SubtitleStyleTileAdapter(::onStyleTileTapped)
        binding.subtitleStyleGrid.layoutManager = GridLayoutManager(this, 2)
        binding.subtitleStyleGrid.adapter = styleTiles
        binding.subtitleStyleEditor.setCardBackgroundColor(
            SectionCard.fill(binding.subtitleStyleEditor, open = true)
        )
        refreshSubtitleRows()
        // The position preview works in the frame's own height, known once it's laid out.
        binding.subtitlePreviewFrame.doOnLayout { updateSubtitlePreview() }
    }

    /** Rebuilds the rows and tiles from the preferences, after a switch, dialog or chip changed one. */
    private fun refreshSubtitleRows() {
        subtitleRows.clear()
        subtitleRows.addAll(buildSubtitleRows())
        @Suppress("NotifyDataSetChanged") // Enabled states change across the rows at once.
        subtitleAdapter.notifyDataSetChanged()
        styleTiles.submit(buildStyleTiles())
        // An editor whose setting just became unavailable (subtitles switched off, say) closes.
        val open = styleTiles.selectedId
        if (open != null && styleTiles.tiles.none { it.id == open && it.enabled }) closeStyleEditor()
        updateSubtitlePreview()
    }

    private fun buildSubtitleRows(): List<Settings> {
        val on = PrefManager.getVal<Boolean>(PrefName.Subtitles)
        return listOf(
            Settings(
                type = 2,
                name = getString(R.string.sub_show),
                desc = getString(R.string.sub_show_desc),
                icon = R.drawable.ic_round_subtitles_24,
                compact = true,
                isChecked = on,
                switch = { checked, _ ->
                    PrefManager.setVal(PrefName.Subtitles, checked)
                    binding.subtitleSettingsRecycler.post { refreshSubtitleRows() }
                },
            ),
            Settings(
                type = 1,
                name = getString(R.string.sub_language),
                desc = subtitleLanguages.getOrElse(PrefManager.getVal(PrefName.SubLanguage)) { "" },
                icon = R.drawable.ic_round_translate_24,
                compact = true,
                isEnabled = on,
                onClick = {
                    choiceBottomSheet(
                        getString(R.string.sub_language),
                        subtitleLanguages,
                        PrefManager.getVal(PrefName.SubLanguage),
                    ) { index ->
                        PrefManager.setVal(PrefName.SubLanguage, index)
                        refreshSubtitleRows()
                    }
                },
            ),
            Settings(
                type = 2,
                name = getString(R.string.sub_renderer),
                desc = getString(R.string.sub_renderer_desc),
                icon = R.drawable.ic_round_short_text_24,
                compact = true,
                isEnabled = on,
                isChecked = PrefManager.getVal(PrefName.TextviewSubtitles),
                switch = { checked, _ ->
                    PrefManager.setVal(PrefName.TextviewSubtitles, checked)
                    binding.subtitleSettingsRecycler.post { refreshSubtitleRows() }
                },
            ),
        )
    }

    private fun buildStyleTiles(): List<SubtitleStyleTile> {
        val on = PrefManager.getVal<Boolean>(PrefName.Subtitles)
        // Thickness and position only mean something to the text renderer.
        val textRenderer = on && PrefManager.getVal<Boolean>(PrefName.TextviewSubtitles)
        fun slider(id: String, icon: Int, label: Int, enabled: Boolean = on): SubtitleStyleTile {
            val spec = sliderSpecs.getValue(id)
            return SubtitleStyleTile(
                id, icon, getString(label), spec.format(snap(spec, spec.read())),
                SubtitleTileEditor.SLIDER, enabled,
            )
        }
        fun color(id: String, icon: Int, label: Int, pref: PrefName): SubtitleStyleTile {
            val value = PrefManager.getVal<Int>(pref)
            return SubtitleStyleTile(
                id, icon, getString(label), colorLabel(value),
                SubtitleTileEditor.COLOR, on, colorSwatch(value),
            )
        }
        return listOf(
            SubtitleStyleTile(
                TILE_FONT, R.drawable.ic_round_format_text_24, getString(R.string.sub_font),
                subtitleFonts.getOrElse(PrefManager.getVal(PrefName.Font)) { subtitleFonts[0] },
                SubtitleTileEditor.CHOICE, on,
            ),
            slider(TILE_SIZE, R.drawable.ic_round_font_size_24, R.string.sub_size),
            color(TILE_TEXT_COLOR, R.drawable.ic_round_color_24, R.string.sub_text_color, PrefName.PrimaryColor),
            SubtitleStyleTile(
                TILE_OUTLINE_STYLE, R.drawable.format_title_24, getString(R.string.sub_outline_style),
                outlineStyles().getOrElse(PrefManager.getVal(PrefName.Outline)) { outlineStyles()[0] },
                SubtitleTileEditor.CHOICE, on,
            ),
            color(TILE_OUTLINE_COLOR, R.drawable.ic_round_color_picker_24, R.string.sub_outline_color, PrefName.SecondaryColor),
            slider(TILE_THICKNESS, R.drawable.ic_format_line_spacing_24, R.string.sub_outline_thickness, textRenderer),
            color(TILE_BEHIND, R.drawable.ic_palette, R.string.sub_background_color, PrefName.SubBackground),
            color(TILE_AROUND, R.drawable.format_video_24, R.string.sub_window_color, PrefName.SubWindow),
            slider(TILE_OPACITY, R.drawable.blur_on, R.string.sub_opacity),
            slider(TILE_POSITION, R.drawable.ic_round_swipe_vertical_24, R.string.sub_bottom, textRenderer),
        )
    }

    private fun onStyleTileTapped(tile: SubtitleStyleTile) {
        when (tile.editor) {
            SubtitleTileEditor.COLOR -> {
                closeStyleEditor()
                val pref = when (tile.id) {
                    TILE_TEXT_COLOR -> PrefName.PrimaryColor
                    TILE_OUTLINE_COLOR -> PrefName.SecondaryColor
                    TILE_BEHIND -> PrefName.SubBackground
                    else -> PrefName.SubWindow
                }
                showColorPicker(
                    PrefManager.getVal(pref),
                    tile.label,
                    object : ColorPickerCallback {
                        override fun onColorSelected(color: Int) {
                            PrefManager.setVal(pref, color)
                            refreshSubtitleRows()
                        }
                    },
                )
            }

            else -> {
                // Tapping the open tile again puts its editor away.
                if (styleTiles.selectedId == tile.id) {
                    closeStyleEditor()
                    return
                }
                val editor = binding.subtitleStyleEditor
                val wasOpen = editor.isVisible
                val fill = { configureStyleEditor(tile) }
                if (wasOpen) SectionCard.animateHeightChange(editor, fill) else fill()
                styleTiles.select(tile.id)
                SectionCard.setExpanded(editor, true)
            }
        }
    }

    private fun configureStyleEditor(tile: SubtitleStyleTile) {
        binding.subtitleEditorTitle.text = tile.label
        val slider = binding.subtitleEditorSlider
        val chipsScroll = binding.subtitleEditorChipsScroll
        val spec = sliderSpecs[tile.id]
        if (spec != null) {
            chipsScroll.visibility = View.GONE
            slider.visibility = View.VISIBLE
            slider.clearOnChangeListeners()
            slider.valueFrom = spec.from
            slider.valueTo = spec.to
            slider.stepSize = spec.step
            slider.value = snap(spec, spec.read())
            binding.subtitleEditorValue.text = spec.format(slider.value)
            slider.addOnChangeListener { _, value, fromUser ->
                binding.subtitleEditorValue.text = spec.format(value)
                if (!fromUser) return@addOnChangeListener
                spec.write(value)
                styleTiles.tiles.firstOrNull { it.id == tile.id }?.let {
                    styleTiles.update(it.copy(value = spec.format(value)))
                }
                updateSubtitlePreview()
            }
            return
        }
        slider.visibility = View.GONE
        chipsScroll.visibility = View.VISIBLE
        binding.subtitleEditorValue.text = ""
        when (tile.id) {
            TILE_FONT -> fillChoices(subtitleFonts, PrefManager.getVal(PrefName.Font), subtitleFontFaces) {
                PrefManager.setVal(PrefName.Font, it)
            }
            TILE_OUTLINE_STYLE -> fillChoices(outlineStyles(), PrefManager.getVal(PrefName.Outline)) {
                PrefManager.setVal(PrefName.Outline, it)
            }
        }
    }

    /**
     * The choice editor's chips, [selected] checked. With [faces], each chip is written in the font
     * it picks, so the choice can be made by eye rather than by name.
     */
    private fun fillChoices(
        options: List<String>,
        selected: Int,
        faces: List<Int>? = null,
        onSelect: (Int) -> Unit,
    ) {
        val group = binding.subtitleEditorChips
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()
        options.forEachIndexed { index, label ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isChecked = index == selected
                faces?.getOrNull(index)?.let { typeface = ResourcesCompat.getFont(context, it) }
            }
            group.addView(chip)
            if (index == selected) {
                chip.post { binding.subtitleEditorChipsScroll.smoothScrollTo(chip.left - chip.width / 2, 0) }
            }
        }
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val index = (0 until group.childCount).firstOrNull { group.getChildAt(it).id == checkedIds.firstOrNull() }
                ?: return@setOnCheckedStateChangeListener
            onSelect(index)
            styleTiles.submit(buildStyleTiles())
            updateSubtitlePreview()
        }
    }

    private fun closeStyleEditor() {
        if (!::styleTiles.isInitialized) return
        styleTiles.select(null)
        SectionCard.setExpanded(binding.subtitleStyleEditor, false)
    }

    /** [value] on the slider's own steps and inside its range, as Slider insists. */
    private fun snap(spec: SliderSpec, value: Float): Float {
        val steps = ((value - spec.from) / spec.step).roundToInt()
        return (spec.from + steps * spec.step).coerceIn(spec.from, spec.to)
    }

    private fun colorLabel(color: Int): String =
        if (Color.alpha(color) == 0) getString(R.string.sub_color_transparent)
        else String.format("#%06X", color and 0xFFFFFF)

    /** A filled dot ringed in the outline colour, so white and transparent still read as a dot. */
    private fun colorSwatch(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        val size = (18 * resources.displayMetrics.density).roundToInt()
        setSize(size, size)
        setColor(color)
        setStroke(
            (1.5f * resources.displayMetrics.density).roundToInt(),
            getThemeColor(com.google.android.material.R.attr.colorOutline),
        )
    }

    /**
     * Draws the preview the way the player draws text subtitles (see ExoplayerView), so what is set
     * here is what shows. Outline thickness and position only apply with text-based rendering; the
     * player's other renderer uses its own thin outline and fixed position, and so does the preview
     * when that one is in use.
     */
    private fun updateSubtitlePreview() {
        val text = binding.subtitlePreviewText
        val window = binding.subtitlePreviewWindow
        val textRenderer = PrefManager.getVal<Boolean>(PrefName.TextviewSubtitles)
        val outlineColor = PrefManager.getVal<Int>(PrefName.SecondaryColor)
        val fontSize = PrefManager.getVal<Int>(PrefName.FontSize).toFloat()

        window.setBackgroundColor(PrefManager.getVal(PrefName.SubWindow))
        window.alpha = if (PrefManager.getVal(PrefName.Subtitles)) PrefManager.getVal(PrefName.SubAlpha) else 0.15f

        text.typeface = subtitleTypeface()
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        text.setTextColor(PrefManager.getVal<Int>(PrefName.PrimaryColor))
        text.setBackgroundColor(PrefManager.getVal(PrefName.SubBackground))
        val stroke = if (textRenderer) PrefManager.getVal<Float>(PrefName.SubStroke)
        else text.textSize * DEFAULT_OUTLINE_RATIO
        when (PrefManager.getVal<Int>(PrefName.Outline)) {
            0 -> text.applyOutline(outlineColor, stroke)
            1 -> text.applyShineEffect(outlineColor)
            2 -> text.applyDropShadow(outlineColor, stroke)
            3 -> text.clearEffect()
            else -> text.applyOutline(outlineColor, stroke)
        }

        // Same proportion of the frame as the player lifts it on screen, held inside the frame.
        val frame = binding.subtitlePreviewFrame
        val room = (frame.height - window.height - window.marginBottom).coerceAtLeast(0)
        val lift = if (textRenderer) PrefManager.getVal<Float>(PrefName.SubBottomMargin) / 50f * frame.height else 0f
        window.translationY = -lift.coerceAtMost(room.toFloat())
    }

    private fun subtitleTypeface() = ResourcesCompat.getFont(
        this,
        when (PrefManager.getVal<Int>(PrefName.Font)) {
            1 -> R.font.poppins_bold
            2 -> R.font.poppins
            3 -> R.font.poppins_thin
            4 -> R.font.century_gothic_regular
            5 -> R.font.levenim_mt_bold
            6 -> R.font.blocky
            else -> R.font.poppins_semi_bold
        },
    )

    /**
     * What used to be the Anime screen.
     *
     * That screen held four rows, one of which was a link to this one — so the label "Anime"
     * promised a section and delivered a near-empty list with a door in it. These rows apply before
     * playback rather than during it, so they sit above the player's own groups.
     */
    private fun bindLibraryRows() {
        binding.settingsRecyclerView.adapter = SettingsAdapter(
            arrayListOf(
                Settings(
                    type = 2,
                    name = getString(R.string.prefer_dub),
                    desc = getString(R.string.prefer_dub_desc),
                    icon = R.drawable.ic_anime_dub_24,
                    anchorKey = "prefer_dub",
                    isChecked = PrefManager.getVal(PrefName.SettingsPreferDub),
                    switch = { isChecked, _ ->
                        PrefManager.setVal(PrefName.SettingsPreferDub, isChecked)
                    }
                ),
                Settings(
                    type = 2,
                    name = getString(R.string.show_yt),
                    desc = getString(R.string.show_yt_desc),
                    icon = R.drawable.format_youtube_24,
                    anchorKey = "show_yt",
                    isChecked = PrefManager.getVal(PrefName.ShowYtButton),
                    switch = { isChecked, _ ->
                        PrefManager.setVal(PrefName.ShowYtButton, isChecked)
                    }
                ),
                Settings(
                    type = 2,
                    name = getString(R.string.include_list),
                    desc = getString(R.string.include_list_anime_desc),
                    icon = R.drawable.view_list_24,
                    anchorKey = "include_anime_list",
                    isChecked = PrefManager.getVal(PrefName.IncludeAnimeList),
                    switch = { isChecked, _ ->
                        PrefManager.setVal(PrefName.IncludeAnimeList, isChecked)
                        Xpandable.markRelaunch(Xpandable.SCOPE_PLAYER)
                        restartApp()
                    }
                ),
                // Was a bare tri-toggle in the Anime layout with no label of its own; as a choice
                // row it keeps the three buttons and gains the title and description it lacked.
                Settings(
                    type = 5,
                    name = getString(R.string.default_ep_view),
                    startsGroup = true,
                    desc = getString(R.string.default_ep_view_desc),
                    icon = R.drawable.ic_round_view_list_24,
                    anchorKey = "default_ep_view",
                    choice = ChoiceConfig(
                        options = listOf(
                            ChoiceOption(R.drawable.ic_round_view_list_24, getString(R.string.list)),
                            ChoiceOption(R.drawable.ic_round_grid_view_24, getString(R.string.grid)),
                            ChoiceOption(R.drawable.ic_round_view_comfy_24, getString(R.string.compact)),
                        ),
                        selected = PrefManager.getVal(PrefName.AnimeDefaultView),
                        onSelect = { PrefManager.setVal(PrefName.AnimeDefaultView, it) },
                    ),
                ),
            ),
            tiled = true,
        )
        binding.settingsRecyclerView.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
    }
}

private const val TILE_FONT = "font"
private const val TILE_SIZE = "size"
private const val TILE_TEXT_COLOR = "text_color"
private const val TILE_OUTLINE_STYLE = "outline_style"
private const val TILE_OUTLINE_COLOR = "outline_color"
private const val TILE_THICKNESS = "thickness"
private const val TILE_BEHIND = "behind"
private const val TILE_AROUND = "around"
private const val TILE_OPACITY = "opacity"
private const val TILE_POSITION = "position"

private const val SUB_SIZE_MIN = 8f
private const val SUB_SIZE_MAX = 64f

/** What the player's non-text renderer outline roughly comes to, as a share of the text size. */
private const val DEFAULT_OUTLINE_RATIO = 0.08f
