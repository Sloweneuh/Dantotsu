package ani.dantotsu.connections.sync

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger

/**
 * Deletes everything this account has in the cloud.
 *
 * Turning cloud sync off only stops this device talking to the database; it never removed what was
 * already there, so settings, reading progress, the installed-extension list and any source logins
 * carried by extension settings simply stayed. There was no way for a user to take them back.
 *
 * Wipes both layouts: the sealed nodes this device writes to, and the pre-encryption plaintext node
 * at `users/{anilistUserId}` if the migration never ran. Someone asking for their data to be gone
 * means all of it, and the plaintext copy is the one that mattered most.
 */
object CloudWipe {

    /** Local bookkeeping to clear, so the next sync starts from a clean baseline rather than a lie. */
    private val BOOKKEEPING = setOf(
        "cloud_settings_ts", "cloud_settings_hash", "cloud_settings_filter_v",
        "cloud_settings_bootstrap_prompt", "cloud_settings_base",
        "progress_sync_state", "progress_pull_floor",
        "ext_sync_hash",
        "ext_settings_ts", "ext_settings_hash",
        "cloud_sync_migrated_v2",
    )

    /**
     * Forgets what this device believes about the cloud, without touching either side's data.
     *
     * Every baseline here — the last-synced hash, the merge base, how far the incremental progress
     * pull has read — is a statement about one particular cloud. Change which cloud this device
     * talks to and they become confident lies: a push would think nothing had changed, and the
     * progress pull would skip everything written before the switch. Called whenever the sync
     * secret changes, and as part of a wipe.
     */
    fun resetLocalBaselines() {
        PrefManager.applyCustomVals(removes = BOOKKEEPING)
        // Raised notices are claims about a cloud copy too — "these two disagree", "the other
        // device has different extensions". Deleting or replacing that copy makes them false, and a
        // banner still offering to resolve something that no longer exists is the same defect as a
        // stale baseline, just one the user can see.
        SyncConflictNotice.clear()
        ExtensionSyncNotice.clear()
        Logger.log("CloudWipe: local sync baselines reset")
    }

    /**
     * Removes the account's cloud data and resets this device's sync bookkeeping.
     *
     * Local settings, progress and extensions are untouched — this deletes the *copy*, not the
     * user's own data. The device stays linked, so a later sync republishes from local state; to
     * stop that too, unlink or turn sync off.
     *
     * Deletes child by child rather than dropping the account subtree in one call: the database
     * rules grant write access at `users/{id}/{child}` and not at `users/{id}`, so removing the
     * parent is denied. An emptied parent disappears on its own once its last child is gone.
     *
     * @return false if any part couldn't be removed, in which case some data may remain.
     */
    suspend fun run(): Boolean {
        var ok = true

        // Without the secret the sealed nodes can't even be named, let alone deleted — their path
        // is derived from it. Saying so matters: the loop below would skip them in silence and
        // report a clean sweep, while the encrypted copy stayed exactly where it was.
        if (!SyncIdentity.isLinked()) {
            Logger.log("CloudWipe: not linked; the encrypted copy can't be addressed from here")
            ok = false
        }

        for (child in SyncIdentity.CHILDREN) {
            SyncIdentity.node(child)?.let { node ->
                if (!node.removeValue().awaitOk()) {
                    Logger.log("CloudWipe: could not delete $child")
                    ok = false
                }
            }
            SyncIdentity.legacyNode(child)?.let { legacy ->
                if (!legacy.removeValue().awaitOk()) {
                    Logger.log("CloudWipe: could not delete legacy $child")
                    ok = false
                }
            }
        }

        // Clear bookkeeping regardless: leaving a baseline that points at data which no longer
        // exists would make the next sync believe it was already up to date.
        resetLocalBaselines()
        Logger.log("CloudWipe: cloud data removed (complete=$ok)")
        return ok
    }
}
