package ani.dantotsu.settings.saving.internal

import android.content.SharedPreferences
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PreferencePackager {
    //map one or more preference maps for import/export

    companion object {

        /**
         * @param keyFilter optional per-location predicate; a key is packed only when it returns
         *   true. Used by cloud sync, which allowlists some locations and blocklists others.
         * @return a json string of the packed preferences
         */
        fun pack(
            map: Map<Location, SharedPreferences>,
            includeKeys: Set<String>? = null,
            keyFilter: ((Location, String) -> Boolean)? = null,
        ): String {
            val prefsMap = packagePreferences(map, includeKeys, keyFilter)
            val gson = Gson()
            return gson.toJson(prefsMap)
        }

        /**
         * @param silent suppresses the user-facing import snackbars — cloud sync applies payloads
         *   in the background, where a toast per location is noise, not feedback.
         * @param filter when set, defines the key set this import is allowed to touch: keys it
         *   rejects are never written, and keys it accepts that are *absent* from the payload are
         *   deleted locally so deletions propagate. Pass the same predicate used to build the
         *   payload (cloud sync). Never pass one for a partial restore — the pruning half would
         *   delete everything left out.
         * @return true if successful, false if error
         */
        fun unpack(
            decryptedJson: String,
            silent: Boolean = false,
            filter: ((Location, String) -> Boolean)? = null,
        ): Boolean {
            val gson = Gson()
            val type = object :
                TypeToken<Map<String, Map<String, Map<String, Any>>>>() {}.type  //oh god...
            // A payload can be truncated, corrupt, or — on the cloud path — written by anyone who
            // can reach the node. gson throws on malformed input and returns null for a literal
            // "null", and this runs inside a GlobalScope launch on the manual sync path, where an
            // escaping exception takes the process with it.
            val rawPrefsMap: Map<String, Map<String, Map<String, Any>>> = try {
                gson.fromJson(decryptedJson, type)
            } catch (e: Exception) {
                Logger.log("PreferencePackager: malformed payload: ${e.message}")
                null
            } ?: run {
                // Restores reach here through a dialog callback, outside the caller's try/catch, so
                // the throw this replaces took the process with it rather than reporting anything.
                if (!silent) snackString(R.string.error_importing_preferences)
                return false
            }


            val deserializedMap = mutableMapOf<String, Map<String, Any?>>()

            rawPrefsMap.forEach { (prefName, prefValueMap) ->
                val innerMap = mutableMapOf<String, Any?>()

                prefValueMap.forEach { (key, typeValueMap) ->

                    val typeName = typeValueMap["type"] as? String
                    val value = typeValueMap["value"]

                    innerMap[key] =
                        when (typeName) {  //weirdly null sometimes so cast to string
                            "kotlin.Int" -> (value as? Double)?.toInt()
                            "kotlin.String" -> value.toString()
                            "kotlin.Boolean" -> value as? Boolean
                            "kotlin.Float" -> value.toString().toFloatOrNull()
                            "kotlin.Long" -> (value as? Double)?.toLong()
                            "java.util.HashSet" -> value as? ArrayList<*>
                            else -> null
                        }
                }
                deserializedMap[prefName] = innerMap
            }
            val success = unpackagePreferences(deserializedMap, silent, filter)
            // A restore is strong evidence of a second device — nobody restores a backup onto the
            // only phone they own. `silent` is exactly the right discriminator: cloud sync applies
            // payloads silently, a person restoring a file does not. If the backup carried a sync
            // code this device is already linked and the offer declines itself.
            if (success && !silent) {
                runCatching { ani.dantotsu.connections.sync.SyncLinkNotice.offer() }
            }
            return success
        }

        /**
         * @return a map of location names to a map of preference names to their values
         */
        private fun packagePreferences(
            map: Map<Location, SharedPreferences>,
            includeKeys: Set<String>?,
            keyFilter: ((Location, String) -> Boolean)? = null,
        ): Map<String, Map<String, *>> {
            val result = mutableMapOf<String, Map<String, *>>()
            for ((location, preferences) in map) {
                val prefMap = mutableMapOf<String, Any>()
                preferences.all.forEach { (key, value) ->
                    if (includeKeys != null && key !in includeKeys) return@forEach
                    if (keyFilter != null && !keyFilter(location, key)) return@forEach
                    val typeValueMap = mapOf(
                        "type" to value?.javaClass?.kotlin?.qualifiedName,
                        "value" to value
                    )
                    prefMap[key] = typeValueMap
                }
                result[location.name] = prefMap
            }
            return result
        }

        /**
         * @return true if successful, false if error
         */
        private fun unpackagePreferences(
            map: Map<String, Map<String, *>>,
            silent: Boolean,
            filter: ((Location, String) -> Boolean)?,
        ): Boolean {
            var success = true
            // Only locations actually present in the payload are touched, so an older peer that
            // didn't sync a location yet can't cause that location to be wiped here.
            map.forEach { (location, prefMap) ->
                val locationEnum = locationFromString(location)
                if (locationEnum == null) {
                    // A filtered import is scoped to a known set of locations by construction, so
                    // one it doesn't recognise is simply out of scope — a peer on a newer build
                    // syncing something this one has no concept of. An unfiltered restore has no
                    // such scope, so there it means the payload is corrupt or from a newer app.
                    Logger.log("PreferencePackager: unknown location '$location' in payload")
                    if (filter == null) success = false
                    return@forEach
                }
                if (!PrefManager.importAllPrefs(prefMap, locationEnum, silent, filter))
                    success = false
            }
            return success
        }

        private fun locationFromString(location: String): Location? =
            Location.entries.find { it.name == location }
    }
}