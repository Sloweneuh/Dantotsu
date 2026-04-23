package ani.dantotsu.settings

import ani.dantotsu.media.Selected
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.DynamicAnimeParser
import ani.dantotsu.parsers.DynamicMangaParser
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.source.model.SManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object ExtensionMediaLinker {

    fun linkMangaMedia(
        mediaId: Int,
        extensionPkg: String,
        langIndex: Int,
        sManga: SManga,
    ): Boolean {
        val mgr: MangaExtensionManager = Injekt.get()
        val ext = mgr.installedExtensionsFlow.value.find { it.pkgName == extensionPkg }
            ?: return false
        val sourceIndex = MangaSources.list.indexOfFirst { it.name == ext.name }
        if (sourceIndex < 0) return false

        (MangaSources.list[sourceIndex].get.value as? DynamicMangaParser)
            ?.sourceLanguage = langIndex

        val selected = Selected().apply {
            this.sourceIndex = sourceIndex
            this.langIndex = langIndex
            preferDub = PrefManager.getVal(PrefName.SettingsPreferDub)
        }
        PrefManager.setCustomVal("Selected-$mediaId", selected)

        val response = ShowResponse(
            name = sManga.title,
            link = sManga.url,
            coverUrl = sManga.thumbnail_url ?: "",
            sManga = sManga,
        )
        PrefManager.setCustomVal("${ext.name}_$mediaId", response)
        return true
    }

    fun linkAnimeMedia(
        mediaId: Int,
        extensionPkg: String,
        langIndex: Int,
        sAnime: SAnime,
    ): Boolean {
        val mgr: AnimeExtensionManager = Injekt.get()
        val ext = mgr.installedExtensionsFlow.value.find { it.pkgName == extensionPkg }
            ?: return false
        val sourceIndex = AnimeSources.list.indexOfFirst { it.name == ext.name }
        if (sourceIndex < 0) return false

        (AnimeSources.list[sourceIndex].get.value as? DynamicAnimeParser)
            ?.sourceLanguage = langIndex

        val selected = Selected().apply {
            this.sourceIndex = sourceIndex
            this.langIndex = langIndex
            preferDub = PrefManager.getVal(PrefName.SettingsPreferDub)
        }
        PrefManager.setCustomVal("Selected-$mediaId", selected)

        val response = ShowResponse(
            name = sAnime.title,
            link = sAnime.url,
            coverUrl = sAnime.thumbnail_url ?: "",
            sAnime = sAnime,
        )
        PrefManager.setCustomVal("${ext.name}_$mediaId", response)
        return true
    }
}
