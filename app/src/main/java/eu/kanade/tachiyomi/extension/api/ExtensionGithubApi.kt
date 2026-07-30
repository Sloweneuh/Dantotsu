package eu.kanade.tachiyomi.extension.api

import ani.dantotsu.asyncMap
import ani.dantotsu.parsers.novel.AvailableNovelSources
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AvailableAnimeSources
import eu.kanade.tachiyomi.extension.manga.model.AvailableMangaSources
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import tachiyomi.core.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy

internal class ExtensionGithubApi {
    private val networkService: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    private val fetcher by lazy { ExtensionRepoFetcher(networkService.client, json) }

    private suspend fun <T> findExtensions(
        repos: PrefName,
        transform: (RepoEntry, String) -> T?,
    ): List<T> = withIOContext {
        PrefManager.getVal<Set<String>>(repos).asyncMap { repo ->
            try {
                fetcher.fetch(repo).mapNotNull { transform(it, repo) }
            } catch (e: Throwable) {
                Logger.log("Failed to get extensions from $repo")
                Logger.log(e)
                emptyList()
            }
        }.flatten()
    }

    suspend fun findAnimeExtensions(): List<AnimeExtension.Available> =
        findExtensions(PrefName.AnimeExtensionRepos) { entry, repo ->
            if (entry.libVersion < ExtensionLoader.ANIME_LIB_VERSION_MIN ||
                entry.libVersion > ExtensionLoader.ANIME_LIB_VERSION_MAX
            ) return@findExtensions null

            AnimeExtension.Available(
                name = entry.name.substringAfter("Aniyomi: "),
                pkgName = entry.pkgName,
                versionName = entry.versionName,
                versionCode = entry.versionCode,
                libVersion = entry.libVersion,
                lang = entry.lang,
                isNsfw = entry.isNsfw,
                hasReadme = entry.hasReadme,
                hasChangelog = entry.hasChangelog,
                sources = entry.sources.map {
                    AvailableAnimeSources(it.id, it.lang, it.name, it.baseUrl)
                },
                apkName = entry.apkName,
                apkUrl = entry.apkUrl,
                iconUrl = entry.iconUrl,
                repository = repo,
            )
        }

    suspend fun findMangaExtensions(): List<MangaExtension.Available> =
        findExtensions(PrefName.MangaExtensionRepos) { entry, repo ->
            if (entry.libVersion < ExtensionLoader.MANGA_LIB_VERSION_MIN ||
                entry.libVersion > ExtensionLoader.MANGA_LIB_VERSION_MAX
            ) return@findExtensions null

            MangaExtension.Available(
                name = entry.name.substringAfter("Tachiyomi: "),
                pkgName = entry.pkgName,
                versionName = entry.versionName,
                versionCode = entry.versionCode,
                libVersion = entry.libVersion,
                lang = entry.lang,
                isNsfw = entry.isNsfw,
                hasReadme = entry.hasReadme,
                hasChangelog = entry.hasChangelog,
                sources = entry.sources.map {
                    AvailableMangaSources(it.id, it.lang, it.name, it.baseUrl)
                },
                apkName = entry.apkName,
                apkUrl = entry.apkUrl,
                iconUrl = entry.iconUrl,
                repository = repo,
            )
        }

    suspend fun findNovelExtensions(): List<NovelExtension.Available> =
        findExtensions(PrefName.NovelExtensionRepos) { entry, repo ->
            NovelExtension.Available(
                name = entry.name,
                pkgName = entry.pkgName,
                versionName = entry.versionName,
                versionCode = entry.versionCode,
                repository = repo,
                sources = entry.sources.map {
                    AvailableNovelSources(it.id, it.lang, it.name, it.baseUrl)
                },
                iconUrl = entry.iconUrl,
                apkUrl = entry.apkUrl,
            )
        }

    fun getAnimeApkUrl(extension: AnimeExtension.Available): String =
        extension.apkUrl ?: "${extension.repository.indexDirUrl()}/apk/${extension.apkName}"

    fun getMangaApkUrl(extension: MangaExtension.Available): String =
        extension.apkUrl ?: "${extension.repository.indexDirUrl()}/apk/${extension.apkName}"

    fun getNovelApkUrl(extension: NovelExtension.Available): String =
        extension.apkUrl ?: "${extension.repository.indexDirUrl()}/apk/${extension.pkgName}.apk"
}

private fun String.indexDirUrl(): String = removeSuffix("/")
    .removeSuffix("/index.min.json")
    .removeSuffix("/index.json")
    .removeSuffix("/index.pb")
    .removeSuffix("/")
