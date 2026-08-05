package ani.dantotsu.connections.sync

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Syncs per-media state across a user's devices — the things AniList itself doesn't store:
 *  - resume data: playback position per episode (`<id>_<ep>`), reading page per chapter
 *    (`<id>_<chapter>` / `<id>_<chapter>_max`), current chapter/episode (`<id>_current_chp|_ep`)
 *  - the user's per-media choices: which source is selected (`SelectedSource-<id>`, plus the
 *    `Selected-<id>` blob holding language/scanlators/dub preference), and the per-media lookups
 *    `comick_slug_<id>` / `subLang_<id>`
 *
 * These all live as custom vals (see e.g. `ExoplayerView`, `MangaReaderActivity`,
 * `MediaDetailsViewModel`), i.e. in [ani.dantotsu.settings.saving.internal.Location.Irrelevant].
 *
 * Each media gets its own child node `users/{uid}/progress/{mediaId}` so independent reading on
 * different devices doesn't last-write-wins over each other; only changed media are uploaded. As in
 * [CloudSync], the background paths never clobber: a pull skips any media whose local copy diverged
 * since the last sync. Gated on the master [PrefName.CloudSyncEnabled] toggle.
 *
 * Per-media state is deliberately synced *here* rather than in [CloudSync]'s settings blob: it
 * changes constantly and there can be thousands of keys, so folding it into one last-write-wins
 * payload would make every source switch on one device conflict with every settings tweak on
 * another. Genuinely device-local per-media keys (playback speed, fullscreen, save-progress
 * toggles…) are excluded by [mediaIdOf].
 */
object ProgressSync {

    private const val NODE = "progress"
    private const val STATE_KEY = "progress_sync_state"
    private const val FLOOR_KEY = "progress_pull_floor"

    // "<id>_<num>", "<id>_<num>_max", "<id>_current_chp" or "<id>_current_ep" — num may be decimal.
    private val PROGRESS_RE =
        Regex("""^(\d+)_(?:\d+(?:\.\d+)?(?:_max)?|current_chp|current_ep)$""")

    // Per-media user choices, keyed with the id as a suffix instead of a prefix.
    private val SELECTION_RE = Regex("""^(?:Selected|SelectedSource|comick_slug|subLang)[-_](\d+)$""")

    /**
     * The media id a custom-val key belongs to, or null if it isn't per-media syncable state.
     * Positive ids only — extension-only media with id < 0 can't be re-resolved on another device.
     */
    private fun mediaIdOf(key: String): String? =
        (PROGRESS_RE.matchEntire(key) ?: SELECTION_RE.matchEntire(key))?.groupValues?.get(1)

    private val gson = Gson()
    private val stateType = object : TypeToken<Map<String, MediaState>>() {}.type
    private val dataType = object : TypeToken<Map<String, Map<String, Any?>>>() {}.type

    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var pullInFlight = false

    private data class MediaState(val hash: Int, val ts: Long)

    private fun enabled(): Boolean =
        PrefManager.getVal<Boolean>(PrefName.CloudSyncEnabled) && SyncIdentity.isLinked()

    private fun userId(): String? =
        PrefManager.getVal<String>(PrefName.AnilistUserId).takeIf { it.isNotBlank() }

    private fun node() = SyncIdentity.node(NODE)

    // ---- local progress snapshot ----

    private fun typed(value: Any?): Map<String, Any?> =
        mapOf("type" to value?.javaClass?.kotlin?.qualifiedName, "value" to value)

    /** All syncable per-media custom vals grouped by media id → { key → {type, value} }. */
    private fun collect(): Map<String, Map<String, Map<String, Any?>>> {
        val out = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()
        PrefManager.getAllCustomValsForMedia("").forEach { (key, value) ->
            val id = mediaIdOf(key) ?: return@forEach
            out.getOrPut(id) { mutableMapOf() }[key] = typed(value)
        }
        return out
    }

