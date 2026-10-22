package ani.dantotsu.media.novel.novelreader

import ani.dantotsu.settings.saving.PrefManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A place in a novel the reader asked to keep.
 *
 * [cfi] is the page's position in its book. A streamed (LNReader) novel is read one chapter-book at
 * a time, so [chapterIndex] says which chapter of the session that book is; -1 for a downloaded
 * book, where every chapter is in the one book and the position alone is enough.
 */
@Serializable
data class NovelBookmark(
    val cfi: String,
    val chapterIndex: Int = -1,
    val chapterTitle: String = "",
    val excerpt: String = "",
    val createdAt: Long = 0L,
)

/**
 * The bookmarks of each novel, under the same id its reader settings use: per novel for a
 * streamed one, per book otherwise. Kept on this device.
 */
object NovelBookmarks {

    private val json = Json { ignoreUnknownKeys = true }

    private fun key(scope: String) = "novel_bookmarks-$scope"

    fun list(scope: String): List<NovelBookmark> {
        val stored = PrefManager.getNullableCustomVal(key(scope), null, String::class.java)
            ?: return emptyList()
        return runCatching { json.decodeFromString<List<NovelBookmark>>(stored) }.getOrDefault(emptyList())
    }

    private fun save(scope: String, bookmarks: List<NovelBookmark>) {
        if (bookmarks.isEmpty()) PrefManager.removeCustomVal(key(scope))
        else PrefManager.setCustomVal(key(scope), json.encodeToString(bookmarks))
    }

    /** Newest first. */
    fun add(scope: String, bookmark: NovelBookmark) =
        save(scope, listOf(bookmark) + list(scope).filterNot { it.sameAs(bookmark) })

    fun remove(scope: String, bookmark: NovelBookmark) =
        save(scope, list(scope).filterNot { it.sameAs(bookmark) })

    /** The bookmarks in chapter [chapterIndex] — the ones the open book can place. */
    fun inChapter(scope: String, chapterIndex: Int) = list(scope).filter { it.chapterIndex == chapterIndex }

    private fun NovelBookmark.sameAs(other: NovelBookmark) =
        cfi == other.cfi && chapterIndex == other.chapterIndex
}
