package ani.dantotsu.parsers

import ani.dantotsu.Lazier
import ani.dantotsu.currContext
import ani.dantotsu.parsers.novel.DynamicNovelParser
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.parsers.novel.lnreader.InstalledLNReaderPlugin
import ani.dantotsu.parsers.novel.lnreader.LNReaderParser
import ani.dantotsu.parsers.novel.lnreader.LNReaderPluginManager
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The novel source list, which carries two unrelated kinds of source.
 *
 * [DynamicNovelParser] wraps an installed `some.random.*` extension APK, whose novels are sets of
 * downloadable EPUB volumes. [LNReaderParser] wraps an LNReader JavaScript plugin, whose novels are
 * chapter lists of fetched HTML. They share [NovelParser] only for search; anything past that has
 * to branch, so callers check the parser type rather than assuming a single content model.
 */
object NovelSources : NovelReadSources() {
    override var list: List<Lazier<BaseParser>> = emptyList()
    var pinnedNovelSources: List<String> = emptyList()

    suspend fun init(
        fromExtensions: StateFlow<List<NovelExtension.Installed>>,
        fromPlugins: StateFlow<List<InstalledLNReaderPlugin>>,
    ) {
        pinnedNovelSources =
            PrefManager.getNullableVal<List<String>>(PrefName.NovelSourcesOrder, null)
                ?: emptyList()

        list = build(fromExtensions.first(), fromPlugins.first())

        combine(fromExtensions, fromPlugins) { extensions, plugins ->
            extensions to plugins
        }.collect { (extensions, plugins) ->
            list = build(extensions, plugins)
        }
    }

    private fun build(
        extensions: List<NovelExtension.Installed>,
        plugins: List<InstalledLNReaderPlugin>,
    ): List<Lazier<BaseParser>> = sortPinnedNovelSources(
        createParsersFromExtensions(extensions) + createParsersFromPlugins(plugins),
        pinnedNovelSources
    ) + Lazier({ OfflineNovelParser() }, "Downloaded")

    fun performReorderNovelSources() {
        //remove the downloaded source from the list to avoid duplicates
        list = list.filter { it.name != "Downloaded" }
        list = sortPinnedNovelSources(list, pinnedNovelSources) + Lazier(
            { OfflineNovelParser() },
            "Downloaded"
        )
    }

    private fun createParsersFromExtensions(extensions: List<NovelExtension.Installed>): List<Lazier<BaseParser>> {
        return extensions.map { extension ->
            Lazier({ DynamicNovelParser(extension) }, extension.name)
        }
    }

    /**
     * The context is resolved inside the lambda, not here: this runs during startup, and reading it
     * eagerly would drop every plugin from the list if it happened before the app context was set,
     * with nothing to trigger a rebuild afterwards. By the time a parser is first used there is
     * always one.
     */
    private fun createParsersFromPlugins(plugins: List<InstalledLNReaderPlugin>): List<Lazier<BaseParser>> =
        plugins.map { plugin ->
            Lazier(
                {
                    val context = currContext()
                        ?: throw IllegalStateException("No context available for ${plugin.name}")
                    LNReaderParser(context.applicationContext, plugin)
                },
                plugin.name
            )
        }

    /** Whether the source at [index] reads chapters of HTML rather than downloadable volumes. */
    fun isLNReader(index: Int): Boolean =
        list.getOrNull(index)?.get?.value is LNReaderParser

    fun lnReaderAt(index: Int): LNReaderParser? =
        list.getOrNull(index)?.get?.value as? LNReaderParser

    private fun sortPinnedNovelSources(
        parsers: List<Lazier<BaseParser>>,
        pinnedSources: List<String>
    ): List<Lazier<BaseParser>> {
        val pinnedSourcesMap = parsers.filter { pinnedSources.contains(it.name) }
            .associateBy { it.name }
        val orderedPinnedSources = pinnedSources.mapNotNull { name ->
            pinnedSourcesMap[name]
        }
        val unpinnedSources = parsers.filterNot { pinnedSources.contains(it.name) }
        return orderedPinnedSources + unpinnedSources
    }
}
