package ani.dantotsu.parsers.novel.lnreader

import android.content.Context
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Installs, updates and removes LNReader plugins.
 *
 * Deliberately not modelled on [ani.dantotsu.parsers.novel.NovelExtensionManager]: that one drives
 * Android's package installer, shows install prompts and listens for package broadcasts, none of
 * which apply to a plugin that is just a downloaded `.js` file. What is kept is the shape the rest
 * of the app already consumes — an installed flow and an available flow — so the extensions screens
 * can treat all three source types alike.
 */
class LNReaderPluginManager(private val context: Context) {

    private val network: NetworkHelper by lazy { Injekt.get() }

    private val _installed = MutableStateFlow<List<InstalledLNReaderPlugin>>(emptyList())
    val installedPluginsFlow = _installed.asStateFlow()

    private val _available = MutableStateFlow<List<LNReaderPlugin>>(emptyList())
    val availablePluginsFlow = _available.asStateFlow()

    /** Where downloaded plugin sources live. One file per plugin, named by id. */
    private val pluginDir: File
        get() = File(context.filesDir, "lnreader_plugins").apply { if (!exists()) mkdirs() }

    private fun sourceFile(id: String) = File(pluginDir, "$id.js")

    init {
        loadInstalled()
    }

    // ---------------------------------------------------------------------------------------
    // Installed
    // ---------------------------------------------------------------------------------------

    /**
     * Rebuilds the installed list from disk.
     *
     * The metadata is kept in preferences while the source sits on disk, so a file deleted out from
     * under us (cache clear, restore) does not leave a ghost entry the user cannot remove.
     */
    fun loadInstalled() {
        val plugins = storedRecords().mapNotNull { encoded ->
            runCatching { decode(encoded) }.getOrNull()
        }.filter { sourceFile(it.id).exists() }

        _installed.value = plugins.map { InstalledLNReaderPlugin(it) }.sortedBy { it.name.lowercase() }
        refreshUpdateFlags()
    }

    fun isInstalled(id: String): Boolean = _installed.value.any { it.id == id }

    fun sourceOf(id: String): String? =
        sourceFile(id).takeIf { it.exists() }?.readText()