    /**
     * @param localKeys what this device currently holds for the same media. Anything in there that
     *   the cloud copy doesn't have was deleted on the other device (e.g. "delete stored progress
     *   for all episodes"); without removing it here the deletion never propagates and our next
     *   push would resurrect it over there.
     *
     * Mutations are collected into [sets]/[removes] rather than written immediately — a pull can
     * touch hundreds of media, and writing each key through its own `apply()` flooded Android's
     * QueuedWork queue, which every Activity/Service stop then blocks on (background ANRs). The
     * caller flushes everything through one [PrefManager.applyCustomVals] call instead.
     */
    private fun applyMedia(
        data: Map<String, Map<String, Any?>>,
        localKeys: Set<String>,
        sets: MutableMap<String, Any>,
        removes: MutableSet<String>,
    ) {
        // Prune per category, and only when the cloud copy actually carries that category. A node
        // last written by a build that synced progress but not selections holds progress keys only
        // — pruning against it wholesale would delete the very selections we're here to sync.
        val remoteHasProgress = data.keys.any { PROGRESS_RE.matches(it) }
        val remoteHasSelection = data.keys.any { SELECTION_RE.matches(it) }
        localKeys.forEach { key ->
            if (key in data) return@forEach
            val prunable = when {
                PROGRESS_RE.matches(key) -> remoteHasProgress
                SELECTION_RE.matches(key) -> remoteHasSelection
                else -> false
            }
            if (prunable) removes += key
        }
        data.forEach { (key, tv) ->
            val type = tv["type"] as? String
            val value = tv["value"]
            when (type) {  // gson numbers arrive as Double
                "kotlin.Int" -> (value as? Double)?.let { sets[key] = it.toInt() }
                "kotlin.Long" -> (value as? Double)?.let { sets[key] = it.toLong() }
                "kotlin.Float" -> value?.toString()?.toFloatOrNull()?.let { sets[key] = it }
                "kotlin.String" -> {
                    val s = value?.toString()
                    if (s == null) removes += key else sets[key] = s
                }
                "kotlin.Boolean" -> (value as? Boolean)?.let { sets[key] = it }
                // A type this build has no case for — written by a newer one. Dropping it silently
                // made that indistinguishable from progress that simply never arrived.
                else -> Logger.log("ProgressSync: ignoring $key of unsupported type $type")
            }
        }
    }

    private fun loadState(): MutableMap<String, MediaState> {
        val json = PrefManager.getCustomVal(STATE_KEY, "")
        if (json.isBlank()) return mutableMapOf()
        return runCatching { gson.fromJson<Map<String, MediaState>>(json, stateType) }
            .getOrNull()?.toMutableMap() ?: mutableMapOf()
    }

    private fun saveState(state: Map<String, MediaState>) =
        PrefManager.setCustomVal(STATE_KEY, gson.toJson(state))

    /**
     * The timestamp the next pull asks the server to start from. Everything at or below it has
     * already been seen, so there is no reason to transfer it again.
     */
    private fun pullFloor(): Long = PrefManager.getCustomVal(FLOOR_KEY, 0L)

    // ---- Firebase primitives ----

    private suspend fun pushChanges(uid: String, updates: Map<String, Any>): Boolean =
        node()?.updateChildren(updates)?.awaitOk() ?: false

    /**
     * Media written since [since], newest state only.
     *
     * This used to read the entire `progress` node — every media, every payload — on cold start, on
     * login, and on every foreground resume more than five minutes apart, on each device, in order
     * to discover that one or two entries had changed. A library of any size made that the bulk of
     * the sync's traffic. `orderByChild("ts")` pushes the filter to the server, so a pull that has
     * nothing to collect transfers nothing; it needs `".indexOn": "ts"` on the node, without which
     * Firebase falls back to downloading everything and sorting here.
     *
     * @return each media's decrypted payload; entries written under another secret are skipped.
     */
    private suspend fun fetchSince(since: Long): List<Triple<String, String, Long>>? = runCatching {
        val node = node() ?: return@runCatching null
        // startAt is inclusive and takes a Double; +1 makes it "strictly newer than what we have".
        val query = if (since > 0) node.orderByChild("ts").startAt((since + 1).toDouble()) else node
        query.getSnapshot().children.mapNotNull { child ->
            val id = child.key ?: return@mapNotNull null
            if (!child.schemaIsReadable("ProgressSync[$id]")) return@mapNotNull null
            val sealed = child.child("payload").getValue(String::class.java)
                ?: return@mapNotNull null
            val payload = SyncIdentity.open(sealed) ?: return@mapNotNull null
            val ts = child.child("ts").getValue(Long::class.java) ?: return@mapNotNull null
            Triple(id, payload, ts)
        }
    }.getOrNull()

    // ---- triggers ----

