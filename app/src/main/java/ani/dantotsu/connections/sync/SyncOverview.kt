package ani.dantotsu.connections.sync

import ani.dantotsu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where each sync module actually stands, on this device and in the cloud.
 *
 * The settings screen used to answer that with one line — "last synced N ago" — reading a single
 * timestamp that only [CloudSync] writes, and only when settings genuinely changed hands. So a
 * device that had been backgrounding all day, pushing reading progress each time, still showed the
 * hour its last *settings* edit went up, and there was no way to tell that from sync being broken.
 *
 * Modules keep different bookkeeping, and this reports what each one has rather than inventing a
 * common shape: [ExtensionSync] deliberately holds no local clock (it compares payload hashes, so
 * there is nothing for a timestamp to mean), and [UnreadSync] is a shared cache with no per-device
 * baseline at all. Saying so is more use than showing a plausible zero.
 */
object SyncOverview {

    /** One module's line. A null timestamp means "no record", which is not the same as "never". */
    data class Module(
        val nameRes: Int,
        val localTs: Long?,
        /** Shown instead of a local timestamp when the module keeps none. */
        val localNoteRes: Int? = null,
        val localDetail: String? = null,
        val cloudTs: Long?,
        /** Which device last wrote the cloud copy, where the node records it. */
        val cloudDevice: String? = null,
        val cloudDetail: String? = null,
        /**
         * This module is waiting on the user and has stopped syncing until it gets an answer.
         *
         * Worth carrying per module rather than leaving to the screens that already raise it: the
         * two sides of a stalled module look normal on their own — a local time, a cloud time — and
         * the panel that exists to explain a quiet sync was showing the stall as an ordinary gap.
         */
        val conflict: Boolean = false,
    )

    /**
     * Reads every module's local bookkeeping and its cloud node. One snapshot per node, so this is
     * five round trips — fine for a panel the user opened on purpose, not for anything automatic.
     */
    suspend fun collect(): List<Module> = withContext(Dispatchers.IO) {
        val settingsCloud = runCatching { CloudSync.cloudInfo() }.getOrNull()
        val progressLocal = ProgressSync.localSummary()
        val progressCloud = cloudProgress()

        listOf(
            Module(
                nameRes = R.string.sync_module_settings,
                localTs = CloudSync.lastSyncedAt().takeIf { it > 0 },
                cloudTs = settingsCloud?.ts,
                cloudDevice = settingsCloud?.device,
                conflict = SyncConflictNotice.isPending(),
            ),
            Module(
                nameRes = R.string.sync_module_progress,
                localTs = progressLocal.second.takeIf { it > 0 },
                localDetail = progressLocal.first.takeIf { it > 0 }?.let { "$it" },
                cloudTs = progressCloud.second,
                cloudDetail = progressCloud.first.takeIf { it > 0 }?.let { "$it" },
            ),
            Module(
                nameRes = R.string.sync_module_extensions,
                localTs = null,
                // Not a gap in the bookkeeping — this one diverges by content, never by date.
                localNoteRes = R.string.sync_compared_by_content,
                cloudTs = cloudTs("extensions"),
                // The same kind of stall as the settings one, reached differently: nothing can
                // install or remove an extension on the user's behalf, so an unreconciled
                // difference is a module that has stopped until they decide.
                conflict = ExtensionSyncNotice.isPending(),
            ),
            Module(
                nameRes = R.string.sync_module_extension_settings,
                localTs = ExtensionSettingsSync.localTs().takeIf { it > 0 },
                cloudTs = cloudTs("extension_settings"),
            ),
            Module(
                nameRes = R.string.sync_module_unread,
                localTs = null,
                // A shared result cache rather than this device's state; there is no local copy.
                localNoteRes = R.string.sync_shared_cache,
                cloudTs = cloudTs("unread"),
            ),
        )
    }

    /** The `ts` every single-payload node carries, or null when nothing is stored (or unreachable). */
    private suspend fun cloudTs(child: String): Long? = runCatching {
        SyncIdentity.node(child)?.child("ts")?.getSnapshot()?.getValue(Long::class.java)
    }.getOrNull()

    /** Progress is a node of nodes, so it reports how many media are up there and the newest write. */
    private suspend fun cloudProgress(): Pair<Int, Long?> = runCatching {
        val snap = SyncIdentity.node("progress")?.getSnapshot() ?: return 0 to null
        var count = 0
        var newest = 0L
        snap.children.forEach { child ->
            count++
            child.child("ts").getValue(Long::class.java)?.let { if (it > newest) newest = it }
        }
        count to newest.takeIf { it > 0 }
    }.getOrElse { 0 to null }
}
