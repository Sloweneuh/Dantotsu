package ani.dantotsu.connections.anilist

import android.app.Activity
import ani.dantotsu.R
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.util.TopBanner

/**
 * Says out loud that AniList, not the app, is the reason nothing is loading.
 *
 * Without it a maintenance window is indistinguishable from a broken build: lists come back empty,
 * a progress update silently doesn't stick, and the only clue is a snackbar that has already gone by
 * the time the user looks up. The banner states the cause once, stays until the user has read it,
 * and offers whatever is actually worth doing about it — which is not the same thing in every
 * outage, hence the two actions below.
 */
object AnilistOutageNotice {

    const val ID = "anilist_outage"

    /**
     * Where AniList announces that it has taken the API down, and the only place that says when it
     * is coming back (https://docs.anilist.co/guide/considerations).
     */
    private const val DISCORD = "https://discord.com/invite/anilist"

    /**
     * The pause this notice was dismissed for, so swiping it away silences that outage and not the
     * next one. Keyed on the window's end time rather than a flag because a fresh refusal moves
     * that end time, which is exactly when the user should hear about it again.
     */
    @Volatile
    private var dismissedFor = 0L

    fun isPending(): Boolean =
        AnilistApiStatus.isPaused() && AnilistApiStatus.pausedUntil != dismissedFor

    fun spec(activity: Activity): TopBanner.Spec {
        // A deliberate outage has an announcement behind it and no end the app can guess at, so the
        // useful move is to go read it. Everything else — an unreachable origin, a blocked IP — is
        // something that just stops being true, where trying again is the whole answer.
        val announced = AnilistApiStatus.kind == AnilistApiStatus.Kind.DISABLED
        return TopBanner.Spec(
            id = ID,
            iconRes = R.drawable.ic_round_anilist_alert_24,
            title = AnilistApiStatus.title(),
            subtitle = AnilistApiStatus.detail(),
            actionLabel = activity.getString(
                if (announced) R.string.anilist_discord else R.string.retry
            ),
            onAction = { current ->
                if (announced) {
                    openLinkInBrowser(DISCORD)
                } else {
                    AnilistApiStatus.retryNow()
                    // Same move as the sync reload notice: the screens already asked AniList and
                    // got nothing, and rebuilding is the one way to make every one of them ask
                    // again.
                    if (!current.isFinishing && !current.isDestroyed) current.recreate()
                }
            },
            onDismiss = { dismissedFor = AnilistApiStatus.pausedUntil },
        )
    }
}
