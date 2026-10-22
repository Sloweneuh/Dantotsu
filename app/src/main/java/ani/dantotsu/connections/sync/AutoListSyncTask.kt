package ani.dantotsu.connections.sync

import android.content.Context
import ani.dantotsu.App
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.isOnline
import ani.dantotsu.notifications.Task
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the "Compare lists" comparison on a schedule and pushes what it finds, so the trackers stay
 * in step without the user opening [ani.dantotsu.settings.ListSyncCompareActivity] and pressing
 * "Sync all" on each section.
 *
 * Entirely opt-in: it does nothing until [PrefName.AutoListSyncInterval] is set to something other
 * than off, and it writes to the user's tracker lists, so the guards below are deliberately
 * conservative rather than clever:
 *  - **Removals are excluded** unless [PrefName.AutoListSyncRemovals] is on as well. A diff that
 *    pushes a status or progress is one the user can see and correct on the next run; a deletion
 *    isn't, and a source list that comes back short for any reason would look exactly like "these
 *    entries were removed".
 *  - **The per-tracker sync switches are honoured.** [PrefName.MalListSyncEnabled],
 *    [PrefName.MangaBakaListSyncEnabled] and the rest mean "don't write to this tracker" everywhere else, and a
 *    background pass that ignored them would be the one place the app wrote to a tracker the user
 *    had switched off. The manual screen is exempt because pressing sync *is* the permission.
 *  - **A failed section is skipped, not guessed at.** [ListCompare.compareStreaming] reports each
 *    section separately, so a tracker that couldn't be fetched contributes nothing rather than
 *    contributing an empty list — which would otherwise read as "everything here is a removal".
 *  - **Mass removals are held back** even with removals on (see [massRemovalGuard]).
 */
class AutoListSyncTask : Task {

    companion object {
        @Volatile
        private var currentlyPerforming = false

        /** See [massRemovalGuard]: more than this many removals, making up this share of the list. */
        private const val MASS_REMOVAL_MIN = 10
        private const val MASS_REMOVAL_PERCENT = 20
    }

    override suspend fun execute(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (currentlyPerforming) {
            Logger.log("AutoListSyncTask: already running")
            return@withContext false
        }
        try {
            currentlyPerforming = true
            PrefManager.init(context)
            App.context = context

            if (PrefManager.getVal<Long>(PrefName.AutoListSyncInterval) <= 0L) {
                Logger.log("AutoListSyncTask: disabled; nothing to do")
                return@withContext true
            }
            if (!isOnline(context)) {
                Logger.log("AutoListSyncTask: offline; deferring")
                return@withContext false
            }

            Anilist.restoreSession()
            if (Anilist.userid == null) {
                // AniList is the source every comparison reads from; without it there is nothing to
                // compare against, and no amount of retrying will change that.
                Logger.log("AutoListSyncTask: no AniList session; skipping")
                record(0, 0)
                return@withContext true
            }
            // This ran in a worker process that had never been near the home screen, so it always
            // had to restore the sessions itself — a third copy of the same code, and one that
            // could race the app's own restore into refreshing the same expired token twice.
            // [TrackerSessions] is now the one place that does it.
            ani.dantotsu.connections.TrackerSessions.await()

            val sections = ListCompare.availableSections().filter { allowed(it) }
            if (sections.isEmpty()) {
                Logger.log("AutoListSyncTask: no tracker is both logged in and enabled for sync")
                record(0, 0)
                return@withContext true
            }

            // Sections run in parallel and report from whichever thread they finished on — the
            // screen marshals its callbacks to the UI thread, and this has to do the equivalent.
            val lock = Any()
            val entries = mutableListOf<ListCompare.DiffEntry>()
            var failedSections = 0
            ListCompare.compareStreaming(
                sections = sections,
                onStats = { _, _ -> },
                onSection = { section, result ->
                    synchronized(lock) { entries += massRemovalGuard(section, result) }
                },
                onError = { section, e ->
                    synchronized(lock) { failedSections++ }
                    Logger.log("AutoListSyncTask: $section comparison failed: ${e.message}")
                },
            )

            val withRemovals = PrefManager.getVal<Boolean>(PrefName.AutoListSyncRemovals)
            val toSync = if (withRemovals) entries else entries.filterNot { it.delete }
            Logger.log(
                "AutoListSyncTask: ${toSync.size} to sync " +
                        "(${entries.size - toSync.size} removals held back, $failedSections sections failed)"
            )

            val results = if (toSync.isEmpty()) emptyList() else ListCompare.syncAll(toSync)
            val synced = results.count { it.second }
            val failed = results.size - synced
            record(synced, failed)
            Logger.log("AutoListSyncTask: synced $synced, failed $failed")

            // Retry only when the run looks like it hit the network rather than the lists: nothing
            // pushed and something failed to even compare. A partial success is left for the next
            // scheduled run, since re-running the comparison is far from free.
            failedSections == 0 || synced > 0
        } catch (e: Exception) {
            Logger.log("AutoListSyncTask: error: ${e.message}")
            Logger.log(e)
            false
        } finally {
            currentlyPerforming = false
        }
    }

