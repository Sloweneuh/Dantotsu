package ani.dantotsu.connections.sync

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import com.google.firebase.database.FirebaseDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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

    private const val ROOT = "users"
    private const val NODE = "progress"
    private const val STATE_KEY = "progress_sync_state"

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

    private fun enabled(): Boolean = PrefManager.getVal(PrefName.CloudSyncEnabled)

    private fun userId(): String? =
        PrefManager.getVal<String>(PrefName.AnilistUserId).takeIf { it.isNotBlank() }

    private fun node(uid: String) =
        FirebaseDatabase.getInstance().reference.child(ROOT).child(uid).child(NODE)

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
     */
    private fun applyMedia(data: Map<String, Map<String, Any?>>, localKeys: Set<String>) {
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
            if (prunable) PrefManager.removeCustomVal(key)
        }
        data.forEach { (key, tv) ->
            val type = tv["type"] as? String
            val value = tv["value"]
            when (type) {  // gson numbers arrive as Double
                "kotlin.Int" -> (value as? Double)?.let { PrefManager.setCustomVal(key, it.toInt()) }
                "kotlin.Long" -> (value as? Double)?.let { PrefManager.setCustomVal(key, it.toLong()) }
                "kotlin.Float" -> value?.toString()?.toFloatOrNull()?.let { PrefManager.setCustomVal(key, it) }
                "kotlin.String" -> PrefManager.setCustomVal(key, value?.toString())
                "kotlin.Boolean" -> (value as? Boolean)?.let { PrefManager.setCustomVal(key, it) }
                else -> {}
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

    // ---- Firebase primitives ----

    private suspend fun pushChanges(uid: String, updates: Map<String, Any>): Boolean = runCatching {
        suspendCancellableCoroutine { cont ->
            node(uid).updateChildren(updates)
                .addOnSuccessListener { cont.resume(true) }
                .addOnFailureListener { cont.resume(false) }
        }
    }.getOrDefault(false)

    private suspend fun fetchAll(uid: String): List<Triple<String, String, Long>>? = runCatching {
        suspendCancellableCoroutine<List<Triple<String, String, Long>>?> { cont ->
            node(uid).get()
                .addOnSuccessListener { snap ->
                    val list = snap.children.mapNotNull { child ->
                        val id = child.key ?: return@mapNotNull null
                        val payload = child.child("payload").getValue(String::class.java)
                            ?: return@mapNotNull null
                        val ts = child.child("ts").getValue(Long::class.java) ?: return@mapNotNull null
                        Triple(id, payload, ts)
                    }
                    cont.resume(list)
                }
                .addOnFailureListener { cont.resume(null) }
        }
    }.getOrNull()

    // ---- triggers ----

    /** Push on app background: upload media whose progress changed since the last sync. */
    suspend fun pushNow() {
        if (!enabled() || userId() == null) return
        runCatching {
            val uid = userId() ?: return
            val grouped = collect()
            val state = loadState()
            val updates = mutableMapOf<String, Any>()
            val pending = mutableMapOf<String, MediaState>()
            val now = System.currentTimeMillis()
            grouped.forEach { (id, data) ->
                val hash = gson.toJson(data).hashCode()
                if (state[id]?.hash != hash) {
                    updates[id] = mapOf("payload" to gson.toJson(data), "ts" to now)
                    pending[id] = MediaState(hash, now)
                }
            }
            if (updates.isEmpty()) return
            if (pushChanges(uid, updates)) {
                state.putAll(pending)
                saveState(state)
                Logger.log("ProgressSync: pushed ${updates.size} media")
            }
        }
    }

    /** Pull on launch/login: apply newer remote progress, skipping locally-diverged media. */
    fun pullInBackground() {
        if (!enabled() || userId() == null || pullInFlight) return
        pullInFlight = true
        scope.launch {
            try {
                runCatching {
                    val uid = userId() ?: return@runCatching
                    val remote = fetchAll(uid) ?: return@runCatching
                    val state = loadState()
                    val local = collect()
                    val applied = mutableMapOf<String, Long>()
                    remote.forEach { (id, payload, ts) ->
                        if (ts <= (state[id]?.ts ?: 0L)) return@forEach
                        // Don't clobber local progress that changed since we last synced this media.
                        val st = state[id]
                        if (st != null && (local[id]?.let { gson.toJson(it).hashCode() } ?: 0) != st.hash) {
                            return@forEach
                        }
                        val data = runCatching { gson.fromJson<Map<String, Map<String, Any?>>>(payload, dataType) }
                            .getOrNull() ?: return@forEach
                        applyMedia(data, local[id]?.keys.orEmpty())
                        applied[id] = ts
                    }
                    if (applied.isNotEmpty()) {
                        // Re-hash from what's now stored locally so the next push doesn't echo it back.
                        val fresh = collect()
                        applied.forEach { (id, ts) ->
                            state[id] = MediaState(gson.toJson(fresh[id]).hashCode(), ts)
                        }
                        saveState(state)
                        Logger.log("ProgressSync: applied ${applied.size} media")
                    }
                }
            } finally {
                pullInFlight = false
            }
        }
    }
}
