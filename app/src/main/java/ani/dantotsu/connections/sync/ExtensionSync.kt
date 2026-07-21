package ani.dantotsu.connections.sync

import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import com.google.firebase.database.FirebaseDatabase
import com.google.gson.Gson
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import kotlinx.coroutines.suspendCancellableCoroutine
import rx.android.schedulers.AndroidSchedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Syncs the *list* of installed extensions across a user's devices (keyed by the Anilist account),
 * as an opt-in complement to [CloudSync]. Only package names are synced — never the APK binaries,
 * which are megabytes each and must go through Android's package installer.
 *
 * Publishing the installed set is automatic and harmless (it just mirrors what this device has).
 * Acting on a difference is always an explicit user choice via the reconcile dialog: [computeDiff]
 * returns both directions (install what's on another device, remove what isn't) and nothing is
 * installed or uninstalled until the user confirms. Installs still surface the system installer
 * prompt — there is no silent path on stock Android.
 *
 * The node is last-write-wins, so it reflects the most recently active device's set; the reconcile
 * diff is always computed against whatever is currently there.
 */
object ExtensionSync {

    private const val ROOT = "users"
    private const val NODE = "extensions"
    private const val TS_KEY = "ext_sync_ts"
    private const val HASH_KEY = "ext_sync_hash"

    private val gson = Gson()

    enum class ExtType { ANIME, MANGA, NOVEL }

    data class ExtItem(
        val type: ExtType,
        val pkgName: String,
        val name: String,
        /** True = needs installing here, false = present here but not on the other device. */
        val isInstall: Boolean,
        /** False for installs whose extension can't be found in the available repos. */
        val available: Boolean = true,
        /** Repo icon URL for installs; removals load the installed app icon instead. */
        val iconUrl: String? = null,
    )

    private data class AvailInfo(val name: String, val iconUrl: String?)

    /** Symmetric reconcile: extensions to add here vs. extensions here but not on the other device. */
    data class Diff(val toInstall: List<ExtItem>, val toRemove: List<ExtItem>)

    // pkg + name. The name is published alongside the package so a device that doesn't have the
    // matching repo can still show a human-readable label instead of the bare package name.
    private data class ExtRef(val pkg: String, val name: String)

    private data class Payload(
        val anime: List<ExtRef> = emptyList(),
        val manga: List<ExtRef> = emptyList(),
        val novel: List<ExtRef> = emptyList(),
    )

    private fun enabled(): Boolean = PrefManager.getVal(PrefName.SyncExtensionsEnabled)

    private fun userId(): String? =
        PrefManager.getVal<String>(PrefName.AnilistUserId).takeIf { it.isNotBlank() }

    private fun node(uid: String) =
        FirebaseDatabase.getInstance().reference.child(ROOT).child(uid).child(NODE)

    private fun anime() = Injekt.get<AnimeExtensionManager>()
    private fun manga() = Injekt.get<MangaExtensionManager>()
    private fun novel() = Injekt.get<NovelExtensionManager>()

    private fun localPayload(): Payload = Payload(
        anime = anime().installedExtensionsFlow.value.map { ExtRef(it.pkgName, it.name) }.sortedBy { it.pkg },
        manga = manga().installedExtensionsFlow.value.map { ExtRef(it.pkgName, it.name) }.sortedBy { it.pkg },
        novel = novel().installedExtensionsFlow.value.map { ExtRef(it.pkgName, it.name) }.sortedBy { it.pkg },
    )

    // ---- Firebase primitives ----

    // The cloud copy plus the hash of its raw JSON, so a background push can tell whether the cloud
    // still matches what we last synced (safe to overwrite) or was changed elsewhere (don't clobber).
    private data class RemoteData(val payload: Payload, val rawHash: Int)

    private fun lastHash(): Int = PrefManager.getCustomVal(HASH_KEY, 0)