    suspend fun install(plugin: LNReaderPlugin): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val source = download(plugin.url)
            // A plugin that cannot be loaded is worse than one that is missing: it would sit in the
            // source list failing every call. Verify before committing it to disk.
            verify(plugin.id, source)
            sourceFile(plugin.id).writeText(source)
            persist(plugin)
            loadInstalled()
        }.onFailure { Logger.log("LNReader install failed for ${plugin.id}: ${it.message}") }
    }

    suspend fun update(installed: InstalledLNReaderPlugin): Result<Unit> {
        val upstream = _available.value.firstOrNull { it.id == installed.id }
            ?: return Result.failure(IllegalStateException("${installed.name} is not in any configured repository"))
        return install(upstream)
    }

    fun uninstall(id: String) {
        sourceFile(id).delete()
        PrefManager.setVal(
            PrefName.LNReaderInstalled,
            storedRecords().filterNot { runCatching { decode(it).id }.getOrNull() == id }.toSet()
        )
        loadInstalled()
    }

    /**
     * Re-downloads any plugin that is recorded as installed but whose source is not on disk.
     *
     * A backup carries the installed *list*, not the bundles — those are files, and a restore onto
     * another device has none of them. Every record keeps the URL it was installed from, so the
     * list can be made real again by fetching them; [loadInstalled] hides a record with no file, so
     * until this runs a restored device simply shows no novel sources.
     *
     * Failures are left recorded rather than dropped: a repository that is briefly unreachable
     * should not silently uninstall the user's sources, and the next call picks them up again.
     *
     * @return how many were fetched.
     */
    suspend fun restoreMissingSources(): Int = withContext(Dispatchers.IO) {
        val missing = storedRecords()
            .mapNotNull { runCatching { decode(it) }.getOrNull() }
            .filterNot { sourceFile(it.id).exists() }
        if (missing.isEmpty()) return@withContext 0

        var restored = 0
        missing.forEach { plugin ->
            runCatching {
                val source = download(plugin.url)
                verify(plugin.id, source)
                sourceFile(plugin.id).writeText(source)
                restored++
            }.onFailure {
                Logger.log("LNReader restore failed for ${plugin.id}: ${it.message}")
            }
        }
        if (restored > 0) withContext(Dispatchers.Main) { loadInstalled() }
        restored
    }

    /**
     * Loads a plugin far enough to prove it runs and reports the id it claims.
     *
     * A mismatch is not fatal — the index and the bundle disagree occasionally — but a bundle that
     * throws on load, or exposes no entry points, would be dead weight in the source list.
     */
    private fun verify(id: String, source: String) {
        LNReaderRuntime.load(context, id, source).use { runtime ->
            val meta = runtime.meta()
            if (meta.optString("id").isBlank() && meta.optString("name").isBlank()) {
                throw IllegalStateException("Plugin exposes no id or name")
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Available
    // ---------------------------------------------------------------------------------------

    /**
     * Fetches every configured repository index.
     *
     * A repository URL may point at the index file itself or at the directory holding it, since
     * both forms circulate; anything that does not parse is skipped with a log rather than failing
     * the whole refresh, so one bad entry cannot hide every other repository's plugins.
     */
    suspend fun findAvailablePlugins() = withContext(Dispatchers.IO) {
        val repos = PrefManager.getVal<Set<String>>(PrefName.LNReaderRepos)
        val all = mutableListOf<LNReaderPlugin>()

        repos.forEach { repo ->
            runCatching {
                val index = download(indexUrl(repo))
                all += parseIndex(index, repo)
            }.onFailure { Logger.log("LNReader repo failed ($repo): ${it.message}") }
        }

        // Later repositories win on id collision, matching how the extension repos behave.
        _available.value = all.associateBy { it.id }.values.sortedBy { it.name.lowercase() }
        refreshUpdateFlags()
    }

    /**
     * Whether a URL serves an LNReader plugin index rather than an extension repository index.
     *
     * The two are told apart by shape: a plugin index is a JSON array of records carrying an `id`
     * and a `url`, while an extension repository index is an object. Nothing in the URL itself
     * distinguishes them, and filing a repository under the wrong list makes it silently list
     * nothing, so it is worth one request to check.
     */
    suspend fun looksLikePluginIndex(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = download(indexUrl(url))
            val array = JSONArray(body)
            if (array.length() == 0) return@runCatching false
            val first = array.optJSONObject(0) ?: return@runCatching false
            first.has("id") && first.has("url")
        }.getOrDefault(false)
    }

    private fun indexUrl(repo: String): String {
        val trimmed = repo.trim().removeSuffix("/")
        return if (trimmed.endsWith(".json")) trimmed else "$trimmed/plugins.min.json"
    }

    private fun parseIndex(json: String, repo: String): List<LNReaderPlugin> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = o.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LNReaderPlugin(
                id = id,
                name = o.optString("name", id),
                site = o.optString("site"),
                lang = o.optString("lang", "Unknown"),
                version = o.optString("version", "0"),
                url = url,
                iconUrl = o.optString("iconUrl").takeIf { it.isNotBlank() },
                customCSS = o.optString("customCSS").takeIf { it.isNotBlank() },
                repository = repo,
            )
        }
    }

    /** Marks installed plugins whose repository now advertises a newer version. */
    private fun refreshUpdateFlags() {
        val available = _available.value.associateBy { it.id }
        if (available.isEmpty()) return
        _installed.value = _installed.value.map { installed ->
            val upstream = available[installed.id]
            val newer = upstream != null && upstream.isNewerThan(installed.plugin.version)
            installed.copy(hasUpdate = newer, availableVersion = upstream?.version.takeIf { newer })
        }
        updatePendingUpdatesCount()
    }

    private fun updatePendingUpdatesCount() {
        PrefManager.setVal(
            PrefName.LNReaderUpdatesCount,
            _installed.value.count { it.hasUpdate }
        )
    }

    // ---------------------------------------------------------------------------------------

    private fun download(url: String): String {
        val request = Request.Builder().url(url).build()
        network.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} for $url")
            return response.body?.string().orEmpty().ifBlank {
                throw IllegalStateException("Empty response for $url")
            }
        }
    }

    private fun persist(plugin: LNReaderPlugin) {
        val withoutOld = storedRecords().filterNot {
            runCatching { decode(it).id }.getOrNull() == plugin.id
        }
        PrefManager.setVal(PrefName.LNReaderInstalled, (withoutOld + encode(plugin)).toSet())
    }

    /**
     * The installed records as stored.
     *
     * Read through the non-null accessor deliberately. `getNullableVal` casts the default it is
     * given to the preference's type before touching the store, so passing `null` for a set-typed
     * preference throws inside its own try/catch and quietly hands back `null` — which reads as
     * "nothing is installed" no matter what was actually saved.
     */
    private fun storedRecords(): Set<String> =
        runCatching { PrefManager.getVal<Set<String>>(PrefName.LNReaderInstalled) }
            .getOrDefault(emptySet())

    /**
     * Metadata is stored as a delimited string rather than JSON because the preference store keeps
     * string sets, and a nested serialiser here would be a migration burden for six fields.
     * [SEPARATOR] is a control character so it cannot occur in a name, url or version.
     */
    private fun encode(p: LNReaderPlugin) = listOf(
        p.id, p.name, p.site, p.lang, p.version, p.url,
        p.iconUrl.orEmpty(), p.customCSS.orEmpty(), p.repository
    ).joinToString(SEPARATOR)

    private fun decode(encoded: String): LNReaderPlugin {
        val parts = encoded.split(SEPARATOR)
        require(parts.size >= 6) { "malformed plugin record" }
        return LNReaderPlugin(
            id = parts[0],
            name = parts[1],
            site = parts[2],
            lang = parts[3],
            version = parts[4],
            url = parts[5],
            iconUrl = parts.getOrNull(6)?.takeIf { it.isNotBlank() },
            customCSS = parts.getOrNull(7)?.takeIf { it.isNotBlank() },
            repository = parts.getOrNull(8).orEmpty(),
        )
    }

    companion object {
        private const val SEPARATOR = ""
        const val DEFAULT_REPO =
            "https://raw.githubusercontent.com/lnreader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json"
    }
}
