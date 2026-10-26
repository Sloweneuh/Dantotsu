package ani.dantotsu.others

import android.app.Activity
import ani.dantotsu.BuildConfig
import ani.dantotsu.R
import ani.dantotsu.util.TopBanner

/**
 * Points out that a newer version of the app is out, and leads to the update screen.
 *
 * This was a bottom sheet raised from the update check, which landed on whichever screen happened
 * to be open as the check finished, and in front of it. A banner waits its turn behind the notices
 * that matter more (see [ani.dantotsu.util.AppNotices]) and follows the user until they act on it
 * or swipe it away.
 *
 * The check lives in the google flavour, which alone ships the update screen; it hands over the
 * release with [offer], along with how to open that screen. Shown once per process, like the
 * extension updates: the release is still out on the next launch, which raises it again.
 */
object AppUpdateNotice {

    const val ID = "app_update"

    private class Offer(val version: String, val open: (Activity) -> Unit)

    @Volatile private var offer: Offer? = null
    @Volatile private var handled = false

    /** [again] raises the banner even if it was already shown, or swiped away, this session. */
    fun offer(version: String, again: Boolean = false, open: (Activity) -> Unit) {
        offer = Offer(version, open)
        if (again) handled = false
    }

    fun isPending(): Boolean = !handled && offer != null

    /** Called once the banner has been put on screen; see the note on showing it only once. */
    fun markShown() {
        handled = true
    }

    fun spec(activity: Activity): TopBanner.Spec {
        val offer = offer
        return TopBanner.Spec(
            id = ID,
            iconRes = R.drawable.ic_round_system_update_24,
            title = activity.getString(R.string.app_update_available),
            subtitle = offer?.let {
                activity.getString(R.string.app_update_notice_subtitle, it.version, BuildConfig.VERSION_NAME)
            },
            actionLabel = activity.getString(R.string.review),
            onAction = { current ->
                handled = true
                offer?.open?.invoke(current)
            },
            onDismiss = { handled = true },
        )
    }
}
