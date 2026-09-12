package ani.dantotsu.media.manga.translation

import android.content.Context
import ani.dantotsu.R
import ani.dantotsu.snackString
import ani.dantotsu.util.choiceBottomSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The four things a reader might want to change about translation, and how to change them.
 *
 * Shared rather than duplicated because they are offered in two places — the reader's quick
 * settings, for changing engine or language against the page in front of you, and the settings
 * screen, for setting it up once. Two copies would be two chances for a picker to change a
 * different setting than the one the other shows.
 *
 * Each picker is given the [MtlSettings] in force and hands back the changed copy; where that copy
 * is kept is the caller's business. The settings screen writes it to the preferences and the
 * reader to the manga's own settings, and this is what lets one set of pickers serve both without
 * either knowing which it is.
 */
object MtlChoices {

    fun engineLabel(settings: MtlSettings): String = settings.engine.label

    fun modelLabel(context: Context, settings: MtlSettings): String {
        if (!settings.engine.needsKey) return context.getString(R.string.ocr_model_not_applicable)
        return settings.modelName()
    }

    fun targetLabel(settings: MtlSettings): String =
        Locale.forLanguageTag(settings.targetLanguage()).getDisplayName(Locale.getDefault())

    /**
     * What the source-script row reads.
     *
     * @param detected what the metadata resolves to, where the caller knows which media is open. On
     *   automatic the row names it — "Japanese (auto)" — because "Automatic" alone leaves the one
     *   thing worth knowing unsaid: a page that comes back with nothing recognised is usually a
     *   page being read with the wrong recognizer, and a row that only says it decided for itself
     *   gives no way to see that.
     */
    fun scriptLabel(context: Context, settings: MtlSettings, detected: TextScript? = null): String =
        settings.script?.label ?: autoLabel(context, detected)

    private fun autoLabel(context: Context, detected: TextScript?): String =
        if (detected == null) context.getString(R.string.ocr_source_script_auto)
        else context.getString(R.string.ocr_source_script_auto_detected, detected.label)

    fun pickEngine(context: Context, settings: MtlSettings, onChanged: (MtlSettings) -> Unit) {
        context.choiceBottomSheet(
            context.getString(R.string.ocr_spike_engine),
            TranslationEngine.entries.map { it.label },
            settings.engine.ordinal,
        ) { index ->
            // The model belongs to the engine that serves it, so a name chosen for one provider is
            // meaningless to the next and would 404 on first use.
            onChanged(settings.copy(engine = TranslationEngine.entries[index], model = ""))
        }
    }

    fun pickTarget(context: Context, settings: MtlSettings, onChanged: (MtlSettings) -> Unit) {
        val targets = MlKitTranslator.supportedTargets()
        context.choiceBottomSheet(
            context.getString(R.string.ocr_spike_target_language),
            targets.map { it.second },
            targets.indexOfFirst { it.first == settings.targetLanguage() },
        ) { index ->
            onChanged(settings.copy(target = targets[index].first))
        }
    }

    fun pickScript(
        context: Context,
        settings: MtlSettings,
        detected: TextScript? = null,
        onChanged: (MtlSettings) -> Unit,
    ) {
        // Automatic first, because it is the right answer for anything with metadata and the one
        // most people should never have to move off. It names what it decided, so choosing against
        // it is a comparison rather than a guess.
        val labels = listOf(autoLabel(context, detected)) + TextScript.entries.map { it.label }
        context.choiceBottomSheet(
            context.getString(R.string.ocr_source_script),
            labels,
            (settings.script?.ordinal ?: -1) + 1,
        ) { index ->
            onChanged(settings.copy(script = TextScript.entries.getOrNull(index - 1)))
        }
    }

    /**
     * Offers what the provider currently serves, having asked it.
     *
     * Fetched rather than listed in code: a baked-in name is wrong the moment a provider retires
     * it, and the symptom is a 404 that reads to the user as the feature being broken. That has
     * already happened here once, to a name that was current when it was written down.
     */
    fun pickModel(
        context: Context,
        scope: CoroutineScope,
        settings: MtlSettings,
        onChanged: (MtlSettings) -> Unit,
    ) {
        val engine = settings.engine
        if (!engine.needsKey) return
        val key = engine.storedKey()
        if (engine == TranslationEngine.GEMINI && key.isBlank()) {
            snackString(context.getString(R.string.ocr_spike_key_required, engine.label))
            return
        }
        snackString(context.getString(R.string.ocr_models_loading))
        scope.launch {
            runCatching { LlmTranslator.fetchModels(engine, key) }
                .onSuccess { models ->
                    if (models.isEmpty()) {
                        snackString(context.getString(R.string.ocr_spike_no_models))
                        return@onSuccess
                    }
                    context.choiceBottomSheet(
                        context.getString(R.string.ocr_spike_model),
                        models,
                        models.indexOf(settings.model),
                    ) { index ->
                        onChanged(settings.copy(model = models[index]))
                    }
                }
                .onFailure { snackString(it.message ?: it.toString()) }
        }
    }
}
