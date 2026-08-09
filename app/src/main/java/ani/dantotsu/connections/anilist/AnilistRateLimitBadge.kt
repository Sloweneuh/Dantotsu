package ani.dantotsu.connections.anilist

import android.app.Activity
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.formatEta
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.snackString
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Shows an AniList rate limit as a countdown the user can glance at and then ignore.
 *
 * A rate limit is not the user's problem to solve — it ends by itself — so it gets the quietest
 * surface the app has that is still visible: the same floating pill the download queue uses, in the
 * same corner, stacked above it. Not a banner, which is for things somebody has to decide, and not
 * a notification, which would put a wait that is usually under a minute into the system shade.
 *
 * What it's actually for is answering "why is nothing loading" without the user having to ask.
 * Before this, a limit was a toast per blocked query and then nothing, so a minute of the app
 * quietly refusing to load anything looked exactly like a minute of the app being broken.
 *
 * Attached from [ani.dantotsu.initActivity] alongside the download pill, which is what keeps it off
 * the reader and the player — neither calls it.
 */
fun attachAnilistRateLimitBadge(activity: Activity) {
    val owner = activity as? ComponentActivity ?: return
    activity.window.decorView.post {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@post
        if (content.findViewById<View>(R.id.anilistRateLimitRoot) != null) return@post

        val badge = LayoutInflater.from(activity)
            .inflate(R.layout.view_anilist_rate_limit_badge, content, false)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply {
            rightMargin = 16f.px
            // One pill's height above the download pill's slot, so the two never share it. The gap
            // when nothing is downloading is the price of never having to coordinate them.
            bottomMargin = navBarHeight + 96f.px + 68f.px
        }
        content.addView(badge, lp)

        val progress = badge.findViewById<CircularProgressIndicator>(R.id.anilistRateLimitProgress)
        val countdown = badge.findViewById<TextView>(R.id.anilistRateLimitCountdown)
        badge.visibility = View.GONE
        // The pill states the wait; tapping says what is waiting, for anyone who wonders which of
        // the app's several backends stopped answering.
        badge.setOnClickListener { snackString(AnilistRateLimit.waitMessage()) }

        owner.lifecycleScope.launch {
            AnilistRateLimit.window.collectLatest { window ->
                if (window == null) {
                    badge.visibility = View.GONE
                    return@collectLatest
                }
                badge.visibility = View.VISIBLE
                setIndeterminate(progress, !window.announced)
                // Nothing arrives from AniList to say the wait is over, so the countdown is also
                // what notices. collectLatest cancels this loop if a new window replaces the one
                // being drawn, which is why the tick can assume its own window is still current.
                while (AnilistRateLimit.isLimited()) {
                    countdown.text = formatEta(AnilistRateLimit.remainingMillis())
                    if (window.announced) {
                        progress.setProgressCompat(AnilistRateLimit.remainingPercent(), true)
                    }
                    delay(250)
                }
                AnilistRateLimit.clear()
            }
        }
    }
}

/** [CircularProgressIndicator] only picks up a mode change while hidden. */
private fun setIndeterminate(progress: CircularProgressIndicator, indeterminate: Boolean) {
    if (progress.isIndeterminate == indeterminate) return
    progress.hide()
    progress.isIndeterminate = indeterminate
    progress.show()
}
