package ani.dantotsu.connections.sync

import ani.dantotsu.BuildConfig
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

/**
 * Who this device is to the cloud, and the only way the sync modules reach it.
 *
 * A device is *linked* when it holds the sync secret. That secret is created on one device and
 * carried to the others by the user — a code they type, or the same code scanned from a QR — so it
 * is never uploaded, never derived from anything public, and never leaves
 * [ani.dantotsu.settings.saving.internal.Location.Protected], which cloud sync itself excludes.
 *
 * Everything the modules used to compute for themselves now comes from here: the node to talk to
 * ([node]) and how to wrap what they put in it ([seal]/[open]). Nothing else in the package knows
 * the database layout.
 *
 * A device that isn't linked has no node — [node] returns null and every module treats that as
 * "sync is off". That is the intended failure mode: without the secret there is nowhere correct to
 * write, and writing somewhere guessable is the thing this replaces.
 */
object SyncIdentity {

    private const val ROOT = "users"

    /** Tags which kind of secret [PrefName.CloudSyncKey] holds; see [store]. */
    private const val CODE_PREFIX = "c:"

    /** Which node the local baselines were built against; see [reconcileIdentity]. */
    private const val IDENTITY_KEY = "cloud_sync_identity"

    private const val INFO_PATH = "path"
    private const val INFO_DATA = "data"

    /** Derived keys are pure functions of the stored secret, so they're cached against it. */
    private class Keys(val secretTag: String, val path: ByteArray, val data: ByteArray)

    @Volatile private var cached: Keys? = null

    // ---- stored secret ----

    private fun stored(): String =
        PrefManager.getVal<String>(PrefName.CloudSyncKey).orEmpty()

    /** True when this device holds the secret and can therefore sync. */
    fun isLinked(): Boolean = stored().isNotBlank()

    /** The code to show for linking another device, or null when there is no secret stored. */
    fun displayCode(): String? =
        stored().takeIf { it.startsWith(CODE_PREFIX) }
            ?.removePrefix(CODE_PREFIX)
            ?.let { SyncCrypto.formatCode(it) }

    private fun store(value: String) {
        if (stored() == value) return
        PrefManager.setVal(PrefName.CloudSyncKey, value)
        cached = null
        // Holding a secret *is* the opt-in, so acquiring one turns sync on and giving it up turns
        // sync off. Keeping the two in step is what lets the toggle mean what it says: it can no
        // longer read "on" while the device has no way to sync.
        PrefManager.setVal(PrefName.CloudSyncEnabled, value.isNotBlank())
        // The secret decides which cloud this device talks to, so every baseline describing the
        // old one is now wrong — and wrong in the confident direction, where a push believes
        // nothing changed and the incremental progress pull skips what it never actually read.
        CloudWipe.resetLocalBaselines()
        rememberIdentity()
        SyncStatus.refresh() // linking and unlinking are what flip sync on and off
    }

    // ---- identity changes ----

    /**
     * Fingerprint of the node this device currently addresses. Not a secret — it is the node name,
     * which is already public to anyone who can see the traffic.
     */
    private fun identityFingerprint(): String {
        val keys = keys() ?: return ""
        val scope = pathScope() ?: return ""
        return runCatching { SyncCrypto.pathId(keys.path, scope) }.getOrDefault("")
    }

    private fun rememberIdentity() {
        identityFingerprint().takeIf { it.isNotBlank() }
            ?.let { PrefManager.setCustomVal(IDENTITY_KEY, it) }
    }

    /**
     * Drops this device's baselines when it starts addressing a different node.
     *
     * Every baseline — the last-synced hash, the merge base, how far the incremental progress pull
     * has read — is a statement about one specific node. Signing into a different AniList account,
     * or moving between a release and a beta install, silently changes which node that is, leaving
     * behind claims that are not merely stale but confidently wrong: a push concludes nothing has
     * changed, and the progress pull skips everything written before the switch.
     *
     * Called from the sync entry points rather than from [node], which is an accessor and is used
     * inside loops — including the migration's, whose own completion flag these baselines contain.
     */
    fun reconcileIdentity() {
        val current = identityFingerprint()
        if (current.isBlank()) return
        val previous = PrefManager.getCustomVal(IDENTITY_KEY, "")
        if (previous == current) return
        PrefManager.setCustomVal(IDENTITY_KEY, current)
        // A blank previous is a device that simply hasn't recorded one yet, not a change.
        if (previous.isNotBlank()) {
            Logger.log("SyncIdentity: now addressing a different node; resetting local baselines")
            CloudWipe.resetLocalBaselines()
            PrefManager.setCustomVal(IDENTITY_KEY, current) // survives the reset above
        }
    }

    /** Creates a new random secret, replacing any existing one. @return the code to display. */
    fun generateCode(): String {
        val code = SyncCrypto.newCode()
        store(CODE_PREFIX + code)
        Logger.log("SyncIdentity: generated a new sync code")
        return SyncCrypto.formatCode(code)
    }