    /** Push on app background: upload media whose progress changed since the last sync. */
    suspend fun pushNow(): PushResult {
        if (!enabled() || userId() == null) return PushResult.NothingToDo
        return runCatching {
            val uid = userId() ?: return PushResult.NothingToDo
            val grouped = collect()
            val state = loadState()
            val updates = mutableMapOf<String, Any>()
            val pending = mutableMapOf<String, MediaState>()
            val now = SyncClock.now()
            grouped.forEach { (id, data) ->
                val json = gson.toJson(data)
                val hash = json.hashCode()
                if (state[id]?.hash != hash) {
                    // Skip rather than fail the whole push: one oversized media shouldn't stop the
                    // rest, and retrying it would be rejected identically every time.
                    if (!fitsInNode(json, NodeLimits.PROGRESS_MEDIA, "ProgressSync[$id]")) {
                        return@forEach
                    }
                    // Hash the plaintext, upload the ciphertext: sealing is randomised, so hashing
                    // the sealed form would make every media look changed on every push.
                    val sealed = SyncIdentity.seal(json) ?: return PushResult.Failed
                    updates[id] =
                        mapOf("payload" to sealed, "ts" to now, "v" to SYNC_SCHEMA_VERSION)
                    pending[id] = MediaState(hash, now)
                }
            }
            if (updates.isEmpty()) return PushResult.NothingToDo
            if (!pushChanges(uid, updates)) return PushResult.Failed
            state.putAll(pending)
            saveState(state)
            Logger.log("ProgressSync: pushed ${updates.size} media")
            PushResult.Pushed
        }.getOrElse {
            Logger.log("ProgressSync: push threw: ${it.message}")
            PushResult.Failed
        }
    }

    /** Pull on launch/login: apply newer remote progress, skipping locally-diverged media. */
    fun pullInBackground() {
        if (!enabled() || userId() == null || pullInFlight) return
        pullInFlight = true
        scope.launch {
            try {
                runCatching {
                    userId() ?: return@runCatching
                    SyncIdentity.reconcileIdentity()
                    val since = pullFloor()
                    val remote = fetchSince(since) ?: return@runCatching
                    if (remote.isEmpty()) return@runCatching
                    val state = loadState()
                    val local = collect()
                    val applied = mutableMapOf<String, Long>()
                    val sets = mutableMapOf<String, Any>()
                    val removes = mutableSetOf<String>()
                    // The floor may only advance past media this pull is finished with. Anything
                    // deferred has to stay inside the next query's window or it would never be
                    // offered again — the server-side filter has no memory of what we declined.
                    var deferredFrom = Long.MAX_VALUE
                    var highest = since
                    remote.forEach { (id, payload, ts) ->
                        if (ts > highest) highest = ts
                        if (ts <= (state[id]?.ts ?: 0L)) return@forEach
                        // Don't clobber local progress that changed since we last synced this media.
                        val st = state[id]
                        if (st != null && (local[id]?.let { gson.toJson(it).hashCode() } ?: 0) != st.hash) {
                            if (ts < deferredFrom) deferredFrom = ts
                            return@forEach
                        }
                        val data = runCatching { gson.fromJson<Map<String, Map<String, Any?>>>(payload, dataType) }
                            .getOrNull() ?: run {
                                if (ts < deferredFrom) deferredFrom = ts
                                return@forEach
                            }
                        applyMedia(data, local[id]?.keys.orEmpty(), sets, removes)
                        applied[id] = ts
                    }
                    // One flush for the whole pull instead of one apply() per key (see [applyMedia]).
                    PrefManager.applyCustomVals(sets, removes)
                    if (applied.isNotEmpty()) {
                        // Re-hash from what's now stored locally so the next push doesn't echo it back.
                        val fresh = collect()
                        applied.forEach { (id, ts) ->
                            state[id] = MediaState(gson.toJson(fresh[id]).hashCode(), ts)
                        }
                        saveState(state)
                        Logger.log("ProgressSync: applied ${applied.size} media")
                    }
                    // Stop one short of the oldest thing we left behind, else take everything seen.
                    val floor = if (deferredFrom == Long.MAX_VALUE) highest else deferredFrom - 1
                    if (floor > since) PrefManager.setCustomVal(FLOOR_KEY, floor)
                }
            } finally {
                pullInFlight = false
            }
        }
    }

    /**
     * Forgets how far the incremental pull has read, so the next one re-reads the whole node.
     *
     * Needed whenever the local baseline stops describing what this device actually holds — after
     * a wipe or a re-link — because the floor is otherwise a claim about data this device may no
     * longer have.
     */
    fun resetPullFloor() = PrefManager.setCustomVal(FLOOR_KEY, 0L)
}
