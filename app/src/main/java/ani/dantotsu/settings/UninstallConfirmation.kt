package ani.dantotsu.settings

/**
 * Tracks extension uninstalls so they are only reported once the package is really gone.
 *
 * Uninstalling hands off to the system uninstall dialog, which the user can cancel, so the tap
 * itself proves nothing. Confirmation instead comes from the package actually disappearing from
 * the installed extensions flow.
 *
 * The removal broadcast arrives while the system dialog is still in front, so [flush] is kept
 * separate from [onInstalledPackagesChanged]: callers flush once their screen is visible again.
 */
class UninstallConfirmation(private val onConfirmed: () -> Unit) {

    private val pending = mutableSetOf<String>()
    private var confirmed = false

    /** Call when the user asks for [pkgName] to be uninstalled. */
    fun onUninstallRequested(pkgName: String) {
        pending += pkgName
    }

    /** Call with the currently installed package names whenever they change. */
    fun onInstalledPackagesChanged(installed: Collection<String>) {
        if (pending.isEmpty()) return
        val installedSet = installed.toSet()
        val removed = pending.filterNot { it in installedSet }
        if (removed.isEmpty()) return
        pending -= removed.toSet()
        confirmed = true
    }

    /** Reports any confirmed uninstall. Safe to call repeatedly; only fires once per uninstall. */
    fun flush() {
        if (!confirmed) return
        confirmed = false
        onConfirmed()
    }
}
