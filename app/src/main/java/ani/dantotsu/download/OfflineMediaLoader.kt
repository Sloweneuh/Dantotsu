package ani.dantotsu.download

import android.content.Context
import android.net.Uri
import ani.dantotsu.download.DownloadsManager.Companion.getSubDirectory
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaType
import ani.dantotsu.util.Logger
import com.anggrayudi.storage.file.openInputStream
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Locally-saved title + cover for a downloaded media (renders with no network). */
data class OfflineMediaMeta(val title: String, val coverUri: Uri?)

/**
 * Loads downloaded media from their local `media.json` + `cover.jpg`, so the offline home,
 * list, and download-management UI render correctly with no network.
 */
object OfflineMediaLoader {
    private val gson = GsonBuilder()
        .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> { SChapterImpl() })
        .create()

    /** Full parser that also understands anime `media.json` (episodes + anime source models). */
    private val fullGson = GsonBuilder()
        .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> { SChapterImpl() })
        .registerTypeAdapter(SAnime::class.java, InstanceCreator<SAnime> { SAnimeImpl() })
        .registerTypeAdapter(SEpisode::class.java, InstanceCreator<SEpisode> { SEpisodeImpl() })
        .create()

    fun load(context: Context, type: MediaType, titleName: String): OfflineMediaMeta {
        val dir = getSubDirectory(context, type, false, titleName)
        val cover = dir?.findFile("cover.jpg")?.takeIf { it.exists() }?.uri
        val title = try {
            dir?.findFile("media.json")?.let { mj ->
                context.contentResolver.openInputStream(mj.uri)?.use {
                    val json = it.readBytes().toString(Charsets.UTF_8)
                    gson.fromJson(json, Media::class.java)?.mainName()
                }
            }
        } catch (e: Exception) {
            Logger.log("Failed to read offline media meta: ${e.message}")
            null
        } ?: titleName
        return OfflineMediaMeta(title, cover)
    }

    /**
     * Loads every downloaded title of the given kind as full [Media] objects (parsed from each
     * title's `media.json`), for use with the standard home/list adapters while offline.
     * @param anime true for downloaded anime, false for downloaded manga + novels.
     */
    fun loadDownloadedMediaList(context: Context, anime: Boolean): ArrayList<Media> {
        val downloadManager = Injekt.get<DownloadsManager>()
        val types = if (anime) {
            downloadManager.animeDownloadedTypes
        } else {
            downloadManager.mangaDownloadedTypes + downloadManager.novelDownloadedTypes
        }
        val result = ArrayList<Media>()
        val seenTitles = types.map { it.titleName.findValidName() }.distinct()
        for (title in seenTitles) {
            val download = types.firstOrNull { it.titleName.findValidName() == title } ?: continue
            val media = loadMedia(context, download) ?: continue
            result.add(media)
        }
        return result
    }

    private fun loadMedia(context: Context, downloadedType: DownloadedType): Media? {
        return try {
            val directory = getSubDirectory(
                context, downloadedType.type, false, downloadedType.titleName
            )
            val mediaFile = directory?.findFile("media.json")
                ?: return DownloadCompat.loadMediaCompat(downloadedType)
            val mediaJson = mediaFile.openInputStream(context)?.bufferedReader().use { it?.readText() }
            fullGson.fromJson(mediaJson, Media::class.java)
        } catch (e: Exception) {
            Logger.log("Error loading media.json for ${downloadedType.titleName}: ${e.message}")
            try {
                DownloadCompat.loadMediaCompat(downloadedType)
            } catch (e2: Exception) {
                Logger.log(e2)
                null
            }
        }
    }
}
