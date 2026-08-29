package ani.dantotsu.media

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Small shared formatting helpers for the Kitsu / Simkl media pages. */
object TrackerFmt {

    private val dateOut = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    /** "2023-09-29" or "2023-09-29T14:00:00Z" -> "29 Sep 2023". Returns the input on a parse miss. */
    fun date(iso: String?): String? {
        val d = iso?.trim()?.takeIf { it.length >= 10 }?.substring(0, 10) ?: return null
        return try {
            LocalDate.parse(d).format(dateOut)
        } catch (_: Exception) {
            iso
        }
    }

    /**
     * Anime airing season from a start date, when the tracker doesn't state one outright.
     * Winter = Jan–Mar, Spring = Apr–Jun, Summer = Jul–Sep, Fall = Oct–Dec.
     */
    fun animeSeason(iso: String?): String? {
        val month = iso?.trim()?.takeIf { it.length >= 7 }?.substring(5, 7)?.toIntOrNull() ?: return null
        return when (month) {
            1, 2, 3 -> "Winter"
            4, 5, 6 -> "Spring"
            7, 8, 9 -> "Summer"
            10, 11, 12 -> "Fall"
            else -> null
        }
    }
}
