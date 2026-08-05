package ani.dantotsu.connections.sync

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseReference

/**
 * Moves an account off the old plaintext layout.
 *
 * Before sync codes, everything lived unencrypted at `users/{anilistUserId}` — a path anyone could
 * reach by counting, holding settings, reading progress and (when the toggle was on) whatever
 * credentials the installed sources keep in their own preferences. Linking a device is what creates
 * somewhere safe to put that, so the move happens on the first link: copy each node across to the
 * sealed location, then delete the original.
 *
 * The delete is the point. Migrating without it would leave the exposed copy exactly as exposed as
 * it was, so a child that can't be deleted fails the migration and is retried, while a child that
 * can't be *copied* only costs the user a re-sync from local state.
 *
 * Everything here works one child at a time. The database rules grant access at
 * `users/{id}/{child}` and never at `users/{id}`, so there is no reading the account in one
 * snapshot and no deleting it in one call — an attempt at either is simply denied.
 *
 * Runs once per account and is safe to re-run: a legacy child that is already gone is a no-op.
 */
object SyncMigration {

    private const val DONE_KEY = "cloud_sync_migrated_v2"

    private fun alreadyDone(): Boolean = PrefManager.getCustomVal(DONE_KEY, false)

    /**
     * Copies any legacy data into this device's sealed nodes and removes the plaintext original.
     *
     * @return true when there is nothing left behind — including the common case of nothing to do.
     *   Only call this once [SyncIdentity.isLinked] is true; without a secret there is nowhere to
     *   copy to, and the plaintext copy must not be deleted before it has somewhere else to live.
     */
    suspend fun run(): Boolean {
        if (alreadyDone()) return true
        if (!SyncIdentity.isLinked()) return false

        var moved = 0
        var complete = true
        for (child in SyncIdentity.CHILDREN) {
            when (migrateChild(child)) {
                Outcome.Moved -> moved++
                Outcome.Nothing -> {}
                Outcome.Failed -> complete = false
            }
        }

        if (!complete) {
            Logger.log("SyncMigration: moved $moved child(ren); some legacy data remains")
            return false
        }
        PrefManager.setCustomVal(DONE_KEY, true)
        Logger.log("SyncMigration: moved $moved child(ren) and deleted the plaintext copies")
        return true
    }

    private enum class Outcome { Moved, Nothing, Failed }

    private suspend fun migrateChild(child: String): Outcome {
        val from = SyncIdentity.legacyNode(child) ?: return Outcome.Failed
        val to = SyncIdentity.node(child) ?: return Outcome.Failed

        val snapshot = runCatching { from.getSnapshot() }.getOrElse {
            Logger.log("SyncMigration: $child unreadable: ${it.message}")
            return Outcome.Failed
        }
        if (!snapshot.exists()) return Outcome.Nothing

        // Don't let a late upgrade undo a recent sync. While the rollout is in progress an
        // already-migrated account can still get a fresh plaintext node written to it by a device
        // on the old build; migrating that afterwards would push stale state over whatever the
        // updated devices have synced since. Timestamps carry across unchanged, so they compare
        // directly. Per-media progress has no single timestamp, so it's merged rather than judged.
        if (child != "progress" && isDestinationNewer(to, snapshot)) {
            Logger.log("SyncMigration: $child is already newer in the cloud; removing the old copy")
            return if (from.removeValue().awaitOk()) Outcome.Moved else Outcome.Failed
        }

        val sealed = seal(child, snapshot) ?: return Outcome.Failed
        if (sealed.isEmpty()) return Outcome.Nothing

        val written = when (child) {
            // Merge rather than replace: another device may already have sealed media of its own
            // under here, and this is a copy of a subset, not a complete picture.
            "progress" -> to.updateChildren(sealed).awaitOk()
            else -> to.setValue(sealed).awaitOk()
        }
        if (!written) {
            Logger.log("SyncMigration: could not write $child; leaving the legacy copy in place")
            return Outcome.Failed
        }
        if (!from.removeValue().awaitOk()) {
            // The copy landed but the plaintext is still there. Don't count it as done — the whole
            // point is that it stops existing, and a retry just overwrites the copy it already made.
            Logger.log("SyncMigration: copied $child but could not delete the legacy node")
            return Outcome.Failed
        }
        return Outcome.Moved
    }

    /** @return the sealed form to write, or null if anything couldn't be sealed. */
    private fun seal(child: String, from: DataSnapshot): Map<String, Any>? = when (child) {
        // Per-media state is a node of nodes; each media's payload is sealed on its own.
        "progress" -> from.children.mapNotNull { media ->
            val id = media.key ?: return@mapNotNull null
            val payload = media.child("payload").getValue(String::class.java)
                ?: return@mapNotNull null
            val ts = media.child("ts").getValue(Long::class.java) ?: return@mapNotNull null
            val sealed = SyncIdentity.seal(payload) ?: return null
            id to mapOf("payload" to sealed, "ts" to ts)
        }.toMap()

        else -> {
            val payload = from.child("payload").getValue(String::class.java)
            val ts = from.child("ts").getValue(Long::class.java)
            if (payload == null || ts == null) emptyMap()
            else buildMap<String, Any> {
                put("payload", SyncIdentity.seal(payload) ?: return null)
                put("ts", ts)
                from.child("device").getValue(String::class.java)
                    ?.let { device -> SyncIdentity.seal(device)?.let { put("device", it) } }
            }
        }
    }

    /**
     * True when the sealed node already holds something at least as recent as the legacy one, so
     * copying would move the account backwards. Unreadable either way counts as "not newer": the
     * migration is the safer default when the comparison can't be made.
     */
    private suspend fun isDestinationNewer(to: DatabaseReference, from: DataSnapshot): Boolean =
        runCatching {
            val legacyTs = from.child("ts").getValue(Long::class.java) ?: return false
            val currentTs = to.getSnapshot().child("ts").getValue(Long::class.java) ?: return false
            currentTs >= legacyTs
        }.getOrDefault(false)

    /**
     * Whether an account still has plaintext data sitting in the old location — used to tell a user
     * who hasn't linked yet that there is something waiting to be moved.
     *
     * Looks at `settings` alone rather than all five children. Settings are written whenever sync
     * is on and anything changes, so an account that ever synced has them; checking the rest would
     * cost four more reads to catch a combination that barely occurs. This only decides whether to
     * *offer* the migration — [run] still moves every child — so the worst a miss can do is leave
     * the offer unmade, never leave data behind.
     */
    suspend fun legacyDataExists(): Boolean {
        if (alreadyDone()) return false
        val node = SyncIdentity.legacyNode("settings") ?: return false
        return runCatching { node.getSnapshot().exists() }.getOrDefault(false)
    }
}
