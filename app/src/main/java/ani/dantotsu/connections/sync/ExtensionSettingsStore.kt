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

    /**
     * Key fragments that mark a value as a credential rather than a setting.
     *
     * Sources choose their own preference keys, so this can only ever be a heuristic — but it costs
     * nothing and covers the conventional names. It applies to the *cloud* path only: a local
     * backup is as private as the device it's written from and should stay complete, whereas the
     * cloud copy is the one whose whole justification is that it doesn't need to be trusted.
     *
     * A source using an unconventional key still syncs its credential, which is why the toggle
     * keeps its warning. This narrows the exposure; it doesn't remove it.
     */
    private val CREDENTIAL_HINTS = listOf(
        "password", "passwd", "token", "secret", "apikey", "api_key",
        "auth", "session", "cookie", "credential", "bearer", "refresh",
    )

    private fun looksLikeCredential(key: String): Boolean {
        val lower = key.lowercase()
        return CREDENTIAL_HINTS.any { it in lower }
    }


    private fun sharedPrefsDir(context: Context) =
        File(context.applicationInfo.dataDir, "shared_prefs")

    private fun sourcePrefNames(context: Context): List<String> =
        sharedPrefsDir(context).listFiles()
            ?.filter { it.name.startsWith(PREFIX) && it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?: emptyList()

    /**
     * @param excludeCredentials drops keys that look like logins (see [CREDENTIAL_HINTS]). Set for
     *   the cloud path; left off for local backups, which are expected to restore a device whole.
     * @return JSON of `{ "source_<id>": { key: { type, value } } }`, empty object when none.
     */
    fun export(context: Context, excludeCredentials: Boolean = false): String {
        val out = mutableMapOf<String, Map<String, Map<String, Any?>>>()
        var dropped = 0
        sourcePrefNames(context).forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = mutableMapOf<String, Map<String, Any?>>()
            prefs.all.forEach { (key, value) ->
                if (excludeCredentials && looksLikeCredential(key)) {
                    dropped++
                    return@forEach
                }
                entries[key] = mapOf(
                    "type" to value?.javaClass?.kotlin?.qualifiedName,
                    "value" to value,
                )
            }
            if (entries.isNotEmpty()) out[name] = entries
        }
        if (dropped > 0) Logger.log("ExtensionSettingsStore: kept $dropped credential(s) off the cloud")
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
