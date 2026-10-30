package ani.dantotsu.tv

import android.content.Context
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.media.Media
import ani.dantotsu.util.Logger
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * The TV home's season and chart rows, kept on disk for [MAX_AGE_MS]. They barely change within a
 * day, yet each launch asked AniList for all of them again — six of the 30 requests a minute it
 * allows. The user's own rows (watching, planned) aren't kept here: they're fetched every time.
 *
 * Kept per account and per adult setting, since both change what the rows hold.
 */
internal object TvRowCache {

    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    private fun file(context: Context, key: String): File {
        val owner = "${Anilist.userid ?: 0}_${Anilist.adult}"
        return File(File(context.cacheDir, "tv_rows"), "${owner}_$key")
    }

    /** The stored row, or null when there is none or it's older than [MAX_AGE_MS]. */
    fun read(context: Context, key: String): List<Media>? {
        val file = file(context, key)
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) return null
        return try {
            ObjectInputStream(file.inputStream().buffered()).use { input ->
                @Suppress("UNCHECKED_CAST")
                (input.readObject() as? ArrayList<Media>)?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            // Written by an older build whose Media no longer reads back: fetched again instead.
            Logger.log("TvRowCache: couldn't read $key: ${e.message}")
            file.delete()
            null
        }
    }

    fun write(context: Context, key: String, row: List<Media>) {
        if (row.isEmpty()) return
        val file = file(context, key)
        try {
            file.parentFile?.mkdirs()
            ObjectOutputStream(file.outputStream().buffered()).use { it.writeObject(ArrayList(row)) }
        } catch (e: Exception) {
            Logger.log("TvRowCache: couldn't write $key: ${e.message}")
            file.delete()
        }
    }

    /** [key]'s row from disk when it's fresh enough, otherwise from [fetch] (and stored). */
    suspend fun get(context: Context, key: String, fetch: suspend () -> List<Media>?): List<Media>? =
        read(context, key) ?: fetch()?.also { write(context, key, it) }
}
