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
 * Keyed by package *and* version, so a new release is always offered afresh. Kept device-local
 * ([ani.dantotsu.settings.saving.internal.Location.Irrelevant], which is not exported or synced):
 * who installed a package is a fact about this device, and carrying it to another would suppress
 * updates there that would have installed silently.
 */
object RefusedExtensionUpdates {

    /** Null when the available version isn't known, in which case it can be neither skipped nor recorded. */
    private fun keyOf(item: UpdateItem): String? =
        item.newVersionName?.let { "${item.pkgName}@$it" }

    private fun stored(): Set<String> = PrefManager.getVal(PrefName.RefusedExtensionUpdates)

    /** [items] minus the ones already known to need confirmation at this exact version. */
    fun withoutAlreadyRefused(items: List<UpdateItem>): List<UpdateItem> {
        val refused = stored()
        if (refused.isEmpty()) return items
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
    fun record(refused: List<UpdateItem>, stillPending: List<UpdateItem>) {
        val pendingKeys = stillPending.mapNotNull(::keyOf).toSet()
        val updated = stored().filterTo(mutableSetOf()) { it in pendingKeys }
        refused.mapNotNullTo(updated, ::keyOf)
        PrefManager.setVal(PrefName.RefusedExtensionUpdates, updated as Set<String>)
    }

    /** Forgets everything, so the next run offers every pending update again. */
    fun clear() {
        PrefManager.setVal(PrefName.RefusedExtensionUpdates, emptySet<String>())
    }
}
