package ani.dantotsu.parsers.novel.lnreader

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns a plugin's release time into something worth showing.
 *
 * `releaseTime` is a free-form string: the type calls for `YYYY-MM-DD` or an ISO timestamp, but
 * plugins also pass through whatever the site printed — "2 days ago", "Chapter 5", an empty string.
 * So a value that parses is formatted like the manga chapter list formats its dates, and anything
 * else is shown exactly as the source wrote it rather than replaced with a guess.
 */
object LNReaderDates {

    private val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
        "yyyy/MM/dd",
        "dd/MM/yyyy",
        "MMM dd, yyyy",
    )

    /** The oldest date worth believing; anything earlier is a parsing artefact, not a release. */
    private val EARLIEST = Date(946684800000L)

    fun format(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return ""
        val date = parse(value) ?: return value
        if (date < EARLIEST) return value

        val difference = System.currentTimeMillis() - date.time
        if (difference < 0) return absolute(date)

        return when (val days = difference / (1000 * 60 * 60 * 24)) {
            0L -> {
                val hours = difference / (1000 * 60 * 60)
                val minutes = (difference / (1000 * 60)) % 60
                when {
                    hours > 0 -> "$hours hour${if (hours > 1) "s" else ""} ago"
                    minutes > 0 -> "$minutes minute${if (minutes > 1) "s" else ""} ago"
                    else -> "Just now"
                }
            }
            1L -> "1 day ago"
            in 2..6 -> "$days days ago"
            else -> absolute(date)
        }
    }

    private fun absolute(date: Date) =
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date)

    private fun parse(value: String): Date? {
        // Epoch values turn up as bare digits, in seconds or milliseconds.
        value.toLongOrNull()?.let {
            return Date(if (it < 100_000_000_000L) it * 1000 else it)
        }
        for (pattern in patterns) {
            runCatching {
                val format = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                    isLenient = false
                    // A trailing Z is UTC; the patterns without a zone are read as local time,
                    // which is the best guess available for a site that did not say.
                    if (pattern.endsWith("'Z'")) timeZone = TimeZone.getTimeZone("UTC")
                }
                return format.parse(value) ?: return@runCatching
            }
        }
        return null
    }
}
