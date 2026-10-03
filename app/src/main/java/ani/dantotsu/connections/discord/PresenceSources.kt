package ani.dantotsu.connections.discord

import android.content.Context
import ani.dantotsu.connections.IdCache
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.kitsu.Kitsu
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.simkl.Simkl
import ani.dantotsu.media.Media
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.BaseSources
import ani.dantotsu.parsers.DynamicAnimeParser
import ani.dantotsu.parsers.DynamicMangaParser
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.parsers.novel.lnreader.LNReaderParser
import ani.dantotsu.parsers.novel.lnreader.LNReaderPluginManager
import ani.dantotsu.parsers.novel.lnreader.LNReaderSession
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The [RPC.Source] for media from each site — its page, the site's icon, and the user's profile
 * there — so screens describe where their media comes from and [RPCManager] dresses it.
 */
object PresenceSources {

    /** What the media is, for the button — "View Novel on AniList". */
    fun noun(isAnime: Boolean, isNovel: Boolean = false): String = when {
        isAnime -> "Anime"
        isNovel -> "Novel"
        else -> "Manga"
    }

    /** AniList files novels under /manga/, like MAL; [isAnime] picks the path, [noun] the label. */
    fun anilist(id: Int, isAnime: Boolean, noun: String = noun(isAnime)) = RPC.Source(
        RPC.Site.ANILIST, "https://anilist.co/${if (isAnime) "anime" else "manga"}/$id/", noun = noun
    )

    fun mal(id: Int, isAnime: Boolean, noun: String = noun(isAnime)) = RPC.Source(
        RPC.Site.MAL, "https://myanimelist.net/${if (isAnime) "anime" else "manga"}/$id", noun = noun
    )

    fun mangaUpdates(seriesId: Long, noun: String = "Manga") = RPC.Source(
        RPC.Site.MANGAUPDATES, "https://www.mangaupdates.com/series/${seriesId.toString(36)}", noun = noun
    )

    fun kitsu(idOrSlug: String, isAnime: Boolean, noun: String = noun(isAnime)) = RPC.Source(
        RPC.Site.KITSU, "${Kitsu.WEB_URL}/${if (isAnime) "anime" else "manga"}/$idOrSlug", noun = noun
    )

    fun simkl(id: Long) = RPC.Source(RPC.Site.SIMKL, "${Simkl.WEB_URL}/anime/$id", noun = "Anime")

    fun mangaBaka(seriesId: Long, noun: String = "Manga") =
        RPC.Source(RPC.Site.MANGABAKA, "${MangaBaka.WEB_URL}/series/$seriesId", noun = noun)

    fun comick(slug: String, mediaType: String, noun: String) =
        RPC.Source(RPC.Site.COMICK, ComickApi.webUrl(slug, mediaType), noun = noun)

    /** An extension's entry: named after its source, with the extension's icon from its repo. */
    fun extension(name: String, url: String?, iconUrl: String?, noun: String) =
        RPC.Source(RPC.Site.EXTENSION, url, name, iconUrl, noun)

    /**
     * Where a [Media] in the player or reader comes from: a MangaUpdates series, an AniList entry,
     * or — an extension-only entry, negative id — the extension it was found through.
     */
    suspend fun forMedia(media: Media, sources: BaseSources? = null): RPC.Source? {
        val isAnime = media.anime != null
        val noun = noun(isAnime, isNovel = media.format == "NOVEL")
        media.muSeriesId?.let { return mangaUpdates(it, noun) }
        if (media.id >= 0) return anilist(media.id, isAnime, noun)
        return if (isAnime) animeExtension(media, sources) else mangaExtension(media, sources, noun)
    }

    /**
     * The extension [media] is read or watched through, from [sources] — the screen's own list,
     * which is not always the default one: adult titles use a separate list, and a chapter opened
     * from an extension's page brings a list holding just that extension.
     *
     * The entry's address is [Media.shareLink] when the screen that built the media set it — an
     * extension page does, and it is the only answer for media that never went through a source
     * search — else the series record saved when the source was matched.
     */
    private suspend fun animeExtension(media: Media, sources: BaseSources?): RPC.Source? =
        withContext(Dispatchers.IO) {
            runCatching {
                val parser = media.selected?.sourceIndex?.let { (sources ?: AnimeSources)[it] }
                    as? DynamicAnimeParser ?: return@runCatching null
                val url = media.shareLink?.takeIf { it.startsWith("http") }
                    ?: parser.loadSavedShowResponse(media.id)?.sAnime?.let { sAnime ->
                        (parser.extension.sources.getOrNull(parser.sourceLanguage) as? AnimeHttpSource)
                            ?.getAnimeUrl(sAnime)
                    }
                extension(parser.extension.name, url, extensionIcon(parser.extension.pkgName, isAnime = true), "Anime")
            }.onFailure { Logger.log("PresenceSources: anime extension lookup failed — ${it.message}") }
                .getOrNull()
        }

    /** As [animeExtension], for manga. */
    private suspend fun mangaExtension(media: Media, sources: BaseSources?, noun: String): RPC.Source? =
        withContext(Dispatchers.IO) {
            runCatching {
                val parser = media.selected?.sourceIndex?.let { (sources ?: MangaSources)[it] }
                    as? DynamicMangaParser ?: return@runCatching null
                val url = media.shareLink?.takeIf { it.startsWith("http") }
                    ?: parser.loadSavedShowResponse(media.id)?.sManga?.let { sManga ->
                        (parser.extension.sources.getOrNull(parser.sourceLanguage) as? HttpSource)
                            ?.getMangaUrl(sManga)
                    }
                extension(parser.extension.name, url, extensionIcon(parser.extension.pkgName, isAnime = false), noun)
            }.onFailure { Logger.log("PresenceSources: manga extension lookup failed — ${it.message}") }
                .getOrNull()
        }

