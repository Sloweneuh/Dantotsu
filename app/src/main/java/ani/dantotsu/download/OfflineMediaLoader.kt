package ani.dantotsu.download

import android.content.Context
import android.net.Uri
import ani.dantotsu.download.DownloadsManager.Companion.getSubDirectory
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaType
import ani.dantotsu.util.Logger
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl

/** Locally-saved title + cover for a downloaded media (renders with no network). */
data class OfflineMediaMeta(val title: String, val coverUri: Uri?)

/**
 * Loads a downloaded media's title and cover from its local `media.json` + `cover.jpg`,
 * so the download queue/management UI renders correctly offline. Mirrors the approach in
 * `OfflineMangaFragment.getMedia`/`OfflineAnimeFragment`.
 */
object OfflineMediaLoader {
    private val gson = GsonBuilder()
        .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> { SChapterImpl() })
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
}
