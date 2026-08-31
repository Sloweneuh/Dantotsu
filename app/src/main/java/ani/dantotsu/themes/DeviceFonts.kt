package ani.dantotsu.themes

import android.graphics.Typeface
import android.graphics.fonts.SystemFonts
import android.os.Build
import ani.dantotsu.util.Logger
import java.io.File

/** One font the picker can offer. */
data class DeviceFont(val label: String, val path: String)

/**
 * The fonts installed on this device.
 *
 * Narrower than it sounds, and deliberately so: [SystemFonts.getAvailableFonts] returns the *system*
 * set, and below API 29 there is nothing but the files in /system/fonts. Neither reaches fonts a
 * user added through an OEM theme store — no public API does — which is why the picker also offers
 * to open a font file directly.
 */
object DeviceFonts {

    private val EXTENSIONS = setOf("ttf", "otf")

    /** Faces whose names mark them as something other than body text. */
    private val EXCLUDED = listOf("emoji", "icon", "symbol", "material", "flag")

    fun list(): List<DeviceFont> = try {
        val files = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SystemFonts.getAvailableFonts().mapNotNull { it.file }
        } else {
            File("/system/fonts").listFiles()?.toList().orEmpty()
        }
        files
            .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
            // One entry per family: the system ships a file per weight and style, and listing
            // "Roboto-Regular", "Roboto-Medium", "Roboto-BoldItalic" separately would offer the
            // user twenty ways to pick the same font while hiding the weight cascade's whole point.
            .groupBy { familyOf(it.name) }
            .mapNotNull { (family, group) ->
                if (EXCLUDED.any { family.lowercase().contains(it) }) return@mapNotNull null
                // Prefer the regular cut as the base; the cascade derives the rest from it.
                val file = group.firstOrNull { it.name.contains("Regular", true) } ?: group.first()
                val tf = runCatching { Typeface.createFromFile(file) }.getOrNull()
                    ?: return@mapNotNull null
                if (!AppFont.hasLatinCoverage(tf)) return@mapNotNull null
                DeviceFont(family, file.absolutePath)
            }
            .sortedBy { it.label.lowercase() }
    } catch (e: Exception) {
        Logger.log("DeviceFonts: enumeration failed - ${e.message}")
        emptyList()
    }

    /** "NotoSans-BoldItalic.ttf" -> "Noto Sans". */
    private fun familyOf(fileName: String): String {
        val stem = fileName.substringBeforeLast('.').substringBefore('-')
        return stem
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .replace('_', ' ')
            .trim()
            .ifBlank { stem }
    }
}
