package ani.dantotsu.settings.saving

import android.content.Context
import ani.dantotsu.connections.sync.ExtensionSettingsStore
import ani.dantotsu.settings.saving.internal.PreferencePackager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Backup container that bundles the app preferences ([PreferencePackager]) together with the
 * per-extension settings ([ExtensionSettingsStore]).
 *
 * Older backups are just the bare preferences JSON; this wraps both in an object tagged with
 * [MARKER]. [restore] detects the tag and falls back to treating an untagged payload as the legacy
 * bare-preferences format, so existing `.ani`/`.sani` files still import.
 */
object BackupArchive {

    private const val MARKER = "__dantotsu_backup__"
    private const val PREFS = "prefs"
    private const val EXTENSIONS = "extensions"
    private val gson = Gson()

    /** Bundles [prefsJson] (from [PrefManager.exportSelectedPrefs]) with [extensionsJson]. */
    fun pack(prefsJson: String, extensionsJson: String?): String {
        val obj = JsonObject()
        obj.addProperty(MARKER, 1)
        obj.addProperty(PREFS, prefsJson)
        if (extensionsJson != null) obj.addProperty(EXTENSIONS, extensionsJson)
        return gson.toJson(obj)
    }

    /** Restores a payload produced by [pack], or a legacy bare-preferences payload. */
    fun restore(context: Context, raw: String): Boolean {
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull()
        if (root != null && root.isJsonObject && root.asJsonObject.has(MARKER)) {
            val obj = root.asJsonObject
            val prefsOk = obj.get(PREFS)?.asString?.let { PreferencePackager.unpack(it) } ?: false
            val extOk = obj.get(EXTENSIONS)?.asString
                ?.let { ExtensionSettingsStore.import(context, it) } ?: true
            return prefsOk && extOk
        }
        // Legacy backup: the whole payload is the bare preferences JSON.
        return PreferencePackager.unpack(raw)
    }
}
