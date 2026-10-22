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
 * The bookmarks of each novel, under the same id its reader settings use ([scope]: per novel for a
 * streamed one, per book otherwise).
 *
 * Filed under the media when there is one — `lnreader_bm-<mediaId>-<scope>` — which is what lets
 * [ani.dantotsu.connections.sync.ProgressSync] carry them to the user's other devices, as it does
 * reading positions. A book with no media to file under keeps them on this device only.
 */
object NovelBookmarks {

    class Owner(val mediaId: Int?, val scope: String)

    private val json = Json { ignoreUnknownKeys = true }

    private fun localKey(scope: String) = "novel_bookmarks-$scope"

    private fun key(owner: Owner) =
        owner.mediaId?.takeIf { it >= 0 }?.let { "lnreader_bm-$it-${owner.scope}" } ?: localKey(owner.scope)

    private fun read(key: String): List<NovelBookmark>? =
        PrefManager.getNullableCustomVal(key, null, String::class.java)
            ?.let { runCatching { json.decodeFromString<List<NovelBookmark>>(it) }.getOrNull() }

    fun list(owner: Owner): List<NovelBookmark> {
        val key = key(owner)
        read(key)?.let { return it }
        // Made before bookmarks were filed under the media: moved there the first time they are read.
        val local = localKey(owner.scope)
        if (key == local) return emptyList()
        val earlier = read(local) ?: return emptyList()
        save(owner, earlier)
        PrefManager.removeCustomVal(local)
        return earlier
    }

    /**
     * An emptied list is stored as one rather than removed when it syncs, so deleting the last
     * bookmark reaches the other devices too: sync carries what is there, and a key that is simply
     * gone says nothing.
     */
    private fun save(owner: Owner, bookmarks: List<NovelBookmark>) {
        val key = key(owner)
        if (bookmarks.isEmpty() && key == localKey(owner.scope)) PrefManager.removeCustomVal(key)
        else PrefManager.setCustomVal(key, json.encodeToString(bookmarks))
    }

    /** Newest first. */
    fun add(owner: Owner, bookmark: NovelBookmark) =
        save(owner, listOf(bookmark) + list(owner).filterNot { it.sameAs(bookmark) })

    fun remove(owner: Owner, bookmark: NovelBookmark) =
        save(owner, list(owner).filterNot { it.sameAs(bookmark) })

    /** The bookmarks in chapter [chapterIndex] — the ones the open book can place. */
    fun inChapter(owner: Owner, chapterIndex: Int) = list(owner).filter { it.chapterIndex == chapterIndex }

    private fun NovelBookmark.sameAs(other: NovelBookmark) =
        cfi == other.cfi && chapterIndex == other.chapterIndex
}