    private suspend fun fetchRemote(uid: String): Result<RemoteData?> = runCatching {
        suspendCancellableCoroutine { cont ->
            node(uid).get()
                .addOnSuccessListener { snap ->
                    val json = snap.child("payload").getValue(String::class.java)
                    val data = json?.let { raw ->
                        runCatching { gson.fromJson(raw, Payload::class.java) }.getOrNull()
                            ?.let { RemoteData(it, raw.hashCode()) }
                    }
                    cont.resume(data)
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    private suspend fun doUpload(uid: String, json: String): Boolean {
        val ts = System.currentTimeMillis()
        val ok = upload(uid, json, ts)
        if (ok) {
            PrefManager.setCustomVal(TS_KEY, ts)
            PrefManager.setCustomVal(HASH_KEY, json.hashCode())
            Logger.log("ExtensionSync: pushed installed set (ts=$ts)")
        }
        return ok
    }

    private suspend fun upload(uid: String, payloadJson: String, ts: Long): Boolean = runCatching {
        suspendCancellableCoroutine { cont ->
            node(uid)
                .setValue(mapOf("payload" to payloadJson, "ts" to ts))
                .addOnSuccessListener { cont.resume(true) }
                .addOnFailureListener { cont.resume(false) }
        }
    }.getOrDefault(false)

    // ---- publish ----

    suspend fun push(): Boolean {
        if (!enabled()) return false
        val uid = userId() ?: return false
        val json = runCatching { gson.toJson(localPayload()) }.getOrNull() ?: return false
        if (json.hashCode() == lastHash()) return true // unchanged
        return doUpload(uid, json)
    }

    /**
     * Unconditionally overwrite the cloud extension set with this device's, ignoring the enable
     * toggle and the "unchanged" hash guard. Backs the explicit "force upload" action.
     */
    suspend fun forcePush(): Boolean {
        val uid = userId() ?: return false
        val json = runCatching { gson.toJson(localPayload()) }.getOrNull() ?: return false
        return doUpload(uid, json)
    }

    /**
     * Publish on app background. Like [CloudSync], this never clobbers the other side: it uploads
     * only when the local set changed AND the cloud still matches what we last synced. If the cloud
     * was changed elsewhere (e.g. a force upload from another device), it's left untouched for the
     * manual reconcile to pick up. No-op when disabled or signed out.
     */
    suspend fun pushNow() {
        if (!enabled() || userId() == null) return
        runCatching {
            val uid = userId() ?: return
            val json = gson.toJson(localPayload())
            if (json.hashCode() == lastHash()) return // nothing changed locally
            // On fetch failure, skip rather than risk clobbering a cloud we couldn't read.
            val remote = fetchRemote(uid).getOrElse {
                Logger.log("ExtensionSync: push skipped, cloud unreadable: ${it.message}")
                return
            }
            if (remote != null && remote.rawHash != lastHash()) {
                Logger.log("ExtensionSync: cloud changed elsewhere; leaving for manual reconcile")
                return
            }
            doUpload(uid, json)
        }
    }

    // ---- reconcile ----

    /**
     * Fetches the cloud set and diffs it against what's installed locally. Returns null only on a
     * genuine failure (sync off, no account, cloud unreachable). An all-empty [Diff] means there's
     * nothing to reconcile — either the devices already match, or nothing has been published yet
     * (in which case this device publishes its own set so the other devices can pick it up).
     *
     * Note: this must NOT push before reading. The node is last-write-wins, so publishing first
     * would overwrite the other device's set and the diff would always come back empty.
     */
    suspend fun computeDiff(): Diff? {
        if (!enabled()) return null
        val uid = userId() ?: return null
        val result = fetchRemote(uid)
        if (result.isFailure) return null
        // Nothing in the cloud yet: publish ours so others can reconcile; nothing to offer here.
        val remote = result.getOrNull()?.payload
            ?: return if (push()) Diff(emptyList(), emptyList()) else null

        val toInstall = mutableListOf<ExtItem>()
        val toRemove = mutableListOf<ExtItem>()

        fun reconcile(
            type: ExtType,
            remoteRefs: List<ExtRef>,
            installed: Map<String, String>,       // pkgName -> name
            available: Map<String, AvailInfo>,    // pkgName -> name + icon url
        ) {
            remoteRefs.filter { it.pkg !in installed }.forEach { ref ->
                val info = available[ref.pkg]
                toInstall += ExtItem(
                    type, ref.pkg,
                    // Prefer the repo's name, else the name the other device published, else the pkg.
                    info?.name ?: ref.name.ifBlank { ref.pkg },
                    isInstall = true, available = info != null, iconUrl = info?.iconUrl,
                )
            }
            val remotePkgs = remoteRefs.map { it.pkg }
            installed.keys.filter { it !in remotePkgs }.forEach { pkg ->
                toRemove += ExtItem(type, pkg, installed[pkg] ?: pkg, isInstall = false)
            }
        }

        reconcile(
            ExtType.ANIME, remote.anime,
            anime().installedExtensionsFlow.value.associate { it.pkgName to it.name },
            anime().availableExtensionsFlow.value.associate { it.pkgName to AvailInfo(it.name, it.iconUrl) },
        )
        reconcile(
            ExtType.MANGA, remote.manga,
            manga().installedExtensionsFlow.value.associate { it.pkgName to it.name },
            manga().availableExtensionsFlow.value.associate { it.pkgName to AvailInfo(it.name, it.iconUrl) },
        )
        reconcile(
            ExtType.NOVEL, remote.novel,
            novel().installedExtensionsFlow.value.associate { it.pkgName to it.name },
            novel().availableExtensionsFlow.value.associate { it.pkgName to AvailInfo(it.name, it.iconUrl) },
        )

        return Diff(toInstall, toRemove)
    }

    /** Kicks off installation via the matching manager. Surfaces the system installer as usual. */
    fun install(item: ExtItem) {
        when (item.type) {
            ExtType.ANIME -> anime().availableExtensionsFlow.value.find { it.pkgName == item.pkgName }
                ?.let { ext ->
                    anime().installExtension(ext).observeOn(AndroidSchedulers.mainThread())
                        .subscribe({}, { Logger.log("ExtensionSync: install error: ${it.message}") })
                }

            ExtType.MANGA -> manga().availableExtensionsFlow.value.find { it.pkgName == item.pkgName }
                ?.let { ext ->
                    manga().installExtension(ext).observeOn(AndroidSchedulers.mainThread())
                        .subscribe({}, { Logger.log("ExtensionSync: install error: ${it.message}") })
                }

            ExtType.NOVEL -> novel().availableExtensionsFlow.value.find { it.pkgName == item.pkgName }
                ?.let { ext ->
                    novel().installExtension(ext).observeOn(AndroidSchedulers.mainThread())
                        .subscribe({}, { Logger.log("ExtensionSync: install error: ${it.message}") })
                }
        }
    }

    /** Uninstalls the extension via the matching manager. */
    fun uninstall(item: ExtItem) {
        when (item.type) {
            ExtType.ANIME -> anime().uninstallExtension(item.pkgName)
            ExtType.MANGA -> manga().uninstallExtension(item.pkgName)
            ExtType.NOVEL -> novel().uninstallExtension(item.pkgName)
        }
    }
}
