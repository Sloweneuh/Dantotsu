package ani.dantotsu.connections.sync

import android.content.Context
import ani.dantotsu.util.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Serializes and restores per-extension settings. Aniyomi/Tachiyomi extensions keep their own
 * configuration in `source_<id>` [android.content.SharedPreferences] files (see
 * `AnimeSource.getPreferenceKey()`), which the [ani.dantotsu.settings.saving.PrefName]-based backup
 * system doesn't cover. This walks the app's `shared_prefs` directory, captures every such file with
 * type information (mirroring
 * [ani.dantotsu.settings.saving.internal.PreferencePackager]), and can write them back.
 *
 * Note: some sources store login tokens here. That's fine in a local backup, but cloud sync of these
 * is gated behind an opt-in toggle with a warning.
 */
object ExtensionSettingsStore {

    private const val PREFIX = "source_"
    private val gson = Gson()

    private fun sharedPrefsDir(context: Context) =
        File(context.applicationInfo.dataDir, "shared_prefs")

    private fun sourcePrefNames(context: Context): List<String> =
        sharedPrefsDir(context).listFiles()
            ?.filter { it.name.startsWith(PREFIX) && it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?: emptyList()

    /** @return JSON of `{ "source_<id>": { key: { type, value } } }`, empty object when none. */
    fun export(context: Context): String {
        val out = mutableMapOf<String, Map<String, Map<String, Any?>>>()
        sourcePrefNames(context).forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = mutableMapOf<String, Map<String, Any?>>()
            prefs.all.forEach { (key, value) ->
                entries[key] = mapOf(
                    "type" to value?.javaClass?.kotlin?.qualifiedName,
                    "value" to value,
                )
            }
            if (entries.isNotEmpty()) out[name] = entries
        }
        return gson.toJson(out)
    }

    /** Restores prefs produced by [export]. @return true on success (including an empty payload). */
    fun import(context: Context, json: String): Boolean {
        return try {
            val type = object :
                TypeToken<Map<String, Map<String, Map<String, Any>>>>() {}.type
            val map: Map<String, Map<String, Map<String, Any>>> =
                gson.fromJson(json, type) ?: return true

            map.forEach { (prefName, entries) ->
                val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                with(prefs.edit()) {
                    entries.forEach { (key, typeValue) ->
                        val typeName = typeValue["type"] as? String
                        val value = typeValue["value"]
                        when (typeName) {  // gson numbers come back as Double
                            "kotlin.Int" -> (value as? Double)?.let { putInt(key, it.toInt()) }
                            "kotlin.String" -> putString(key, value?.toString())
                            "kotlin.Boolean" -> (value as? Boolean)?.let { putBoolean(key, it) }
                            "kotlin.Float" -> value?.toString()?.toFloatOrNull()?.let { putFloat(key, it) }
                            "kotlin.Long" -> (value as? Double)?.let { putLong(key, it.toLong()) }
                            "java.util.HashSet" ->
                                putStringSet(key, (value as? List<*>)?.map { it.toString() }?.toSet())
                            else -> {}
                        }
                    }
                    apply()
                }
            }
            true
        } catch (e: Exception) {
            Logger.log("ExtensionSettingsStore: import failed: ${e.message}")
            false
        }
    }
}
