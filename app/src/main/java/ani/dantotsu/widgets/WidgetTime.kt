package ani.dantotsu.widgets

import android.content.Context
import android.text.format.DateUtils
import ani.dantotsu.R
import java.util.Calendar

/**
 * Localised time text for widgets.
 *
 * The upcoming widget used to build its countdown by hand as `"$days days $hours hours $minutes
 * minutes"` — untranslated in every language, and reading "0 days 0 hours 4 minutes" for something
 * about to air. [DateUtils] already renders exactly this, in the user's language, for free.
 */
object WidgetTime {

    /**
     * "in 3 days", "in 5 hours", "in 12 minutes" — or a fixed label once the episode is out.
     *
     * Takes an absolute airing time rather than a remaining duration on purpose: a cached duration
     * silently ages, which is how the old widget ended up counting down past zero into negatives.
     */
    fun untilAiring(context: Context, airingAtMillis: Long, now: Long = System.currentTimeMillis()): String {
        if (airingAtMillis <= now) return context.getString(R.string.widget_aired)
        return DateUtils.getRelativeTimeSpanString(
            airingAtMillis,
            now,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }

    /** "Today", "Tomorrow", or an abbreviated weekday and date for anything further out. */
    fun dayLabel(context: Context, millis: Long, now: Long = System.currentTimeMillis()): String =
        when (daysBetween(now, millis)) {
            0 -> context.getString(R.string.widget_today)
            1 -> context.getString(R.string.widget_tomorrow)
            else -> DateUtils.formatDateTime(
                context,
                millis,
                DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or
                    DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_NO_YEAR
            )
        }

    /** Local clock time, in the user's 12/24-hour preference. */
    fun timeLabel(context: Context, millis: Long): String =
        DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)

    /**
     * Calendar days from [from] to [to], counted by date and not by elapsed milliseconds — 23:00 to
     * 01:00 is the next day, not "the same day, two hours later".
     */
    fun daysBetween(from: Long, to: Long): Int {
        val start = midnight(from)
        val end = midnight(to)
        return ((end - start) / DateUtils.DAY_IN_MILLIS).toInt()
    }

    /** Start of the local day containing [millis]. Doubles as the grouping key for day sections. */
    fun midnight(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
