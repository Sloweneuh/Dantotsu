package ani.dantotsu.connections.sync

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.internal.Location
import ani.dantotsu.util.Logger
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Per-user settings sync over Firebase Realtime Database, keyed by the Anilist account.
 *
 * Same dead-simple `{payload, ts}` envelope as [ani.dantotsu.connections.handoff.CloudHandoff].
 * Only `get()`/`setValue()` are used — no persistent listeners — so the idle cost (and quota
 * footprint) stays near zero, which is what keeps a handful of users far under the free tier.
 *
 * The payload is the packed preferences from [SYNC_LOCATIONS] minus [DEVICE_LOCAL_KEYS].
 * [Location.Protected] (tokens, passwords) is never included, so no secret leaves the device.
 *
 * **Divergence handling.** "Changed" is tracked by comparing the local payload's hash against the
 * hash last synced, and the remote's timestamp against the timestamp last synced. The background
 * triggers never clobber the other side: a background push only uploads when the remote has *not*
 * also moved on, and a background pull only applies when the local copy is unchanged. When both
 * sides changed, the background paths leave everything alone and the divergence is reported by
 * [syncManual] as a [SyncOutcome.Conflict] for the user to resolve.
 *
 * All Firebase access is wrapped so a misconfigured/unreachable database degrades to a no-op
 * rather than crashing the caller.
 */
object CloudSync {

    private const val ROOT = "users"
    private const val SETTINGS = "settings"

    // Locations holding genuine, shareable user preferences. Protected (secrets), Irrelevant
    // (device-local scratch state) and AnimeDownloads are deliberately omitted.
    private val SYNC_LOCATIONS = listOf(
        Location.General, Location.UI, Location.Player, Location.Reader, Location.NovelReader,
    )

    // Keys that live inside the synced locations but are device-specific and must not propagate.
    private val DEVICE_LOCAL_KEYS = setOf(
        PrefName.FirebaseToken,
        PrefName.LastFirebaseBackgroundCheck,
        PrefName.LastUnreadChapterCheck,
        PrefName.LastSubscriptionCheck,
        PrefName.UseAlarmManager,
        PrefName.CloudSyncEnabled,
        PrefName.SyncExtensionsEnabled,
        PrefName.SyncExtensionSettingsEnabled,
    ).map { it.name }.toSet()

    // Local bookkeeping, stored in the (never-synced) Irrelevant prefs.
    private const val TS_KEY = "cloud_settings_ts"
    private const val HASH_KEY = "cloud_settings_hash"

    private val scope = CoroutineScope(Dispatchers.IO)

    // Set while applying a remote payload so a racing push doesn't re-upload mid-import.
    @Volatile private var applyingRemote = false
    // Coalesces the concurrent background syncs fired by the home fragments at startup into one.
    @Volatile private var bgInFlight = false

    /** Result of a manual ("Sync now") sync. [Conflict] carries the remote copy for resolution. */
    sealed class SyncOutcome {
        data object Pushed : SyncOutcome()
        data object Pulled : SyncOutcome()
        data object UpToDate : SyncOutcome()
        data object Disabled : SyncOutcome()
        data object NoUser : SyncOutcome()
        data object Failed : SyncOutcome()
        data class Conflict(
            val remotePayload: String,
            val remoteTs: Long,
            val remoteDevice: String?,
        ) : SyncOutcome()
    }

    private data class Remote(val payload: String, val ts: Long, val device: String?)

