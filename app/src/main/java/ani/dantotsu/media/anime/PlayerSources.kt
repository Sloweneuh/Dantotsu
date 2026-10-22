package ani.dantotsu.media.anime

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.defaultHeaders
import ani.dantotsu.download.DownloadsManager.Companion.getSubDirectory
import ani.dantotsu.download.video.Helper
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.SubtitleDownloader
import ani.dantotsu.okHttpClient
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.parsers.SubtitleType
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.snackString
import com.anggrayudi.storage.file.extension
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.InetAddress

/**
 * What the player plays an episode from: the network and cache stack a stream is read through,
 * its external subtitles and audio tracks as media items and sources, and a downloaded episode's
 * file. Nothing here holds the player; the activity puts the pieces together.
 */
@UnstableApi
object PlayerSources {

    /** The subtitle languages the "default subtitle language" setting picks from, in its order. */
    val SUB_LANGUAGES =
        arrayOf(
            "Albanian",
            "Arabic",
            "Bosnian",
            "Bulgarian",
            "Chinese",
            "Croatian",
            "Czech",
            "Danish",
            "Dutch",
            "English",
            "Estonian",
            "Finnish",
            "French",
            "Georgian",
            "German",
            "Greek",
            "Hebrew",
            "Hindi",
            "Indonesian",
            "Irish",
            "Italian",
            "Japanese",
            "Korean",
            "Lithuanian",
            "Luxembourgish",
            "Macedonian",
            "Mongolian",
            "Norwegian",
            "Polish",
            "Portuguese",
            "Punjabi",
            "Romanian",
            "Russian",
            "Serbian",
            "Slovak",
            "Slovenian",
            "Spanish",
            "Turkish",
            "Ukrainian",
            "Urdu",
            "Vietnamese",
        )

    /**
     * Resolves `localhost` to both loopback addresses instead of whichever one the system
     * happens to prefer.
     *
     * Some extensions don't hand back a remote URL at all — they stream through a proxy they
     * run on the device and point the player at `http://localhost:<port>/…`. Android resolves
     * that name to `127.0.0.1` alone, so when such a server binds the IPv6 wildcard (which is
     * the default for a Java `ServerSocket` on a v6-capable device) the player is refused by a
     * server that is up and answering. Offering both addresses lets OkHttp's happy-eyeballs
     * connect over whichever stack is actually listening, rather than us guessing which.
     */
    private val LoopbackAwareDns = Dns { hostname ->
        if (hostname.equals("localhost", ignoreCase = true)) {
            listOf(InetAddress.getByName("::1"), InetAddress.getByName("127.0.0.1"))
        } else {
            Dns.SYSTEM.lookup(hostname)
        }
    }

    /**
     * The data source a stream is read through: OkHttp with the extension's headers, written
     * through to the disk cache.
     */
    fun cacheFactory(context: Context, video: Video?): CacheDataSource.Factory {
        val httpClient =
            okHttpClient
                .newBuilder()
                .apply {
                    ignoreAllSSLErrors()
                    followRedirects(true)
                    followSslRedirects(true)
                    dns(LoopbackAwareDns)
                }.build()
        val httpDataSourceFactory =
            OkHttpDataSource.Factory(httpClient).apply {
                setDefaultRequestProperties(defaultHeaders)
                video?.file?.headers?.let {
                    setDefaultRequestProperties(it)
                }
            }
        val defaultDataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        // What streams in is written to the disk cache too, so seeking back past the in-memory
        // back buffer, rewatching, and re-cutting a clip read it from disk instead of downloading
        // it again. A cache that fails (disk full, a corrupt span) is bypassed, not fatal.
        val videoCache = VideoCache.getInstance(context)
        return CacheDataSource.Factory().apply {
            setCache(videoCache)
            setUpstreamDataSourceFactory(defaultDataSourceFactory)
            setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(videoCache))
            setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
    }

