package ani.dantotsu.settings.saving

import android.content.Context
import ani.dantotsu.connections.sync.ExtensionSettingsStore
import ani.dantotsu.parsers.novel.lnreader.LNReaderPluginManager
import ani.dantotsu.settings.saving.internal.PreferencePackager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
            if (prefsOk) fetchRestoredPlugins()
            return prefsOk && extOk
        }
        // Legacy backup: the whole payload is the bare preferences JSON.
        return PreferencePackager.unpack(raw).also { if (it) fetchRestoredPlugins() }
    }

    /**
     * Downloads the LNReader plugins the restored preferences say are installed.
     *
     * The backup carries their records, not their sources — a plugin is a JavaScript file, and the
     * backup is a preferences payload. Without this the novel sources stay empty until the next
     * launch, which does the same thing on start.
     *
     * Fire and forget: the installed list is a flow, so the screens pick the plugins up whenever
     * they arrive, and a repository being unreachable must not fail the restore around it.
     */
    private fun fetchRestoredPlugins() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { Injekt.get<LNReaderPluginManager>().restoreMissingSources() }
        }
    }
}
