package ani.dantotsu.connections.sync

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.internal.Location
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

    private const val SETTINGS = "settings"

    // Locations holding genuine, shareable user preferences. Protected (secrets) and
    // AnimeDownloads are deliberately omitted. Irrelevant is included but allowlisted key-by-key
    // (see [SYNCABLE_IRRELEVANT_KEYS]) — it's the app's junk drawer, not a settings location.
    // NovelReader shares a file with Reader; PrefManager dedupes that.
    private val SYNC_LOCATIONS = listOf(
        Location.General, Location.UI, Location.Player, Location.Reader, Location.NovelReader,
        Location.Irrelevant, Location.Protected,
    )

    // Keys that live inside the synced locations but are device-specific and must not propagate.
    private val DEVICE_LOCAL_KEYS = setOf(
        PrefName.FirebaseToken,
        PrefName.LastFirebaseBackgroundCheck,
        PrefName.LastUnreadChapterCheck,
        PrefName.LastSubscriptionCheck,
        // Where this device's notification de-duplication got to. Its siblings above were already
        // excluded; this one was missed, so a push moved the *other* devices' cursors forward and
        // they silently skipped AniList notifications they had never shown.
        PrefName.LastAnilistNotificationId,
        PrefName.UseAlarmManager,
        PrefName.CloudSyncEnabled,
        PrefName.SyncExtensionsEnabled,
        PrefName.SyncExtensionSettingsEnabled,
        // The proxy toggles, but not the host/port/credentials they need — those are Protected and
        // deliberately never leave the device. Syncing the flag alone left the receiving device
        // enabling a SOCKS proxy with an empty host (see NetworkHelper.setupSocks5Proxy), i.e. all
        // networking dead, with the cause buried in a submenu it never opened.
        PrefName.EnableSocks5Proxy,
        PrefName.ProxyAuthEnabled,
    ).map { it.name }.toSet()

    // Everything written through PrefManager.setCustomVal lands in Location.Irrelevant, which mixes
    // real user settings with caches, notification stores, auth blobs and this very sync's own
    // bookkeeping (cloud_settings_ts/hash). Syncing it wholesale would be catastrophic, so these are
    // opted in one by one. Per-media state (Selected-<id> etc.) is deliberately NOT here: it's
    // handled by [ProgressSync], which shards per media instead of stuffing it all into one blob.
    private val SYNCABLE_IRRELEVANT_KEYS = setOf(
        "subscriptions",                            // SubscriptionHelper: notification subscriptions
        "mediaView",                                // MediaListViewActivity: list/grid choice
        "stackShowNovels",                          // MediaListViewActivity: novels shown in stacks
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

    /**
     * The set of prefs this sync owns — and, since [PrefManager.importAllPrefs] applies the same
     * predicate on the way in, the only prefs an incoming payload can write.
     *
     * The location check is what makes that safe. This used to fall through to `key !in
     * DEVICE_LOCAL_KEYS` for *any* location, which was harmless while it only ever ran over
     * [SYNC_LOCATIONS] on export — but a payload names its own locations, so on import that
     * accepted a `Protected` block and let it overwrite the token store.
     */
    /**
     * The only things from the credential store that ever leave the device: the display names of
     * the accounts signed in elsewhere. Never a token, never a password — those are what
     * [Location.Protected] exists to hold, and none of them is here.
     *
     * They earn their place by fixing a toggle that lies. The tracker preferences
     * (`MalListSyncEnabled` and friends) live in [Location.General] and have always synced, so a
     * second device shows "MyAnimeList list sync: on" while having no MyAnimeList login and doing
     * nothing at all. Carrying the name across is what lets that screen say *which* account it
     * means and offer to reconnect, instead of an unexplained switch.
     *
     * AniList is deliberately absent: it keys the sync itself, so both devices are the same account
     * by construction and there is nothing to tell them.
     */
    private val SYNCABLE_PROTECTED_KEYS = setOf(
        PrefName.MALUserName.name,
        PrefName.MangaUpdatesUsername.name,
        PrefName.MangaBakaUserName.name,
        PrefName.DiscordUserName.name,
    )

    private fun isSyncable(location: Location, key: String): Boolean = when {
        location !in SYNC_LOCATIONS -> false
        location == Location.Irrelevant -> key in SYNCABLE_IRRELEVANT_KEYS
        location == Location.Protected -> key in SYNCABLE_PROTECTED_KEYS
        else -> key !in DEVICE_LOCAL_KEYS
    }

    // Local bookkeeping. Lives in Irrelevant like every custom val, but is kept out of
    // SYNCABLE_IRRELEVANT_KEYS — syncing a device's own sync baseline would be self-defeating.
    private const val TS_KEY = "cloud_settings_ts"
    private const val HASH_KEY = "cloud_settings_hash"
    private const val FILTER_VERSION_KEY = "cloud_settings_filter_v"

    /**
     * The payload this device last agreed with the cloud on — the baseline [SyncMerge] diffs both
     * sides against to tell "I changed this" from "they changed this". Without it a divergence can
     * only be resolved wholesale; with it, the overwhelmingly common case of two devices touching
     * different settings resolves itself and never reaches the user.
     */
    private const val BASE_KEY = "cloud_settings_base"

    /**
     * Bump whenever [isSyncable]'s key set changes.
     *
     * "Local changed" is `packLocal().hashCode() != lastHash()`, so widening or narrowing the
     * filter changes the payload — and therefore reads as a local edit — on every device at once,
     * the moment each one updates. The first to background pushes; every other device then sees a
     * remote that also moved and backs off as divergent, so background sync quietly stops until
     * the user hits "Sync now" and is asked to resolve a conflict nobody made.
     *
     * On a bump, re-baseline the hash instead: the settings didn't change, only which of them we
     * share. See [rebaselineForFilterChange].
     */
    private const val FILTER_VERSION = 3

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


    /** Result of a manual ("Sync now") sync. [Conflict] carries the remote copy for resolution. */
    sealed class SyncOutcome {
        data object Pushed : SyncOutcome()
        data object Pulled : SyncOutcome()
        data object UpToDate : SyncOutcome()
        data object Disabled : SyncOutcome()
        data object NoUser : SyncOutcome()
        data object Failed : SyncOutcome()
        /** Auto-merged: both sides changed, but never the same setting. Nothing to ask. */
        data object Merged : SyncOutcome()

        /**
         * Both sides changed the same settings. [conflicts] names them; the two payloads are the
         * fully-merged result either way, so choosing only decides the overlap — everything each
         * device changed on its own is kept regardless.
         */
        data class Conflict(
            val remotePayload: String,
            val remoteTs: Long,
            val remoteDevice: String?,
            val conflicts: List<SyncMerge.Conflict> = emptyList(),
            val keepLocalPayload: String? = null,
            val useRemotePayload: String? = null,
        ) : SyncOutcome()
    }

    private data class Remote(val payload: String, val ts: Long, val device: String?)

    /** Human-readable label for this device, stored with each push to help resolve conflicts. */
    private fun deviceName(): String =
        listOfNotNull(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
            .joinToString(" ").trim().ifBlank { "Unknown device" }

    /** Enabled *and* linked: without the secret there is no node to address. */
    private fun isEnabled(): Boolean =
        PrefManager.getVal<Boolean>(PrefName.CloudSyncEnabled) && SyncIdentity.isLinked()

    private fun userId(): String? =
        PrefManager.getVal<String>(PrefName.AnilistUserId).takeIf { it.isNotBlank() }

    private fun settingsNode() = SyncIdentity.node(SETTINGS)

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
     * Such a device always defers to the user rather than resolving on its own: it has no way to
     * distinguish settings it was configured with from the defaults it shipped with.
     */
    private fun neverSynced(): Boolean = lastTs() == 0L && lastHash() == 0

    /**
     * Adopts the current payload as the baseline when [FILTER_VERSION] moved, so a change to what
     * we sync doesn't masquerade as a change to the settings themselves.
     *
     * [lastTs] is deliberately left alone: a remote that genuinely is newer must still win. And a
     * device that has never synced is skipped outright — its zeroed baseline is what [neverSynced]
     * reads, and writing a real hash here would strand it out of the bootstrap path entirely.
     */
    private fun rebaselineForFilterChange() {
        if (PrefManager.getCustomVal(FILTER_VERSION_KEY, 1) == FILTER_VERSION) return
        if (neverSynced()) return
        runCatching {
            PrefManager.setCustomVal(HASH_KEY, packLocal().hashCode())
            PrefManager.setCustomVal(FILTER_VERSION_KEY, FILTER_VERSION)
            Logger.log("CloudSync: re-baselined for filter v$FILTER_VERSION")
        }
    }

    /**
     * Set when a background pull found a cloud copy it must not silently adopt: this device has
     * settings of its own and no baseline to diff them against. The UI picks this up and raises the
     * conflict prompt. Lives in Irrelevant but is not allowlisted, so it never syncs.
     */
    private const val BOOTSTRAP_PROMPT_KEY = "cloud_settings_bootstrap_prompt"

    /** True when a background pull deferred to the user; see [BOOTSTRAP_PROMPT_KEY]. */
    fun bootstrapPromptPending(): Boolean = PrefManager.getCustomVal(BOOTSTRAP_PROMPT_KEY, false)

    /**
     * Whether this device has ever completed a sync. Used to tell an existing user — who now needs
     * a sync code for a feature that used to need nothing — apart from a fresh install, which
     * shouldn't be prompted about a feature it has never used.
     */
    fun hasSyncBaseline(): Boolean = !neverSynced()

    /** When this device last agreed with the cloud, or 0 if it never has. For display. */
    fun lastSyncedAt(): Long = lastTs()

    /** What the cloud currently holds, as far as anyone needs to display it. */
    data class CloudInfo(val ts: Long, val device: String?)

    /**
     * Reads just the metadata of the stored copy — when it was written, and by which device.
     *
     * Deliberately reads the two small children rather than the node, because the payload beside
     * them can run to a megabyte and none of it is wanted here. @return null when nothing is stored,
     * when this device can't read it, or when it isn't linked — the caller can't tell those apart
     * and shouldn't claim to.
     */
    suspend fun cloudInfo(): CloudInfo? = runCatching {
        val node = settingsNode() ?: return null
        val ts = node.child("ts").getSnapshot().getValue(Long::class.java) ?: return null
        val device = node.child("device").getSnapshot().getValue(String::class.java)
            ?.let { SyncIdentity.open(it) }
        CloudInfo(ts, device)
    }.getOrNull()

    private fun clearBootstrapPrompt() = PrefManager.setCustomVal(BOOTSTRAP_PROMPT_KEY, false)

    // ---- Firebase primitives (suspend, failure-safe) ----

    /**
     * @return success(Remote) / success(null) when absent / failure when unreachable.
     *
     * A node that won't decrypt reads as absent: it belongs to a different secret, so from this
     * device's point of view there is nothing there. The device label is sealed alongside the
     * payload rather than stored beside it — "Pixel 8" next to a timestamp is still a fact about
     * someone, and the node is only as private as its least private field.
     */
    private suspend fun fetchRemote(uid: String): Result<Remote?> = runCatching {
        val snap = settingsNode()?.getSnapshot() ?: return@runCatching null
        if (!snap.schemaIsReadable("CloudSync")) return@runCatching null
        val sealed = snap.child("payload").getValue(String::class.java)
        val ts = snap.child("ts").getValue(Long::class.java)
        val json = sealed?.let { SyncIdentity.open(it) }
        if (json == null && sealed != null) {
            Logger.log("CloudSync: remote payload is not ours to read")
        }
        val device = snap.child("device").getValue(String::class.java)
            ?.let { SyncIdentity.open(it) }
        if (json != null && ts != null) Remote(json, ts, device) else null
    }

    /** The stored form of a payload: sealed contents plus the plaintext bookkeeping around them. */
    private fun envelope(payload: String, ts: Long): Map<String, Any?>? =
        storedEnvelope(payload, NodeLimits.SETTINGS, "CloudSync", ts)?.apply {
            put("device", SyncIdentity.seal(deviceName()))
        }

    /** Unconditional overwrite, for the paths where replacing the cloud is the user's decision. */
    private suspend fun upload(uid: String, payload: String, ts: Long): Boolean {
        val node = settingsNode() ?: return false
        val body = envelope(payload, ts) ?: return false
        return node.setValue(body).awaitOk()
    }

    /**
     * Writes only if the cloud still holds what this device last agreed with, in one step.
     *
     * Reading, deciding, and then writing left a window in which another device could commit
     * between the read and the write — both would conclude the cloud was unchanged, and the slower
     * one's session would be overwritten while it recorded a baseline saying it had won.
     */
    private suspend fun uploadIfUnchanged(
        payload: String,
        ts: Long,
        expectedMaxTs: Long,
    ): CasOutcome {
        val node = settingsNode() ?: return CasOutcome.Failed
        val body = envelope(payload, ts) ?: return CasOutcome.Failed
        return node.compareAndSet(body) { current ->
            val currentTs = current.child("ts").getValue(Long::class.java)
            currentTs == null || currentTs <= expectedMaxTs
        }
    }

    // ---- shared push / apply, with bookkeeping ----

    /** Records that the cloud now holds [payload], stamped [ts]. */
    private fun rememberPushed(payload: String, ts: Long) {
        PrefManager.setCustomVal(TS_KEY, ts)
        PrefManager.setCustomVal(HASH_KEY, payload.hashCode())
        PrefManager.setCustomVal(BASE_KEY, payload) // what the cloud now holds; see [BASE_KEY]
        PrefManager.setCustomVal(FILTER_VERSION_KEY, FILTER_VERSION) // baseline is filter-current
        clearBootstrapPrompt() // we now have a baseline; nothing left to ask about
        Logger.log("CloudSync: pushed settings (ts=$ts)")
    }

    /** Unconditional push, for force actions and conflict resolutions the user asked for. */
    private suspend fun doPush(uid: String, payload: String): Boolean {
        val ts = SyncClock.now()
        val ok = upload(uid, payload, ts)
        if (ok) rememberPushed(payload, ts) else Logger.log("CloudSync: settings push failed")
        return ok
    }

    /**
     * Push that yields to a cloud which moved on. [CasOutcome.Superseded] means another device
     * committed first: nothing was written, nothing was lost, and the next pull sees their version.
     */
    /**
     * @param expectedMaxTs the newest cloud version this push is allowed to replace — normally the
     *   baseline this device last agreed with, but the version a merge was computed against when
     *   resolving a divergence, since there the cloud has legitimately moved past the baseline.
     */
    private suspend fun doPushIfUnchanged(payload: String, expectedMaxTs: Long): CasOutcome {
        val ts = SyncClock.now()
        val outcome = uploadIfUnchanged(payload, ts, expectedMaxTs)
        when (outcome) {
            CasOutcome.Written -> rememberPushed(payload, ts)
            CasOutcome.Superseded ->
                Logger.log("CloudSync: another device pushed first; leaving the cloud alone")

            CasOutcome.Failed -> Logger.log("CloudSync: settings push failed")
        }
        return outcome
    }

    private fun doApply(payload: String, ts: Long): Boolean {
        applyingRemote = true
        val applied = try {
            // Same filter that built our own payload, so the payload can only ever write — and
            // pruning only ever remove — keys this device would itself have uploaded: deletions
            // propagate, secrets stay put.
            PrefManager.importPackedPrefs(payload, ::isSyncable)
        } catch (e: Exception) {
            // The background paths are wrapped in runCatching, but syncManual/resolveUseRemote run
            // in a GlobalScope launch where an escaping exception takes the process with it.
            Logger.log("CloudSync: apply threw: ${e.message}")
            false
        } finally {
            applyingRemote = false
        }
        if (applied) {
            PrefManager.setCustomVal(TS_KEY, ts)
            // Record OUR re-exported form (not the remote payload) as both hash and baseline, so
            // the next push recognises the freshly-applied state as unchanged and doesn't echo it
            // back, and the next merge diffs against what this device actually holds.
            val reexported = runCatching { packLocal() }.getOrNull()
            PrefManager.setCustomVal(HASH_KEY, reexported?.hashCode() ?: 0)
            reexported?.let { PrefManager.setCustomVal(BASE_KEY, it) }
            PrefManager.setCustomVal(FILTER_VERSION_KEY, FILTER_VERSION) // baseline is filter-current
            clearBootstrapPrompt() // we now have a baseline; nothing left to ask about
            Logger.log("CloudSync: applied remote settings (ts=$ts)")
        } else {
            Logger.log("CloudSync: failed to apply remote settings")
        }
        return applied
    }

    // ---- manual "Sync now" (surfaces conflicts to the UI) ----

    suspend fun syncManual(): SyncOutcome = syncManualOnce(allowRetry = true)

    /**
     * @param allowRetry whether a push lost to another device may be re-attempted. Losing means the
     *   cloud changed between this sync's read and its write, so the decision was made against
     *   stale information — reporting a failure would be wrong (nothing failed, and nothing was
     *   lost) and so would pushing anyway. Re-deciding against the new version is the correct
     *   answer, once; a second loss is rare enough to leave to the user.
     */
    private suspend fun syncManualOnce(allowRetry: Boolean): SyncOutcome {
        if (!isEnabled()) return SyncOutcome.Disabled
        val uid = userId() ?: return SyncOutcome.NoUser
        SyncIdentity.reconcileIdentity()
        rebaselineForFilterChange()
        val local = runCatching { packLocal() }.getOrNull() ?: return SyncOutcome.Failed
        val remote = fetchRemote(uid).getOrElse {
            Logger.log("CloudSync: manual sync fetch failed: ${it.message}")
            return SyncOutcome.Failed
        }
        // A device with no baseline can't know whether its settings are the user's work or an
        // install's defaults, so it treats itself as changed and asks. It used to guess, by
        // checking whether any primitive pref differed from its declared default — which reads a
        // device customised only in serialized prefs (home layout, source order, saved filters,
        // search history) as pristine, and silently overwrote exactly those.
        val localChanged = neverSynced() || local.hashCode() != lastHash()
        val remoteChanged = remote != null && remote.ts > lastTs()
        return when {
            remote == null || localChanged && !remoteChanged ->
                // Both are "replace the cloud with ours", and both must yield if another device
                // committed since the read above — including the empty-cloud case, where two
                // devices seeding at once would otherwise have one silently overwrite the other.
                // -1 when the cloud was empty: no real timestamp can satisfy it, so the write
                // lands only while the node is still absent.
                when (doPushIfUnchanged(local, expectedMaxTs = remote?.ts ?: -1L)) {
                    CasOutcome.Written -> SyncOutcome.Pushed
                    CasOutcome.Superseded ->
                        if (allowRetry) syncManualOnce(allowRetry = false) else SyncOutcome.Failed

                    CasOutcome.Failed -> SyncOutcome.Failed
                }

            remoteChanged && localChanged -> resolveDivergence(local, remote, allowRetry)
            remoteChanged -> if (doApply(remote.payload, remote.ts)) SyncOutcome.Pulled else SyncOutcome.Failed
            else -> SyncOutcome.UpToDate
        }
    }

    /**
     * Both sides moved. Merge what can be merged and only surface what genuinely collides.
     *
     * A clean merge is applied and pushed straight away, so the two devices converge without the
     * user being asked to arbitrate a disagreement that doesn't exist. If the merge can't be
     * computed at all the whole-payload choice is still offered — coarse, but never wrong.
     */
    private suspend fun resolveDivergence(
        local: String,
        remote: Remote,
        allowRetry: Boolean,
    ): SyncOutcome {
        val merge = SyncMerge.merge(baseline(), local, remote.payload)
            ?: return SyncOutcome.Conflict(remote.payload, remote.ts, remote.device)

        if (merge.conflicts.isEmpty()) {
            Logger.log("CloudSync: divergence merged cleanly")
            // Publish before adopting. The merge is only valid against the version it was computed
            // from, so if a third write landed in between it has to be redone — and redoing it is
            // only possible while this device still holds its own un-merged copy to merge from.
            val ts = SyncClock.now()
            return when (uploadIfUnchanged(merge.preferringLocal, ts, expectedMaxTs = remote.ts)) {
                CasOutcome.Written ->
                    if (doApply(merge.preferringLocal, ts)) SyncOutcome.Merged
                    else SyncOutcome.Failed

                CasOutcome.Superseded ->
                    if (allowRetry) syncManualOnce(allowRetry = false) else SyncOutcome.Failed

                CasOutcome.Failed -> SyncOutcome.Failed
            }
        }

        Logger.log("CloudSync: ${merge.conflicts.size} setting(s) genuinely conflict")
        return SyncOutcome.Conflict(
            remotePayload = remote.payload,
            remoteTs = remote.ts,
            remoteDevice = remote.device,
            conflicts = merge.conflicts,
            keepLocalPayload = merge.preferringLocal,
            useRemotePayload = merge.preferringRemote,
        )
    }

    private fun baseline(): String? =
        PrefManager.getCustomVal(BASE_KEY, "").takeIf { it.isNotBlank() }

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

    /**
     * Conflict resolution: adopt [payload] on this device and publish it, so both sides end up
     * holding the same thing.
     *
     * [payload] is a *merged* payload — it already carries whatever each device changed on its own,
     * and differs between the two choices only in the settings that genuinely collided. Applying it
     * locally as well as pushing is what makes the choice converge: the old "keep this device"
     * pushed local over the cloud and discarded the other device's unrelated changes entirely.
     */
    suspend fun resolveWith(payload: String, ts: Long): Boolean {
        val uid = userId() ?: return false
        if (!doApply(payload, ts)) return false
        return doPush(uid, runCatching { packLocal() }.getOrNull() ?: return false)
    }

    /** Conflict resolution fallback when no merge was available: keep this device, overwrite cloud. */
    suspend fun resolveKeepLocal(): Boolean = forcePush()

    /** Conflict resolution fallback when no merge was available: take the cloud copy wholesale. */
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
     *
     * @return whether anything was uploaded, and whether a failure is worth another attempt.
     */
    suspend fun pushNow(): PushResult = SyncStatus.uploading {
        if (!isEnabled() || userId() == null || applyingRemote) return@uploading PushResult.NothingToDo
        SyncIdentity.reconcileIdentity()
        rebaselineForFilterChange()
        runCatching {
            userId() ?: return PushResult.NothingToDo
            val local = packLocal()
            if (local.hashCode() == lastHash()) return PushResult.NothingToDo // nothing changed
            // One operation rather than read-then-write: the condition is evaluated by the server
            // against the value it is about to replace, so nothing can slip in between. A cloud
            // that moved on — including the never-synced case, where the baseline is zero and any
            // existing node fails the test — is left for the next pull to reconcile. Not
            // retryable: divergence is a standing state, not a transient one.
            when (doPushIfUnchanged(local, expectedMaxTs = lastTs())) {
                CasOutcome.Written -> PushResult.Pushed
                CasOutcome.Superseded -> PushResult.NothingToDo
                CasOutcome.Failed -> PushResult.Failed
            }
        }.getOrElse {
            Logger.log("CloudSync: push threw: ${it.message}")
            PushResult.Failed
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
            SyncStatus.downloading {
              try {
                SyncIdentity.reconcileIdentity()
        rebaselineForFilterChange()
                runCatching {
                    val uid = userId() ?: return@runCatching
                    val remote = fetchRemote(uid).getOrElse {
                        Logger.log("CloudSync: pull skipped, remote unreadable: ${it.message}")
                        return@runCatching
                    } ?: return@runCatching
                    if (remote.ts <= lastTs()) return@runCatching // remote not newer
                    if (neverSynced()) {
                        // No baseline: this device cannot tell its own settings from an install's
                        // defaults, and a background pull has no UI to ask with. Flag it and let
                        // the prompt handle it — silently adopting is how a configured device used
                        // to lose everything the old heuristic couldn't see.
                        Logger.log("CloudSync: no baseline; deferring to the user")
                        PrefManager.setCustomVal(BOOTSTRAP_PROMPT_KEY, true)
                        return@runCatching
                    } else if (packLocal().hashCode() != lastHash()) {
                        // Correct not to guess, but from the user's side this was sync going quiet
                        // with nothing to explain it. Say so, and let them settle it when they want.
                        Logger.log("CloudSync: divergent (both changed); leaving for manual resolution")
                        SyncConflictNotice.raiseDivergent()
                        return@runCatching
                    }
                    // Live screens have already read the old values, so an applied pull is
                    // otherwise invisible; the notice follows the user to whatever is on screen.
                    if (doApply(remote.payload, remote.ts)) SyncReloadNotice.raise()
                }
              } finally {
                bgInFlight = false
              }
            }
        }
    }
}
