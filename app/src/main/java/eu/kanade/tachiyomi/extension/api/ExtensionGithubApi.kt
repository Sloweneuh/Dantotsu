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

/**
 * Result of fetching every repository configured for a media type.
 *
 * A repo that fails to fetch, or does not actually parse as an extension list (a dead link, or a
 * page url like a GitHub file-viewer link rather than its raw index), is skipped rather than
 * failing the whole refresh, so one bad entry cannot hide every other repository's extensions. But
 * that also means an empty match for a package can mean either "removed upstream" or "its repo did
 * not resolve this time" — callers need [allReposResolved] to tell those apart before treating an
 * installed extension as obsolete.
 */
internal data class RepoFetchResult<T>(
    val extensions: List<T>,
    val allReposResolved: Boolean,
)

internal class ExtensionGithubApi {
    private val networkService: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    private val fetcher by lazy { ExtensionRepoFetcher(networkService.client, json) }

    private suspend fun <T> findExtensions(
        repos: PrefName,
        transform: (RepoEntry, String) -> T?,
    ): RepoFetchResult<T> = withIOContext {
        val perRepo = PrefManager.getVal<Set<String>>(repos).asyncMap { repo ->
            try {
                fetcher.fetch(repo).mapNotNull { transform(it, repo) } to true
            } catch (e: Throwable) {
                Logger.log("Failed to get extensions from $repo")
                Logger.log(e)
                emptyList<T>() to false
            }
        }
        RepoFetchResult(
            extensions = perRepo.flatMap { it.first },
            allReposResolved = perRepo.all { it.second },
        )
    }

    suspend fun findAnimeExtensions(): RepoFetchResult<AnimeExtension.Available> =
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

    suspend fun findMangaExtensions(): RepoFetchResult<MangaExtension.Available> =
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

    suspend fun findNovelExtensions(): RepoFetchResult<NovelExtension.Available> =
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
