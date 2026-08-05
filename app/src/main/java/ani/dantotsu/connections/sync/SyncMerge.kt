package ani.dantotsu.connections.sync

import ani.dantotsu.util.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** `{ location -> { key -> { "type": …, "value": … } } }` — the packed-prefs shape. */
private typealias PackedPrefs = Map<String, Map<String, Map<String, Any?>>>

/**
 * Three-way merge of two packed-preference payloads against the baseline they diverged from.
 *
 * The old conflict prompt was all-or-nothing: one dialog for the entire settings blob, so changing
 * the theme here and the reader direction there meant losing one of them, with nothing on screen
 * saying which. Almost none of that is a real conflict — the two devices usually touched entirely
 * different settings, and a merge can see that as long as it knows what both started from.
 *
 * Hence the baseline: [CloudSync] keeps a copy of the payload it last agreed with the cloud on, so
 * a key that changed on exactly one side has an unambiguous answer. Only keys that moved on *both*
 * sides, to different values, are true conflicts, and those are the only ones worth asking about.
 *
 * Without a baseline (a device that has never synced) every difference is a conflict, which is the
 * honest reading: there is genuinely no way to tell who changed what.
 */
object SyncMerge {

    private val gson = Gson()

    private val payloadType =
        object : TypeToken<Map<String, Map<String, Map<String, Any?>>>>() {}.type

    /**
     * One setting both sides changed, with what each of them changed it to.
     *
     * The values are carried through so the prompt can show the actual disagreement rather than
     * just naming it — deciding between "Dark" and "Light" is a real choice, while deciding between
     * `UI.DarkMode` and `UI.DarkMode` is not.
     */
    data class Conflict(
        /** The preference's own name, without the file it lives in. */
        val key: String,
        /** `{type, value}` as stored, or null when the setting is absent on that side. */
        val local: Map<String, Any?>?,
        val remote: Map<String, Any?>?,
    )

    data class Result(
        /** Each setting that changed on both sides, to different values. */
        val conflicts: List<Conflict>,
        /** Everything merged, with conflicts resolved in favour of this device. */
        val preferringLocal: String,
        /** Everything merged, with conflicts resolved in favour of the cloud. */
        val preferringRemote: String,
    )

    /**
     * @param base the payload both sides last agreed on, or null when this device has no baseline.
     * @return null when either side can't be parsed — the caller falls back to the whole-payload
     *   choice, which is always available and never wrong, only coarse.
     */
    fun merge(base: String?, local: String, remote: String): Result? {
        val localMap = parse(local) ?: return null
        val remoteMap = parse(remote) ?: return null
        val baseMap = base?.let { parse(it) }

        val conflicts = mutableListOf<Conflict>()
        val mergedLocal = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()
        val mergedRemote = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()

        for (location in localMap.keys + remoteMap.keys) {
            val l = localMap[location].orEmpty()
            val r = remoteMap[location].orEmpty()
            val b = baseMap?.get(location).orEmpty()
            val outLocal = mutableMapOf<String, Map<String, Any?>>()
            val outRemote = mutableMapOf<String, Map<String, Any?>>()

            for (key in l.keys + r.keys) {
                val lv = l[key]
                val rv = r[key]
                val bv = b[key]
                // A key absent from a side is a real state, not a gap: it means deleted there, and
                // it has to win over a stale copy on the other side the same way a changed value
                // would. Absence is carried by simply not writing it to the output.
                when {
                    lv == rv -> lv?.let { outLocal[key] = it; outRemote[key] = it }

                    // Only one side moved: that side is the answer, on both variants.
                    baseMap != null && lv == bv -> rv?.let { outLocal[key] = it; outRemote[key] = it }
                    baseMap != null && rv == bv -> lv?.let { outLocal[key] = it; outRemote[key] = it }

                    else -> {
                        conflicts += Conflict(key, lv, rv)
                        lv?.let { outLocal[key] = it }
                        rv?.let { outRemote[key] = it }
                    }
                }
            }
            if (outLocal.isNotEmpty()) mergedLocal[location] = outLocal
            if (outRemote.isNotEmpty()) mergedRemote[location] = outRemote
        }

        return runCatching {
            Result(
                conflicts = conflicts.sortedBy { it.key },
                preferringLocal = gson.toJson(mergedLocal),
                preferringRemote = gson.toJson(mergedRemote),
            )
        }.getOrElse {
            Logger.log("SyncMerge: could not re-encode merged payload: ${it.message}")
            null
        }
    }

    private fun parse(json: String): PackedPrefs? = runCatching {
        gson.fromJson<PackedPrefs>(json, payloadType)
    }.getOrNull()

}
