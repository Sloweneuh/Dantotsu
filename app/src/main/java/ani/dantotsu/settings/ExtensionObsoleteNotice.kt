package ani.dantotsu.settings

import android.app.Activity
import android.content.Intent
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.TopBanner
import eu.kanade.domain.source.service.SourcePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Points out that installed extensions are no longer listed by any of their configured repos.
 *
 * Modeled on [ExtensionUpdateNotice]: shown once per process rather than following the user
 * around, since nothing breaks while this goes unread and the "Obsolete extension" label is still
 * sitting on the row whenever they get to the extensions screen on their own.
 */
object ExtensionObsoleteNotice {

    const val ID = "extension_obsolete"

    @Volatile private var handled = false

    /** Per-media-type obsolete counts, read together so [targetTab] and the total stay consistent. */
    private fun counts(): Triple<Int, Int, Int> = runCatching {
        val preferences: SourcePreferences = Injekt.get()
        Triple(
            preferences.animeExtensionObsoleteCount().get(),
            preferences.mangaExtensionObsoleteCount().get(),
            preferences.novelExtensionObsoleteCount().get(),
        )
    }.getOrDefault(Triple(0, 0, 0))

    private fun obsoleteCount(): Int = counts().let { (a, m, n) -> a + m + n }

    fun isPending(): Boolean = !handled && obsoleteCount() > 0

    /** Called once the banner has been put on screen; see the note on showing it only once. */
    fun markShown() {
        handled = true
    }

    fun spec(activity: Activity) = TopBanner.Spec(
        id = ID,
        iconRes = R.drawable.ic_extension,
        title = activity.getString(R.string.obsolete_extensions_found),
        subtitle = activity.resources.getQuantityString(
            R.plurals.obsolete_extensions_count, obsoleteCount(), obsoleteCount()
        ),
        actionLabel = activity.getString(R.string.review),
        onAction = { current ->
            handled = true
            current.startActivity(
                Intent(current, ExtensionsActivity::class.java)
                    .putExtra("tab", targetTab())
            )
        },
        onDismiss = { handled = true },
    )

    /**
     * The Installed tab for whichever media type actually has an obsolete extension — Anime, then
     * Manga, then Novel — shifted by one when the Updates tab is also present, exactly as
     * [ExtensionsActivity] lays its tabs out.
     */
    private fun targetTab(): Int {
        val (anime, manga, novel) = counts()
        val offset = if (hasAnyExtensionUpdate()) 1 else 0
        return offset + when {
            anime > 0 -> 0
            manga > 0 -> 2
            novel > 0 -> 4
            else -> 0
        }
    }

    private fun hasAnyExtensionUpdate(): Boolean = runCatching {
        val preferences: SourcePreferences = Injekt.get()
        preferences.animeExtensionUpdatesCount().get() > 0 ||
            preferences.mangaExtensionUpdatesCount().get() > 0 ||
            preferences.novelExtensionUpdatesCount().get() > 0 ||
            PrefManager.getVal<Int>(PrefName.LNReaderUpdatesCount) > 0
    }.getOrDefault(false)
}
