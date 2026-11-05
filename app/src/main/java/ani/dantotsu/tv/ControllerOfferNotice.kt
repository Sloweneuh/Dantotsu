package ani.dantotsu.tv

import android.app.Activity
import ani.dantotsu.App
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.AppNotices
import ani.dantotsu.util.TopBanner

/**
 * Offers TV mode when a controller connects. It's the one thing a controller or a remote can do in
 * the phone interface ([PhoneControllerBlock]), or there'd be no way into TV mode from one.
 *
 * Offered once per launch, not again after leaving TV mode during it, and never to someone who
 * switched TV mode off in the settings: that's a choice they made, not something to argue with.
 */
object ControllerOfferNotice {

    const val ID = "controller_offer"

    @Volatile private var pending = false
    private var offered = false

    private fun turnedOff() = PrefManager.getVal<Int>(PrefName.BigPictureMode) == TvMode.OFF

    fun isPending(): Boolean {
        val context = App.context ?: return false
        return pending && !turnedOff() && !TvMode.isOn(context)
    }

    fun offer(activity: Activity) {
        if (offered || turnedOff() || TvMode.isOn(activity)) return
        offered = true
        pending = true
        AppNotices.showPending(activity)
    }

    /** Just back from TV mode: not a moment to offer it. */
    fun skipThisLaunch() {
        offered = true
        pending = false
    }

    /**
     * The app's main screen closed for good, so opening it again is a new launch, even if Android
     * kept the process alive in between.
     */
    fun onAppClosed() {
        offered = false
        pending = false
    }

    /** The controller went away, and the offer with it. */
    fun withdraw() {
        pending = false
        if (TopBanner.isShowing(ID)) TopBanner.dismiss(ID)
    }

    /**
     * A controller's press on [activity]. Only with the card on that screen: every other notice,
     * and everything else in the phone interface, ignores a controller.
     */
    fun accept(activity: Activity): Boolean {
        if (!isPending() || !TopBanner.isShowingIn(activity, listOf(ID))) return false
        pending = false
        TopBanner.dismiss(ID)
        TvMode.enter(activity)
        return true
    }

    fun spec(activity: Activity) = TopBanner.Spec(
        id = ID,
        iconRes = R.drawable.ic_round_sports_esports_outline_24,
        title = activity.getString(R.string.tv_controller_offer_title),
        subtitle = activity.getString(R.string.tv_controller_offer_hint),
        actionLabel = activity.getString(R.string.tv_switch),
        onAction = { current ->
            pending = false
            TvMode.enter(current)
        },
        onDismiss = { pending = false },
    )
}
