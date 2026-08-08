package ani.dantotsu.util

import android.content.Context
import ani.dantotsu.R

/**
 * Durations written the way a person would say them — "12 hours", "2 days", "90 minutes".
 *
 * Settings store these as a bare number of minutes (or seconds), which is fine while the screen that
 * owns the setting can label it. Anywhere else — the sync conflict list, which puts two devices'
 * values side by side — the number arrives with nothing attached, and "1440" against "10080" asks
 * the user to do arithmetic before they can choose.
 *
 * Whole days and whole hours collapse to those units; anything else stays in the unit it was given
 * rather than being split into "1 hour 30 minutes", which is longer to read and no clearer at the
 * sizes these settings actually take.
 *
 * Zero is left to the caller: it means "off" for an interval and "none" for a length, and neither
 * reads as "0 minutes".
 */
fun Context.durationLabel(minutes: Long): String {
    val days = minutes / 1440L
    val hours = minutes / 60L
    return when {
        minutes % 1440L == 0L && days > 0L ->
            resources.getQuantityString(R.plurals.interval_days, days.toInt(), days.toInt())

        minutes % 60L == 0L && hours > 0L ->
            resources.getQuantityString(R.plurals.interval_hours, hours.toInt(), hours.toInt())

        else -> resources.getQuantityString(
            R.plurals.interval_minutes, minutes.toInt(), minutes.toInt()
        )
    }
}

/** As [durationLabel], for a setting stored in seconds. Whole minutes are handed on to it. */
fun Context.durationLabelSeconds(seconds: Long): String =
    if (seconds % 60L == 0L && seconds >= 60L) durationLabel(seconds / 60L)
    else resources.getQuantityString(R.plurals.interval_seconds, seconds.toInt(), seconds.toInt())
