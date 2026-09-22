package ani.dantotsu.media.manga.mangareader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetReaderMtlSettingsBinding
import ani.dantotsu.media.manga.translation.MtlChoices
import ani.dantotsu.media.manga.translation.MtlSettings

/**
 * Engine, model, script and target, changeable without leaving the page.
 *
 * Its own sheet rather than a section of [ReaderSettingsDialogFragment] — that sheet is already
 * long with the general display settings, and these four rows plus two switches were pushing it
 * further still. Opened from an entry row there instead.
 *
 * Here as well as in settings because these are things you change *against* a page — a model
 * that reads one series badly may be fine on the next, and a script guessed from metadata is
 * wrong exactly when you are looking at the evidence. Sending the reader to a settings screen to
 * find that out is the difference between adjusting and giving up.
 *
 * Changing one re-labels the rows and nothing else. Pages already translated keep the words they
 * have — quietly redoing them would spend quota nobody asked to spend, and the page's long-press
 * already offers to translate again when that is what is wanted.
 */
class ReaderMtlSettingsDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetReaderMtlSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetReaderMtlSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MangaReaderActivity

        // What automatic resolves to for the media open right now, so the row can say "Japanese
        // (auto)" rather than only that it decided for itself.
        val detected = activity.detectedScript()

        // This manga's own choices: a change here is saved with its layout and direction and
        // touches no other series. The preferences are only where a manga's first copy came from.
        fun current() = activity.mtlSettings()
        fun refresh() {
            val settings = current()
            binding.readerMtlEngineState.text = MtlChoices.engineLabel(settings)
            binding.readerMtlModelState.text = MtlChoices.modelLabel(activity, settings)
            binding.readerMtlScriptState.text = MtlChoices.scriptLabel(activity, settings, detected)
            binding.readerMtlTargetState.text = MtlChoices.targetLabel(settings)
            // Only the key-based engines have a model to choose. Hiding the row takes its value
            // line with it — they are one view now, so the state no longer needs hiding of its own.
            binding.readerMtlModel.isVisible = settings.engine.needsKey
        }
        refresh()

        // Whatever went wrong under the old settings is worth trying again under the new ones, so
        // a change re-opens the pages automatic translation had given up on. Only on a *change*:
        // hung off refresh() this would also fire on merely opening the sheet.
        fun changed(settings: MtlSettings) {
            activity.updateMtlSettings(settings)
            refresh()
        }

        binding.readerMtlEngine.setOnClickListener {
            MtlChoices.pickEngine(activity, current(), ::changed)
        }
        binding.readerMtlModel.setOnClickListener {
            MtlChoices.pickModel(activity, activity.lifecycleScope, current(), ::changed)
        }
        binding.readerMtlScript.setOnClickListener {
            MtlChoices.pickScript(activity, current(), detected, ::changed)
        }
        binding.readerMtlTarget.setOnClickListener {
            MtlChoices.pickTarget(activity, current(), ::changed)
        }

        binding.readerMtlAuto.isChecked = current().auto
        binding.readerMtlAuto.setOnCheckedChangeListener { _, isChecked ->
            changed(current().copy(auto = isChecked))
            // Said out loud because switching it on spends an API quota with nobody pressing
            // anything afterwards, and switching it off leaves pages looking untranslated.
            Toast.makeText(
                requireContext(),
                if (isChecked) R.string.mtl_auto_on else R.string.mtl_auto_off,
                Toast.LENGTH_SHORT,
            ).show()
        }

        binding.readerMtlStitch.isChecked = current().stitch
        binding.readerMtlStitch.setOnCheckedChangeListener { _, isChecked ->
            changed(current().copy(stitch = isChecked))
        }

        // Also in the main reader settings sheet — not part of MtlSettings (it's a plain display
        // setting, saved and applied the same way as ReaderSettingsDialogFragment's own copy of
        // this switch), just surfaced here too since it's what turns on the long-press menu this
        // feature's manual "translate this page" lives in.
        binding.readerMtlLongClickImage.isChecked = activity.defaultSettings.longClickImage
        binding.readerMtlLongClickImage.setOnCheckedChangeListener { _, isChecked ->
            activity.defaultSettings.longClickImage = isChecked
            activity.applySettings()
        }
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }

    companion object {
        fun newInstance() = ReaderMtlSettingsDialogFragment()
    }
}
