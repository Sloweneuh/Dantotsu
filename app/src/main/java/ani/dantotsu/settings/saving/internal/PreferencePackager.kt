package ani.dantotsu.settings.saving.internal

import android.content.SharedPreferences
import ani.dantotsu.settings.saving.PrefManager
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
         * @param prune when set, keys this predicate accepts that are *absent* from the payload are
         *   deleted locally, making deletions propagate. Only for full payloads (cloud sync) —
         *   never pass it for a partial restore, which would delete everything left out.
         * @return true if successful, false if error
         */
        fun unpack(
            decryptedJson: String,
            silent: Boolean = false,
            prune: ((Location, String) -> Boolean)? = null,
        ): Boolean {
            val gson = Gson()
            val type = object :
                TypeToken<Map<String, Map<String, Map<String, Any>>>>() {}.type  //oh god...
            val rawPrefsMap: Map<String, Map<String, Map<String, Any>>> =
                gson.fromJson(decryptedJson, type)


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
            return unpackagePreferences(deserializedMap, silent, prune)
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
            prune: ((Location, String) -> Boolean)?,
        ): Boolean {
            var success = true
            // Only locations actually present in the payload are touched, so an older peer that
            // didn't sync a location yet can't cause that location to be wiped here.
            map.forEach { (location, prefMap) ->
                val locationEnum = locationFromString(location)
                if (!PrefManager.importAllPrefs(prefMap, locationEnum, silent, prune))
                    success = false
            }
            return success
        }

        private fun locationFromString(location: String): Location {
            val loc = Location.entries.find { it.name == location }
            return loc ?: throw IllegalArgumentException("Location not found")
        }
    }
}