    /**
     * An entry on an installed extension's source, by package: what the extension info page shows.
     * [novelParser] reuses a parser the page has already built for the plugin.
     */
    suspend fun extensionEntry(
        context: Context,
        pkg: String,
        langIndex: Int,
        manga: SManga?,
        anime: SAnime?,
        novelLink: String?,
        novelParser: LNReaderParser? = null,
    ): RPC.Source? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                anime != null -> {
                    val ext = Injekt.get<AnimeExtensionManager>().installedExtensionsFlow.value
                        .firstOrNull { it.pkgName == pkg } ?: return@runCatching null
                    val url = (ext.sources.getOrNull(langIndex) as? AnimeHttpSource)?.getAnimeUrl(anime)
                    extension(ext.name, url, extensionIcon(pkg, isAnime = true), "Anime")
                }
                manga != null -> {
                    val ext = Injekt.get<MangaExtensionManager>().installedExtensionsFlow.value
                        .firstOrNull { it.pkgName == pkg } ?: return@runCatching null
                    val url = (ext.sources.getOrNull(langIndex) as? HttpSource)?.getMangaUrl(manga)
                    extension(ext.name, url, extensionIcon(pkg, isAnime = false), "Manga")
                }
                novelLink != null -> {
                    val installed = Injekt.get<LNReaderPluginManager>().installedPluginsFlow.value
                        .firstOrNull { it.id == pkg } ?: return@runCatching null
                    val parser = novelParser ?: LNReaderParser(context.applicationContext, installed)
                    val url = runCatching { parser.resolveUrl(novelLink, isNovel = true) }.getOrNull()
                    extension(installed.name, url, installed.plugin.iconUrl, "Novel")
                }
                else -> null
            }
        }.onFailure { Logger.log("PresenceSources: extension entry lookup failed — ${it.message}") }
            .getOrNull()
    }

    /** The novel plugin the current [LNReaderSession] reads through, and the novel's page on it. */
    suspend fun novelPlugin(): RPC.Source? = withContext(Dispatchers.IO) {
        val parser = LNReaderSession.parser ?: return@withContext null
        val url = LNReaderSession.novel?.path?.let { path ->
            runCatching { parser.resolveUrl(path, isNovel = true) }.getOrNull()
        }
        extension(parser.plugin.name, url, parser.plugin.plugin.iconUrl, "Novel")
    }

    /**
     * An extension's icon: the PNG its repo hosts, found by package among the repo listings the
     * extension screens load. Kept once found, so a session that hasn't opened those screens yet
     * still has it. Null for an extension no added repo lists.
     */
    fun extensionIcon(pkg: String, isAnime: Boolean): String? {
        val key = "discord_ext_icon_$pkg"
        val listed = runCatching {
            if (isAnime) Injekt.get<AnimeExtensionManager>().availableExtensionsFlow.value
                .firstOrNull { it.pkgName == pkg }?.iconUrl
            else Injekt.get<MangaExtensionManager>().availableExtensionsFlow.value
                .firstOrNull { it.pkgName == pkg }?.iconUrl
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (listed != null) {
            IdCache.put(key, listed)
            return listed
        }
        return IdCache[key]?.takeIf { it.isNotBlank() }
    }

    /** The small icon for [source], as (image, hover text); null where there's none to show. */
    fun icon(source: RPC.Source): Pair<String, String>? = when (source.site) {
        RPC.Site.ANILIST -> Discord.small_Image_AniList to source.name
        RPC.Site.MAL -> Discord.small_Image_MAL to source.name
        RPC.Site.MANGAUPDATES -> Discord.small_Image_MangaUpdates to source.name
        RPC.Site.KITSU -> Discord.small_Image_Kitsu to source.name
        RPC.Site.SIMKL -> Discord.small_Image_Simkl to source.name
        RPC.Site.MANGABAKA -> Discord.small_Image_MangaBaka to source.name
        RPC.Site.COMICK -> Discord.small_Image_Comick to source.name
        RPC.Site.EXTENSION -> source.iconUrl?.let { it to source.name }
    }

    /**
     * The user's profile on [site], when logged in there. Comick has no profile to link, and an
     * extension's source knows nothing of the user.
     */
    fun profileUrl(site: RPC.Site): String? = when (site) {
        RPC.Site.ANILIST -> PrefManager.getVal(PrefName.AnilistUserName, "")
            .takeIf { it.isNotBlank() }?.let { "https://anilist.co/user/$it/" }
        RPC.Site.MAL -> (MAL.username ?: PrefManager.getVal(PrefName.MALUserName, ""))
            .takeIf { it.isNotBlank() }?.let { "https://myanimelist.net/profile/$it" }
        RPC.Site.MANGAUPDATES -> MangaUpdates.username
            ?.takeIf { it.isNotBlank() }?.let { "https://www.mangaupdates.com/users/$it" }
        RPC.Site.KITSU -> Kitsu.slug?.takeIf { it.isNotBlank() }?.let { "${Kitsu.WEB_URL}/users/$it" }
        RPC.Site.SIMKL -> Simkl.userid?.toString()?.takeIf { it.isNotBlank() }?.let { "${Simkl.WEB_URL}/$it/dashboard" }
        RPC.Site.MANGABAKA -> MangaBaka.username?.takeIf { it.isNotBlank() }?.let { "${MangaBaka.WEB_URL}/u/$it" }
        RPC.Site.COMICK, RPC.Site.EXTENSION -> null
    }
}
