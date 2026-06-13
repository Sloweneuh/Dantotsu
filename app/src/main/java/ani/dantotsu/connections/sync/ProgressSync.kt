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
 * Syncs per-media "continue where you left off" progress across a user's devices — the resume data
 * that AniList itself doesn't store: playback position per episode (`<id>_<ep>`), reading page per
 * chapter (`<id>_<chapter>` / `<id>_<chapter>_max`) and the current chapter (`<id>_current_chp`).
 * These live as custom vals (see e.g. `ExoplayerView`/`MangaReaderActivity`).
 *
 * Each media gets its own child node `users/{uid}/progress/{mediaId}` so independent reading on
 * different devices doesn't last-write-wins over each other; only changed media are uploaded. As in
 * [CloudSync], the background paths never clobber: a pull skips any media whose local copy diverged
 * since the last sync. Gated on the master [PrefName.CloudSyncEnabled] toggle.
 *
 * Device-specific per-media keys (speed, fullscreen, sub language, save-progress toggles…) have
 * non-numeric suffixes and are deliberately excluded by [PROGRESS_RE].
 */
object ProgressSync {

    private const val ROOT = "users"
    private const val NODE = "progress"
    private const val STATE_KEY = "progress_sync_state"

    // "<id>_<num>", "<id>_<num>_max", or "<id>_current_chp" — num may be decimal. Positive ids only
    // (extension-only media with id < 0 can't be re-fetched elsewhere, so they're skipped).
    private val PROGRESS_RE = Regex("""^(\d+)_(?:\d+(?:\.\d+)?(?:_max)?|current_chp)$""")

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

    /** All progress custom vals grouped by media id → { key → {type, value} }. */
    private fun collect(): Map<String, Map<String, Map<String, Any?>>> {
        val out = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()
        PrefManager.getAllCustomValsForMedia("").forEach { (key, value) ->
            val id = PROGRESS_RE.matchEntire(key)?.groupValues?.get(1) ?: return@forEach
            out.getOrPut(id) { mutableMapOf() }[key] = typed(value)
        }
        return out
    }

    private fun applyMedia(data: Map<String, Map<String, Any?>>) {
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
    fun pushInBackground() {
        if (!enabled() || userId() == null) return
        scope.launch {
            runCatching {
                val uid = userId() ?: return@runCatching
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
                if (updates.isEmpty()) return@runCatching
                if (pushChanges(uid, updates)) {
                    state.putAll(pending)
                    saveState(state)
                    Logger.log("ProgressSync: pushed ${updates.size} media")
                }
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
                        applyMedia(data)
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
