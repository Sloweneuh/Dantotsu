package ani.dantotsu.media.novel.novelreader

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.NoPaddingArrayAdapter
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetNovelTtsSettingsBinding
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import java.util.Locale

/**
 * Everything about how a novel is read aloud.
 *
 * One sheet, opened from two places: the playback bar in the reader and the novel section of the
 * reader settings screen. The settings are the same either way, so sharing the sheet is what keeps
 * them the same — the two screens have drifted apart before when each grew its own copy of a
 * control.
 *
 * Voices and engines are read from the device rather than stored as a list, because what is
 * installed changes: an engine can be added or removed between two openings of this sheet, and a
 * saved choice that no longer exists falls back to the device default rather than failing.
 */
class NovelTtsSettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetNovelTtsSettingsBinding? = null
    private val binding get() = _binding!!

    /**
     * A speech engine of this sheet's own, purely to ask what voices exist.
     *
     * Not [NovelTts]'s engine: this sheet opens from the settings screen too, where nothing is being
     * read and there is no engine to borrow. Shut down with the sheet, so listing voices never
     * leaves one running.
     */
    private var probe: TextToSpeech? = null
    private var voices: List<Voice> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetNovelTtsSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindSpeed()
        bindPitch()
        bindSwitches()
        bindEngines()
        bindSleepTimer()
        startProbe()
    }

    private fun bindSpeed() {
        val current = PrefManager.getVal<Float>(PrefName.NovelTtsSpeed).coerceIn(0.25f, 3f)
        binding.ttsSpeed.value = current
        showSpeed(current)
        binding.ttsSpeed.addOnChangeListener { _, value, fromUser ->
            showSpeed(value)
            if (!fromUser) return@addOnChangeListener
            PrefManager.setVal(PrefName.NovelTtsSpeed, value)
            NovelTts.applySettings()
        }
    }

    private fun showSpeed(value: Float) {
        binding.ttsSpeedLabel.text =
            getString(R.string.novel_tts_speed_value, String.format(Locale.US, "%.2f", value))
    }

    private fun bindPitch() {
        val current = PrefManager.getVal<Float>(PrefName.NovelTtsPitch).coerceIn(0.5f, 2f)
        binding.ttsPitch.value = current
        showPitch(current)
        binding.ttsPitch.addOnChangeListener { _, value, fromUser ->
            showPitch(value)
            if (!fromUser) return@addOnChangeListener
            PrefManager.setVal(PrefName.NovelTtsPitch, value)
            NovelTts.applySettings()
        }
    }

    private fun showPitch(value: Float) {
        binding.ttsPitchLabel.text =
            getString(R.string.novel_tts_pitch_value, String.format(Locale.US, "%.2f", value))
    }

    private fun bindSwitches() {
        binding.ttsAutoNextChapter.isChecked =
            PrefManager.getVal(PrefName.NovelTtsAutoNextChapter)
        binding.ttsAutoNextChapter.setOnCheckedChangeListener { _, checked ->
            PrefManager.setVal(PrefName.NovelTtsAutoNextChapter, checked)
        }

        binding.ttsFollowText.isChecked = PrefManager.getVal(PrefName.NovelTtsFollowText)
        binding.ttsFollowText.setOnCheckedChangeListener { _, checked ->
            PrefManager.setVal(PrefName.NovelTtsFollowText, checked)
        }
    }

    /**
     * The engines installed on the device, plus an entry for not choosing.
     *
     * "System default" is first and is what a blank preference means, so someone who installs a
     * better engine and makes it their system one gets it here without touching this sheet.
     */
    private fun bindEngines() {
        val context = context ?: return
        val engines = probeEngines()
        val labels = listOf(getString(R.string.novel_tts_system_default)) + engines.map { it.second }
        val saved = PrefManager.getVal<String>(PrefName.NovelTtsEngine)
        val selected = engines.indexOfFirst { it.first == saved }.let { if (it < 0) 0 else it + 1 }

        binding.ttsEngine.adapter = NoPaddingArrayAdapter(context, R.layout.item_dropdown, labels)
        binding.ttsEngine.setSelection(selected, false)
        binding.ttsEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val chosen = if (position == 0) "" else engines.getOrNull(position - 1)?.first.orEmpty()
                if (chosen == PrefManager.getVal<String>(PrefName.NovelTtsEngine)) return
                PrefManager.setVal(PrefName.NovelTtsEngine, chosen)
                // The voice belongs to the engine that offered it, so it cannot survive the switch.
                PrefManager.setVal(PrefName.NovelTtsVoice, "")
                NovelTts.restartEngine()
                startProbe()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /**
     * Which engines are installed.
     *
     * Asked of a throwaway instance because [TextToSpeech.getEngines] is an instance method that
     * needs no initialisation — it reads the package manager. The instance is shut down straight
     * away rather than left waiting on a callback it does not need.
     */
    private fun probeEngines(): List<Pair<String, String>> = runCatching {
        val temporary = TextToSpeech(requireContext().applicationContext, null)
        val engines = temporary.engines.map { it.name to it.label }
        temporary.shutdown()
        engines
    }.getOrElse { emptyList() }

    /**
     * Lists the voices the chosen engine offers, once it is up.
     *
     * Only voices for the language being read, when any match. A device can carry several hundred
     * across every language it supports, and a list that long is not a choice — it is a search
     * problem. When none match, the whole list is shown rather than an empty one.
     */
    private fun startProbe() {
        probe?.shutdown()
        val context = context?.applicationContext ?: return
        val preferred = PrefManager.getVal<String>(PrefName.NovelTtsEngine).takeIf { it.isNotBlank() }
        lateinit var engine: TextToSpeech
        val onInit = TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.SUCCESS) return@OnInitListener
            val all = runCatching { engine.voices?.toList() }.getOrNull().orEmpty()
            view?.post { if (_binding != null) bindVoices(all) }
        }
        engine = if (preferred != null) TextToSpeech(context, onInit, preferred)
        else TextToSpeech(context, onInit)
        probe = engine
    }

    private fun bindVoices(all: List<Voice>) {
        val context = context ?: return
        val language = NovelTts.currentLanguage()
        val matching = all.filter { it.locale?.language == language.language }
        voices = (matching.ifEmpty { all })
            .filterNot { it.isNetworkConnectionRequired && it.features?.contains("notInstalled") == true }
            .sortedBy { it.name }

        val labels = listOf(getString(R.string.novel_tts_system_default)) + voices.map { label(it) }
        val saved = PrefManager.getVal<String>(PrefName.NovelTtsVoice)
        val selected = voices.indexOfFirst { it.name == saved }.let { if (it < 0) 0 else it + 1 }

        binding.ttsVoice.adapter = NoPaddingArrayAdapter(context, R.layout.item_dropdown, labels)
        binding.ttsVoice.setSelection(selected, false)
        binding.ttsVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val chosen = if (position == 0) "" else voices.getOrNull(position - 1)?.name.orEmpty()
                if (chosen == PrefManager.getVal<String>(PrefName.NovelTtsVoice)) return
                PrefManager.setVal(PrefName.NovelTtsVoice, chosen)
                NovelTts.applySettings()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /**
     * A voice's name is an identifier, not a label — "en-gb-x-gbb-local" and the like — so it is
     * shown with its locale and quality in front to make the list navigable.
     */
    private fun label(voice: Voice): String {
        val locale = voice.locale?.displayName.orEmpty()
        val quality = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> getString(R.string.novel_tts_quality_high)
            voice.quality <= Voice.QUALITY_LOW -> getString(R.string.novel_tts_quality_low)
            else -> ""
        }
        val network = if (voice.isNetworkConnectionRequired) getString(R.string.novel_tts_online) else ""
        val tags = listOf(quality, network).filter { it.isNotBlank() }
        val suffix = if (tags.isEmpty()) "" else " (${tags.joinToString(", ")})"
        return if (locale.isBlank()) "${voice.name}$suffix" else "$locale — ${voice.name}$suffix"
    }

    /**
     * Only offered while something is being read.
     *
     * A timer is a thing you set for the session you are in, not a preference — setting one from
     * the settings screen, hours before opening a book, would mean nothing.
     */
    private fun bindSleepTimer() {
        val context = context ?: return
        if (!NovelTts.isActive) {
            binding.ttsSleepTimerRow.visibility = View.GONE
            return
        }
        val minutes = listOf(0, 5, 15, 30, 45, 60, 90)
        val labels = minutes.map {
            if (it == 0) getString(R.string.novel_tts_sleep_off)
            else getString(R.string.novel_tts_sleep_minutes, it)
        }
        val running = NovelTts.sleepMinutesLeft()
        val selected = minutes.indexOfFirst { it >= running }.coerceAtLeast(0)

        binding.ttsSleepTimer.adapter = NoPaddingArrayAdapter(context, R.layout.item_dropdown, labels)
        binding.ttsSleepTimer.setSelection(selected, false)
        binding.ttsSleepTimer.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var first = true
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                // A Spinner reports the selection set above, which would restart the timer that is
                // already running every time this sheet is opened.
                if (first) {
                    first = false
                    return
                }
                NovelTts.setSleepTimer(minutes[position])
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    override fun onDestroyView() {
        probe?.shutdown()
        probe = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "NovelTtsSettingsBottomSheet"
        fun newInstance() = NovelTtsSettingsBottomSheet()
    }
}
