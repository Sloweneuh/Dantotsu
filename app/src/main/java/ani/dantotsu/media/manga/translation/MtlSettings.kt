package ani.dantotsu.media.manga.translation

import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import java.util.Locale

/**
 * The translation choices a reader can change, as one value.
 *
 * They live in two places. The preferences hold the defaults every manga starts from, and are
 * what the settings screen edits; [CurrentReaderSettings] holds each manga's own copy, the way it
 * holds that manga's layout and direction, and is what the reader's sheet edits. Everything that
 * translates a page is handed one of these rather than reading the preferences itself — that is
 * what makes the manga's copy the one that counts once it exists, and what stops a toggle flipped
 * for one series quietly changing every other.
 *
 * The master switch is not here on purpose: whether the feature exists at all is a decision about
 * the app, not about a series, and it stays on the preference — see [PageTranslationPipeline.enabled].
 */
data class MtlSettings(
    val engine: TranslationEngine,
    /** Blank for the engine's own default. */
    val model: String,
    /** Blank for the device's language. */
    val target: String,
    /** Null for automatic — see [SourceScript.detect]. */
    val script: TextScript?,
    /** Whether pages translate themselves as they scroll into view. */
    val auto: Boolean,
    /** Whether a page is read together with a strip of its neighbours. */
    val stitch: Boolean,
) {
    fun targetLanguage(): String = target.ifBlank { Locale.getDefault().language }

    fun modelName(): String = model.ifBlank { engine.defaultModel() }

    /** Whether an engine that needs a key has one, so a caller can explain rather than fail. */
    fun ready(): Boolean = !engine.needsKey || engine.storedKey().isNotBlank()

    fun saveToPrefs() {
        PrefManager.setVal(PrefName.OcrTranslationEngine, engine.ordinal)
        PrefManager.setVal(PrefName.OcrTranslationModel, model)
        PrefManager.setVal(PrefName.OcrTargetLanguage, target)
        PrefManager.setVal(PrefName.OcrSourceScript, script?.ordinal ?: -1)
        PrefManager.setVal(PrefName.OcrAutoTranslate, auto)
        PrefManager.setVal(PrefName.OcrStitchPages, stitch)
    }

    fun applyTo(settings: CurrentReaderSettings) {
        settings.mtlEngine = engine.ordinal
        settings.mtlModel = model
        settings.mtlTarget = target
        settings.mtlScript = script?.ordinal ?: -1
        settings.mtlAuto = auto
        settings.mtlStitch = stitch
    }

    companion object {
        fun fromPrefs() = MtlSettings(
            engine = TranslationEngine.fromPref(),
            model = PrefManager.getVal(PrefName.OcrTranslationModel),
            target = PrefManager.getVal(PrefName.OcrTargetLanguage),
            script = SourceScript.override(),
            auto = PrefManager.getVal(PrefName.OcrAutoTranslate),
            stitch = PrefManager.getVal(PrefName.OcrStitchPages),
        )

        fun from(settings: CurrentReaderSettings) = MtlSettings(
            engine = TranslationEngine.entries.getOrElse(settings.mtlEngine) {
                TranslationEngine.ML_KIT
            },
            model = settings.mtlModel,
            target = settings.mtlTarget,
            script = TextScript.entries.getOrNull(settings.mtlScript),
            auto = settings.mtlAuto,
            stitch = settings.mtlStitch,
        )
    }
}
