package ani.dantotsu.connections.sync

import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Shares the result of the unread-chapter check across a user's devices, keyed by the Anilist
 * account. The expensive part of [ani.dantotsu.notifications.unread.UnreadChapterNotificationTask]
 * is the per-manga batch call to the third-party MALSync API; since the result (latest chapter vs.
 * the user's Anilist progress) is the same on every device, the first device to run publishes it and
 * the others reuse it instead of re-scanning — cutting redundant external load and battery.
 *
 * The cloud copy is treated as a freshness cache: [fetchFresh] only returns it when it's younger
 * than the caller's check interval. Notification de-duplication stays device-local. Gated on the
 * master [PrefName.CloudSyncEnabled] toggle (no separate setting — it's the user's own data).
 */
object UnreadSync {

    private const val NODE = "unread"
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<Int, UnreadChapterInfo>>() {}.type

    private fun enabled(): Boolean =
        PrefManager.getVal<Boolean>(PrefName.CloudSyncEnabled) && SyncIdentity.isLinked()

    private fun userId(): String? =
        PrefManager.getVal<String>(PrefName.AnilistUserId).takeIf { it.isNotBlank() }

    private fun node() = SyncIdentity.node(NODE)

    /**
     * @return the shared result if one exists and was saved within [maxAgeMs], else null — in which
     * case the caller must run the scan itself (and ideally [push] the result afterwards).
     */
    suspend fun fetchFresh(maxAgeMs: Long): Map<Int, UnreadChapterInfo>? {
        if (!enabled()) return null
        val uid = userId() ?: return null
        return runCatching {
            val snap = node()?.getSnapshot() ?: return@runCatching null
            if (!snap.schemaIsReadable("UnreadSync")) return@runCatching null
            val json = snap.child("payload").getValue(String::class.java)
                ?.let { SyncIdentity.open(it) }
            val ts = snap.child("ts").getValue(Long::class.java)
            // Server-relative on both sides: the publishing device stamped ts with the same
            // corrected clock, so freshness doesn't depend on the two devices agreeing.
            val age = ts?.let { SyncClock.now() - it }
            val result = if (json != null && age != null && age <= maxAgeMs) {
                runCatching { gson.fromJson<Map<Int, UnreadChapterInfo>>(json, mapType) }
                    .getOrNull()
            } else null
            if (result != null) {
                Logger.log("UnreadSync: serving shared result (${result.size}, age ${age}ms)")
            }
            result
        }.getOrNull()
    }

    /** Publishes a freshly-computed result for the other devices to reuse. */
    suspend fun push(result: Map<Int, UnreadChapterInfo>): Boolean {
        if (!enabled()) return false
        val uid = userId() ?: return false
        val json = runCatching { gson.toJson(result) }.getOrNull() ?: return false
        val node = node() ?: return false
        // This is a shared cache, not the user's data — a library large enough to overflow the node
        // just means every device computes the check itself, which is what happened before this
        // existed. Far better than a rejected write on every cycle.
        if (!fitsInNode(json, NodeLimits.UNREAD, "UnreadSync")) return false
        val sealed = SyncIdentity.seal(json) ?: return false
        val ts = SyncClock.now()
        return node
            .setValue(mapOf("payload" to sealed, "ts" to ts, "v" to SYNC_SCHEMA_VERSION))
            .awaitOk().also {
            if (it) Logger.log("UnreadSync: published result (${result.size}, ts=$ts)")
        }
    }
}
