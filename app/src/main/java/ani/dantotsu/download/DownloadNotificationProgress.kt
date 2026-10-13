package ani.dantotsu.download

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import ani.dantotsu.R

/**
 * Download progress on a notification. On Android 16+ it's the system's progress style — a
 * fuller bar with the download icon travelling along it; below that, [NotificationCompat]
 * falls back to the classic bar set alongside it. `(0, 0, false)` clears the bar, as with
 * [NotificationCompat.Builder.setProgress].
 */
fun NotificationCompat.Builder.setDownloadProgress(
    context: Context,
    max: Int,
    progress: Int,
    indeterminate: Boolean = false,
): NotificationCompat.Builder {
    setProgress(max, progress, indeterminate)
    if (max <= 0 && !indeterminate) {
        setStyle(null)
        return this
    }
    return setStyle(
        NotificationCompat.ProgressStyle()
            .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(max.coerceAtLeast(1))))
            .setProgress(progress.coerceIn(0, max.coerceAtLeast(1)))
            .setProgressIndeterminate(indeterminate)
            .setStyledByProgress(true)
            .setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.ic_download_24))
    )
}
