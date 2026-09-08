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
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityMangaOcrSpikeBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.media.manga.translation.BlockScorer
import ani.dantotsu.media.manga.translation.BlockVerdict
import ani.dantotsu.media.manga.translation.DetectionThresholds
import ani.dantotsu.media.manga.translation.LlmTranslator
import ani.dantotsu.media.manga.translation.PageTextDetector
import ani.dantotsu.media.manga.translation.ScoredBlock
import ani.dantotsu.media.manga.translation.SourceScript
import ani.dantotsu.media.manga.translation.TextBlock
import ani.dantotsu.media.manga.translation.TextScript
import ani.dantotsu.media.manga.translation.TranslationLayout
import ani.dantotsu.media.manga.translation.MlKitTranslator
import ani.dantotsu.media.manga.translation.TranslationEngine
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import com.google.android.material.slider.Slider
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

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
    private var blocks: List<TextBlock> = emptyList()
    private var lastOcrMillis = 0L
    private var nextId = 1
    private var currentScript = TextScript.JAPANESE
    private var lastTranslateMillis = 0L

    /** The recognizer's runs for the current page, before merging. See [PageTextDetector.TextRun]. */
    private var candidates: List<PageTextDetector.TextRun> = emptyList()

    /** Lines and tile finds the ring test threw out, so a filter doing nothing is visible. */
    private var rejected = 0

    /** Last failure, kept on screen rather than flashed past in a toast. */
    private var lastError: String? = null

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
     * The detector for the script currently chosen, rebuilt when that changes.
     *
     * Held rather than passed around so this screen and the reader run the same code on the same
     * settings: if a threshold or a heuristic could differ between them, calibrating here would
     * tell nobody anything about what the reader does.
     */
    private var detectorFor: TextScript? = null
    private var detectorInstance: PageTextDetector? = null

    private fun detector(): PageTextDetector {
        if (detectorFor != currentScript || detectorInstance == null) {
            detectorInstance = PageTextDetector(currentScript)
            detectorFor = currentScript
        }
        return detectorInstance!!
    }

    /**
     * Reports what the reader is configured to translate with, without offering to change it.
     *
     * The engine, its model and the target language are the *feature's* settings and live in the
     * reader's OCR & MTL card. Duplicating the pickers here would give two places to set one thing
     * and no way to tell which had been used; showing them keeps this screen honest about what it
     * is about to do.
     */
    private fun showMtlConfig() {
        val engine = TranslationEngine.fromPref()
        val target = SourceScript.targetLanguage()
        binding.mtlConfigText.text = getString(
            R.string.ocr_spike_mtl_config,
            engine.label,
            if (engine.needsKey) {
                PrefManager.getVal<String>(PrefName.OcrTranslationModel)
                    .ifBlank { engine.defaultModel() }
            } else {
                getString(R.string.ocr_model_not_applicable)
            },
            Locale.forLanguageTag(target).getDisplayName(Locale.getDefault()),
        )
    }

    override fun onResume() {
        super.onResume()
        // Settings are a screen away and can have changed while this one sat in the back stack.
        showMtlConfig()
    }

    private fun score(t: DetectionThresholds): List<ScoredBlock> {
        val source = sourceBitmap ?: return emptyList()
        return BlockScorer.score(blocks, source, t)
    }

    /** What a verdict is called in the report. Padded so the columns line up. */
    private val BlockVerdict.label: String
        get() = when (this) {
            BlockVerdict.DIALOGUE -> "DIALOGUE"
            BlockVerdict.SFX -> "SFX     "
            BlockVerdict.LOW_CONF -> "LOW_CONF"
            BlockVerdict.OVER_ART -> "OVER_ART"
            BlockVerdict.FURIGANA -> "FURIGANA"
            BlockVerdict.SEAM -> "SEAM    "
        }

    /** What a verdict is drawn in. */
    private val BlockVerdict.color: Int
        get() = when (this) {
            BlockVerdict.DIALOGUE -> 0xFF4CAF50.toInt()
            BlockVerdict.SFX -> 0xFFFF9800.toInt()
            BlockVerdict.LOW_CONF -> 0xFF9C27B0.toInt()
            BlockVerdict.OVER_ART -> 0xFFF44336.toInt()
            BlockVerdict.FURIGANA -> 0xFF2196F3.toInt()
            BlockVerdict.SEAM -> 0xFF00BCD4.toInt()
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
            TextScript.entries.map { it.label },
        )


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
        showMtlConfig()
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

    private fun thresholds() = DetectionThresholds(
        glyphPercent = binding.glyphSlider.value,
        minConfidence = binding.confSlider.value,
        katakanaPercent = binding.kataSlider.value,
        minBrightness = binding.ringBrightSlider.value,
        ringIqr = binding.ringIqrSlider.value,
        ringPad = binding.ringPadSlider.value,
        flatPercent = binding.ringFlatSlider.value,
    )

    // ---------------------------------------------------------------------------------------
    // Recognition
    // ---------------------------------------------------------------------------------------

    private fun runOcr(uri: Uri) {
        currentScript = TextScript.entries.getOrElse(binding.scriptSpinner.selectedItemPosition) {
            TextScript.JAPANESE
        }
        binding.ocrProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val bitmap = decodeSampled(uri) ?: error("could not decode that image")
                    val started = System.currentTimeMillis()
                    val found = detector().read(bitmap)
                    rejected = detector().rejected
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
     * A page is decoded at up to [MAX_DIMENSION] across, within a budget of [MAX_PIXELS]. The reader
     * hands ML Kit a bitmap it has already downsampled to about twice the display, so recognising a
     * full-resolution scan here would flatter the results relative to what the real pipeline would
     * feed it.
     *
     * Across, not on the long edge, which is what this used to measure. A longstrip image is 800
     * wide and eight thousand tall, and capping its *long* edge at 2048 sampled it down by four —
     * leaving a 200-pixel-wide page that was visibly pixelated on screen and had no glyphs left in
     * it to recognise. Width is what decides whether text is legible; height only decides how much
     * of it there is, and the pixel budget is what keeps that from running away.
     */
    private fun decodeSampled(uri: Uri): Bitmap? {
        // Read once, decode twice. Measuring and decoding each used to open the provider
        // separately, which gives a picked file two chances to have become unreachable between
        // them — and on a picker entry whose grant has lapsed, or a file behind a bridge that has
        // gone away, that is exactly what happens. A page is a few hundred kilobytes; holding it
        // briefly costs far less than the failure did.
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("could not open that image")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            bounds.outWidth / sample > MAX_DIMENSION ||
            bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > MAX_PIXELS
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** Builds the working blocks from [candidates], honouring the merge toggle. */
    private fun rebuildBlocks() {
        val handDrawn = blocks.filter { it.synthetic }
        blocks = emptyList()
        val detected = detector().blocks(candidates, binding.mergeBlocks.isChecked)
        nextId = detected.size + 1
        blocks = detected + handDrawn.map { it.copy(id = nextId++, translation = "") }
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
            val (lines, glyphScale) = detector().readRegion(source, rect)
            val block = detector().toBlock(
                nextId++, lines, rect,
                synthetic = true, glyphScale = glyphScale, fallbackGlyphPx = fallbackGlyph(rect),
            )
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
            val (lines, glyphScale) = detector().readRegion(source, block.box)
            val updated = detector().toBlock(
                id, lines, block.box,
                block.synthetic, glyphScale, fallbackGlyphPx = fallbackGlyph(block.box),
            )
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
     * Only [BlockVerdict.DIALOGUE] and [BlockVerdict.SFX] are sent. The other two verdicts exist precisely to
     * say "do not translate this": OVER_ART would have its box painted over artwork, and LOW_CONF
     * is text the recognizer itself does not believe, which a translator will happily render as
     * confident nonsense. Skipping them here is the first time those verdicts have actually been
     * *used* for anything rather than merely reported.
     */
    private fun translatePage() {
        sourceBitmap ?: return
        val from = MlKitTranslator.translationCodeFor(currentScript.name) ?: return
        val to = SourceScript.targetLanguage()
        val engine = TranslationEngine.fromPref()
        if (engine.needsKey && engine.storedKey().isBlank()) {
            toast(getString(R.string.ocr_spike_key_required, engine.label))
            return
        }
        val t = thresholds()
        val wanted = score(t).filter {
            it.verdict == BlockVerdict.DIALOGUE || it.verdict == BlockVerdict.SFX
        }
        if (wanted.isEmpty()) {
            toast(getString(R.string.ocr_spike_nothing_to_translate))
            return
        }

        binding.ocrProgress.visibility = View.VISIBLE
        binding.translateButton.isEnabled = false
        lifecycleScope.launch {
            val result = runCatching {
                engine.build(from, to, Locale.forLanguageTag(to).getDisplayName(Locale.ENGLISH)).use { translator ->
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
                }
                reportError(getString(R.string.ocr_spike_translate_failed, it.message ?: ""))
            }
        }
    }

    /**
     * The block's text as one run, for the translator.
     *
     * [TextBlock.text] carries " / " between lines so the report stays on one line, which would
     * reach the translator as punctuation inside the sentence. A bubble is one sentence broken
     * wherever its columns ended, so the separators come back out.
     */
    private fun TextBlock.sourceText(): String {
        // Japanese, Korean and Chinese do not space their words, so the columns rejoin with
        // nothing between them. Latin does, and joining "hello" to "world" with nothing would hand
        // the translator a word that does not exist.
        val joiner = if (currentScript == TextScript.LATIN) " " else ""
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



    private fun deleteSelected() {
        val id = binding.pageImage.selectedId ?: return
        blocks = blocks.filterNot { it.id == id }
        binding.pageImage.select(null)
        render()
    }

    // ---------------------------------------------------------------------------------------
    // Scoring — a slider move or a drag costs one re-sample of the ring, not a re-OCR
    // ---------------------------------------------------------------------------------------

    /** Which test decided [scored]'s verdict, so a drag says *why* the colour changed. */
    private fun reason(scored: ScoredBlock, pageHeight: Int, t: DetectionThresholds): String {
        val block = scored.block
        return when (scored.verdict) {
            BlockVerdict.OVER_ART -> if (scored.ring.iqr > t.ringIqr) {
                getString(
                    R.string.ocr_spike_reason_iqr,
                    scored.ring.iqr,
                    t.ringIqr,
                    scored.ring.flatShare * 100f,
                    t.flatPercent,
                )
            } else {
                getString(R.string.ocr_spike_reason_median, scored.ring.median, t.minBrightness)
            }

            BlockVerdict.LOW_CONF -> getString(
                R.string.ocr_spike_reason_confidence,
                block.confidence,
                t.minConfidence,
            )

            BlockVerdict.SFX -> if (block.glyphPercent(pageHeight) >= t.glyphPercent) {
                getString(
                    R.string.ocr_spike_reason_glyph,
                    block.glyphPercent(pageHeight),
                    t.glyphPercent,
                )
            } else {
                getString(
                    R.string.ocr_spike_reason_katakana,
                    block.katakanaRatio * 100,
                    t.katakanaPercent,
                    block.glyphPercent(pageHeight),
                )
            }

            BlockVerdict.FURIGANA -> getString(R.string.ocr_spike_reason_furigana)

            BlockVerdict.SEAM -> getString(R.string.ocr_spike_reason_seam)

            BlockVerdict.DIALOGUE -> if (BlockScorer.flat(scored.ring, t)) {
                getString(R.string.ocr_spike_reason_flat, scored.ring.flatShare * 100f)
            } else {
                getString(R.string.ocr_spike_reason_pass)
            }
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
            scored.map { BlockEditorView.Box(it.block.id, it.block.box, it.verdict.color) },
            // Laid out by the same code the reader will use, so what is calibrated here is what
            // gets drawn there — the page included, since a box only grows across pixels that
            // measure as the colour it is about to be painted.
            TranslationLayout.paint(scored, source.width, source.height, source),
        )

        val counts = scored.groupingBy { it.verdict }.eachCount()
        binding.summaryText.text = buildString {
            appendLine(
                getString(
                    R.string.ocr_spike_counts,
                    scored.size,
                    counts[BlockVerdict.DIALOGUE] ?: 0,
                    counts[BlockVerdict.SFX] ?: 0,
                    counts[BlockVerdict.LOW_CONF] ?: 0,
                    counts[BlockVerdict.OVER_ART] ?: 0,
                    counts[BlockVerdict.FURIGANA] ?: 0,
                ),
            )
            val flat = scored.count { it.verdict.covered && BlockScorer.flat(it.ring, t) }
            if (flat > 0) appendLine(getString(R.string.ocr_spike_counts_flat, flat))
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
            if (rejected > 0) {
                append("\n" + getString(R.string.ocr_spike_rejected_count, rejected))
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
                        block.glyphPercent(source.height),
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
                        selected.ring.flatShare * 100f,
                        String.format("#%06X", selected.ring.color and 0xFFFFFF),
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

    private fun renderLabels(t: DetectionThresholds) {
        binding.glyphLabel.text = getString(R.string.ocr_spike_label_glyph, t.glyphPercent)
        binding.confLabel.text = getString(R.string.ocr_spike_label_confidence, t.minConfidence)
        binding.kataLabel.text = getString(R.string.ocr_spike_label_katakana, t.katakanaPercent)
        binding.ringBrightLabel.text = if (t.minBrightness <= 0f) {
            getString(R.string.ocr_spike_label_min_median_off)
        } else {
            getString(R.string.ocr_spike_label_min_median, t.minBrightness)
        }
        binding.ringIqrLabel.text = getString(R.string.ocr_spike_label_iqr, t.ringIqr)
        binding.ringPadLabel.text = getString(R.string.ocr_spike_label_pad, t.ringPad)
        binding.ringFlatLabel.text = if (t.flatPercent >= 100f) {
            getString(R.string.ocr_spike_label_flat_off)
        } else {
            getString(R.string.ocr_spike_label_flat, t.flatPercent)
        }
    }

    private fun buildReport(scored: List<ScoredBlock>, t: DetectionThresholds, full: Boolean): String {
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
                        t.glyphPercent, t.minConfidence, t.katakanaPercent,
                        t.ringIqr, t.minBrightness, t.ringPad, t.flatPercent,
                    ),
                )
            }
            scored.forEach { (block, ring, verdict) ->
                appendLine(
                    getString(
                        R.string.ocr_spike_report_line,
                        block.id,
                        verdict.label,
                        block.glyphPercent(source.height),
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
            Tunable(PrefName.OcrFlatPercent) { it.ringFlatSlider },
        )

        const val TAG = "OcrSpike"

        /** Width the page is decoded to, matching roughly what the reader would hand over. */
        const val MAX_DIMENSION = 2048

        /** Ceiling on the whole bitmap, which is what stops a tall strip exhausting memory. */
        const val MAX_PIXELS = 8_000_000L

        /** Above this line angle the block is treated as a vertical column. */
        const val VERTICAL_ANGLE = 85f

        /** Width-to-height a vertical block's box is widened towards before text is set into it. */
        const val PREVIEW_ASPECT = 1.4f

        /** Margin added when covering a block up, in glyph widths. */
        const val ERASURE_PAD = 0.25f

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

        /**
         * Width the page is enlarged to before the recognizer sees it.
         *
         * ML Kit is markedly better on larger text, and manga pages arrive small enough that its
         * glyphs sit near the bottom of what it reads reliably.
         */
        const val OCR_TARGET_WIDTH = 1600
        const val MAX_OCR_SCALE = 2.5f

        /**
         * How many median glyphs tall a glyph box may be before it is treated as misdrawn.
         *
         * Two is well clear of real variation — the widest spread measured in one column was 19
         * to 39 against a median of 36 — while the bad box that prompted this was 145.
         */
        const val GLYPH_OUTLIER = 2f

        /**
         * Below this share of the page median glyph, kana-only text is taken to be furigana.
         *
         * Ruby is conventionally about half the size of the text it annotates. On the page this
         * came from, body glyphs ran 1.16-1.56%% of page height while the three furigana measured
         * 0.43, 0.69 and 0.94%% — the last of which is why this sits at 0.8 rather than nearer the
         * conventional 0.5.
         */
        const val FURIGANA_RATIO = 0.8f

        /** Ring thickness used when judging a single line, in glyph widths. */
        const val LINE_RING_PAD = 0.5f

        /**
         * Ring spread above which a line is taken to be outside any bubble.
         *
         * Slacker than the block-level slider, but not by as much as it first was. Set to 60 it
         * sat above measured artwork readings of 28 and 44 and so rejected nothing that mattered;
         * across four pages in-bubble rings never exceeded 17 while artwork ran 28 and up, and 30
         * sits in that gap with room on the safe side. Every false rejection here silently deletes
         * readable text, which is why it is not tightened to the block-level 20.
         */
        const val LINE_RING_IQR = 30f

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
