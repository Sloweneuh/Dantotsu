package ani.dantotsu.connections.sync

import android.content.Context
import ani.dantotsu.App
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Opt-in cloud sync of per-extension settings ([ExtensionSettingsStore]), keyed by the Anilist
 * account. Structurally identical to [CloudSync] (same `{payload, ts}` envelope, divergence-safe
 * background push/pull, last-write-wins) but for the `source_*` preference blob and gated behind its
 * own toggle — because some sources store login tokens, and this puts them in the cloud.
 *
 * Applies silently like settings sync; there's no reconcile UI. All Firebase access is failure-safe.
 */
object ExtensionSettingsSync {

    private const val NODE = "extension_settings"
    private const val TS_KEY = "ext_settings_ts"
    private const val HASH_KEY = "ext_settings_hash"

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var applyingRemote = false
    @Volatile private var pullInFlight = false

    private data class Remote(val payload: String, val ts: Long)

    private fun enabled(): Boolean =
        PrefManager.getVal<Boolean>(PrefName.SyncExtensionSettingsEnabled) && SyncIdentity.isLinked()

    private fun ctx(): Context? = App.instance ?: App.context

    private fun userId(): String? =
        PrefManager.getVal<String>(PrefName.AnilistUserId).takeIf { it.isNotBlank() }

    private fun node() = SyncIdentity.node(NODE)

    // Credentials are held back from the cloud copy; local backups still carry them in full.
    private fun packLocal(): String? =
        ctx()?.let { ExtensionSettingsStore.export(it, excludeCredentials = true) }

    private fun lastTs(): Long = PrefManager.getCustomVal(TS_KEY, 0L)
    private fun lastHash(): Int = PrefManager.getCustomVal(HASH_KEY, 0)

    /** When this device last agreed with the cloud here, for [SyncOverview]. 0 if it never has. */
    internal fun localTs(): Long = lastTs()

    /** See [CloudSync.neverSynced] — no baseline means there is nothing to diff a divergence against. */
    private fun neverSynced(): Boolean = lastTs() == 0L && lastHash() == 0

    /** True when no source has stored any preferences here yet, so there is nothing to overwrite. */
    private fun localIsEmpty(): Boolean {
        val packed = packLocal() ?: return false
        return packed.isBlank() || packed == "{}"
    }

    // ---- Firebase primitives ----

    private suspend fun fetchRemote(uid: String): Result<Remote?> = runCatching {
        val snap = node()?.getSnapshot() ?: return@runCatching null
        if (!snap.schemaIsReadable("ExtensionSettingsSync")) return@runCatching null
        val json = snap.child("payload").getValue(String::class.java)
            ?.let { SyncIdentity.open(it) }
        val ts = snap.child("ts").getValue(Long::class.java)
        if (json != null && ts != null) Remote(json, ts) else null
    }

    private fun envelope(payload: String, ts: Long): Map<String, Any?>? =
        storedEnvelope(payload, NodeLimits.EXTENSION_SETTINGS, "ExtensionSettingsSync", ts)

    /** Unconditional overwrite, for the force actions. */
    private suspend fun upload(uid: String, payload: String, ts: Long): Boolean {
        val node = node() ?: return false
        val body = envelope(payload, ts) ?: return false
        return node.setValue(body).awaitOk()
    }

    /** See [CloudSync.uploadIfUnchanged] — same race, same fix. */
    private suspend fun uploadIfUnchanged(payload: String, ts: Long): CasOutcome {
        val node = node() ?: return CasOutcome.Failed
        val body = envelope(payload, ts) ?: return CasOutcome.Failed
        val baseline = lastTs()
        return node.compareAndSet(body) { current ->
            val currentTs = current.child("ts").getValue(Long::class.java)
            currentTs == null || currentTs <= baseline
        }
    }

    private fun rememberPushed(payload: String, ts: Long) {
        PrefManager.setCustomVal(TS_KEY, ts)
        PrefManager.setCustomVal(HASH_KEY, payload.hashCode())
        Logger.log("ExtensionSettingsSync: pushed (ts=$ts)")
    }

    private suspend fun doPush(uid: String, payload: String): Boolean {
        val ts = SyncClock.now()
        val ok = upload(uid, payload, ts)
        if (ok) rememberPushed(payload, ts)
        return ok
    }

    /** Push that yields to a cloud another device already moved. */
    private suspend fun doPushIfUnchanged(payload: String): CasOutcome {
        val ts = SyncClock.now()
        val outcome = uploadIfUnchanged(payload, ts)
        when (outcome) {
            CasOutcome.Written -> rememberPushed(payload, ts)
            CasOutcome.Superseded ->
                Logger.log("ExtensionSettingsSync: another device pushed first; leaving it alone")

            CasOutcome.Failed -> Logger.log("ExtensionSettingsSync: push failed")
        }
        return outcome
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
    suspend fun pushNow(): PushResult = SyncStatus.uploading {
        if (!enabled() || userId() == null || applyingRemote) return@uploading PushResult.NothingToDo
        runCatching {
            userId() ?: return PushResult.NothingToDo
            val local = packLocal() ?: return PushResult.NothingToDo
            if (local.hashCode() == lastHash()) return PushResult.NothingToDo
            // Decided and written in one step, so a device committing between the two can't be
            // overwritten. A cloud that moved on is left for the manual/force paths.
            when (doPushIfUnchanged(local)) {
                CasOutcome.Written -> PushResult.Pushed
                CasOutcome.Superseded -> PushResult.NothingToDo
                CasOutcome.Failed -> PushResult.Failed
            }
        }.getOrElse {
            Logger.log("ExtensionSettingsSync: push threw: ${it.message}")
            PushResult.Failed
        }
    }

    /** Pull on launch/login; applies only when the local copy is unchanged. */
    fun pullInBackground() {
        if (!enabled() || userId() == null || pullInFlight) return
        pullInFlight = true
        scope.launch {
            SyncStatus.downloading {
              try {
                runCatching {
                    val uid = userId() ?: return@runCatching
                    SyncIdentity.reconcileIdentity()
                    val remote = fetchRemote(uid).getOrElse {
                        Logger.log("ExtensionSettingsSync: pull skipped, remote unreadable: ${it.message}")
                        return@runCatching
                    } ?: return@runCatching
                    if (remote.ts <= lastTs()) return@runCatching
                    if (neverSynced()) {
                        // No baseline. Unlike settings, "has this device got anything to lose" is
                        // answerable here without guessing: either sources have stored preferences
                        // or they haven't. Adopting over existing ones could replace a source's
                        // saved login with another device's, so only an empty store is adopted.
                        if (!localIsEmpty()) {
                            Logger.log("ExtensionSettingsSync: no baseline and local settings exist; not adopting")
                            return@runCatching
                        }
                        Logger.log("ExtensionSettingsSync: no local extension settings; adopting cloud copy")
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
}
