package ani.dantotsu.parsers.novel.lnreader

import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager

/**
 * The chapter list the reader is currently working through.
 *
 * Handed over through a singleton rather than intent extras, the same way [ani.dantotsu.media.MediaSingleton]
 * feeds the manga reader. A novel here routinely runs to thousands of chapters, and serialising
 * that into a Bundle would cross the binder transaction limit; the reader also needs the live
 * parser, which is not serialisable at all.
 *
 * Cleared when the reader is opened for anything else, so a stale list cannot attach itself to an
 * unrelated book.
 */
object LNReaderSession {

    var parser: LNReaderParser? = null
        private set
    var novel: LNReaderNovel? = null
        private set

    /** The media the novel was opened from, when there is one, so progress can be reported. */
    var media: Media? = null
        private set

    /** Index into `novel.chapters` of whatever the reader is showing. */
    var currentIndex: Int = -1

    val chapters: List<LNReaderChapter> get() = novel?.chapters.orEmpty()

    val isActive: Boolean get() = parser != null && novel != null && currentIndex >= 0

    fun start(parser: LNReaderParser, novel: LNReaderNovel, index: Int, media: Media?) {
        this.parser = parser
        this.novel = novel
        this.currentIndex = index
        this.media = media
    }

    /**
     * Carries only the media, for a downloaded run.
     *
     * A saved run is a complete book with its own spine, so there is no chapter list to fetch and
     * no parser to keep — [isActive] stays false and every path that streams chapters is left
     * alone. What the reader still needs is what the book belongs to, or it has nothing to report
     * reading progress against.
     */
    fun startDownloaded(media: Media?) {
        this.parser = null
        this.novel = null
        this.currentIndex = -1
        this.media = media
    }

    fun clear() {
        parser = null
        novel = null
        media = null
        currentIndex = -1
    }

    fun chapterAt(index: Int): LNReaderChapter? = chapters.getOrNull(index)

    fun hasNext(): Boolean = currentIndex + 1 in chapters.indices
    fun hasPrevious(): Boolean = currentIndex - 1 in chapters.indices
}

/**
 * Which chapters of a novel this app has opened.
 *
 * Not a claim about the user's list — the chapter list and the continue card read that from
 * [ani.dantotsu.media.Media.userProgress], as every other media type does. This is the novel
 * equivalent of manga's `<id>_current_chp`: where the app itself last got to, which the continue
 * card takes the later of against the tracker so it keeps up whether the reading happened here or
 * somewhere else. A chapter path rather than a number, because plenty of novel chapters have none.
 */
object LNReaderReadState {

    /**
     * Where a novel's read chapters are filed.
     *
     * The media id leads so [ani.dantotsu.connections.sync.ProgressSync] can shard this the way it
     * shards every other per-media key — it belongs on all of the user's devices, not just the one
     * that did the reading. The plugin and novel still follow it, because one media can be matched
     * on several sources and their chapter paths have nothing to do with each other.
     *
     * A media id of null means there is no media behind the read — browsing a source directly —
     * and those stay under the legacy key, which nothing syncs. That is the same line
     * [ProgressSync] already draws for extension-only media.
     */
    private fun key(mediaId: Int?, pluginId: String, novelPath: String): String {
        val novel = "${pluginId}_${novelPath.hashCode()}"
        return if (mediaId != null && mediaId >= 0) "$PREFIX-$mediaId-$novel"
        else "lnreader_read_$novel"
    }

    const val PREFIX = "lnreader_read"

    /**
     * Stored as one delimited string rather than a string set.
     *
     * [ProgressSync] carries a value by its type name, and a set arrives back as a list of an
     * unpredictable concrete class; a string crosses unchanged. Chapter paths are URL paths, so a
     * newline cannot occur inside one.
     */
    private const val SEPARATOR = "\n"

    fun readPaths(mediaId: Int?, pluginId: String, novelPath: String): Set<String> {
        val stored = PrefManager.getCustomVal(key(mediaId, pluginId, novelPath), "")
        if (stored.isNotEmpty()) return stored.split(SEPARATOR).filter { it.isNotEmpty() }.toSet()
        // Written before read state was keyed by media, or by a build that stored a set.
        val legacy = PrefManager.getNullableCustomVal(
            "lnreader_read_${pluginId}_${novelPath.hashCode()}", null, Set::class.java
        )?.filterIsInstance<String>()?.toSet().orEmpty()
        if (legacy.isNotEmpty() && mediaId != null && mediaId >= 0) save(mediaId, pluginId, novelPath, legacy)
        return legacy
    }

    fun markRead(mediaId: Int?, pluginId: String, novelPath: String, chapterPath: String) {
        save(mediaId, pluginId, novelPath, readPaths(mediaId, pluginId, novelPath) + chapterPath)
    }

    private fun save(mediaId: Int?, pluginId: String, novelPath: String, paths: Set<String>) {
        PrefManager.setCustomVal(
            key(mediaId, pluginId, novelPath), paths.joinToString(SEPARATOR)
        )
    }
}