    /** Human-readable label for this device, stored with each push to help resolve conflicts. */
    private fun deviceName(): String =
        listOfNotNull(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
            .joinToString(" ").trim().ifBlank { "Unknown device" }

    private fun isEnabled(): Boolean = PrefManager.getVal(PrefName.CloudSyncEnabled)

    private fun userId(): String? =
        PrefManager.getVal<String>(PrefName.AnilistUserId).takeIf { it.isNotBlank() }

    private fun settingsNode(uid: String) =
        FirebaseDatabase.getInstance().reference.child(ROOT).child(uid).child(SETTINGS)

    private fun packLocal(): String =
        PrefManager.exportSyncablePrefs(SYNC_LOCATIONS, DEVICE_LOCAL_KEYS)

    private fun lastTs(): Long = PrefManager.getCustomVal(TS_KEY, 0L)
    private fun lastHash(): Int = PrefManager.getCustomVal(HASH_KEY, 0)

    // ---- Firebase primitives (suspend, failure-safe) ----

    /** @return success(Remote) / success(null) when absent / failure when unreachable. */
    private suspend fun fetchRemote(uid: String): Result<Remote?> = runCatching {
        suspendCancellableCoroutine { cont ->
            settingsNode(uid).get()
                .addOnSuccessListener { snap ->
                    val json = snap.child("payload").getValue(String::class.java)
                    val ts = snap.child("ts").getValue(Long::class.java)
                    val device = snap.child("device").getValue(String::class.java)
                    cont.resume(if (json != null && ts != null) Remote(json, ts, device) else null)
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    private suspend fun upload(uid: String, payload: String, ts: Long): Boolean = runCatching {
        suspendCancellableCoroutine { cont ->
            settingsNode(uid)
                .setValue(mapOf("payload" to payload, "ts" to ts, "device" to deviceName()))
                .addOnSuccessListener { cont.resume(true) }
                .addOnFailureListener { cont.resume(false) }
        }
    }.getOrDefault(false)

    // ---- shared push / apply, with bookkeeping ----

    private suspend fun doPush(uid: String, payload: String): Boolean {
        val ts = System.currentTimeMillis()
        val ok = upload(uid, payload, ts)
        if (ok) {
            PrefManager.setCustomVal(TS_KEY, ts)
            PrefManager.setCustomVal(HASH_KEY, payload.hashCode())
            Logger.log("CloudSync: pushed settings (ts=$ts)")
        } else {
            Logger.log("CloudSync: settings push failed")
        }
        return ok
    }

    private fun doApply(payload: String, ts: Long): Boolean {
        applyingRemote = true
        val applied = try {
            PrefManager.importPackedPrefs(payload)
        } finally {
            applyingRemote = false
        }
        if (applied) {
            PrefManager.setCustomVal(TS_KEY, ts)
            // Record OUR re-exported hash (not the remote payload's) so the next push recognises
            // the freshly-applied state as unchanged and doesn't echo it back.
            PrefManager.setCustomVal(HASH_KEY, runCatching { packLocal().hashCode() }.getOrDefault(0))
            Logger.log("CloudSync: applied remote settings (ts=$ts)")
        } else {
            Logger.log("CloudSync: failed to apply remote settings")
        }
        return applied
    }

    // ---- manual "Sync now" (surfaces conflicts to the UI) ----

    suspend fun syncManual(): SyncOutcome {
        if (!isEnabled()) return SyncOutcome.Disabled
        val uid = userId() ?: return SyncOutcome.NoUser
        val local = runCatching { packLocal() }.getOrNull() ?: return SyncOutcome.Failed
        val remote = fetchRemote(uid).getOrElse {
            Logger.log("CloudSync: manual sync fetch failed: ${it.message}")
            return SyncOutcome.Failed
        }
        val localChanged = local.hashCode() != lastHash()
        val remoteChanged = remote != null && remote.ts > lastTs()
        return when {
            remote == null -> if (doPush(uid, local)) SyncOutcome.Pushed else SyncOutcome.Failed
            remoteChanged && localChanged -> SyncOutcome.Conflict(remote.payload, remote.ts, remote.device)
            remoteChanged -> if (doApply(remote.payload, remote.ts)) SyncOutcome.Pulled else SyncOutcome.Failed
            localChanged -> if (doPush(uid, local)) SyncOutcome.Pushed else SyncOutcome.Failed
            else -> SyncOutcome.UpToDate
        }
    }

    /**
     * Unconditionally overwrite the cloud settings with this device's, ignoring the enable toggle
     * and the divergence checks. Used by the conflict "keep this device" choice and the explicit
     * "force upload" action.
     */
    suspend fun forcePush(): Boolean {
        val uid = userId() ?: return false
        val local = runCatching { packLocal() }.getOrNull() ?: return false
        return doPush(uid, local)
    }

    /** Conflict resolution: keep this device's settings and overwrite the cloud. */
    suspend fun resolveKeepLocal(): Boolean = forcePush()

    /** Conflict resolution: apply the cloud's settings over this device. */
    suspend fun resolveUseRemote(payload: String, ts: Long): Boolean = doApply(payload, ts)

    /**
     * Unconditionally overwrite this device's settings with the cloud copy, ignoring the enable
     * toggle and the newer-than checks. Returns false when signed out, the cloud is empty, or it's
     * unreachable. Backs the explicit "force download" action.
     */
    suspend fun forcePull(): Boolean {
        val uid = userId() ?: return false
        val remote = fetchRemote(uid).getOrNull() ?: return false
        return doApply(remote.payload, remote.ts)
    }

    // ---- background triggers (never clobber the other side) ----

    /** Push on app background; uploads only local-only changes. No-op when divergent. */
    fun pushInBackground() {
        if (!isEnabled() || userId() == null) return
        scope.launch {
            runCatching {
                val uid = userId() ?: return@runCatching
                val local = packLocal()
                if (local.hashCode() == lastHash()) return@runCatching // nothing changed locally
                val remoteResult = fetchRemote(uid)
                // On fetch failure, skip rather than risk clobbering a remote we couldn't read.
                val remote = remoteResult.getOrElse { return@runCatching }
                if (remote != null && remote.ts > lastTs()) {
                    Logger.log("CloudSync: divergent (both changed); leaving for manual resolution")
                    return@runCatching
                }
                doPush(uid, local)
            }
        }
    }

    /** Pull on launch/login; applies only when the local copy is unchanged. Coalesces callers. */
    fun pullInBackground() {
        if (!isEnabled() || userId() == null || bgInFlight) return
        bgInFlight = true
        scope.launch {
            try {
                runCatching {
                    val uid = userId() ?: return@runCatching
                    val remote = fetchRemote(uid).getOrNull() ?: return@runCatching
                    if (remote.ts <= lastTs()) return@runCatching // remote not newer
                    val localChanged = packLocal().hashCode() != lastHash()
                    if (localChanged) {
                        Logger.log("CloudSync: divergent (both changed); leaving for manual resolution")
                        return@runCatching
                    }
                    doApply(remote.payload, remote.ts)
                }
            } finally {
                bgInFlight = false
            }
        }
    }
}
