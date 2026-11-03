package ani.dantotsu.notifications.extension

import ani.dantotsu.settings.UpdateItem
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * Remembers the updates the system refused to install without confirmation, so a scheduled run
 * stops asking about them.
 *
 * Without this, an extension this build is not the installer of record for produces a notification
 * on every single cycle, forever — it can never succeed unattended, and nothing about waiting
 * changes that. Recording it means the user is told once, and told again only when a genuinely
 * newer version appears.
 *
 * One refusal isn't enough to say so, though: the system also refuses a silent update for reasons
 * that pass — it throttles silent updates of a package for a while after the last one — and the
 * update then goes through silently the next time, or from the Update button. So the first refusal
 * of a version is only noted ([PROVISIONAL]), and that version is tried again on the next run; only
 * a second refusal, on a later run, marks it as needing confirmation.
 *
 * Keyed by package *and* version, so a new release is always offered afresh. Kept device-local
 * ([ani.dantotsu.settings.saving.internal.Location.Irrelevant], which is not exported or synced):
 * who installed a package is a fact about this device, and carrying it to another would suppress
 * updates there that would have installed silently.
 */
object RefusedExtensionUpdates {

    /** Null when the available version isn't known, in which case it can be neither skipped nor recorded. */
    private fun keyOf(item: UpdateItem): String? =
        // "2:" since a second refusal became the bar: what the first rule marked after one refusal,
        // often wrongly, matches nothing now, and is dropped from the set at the next run.
        item.newVersionName?.let { "2:${item.pkgName}@$it" }

    private fun stored(): Set<String> = PrefManager.getVal(PrefName.RefusedExtensionUpdates)

    /** [items] minus the ones already known to need confirmation at this exact version. */
    fun withoutAlreadyRefused(items: List<UpdateItem>): List<UpdateItem> {
        val refused = stored()
        if (refused.isEmpty()) return items
        // A version refused only once is tried again: see the class comment.
        return items.filter { keyOf(it) !in refused }
    }

    /**
     * Whether [item] is the exact version a scheduled run already had refused unattended.
     *
     * Lets a list of pending updates mark the ones sitting there for that reason, rather than
     * leaving that only in the one-off notification a scheduled run sends when it first happens.
     */
    fun isRefused(item: UpdateItem): Boolean {
        val key = keyOf(item) ?: return false
        return key in stored()
    }

    /**
     * Records [refused] and drops anything that is no longer pending.
     *
     * [stillPending] is every update that existed this run, refused or not. Entries outside it have
     * been installed, uninstalled or superseded, so remembering them would only grow the set.
     */
    fun record(refused: List<UpdateItem>, stillPending: List<UpdateItem>): List<UpdateItem> {
        val pendingKeys = stillPending.mapNotNull(::keyOf).toSet()
        val before = stored()
        val updated = before.filterTo(mutableSetOf()) { it.removeSuffix(PROVISIONAL) in pendingKeys }
        val confirmed = mutableListOf<UpdateItem>()
        refused.forEach { item ->
            val key = keyOf(item) ?: return@forEach
            if (key + PROVISIONAL in before) {
                // Refused on an earlier run too: it does need confirmation.
                updated -= key + PROVISIONAL
                updated += key
                confirmed += item
            } else if (key !in updated) {
                updated += key + PROVISIONAL
            }
        }
        // A provisional one not refused this run went through, or wasn't tried: either way it's gone
        // from the pending list or will be tried afresh — nothing to keep beyond the pending filter.
        PrefManager.setVal(PrefName.RefusedExtensionUpdates, updated as Set<String>)
        return confirmed
    }

    /** Marks a version refused once, on a single run: not yet taken to need confirmation. */
    private const val PROVISIONAL = "?"

    /** Forgets everything, so the next run offers every pending update again. */
    fun clear() {
        PrefManager.setVal(PrefName.RefusedExtensionUpdates, emptySet<String>())
    }
}
