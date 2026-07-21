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
 * The payload is the packed preferences from [SYNC_LOCATIONS], filtered by [isSyncable].
 * [Location.Protected] (tokens, passwords) is never included, so no secret leaves the device.
 *
 * **Divergence handling.** "Changed" is tracked by comparing the local payload's hash against the
 * hash last synced, and the remote's timestamp against the timestamp last synced. The background
 * triggers never clobber the other side: a background push only uploads when the remote has *not*
 * also moved on, and a background pull only applies when the local copy is unchanged. When both
 * sides changed, the background paths leave everything alone and the divergence is reported by
 * [syncManual] as a [SyncOutcome.Conflict] for the user to resolve.
 *
 * The one exception is a device that has never synced ([neverSynced]) — see that function.
 *
 * All Firebase access is wrapped so a misconfigured/unreachable database degrades to a no-op
 * rather than crashing the caller.
 */
object CloudSync {

    private const val ROOT = "users"
    private const val SETTINGS = "settings"

    // Locations holding genuine, shareable user preferences. Protected (secrets) and
    // AnimeDownloads are deliberately omitted. Irrelevant is included but allowlisted key-by-key
    // (see [SYNCABLE_IRRELEVANT_KEYS]) — it's the app's junk drawer, not a settings location.
    // NovelReader shares a file with Reader; PrefManager dedupes that.
    private val SYNC_LOCATIONS = listOf(
        Location.General, Location.UI, Location.Player, Location.Reader, Location.NovelReader,
        Location.Irrelevant,
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

    // Everything written through PrefManager.setCustomVal lands in Location.Irrelevant, which mixes
    // real user settings with caches, notification stores, auth blobs and this very sync's own
    // bookkeeping (cloud_settings_ts/hash). Syncing it wholesale would be catastrophic, so these are
    // opted in one by one. Per-media state (Selected-<id> etc.) is deliberately NOT here: it's
    // handled by [ProgressSync], which shards per media instead of stuffing it all into one blob.
    private val SYNCABLE_IRRELEVANT_KEYS = setOf(
        "subscriptions",                            // SubscriptionHelper: notification subscriptions
        "mediaView",                                // MediaListViewActivity: list/grid choice
        PrefName.MalSyncLanguagePreferences.name,
        PrefName.DiscordStatus.name,
        PrefName.DiscordRPCModeAnime.name,
        PrefName.DiscordRPCModeManga.name,
        PrefName.DiscordRPCShowIconAnime.name,
        PrefName.DiscordRPCShowIconManga.name,
        PrefName.DiscordShowButtons.name,
        PrefName.rpcEnabled.name,
        PrefName.SearchStyle.name,
        PrefName.SearchStyleSupporting.name,
        PrefName.LangSort.name,
        PrefName.AllowOpeningLinks.name,
        PrefName.MakeDefault.name,
    )

    private fun isSyncable(location: Location, key: String): Boolean = when (location) {
        Location.Irrelevant -> key in SYNCABLE_IRRELEVANT_KEYS
        else -> key !in DEVICE_LOCAL_KEYS
    }

    // Local bookkeeping. Lives in Irrelevant like every custom val, but is kept out of
    // SYNCABLE_IRRELEVANT_KEYS — syncing a device's own sync baseline would be self-defeating.
    private const val TS_KEY = "cloud_settings_ts"
    private const val HASH_KEY = "cloud_settings_hash"

    private val scope = CoroutineScope(Dispatchers.IO)

    // Set while applying a remote payload so a racing push doesn't re-upload mid-import.
    @Volatile private var applyingRemote = false
    // Coalesces the concurrent background syncs fired by the home fragments at startup into one.
    @Volatile private var bgInFlight = false

    /**
     * Set while a settings screen is in the foreground (see `App`'s lifecycle callbacks). A
     * background pull landing mid-edit would rewrite prefs underneath the user, and the screen
     * would keep showing — and on the next toggle re-save — the values it read on open. Only the
     * background path defers; the explicit "Sync now" / force actions are launched *from* those
     * screens and must still work.
     */
    @Volatile var settingsUiOpen = false

    /**
     * Invoked after a background pull changed this device's settings, so the UI can offer a reload.
     * Live screens have already read the old values, so an applied pull is otherwise invisible.
     * Activities must clear this when they pause — it holds a reference to them.
     */
    @Volatile var onBackgroundApply: (() -> Unit)? = null

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
        PrefManager.exportSyncablePrefs(SYNC_LOCATIONS, ::isSyncable)

    private fun lastTs(): Long = PrefManager.getCustomVal(TS_KEY, 0L)
    private fun lastHash(): Int = PrefManager.getCustomVal(HASH_KEY, 0)

    /**
     * True when this device has never completed a sync, so there is no baseline to diff against.
     *
     * This matters because "local changed" is `packLocal().hashCode() != lastHash()`, and with no
     * baseline `lastHash()` is 0 while the hash of a non-empty payload effectively never is. So a
     * virgin device reported a *false* divergence on every background push and every background
     * pull, and — since the cloud node already existed — deadlocked: it would never pull (local
     * "changed") and never push (remote "changed"), forever, on every device except the one that
     * first seeded the cloud.
     *
     * Whether that resolves silently or asks the user depends on [hasLocalCustomisations].
     */
    private fun neverSynced(): Boolean = lastTs() == 0L && lastHash() == 0

    /**
     * Whether the user has changed any synced setting away from its declared default on this
     * device. This is what separates a genuinely fresh install — nothing to lose, so adopting the
     * cloud copy silently is what the user wants — from a device that has been configured, where
     * overwriting without asking would throw away real work.
     *
     * Only primitive-valued prefs are considered. Serialized List/class prefs are written eagerly
     * on first run and their stored form (a Base64 blob) never compares equal to the declared
     * default, so counting them would mark every device as customised. Same for the allowlisted
     * custom vals, several of which are written on first read. The trade-off: a device whose only
     * customisation is e.g. home-layout order reads as pristine and gets adopted silently.
     *
     * Errs toward asking: any failure to determine this returns true.
     */
    private fun hasLocalCustomisations(): Boolean = runCatching {
        val byName = PrefName.entries.associateBy { it.name }
        SYNC_LOCATIONS.any { location ->
            PrefManager.rawPrefs(location).any pref@{ (key, value) ->
                if (!isSyncable(location, key)) return@pref false
                when (val default = byName[key]?.data?.default) {
                    is Boolean, is Int, is Float, is Long, is String -> value != default
                    else -> false
                }
            }
        }
    }.getOrDefault(true)

    /**
     * Set when a background pull found a cloud copy it must not silently adopt: this device has
     * settings of its own and no baseline to diff them against. The UI picks this up and raises the
     * conflict prompt. Lives in Irrelevant but is not allowlisted, so it never syncs.
     */
    private const val BOOTSTRAP_PROMPT_KEY = "cloud_settings_bootstrap_prompt"

    /** True when a background pull deferred to the user; see [BOOTSTRAP_PROMPT_KEY]. */
    fun bootstrapPromptPending(): Boolean = PrefManager.getCustomVal(BOOTSTRAP_PROMPT_KEY, false)

    private fun clearBootstrapPrompt() = PrefManager.setCustomVal(BOOTSTRAP_PROMPT_KEY, false)

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
            clearBootstrapPrompt() // we now have a baseline; nothing left to ask about
            Logger.log("CloudSync: pushed settings (ts=$ts)")
        } else {
            Logger.log("CloudSync: settings push failed")
        }
        return ok
    }

    private fun doApply(payload: String, ts: Long): Boolean {
        applyingRemote = true
        val applied = try {
            // Same filter that built our own payload, so pruning can only ever remove keys this
            // device would itself have uploaded — deletions propagate, secrets stay put.
            PrefManager.importPackedPrefs(payload, ::isSyncable)
        } finally {
            applyingRemote = false
        }
        if (applied) {
            PrefManager.setCustomVal(TS_KEY, ts)
            // Record OUR re-exported hash (not the remote payload's) so the next push recognises
            // the freshly-applied state as unchanged and doesn't echo it back.
            PrefManager.setCustomVal(HASH_KEY, runCatching { packLocal().hashCode() }.getOrDefault(0))
            clearBootstrapPrompt() // we now have a baseline; nothing left to ask about
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
        // With no baseline there's no hash to diff, so fall back to "did the user configure this
        // device at all" — pristine adopts the cloud, configured raises the conflict prompt.
        val localChanged =
            if (neverSynced()) hasLocalCustomisations() else local.hashCode() != lastHash()
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

    /**
     * Push on app background; uploads only local-only changes. No-op when divergent.
     *
     * Suspending so [SyncPushWorker] can await it — this runs as the app is being backgrounded or
     * swiped away, and a bare fire-and-forget coroutine gets killed with the process mid-upload.
     */
    suspend fun pushNow() {
        if (!isEnabled() || userId() == null || applyingRemote) return
        runCatching {
            val uid = userId() ?: return
            val local = packLocal()
            if (local.hashCode() == lastHash()) return // nothing changed locally
            val remoteResult = fetchRemote(uid)
            // On fetch failure, skip rather than risk clobbering a remote we couldn't read.
            val remote = remoteResult.getOrElse {
                Logger.log("CloudSync: push skipped, remote unreadable: ${it.message}")
                return
            }
            if (remote != null && remote.ts > lastTs()) {
                // Includes the never-synced case: let the next pull bootstrap us instead of
                // overwriting the cloud with a device that has no baseline.
                Logger.log("CloudSync: divergent (both changed); leaving for manual resolution")
                return
            }
            doPush(uid, local)
        }
    }

    /** Pull on launch/login/foreground; applies only when local is unchanged. Coalesces callers. */
    fun pullInBackground() {
        if (!isEnabled() || userId() == null || bgInFlight) return
        if (settingsUiOpen) {
            // Checked before the fetch so a user sitting on a settings screen costs us nothing.
            Logger.log("CloudSync: settings screen open; deferring pull")
            return
        }
        bgInFlight = true
        scope.launch {
            try {
                runCatching {
                    val uid = userId() ?: return@runCatching
                    val remote = fetchRemote(uid).getOrElse {
                        Logger.log("CloudSync: pull skipped, remote unreadable: ${it.message}")
                        return@runCatching
                    } ?: return@runCatching
                    if (remote.ts <= lastTs()) return@runCatching // remote not newer
                    if (neverSynced()) {
                        if (hasLocalCustomisations()) {
                            // Settings of its own, no baseline to diff them against: we can't tell
                            // what to keep, so don't guess. Flag it for the conflict prompt.
                            Logger.log("CloudSync: no baseline but local settings exist; asking user")
                            PrefManager.setCustomVal(BOOTSTRAP_PROMPT_KEY, true)
                            return@runCatching
                        }
                        Logger.log("CloudSync: pristine device; adopting cloud settings")
                    } else if (packLocal().hashCode() != lastHash()) {
                        Logger.log("CloudSync: divergent (both changed); leaving for manual resolution")
                        return@runCatching
                    }
                    if (doApply(remote.payload, remote.ts)) {
                        runCatching { onBackgroundApply?.invoke() }
                    }
                }
            } finally {
                bgInFlight = false
            }
        }
    }
}