    /** The extension's external subtitles, as subtitle configurations for the media item. */
    fun subtitleConfigurations(
        ext: VideoExtractor,
        video: Video,
        hasExtSubtitles: Boolean,
    ): List<MediaItem.SubtitleConfiguration> {
        val sub = mutableListOf<MediaItem.SubtitleConfiguration>()
        ext.subtitles.forEach { subtitle ->
            val subtitleUrl = if (!hasExtSubtitles) video.file.url else subtitle.file.url
            if (subtitle.type == SubtitleType.UNKNOWN) {
                runBlocking {
                    val type = SubtitleDownloader.loadSubtitleType(subtitleUrl)
                    val fileUri = (subtitleUrl).toUri()
                    sub +=
                        MediaItem.SubtitleConfiguration
                            .Builder(fileUri)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .setMimeType(
                                when (type) {
                                    SubtitleType.VTT -> MimeTypes.TEXT_VTT
                                    SubtitleType.ASS -> MimeTypes.TEXT_SSA
                                    SubtitleType.SRT -> MimeTypes.APPLICATION_SUBRIP
                                    else -> MimeTypes.TEXT_UNKNOWN
                                },
                            ).setId("69")
                            .setLanguage(subtitle.language)
                            .build()
                }
            } else {
                val subUri = subtitleUrl.toUri()
                sub +=
                    MediaItem.SubtitleConfiguration
                        .Builder(subUri)
                        .setSelectionFlags(C.SELECTION_FLAG_FORCED)
                        .setMimeType(
                            when (subtitle.type) {
                                SubtitleType.VTT -> MimeTypes.TEXT_VTT
                                SubtitleType.ASS -> MimeTypes.TEXT_SSA
                                SubtitleType.SRT -> MimeTypes.APPLICATION_SUBRIP
                                else -> MimeTypes.TEXT_UNKNOWN
                            },
                        ).setId("69")
                        .setLanguage(subtitle.language)
                        .build()
            }
        }
        return sub
    }

    /**
     * The extension's separate audio tracks as media sources, one per language. [audioLanguages]
     * is refilled with each track's name and language code, in the same order.
     */
    fun audioSources(
        ext: VideoExtractor,
        cacheFactory: CacheDataSource.Factory,
        audioLanguages: MutableList<Pair<String, String>>,
    ): Array<MediaSource> {
        val audioMediaItem = mutableListOf<MediaItem>()
        audioLanguages.clear()
        ext.audioTracks.forEach {
            var code = LanguageMapper.getLanguageCode(it.lang)
            if (code == "all") code = "un"
            audioLanguages.add(Pair(it.lang, code))
            audioMediaItem.add(
                MediaItem
                    .Builder()
                    .setUri(it.url)
                    .setMimeType(MimeTypes.AUDIO_UNKNOWN)
                    .setTag(code)
                    .build(),
            )
        }

        return audioMediaItem.map { mediaItem ->
            if (mediaItem.localConfiguration?.uri.toString().contains(".m3u8")) {
                HlsMediaSource.Factory(cacheFactory).createMediaSource(mediaItem)
            } else {
                DefaultMediaSourceFactory(cacheFactory).createMediaSource(mediaItem)
            }
        }.toTypedArray()
    }

    /**
     * A downloaded episode's media item: from the download index when it was saved as a stream,
     * otherwise from its file in the downloads folder. Null, with a message, when neither is there.
     */
    fun downloadedItem(context: Context, ext: VideoExtractor): MediaItem? {
        val titleName =
            ext.server.name
                .split("/")
                .first()
        val episodeName =
            ext.server.name
                .split("/")
                .last()
        val downloadId = PrefManager
            .getAnimeDownloadPreferences()
            .getString("$titleName - $episodeName", null)
            ?: PrefManager
                .getAnimeDownloadPreferences()
                .getString(ext.server.name, null)
        val exoItem =
            if (downloadId != null) {
                Helper
                    .downloadManager(context)
                    .downloadIndex
                    .getDownload(downloadId)
                    ?.request
                    ?.toMediaItem()
            } else {
                null
            }
        if (exoItem != null) return exoItem
        return run {
            val directory =
                getSubDirectory(context, MediaType.ANIME, false, titleName, episodeName)
            if (directory != null) {
                val files = directory.listFiles()
                println(files)
                val docFile =
                    directory.listFiles().firstOrNull {
                        it.name?.endsWith(".mp4") == true ||
                                it.name?.endsWith(".mkv") == true ||
                                it.name?.endsWith(
                                    ".${
                                        Injekt
                                            .get<DownloadAddonManager>()
                                            .extension
                                            ?.extension
                                            ?.getFileExtension()
                                            ?.first ?: "ts"
                                    }",
                                ) ==
                                true
                    }
                if (docFile != null) {
                    val uri = docFile.uri
                    val downloadedMimeType =
                        when (docFile.extension) {
                            "mp4" -> MimeTypes.APPLICATION_MP4
                            "mkv" -> MimeTypes.APPLICATION_MATROSKA
                            else -> MimeTypes.APPLICATION_MP4
                        }
                    MediaItem
                        .Builder()
                        .setUri(uri)
                        .setMimeType(downloadedMimeType)
                        .build()
                } else {
                    snackString("File not found")
                    null
                }
            } else {
                snackString("Directory not found")
                null
            }
        }
    }
}
