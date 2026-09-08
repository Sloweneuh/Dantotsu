package ani.dantotsu.media.manga.mangareader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.media.manga.translation.MtlChoices
import ani.dantotsu.media.manga.translation.PageTranslationPipeline
import ani.dantotsu.media.manga.translation.TranslationEngine
import ani.dantotsu.databinding.BottomSheetCurrentReaderSettingsBinding
import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.CurrentReaderSettings.Directions
import android.widget.Toast
import com.google.android.material.slider.Slider

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
        val isMultiChapter = PrefManager.getVal<Boolean>(PrefName.ContinuousMultiChapter)

        bindMtlRows(activity)

        // Hide irrelevant settings in multi-chapter mode
        if (isMultiChapter) {
            binding.readerDualPageContainer.visibility = View.GONE
            binding.readerDualPageInfo.visibility = View.GONE
            binding.readerOverscroll.visibility = View.GONE
        }

        binding.readerDirectionText.text =
            resources.getStringArray(R.array.manga_directions)[settings.direction.ordinal]
        binding.readerDirection.rotation = 90f * (settings.direction.ordinal)
        binding.readerDirection.setOnClickListener {
            settings.direction =
                Directions[settings.direction.ordinal + 1] ?: Directions.TOP_TO_BOTTOM
            binding.readerDirectionText.text =
                resources.getStringArray(R.array.manga_directions)[settings.direction.ordinal]
            binding.readerDirection.rotation = 90f * (settings.direction.ordinal)
            activity.applySettings()
        }

        val list = listOf(
            binding.readerPaged,
            binding.readerContinuousPaged,
            binding.readerContinuous
        )

        binding.readerPadding.isEnabled = settings.layout.ordinal != 0
        fun paddingAvailable(enable: Boolean) {
            binding.readerPadding.isEnabled = enable
        }

        binding.readerPadding.isChecked = settings.padding
        binding.readerPadding.setOnCheckedChangeListener { _, isChecked ->
            settings.padding = isChecked
            activity.applySettings()
        }

        binding.readerCropBorders.isChecked = settings.cropBorders
        binding.readerCropBorders.setOnCheckedChangeListener { _, isChecked ->
            settings.cropBorders = isChecked
            activity.applySettings()
        }

        binding.readerLayoutText.text =
            resources.getStringArray(R.array.manga_layouts)[settings.layout.ordinal]
        var selected = list[settings.layout.ordinal]
        selected.alpha = 1f

        list.forEachIndexed { index, imageButton ->
            imageButton.setOnClickListener {
                selected.alpha = 0.33f
                selected = imageButton
                selected.alpha = 1f
                settings.layout =
                    CurrentReaderSettings.Layouts[index] ?: CurrentReaderSettings.Layouts.CONTINUOUS
                binding.readerLayoutText.text =
                    resources.getStringArray(R.array.manga_layouts)[settings.layout.ordinal]
                activity.applySettings()
                paddingAvailable(settings.layout.ordinal != 0)
                // only allow autoscroll when using continuous layout
                val autoscrollAllowed = settings.layout == CurrentReaderSettings.Layouts.CONTINUOUS
                binding.readerAutoscrollEnabled.isEnabled = autoscrollAllowed
                if (!autoscrollAllowed) {
                    // disable autoscroll preference and stop any active autoscroll
                    PrefManager.setVal(PrefName.AutoScrollEnabled, false)
                    binding.readerAutoscrollEnabled.isChecked = false
                    activity.stopAutoscroll()
                }
            }
        }

        val dualList = listOf(
            binding.readerDualNo,
            binding.readerDualAuto,
            binding.readerDualForce
        )

        binding.readerDualPageText.text = settings.dualPageMode.toString()
        var selectedDual = dualList[settings.dualPageMode.ordinal]
        selectedDual.alpha = 1f

        dualList.forEachIndexed { index, imageButton ->
            imageButton.setOnClickListener {
                selectedDual.alpha = 0.33f
                selectedDual = imageButton
                selectedDual.alpha = 1f
                settings.dualPageMode = CurrentReaderSettings.DualPageModes[index]
                    ?: CurrentReaderSettings.DualPageModes.Automatic
                binding.readerDualPageText.text = settings.dualPageMode.toString()
                activity.applySettings()
            }
        }
        binding.readerTrueColors.isChecked = settings.trueColors
        binding.readerTrueColors.setOnCheckedChangeListener { _, isChecked ->
            settings.trueColors = isChecked
            activity.applySettings()
        }

        binding.readerImageRotation.isChecked = settings.rotation
        binding.readerImageRotation.setOnCheckedChangeListener { _, isChecked ->
            settings.rotation = isChecked
            activity.applySettings()
        }

        binding.readerHorizontalScrollBar.isChecked = settings.horizontalScrollBar
        binding.readerHorizontalScrollBar.setOnCheckedChangeListener { _, isChecked ->
            settings.horizontalScrollBar = isChecked
            activity.applySettings()
        }

        binding.readerKeepScreenOn.isChecked = settings.keepScreenOn
        binding.readerKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            settings.keepScreenOn = isChecked
            activity.applySettings()
        }

        binding.readerLockRotation.isChecked = settings.lockRotation
        binding.readerLockRotation.setOnCheckedChangeListener { _, isChecked ->
            settings.lockRotation = isChecked
            activity.applySettings()
        }

        binding.readerHideScrollBar.isChecked = settings.hideScrollBar
        binding.readerHideScrollBar.setOnCheckedChangeListener { _, isChecked ->
            settings.hideScrollBar = isChecked
            activity.applySettings()
        }

        binding.readerHidePageNumbers.isChecked = settings.hidePageNumbers
        binding.readerHidePageNumbers.setOnCheckedChangeListener { _, isChecked ->
            settings.hidePageNumbers = isChecked
            activity.applySettings()
        }

        binding.readerOverscroll.isChecked = settings.overScrollMode
        binding.readerOverscroll.setOnCheckedChangeListener { _, isChecked ->
            settings.overScrollMode = isChecked
            activity.applySettings()
        }

        binding.readerVolumeButton.isChecked = settings.volumeButtons
        binding.readerVolumeButton.setOnCheckedChangeListener { _, isChecked ->
            settings.volumeButtons = isChecked
            activity.applySettings()
        }

        binding.readerWrapImage.isChecked = settings.wrapImages
        binding.readerWrapImage.setOnCheckedChangeListener { _, isChecked ->
            settings.wrapImages = isChecked
            activity.applySettings()
        }

        binding.readerLongClickImage.isChecked = settings.longClickImage
        binding.readerLongClickImage.setOnCheckedChangeListener { _, isChecked ->
            settings.longClickImage = isChecked
            activity.applySettings()
        }

        // Autoscroll enabled - only valid for Continuous layout
        binding.readerAutoscrollEnabled.isChecked = PrefManager.getVal(PrefName.AutoScrollEnabled)
        binding.readerAutoscrollEnabled.isEnabled = settings.layout == CurrentReaderSettings.Layouts.CONTINUOUS
        binding.readerAutoscrollEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (settings.layout == CurrentReaderSettings.Layouts.CONTINUOUS) {
                    PrefManager.setVal(PrefName.AutoScrollEnabled, true)
                    activity.startAutoscroll()
                } else {
                    // Prevent enabling autoscroll for non-continuous layouts
                    Toast.makeText(requireContext(), R.string.autoscroll_only_continuous, Toast.LENGTH_SHORT).show()
                    binding.readerAutoscrollEnabled.isChecked = false
                    PrefManager.setVal(PrefName.AutoScrollEnabled, false)
                }
            } else {
                PrefManager.setVal(PrefName.AutoScrollEnabled, false)
                activity.stopAutoscroll()
            }
        }

        // Autoscroll speed (clamp stored preference to slider bounds)
        run {
            val pref = PrefManager.getVal<Float>(PrefName.AutoScrollSpeed)
            val from = binding.readerAutoscrollSpeed.valueFrom
            val to = binding.readerAutoscrollSpeed.valueTo
            val clamped = when {
                pref < from -> from
                pref > to -> to
                else -> pref
            }
            binding.readerAutoscrollSpeed.value = clamped
        }
        binding.readerAutoscrollSpeed.addOnChangeListener(object : Slider.OnChangeListener {
            override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
                PrefManager.setVal(PrefName.AutoScrollSpeed, value)
                activity.updateAutoscrollSpeed(value)
            }
        })
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }

    /**
     * Engine, model, script and target, changeable without leaving the page.
     *
     * Here as well as in settings because these are things you change *against* a page — a model
     * that reads one series badly may be fine on the next, and a script guessed from metadata is
     * wrong exactly when you are looking at the evidence. Sending the reader to a settings screen
     * to find that out is the difference between adjusting and giving up.
     *
     * Hidden entirely when translation is off rather than shown disabled: to somebody not using the
     * feature these are four more rows in a sheet that is already long.
     *
     * Changing one re-labels the rows and nothing else. Pages already translated keep the words
     * they have — quietly redoing them would spend quota nobody asked to spend, and the page's
     * long-press already offers to translate again when that is what is wanted.
     */
    private fun bindMtlRows(activity: MangaReaderActivity) {
        if (!PageTranslationPipeline.enabled()) return
        binding.readerMtlGroup.isVisible = true

        // What automatic resolves to for the media open right now, so the row can say "Japanese
        // (auto)" rather than only that it decided for itself.
        val detected = activity.detectedScript()

        fun refresh() {
            binding.readerMtlEngineState.text = MtlChoices.engineLabel()
            binding.readerMtlModelState.text = MtlChoices.modelLabel(activity)
            binding.readerMtlScriptState.text = MtlChoices.scriptLabel(activity, detected)
            binding.readerMtlTargetState.text = MtlChoices.targetLabel()
            // Only the key-based engines have a model to choose. Hiding the row takes its value
            // line with it — they are one view now, so the state no longer needs hiding of its own.
            binding.readerMtlModel.isVisible = TranslationEngine.fromPref().needsKey
        }
        refresh()

        // Whatever went wrong under the old settings is worth trying again under the new ones, so
        // a change re-opens the pages automatic translation had given up on. Only on a *change*:
        // hung off refresh() this would also fire on merely opening the sheet.
        fun changed() {
            refresh()
            activity.onMtlSettingsChanged()
        }

        binding.readerMtlEngine.setOnClickListener { MtlChoices.pickEngine(activity) { changed() } }
        binding.readerMtlModel.setOnClickListener {
            MtlChoices.pickModel(activity, activity.lifecycleScope) { changed() }
        }
        binding.readerMtlScript.setOnClickListener {
            MtlChoices.pickScript(activity, detected) { changed() }
        }
        binding.readerMtlTarget.setOnClickListener { MtlChoices.pickTarget(activity) { changed() } }

        binding.readerMtlAuto.isChecked = PrefManager.getVal(PrefName.OcrAutoTranslate)
        binding.readerMtlAuto.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.OcrAutoTranslate, isChecked)
            // Said out loud because switching it on spends an API quota with nobody pressing
            // anything afterwards, and switching it off leaves pages looking untranslated.
            Toast.makeText(
                requireContext(),
                if (isChecked) R.string.mtl_auto_on else R.string.mtl_auto_off,
                Toast.LENGTH_SHORT,
            ).show()
            activity.onMtlSettingsChanged()
        }

        binding.readerMtlStitch.isChecked = PrefManager.getVal(PrefName.OcrStitchPages)
        binding.readerMtlStitch.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.OcrStitchPages, isChecked)
            activity.onMtlSettingsChanged()
        }
    }

    companion object {
        fun newInstance() = ReaderSettingsDialogFragment()
    }
}