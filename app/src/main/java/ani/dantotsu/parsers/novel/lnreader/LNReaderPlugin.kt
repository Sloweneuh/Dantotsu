package ani.dantotsu.parsers.novel.lnreader

import java.io.Serializable

/**
 * An LNReader plugin as published in a repository index.
 *
 * These are not Android packages. Unlike the anime/manga extensions — and unlike the older
 * `some.random.*` novel extensions this app also supports — an LNReader plugin is a single
 * JavaScript file, so there is no installer, no package manager and no signature. Installing means
 * downloading the file; uninstalling means deleting it.
 */
data class LNReaderPlugin(
    val id: String,
    val name: String,
    val site: String,
    val lang: String,
    val version: String,
    val url: String,
    val iconUrl: String?,
    val customCSS: String?,
    /** Which configured repository this came from, so a plugin can be traced back to its source. */
    val repository: String,
) : Serializable {

    /**
     * Versions are free-form strings upstream (`2.2.0`, `1.0`, occasionally with suffixes), so
     * they are compared segment-wise with a plain string fallback rather than parsed strictly.
     */
    fun isNewerThan(other: String): Boolean {
        val a = version.split('.', '-').mapNotNull { it.toIntOrNull() }
        val b = other.split('.', '-').mapNotNull { it.toIntOrNull() }
        if (a.isEmpty() || b.isEmpty()) return version != other
        for (i in 0 until maxOf(a.size, b.size)) {
            val l = a.getOrElse(i) { 0 }
            val r = b.getOrElse(i) { 0 }
            if (l != r) return l > r
        }
        return false
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** A plugin whose source has been downloaded and is ready to run. */
data class InstalledLNReaderPlugin(
    val plugin: LNReaderPlugin,
    val hasUpdate: Boolean = false,
    /** Version available upstream when [hasUpdate] is set. */
    val availableVersion: String? = null,
) : Serializable {
    val id get() = plugin.id
    val name get() = plugin.name

    companion object {
        private const val serialVersionUID = 1L
    }
}
