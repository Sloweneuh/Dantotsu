package ani.dantotsu.connections.sync

import android.content.Context
import ani.dantotsu.App
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Opt-in cloud sync of per-extension settings ([ExtensionSettingsStore]), keyed by the Anilist
 * account. Structurally identical to [CloudSync] (same `{payload, ts}` envelope, divergence-safe
 * background push/pull, last-write-wins) but for the `source_*` preference blob and gated behind its
 * own toggle — because some sources store login tokens, and this puts them in the cloud.
 *
 * Applies silently like settings sync; there's no reconcile UI. All Firebase access is failure-safe.
 */
object ExtensionSettingsSync {

    private const val ROOT = "users"
    private const val NODE = "extension_settings"
    private const val TS_KEY = "ext_settings_ts"
    private const val HASH_KEY = "ext_settings_hash"

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var applyingRemote = false
    @Volatile private var pullInFlight = false

    private data class Remote(val payload: String, val ts: Long)

    private fun enabled(): Boolean = PrefManager.getVal(PrefName.SyncExtensionSettingsEnabled)

    private fun ctx(): Context? = App.instance ?: App.context

    private fun userId(): String? =
        PrefManager.getVal<String>(PrefName.AnilistUserId).takeIf { it.isNotBlank() }

    private fun node(uid: String) =
        FirebaseDatabase.getInstance().reference.child(ROOT).child(uid).child(NODE)

    private fun packLocal(): String? = ctx()?.let { ExtensionSettingsStore.export(it) }

    private fun lastTs(): Long = PrefManager.getCustomVal(TS_KEY, 0L)
    private fun lastHash(): Int = PrefManager.getCustomVal(HASH_KEY, 0)

    /** See [CloudSync.neverSynced] — no baseline means no divergence to defend, so the cloud wins. */
    private fun neverSynced(): Boolean = lastTs() == 0L && lastHash() == 0

    // ---- Firebase primitives ----

    private suspend fun fetchRemote(uid: String): Result<Remote?> = runCatching {
        suspendCancellableCoroutine { cont ->
            node(uid).get()
                .addOnSuccessListener { snap ->
                    val json = snap.child("payload").getValue(String::class.java)
                    val ts = snap.child("ts").getValue(Long::class.java)
                    cont.resume(if (json != null && ts != null) Remote(json, ts) else null)
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    private suspend fun upload(uid: String, payload: String, ts: Long): Boolean = runCatching {
        suspendCancellableCoroutine { cont ->
            node(uid)
                .setValue(mapOf("payload" to payload, "ts" to ts))
                .addOnSuccessListener { cont.resume(true) }
                .addOnFailureListener { cont.resume(false) }
        }
    }.getOrDefault(false)

    private suspend fun doPush(uid: String, payload: String): Boolean {
        val ts = System.currentTimeMillis()
        val ok = upload(uid, payload, ts)
        if (ok) {
            PrefManager.setCustomVal(TS_KEY, ts)
            PrefManager.setCustomVal(HASH_KEY, payload.hashCode())
            Logger.log("ExtensionSettingsSync: pushed (ts=$ts)")
        }
        return ok
    }

    private fun doApply(payload: String, ts: Long): Boolean {
        val context = ctx() ?: return false
        applyingRemote = true
        val applied = try {
            ExtensionSettingsStore.import(context, payload)
        } finally {
            applyingRemote = false
        }
        if (applied) {
            PrefManager.setCustomVal(TS_KEY, ts)
            PrefManager.setCustomVal(HASH_KEY, runCatching { packLocal()?.hashCode() }.getOrNull() ?: 0)
            Logger.log("ExtensionSettingsSync: applied remote (ts=$ts)")
        }
        return applied
    }

    // ---- explicit force actions ----

    /** Unconditionally overwrite the cloud copy with this device's. */
    suspend fun forcePush(): Boolean {
        val uid = userId() ?: return false
        val local = packLocal() ?: return false
        return doPush(uid, local)
    }

    /** Unconditionally overwrite this device's extension settings with the cloud copy. */
    suspend fun forcePull(): Boolean {
        val uid = userId() ?: return false
        val remote = fetchRemote(uid).getOrNull() ?: return false
        return doApply(remote.payload, remote.ts)
    }

    // ---- background triggers (never clobber the other side) ----

    /** Push on app background; uploads only local-only changes. No-op when disabled/divergent. */
    suspend fun pushNow() {
        if (!enabled() || userId() == null || applyingRemote) return
        runCatching {
            val uid = userId() ?: return
            val local = packLocal() ?: return
            if (local.hashCode() == lastHash()) return
            val remote = fetchRemote(uid).getOrElse {
                Logger.log("ExtensionSettingsSync: push skipped, remote unreadable: ${it.message}")
                return
            }
            if (remote != null && remote.ts > lastTs()) {
                Logger.log("ExtensionSettingsSync: divergent; leaving for manual/force")
                return
            }
            doPush(uid, local)
        }
    }

    /** Pull on launch/login; applies only when the local copy is unchanged. */
    fun pullInBackground() {
        if (!enabled() || userId() == null || pullInFlight) return
        pullInFlight = true
        scope.launch {
            try {
                runCatching {
                    val uid = userId() ?: return@runCatching
                    val remote = fetchRemote(uid).getOrElse {
                        Logger.log("ExtensionSettingsSync: pull skipped, remote unreadable: ${it.message}")
                        return@runCatching
                    } ?: return@runCatching
                    if (remote.ts <= lastTs()) return@runCatching
                    if (neverSynced()) {
                        Logger.log("ExtensionSettingsSync: no local baseline; adopting cloud copy")
                    } else if ((packLocal()?.hashCode() ?: 0) != lastHash()) {
                        Logger.log("ExtensionSettingsSync: divergent; leaving for manual/force")
                        return@runCatching
                    }
                    doApply(remote.payload, remote.ts)
                }
            } finally {
                pullInFlight = false
            }
        }
    }
}
