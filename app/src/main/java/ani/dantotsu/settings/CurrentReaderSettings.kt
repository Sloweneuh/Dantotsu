package ani.dantotsu.settings

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import java.io.Serializable

data class CurrentReaderSettings(
    var direction: Directions = Directions[PrefManager.getVal(PrefName.Direction)]
        ?: Directions.TOP_TO_BOTTOM,
    var layout: Layouts = Layouts[PrefManager.getVal(PrefName.LayoutReader)]
        ?: Layouts.CONTINUOUS,
    var dualPageMode: DualPageModes = DualPageModes[PrefManager.getVal(PrefName.DualPageModeReader)]
        ?: DualPageModes.Automatic,
    var overScrollMode: Boolean = PrefManager.getVal(PrefName.OverScrollMode),
    var trueColors: Boolean = PrefManager.getVal(PrefName.TrueColors),
    var rotation: Boolean = PrefManager.getVal(PrefName.Rotation),
    var padding: Boolean = PrefManager.getVal(PrefName.Padding),
    var hideScrollBar: Boolean = PrefManager.getVal(PrefName.HideScrollBar),
    var hidePageNumbers: Boolean = PrefManager.getVal(PrefName.HidePageNumbers),
    var horizontalScrollBar: Boolean = PrefManager.getVal(PrefName.HorizontalScrollBar),
    var keepScreenOn: Boolean = PrefManager.getVal(PrefName.KeepScreenOn),
    var volumeButtons: Boolean = PrefManager.getVal(PrefName.VolumeButtonsReader),
    var wrapImages: Boolean = PrefManager.getVal(PrefName.WrapImages),
    var longClickImage: Boolean = PrefManager.getVal(PrefName.LongClickImage),
    var cropBorders: Boolean = PrefManager.getVal(PrefName.CropBorders),
    var cropBorderThreshold: Int = PrefManager.getVal(PrefName.CropBorderThreshold),
    var lockRotation: Boolean = PrefManager.getVal(PrefName.LockRotation),
    var preloadAmount: Int = PrefManager.getVal(PrefName.PreloadAmount),
    // Translation, kept as the raw preference values so this stays plainly serializable. Read and
    // written through ani.dantotsu.media.manga.translation.MtlSettings rather than directly.
    var mtlEngine: Int = PrefManager.getVal(PrefName.OcrTranslationEngine),
    var mtlModel: String = PrefManager.getVal(PrefName.OcrTranslationModel),
    var mtlTarget: String = PrefManager.getVal(PrefName.OcrTargetLanguage),
    var mtlScript: Int = PrefManager.getVal(PrefName.OcrSourceScript),
    var mtlAuto: Boolean = PrefManager.getVal(PrefName.OcrAutoTranslate),
    var mtlStitch: Boolean = PrefManager.getVal(PrefName.OcrStitchPages),
) : Serializable {

    /**
     * Every series' reader settings are stored as this object, serialized ([ReaderSettingsStore]).
     * Without a fixed version id, Java derives one from the class's shape, so adding a setting
     * made every stored file unreadable — and the store deletes those without a word, putting
     * every series back on the global defaults. That is how a series the reader had been told not
     * to auto-translate started translating again. Pinned to the id the class had then, so files
     * written before it load as they are; a field added later reads as Java's default (false, 0,
     * null) from an older file — [readResolve] puts text fields right.
     */
    private fun readResolve(): Any {
        @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
        if (mtlModel == null || mtlTarget == null) {
            mtlModel = mtlModel ?: ""
            mtlTarget = mtlTarget ?: ""
        }
        return this
    }

    enum class Directions {
        TOP_TO_BOTTOM,
        RIGHT_TO_LEFT,
        BOTTOM_TO_TOP,
        LEFT_TO_RIGHT;

        companion object {
            operator fun get(value: Int) = values().firstOrNull { it.ordinal == value }
        }
    }

    enum class Layouts {
        PAGED,
        CONTINUOUS_PAGED,
        CONTINUOUS;

        companion object {
            operator fun get(value: Int) = values().firstOrNull { it.ordinal == value }
        }
    }

    enum class DualPageModes {
        No, Automatic, Force;

        companion object {
            operator fun get(value: Int) = values().firstOrNull { it.ordinal == value }
        }
    }

    companion object {
        /** See [readResolve]: the id the class had when it was pinned, so older files still load. */
        private const val serialVersionUID = 8599800351496651642L

        fun applyWebtoon(settings: CurrentReaderSettings) {
            settings.apply {
                layout = Layouts.CONTINUOUS
                direction = Directions.TOP_TO_BOTTOM
                dualPageMode = DualPageModes.No
                // Smart spacing joins a strip's pieces on its own, and keeps the gap where a
                // non-Japanese series turns out to be uploaded as pages after all.
                if (!PrefManager.getVal<Boolean>(PrefName.SmartPadding)) padding = false
            }
        }
    }
}

