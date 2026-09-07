package ani.dantotsu.spike

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityMangaOcrSpikeBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import com.google.android.material.slider.Slider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Calibrates the text-detection thresholds the manga MTL pipeline will read, and started life as
 * the spike that decided whether those thresholds could work at all: on a real raw page, do ML
 * Kit's own per-block signals separate dialogue from sound effects and art text well enough that no
 * bubble-segmentation model is needed? So far, yes.
 *
 * Pick a page, and every block ML Kit returns is measured on the signals that are free — glyph
 * height relative to the page, line confidence, katakana ratio, and the luminance of a ring sampled
 * just outside the block — then classified and drawn back over the page. Thresholds are sliders and
 * blocks are draggable, and neither re-runs the page: OCR happens once, and everything after it is
 * a re-score, so the numbers can be explored rather than guessed at.
 *
 * What the sliders set is persisted, in the `Ocr*` preferences under `Location.Reader`, so a
 * calibration survives the screen and can be built up across several pages in separate sittings.
 * [TUNABLES] is the one place a threshold's slider and preference are tied together, and loading,
 * saving and resetting all walk it. **Nothing reads these preferences yet** — the reader-side
 * detection they exist for is not written. Until it is, the value here is still the report.
 *
 * Findings so far, from a vertical Japanese page (822x1200, 24 blocks):
 *  - vertical text is *recognised*. Every block came back at an angle near 90 degrees, so the
 *    recognizer's documented weak spot is much less of a threat here than expected. Some text on
 *    the page was still missed entirely — draw a block over a missed bubble and [reOcrSelected]
 *    will say whether the region reads on its own, which separates a detection failure from a
 *    recognition one.
 *  - `symbols` are populated, so TachiyomiAT's `lines.first().elements.first().symbols.first()
 *    .boundingBox!!` does not blow up on Japanese. The line-box fallback stays anyway; the report
 *    tags each block `sym` or `box` so a script that behaves differently is visible.
 *  - lines within a vertical block arrive in the wrong order — see [orderedText].
 *  - confidence tracks OCR *quality*, not effect-ness, so it earns a verdict of its own rather than
 *    being folded into the SFX test. Across two pages the garbled blocks scored 0.18 and 0.23 while
 *    correct text ran from 0.37 to 0.87 — including perfectly good Japanese at 0.41 and 0.50, which
 *    is why the threshold defaults to 0.30 and not the 0.50 it started at. The band is narrow
 *    enough that in production this probably wants to flag a block rather than drop it.
 *  - the ring wants a *robust* spread. See [Ring].
 *
 * To remove it: delete this package, `activity_manga_ocr_spike.xml`, `styles_ocr_spike.xml`, the
 * `ocr_spike*`/`ocr_backup_*`/`backup_sub_ocr*` strings in both `values` and `values-fr`, the
 * activity and the `com.google.mlkit.vision.DEPENDENCIES` meta-data in the manifest, the
 * `readerSettingsOcrCalibration` row and its click handler in
 * [ani.dantotsu.settings.ReaderSettingsActivity], the `Ocr*` entries in
 * [ani.dantotsu.settings.saving.PrefName] and the `reader_ocr` sub-category in
 * [ani.dantotsu.settings.saving.BackupTree], and the ML Kit dependencies.
 */
class MangaOcrSpikeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMangaOcrSpikeBinding

    private var sourceBitmap: Bitmap? = null
    private var blocks: List<RawBlock> = emptyList()
    private var lastOcrMillis = 0L
    private var nextId = 1
    private var currentScript = Script.JAPANESE
    private var lastTranslateMillis = 0L

    /** The recognizer's runs for the current page, before merging. See [Candidate]. */
    private var candidates: List<Candidate> = emptyList()

    /** Last failure, kept on screen rather than flashed past in a toast. */
    private var lastError: String? = null

    /** Translation codes and their display names, in the order the picker shows them. */
    private var targetCodes: List<String> = emptyList()
    private var targetLabels: List<String> = emptyList()
    private val targetLanguage: String?
        get() = targetCodes.getOrNull(binding.targetLanguageSpinner.selectedItemPosition)

    /** The chosen language named in words, which is what the LLM engines put in their prompt. */
    private fun targetLabel(): String =
        targetLabels.getOrNull(binding.targetLanguageSpinner.selectedItemPosition) ?: "English"

    /** The model names currently in the picker, parallel to its entries. */
    private var modelNames: List<String> = emptyList()

    /** Key and model only matter to the engines that take one. */
    private fun showEngineFields() {
        val engine = TranslationEngine.entries[binding.engineSpinner.selectedItemPosition]
        binding.keyFields.isVisible = engine.needsKey
        if (engine.needsKey) {
            binding.keyStateText.text = if (engine.storedKey().isBlank()) {
                getString(R.string.ocr_spike_key_missing, engine.label)
            } else {
                getString(R.string.ocr_spike_key_present, engine.label)
            }
            val cached = modelCache[engine]
            if (cached != null) {
                setModels(cached, PrefManager.getVal(PrefName.OcrTranslationModel))
            } else {
                // The stored choice on its own until the catalogue arrives, so the picker shows
                // what would actually be used rather than sitting empty.
                val stored = PrefManager.getVal<String>(PrefName.OcrTranslationModel)
                    .ifBlank { engine.defaultModel() }
                setModels(listOf(stored), stored)
                // Fetched without being asked, because the alternative is discovering that the
                // stored model was retired only when a page fails to translate. OpenRouter's
                // catalogue is public; Gemini's needs the key, so it waits for one.
                if (engine != TranslationEngine.GEMINI || engine.storedKey().isNotBlank()) {
                    refreshModels(quiet = true)
                }
            }
        }
    }

    /** Catalogues already fetched this session, so switching engines does not re-request. */
    private val modelCache = mutableMapOf<TranslationEngine, List<String>>()

    private fun setModels(names: List<String>, selected: String) {
        modelNames = names
        binding.modelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            names,
        )
        names.indexOf(selected).takeIf { it >= 0 }?.let { binding.modelSpinner.setSelection(it) }
    }

    /**
     * Replaces the picker's contents with what the provider says it currently serves.
     *
     * @param quiet for the automatic fetch on selecting an engine, where a failure is not the
     *              answer to anything the user just asked and should not displace what is on
     *              screen. A fetch they pressed the button for reports either way.
     */
    private fun refreshModels(quiet: Boolean = false) {
        val engine = TranslationEngine.entries[binding.engineSpinner.selectedItemPosition]
        if (!engine.needsKey) return
        val key = engine.storedKey()
        if (engine == TranslationEngine.GEMINI && key.isBlank()) {
            if (!quiet) reportError(getString(R.string.ocr_spike_key_required, engine.label))
            return
        }

        binding.refreshModelsButton.isEnabled = false
        lifecycleScope.launch {
            runCatching { LlmTranslator.fetchModels(engine, key) }
                .onSuccess { models ->
                    if (models.isEmpty()) {
                        if (!quiet) reportError(getString(R.string.ocr_spike_no_models))
                    } else {
                        modelCache[engine] = models
                        // A stored model the provider no longer lists is the failure this whole
                        // path exists to prevent, so it is replaced rather than left to 404 at
                        // translate time.
                        val stored = PrefManager.getVal<String>(PrefName.OcrTranslationModel)
                        val chosen = stored.takeIf { it in models }
                            ?: LlmTranslator.preferredModel(models).orEmpty()
                        if (chosen != stored) {
                            PrefManager.setVal(PrefName.OcrTranslationModel, chosen)
                        }
                        setModels(models, chosen)
                        lastError = null
                        render()
                    }
                }
                .onFailure { if (!quiet) reportError(it.message ?: it.toString()) }
            binding.refreshModelsButton.isEnabled = true
        }
    }

    /**
     * Puts a failure where it can be read.
     *
     * A toast is gone in three seconds and these messages are the provider's own diagnosis — a
     * dead model name and a rejected key are both 4xx and need entirely different fixes, so the
     * text needs to stay on screen long enough to act on.
     */
    private fun reportError(message: String) {
        lastError = message
        Logger.log("OCR spike: $message")
        render()
    }

    /**
     * The colour a repainted block is filled with, from its ring's median luminance.
     *
     * Grey built from luminance, not the ring's actual colour, because [Ring] only ever measured
     * luminance — enough for the white and inverted-black bubbles that dominate, and visibly wrong
     * on a coloured or toned one. Sampling the median *colour* is the fix if that turns out to
     * matter on real pages.
     */
    private fun fillColor(median: Float): Int =
        median.roundToInt().coerceIn(0, 255).let { Color.rgb(it, it, it) }

    /** What the recognizer reported for one block, before any judgement is applied. */
    private data class RawBlock(
        val id: Int,
        val text: String,
        val reordered: Boolean,
        val box: Rect,
        val confidence: Float,
        val angle: Float,
        val glyphPx: Float,
        val symbolsAvailable: Boolean,
        val lineCount: Int,
        val katakanaRatio: Float,
        /** Drawn by hand rather than found by the full-page pass. */
        val synthetic: Boolean,
        /** Filled in by [translatePage]; empty until then. */
        val translation: String = "",
    ) {
        /** Glyph height as a percentage of page height — the page-size-independent form. */
        fun glyphPct(pageHeight: Int) = glyphPx / pageHeight * 100f
    }

    /**
     * What the ring around a block looks like.
     *
     * [median] and [iqr] are the ones the verdict uses; [mean] and [stdDev] are kept only so the
     * two can be compared in the report. A ring is mostly bubble interior with a minority of
     * contaminating ink — the bubble's own outline, or the edge of an outermost glyph — and a
     * standard deviation is at the mercy of that minority: rings measured at 208-244 mean on a page
     * of ordinary dialogue reported deviations of 43-74, high enough to read as artwork. A quartile
     * range ignores contamination until it exceeds a quarter of the samples, which is exactly the
     * robustness wanted here.
     *
     * [median] doubles as the fill colour a real implementation would paint the block with, which
     * is the right answer for a toned or inverted bubble as well as a white one.
     */
    private data class Ring(
        val median: Float,
        val iqr: Float,
        val mean: Float,
        val stdDev: Float,
    )

    /** A block plus everything that depends on the current thresholds. */
    private data class Scored(
        val block: RawBlock,
        val ring: Ring,
        val verdict: Verdict,
    )

    private data class Thresholds(
        val glyphPct: Float,
        val minConfidence: Float,
        val katakanaPct: Float,
        val minBrightness: Float,
        val ringIqr: Float,
        val ringPad: Float,
    )

    private enum class Verdict(val label: String, val color: Int) {
        /** In a bubble, ordinary glyph size, legible — box it and write the translation in. */
        DIALOGUE("DIALOGUE", 0xFF4CAF50.toInt()),

        /** In a bubble but reads as an effect — boxing is safe, translating may not be wanted. */
        SFX("SFX     ", 0xFFFF9800.toInt()),

        /** Recognised, but the recognizer does not believe it. Translating this yields nonsense. */
        LOW_CONF("LOW_CONF", 0xFF9C27B0.toInt()),

        /** Not inside anything uniform. Boxing this is what destroys artwork. */
        OVER_ART("OVER_ART", 0xFFF44336.toInt()),
    }

    private enum class Script(val label: String) {
        JAPANESE("Japanese"),
        KOREAN("Korean"),
        CHINESE("Chinese"),
        LATIN("Latin"),
    }

    private val pickPage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) runOcr(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityMangaOcrSpikeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        binding.ocrSpikeContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.scriptSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Script.entries.map { it.label },
        )

        val targets = MlKitTranslator.supportedTargets()
        targetCodes = targets.map { it.first }
        targetLabels = targets.map { it.second }
        binding.targetLanguageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            targetLabels,
        )
        targetCodes.indexOf(Locale.getDefault().language).takeIf { it >= 0 }?.let {
            binding.targetLanguageSpinner.setSelection(it)
        }

        binding.engineSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            TranslationEngine.entries.map { it.label },
        )
        binding.engineSpinner.setSelection(TranslationEngine.fromPref().ordinal)
        binding.engineSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                PrefManager.setVal(PrefName.OcrTranslationEngine, pos)
                showEngineFields()
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        binding.refreshModelsButton.setOnClickListener { refreshModels() }
        binding.modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                PrefManager.setVal(PrefName.OcrTranslationModel, modelNames.getOrElse(pos) { "" })
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        showEngineFields()

        binding.mergeBlocks.setOnCheckedChangeListener { _, _ ->
            if (candidates.isEmpty()) return@setOnCheckedChangeListener
            // Merging changes what a block *is*, so the translations keyed to the old ones no
            // longer describe anything. Dropping them is honest; keeping them would attach a
            // fragment's translation to a bubble.
            rebuildBlocks()
            lastTranslateMillis = 0L
            setPreviewMode(false)
        }

        binding.translateButton.setOnClickListener { translatePage() }
        binding.previewToggle.setOnClickListener {
            setPreviewMode(!binding.pageImage.previewMode)
        }
        binding.previewToggle.isEnabled = false

        binding.pickPageButton.setOnClickListener { pickPage.launch("image/*") }
        binding.copyReportButton.setOnClickListener {
            val current = thresholds()
            copyToClipboard(buildReport(score(current), current, full = true))
        }
        binding.reOcrButton.setOnClickListener { reOcrSelected() }
        binding.deleteBlockButton.setOnClickListener { deleteSelected() }

        binding.pageImage.onBoxChanged = { id, rect ->
            blocks = blocks.map { if (it.id == id) it.copy(box = rect) else it }
            render()
        }
        binding.addBlockButton.setOnClickListener { setAddMode(!binding.pageImage.addMode) }
        binding.pageImage.onBoxAdded = { rect ->
            // One box per arming: a drawing mode left on by accident goes back to eating every
            // scroll, which is the thing this mode exists to prevent.
            setAddMode(false)
            addBlock(rect)
        }
        binding.pageImage.onSelectionChanged = { render() }

        binding.resetThresholdsButton.setOnClickListener {
            // Removing the stored value is the reset: getVal then falls back to the Pref default.
            TUNABLES.forEach { PrefManager.removeVal(it.pref) }
            loadThresholds()
            render()
            snackString(getString(R.string.ocr_spike_reset_done))
        }

        loadThresholds()
        TUNABLES.forEach { tunable ->
            tunable.slider(binding).addOnChangeListener { _, value, fromUser ->
                // Only a real drag is a calibration decision. Without this the programmatic writes
                // in loadThresholds would immediately save themselves back.
                if (fromUser) PrefManager.setVal(tunable.pref, value)
                render()
            }
        }
        render()
    }

    /** Seeds every slider from what was last calibrated. */
    private fun loadThresholds() = TUNABLES.forEach { tunable ->
        val slider = tunable.slider(binding)
        // A stored value from an older build can sit outside the slider's range, and Slider throws
        // rather than clamping.
        slider.value = PrefManager.getVal<Float>(tunable.pref)
            .coerceIn(slider.valueFrom, slider.valueTo)
    }

    private fun thresholds() = Thresholds(
        glyphPct = binding.glyphSlider.value,
        minConfidence = binding.confSlider.value,
        katakanaPct = binding.kataSlider.value,
        minBrightness = binding.ringBrightSlider.value,
        ringIqr = binding.ringIqrSlider.value,
        ringPad = binding.ringPadSlider.value,
    )

    // ---------------------------------------------------------------------------------------
    // Recognition
    // ---------------------------------------------------------------------------------------

    private fun runOcr(uri: Uri) {
        currentScript = Script.entries.getOrElse(binding.scriptSpinner.selectedItemPosition) {
            Script.JAPANESE
        }
        val useSecondPass = binding.secondPass.isChecked
        binding.ocrProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val bitmap = decodeSampled(uri) ?: error("could not decode that image")
                    val started = System.currentTimeMillis()
                    var found = candidatesOf(recognize(bitmap), bitmap)
                    if (useSecondPass) found = found + secondPass(bitmap, found)
                    Triple(bitmap, found, System.currentTimeMillis() - started)
                }
            }
            binding.ocrProgress.visibility = View.GONE
            result.onSuccess { (bitmap, found, elapsed) ->
                sourceBitmap = bitmap
                // Cleared before rebuilding, not after: fallbackGlyph reads `blocks`, and letting
                // it see the previous page's would size this page's ring against that one.
                blocks = emptyList()
                candidates = found
                rebuildBlocks()
                lastOcrMillis = elapsed
                binding.pageImage.setPage(bitmap)
                // A new page has no translations, so previewing it would show a blank overlay.
                lastTranslateMillis = 0L
                setPreviewMode(false)
                val current = thresholds()
                val report = buildReport(score(current), current, full = true)
                Logger.log(report)
                Log.d(TAG, report)
            }.onFailure {
                toast(getString(R.string.ocr_spike_failed, it.message ?: ""))
                Logger.log("OCR spike failed: ${it.stackTraceToString()}")
            }
        }
    }

    /**
     * Reads the page again in overlapping tiles, and returns what the first pass never found.
     *
     * The full-page pass misses text it can read perfectly well: one measured page dropped a
     * normal-sized bubble outright, and the identical pixels came back at 0.85 confidence — the
     * best on that page — the moment they were handed over as a crop. So the limit is the
     * *detector* at page scale, not the recogniser, and the fix is to ask again at a scale where
     * the text is larger, which is precisely what the crop did by accident.
     *
     * Tiles overlap by [TILE_OVERLAP] so a bubble on a seam is whole in at least one of them, and
     * anything landing where the first pass already found something is discarded — the second pass
     * is there to add, never to relitigate.
     */
    private suspend fun secondPass(bitmap: Bitmap, found: List<Candidate>): List<Candidate> {
        val extra = mutableListOf<Candidate>()
        val stepX = bitmap.width / TILE_COLUMNS
        val stepY = bitmap.height / TILE_ROWS
        val padX = (stepX * TILE_OVERLAP).toInt()
        val padY = (stepY * TILE_OVERLAP).toInt()

        for (row in 0 until TILE_ROWS) {
            for (column in 0 until TILE_COLUMNS) {
                val tile = Rect(
                    (column * stepX - padX).coerceAtLeast(0),
                    (row * stepY - padY).coerceAtLeast(0),
                    ((column + 1) * stepX + padX).coerceAtMost(bitmap.width),
                    ((row + 1) * stepY + padY).coerceAtMost(bitmap.height),
                )
                if (tile.width() < MIN_CROP || tile.height() < MIN_CROP) continue

                val scale = (TILE_TARGET.toFloat() / min(tile.width(), tile.height()))
                    .coerceIn(1f, MAX_CROP_SCALE)
                val crop = Bitmap.createBitmap(
                    bitmap, tile.left, tile.top, tile.width(), tile.height(),
                )
                val scaled = if (scale > 1f) {
                    crop.scale((crop.width * scale).toInt(), (crop.height * scale).toInt())
                } else {
                    crop
                }
                val text = try {
                    recognize(scaled)
                } finally {
                    if (scaled !== crop) scaled.recycle()
                    crop.recycle()
                }

                text.textBlocks
                    .filter { it.boundingBox != null && it.text.length > 1 && it.lines.isNotEmpty() }
                    .forEach { block ->
                        val box = block.boundingBox!!.toPage(tile, scale)
                        val known = found.any { intersects(it.box, box) } ||
                            extra.any { intersects(it.box, box) }
                        if (known) return@forEach
                        val glyphs = block.lines.map { glyphSize(it).second }
                        extra += Candidate(
                            box = box,
                            lines = block.lines,
                            // Measured on the enlarged tile, so scaled back to page pixels or the
                            // glyph would read several times its real size.
                            glyphPx = glyphs.average().toFloat() / scale,
                            vertical = block.lines.first().angle > VERTICAL_ANGLE,
                        )
                    }
            }
        }
        return extra
    }

    /** A rect measured on a scaled tile, put back where it belongs on the page. */
    private fun Rect.toPage(tile: Rect, scale: Float) = Rect(
        tile.left + (left / scale).toInt(),
        tile.top + (top / scale).toInt(),
        tile.left + (right / scale).toInt(),
        tile.top + (bottom / scale).toInt(),
    )

    private fun intersects(a: Rect, b: Rect) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private suspend fun recognize(bitmap: Bitmap): Text {
        val recognizer = TextRecognition.getClient(currentScript.options())
        return try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        } finally {
            recognizer.close()
        }
    }

    private fun Script.options() = when (this) {
        Script.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
        Script.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
        Script.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
        Script.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
    }

    /**
     * A page is decoded at up to [MAX_DIMENSION] on its long edge. The reader hands ML Kit a bitmap
     * it has already downsampled to about twice the display, so recognising a full-resolution scan
     * here would flatter the results relative to what the real pipeline would feed it.
     */
    private fun decodeSampled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /**
     * A run of text as the recognizer found it, before any merging or judgement.
     *
     * Kept around because merging is a toggle: flipping it rebuilds the blocks from these rather
     * than re-reading the page, so merged and unmerged can be compared without a second OCR pass.
     */
    private class Candidate(
        val box: Rect,
        val lines: List<Text.Line>,
        val glyphPx: Float,
        val vertical: Boolean,
    )

    private fun candidatesOf(text: Text, bitmap: Bitmap): List<Candidate> =
        text.textBlocks
            // Same filter TachiyomiAT applies before it builds its blocks, so the counts here mean
            // what they would mean downstream. A block with no lines has nothing to measure.
            .filter { it.boundingBox != null && it.text.length > 1 && it.lines.isNotEmpty() }
            .mapNotNull { block ->
                val lines = linesInsideBubble(block.lines, bitmap)
                if (lines.isEmpty()) return@mapNotNull null
                Candidate(
                    // The surviving lines' own extent, not the recognizer's block box. The two
                    // differ exactly when a line has been dropped, which is the case this exists
                    // for: a page measured here had a block stretched over a character's teeth,
                    // and the box went with it even though nothing readable was there.
                    box = lines.mapNotNull { it.boundingBox }.reduce { a, b ->
                        Rect(a).apply { union(b) }
                    },
                    lines = lines,
                    glyphPx = lines.map { glyphSize(it).second }.average().toFloat(),
                    vertical = lines.first().angle > VERTICAL_ANGLE,
                )
            }

    /**
     * Drops lines the recognizer found outside anything resembling a bubble.
     *
     * Teeth, hatching and panel borders all read as text often enough to matter, and a single
     * spurious line drags the whole block's box out over the artwork with it. Each line is ringed
     * on its own — with its siblings excluded from the sample, or a middle column would measure the
     * columns either side of it — and judged against a deliberately slack threshold. The block-level
     * test decides whether a bubble may be painted over; this one only removes lines that are
     * plainly not in one, so it is set far looser than the slider and never touches ordinary text.
     */
    private fun linesInsideBubble(lines: List<Text.Line>, bitmap: Bitmap): List<Text.Line> {
        if (lines.size < 2) return lines
        val boxes = lines.mapNotNull { it.boundingBox }
        val kept = lines.filter { line ->
            val box = line.boundingBox ?: return@filter false
            val ring = ringStats(
                bitmap,
                box,
                glyphSize(line).second.toFloat(),
                LINE_RING_PAD,
                boxes.filter { it !== box },
            )
            ring.iqr <= LINE_RING_IQR
        }
        // Rejecting everything means the test is wrong about this block rather than the block being
        // wrong, so the recognizer's own answer stands.
        return kept.ifEmpty { lines }
    }

    /**
     * Joins the runs that belong to the same speech bubble.
     *
     * The recognizer splits a bubble wherever it likes — one measured page came back as 24 blocks
     * for perhaps nine bubbles — and every stage downstream suffers for it. Three fragments of one
     * sentence get three separate boxes competing for the same width, so each stays narrow; they
     * are drawn in no particular order, so the sentence is scattered across the page; and worst,
     * each fragment reaches the translator alone, which is exactly the input that turns a merely
     * mediocre translator into a confidently wrong one.
     *
     * Merging is orientation-aware rather than TachiyomiAT's fixed rule, because for vertical text
     * every axis is transposed: its columns advance sideways, so what marks them as one bubble is
     * a small *horizontal* gap between runs that share most of their *vertical* extent.
     */
    private fun mergeCandidates(input: List<Candidate>): List<Candidate> {
        val work = input.toMutableList()
        while (true) {
            val pair = findMergeablePair(work) ?: break
            val (i, j) = pair
            val merged = join(work[i], work[j])
            // Higher index first, or removing the lower one shifts the higher out from under us.
            work.removeAt(j)
            work.removeAt(i)
            work.add(merged)
        }
        return work
    }

    private fun findMergeablePair(work: List<Candidate>): Pair<Int, Int>? {
        for (i in work.indices) {
            for (j in i + 1 until work.size) {
                if (shouldMerge(work[i], work[j])) return i to j
            }
        }
        return null
    }

    private fun shouldMerge(a: Candidate, b: Candidate): Boolean {
        if (a.vertical != b.vertical) return false
        if (a.glyphPx <= 0f || b.glyphPx <= 0f) return false
        // Text of visibly different size is not one bubble — this is what stops a sound effect
        // being absorbed into the dialogue it happens to sit beside.
        if (max(a.glyphPx, b.glyphPx) / min(a.glyphPx, b.glyphPx) > MERGE_GLYPH_RATIO) return false

        val glyph = (a.glyphPx + b.glyphPx) / 2f
        return if (a.vertical) {
            gapBetween(a.box.left, a.box.right, b.box.left, b.box.right) <= glyph * MERGE_GAP_GLYPHS &&
                spanOverlap(a.box.top, a.box.bottom, b.box.top, b.box.bottom) >= MERGE_SPAN_OVERLAP
        } else {
            gapBetween(a.box.top, a.box.bottom, b.box.top, b.box.bottom) <= glyph * MERGE_GAP_GLYPHS &&
                spanOverlap(a.box.left, a.box.right, b.box.left, b.box.right) >= MERGE_SPAN_OVERLAP
        }
    }

    /** Distance between two spans on one axis; zero where they touch or overlap. */
    private fun gapBetween(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Float =
        max(0, max(aStart, bStart) - min(aEnd, bEnd)).toFloat()

    /** How much of the shorter of two spans the two share, as a fraction. */
    private fun spanOverlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Float {
        val overlap = min(aEnd, bEnd) - max(aStart, bStart)
        val shorter = min(aEnd - aStart, bEnd - bStart)
        return if (shorter <= 0) 0f else (overlap.toFloat() / shorter).coerceIn(0f, 1f)
    }

    private fun join(a: Candidate, b: Candidate) = Candidate(
        box = Rect(a.box).apply { union(b.box) },
        // Left unordered: orderedText sorts every line of the merged run into reading order, which
        // is the point of merging in the first place.
        lines = a.lines + b.lines,
        glyphPx = (a.glyphPx + b.glyphPx) / 2f,
        vertical = a.vertical,
    )

    /** Builds the working blocks from [candidates], honouring the merge toggle. */
    private fun rebuildBlocks() {
        val handDrawn = blocks.filter { it.synthetic }
        val runs = if (binding.mergeBlocks.isChecked) mergeCandidates(candidates) else candidates
        nextId = 1
        blocks = emptyList()
        blocks = runs.map { toRawBlock(nextId++, it.lines, it.box, synthetic = false) } +
            handDrawn.map { it.copy(id = nextId++, translation = "") }
    }

    /**
     * @param glyphScale what glyph sizes in [lines] were magnified by, so a block read from an
     *                   upscaled crop reports its glyphs in page pixels like every other block.
     */
    private fun toRawBlock(
        id: Int,
        lines: List<Text.Line>,
        box: Rect,
        synthetic: Boolean,
        glyphScale: Float = 1f,
    ): RawBlock {
        val glyphs = lines.map { glyphSize(it) }
        val (ordered, reordered) = orderedText(lines)
        return RawBlock(
            id = id,
            text = if (lines.isEmpty()) {
                getString(R.string.ocr_spike_nothing_recognised)
            } else {
                ordered.replace("\n", " / ")
            },
            reordered = reordered,
            box = box,
            confidence = if (lines.isEmpty()) 0f else {
                lines.map { it.confidence }.average().toFloat()
            },
            angle = lines.firstOrNull()?.angle ?: 0f,
            glyphPx = if (glyphs.isEmpty()) fallbackGlyph(box) else {
                glyphs.map { it.second }.average().toFloat() / glyphScale
            },
            symbolsAvailable = glyphs.isNotEmpty() && glyphs.all { it.first },
            lineCount = lines.size,
            katakanaRatio = katakanaRatio(ordered),
            synthetic = synthetic,
        )
    }

    /**
     * Glyph size to assume for a hand-drawn block the recognizer read nothing in.
     *
     * It still needs one, because the ring is padded in glyph widths. The median of the page's real
     * blocks is the honest guess — a block drawn on a page of ordinary dialogue is being compared
     * against that dialogue, so it should be sampled the same way.
     */
    private fun fallbackGlyph(box: Rect): Float {
        val real = blocks.filter { !it.synthetic && it.glyphPx > 0f }.map { it.glyphPx }.sorted()
        if (real.isNotEmpty()) return real[real.size / 2]
        return (min(box.width(), box.height()) / 10f).coerceAtLeast(2f)
    }

    /**
     * The lines in reading order, and whether that differed from the order ML Kit gave.
     *
     * Vertical Japanese runs top-to-bottom in columns that advance **right-to-left**, so the first
     * column of a bubble is the rightmost one. ML Kit hands the lines back the other way round: on
     * the measured page, one block came back as "wait" followed by "until the results come out",
     * which is the sentence inside out. Every multi-line vertical bubble is affected, and the
     * damage is invisible downstream — the translator simply receives a scrambled sentence and
     * returns a confident, wrong translation of it.
     */
    private fun orderedText(lines: List<Text.Line>): Pair<String, Boolean> {
        if (lines.isEmpty()) return "" to false
        if (lines.size < 2) return lines.joinToString("\n") { it.text } to false

        val ordered = if (lines.first().angle > VERTICAL_ANGLE) {
            lines.sortedByDescending { it.boundingBox?.left ?: 0 }
        } else {
            // Sorted rather than taken as given, because a merged run's lines arrive in the order
            // its pieces happened to be joined in, which is nobody's reading order.
            lines.sortedBy { it.boundingBox?.top ?: 0 }
        }
        return ordered.joinToString("\n") { it.text } to (ordered != lines)
    }

    /**
     * Glyph size for one line, and whether it came from a real symbol box.
     *
     * The cross-axis of a single line of text is its glyph size whichever way the line runs, so
     * `min(width, height)` of the line box is a sound fallback when symbols come back empty.
     */
    private fun glyphSize(line: Text.Line): Pair<Boolean, Int> {
        val symbol = line.elements.firstOrNull()?.symbols?.firstOrNull()?.boundingBox
        if (symbol != null) return true to min(symbol.width(), symbol.height())
        val box = line.boundingBox ?: return false to 0
        return false to min(box.width(), box.height())
    }

    /**
     * Mean and standard deviation of luminance in a ring just outside [box].
     *
     * The ring, not the interior: inside the box the text strokes themselves guarantee high
     * variance whether the block sits in a bubble or on artwork, so it carries no signal. What
     * separates them is what surrounds the text — a bubble is a large uniform light field, artwork
     * is not.
     *
     * The ring is padded in **glyph widths**, not as a fraction of the block. Padding by the block
     * was the first attempt and it misclassified more than half of a page of plain dialogue: a tall
     * vertical column padded by a quarter of its own height reaches far past the top and bottom of
     * its bubble, so the ring samples the bubble outline and the artwork behind it and reports the
     * variance of those. Glyph-relative padding keeps the ring against the text, inside the bubble,
     * whatever the block's shape.
     */
    private fun ringStats(
        bitmap: Bitmap,
        box: Rect,
        glyphPx: Float,
        pad: Float,
        /**
         * Regions to leave out of the sample.
         *
         * Only used when ringing a single *line*, where the neighbouring columns of the same
         * bubble sit exactly where the ring would fall. Without excluding them a line in the
         * middle of a bubble measures its neighbours' ink and reads as artwork — which would make
         * per-line filtering reject the healthiest text on the page.
         */
        exclude: List<Rect> = emptyList(),
    ): Ring {
        val padPx = (glyphPx * pad).toInt().coerceAtLeast(2)
        val outer = Rect(box.left - padPx, box.top - padPx, box.right + padPx, box.bottom + padPx)
        val stepX = (outer.width() / RING_SAMPLES).coerceAtLeast(1)
        val stepY = (outer.height() / RING_SAMPLES).coerceAtLeast(1)

        val samples = ArrayList<Float>(RING_SAMPLES * RING_SAMPLES)
        var sum = 0.0
        var sumOfSquares = 0.0
        var y = outer.top
        while (y < outer.bottom) {
            var x = outer.left
            while (x < outer.right) {
                if (!box.contains(x, y) && x in 0 until bitmap.width && y in 0 until bitmap.height &&
                    exclude.none { it.contains(x, y) }
                ) {
                    val pixel = bitmap[x, y]
                    val luminance = (
                        0.299 * Color.red(pixel) +
                            0.587 * Color.green(pixel) +
                            0.114 * Color.blue(pixel)
                        ).toFloat()
                    samples.add(luminance)
                    sum += luminance
                    sumOfSquares += luminance * luminance
                }
                x += stepX
            }
            y += stepY
        }
        if (samples.isEmpty()) return Ring(0f, 0f, 0f, 0f)

        samples.sort()
        val mean = sum / samples.size
        val variance = (sumOfSquares / samples.size - mean * mean).coerceAtLeast(0.0)
        return Ring(
            median = samples[samples.size / 2],
            iqr = samples[samples.size * 3 / 4] - samples[samples.size / 4],
            mean = mean.toFloat(),
            stdDev = sqrt(variance).toFloat(),
        )
    }

    /** Katakana as a share of letters — Japanese onomatopoeia is written in it almost exclusively. */
    private fun katakanaRatio(text: String): Float {
        var katakana = 0
        var letters = 0
        text.forEach { c ->
            if (!c.isLetter()) return@forEach
            letters++
            // Katakana, the phonetic-extension small katakana, and the halfwidth forms.
            if (c in KATAKANA || c in KATAKANA_PHONETIC || c in KATAKANA_HALFWIDTH) {
                katakana++
            }
        }
        return if (letters == 0) 0f else katakana.toFloat() / letters
    }

    // ---------------------------------------------------------------------------------------
    // Editing
    // ---------------------------------------------------------------------------------------

    /**
     * Adds a hand-drawn block, having first asked the recognizer what it makes of that region on
     * its own. A bubble the full-page pass skipped that reads perfectly from a crop is a *detection*
     * failure, which a bubble-segmentation model would fix; one that comes back empty either way is
     * a *recognition* failure, which it would not.
     */
    private fun addBlock(rect: Rect) {
        val source = sourceBitmap ?: return
        lifecycleScope.launch {
            val (lines, glyphScale) = recognizeCrop(source, rect)
            val block = toRawBlock(nextId++, lines, rect, synthetic = true, glyphScale = glyphScale)
            blocks = blocks + block
            binding.pageImage.select(block.id)
            render()
        }
    }

    private fun reOcrSelected() {
        val id = binding.pageImage.selectedId ?: return
        val block = blocks.firstOrNull { it.id == id } ?: return
        val source = sourceBitmap ?: return
        lifecycleScope.launch {
            val (lines, glyphScale) = recognizeCrop(source, block.box)
            val updated = toRawBlock(id, lines, block.box, block.synthetic, glyphScale)
            blocks = blocks.map { if (it.id == id) updated else it }
            render()
        }
    }

    private fun setAddMode(enabled: Boolean) {
        binding.pageImage.addMode = enabled
        binding.addBlockButton.setText(
            if (enabled) R.string.ocr_spike_add_block_armed else R.string.ocr_spike_add_block,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Translation
    // ---------------------------------------------------------------------------------------

    /**
     * Translates every block the thresholds accept, then switches to the preview.
     *
     * Only [Verdict.DIALOGUE] and [Verdict.SFX] are sent. The other two verdicts exist precisely to
     * say "do not translate this": OVER_ART would have its box painted over artwork, and LOW_CONF
     * is text the recognizer itself does not believe, which a translator will happily render as
     * confident nonsense. Skipping them here is the first time those verdicts have actually been
     * *used* for anything rather than merely reported.
     */
    private fun translatePage() {
        sourceBitmap ?: return
        val from = MlKitTranslator.translationCodeFor(currentScript.name) ?: return
        val to = targetLanguage ?: return
        val engine = TranslationEngine.entries[binding.engineSpinner.selectedItemPosition]
        if (engine.needsKey && engine.storedKey().isBlank()) {
            toast(getString(R.string.ocr_spike_key_required, engine.label))
            return
        }
        val t = thresholds()
        val wanted = score(t).filter {
            it.verdict == Verdict.DIALOGUE || it.verdict == Verdict.SFX
        }
        if (wanted.isEmpty()) {
            toast(getString(R.string.ocr_spike_nothing_to_translate))
            return
        }

        binding.ocrProgress.visibility = View.VISIBLE
        binding.translateButton.isEnabled = false
        lifecycleScope.launch {
            val result = runCatching {
                engine.build(from, to, targetLabel()).use { translator ->
                    // Separate from the translation so the ~30 MB first-run model download is
                    // announced rather than looking like the request having hung.
                    snackString(getString(R.string.ocr_spike_preparing_model))
                    translator.prepare()

                    val started = System.currentTimeMillis()
                    // One call for the whole page, in order. The batch is the interface's whole
                    // point: it is what lets a language model see the bubbles as a conversation.
                    val results = translator.translate(wanted.map { it.block.sourceText() })
                    val translated = wanted.mapIndexed { index, scored ->
                        scored.block.id to results.getOrElse(index) { scored.block.sourceText() }
                    }.toMap()
                    translated to (System.currentTimeMillis() - started)
                }
            }
            binding.ocrProgress.visibility = View.GONE
            binding.translateButton.isEnabled = true
            result.onSuccess { (translated, elapsed) ->
                blocks = blocks.map { it.copy(translation = translated[it.id] ?: "") }
                lastTranslateMillis = elapsed
                setPreviewMode(true)
            }.onFailure {
                Logger.log("OCR spike translation failed: ${it.stackTraceToString()}")
                if (it is LlmTranslator.ModelUnavailable) {
                    // The stored model does not work with this key, and it will not start working.
                    // Dropped and the catalogue re-read, so the next attempt is made with whatever
                    // the provider currently offers rather than failing identically.
                    PrefManager.removeVal(PrefName.OcrTranslationModel)
                    modelCache.remove(engine)
                    refreshModels(quiet = true)
                }
                reportError(getString(R.string.ocr_spike_translate_failed, it.message ?: ""))
            }
        }
    }

    /**
     * The block's text as one run, for the translator.
     *
     * [RawBlock.text] carries " / " between lines so the report stays on one line, which would
     * reach the translator as punctuation inside the sentence. A bubble is one sentence broken
     * wherever its columns ended, so the separators come back out.
     */
    private fun RawBlock.sourceText(): String {
        // Japanese, Korean and Chinese do not space their words, so the columns rejoin with
        // nothing between them. Latin does, and joining "hello" to "world" with nothing would hand
        // the translator a word that does not exist.
        val joiner = if (currentScript == Script.LATIN) " " else ""
        return text.replace(" / ", joiner)
    }

    private fun setPreviewMode(enabled: Boolean) {
        binding.pageImage.previewMode = enabled
        binding.previewToggle.setText(
            if (enabled) R.string.ocr_spike_show_detection else R.string.ocr_spike_show_translation,
        )
        binding.previewToggle.isEnabled = blocks.any { it.translation.isNotBlank() }
        render()
    }

    /**
     * The rect a translation should be drawn in, which is not the rect the text was read from.
     *
     * A vertical Japanese column is tall and narrow — one measured page ran 40x200 px — and English
     * set into that shape wraps to roughly one character per line. So a block whose source ran
     * vertically is widened about its own centre until it is no worse than [PREVIEW_ASPECT] wide
     * for its height, taking the space from the artwork either side, and clamped to the page.
     *
     * This is a real cost of the approach rather than a detail: the widened box paints over more of
     * the drawing than the text ever occupied. A production renderer would want the bubble's actual
     * outline here — which is the strongest argument yet for bubble segmentation, and it comes from
     * rendering rather than from detection, where the argument was expected.
     */
    private fun previewRects(scored: List<Scored>, page: Bitmap): Map<Int, Rect> {
        val original = scored.associate { it.block.id to it.block.box }

        return scored.associate { s ->
            val block = s.block
            val own = block.box
            if (block.angle <= VERTICAL_ANGLE) return@associate block.id to own

            val wanted = (own.height() * PREVIEW_ASPECT).toInt()
            if (own.width() >= wanted) return@associate block.id to own

            // How far out it may grow before it would reach something else. Only blocks sharing
            // some of its vertical span can be in the way; one further up the page cannot be.
            var leftLimit = 0
            var rightLimit = page.width
            original.forEach { (id, other) ->
                if (id == block.id) return@forEach
                if (other.top >= own.bottom || other.bottom <= own.top) return@forEach
                // Each takes half the gap between them. Letting both expand to the other's edge is
                // what made the text overlap: the whole gap would be claimed twice over.
                if (other.right <= own.left) {
                    leftLimit = max(leftLimit, (other.right + own.left) / 2)
                }
                if (other.left >= own.right) {
                    rightLimit = min(rightLimit, (own.right + other.left) / 2)
                }
            }
            leftLimit += PREVIEW_GUTTER
            rightLimit -= PREVIEW_GUTTER

            val grow = (wanted - own.width()) / 2
            block.id to Rect(
                // coerceAtLeast against the block's own edge, never past it: a neighbour that
                // already overlaps horizontally would otherwise push a limit *inside* this block
                // and crop the very text being widened for.
                (own.left - grow).coerceAtLeast(min(leftLimit, own.left)),
                own.top,
                (own.right + grow).coerceAtMost(max(rightLimit, own.right)),
                own.bottom,
            )
        }
    }

    private fun deleteSelected() {
        val id = binding.pageImage.selectedId ?: return
        blocks = blocks.filterNot { it.id == id }
        binding.pageImage.select(null)
        render()
    }

    /**
     * Reads one region on its own, and by how much the glyphs in the result were magnified.
     *
     * Two corrections, without which a negative result here means nothing. The crop is **padded**,
     * because a box drawn tightly by hand leaves the recognizer no quiet space around the text and
     * it reads a bare column of characters poorly. And a small crop is **upscaled**, because a few
     * characters cut out of a page is a very small image — the full-page pass sees those same
     * glyphs as part of something far larger, so cropping without scaling asks the recognizer a
     * harder question than the one it already answered, and a failure would say nothing about
     * whether the text is readable.
     *
     * Sizes in the result are therefore in scaled-crop pixels; the second element is the divisor
     * that puts them back into page pixels.
     */
    private suspend fun recognizeCrop(source: Bitmap, rect: Rect): Pair<List<Text.Line>, Float> {
        val marginX = (rect.width() * CROP_MARGIN).toInt().coerceAtLeast(MIN_CROP_MARGIN)
        val marginY = (rect.height() * CROP_MARGIN).toInt().coerceAtLeast(MIN_CROP_MARGIN)
        val safe = Rect(
            (rect.left - marginX).coerceIn(0, source.width - 1),
            (rect.top - marginY).coerceIn(0, source.height - 1),
            (rect.right + marginX).coerceIn(1, source.width),
            (rect.bottom + marginY).coerceIn(1, source.height),
        )
        if (safe.width() < MIN_CROP || safe.height() < MIN_CROP) return emptyList<Text.Line>() to 1f

        return runCatching {
            withContext(Dispatchers.Default) {
                val crop = Bitmap.createBitmap(
                    source, safe.left, safe.top, safe.width(), safe.height(),
                )
                val scale = (CROP_TARGET.toFloat() / min(crop.width, crop.height))
                    .coerceIn(1f, MAX_CROP_SCALE)
                val scaled = if (scale > 1f) {
                    crop.scale((crop.width * scale).toInt(), (crop.height * scale).toInt())
                } else {
                    crop
                }
                try {
                    recognize(scaled).textBlocks.flatMap { it.lines } to scale
                } finally {
                    if (scaled !== crop) scaled.recycle()
                    crop.recycle()
                }
            }
        }.getOrElse {
            Logger.log("OCR spike crop failed: ${it.stackTraceToString()}")
            emptyList<Text.Line>() to 1f
        }
    }

    // ---------------------------------------------------------------------------------------
    // Scoring — a slider move or a drag costs one re-sample of the ring, not a re-OCR
    // ---------------------------------------------------------------------------------------

    private fun score(t: Thresholds): List<Scored> {
        val source = sourceBitmap ?: return emptyList()
        return blocks.map { block ->
            val ring = ringStats(source, block.box, block.glyphPx, t.ringPad)
            Scored(block, ring, verdict(block, ring, source.height, t))
        }
    }

    /**
     * Ordered by what makes the difference downstream: a block that is not in a bubble must not be
     * boxed at all, a block the recognizer does not believe must not be translated, and only then
     * is it worth asking whether what remains is dialogue or an effect.
     *
     * The bubble test is **uniformity alone**, not brightness. Requiring a bright ring threw out
     * white-on-black dialogue — one measured block sat in an inverted bubble at median luminance 2
     * and was the most uniform region on its page, yet failed a `>= 200` test. Since the question
     * this verdict actually answers is "can a rectangle be painted here without destroying
     * artwork", a uniform dark field qualifies exactly as much as a uniform light one; the fill
     * colour is then [Ring.median] rather than always white. [Thresholds.minBrightness] is kept as
     * an opt-in, defaulting to 0 — off.
     */
    private fun verdict(
        block: RawBlock,
        ring: Ring,
        pageHeight: Int,
        t: Thresholds,
    ): Verdict {
        if (ring.iqr > t.ringIqr) return Verdict.OVER_ART
        if (t.minBrightness > 0f && ring.median < t.minBrightness) return Verdict.OVER_ART
        if (block.confidence < t.minConfidence) return Verdict.LOW_CONF

        val glyphPct = block.glyphPct(pageHeight)
        val isSfx = glyphPct >= t.glyphPct ||
            (block.katakanaRatio * 100f >= t.katakanaPct && glyphPct >= t.glyphPct / 2f)
        return if (isSfx) Verdict.SFX else Verdict.DIALOGUE
    }

    /** Which test decided [scored]'s verdict, so a drag says *why* the colour changed. */
    private fun reason(scored: Scored, pageHeight: Int, t: Thresholds): String {
        val block = scored.block
        return when (scored.verdict) {
            Verdict.OVER_ART -> if (scored.ring.iqr > t.ringIqr) {
                getString(R.string.ocr_spike_reason_iqr, scored.ring.iqr, t.ringIqr)
            } else {
                getString(R.string.ocr_spike_reason_median, scored.ring.median, t.minBrightness)
            }

            Verdict.LOW_CONF -> getString(
                R.string.ocr_spike_reason_confidence,
                block.confidence,
                t.minConfidence,
            )

            Verdict.SFX -> if (block.glyphPct(pageHeight) >= t.glyphPct) {
                getString(
                    R.string.ocr_spike_reason_glyph,
                    block.glyphPct(pageHeight),
                    t.glyphPct,
                )
            } else {
                getString(
                    R.string.ocr_spike_reason_katakana,
                    block.katakanaRatio * 100,
                    t.katakanaPct,
                    block.glyphPct(pageHeight),
                )
            }

            Verdict.DIALOGUE -> getString(R.string.ocr_spike_reason_pass)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Output
    // ---------------------------------------------------------------------------------------

    private fun render() {
        val t = thresholds()
        renderLabels(t)

        val source = sourceBitmap
        if (source == null) {
            binding.summaryText.text = listOfNotNull(
                getString(R.string.ocr_spike_pick_prompt),
                lastError,
            ).joinToString("\n")
            return
        }
        val scored = score(t)
        binding.pageImage.setBoxes(
            if (binding.pageImage.previewMode) {
                // Widened against every block, not just the drawn ones: a block that was filtered
                // out of the preview still occupies space the others must not grow into.
                val rects = previewRects(scored, source)
                scored.filter { it.block.translation.isNotBlank() }.map {
                    BlockEditorView.Box(
                        id = it.block.id,
                        rect = rects.getValue(it.block.id),
                        color = it.verdict.color,
                        translation = it.block.translation,
                        fill = fillColor(it.ring.median),
                    )
                }
            } else {
                scored.map { BlockEditorView.Box(it.block.id, it.block.box, it.verdict.color) }
            },
        )

        val counts = scored.groupingBy { it.verdict }.eachCount()
        binding.summaryText.text = buildString {
            appendLine(
                getString(
                    R.string.ocr_spike_counts,
                    scored.size,
                    counts[Verdict.DIALOGUE] ?: 0,
                    counts[Verdict.SFX] ?: 0,
                    counts[Verdict.LOW_CONF] ?: 0,
                    counts[Verdict.OVER_ART] ?: 0,
                ),
            )
            append(
                getString(
                    R.string.ocr_spike_page_info,
                    source.width,
                    source.height,
                    lastOcrMillis,
                ),
            )
            val reordered = scored.count { it.block.reordered }
            if (reordered > 0) {
                append("\n" + getString(R.string.ocr_spike_reordered_count, reordered))
            }
            val detected = scored.count { !it.block.synthetic }
            if (binding.mergeBlocks.isChecked && candidates.size != detected) {
                append(
                    "\n" + getString(
                        R.string.ocr_spike_merged_info,
                        candidates.size,
                        detected,
                    ),
                )
            }
            val hand = scored.count { it.block.synthetic }
            if (hand > 0) append("\n" + getString(R.string.ocr_spike_hand_drawn_count, hand))
            val translated = scored.count { it.block.translation.isNotBlank() }
            if (translated > 0) {
                append(
                    "\n" + getString(
                        R.string.ocr_spike_translated_info,
                        translated,
                        lastTranslateMillis,
                    ),
                )
            }
            if (blocks.isNotEmpty() && blocks.none { it.symbolsAvailable }) {
                append("\n" + getString(R.string.ocr_spike_symbols_empty))
            }
            lastError?.let { append("\n\n$it") }
        }

        val selected = scored.firstOrNull { it.block.id == binding.pageImage.selectedId }
        binding.selectionText.text = if (selected == null) {
            getString(R.string.ocr_spike_hint)
        } else {
            val block = selected.block
            buildString {
                appendLine("#${block.id}  ${selected.verdict.label.trim()}")
                appendLine("  ${reason(selected, source.height, t)}")
                appendLine(
                    getString(
                        R.string.ocr_spike_selection_metrics,
                        block.glyphPct(source.height),
                        block.confidence,
                        block.angle,
                        (block.katakanaRatio * 100).roundToInt(),
                        block.lineCount,
                        if (block.reordered) {
                            " " + getString(R.string.ocr_spike_line_reordered)
                        } else {
                            ""
                        },
                    ),
                )
                appendLine(
                    getString(
                        R.string.ocr_spike_selection_ring,
                        selected.ring.median.roundToInt(),
                        selected.ring.iqr.roundToInt(),
                        selected.ring.mean.roundToInt(),
                        selected.ring.stdDev.roundToInt(),
                    ),
                )
                appendLine(
                    getString(
                        R.string.ocr_spike_selection_size,
                        block.box.width(),
                        block.box.height(),
                        if (block.synthetic) {
                            "  " + getString(R.string.ocr_spike_hand_drawn)
                        } else {
                            ""
                        },
                    ),
                )
                appendLine("  ${block.text}")
                if (block.translation.isNotBlank()) append("  -> ${block.translation}")
            }
        }
        val hasSelection = selected != null
        binding.reOcrButton.isEnabled = hasSelection
        binding.deleteBlockButton.isEnabled = hasSelection

        binding.reportText.text = buildReport(scored, t, full = false)
    }

    private fun renderLabels(t: Thresholds) {
        binding.glyphLabel.text = getString(R.string.ocr_spike_label_glyph, t.glyphPct)
        binding.confLabel.text = getString(R.string.ocr_spike_label_confidence, t.minConfidence)
        binding.kataLabel.text = getString(R.string.ocr_spike_label_katakana, t.katakanaPct)
        binding.ringBrightLabel.text = if (t.minBrightness <= 0f) {
            getString(R.string.ocr_spike_label_min_median_off)
        } else {
            getString(R.string.ocr_spike_label_min_median, t.minBrightness)
        }
        binding.ringIqrLabel.text = getString(R.string.ocr_spike_label_iqr, t.ringIqr)
        binding.ringPadLabel.text = getString(R.string.ocr_spike_label_pad, t.ringPad)
    }

    private fun buildReport(scored: List<Scored>, t: Thresholds, full: Boolean): String {
        val source = sourceBitmap ?: return getString(R.string.ocr_spike_no_page)
        return buildString {
            if (full) {
                appendLine(getString(R.string.ocr_spike_report_header))
                appendLine(
                    getString(
                        R.string.ocr_spike_report_page,
                        source.width,
                        source.height,
                        lastOcrMillis,
                    ),
                )
                appendLine(
                    getString(
                        R.string.ocr_spike_report_thresholds,
                        t.glyphPct, t.minConfidence, t.katakanaPct,
                        t.ringIqr, t.minBrightness, t.ringPad,
                    ),
                )
            }
            scored.forEach { (block, ring, verdict) ->
                appendLine(
                    getString(
                        R.string.ocr_spike_report_line,
                        block.id,
                        verdict.label,
                        block.glyphPct(source.height),
                        block.confidence,
                        block.angle,
                        (block.katakanaRatio * 100).roundToInt(),
                        ring.median.roundToInt(),
                        ring.iqr.roundToInt(),
                        ring.stdDev.roundToInt(),
                        if (block.symbolsAvailable) "sym" else "box",
                        if (block.reordered) "R" else " ",
                        if (block.synthetic) "H" else " ",
                        // Source and result on one line: judging a translation means seeing what
                        // it was translated *from*, and pasting the report elsewhere is how these
                        // get compared.
                        if (block.translation.isBlank()) {
                            block.text.take(24)
                        } else {
                            "${block.text.take(24)} -> ${block.translation}"
                        },
                    ),
                )
            }
            if (scored.isEmpty()) appendLine(getString(R.string.ocr_spike_no_blocks))
        }
    }

    /** One calibrated threshold: the slider that sets it and the preference that keeps it. */
    private class Tunable(
        val pref: PrefName,
        val slider: (ActivityMangaOcrSpikeBinding) -> Slider,
    )

    private companion object {
        /**
         * Every threshold, paired with where it lives. Loading, saving and resetting all walk this
         * one list, so adding a threshold cannot leave it persisted in one direction only.
         */
        val TUNABLES = listOf(
            Tunable(PrefName.OcrSfxGlyphPercent) { it.glyphSlider },
            Tunable(PrefName.OcrMinConfidence) { it.confSlider },
            Tunable(PrefName.OcrKatakanaPercent) { it.kataSlider },
            Tunable(PrefName.OcrMinRingMedian) { it.ringBrightSlider },
            Tunable(PrefName.OcrRingIqr) { it.ringIqrSlider },
            Tunable(PrefName.OcrRingPad) { it.ringPadSlider },
        )

        const val TAG = "OcrSpike"

        /** Long edge the page is decoded to, matching roughly what the reader would hand over. */
        const val MAX_DIMENSION = 2048

        /** Above this line angle the block is treated as a vertical column. */
        const val VERTICAL_ANGLE = 85f

        /** Width-to-height a vertical block's box is widened towards before text is set into it. */
        const val PREVIEW_ASPECT = 1.4f

        /** Page pixels left between two widened boxes, so neighbours read as separate. */
        const val PREVIEW_GUTTER = 3

        /** Largest glyph-size ratio two runs may differ by and still be one bubble. */
        const val MERGE_GLYPH_RATIO = 1.6f

        /** Largest gap between two runs of one bubble, in glyph widths. */
        const val MERGE_GAP_GLYPHS = 1.8f

        /** How much of their shared axis two runs must overlap on to be one bubble. */
        const val MERGE_SPAN_OVERLAP = 0.5f

        /** Grid resolution of the ring sample, per axis. */
        const val RING_SAMPLES = 40

        /** Below this the crop is too small for the recognizer to be asked anything useful. */
        const val MIN_CROP = 12

        /** Second-pass tiling. Overlap so a bubble on a seam is whole in at least one tile. */
        const val TILE_ROWS = 3
        const val TILE_COLUMNS = 2
        const val TILE_OVERLAP = 0.15f
        const val TILE_TARGET = 900

        /** Ring thickness used when judging a single line, in glyph widths. */
        const val LINE_RING_PAD = 0.5f

        /**
         * Ring spread above which a line is taken to be outside any bubble.
         *
         * Far slacker than the block-level slider on purpose. This only has to catch lines sitting
         * on artwork — teeth, hatching, a panel border — and every false rejection here silently
         * deletes readable text, which is a worse failure than leaving a stray line in.
         */
        const val LINE_RING_IQR = 60f

        /** Quiet space added around a hand-drawn box before it is read, as a share of its size. */
        const val CROP_MARGIN = 0.25f

        /** Floor for that margin, for a box drawn tightly around a couple of characters. */
        const val MIN_CROP_MARGIN = 12

        /** A crop is upscaled until its short edge reaches this, which the recognizer prefers. */
        const val CROP_TARGET = 320

        /** Ceiling on that upscale — past this it is interpolation inventing detail, not detail. */
        const val MAX_CROP_SCALE = 4f

        /** U+30A0..U+30FF, the main katakana block. */
        val KATAKANA = '゠'..'ヿ'

        /** U+31F0..U+31FF, small katakana used for phonetic extensions. */
        val KATAKANA_PHONETIC = 'ㇰ'..'ㇿ'

        /** U+FF66..U+FF9D, the halfwidth forms. */
        val KATAKANA_HALFWIDTH = 'ｦ'..'ﾝ'
    }
}