    /**
     * Links this device with a code from another one.
     * @return false when the code is malformed or mistyped (see [SyncCrypto.normalizeCode]).
     */
    fun linkWithCode(input: String): Boolean {
        val code = SyncCrypto.normalizeCode(input) ?: return false
        store(CODE_PREFIX + code)
        Logger.log("SyncIdentity: linked with a sync code")
        return true
    }

    /** Forgets the secret. The cloud copy is left alone; see [CloudWipe] to remove that too. */
    fun unlink() {
        store("")
        Logger.log("SyncIdentity: unlinked")
    }

    // ---- derived keys ----

    private fun keys(): Keys? {
        val tag = stored()
        if (tag.isBlank()) return null
        cached?.let { if (it.secretTag == tag) return it }
        return runCatching {
            // The prefix is kept even with only one kind of secret left, so a future format has
            // somewhere to announce itself rather than being mistaken for a code.
            if (!tag.startsWith(CODE_PREFIX)) return null
            val secret = SyncCrypto.secretFromCode(tag.removePrefix(CODE_PREFIX))
            Keys(
                secretTag = tag,
                path = SyncCrypto.derive(secret, INFO_PATH),
                data = SyncCrypto.derive(secret, INFO_DATA),
            ).also { cached = it }
        }.getOrElse {
            Logger.log("SyncIdentity: could not derive keys: ${it.message}")
            null
        }
    }

    /** The AniList account this device is signed in to, or null. Not a secret. */
    private fun accountId(): String? =
        PrefManager.getVal<String>(PrefName.AnilistUserId).takeIf { it.isNotBlank() }

    /**
     * What the node name is scoped to: the account, so one secret used on two AniList accounts
     * keeps them apart, and the application id, so a release install and a beta install never
     * share storage.
     *
     * Separating builds by *path* rather than by a field in the envelope is what makes the
     * separation absolute — there is no shared node for the two to race over, no last-write-wins
     * between a stable build and an experimental one, and nothing to filter out on read. It also
     * needs no rules change, since the result is still an opaque HMAC.
     *
     * [BuildConfig.APPLICATION_ID] is the right axis because it is exactly what distinguishes two
     * *installs*: `alpha` and `debug` share it (they are the same app, and cannot be installed side
     * by side), so switching between them keeps a device's sync continuous, while `release` carries
     * no suffix and is therefore always separate.
     *
     * Scoped here rather than mixed into the secret itself, because the build is a storage
     * namespace and not an identity: the same code stays the same code across both.
     */
    private fun pathScope(): String? = accountId()?.let { "$it|${BuildConfig.APPLICATION_ID}" }

    // ---- node access ----

    /**
     * The database node for [child] (`settings`, `progress`, …), or null when this device can't
     * address one: not linked, or not signed in.
     */
    fun node(child: String): DatabaseReference? = runCatching {
        val keys = keys() ?: return null
        val scope = pathScope() ?: return null
        FirebaseDatabase.getInstance().reference
            .child(ROOT)
            .child(SyncCrypto.pathId(keys.path, scope))
            .child(child)
    }.getOrElse {
        Logger.log("SyncIdentity: node unavailable: ${it.message}")
        null
    }

    /**
     * A child of the pre-encryption node this account used to write to:
     * `users/{anilistUserId}/{child}`.
     *
     * Only [SyncMigration] and [CloudWipe] should touch this. It is world-readable by anyone who
     * can count, which is the whole reason for everything above.
     *
     * Addressed per child, never as the parent, because the database rules grant read and write one
     * level down — `users/{id}` itself is not readable, listable or removable, which is what stops
     * the tree being walked. Anything operating on "the whole account" has to iterate.
     */
    internal fun legacyNode(child: String): DatabaseReference? = runCatching {
        // The bare account id, never the build-scoped one: this addresses what the old layout
        // actually wrote, which both channels shared.
        val id = accountId() ?: return null
        FirebaseDatabase.getInstance().reference.child(ROOT).child(id).child(child)
    }.getOrNull()

    /** The children both layouts use, in the order they're worth rescuing. */
    internal val CHILDREN =
        listOf("settings", "progress", "extensions", "extension_settings", "unread")

    // ---- payload sealing ----

    /** Encrypts a payload for storage. @return null when this device isn't linked. */
    fun seal(plaintext: String): String? = runCatching {
        val keys = keys() ?: return null
        SyncCrypto.seal(keys.data, plaintext)
    }.getOrElse {
        Logger.log("SyncIdentity: seal failed: ${it.message}")
        null
    }

    /**
     * Decrypts a stored payload. @return null when this device isn't linked, or when the payload
     * wasn't written by a device holding the same secret.
     */
    fun open(sealed: String): String? {
        val keys = keys() ?: return null
        return SyncCrypto.open(keys.data, sealed)
    }
}