    /**
     * Drops a section's removals when they'd take out a large share of the destination list in one
     * go. Removals only make sense in ones and twos — what someone deleted on AniList since the last
     * run — so a pass that wants to clear most of a tracker is far likelier to be reading a source
     * list that came back short (an outage, a half-fetched custom list) than a real mass deletion,
     * and getting that wrong is unrecoverable. The manual compare screen still offers them.
     */
    private fun massRemovalGuard(
        section: ListCompare.Section,
        result: ListCompare.SubsectionResult,
    ): List<ListCompare.DiffEntry> {
        val removals = result.diffs.count { it.delete }
        val destTotal = result.dest.total
        if (removals < MASS_REMOVAL_MIN || removals * 100 < destTotal * MASS_REMOVAL_PERCENT) return result.diffs
        Logger.log(
            "AutoListSyncTask: $section wants to remove $removals of $destTotal entries; " +
                    "holding its removals back"
        )
        return result.diffs.filterNot { it.delete }
    }

    /** Whether the tracker a section targets is one the user has left sync switched on for. */
    private fun allowed(section: ListCompare.Section): Boolean = when (section) {
        ListCompare.Section.MAL_ANIME, ListCompare.Section.MAL_MANGA ->
            PrefManager.getVal(PrefName.MalListSyncEnabled)

        ListCompare.Section.KITSU_ANIME, ListCompare.Section.KITSU_MANGA ->
            PrefManager.getVal(PrefName.KitsuListSyncEnabled)

        ListCompare.Section.SIMKL_ANIME ->
            PrefManager.getVal(PrefName.SimklListSyncEnabled)

        ListCompare.Section.MANGABAKA ->
            PrefManager.getVal(PrefName.MangaBakaListSyncEnabled)

        ListCompare.Section.COMICK_ANIME, ListCompare.Section.COMICK_MANGA ->
            PrefManager.getVal(PrefName.ComickListSyncEnabled)
    }

    /**
     * Stamps the run for the settings row to report.
     *
     * Called for a pass that found nothing to do as well as one that pushed something: the row
     * reads "hasn't run yet" from the absence of a stamp, so a task that ran and correctly decided
     * there was nothing to compare was indistinguishable from one that never ran at all — which is
     * the opposite of what someone checking that screen is trying to find out.
     */
    private fun record(synced: Int, failed: Int) {
        PrefManager.setVal(PrefName.AutoListSyncLastRun, System.currentTimeMillis())
        PrefManager.setVal(PrefName.AutoListSyncLastSynced, synced)
        PrefManager.setVal(PrefName.AutoListSyncLastFailed, failed)
    }
}
