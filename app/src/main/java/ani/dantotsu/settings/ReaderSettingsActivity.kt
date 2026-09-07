package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.NoPaddingArrayAdapter
import androidx.recyclerview.widget.LinearLayoutManager
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
        SettingsRouter.handleHighlight(this, binding.settingsRecyclerView)
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

        //Manga Settings
        binding.readerSettingsSourceName.isChecked = PrefManager.getVal(PrefName.ShowSource)
        binding.readerSettingsSourceName.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.ShowSource, isChecked)
        }

        binding.readerSettingsSystemBars.isChecked = PrefManager.getVal(PrefName.ShowSystemBars)
        binding.readerSettingsSystemBars.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.ShowSystemBars, isChecked)
        }
        //Default Manga
        binding.readerSettingsAutoWebToon.isChecked = PrefManager.getVal(PrefName.AutoDetectWebtoon)
        binding.readerSettingsAutoWebToon.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.AutoDetectWebtoon, isChecked)
        }


        val layoutList = listOf(
            binding.readerSettingsPaged,
            binding.readerSettingsContinuousPaged,
            binding.readerSettingsContinuous
        )

        binding.readerSettingsLayoutText.text =
            resources.getStringArray(R.array.manga_layouts)[defaultSettings.layout.ordinal]
        var selectedLayout = layoutList[defaultSettings.layout.ordinal]
        selectedLayout.alpha = 1f

        layoutList.forEachIndexed { index, imageButton ->
            imageButton.setOnClickListener {
                selectedLayout.alpha = 0.33f
                selectedLayout = imageButton
                selectedLayout.alpha = 1f
                defaultSettings.layout =
                    CurrentReaderSettings.Layouts[index] ?: CurrentReaderSettings.Layouts.CONTINUOUS
                binding.readerSettingsLayoutText.text =
                    resources.getStringArray(R.array.manga_layouts)[defaultSettings.layout.ordinal]
                PrefManager.setVal(PrefName.LayoutReader, defaultSettings.layout.ordinal)
            }
        }

        binding.readerSettingsDirectionText.text =
            resources.getStringArray(R.array.manga_directions)[defaultSettings.direction.ordinal]
        binding.readerSettingsDirection.rotation = 90f * (defaultSettings.direction.ordinal)
        binding.readerSettingsDirection.setOnClickListener {
            defaultSettings.direction =
                CurrentReaderSettings.Directions[defaultSettings.direction.ordinal + 1]
                    ?: CurrentReaderSettings.Directions.TOP_TO_BOTTOM
            binding.readerSettingsDirectionText.text =
                resources.getStringArray(R.array.manga_directions)[defaultSettings.direction.ordinal]
            binding.readerSettingsDirection.rotation = 90f * (defaultSettings.direction.ordinal)
            PrefManager.setVal(PrefName.Direction, defaultSettings.direction.ordinal)
        }

        val dualList = listOf(
            binding.readerSettingsDualNo,
            binding.readerSettingsDualAuto,
            binding.readerSettingsDualForce
        )

        binding.readerSettingsDualPageText.text = defaultSettings.dualPageMode.toString()
        var selectedDual = dualList[defaultSettings.dualPageMode.ordinal]
        selectedDual.alpha = 1f

        dualList.forEachIndexed { index, imageButton ->
            imageButton.setOnClickListener {
                selectedDual.alpha = 0.33f
                selectedDual = imageButton
                selectedDual.alpha = 1f
                defaultSettings.dualPageMode = CurrentReaderSettings.DualPageModes[index]
                    ?: CurrentReaderSettings.DualPageModes.Automatic
                binding.readerSettingsDualPageText.text = defaultSettings.dualPageMode.toString()
                PrefManager.setVal(
                    PrefName.DualPageModeReader,
                    defaultSettings.dualPageMode.ordinal
                )
            }
        }
        binding.readerSettingsTrueColors.isChecked = defaultSettings.trueColors
        binding.readerSettingsTrueColors.setOnCheckedChangeListener { _, isChecked ->
            defaultSettings.trueColors = isChecked
            PrefManager.setVal(PrefName.TrueColors, isChecked)
        }

        binding.readerSettingsCropBorders.isChecked = defaultSettings.cropBorders
        binding.readerSettingsCropBorders.setOnCheckedChangeListener { _, isChecked ->
            defaultSettings.cropBorders = isChecked
            PrefManager.setVal(PrefName.CropBorders, isChecked)
        }

        binding.readerSettingsImageRotation.isChecked = defaultSettings.rotation
        binding.readerSettingsImageRotation.setOnCheckedChangeListener { _, isChecked ->
            defaultSettings.rotation = isChecked
            PrefManager.setVal(PrefName.Rotation, isChecked)
        }

        binding.readerSettingsHorizontalScrollBar.isChecked = defaultSettings.horizontalScrollBar
        binding.readerSettingsHorizontalScrollBar.setOnCheckedChangeListener { _, isChecked ->
            defaultSettings.horizontalScrollBar = isChecked
            PrefManager.setVal(PrefName.HorizontalScrollBar, isChecked)
        }
        binding.readerSettingsPadding.isChecked = defaultSettings.padding
        binding.readerSettingsPadding.setOnCheckedChangeListener { _, isChecked ->
            defaultSettings.padding = isChecked
            PrefManager.setVal(PrefName.Padding, isChecked)
        }

        binding.readerSettingsKeepScreenOn.isChecked = defaultSettings.keepScreenOn
        binding.readerSettingsKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            defaultSettings.keepScreenOn = isChecked
            PrefManager.setVal(PrefName.KeepScreenOn, isChecked)
        }

        binding.readerSettingsLockRotation.isChecked = defaultSettings.lockRotation
        binding.readerSettingsLockRotation.setOnCheckedChangeListener { _, isChecked ->
            defaultSettings.lockRotation = isChecked
            PrefManager.setVal(PrefName.LockRotation, isChecked)
        }

        binding.readerSettingsHideScrollBar.isChecked = defaultSettings.hideScrollBar
        binding.readerSettingsHideScrollBar.setOnCheckedChangeListener { _, isChecked ->
            defaultSettings.hideScrollBar = isChecked
            PrefManager.setVal(PrefName.HideScrollBar, isChecked)
        }

        binding.readerSettingsHidePageNumbers.isChecked = defaultSettings.hidePageNumbers
        binding.readerSettingsHidePageNumbers.setOnCheckedChangeListener { _, isChecked ->
            defaultSettings.hidePageNumbers = isChecked
            PrefManager.setVal(PrefName.HidePageNumbers, isChecked)
        }

        binding.readerSettingsOverscroll.isChecked = defaultSettings.overScrollMode
        binding.readerSettingsOverscroll.setOnCheckedChangeListener { _, isChecked ->
            defaultSettings.overScrollMode = isChecked
            PrefManager.setVal(PrefName.OverScrollMode, isChecked)
        }

        binding.readerSettingsVolumeButton.isChecked = defaultSettings.volumeButtons
        binding.readerSettingsVolumeButton.setOnCheckedChangeListener { _, isChecked ->
            defaultSettings.volumeButtons = isChecked
            PrefManager.setVal(PrefName.VolumeButtonsReader, isChecked)
        }

        binding.readerSettingsWrapImages.isChecked = defaultSettings.wrapImages
        binding.readerSettingsWrapImages.setOnCheckedChangeListener { _, isChecked ->
            defaultSettings.wrapImages = isChecked
            PrefManager.setVal(PrefName.WrapImages, isChecked)
        }

        binding.readerSettingsLongClickImage.isChecked = defaultSettings.longClickImage
        binding.readerSettingsLongClickImage.setOnCheckedChangeListener { _, isChecked ->
            defaultSettings.longClickImage = isChecked
            PrefManager.setVal(PrefName.LongClickImage, isChecked)
        }

        binding.readerSettingsPreloadAmount.setText(defaultSettings.preloadAmount.toString())
        binding.readerSettingsPreloadAmount.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = (binding.readerSettingsPreloadAmount.text.toString().toIntOrNull()
                    ?: defaultSettings.preloadAmount).coerceIn(4, 20)
                defaultSettings.preloadAmount = value
                binding.readerSettingsPreloadAmount.setText(value.toString())
                PrefManager.setVal(PrefName.PreloadAmount, value)
            }
        }

        binding.readerSettingsIncrementPreloadAmount.setOnClickListener {
            val value = (defaultSettings.preloadAmount + 1).coerceIn(4, 20)
            defaultSettings.preloadAmount = value
            binding.readerSettingsPreloadAmount.setText(value.toString())
            PrefManager.setVal(PrefName.PreloadAmount, value)
        }

        binding.readerSettingsDecrementPreloadAmount.setOnClickListener {
            val value = (defaultSettings.preloadAmount - 1).coerceIn(4, 20)
            defaultSettings.preloadAmount = value
            binding.readerSettingsPreloadAmount.setText(value.toString())
            PrefManager.setVal(PrefName.PreloadAmount, value)
        }

        // Autoscroll speed (clamp stored preference to slider bounds)
        run {
            val pref = PrefManager.getVal<Float>(PrefName.AutoScrollSpeed)
            val from = binding.readerSettingsAutoscrollSpeed.valueFrom
            val to = binding.readerSettingsAutoscrollSpeed.valueTo
            val clamped = when {
                pref < from -> from
                pref > to -> to
                else -> pref
            }
            binding.readerSettingsAutoscrollSpeed.value = clamped
        }
        binding.readerSettingsAutoscrollEnabled.isChecked = PrefManager.getVal(PrefName.AutoScrollEnabled)
        binding.readerSettingsAutoscrollEnabled.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.AutoScrollEnabled, isChecked)
        }
        binding.readerSettingsAutoscrollSpeed.addOnChangeListener { _, value, _ ->
            PrefManager.setVal(PrefName.AutoScrollSpeed, value)
        }

        binding.readerSettingsContinuousMultiChapter.isChecked = PrefManager.getVal(PrefName.ContinuousMultiChapter)
        binding.readerSettingsContinuousMultiChapter.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.ContinuousMultiChapter, isChecked)
        }

        //LN settings
        // The same picker the reader's own sheet shows, minus whatever theme the open book adds —
        // there is no book here. Without it, a default set in the reader had no counterpart in
        // settings, which is the one control the two screens did not share.
        val themeNames = NovelReaderActivity.THEME_NAMES
        binding.LNthemeSelect.adapter =
            NoPaddingArrayAdapter(this, R.layout.item_dropdown, themeNames)
        binding.LNthemeSelect.setSelection(
            themeNames.indexOfFirst { it.equals(defaultSettingsLN.currentThemeName, true) }
                .coerceAtLeast(0),
            false
        )
        binding.LNthemeSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                // Fires once for the selection set above; re-applying then would overwrite a saved
                // theme with whatever the list happened to start on.
                if (themeNames[position] == defaultSettingsLN.currentThemeName) return
                defaultSettingsLN.currentThemeName = themeNames[position]
                PrefManager.setVal(PrefName.CurrentThemeName, themeNames[position])
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val layoutListLN = listOf(
            binding.LNpaged,
            binding.LNcontinuous
        )

        binding.LNlayoutText.text = defaultSettingsLN.layout.string
        var selectedLN = layoutListLN[defaultSettingsLN.layout.ordinal]
        selectedLN.alpha = 1f

        layoutListLN.forEachIndexed { index, imageButton ->
            imageButton.setOnClickListener {
                selectedLN.alpha = 0.33f
                selectedLN = imageButton
                selectedLN.alpha = 1f
                defaultSettingsLN.layout = CurrentNovelReaderSettings.Layouts[index]
                    ?: CurrentNovelReaderSettings.Layouts.PAGED
                binding.LNlayoutText.text = defaultSettingsLN.layout.string
                PrefManager.setVal(PrefName.LayoutNovel, defaultSettingsLN.layout.ordinal)
            }
        }

        val dualListLN = listOf(
            binding.LNdualNo,
            binding.LNdualAuto,
            binding.LNdualForce
        )

        binding.LNdualPageText.text = defaultSettingsLN.dualPageMode.toString()
        var selectedDualLN = dualListLN[defaultSettingsLN.dualPageMode.ordinal]
        selectedDualLN.alpha = 1f

        dualListLN.forEachIndexed { index, imageButton ->
            imageButton.setOnClickListener {
                selectedDualLN.alpha = 0.33f
                selectedDualLN = imageButton
                selectedDualLN.alpha = 1f
                defaultSettingsLN.dualPageMode = CurrentReaderSettings.DualPageModes[index]
                    ?: CurrentReaderSettings.DualPageModes.Automatic
                binding.LNdualPageText.text = defaultSettingsLN.dualPageMode.toString()
                PrefManager.setVal(
                    PrefName.DualPageModeNovel,
                    defaultSettingsLN.dualPageMode.ordinal
                )
            }
        }

        binding.LNlineHeight.setText(defaultSettingsLN.lineHeight.toString())
        binding.LNlineHeight.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = binding.LNlineHeight.text.toString().toFloatOrNull() ?: 1.4f
                defaultSettingsLN.lineHeight = value
                binding.LNlineHeight.setText(value.toString())
                PrefManager.setVal(PrefName.LineHeight, value)
            }
        }

        binding.LNincrementLineHeight.setOnClickListener {
            val value = binding.LNlineHeight.text.toString().toFloatOrNull() ?: 1.4f
            defaultSettingsLN.lineHeight = value + 0.1f
            binding.LNlineHeight.setText(defaultSettingsLN.lineHeight.toString())
            PrefManager.setVal(PrefName.LineHeight, defaultSettingsLN.lineHeight)
        }

        binding.LNdecrementLineHeight.setOnClickListener {
            val value = binding.LNlineHeight.text.toString().toFloatOrNull() ?: 1.4f
            defaultSettingsLN.lineHeight = value - 0.1f
            binding.LNlineHeight.setText(defaultSettingsLN.lineHeight.toString())
            PrefManager.setVal(PrefName.LineHeight, defaultSettingsLN.lineHeight)
        }

        binding.LNmargin.setText(defaultSettingsLN.margin.toString())
        binding.LNmargin.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = binding.LNmargin.text.toString().toFloatOrNull() ?: 0.06f
                defaultSettingsLN.margin = value
                binding.LNmargin.setText(value.toString())
                PrefManager.setVal(PrefName.Margin, value)
            }
        }

        binding.LNincrementMargin.setOnClickListener {
            val value = binding.LNmargin.text.toString().toFloatOrNull() ?: 0.06f
            defaultSettingsLN.margin = value + 0.01f
            binding.LNmargin.setText(defaultSettingsLN.margin.toString())
            PrefManager.setVal(PrefName.Margin, defaultSettingsLN.margin)
        }

        binding.LNdecrementMargin.setOnClickListener {
            val value = binding.LNmargin.text.toString().toFloatOrNull() ?: 0.06f
            defaultSettingsLN.margin = value - 0.01f
            binding.LNmargin.setText(defaultSettingsLN.margin.toString())
            PrefManager.setVal(PrefName.Margin, defaultSettingsLN.margin)
        }

        binding.LNmaxInlineSize.setText(defaultSettingsLN.maxInlineSize.toString())
        binding.LNmaxInlineSize.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = binding.LNmaxInlineSize.text.toString().toIntOrNull() ?: 720
                defaultSettingsLN.maxInlineSize = value
                binding.LNmaxInlineSize.setText(value.toString())
                PrefManager.setVal(PrefName.MaxInlineSize, value)
            }
        }

        binding.LNincrementMaxInlineSize.setOnClickListener {
            val value = binding.LNmaxInlineSize.text.toString().toIntOrNull() ?: 720
            defaultSettingsLN.maxInlineSize = value + 10
            binding.LNmaxInlineSize.setText(defaultSettingsLN.maxInlineSize.toString())
            PrefManager.setVal(PrefName.MaxInlineSize, defaultSettingsLN.maxInlineSize)
        }

        binding.LNdecrementMaxInlineSize.setOnClickListener {
            val value = binding.LNmaxInlineSize.text.toString().toIntOrNull() ?: 720
            defaultSettingsLN.maxInlineSize = value - 10
            binding.LNmaxInlineSize.setText(defaultSettingsLN.maxInlineSize.toString())
            PrefManager.setVal(PrefName.MaxInlineSize, defaultSettingsLN.maxInlineSize)
        }

        binding.LNmaxBlockSize.setText(defaultSettingsLN.maxBlockSize.toString())
        binding.LNmaxBlockSize.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = binding.LNmaxBlockSize.text.toString().toIntOrNull() ?: 720
                defaultSettingsLN.maxBlockSize = value
                binding.LNmaxBlockSize.setText(value.toString())
                PrefManager.setVal(PrefName.MaxBlockSize, value)
            }
        }
        binding.LNincrementMaxBlockSize.setOnClickListener {
            val value = binding.LNmaxBlockSize.text.toString().toIntOrNull() ?: 720
            defaultSettingsLN.maxBlockSize = value + 10
            binding.LNmaxBlockSize.setText(defaultSettingsLN.maxBlockSize.toString())
            PrefManager.setVal(PrefName.MaxBlockSize, defaultSettingsLN.maxBlockSize)
        }

        binding.LNdecrementMaxBlockSize.setOnClickListener {
            val value = binding.LNmaxBlockSize.text.toString().toIntOrNull() ?: 720
            defaultSettingsLN.maxBlockSize = value - 10
            binding.LNmaxBlockSize.setText(defaultSettingsLN.maxBlockSize.toString())
            PrefManager.setVal(PrefName.MaxBlockSize, defaultSettingsLN.maxBlockSize)
        }

        binding.LNjustify.isChecked = defaultSettingsLN.justify
        binding.LNjustify.setOnCheckedChangeListener { _, isChecked ->
            defaultSettingsLN.justify = isChecked
            PrefManager.setVal(PrefName.Justify, isChecked)
        }

        binding.LNhyphenation.isChecked = defaultSettingsLN.hyphenation
        binding.LNhyphenation.setOnCheckedChangeListener { _, isChecked ->
            defaultSettingsLN.hyphenation = isChecked
            PrefManager.setVal(PrefName.Hyphenation, isChecked)
        }

        binding.LNuseDarkTheme.isChecked = defaultSettingsLN.useDarkTheme
        binding.LNuseDarkTheme.setOnCheckedChangeListener { _, isChecked ->
            defaultSettingsLN.useDarkTheme = isChecked
            PrefManager.setVal(PrefName.UseDarkThemeNovel, isChecked)
        }

        binding.LNuseOledTheme.isChecked = defaultSettingsLN.useOledTheme
        binding.LNuseOledTheme.setOnCheckedChangeListener { _, isChecked ->
            defaultSettingsLN.useOledTheme = isChecked
            PrefManager.setVal(PrefName.UseOledThemeNovel, isChecked)
        }

        binding.LNlockRotation.isChecked = defaultSettingsLN.lockRotation
        binding.LNlockRotation.setOnCheckedChangeListener { _, isChecked ->
            defaultSettingsLN.lockRotation = isChecked
            PrefManager.setVal(PrefName.LockRotationNovel, isChecked)
        }

        binding.LNhidePageNumbers.isChecked = defaultSettingsLN.hidePageNumbers
        binding.LNhidePageNumbers.setOnCheckedChangeListener { _, isChecked ->
            defaultSettingsLN.hidePageNumbers = isChecked
            PrefManager.setVal(PrefName.HidePageNumbersNovel, isChecked)
        }

        binding.LNkeepScreenOn.isChecked = defaultSettingsLN.keepScreenOn
        binding.LNkeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            defaultSettingsLN.keepScreenOn = isChecked
            PrefManager.setVal(PrefName.KeepScreenOnNovel, isChecked)
        }

        binding.LNvolumeButton.isChecked = defaultSettingsLN.volumeButtons
        binding.LNvolumeButton.setOnCheckedChangeListener { _, isChecked ->
            defaultSettingsLN.volumeButtons = isChecked
            PrefManager.setVal(PrefName.VolumeButtonsNovel, isChecked)
        }

        // OCR & MTL; delete with ani.dantotsu.spike
        binding.readerSettingsOcrCalibration.setOnClickListener {
            startActivity(Intent(this, MangaOcrSpikeActivity::class.java))
        }
        binding.readerSettingsMtlEnabled.isChecked = PrefManager.getVal(PrefName.OcrTranslateEnabled)
        binding.readerSettingsMtlEnabled.setOnCheckedChangeListener { _, on ->
            PrefManager.setVal(PrefName.OcrTranslateEnabled, on)
        }
        bindMtlChoices()
        bindOcrKey(
            TranslationEngine.GEMINI,
            binding.readerSettingsGeminiKey,
            binding.readerSettingsGeminiKeyState,
        )
        bindOcrKey(
            TranslationEngine.OPENROUTER,
            binding.readerSettingsOpenRouterKey,
            binding.readerSettingsOpenRouterKeyState,
        )

        binding.LNtextToSpeech.setOnClickListener {
            NovelTtsSettingsBottomSheet.newInstance()
                .show(supportFragmentManager, NovelTtsSettingsBottomSheet.TAG)
        }

        //Update Progress
        binding.readerSettingsAskUpdateProgress.isChecked =
            PrefManager.getVal(PrefName.AskIndividualReader)
        binding.readerSettingsAskUpdateProgress.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.AskIndividualReader, isChecked)
            binding.readerSettingsAskChapterZero.isEnabled = !isChecked
        }
        binding.readerSettingsAskChapterZero.isChecked =
            PrefManager.getVal(PrefName.ChapterZeroReader)
        binding.readerSettingsAskChapterZero.isEnabled =
            !PrefManager.getVal<Boolean>(PrefName.AskIndividualReader)
        binding.readerSettingsAskChapterZero.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.ChapterZeroReader, isChecked)
        }
        binding.readerSettingsAskUpdateHentai.isChecked =
            PrefManager.getVal(PrefName.UpdateForHReader)
        binding.readerSettingsAskUpdateHentai.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.UpdateForHReader, isChecked)
            if (isChecked) snackString(getString(R.string.very_bold))
        }
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

    /**
     * Engine, model, target language and source script.
     *
     * These live here rather than in the calibration screen because they are what the *reader*
     * translates with; that screen tunes detection thresholds, and a control it owned would be
     * configuring one page's experiment rather than the feature.
     */
    private fun bindMtlChoices() {
        fun refresh() {
            binding.readerSettingsMtlEngineState.text = MtlChoices.engineLabel()
            binding.readerSettingsMtlModelState.text = MtlChoices.modelLabel(this)
            binding.readerSettingsMtlTargetState.text = MtlChoices.targetLabel()
            binding.readerSettingsMtlScriptState.text = MtlChoices.scriptLabel(this)
            binding.readerSettingsMtlModel.isVisible = TranslationEngine.fromPref().needsKey
        }
        refresh()

        binding.readerSettingsMtlEngine.setOnClickListener {
            MtlChoices.pickEngine(this) { refresh() }
        }
        binding.readerSettingsMtlModel.setOnClickListener {
            MtlChoices.pickModel(this, lifecycleScope) { refresh() }
        }
        binding.readerSettingsMtlTarget.setOnClickListener {
            MtlChoices.pickTarget(this) { refresh() }
        }
        binding.readerSettingsMtlScript.setOnClickListener {
            MtlChoices.pickScript(this) { refresh() }
        }
    }


    /**
     * Wires one provider's key row.
     *
     * A key is write-once: entered, checked against the provider, and from then on only removable.
     * There is no editing step because there is nothing to edit — a key is opaque, so "changing"
     * one means pasting a different one, which is a remove and an add. Offering a pre-filled box
     * instead would put the existing secret back on screen for no gain, and invite a half-edited
     * key being saved without ever being checked.
     */
    private fun bindOcrKey(engine: TranslationEngine, row: View, state: TextView) {
        fun refresh() {
            val stored = engine.storedKey()
            state.setText(if (stored.isBlank()) R.string.ocr_key_not_set else R.string.ocr_key_set)
        }
        refresh()

        row.setOnClickListener {
            if (engine.storedKey().isNotBlank()) {
                customAlertDialog().apply {
                    setTitle(R.string.ocr_key_remove_title)
                    setMessage(getString(R.string.ocr_key_remove_message, engine.label))
                    setPosButton(R.string.remove) {
                        engine.keyPref?.let { PrefManager.removeVal(it) }
                        refresh()
                        snackString(getString(R.string.ocr_key_removed))
                    }
                    setNegButton(R.string.cancel)
                    show()
                }
            } else {
                promptForOcrKey(engine, ::refresh)
            }
        }
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
