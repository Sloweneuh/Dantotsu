package ani.dantotsu.settings

import android.graphics.drawable.Drawable
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.parsers.novel.lnreader.InstalledLNReaderPlugin

/**
 * One row in the novel source lists, whichever kind of source it is.
 *
 * The novel tabs show two unrelated things — extension APKs and LNReader JavaScript plugins — that
 * happen to be interchangeable to a reader picking a source. They share no install path, so this
 * exists only to give the lists a single row type; anything that acts on a row branches on which
 * case it is.
 */
sealed class NovelSourceItem {

    abstract val key: String
    abstract val name: String
    abstract val versionLabel: String
    abstract val hasUpdate: Boolean
    abstract val icon: Drawable?

    data class Extension(val extension: NovelExtension.Installed) : NovelSourceItem() {
        override val key get() = "ext:${extension.pkgName}"
        override val name get() = extension.name
        override val versionLabel
            get() = "${LanguageMapper.getLanguageName("all")} ${extension.versionName}"
        override val hasUpdate get() = extension.hasUpdate
        override val icon get() = extension.icon
    }

    data class Plugin(val plugin: InstalledLNReaderPlugin) : NovelSourceItem() {
        override val key get() = "plugin:${plugin.id}"
        override val name get() = plugin.name
        override val versionLabel get() = buildString {
            append(plugin.plugin.lang).append(' ').append(plugin.plugin.version)
            plugin.availableVersion?.let { append("  →  ").append(it) }
        }
        override val hasUpdate get() = plugin.hasUpdate
        // Plugin icons are remote URLs rather than a packaged drawable, so rows load them lazily.
        override val icon: Drawable? get() = null
        val iconUrl: String? get() = plugin.plugin.iconUrl
    }
}
