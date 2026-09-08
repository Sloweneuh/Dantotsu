package ani.dantotsu.media.novel.translation

import android.content.Context
import ani.dantotsu.util.Logger
import java.io.File
import java.security.MessageDigest

/**
 * Translated chapters, kept on disk so a chapter is only ever translated once.
 *
 * The reason is the same one the manga side caches for, and it is about consistency before it is
 * about quota: a language model does not return the same words twice, so a chapter re-read without
 * this would be worded differently every time through — names rendered one way and then another,
 * a line of dialogue you remember reading coming back subtly changed. Keeping the first answer is
 * what makes a novel read like one book.
 *
 * On disk rather than in memory, because the unit here is a whole chapter and the natural way to
 * read a novel is over days. An in-memory cache would be empty every one of those days.
 *
 * **Not in `lnreader_chapters`.** That directory holds the packaged EPUBs, which are disposable —
 * every one can be rebuilt from the plugin — and [ani.dantotsu.App] empties it on every launch. A
 * translation cannot be rebuilt for free, so it lives somewhere that survives that.
 */
object NovelTranslationCache {

    private const val DIRECTORY = "lnreader_mtl"

    /**
     * How much translated text is kept, before the least recently read is dropped.
     *
     * A chapter is tens of kilobytes of HTML, so this is hundreds of them — far more than anybody
     * has open at once and still small enough to sit in a cache directory without comment. It is
     * `cacheDir`, so the system may take it back under storage pressure; that costs a re-translation
     * and nothing else.
     */
    private const val MAX_BYTES = 32L * 1024 * 1024

    /** The stored translation for [key], or null where there is none. */
    fun get(context: Context, key: String): String? = runCatching {
        val file = fileFor(context, key)
        if (!file.exists()) return null
        // Read counts as use, so eviction drops what has not been opened rather than what was
        // translated longest ago — a novel read from the start would otherwise evict its own
        // beginning first, which is exactly the part a re-read returns to.
        file.setLastModified(System.currentTimeMillis())
        file.readText()
    }.getOrElse {
        Logger.log("Novel MTL cache read failed: ${it.message}")
        null
    }

    fun put(context: Context, key: String, html: String) {
        runCatching {
            val file = fileFor(context, key)
            // Written beside and moved into place: a chapter interrupted halfway through writing
            // would otherwise be cached as a truncated document and served as the real thing for
            // as long as it survived.
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(html)
            if (file.exists()) file.delete()
            if (!temporary.renameTo(file)) temporary.delete()
            evict(context)
        }.onFailure { Logger.log("Novel MTL cache write failed: ${it.message}") }
    }

    /** Drops everything, for a caller that wants the next read re-translated. */
    fun clear(context: Context) {
        runCatching { directory(context).listFiles()?.forEach { it.delete() } }
    }

    /** Oldest-read first, until the directory is back inside its budget. */
    private fun evict(context: Context) {
        val files = directory(context).listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_BYTES) return
            total -= file.length()
            file.delete()
        }
    }

    /**
     * Where one key's translation lives.
     *
     * Hashed rather than used directly: a key carries a chapter's path from an arbitrary site, and
     * those contain slashes, query strings and characters no filesystem accepts.
     */
    private fun fileFor(context: Context, key: String): File =
        File(directory(context), hash(key))

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun directory(context: Context) =
        File(context.cacheDir, DIRECTORY).apply { if (!exists()) mkdirs() }
}
