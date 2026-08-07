package ani.dantotsu.parsers

import ani.dantotsu.settings.saving.PrefManager

/**
 * Where the entry a parser matched for a media is stored — "this AniList/MangaUpdates media is
 * *that* series on that extension". Written whenever the user picks a result (or auto-search finds
 * one) and read back by every reader/player entry point, so it is the other half of the per-media
 * choice that `SelectedSource-<id>` holds: which extension, and which of its entries.
 *
 * The key used to be `"<saveName>[_sub|_dub]_<mediaId>"`, which nothing could pick apart: the media
 * id is the *suffix* and the prefix is a free-form extension name, so no lexical rule separates
 * `"Comick_12345"` from any other custom val ending in digits. [ani.dantotsu.connections.sync.ProgressSync]
 * shards per media and has to recognise a key without consulting the installed extensions — the
 * device receiving it may not have them — so the id moved to the front and the name became the
 * remainder. Keys written before that are still read, and are moved over on first access.
 */
object SavedShowResponse {

    /** Marks a key as an extension entry; the media id follows immediately. */
    const val PREFIX = "ShowResponse"

    fun key(mediaId: Int, saveName: String, dub: String = ""): String =
        "$PREFIX-$mediaId-$saveName$dub"

    private fun legacyKey(mediaId: Int, saveName: String, dub: String): String =
        "$saveName${dub}_$mediaId"

    /**
     * @param dub `"_sub"`/`"_dub"` for sources that carry the two separately, empty otherwise —
     *   they are different entries on the source and each is saved under its own key.
     */
    fun load(mediaId: Int, saveName: String, dub: String = ""): ShowResponse? {
        val key = key(mediaId, saveName, dub)
        PrefManager.getNullableCustomVal(key, null, ShowResponse::class.java)?.let { return it }
        val legacy = legacyKey(mediaId, saveName, dub)
        val stored = PrefManager.getNullableCustomVal(legacy, null, ShowResponse::class.java)
            ?: return null
        // Written before the rename. Move it rather than just reading it, so this device's next
        // push actually carries the user's match instead of leaving it stranded here.
        PrefManager.setCustomVal(key, stored)
        PrefManager.removeCustomVal(legacy)
        return stored
    }

    fun save(mediaId: Int, saveName: String, response: ShowResponse, dub: String = "") {
        PrefManager.setCustomVal(key(mediaId, saveName, dub), response)
        PrefManager.removeCustomVal(legacyKey(mediaId, saveName, dub))
    }
}
