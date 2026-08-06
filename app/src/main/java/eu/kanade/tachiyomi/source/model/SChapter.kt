package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

interface SChapter : Serializable {

    var url: String

    var name: String

    var date_upload: Long

    var chapter_number: Float

    var scanlator: String?

    /**
     * Extra metadata the source attaches to the chapter — never shown to the user, and never
     * interpreted by the app. Sources on lib 1.6 use it to carry an id or slug from the chapter
     * list into the page request, sparing a second lookup.
     *
     * @since tachiyomix 1.6
     */
    var memo: JsonObject

    fun copyFrom(other: SChapter) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        chapter_number = other.chapter_number
        scanlator = other.scanlator
        // Only when the other side has something: see SManga.copyFrom for why an empty memo must
        // not overwrite one that already carries data.
        if (other.memo.isNotEmpty()) {
            memo = other.memo
        }
    }

    companion object {
        fun create(): SChapter {
            return SChapterImpl()
        }
    }
}
