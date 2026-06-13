package ani.dantotsu.connections.sync

import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import com.google.firebase.database.FirebaseDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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

    private const val ROOT = "users"
    private const val NODE = "unread"
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<Int, UnreadChapterInfo>>() {}.type

    private fun enabled(): Boolean = PrefManager.getVal(PrefName.CloudSyncEnabled)

    private fun userId(): String? =
        PrefManager.getVal<String>(PrefName.AnilistUserId).takeIf { it.isNotBlank() }

    private fun node(uid: String) =
        FirebaseDatabase.getInstance().reference.child(ROOT).child(uid).child(NODE)

    /**
     * @return the shared result if one exists and was saved within [maxAgeMs], else null — in which
     * case the caller must run the scan itself (and ideally [push] the result afterwards).
     */
    suspend fun fetchFresh(maxAgeMs: Long): Map<Int, UnreadChapterInfo>? {
        if (!enabled()) return null
        val uid = userId() ?: return null
        return runCatching {
            suspendCancellableCoroutine<Map<Int, UnreadChapterInfo>?> { cont ->
                node(uid).get()
                    .addOnSuccessListener { snap ->
                        val json = snap.child("payload").getValue(String::class.java)
                        val ts = snap.child("ts").getValue(Long::class.java)
                        val fresh = ts != null && System.currentTimeMillis() - ts <= maxAgeMs
                        val result = if (json != null && fresh) {
                            runCatching { gson.fromJson<Map<Int, UnreadChapterInfo>>(json, mapType) }
                                .getOrNull()
                        } else null
                        if (result != null) {
                            Logger.log("UnreadSync: serving shared result (${result.size}, age ${ts?.let { System.currentTimeMillis() - it }}ms)")
                        }
                        cont.resume(result)
                    }
                    .addOnFailureListener { cont.resume(null) }
            }
        }.getOrNull()
    }

    /** Publishes a freshly-computed result for the other devices to reuse. */
    suspend fun push(result: Map<Int, UnreadChapterInfo>): Boolean {
        if (!enabled()) return false
        val uid = userId() ?: return false
        val json = runCatching { gson.toJson(result) }.getOrNull() ?: return false
        val ts = System.currentTimeMillis()
        return runCatching {
            suspendCancellableCoroutine<Boolean> { cont ->
                node(uid).setValue(mapOf("payload" to json, "ts" to ts))
                    .addOnSuccessListener { cont.resume(true) }
                    .addOnFailureListener { cont.resume(false) }
            }
        }.getOrDefault(false).also {
            if (it) Logger.log("UnreadSync: published result (${result.size}, ts=$ts)")
        }
    }
}
