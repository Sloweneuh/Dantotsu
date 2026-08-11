package ani.dantotsu.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ItemQuickSettingsBinding
import ani.dantotsu.isOnline
import ani.dantotsu.loadImage
import ani.dantotsu.profile.ProfileActivity
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.SettingsDialogFragment.Companion.PageType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

// Reaching the settings sheet used to mean going all the way back to a main tab, because the only
// thing that opened it was the avatar in the home/anime/manga headers. The helpers here put it
// within reach of every other screen without adding chrome to bars that are already full:
//
//  - bindQuickSettings() on screens whose top bar has room for the visible item_quick_settings
//    button (the same avatar affordance the main tabs use).
//  - enableSettingsLongPress() on the in-app back arrow everywhere else, so the entry point exists
//    even where nothing new can be drawn.
//
// Deliberately not wired to the system back button: gesture navigation has no long-press, and
// overloading hardware back would break the one gesture users rely on to leave a screen.

private const val SETTINGS_SHEET_TAG = "settingsSheet"

/**
 * Opens the settings sheet, ignoring the request if one is already up or the activity has already
 * saved its state — long-presses and stray taps arrive at both of those moments.
 */
fun FragmentActivity.openSettingsSheet(pageType: PageType = PageType.HOME) {
    val manager = supportFragmentManager
    if (manager.isStateSaved || manager.isDestroyed) return
    if (manager.findFragmentByTag(SETTINGS_SHEET_TAG) != null) return
    SettingsDialogFragment.newInstance(pageType).show(manager, SETTINGS_SHEET_TAG)
}

/**
 * Makes a long-press on [this] open the settings sheet, leaving its normal click alone.
 *
 * Applied to in-app back arrows: they exist on virtually every screen, sit in the same corner
 * everywhere, and have no long-press behaviour of their own to collide with.
 */
fun View.enableSettingsLongPress(pageType: PageType = PageType.HOME) {
    setOnLongClickListener {
        val activity = context.findFragmentActivity() ?: return@setOnLongClickListener false
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        activity.openSettingsSheet(pageType)
        true
    }
}

/**
 * Wires up the included `item_quick_settings` button: tap opens the sheet, long-press jumps
 * straight to the profile, mirroring the home header avatar.
 *
 * Pass `useAvatar = false` on screens that already show somebody's avatar — the profile page in
 * particular, where a second, smaller face next to the one being viewed just reads as confusion.
 * Those keep the placeholder glyph.
 */
fun ItemQuickSettingsBinding.bindQuickSettings(
    pageType: PageType = PageType.HOME,
    useAvatar: Boolean = true
) {
    val activity = root.context.findFragmentActivity() ?: return
    bindQuickSettings(activity, pageType, useAvatar)
}

fun ItemQuickSettingsBinding.bindQuickSettings(
    activity: FragmentActivity,
    pageType: PageType = PageType.HOME,
    useAvatar: Boolean = true
) {
    root.setSafeOnClickListener { activity.openSettingsSheet(pageType) }

    val offline = !isOnline(activity) || PrefManager.getVal<Boolean>(PrefName.OfflineMode)
    val avatar = Anilist.avatar
    if (useAvatar && !offline && Anilist.token != null && !avatar.isNullOrEmpty()) {
        // The glyph is only a placeholder for the signed-in user; once we have their picture the
        // tint baked into the layout would wash it out.
        quickSettingsAvatar.imageTintList = null
        quickSettingsAvatar.setPadding(0, 0, 0, 0)
        quickSettingsAvatar.loadImage(avatar)
        root.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            ContextCompat.startActivity(
                activity, Intent(activity, ProfileActivity::class.java)
                    .putExtra("userId", Anilist.userid), null
            )
            true
        }
    }
}

/** Views only ever see a themed [ContextWrapper], so the hosting activity has to be unwrapped. */
private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
