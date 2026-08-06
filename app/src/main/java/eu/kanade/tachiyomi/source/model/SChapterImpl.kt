package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class SChapterImpl : SChapter {

    override lateinit var url: String

    override lateinit var name: String

    override var date_upload: Long = 0

    override var chapter_number: Float = -1f

    override var scanlator: String? = null

    /** Held as text so the chapter stays `Serializable`; see [SMangaImpl.memo] for the reasoning. */
    private var memoJson: String = EMPTY_MEMO

    override var memo: JsonObject
        get() = runCatching { Json.parseToJsonElement(memoJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        set(value) {
            memoJson = value.toString()
        }

    private companion object {
        const val EMPTY_MEMO = "{}"

        /** Pinned for the same reason as [SMangaImpl]: chapters travel through serialization too. */
        private const val serialVersionUID = 1L
    }
}
