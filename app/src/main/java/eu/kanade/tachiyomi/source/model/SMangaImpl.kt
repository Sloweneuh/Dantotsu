package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class SMangaImpl : SManga {

    override lateinit var url: String

    override lateinit var title: String

    override var artist: String? = null

    override var author: String? = null

    override var description: String? = null

    override var genre: String? = null

    override var status: Int = 0

    override var thumbnail_url: String? = null

    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE

    override var initialized: Boolean = false

    /**
     * The memo, held as its text rather than as a parsed object.
     *
     * [SManga] is `Serializable` and genuinely gets serialized — it travels in intent extras to the
     * details screen and inside a handoff payload to another device. `JsonObject` is not
     * `Serializable`, so storing one directly would turn any source that sets a memo into a
     * `NotSerializableException` the moment its entry was opened: a worse crash than the missing
     * method this exists to fix, on a far more common path.
     *
     * A string costs a parse per read, which is nothing against how rarely this is touched, and it
     * keeps the memo intact across both of those boundaries.
     */
    private var memoJson: String = EMPTY_MEMO

    override var memo: JsonObject
        get() = runCatching { Json.parseToJsonElement(memoJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        set(value) {
            memoJson = value.toString()
        }

    private companion object {
        const val EMPTY_MEMO = "{}"

        /**
         * Pinned so adding another field here doesn't silently invalidate every already-serialized
         * copy — Java derives this from the class shape when it isn't declared, and a mismatch
         * makes an intent extra or a handoff payload undeserializable.
         */
        private const val serialVersionUID = 1L
    }
}
