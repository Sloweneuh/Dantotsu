package ani.dantotsu.settings.saving

import android.content.Context
import android.content.SharedPreferences
import ani.dantotsu.util.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Keeps the per-episode and per-chapter resume data from growing forever.
 *
 * Every episode watched and chapter read leaves custom vals behind — `<id>_<number>` (the position
 * or page), `<id>_<number>_max` and `<id>_<number>_offset` — and nothing ever removed them, so the
 * Irrelevant preferences grew by a few keys per episode for as long as the app was used. That map
 * is resident for the life of the process and rewritten whole on every save, so its size costs on
 * every position the player saves.
 *
 * Each media's resume data is dropped once nothing about it has been written for [MAX_AGE_DAYS]:
 * a series left that long starts its next episode from the top, and its episode list loses the
 * part-watched bars. Media still being watched or read keep everything. The current
 * episode/chapter pointers (`<id>_current_ep|_chp`) are kept regardless — one key a media.
 *
 * When a media was last written is kept here, in its own small file rather than among the keys it
 * dates: per id, to the day, so a save only writes when the day changes. A media first seen by
 * [prune] without a date is dated then, so data from before this existed gets the full period too.
 */
object ResumePositions {

    private const val MAX_AGE_DAYS = 180L
    private const val FILE = "resume_positions_touched"
    private const val LAST_PRUNE = "last_prune_day"

    // The number may be decimal (chapters); ids are negative for extension-only media.
    private val KEY_RE = Regex("""^(-?\d+)_\d+(?:\.\d+)?(?:_max|_offset)?$""")

    private var prefs: SharedPreferences? = null

    /** The day each id was last stamped this run, so repeated saves skip the file. */
    private val stamped = ConcurrentHashMap<String, Long>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun today() = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis())

    /** Called for every custom val written: dates the media when [key] is its resume data. */
    fun touched(key: String) {
        val id = KEY_RE.matchEntire(key)?.groupValues?.get(1) ?: return
        val day = today()
        if (stamped.put(id, day) == day) return
        prefs?.edit()?.putLong(id, day)?.apply()
    }

    /**
     * Removes the resume data of every media not written to in [MAX_AGE_DAYS]. At most once a
     * day, and never on the main thread: it reads the whole Irrelevant map.
     */
    fun prune() {
        val dates = prefs ?: return
        val irrelevant = PrefManager.irrelevantPrefs() ?: return
        val day = today()
        if (dates.getLong(LAST_PRUNE, -1L) == day) return

        val byId = HashMap<String, MutableList<String>>()
        irrelevant.all.keys.forEach { key ->
            val id = KEY_RE.matchEntire(key)?.groupValues?.get(1) ?: return@forEach
            byId.getOrPut(id) { mutableListOf() } += key
        }

        val dead = mutableListOf<String>()
        with(dates.edit()) {
            byId.forEach { (id, keys) ->
                val last = dates.getLong(id, -1L)
                when {
                    last < 0 -> putLong(id, day)
                    day - last > MAX_AGE_DAYS -> {
                        dead += keys
                        remove(id)
                        stamped.remove(id)
                    }
                }
            }
            // Ids whose data is gone some other way (a sync pull, a cleared cache) need no date.
            dates.all.keys.forEach { id -> if (id != LAST_PRUNE && id !in byId) remove(id) }
            putLong(LAST_PRUNE, day)
            apply()
        }
        if (dead.isNotEmpty()) PrefManager.applyCustomVals(removes = dead.toSet())
        Logger.log("ResumePositions: pruned ${dead.size} keys of ${byId.size} media")
    }
}
