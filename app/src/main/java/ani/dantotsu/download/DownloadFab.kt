package ani.dantotsu.download

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Attaches a floating round download-progress button to any normal activity's content view.
 * Called from [ani.dantotsu.initActivity], the single hook every non-reader/non-player activity
 * runs — so the reader and player (which never call it) are naturally excluded. The button is
 * only visible while downloads are active and opens the [DownloadActivity] on tap.
 */
/** PendingIntent that opens the download queue/management screen (for notification taps). */
fun Context.downloadActivityIntent(): PendingIntent =
    PendingIntent.getActivity(
        this, 0,
        Intent(this, DownloadActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE
    )

fun attachDownloadFab(activity: Activity) {
    if (activity is DownloadActivity) return
    val owner = activity as? ComponentActivity ?: return
    activity.window.decorView.post {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@post
        if (content.findViewById<View>(R.id.downloadFabRoot) != null) return@post

        val fab = LayoutInflater.from(activity)
            .inflate(R.layout.view_download_fab, content, false)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply {
            rightMargin = 16f.px
            bottomMargin = navBarHeight + 96f.px
        }
        content.addView(fab, lp)

        val progress = fab.findViewById<CircularProgressIndicator>(R.id.downloadFabProgress)
        fab.visibility = View.GONE
        fab.setOnClickListener {
            activity.startActivity(Intent(activity, DownloadActivity::class.java))
        }

        owner.lifecycleScope.launch {
            DownloadTracker.items.collectLatest { list ->
                if (list.isEmpty()) {
                    fab.visibility = View.GONE
                } else {
                    fab.visibility = View.VISIBLE
                    // Whole-session progress: fills to 100% only when every download is done.
                    val overall = DownloadTracker.overallPercent()
                    if (overall > 0) {
                        if (progress.isIndeterminate) {
                            progress.hide()
                            progress.isIndeterminate = false
                            progress.show()
                        }
                        progress.setProgressCompat(overall, true)
                    } else if (!progress.isIndeterminate) {
                        progress.hide()
                        progress.isIndeterminate = true
                        progress.show()
                    }
                }
            }
        }
    }
}
