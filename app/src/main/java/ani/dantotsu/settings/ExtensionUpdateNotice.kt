package ani.dantotsu.settings

import android.app.Activity
import android.content.Intent
import ani.dantotsu.R
import ani.dantotsu.notifications.extension.RefusedExtensionUpdates
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.TopBanner
import eu.kanade.domain.source.service.SourcePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Points out that installed extensions have updates waiting.
 *
 * This was a snackbar shown once from `MainActivity.onCreate` — with `LENGTH_SHORT`, and with an
 * action on it. A button the user gets a second and a half to find, on one screen, in the same
 * shape as every other message, is close to not offering one at all; the update tab was effectively
 * reachable only by knowing it was there.
 *
 * Shown once per process rather than following the user around: unlike a sync conflict, nothing is
 * broken while this goes unread, and the count is still sitting in the extensions screen whenever
 * they get to it.
 *
 * With auto-update on, a plain pending count says nothing worth interrupting for — the scheduled
 * worker is already going to try installing it, silently, on its own. The one thing auto-update
 * can never resolve by itself is a package the system refuses to replace unattended (see
 * [RefusedExtensionUpdates]), so that is the only count this banner still raises while it's on.
 */
object ExtensionUpdateNotice {

    const val ID = "extension_updates"

    @Volatile private var handled = false

    private fun updateCount(): Int = runCatching {
        val preferences: SourcePreferences = Injekt.get()
        preferences.animeExtensionUpdatesCount().get() +
                preferences.mangaExtensionUpdatesCount().get() +
                preferences.novelExtensionUpdatesCount().get()
    }.getOrDefault(0)

    private fun needsConfirmationCount(): Int = runCatching {
        ExtensionUpdateRunner.pendingUpdates().count(RefusedExtensionUpdates::isRefused)
    }.getOrDefault(0)

    private fun autoUpdateOn(): Boolean = PrefManager.getVal(PrefName.AutoUpdateExtensions)

    fun isPending(): Boolean {
        if (handled) return false
        return if (autoUpdateOn()) needsConfirmationCount() > 0 else updateCount() > 0
    }

    /** Called once the banner has been put on screen; see the note on showing it only once. */
    fun markShown() {
        handled = true
    }

    fun spec(activity: Activity): TopBanner.Spec {
        val autoUpdate = autoUpdateOn()
        val count = if (autoUpdate) needsConfirmationCount() else updateCount()
        return TopBanner.Spec(
            id = ID,
            iconRes = R.drawable.ic_extension,
            title = activity.getString(
                if (autoUpdate) R.string.extension_updates_need_confirmation
                else R.string.extension_updates_available
            ),
            subtitle = activity.resources.getQuantityString(
                if (autoUpdate) R.plurals.extension_updates_need_confirmation_count
                else R.plurals.extension_updates_count,
                count, count
            ),
            actionLabel = activity.getString(R.string.review),
            onAction = { current ->
                handled = true
                current.startActivity(
                    Intent(current, ExtensionsActivity::class.java)
                        .putExtra("tab", 0) // Updates tab
                )
            },
            onDismiss = { handled = true },
        )
    }
}
