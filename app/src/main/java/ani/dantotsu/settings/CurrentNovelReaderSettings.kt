package ani.dantotsu.settings

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import java.io.Serializable

data class CurrentNovelReaderSettings(
    var currentThemeName: String = PrefManager.getVal(PrefName.CurrentThemeName),
    var layout: Layouts = Layouts[PrefManager.getVal(PrefName.LayoutNovel)]
        ?: Layouts.PAGED,
    var dualPageMode: CurrentReaderSettings.DualPageModes = CurrentReaderSettings.DualPageModes[PrefManager.getVal(
        PrefName.DualPageModeNovel
    )]
        ?: CurrentReaderSettings.DualPageModes.Automatic,
    var lineHeight: Float = PrefManager.getVal(PrefName.LineHeight),
    var margin: Float = PrefManager.getVal(PrefName.Margin),
    var justify: Boolean = PrefManager.getVal(PrefName.Justify),
    var hyphenation: Boolean = PrefManager.getVal(PrefName.Hyphenation),
    var useDarkTheme: Boolean = PrefManager.getVal(PrefName.UseDarkThemeNovel),
    var useOledTheme: Boolean = PrefManager.getVal(PrefName.UseOledThemeNovel),
    var invert: Boolean = PrefManager.getVal(PrefName.Invert),
    var maxInlineSize: Int = PrefManager.getVal(PrefName.MaxInlineSize),
    var maxBlockSize: Int = PrefManager.getVal(PrefName.MaxBlockSize),
    var horizontalScrollBar: Boolean = PrefManager.getVal(PrefName.HorizontalScrollBarNovel),
    var keepScreenOn: Boolean = PrefManager.getVal(PrefName.KeepScreenOnNovel),
    var volumeButtons: Boolean = PrefManager.getVal(PrefName.VolumeButtonsNovel),
    var lockRotation: Boolean = PrefManager.getVal(PrefName.LockRotationNovel),
    var hidePageNumbers: Boolean = PrefManager.getVal(PrefName.HidePageNumbersNovel),
    /**
     * Text size as a percentage of the book's own, applied as the reader's text zoom. 0 in settings
     * saved before it existed (deserialising skips defaults) — read it through [effectiveTextSize].
     */
    var textSize: Int = PrefManager.getVal(PrefName.NovelTextSize),
    /** The typeface, as an index into NovelFonts; 0 in settings saved before it existed. */
    var font: Int = PrefManager.getVal(PrefName.NovelFont),
) : Serializable {

    /** [textSize], with the global default standing in for a book saved before it existed. */
    val effectiveTextSize: Int
        get() = textSize.takeIf { it > 0 } ?: PrefManager.getVal(PrefName.NovelTextSize)

    /** [font], with the global default standing in for a book saved before it existed. */
    val effectiveFont: Int
        get() = font.takeIf { it > 0 } ?: PrefManager.getVal(PrefName.NovelFont)

    companion object {
        // Pinned to the value the class had before textSize and font were added, so every book's saved
        // settings still load: Java computes one from the fields otherwise, and any new field would
        // make all of them fail to read and fall back to the defaults.
        private const val serialVersionUID = 4612653471301037023L
    }

    enum class Layouts(val string: String) {
        PAGED("Paged"),
        SCROLLED("Scrolled");

        companion object {
            operator fun get(value: Int) = values().firstOrNull { it.ordinal == value }
        }
    }
}
