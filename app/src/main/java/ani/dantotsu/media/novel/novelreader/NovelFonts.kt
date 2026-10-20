package ani.dantotsu.media.novel.novelreader

import ani.dantotsu.R

/**
 * The novel reader's typefaces: the book's own, or one of the system's families, which the web
 * view finds by their Android names. Indexed from 1 — 0 is what a book saved before fonts existed
 * reads back, and stands for the global default (see CurrentNovelReaderSettings.effectiveFont).
 */
object NovelFonts {

    private class Font(val name: Int, val css: String?)

    private val fonts = listOf(
        Font(R.string.novel_font_book, null),
        Font(R.string.novel_font_serif, "serif"),
        Font(R.string.novel_font_sans, "sans-serif"),
        Font(R.string.novel_font_condensed, "'sans-serif-condensed', sans-serif"),
        Font(R.string.novel_font_mono, "monospace"),
    )

    /** Names in index order, from 1. */
    val names: List<Int> get() = fonts.map { it.name }

    fun name(index: Int): Int = (fonts.getOrNull(index - 1) ?: fonts[0]).name

    /** The CSS font-family list for [index], or null to keep the book's own. */
    fun css(index: Int): String? = fonts.getOrNull(index - 1)?.css
}
