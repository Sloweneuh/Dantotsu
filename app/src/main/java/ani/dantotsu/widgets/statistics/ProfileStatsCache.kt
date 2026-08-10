package ani.dantotsu.widgets.statistics

import android.content.Context
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import androidx.annotation.StringRes
import com.google.gson.Gson

/** Every number the stats widget can show, plus who they belong to. */
data class ProfileStats(
    val userId: Int,
    val userName: String,
    val avatarUrl: String,
    val animeCount: Int,
    val episodesWatched: Int,
    val minutesWatched: Int,
    val animeMeanScore: Float,
    val mangaCount: Int,
    val chaptersRead: Int,
    val volumesRead: Int,
    val mangaMeanScore: Float
)

/**
 * One number a widget cell can be set to show, plus how to read it out of [ProfileStats].
 *
 * The same set the home screen's own configurable stat row already offers — reusing its string
 * resources rather than inventing parallel ones — but persisted as an enum, matching every other
 * per-widget setting in [ani.dantotsu.widgets.WidgetPrefs], rather than by the numeric index the home
 * screen prefs use.
 */
enum class ProfileStat(@StringRes val labelRes: Int) {
    NONE(R.string.none),
    ANIME_COUNT(R.string.anime_count),
    EPISODES_WATCHED(R.string.episodes_watched),
    ANIME_MEAN_SCORE(R.string.anime_mean_score),
    DAYS_WATCHED(R.string.days_watched),
    MANGA_COUNT(R.string.manga_count),
    CHAPTERS_READ(R.string.chapters_read),
    VOLUMES_READ(R.string.volumes_read),
    MANGA_MEAN_SCORE(R.string.manga_mean_score);

    /** This stat's value out of [stats], formatted the same way the home screen's row does. */
    fun value(stats: ProfileStats): String = when (this) {
        NONE -> ""
        ANIME_COUNT -> stats.animeCount.toString()
        EPISODES_WATCHED -> stats.episodesWatched.toString()
        ANIME_MEAN_SCORE -> "%.1f".format(stats.animeMeanScore)
        DAYS_WATCHED -> daysWatched(stats.minutesWatched)
        MANGA_COUNT -> stats.mangaCount.toString()
        CHAPTERS_READ -> stats.chaptersRead.toString()
        VOLUMES_READ -> stats.volumesRead.toString()
        MANGA_MEAN_SCORE -> "%.1f".format(stats.mangaMeanScore)
    }

    companion object {
        fun from(name: String?, default: ProfileStat): ProfileStat =
            entries.firstOrNull { it.name == name } ?: default

        /** A whole number of days when minutes divide evenly, one decimal place otherwise. */
        private fun daysWatched(minutesWatched: Int): String {
            val days = minutesWatched / 1440.0
            return if (days == days.toLong().toDouble()) days.toLong().toString()
            else "%.1f".format(days)
        }
    }
}

/**
 * Caches the profile stats behind every instance of the widget.
 *
 * Without this each widget refetched the whole profile on every update, showed nothing at all while
 * waiting, and went blank when the request failed. AniList only recomputes these figures every couple
 * of days anyway, so serving a cached copy costs nothing in accuracy.
 */
object ProfileStatsCache {

    private const val PREFS = "ani.dantotsu.widget.stats"
    private const val KEY = "stats"
    private const val UPDATED = "updated"
    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    private val gson = Gson()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLoggedOut(): Boolean = PrefManager.getVal<String>(PrefName.AnilistUserId).isNullOrEmpty()

    /** The last stats stored, however old, or null if there are none. */
    fun cached(context: Context): ProfileStats? = try {
        prefs(context).getString(KEY, null)?.let { gson.fromJson(it, ProfileStats::class.java) }
    } catch (e: Exception) {
        Logger.log("Stats widget cache unreadable: $e")
        null
    }

    /**
     * Cached stats when they are recent enough, otherwise freshly fetched.
     *
     * Returns null only when there is nothing to show at all — not signed in, or a failed fetch with an
     * empty cache. A failure with a cache behind it returns the cached copy.
     */
    suspend fun load(context: Context, force: Boolean = false): ProfileStats? {
        val userId = PrefManager.getVal<String>(PrefName.AnilistUserId)
        if (userId.isNullOrEmpty()) return null
        val existing = cached(context)
        val age = System.currentTimeMillis() - prefs(context).getLong(UPDATED, 0)
        if (!force && existing != null && age < MAX_AGE_MS) return existing

        val user = Anilist.query.getUserProfile(userId.toInt())?.data?.user ?: return existing
        val stats = ProfileStats(
            userId = user.id,
            userName = user.name,
            avatarUrl = user.avatar?.medium.orEmpty(),
            animeCount = user.statistics.anime.count,
            episodesWatched = user.statistics.anime.episodesWatched,
            minutesWatched = user.statistics.anime.minutesWatched,
            animeMeanScore = user.statistics.anime.meanScore,
            mangaCount = user.statistics.manga.count,
            chaptersRead = user.statistics.manga.chaptersRead,
            volumesRead = user.statistics.manga.volumesRead,
            mangaMeanScore = user.statistics.manga.meanScore
        )
        prefs(context).edit()
            .putString(KEY, gson.toJson(stats))
            .putLong(UPDATED, System.currentTimeMillis())
            .apply()
        return stats
    }
}
