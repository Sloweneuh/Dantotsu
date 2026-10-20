package ani.dantotsu.media.novel.novelreader

import android.content.Context
import android.graphics.Typeface
import android.util.Base64
import androidx.core.content.res.ResourcesCompat
import ani.dantotsu.R
import ani.dantotsu.themes.AppFont
import ani.dantotsu.themes.DeviceFonts
import ani.dantotsu.util.Logger
import java.io.File

/**
 * The novel reader's typefaces, named by a key (NovelFontKey, and per book in
 * CurrentNovelReaderSettings):
 *  - [BOOK]: the book's own fonts, untouched.
 *  - `css:<family>`: one of the web view's generic families.
 *  - `res:<name>`: a font the app ships — Poppins and AppFont's bundled ones.
 *  - `file:<path>`: a font file — one installed on the device, or one imported for the app font.
 *
 * The last two reach the book as an `@font-face` carrying the file itself as a data URL: chapters
 * render in frames of their own origin, which can't load the app's resources or files by path.
 */
object NovelFonts {

    const val BOOK = "book"

    /** The face every `res:`/`file:` choice is declared under in the chapter's styles. */
    private const val FACE = "DantotsuNovelFont"

    /** Past this a font is refused: it is sent to the page whole, and kept in every chapter's styles. */
    private const val MAX_BYTES = 6L * 1024 * 1024

    private val generic = listOf(
        "css:serif" to R.string.novel_font_serif,
        "css:sans-serif" to R.string.novel_font_sans,
        "css:sans-serif-condensed" to R.string.novel_font_condensed,
        "css:monospace" to R.string.novel_font_mono,
    )

    private val shipped = listOf("poppins" to R.font.poppins) + AppFont.bundled

    /** A choice the picker offers: its key, its name and the face its row is drawn in. */
    class Option(val key: String, val label: String, val preview: Typeface?)

    /**
     * Every choice, in picker order: the book's own, the generic families, the app's fonts, fonts
     * imported for the app font, then the device's. Reads the device's font files, so it is not
     * free; the picker calls it once per opening.
     */
    fun options(context: Context): List<Option> = buildList {
        add(Option(BOOK, context.getString(R.string.novel_font_book), null))
        generic.forEach { (key, name) -> add(Option(key, context.getString(name), typefaceOf(context, key))) }
        shipped.forEach { (name, _) -> add(Option("res:$name", pretty(name), typefaceOf(context, "res:$name"))) }
        imported(context).forEach { add(Option("file:${it.path}", pretty(it.nameWithoutExtension), typefaceOf(context, "file:${it.path}"))) }
        DeviceFonts.list().forEach { add(Option("file:${it.path}", it.label, typefaceOf(context, "file:${it.path}"))) }
    }

    /** What a row reads as for [key]. */
    fun label(context: Context, key: String): String = when {
        key == BOOK -> context.getString(R.string.novel_font_book)
        key.startsWith("css:") -> generic.firstOrNull { it.first == key }?.let { context.getString(it.second) }
            ?: key.removePrefix("css:")
        key.startsWith("res:") -> pretty(key.removePrefix("res:"))
        key.startsWith("file:") -> pretty(
            File(key.removePrefix("file:")).nameWithoutExtension.substringBefore('-')
                .replace(Regex("(?<=[a-z])(?=[A-Z])"), " "),
        )
        else -> context.getString(R.string.novel_font_book)
    }

    /** The fonts imported for the app font: copied into the app's files, where they stay. */
    private fun imported(context: Context): List<File> =
        File(context.filesDir, "fonts").listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    private fun pretty(name: String) =
        name.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun typefaceOf(context: Context, key: String): Typeface? = runCatching {
        when {
            key == "css:serif" -> Typeface.SERIF
            key == "css:sans-serif" -> Typeface.SANS_SERIF
            key == "css:monospace" -> Typeface.MONOSPACE
            key.startsWith("css:") -> Typeface.create(key.removePrefix("css:"), Typeface.NORMAL)
            key.startsWith("res:") -> shipped.firstOrNull { it.first == key.removePrefix("res:") }
                ?.let { ResourcesCompat.getFont(context, it.second) }
            key.startsWith("file:") -> File(key.removePrefix("file:")).takeIf { it.isFile }
                ?.let { Typeface.createFromFile(it) }
            else -> null
        }
    }.getOrNull()

    private var cachedKey: String? = null
    private var cachedRule: String? = null

    /**
     * The CSS that puts [key]'s font on a chapter's text, or "" for the book's own (or a font that
     * can't be read). Code keeps its monospace face. Cached for the last key: building it reads and
     * encodes the whole font file.
     */
    fun cssRule(context: Context, key: String): String {
        if (key == cachedKey) return cachedRule.orEmpty()
        val family: String? = when {
            key.startsWith("css:") -> key.removePrefix("css:").takeIf { it.matches(Regex("[a-z-]+")) }
                ?.let { if (it == "sans-serif-condensed") "'sans-serif-condensed', sans-serif" else it }
            key.startsWith("res:") || key.startsWith("file:") -> null
            else -> return ""
        }
        val rule = if (family != null) {
            body(family)
        } else {
            val data = bytesOf(context, key)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            if (data == null) "" else
                "@font-face { font-family: '$FACE'; src: url(data:font/ttf;base64,$data); }\n" +
                    body("'$FACE', serif")
        }
        cachedKey = key
        cachedRule = rule
        return rule
    }

    private fun body(family: String) =
        "html, body, body *:not(pre):not(code):not(kbd):not(samp) { font-family: $family !important; }"

    private fun bytesOf(context: Context, key: String): ByteArray? = try {
        when {
            key.startsWith("res:") -> shipped.firstOrNull { it.first == key.removePrefix("res:") }?.let { (_, id) ->
                context.resources.openRawResource(id).use { it.readBytes() }
            }
            key.startsWith("file:") -> File(key.removePrefix("file:"))
                .takeIf { it.isFile && it.length() <= MAX_BYTES }?.readBytes()
            else -> null
        }
    } catch (e: Exception) {
        Logger.log("NovelFonts: could not read $key - ${e.message}")
        null
    }
}
