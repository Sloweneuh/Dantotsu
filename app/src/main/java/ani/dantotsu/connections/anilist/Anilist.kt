package ani.dantotsu.connections.anilist

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import ani.dantotsu.R
import ani.dantotsu.client
import ani.dantotsu.connections.comments.CommentsAPI
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import androidx.core.net.toUri

object Anilist {
    val query: AnilistQueries = AnilistQueries()
    val mutation: AnilistMutations = AnilistMutations()

    var token: String? = null
    var username: String? = null

    var userid: Int? = null
    var avatar: String? = null
    var bg: String? = null
    var episodesWatched: Int? = null
    var chapterRead: Int? = null
    var animeCount: Int? = null
    var minutesWatched: Int? = null
    var mangaCount: Int? = null
    var volumesRead: Int? = null
    var animeMeanScore: Float? = null
    var mangaMeanScore: Float? = null
    var unreadNotificationCount: Int = 0

    var genres: ArrayList<String>? = null
    var tags: Map<Boolean, List<String>>? = null

    var initialized = false
    var adult: Boolean = false
    var titleLanguage: String? = null
    var staffNameLanguage: String? = null
    var airingNotifications: Boolean = false
    var restrictMessagesToFollowing: Boolean = false
    var scoreFormat: String? = null
    var rowOrder: String? = null
    var activityMergeTime: Int? = null
    var timezone: String? = null
    var animeCustomLists: List<String>? = null
    var mangaCustomLists: List<String>? = null

    val sortBy = listOf(
        "SCORE_DESC",
        "POPULARITY_DESC",
        "TRENDING_DESC",
        "START_DATE_DESC",
        "TITLE_ENGLISH",
        "TITLE_ENGLISH_DESC",
        "SCORE"
    )

    val source = listOf(
        "ORIGINAL",
        "MANGA",
        "LIGHT NOVEL",
        "VISUAL NOVEL",
        "VIDEO GAME",
        "OTHER",
        "NOVEL",
        "DOUJINSHI",
        "ANIME",
        "WEB NOVEL",
        "LIVE ACTION",
        "GAME",
        "COMIC",
        "MULTIMEDIA PROJECT",
        "PICTURE BOOK"
    )

    val animeStatus = listOf(
        "FINISHED",
        "RELEASING",
        "NOT YET RELEASED",
        "CANCELLED"
    )

    val mangaStatus = listOf(
        "FINISHED",
        "RELEASING",
        "NOT YET RELEASED",
        "HIATUS",
        "CANCELLED"
    )

    val seasons = listOf(
        "WINTER", "SPRING", "SUMMER", "FALL"
    )

    val animeFormats = listOf(
        "TV", "TV SHORT", "MOVIE", "SPECIAL", "OVA", "ONA", "MUSIC"
    )

    val mangaFormats = listOf(
        "MANGA", "NOVEL", "ONE SHOT"
    )

    val authorRoles = listOf(
        "Original Creator", "Story & Art", "Story"
    )

    val timeZone = listOf(
        "(GMT-11:00) Pago Pago",
        "(GMT-10:00) Hawaii Time",
        "(GMT-09:00) Alaska Time",
        "(GMT-08:00) Pacific Time",
        "(GMT-07:00) Mountain Time",
        "(GMT-06:00) Central Time",
        "(GMT-05:00) Eastern Time",
        "(GMT-04:00) Atlantic Time - Halifax",
        "(GMT-03:00) Sao Paulo",
        "(GMT-02:00) Mid-Atlantic",
        "(GMT-01:00) Azores",
        "(GMT+00:00) London",
        "(GMT+01:00) Berlin",
        "(GMT+02:00) Helsinki",
        "(GMT+03:00) Istanbul",
        "(GMT+04:00) Dubai",
        "(GMT+04:30) Kabul",
        "(GMT+05:00) Maldives",
        "(GMT+05:30) India Standard Time",
        "(GMT+05:45) Kathmandu",
        "(GMT+06:00) Dhaka",
        "(GMT+06:30) Cocos",
        "(GMT+07:00) Bangkok",
        "(GMT+08:00) Hong Kong",
        "(GMT+08:30) Pyongyang",
        "(GMT+09:00) Tokyo",
        "(GMT+09:30) Central Time - Darwin",
        "(GMT+10:00) Eastern Time - Brisbane",
        "(GMT+10:30) Central Time - Adelaide",
        "(GMT+11:00) Eastern Time - Melbourne, Sydney",
        "(GMT+12:00) Nauru",
        "(GMT+13:00) Auckland",
        "(GMT+14:00) Kiritimati",
    )

    val titleLang = listOf(
        "English (Attack on Titan)",
        "Romaji (Shingeki no Kyojin)",
        "Native (進撃の巨人)"
    )

    val staffNameLang = listOf(
        "Romaji, Western Order (Killua Zoldyck)",
        "Romaji (Zoldyck Killua)",
        "Native (キルア=ゾルディック)"
    )

    val scoreFormats = listOf(
        "100 Point (55/100)",
        "10 Point Decimal (5.5/10)",
        "10 Point (5/10)",
        "5 Star (3/5)",
        "3 Point Smiley :)"
    )

    val rowOrderMap = mapOf(
        "Score" to "score",
        "Title" to "title",
        "Last Updated" to "updatedAt",
        "Last Added" to "id"
    )

    val activityMergeTimeMap = mapOf(
        "Never" to 0,
        "30 mins" to 30,
        "69 mins" to 69,
        "1 hour" to 60,
        "2 hours" to 120,
        "3 hours" to 180,
        "6 hours" to 360,
        "12 hours" to 720,
        "1 day" to 1440,
        "2 days" to 2880,
        "3 days" to 4320,
        "1 week" to 10080,
        "2 weeks" to 20160,
        "Always" to 29160
    )

    private val cal: Calendar = Calendar.getInstance()
    private val currentYear = cal.get(Calendar.YEAR)
    private val currentSeason: Int = when (cal.get(Calendar.MONTH)) {
        0, 1, 2 -> 0
        3, 4, 5 -> 1
        6, 7, 8 -> 2
        9, 10, 11 -> 3
        else -> 0
    }

    fun getDisplayTimezone(apiTimezone: String, context: Context): String {
        val noTimezone = context.getString(R.string.selected_no_time_zone)
        val parts = apiTimezone.split(":")
        if (parts.size != 2) return noTimezone

        val hours = parts[0].toIntOrNull() ?: 0
        val minutes = parts[1].toIntOrNull() ?: 0
        val sign = if (hours >= 0) "+" else "-"
        val formattedHours = String.format(Locale.US, "%02d", abs(hours))
        val formattedMinutes = String.format(Locale.US, "%02d", minutes)

        val searchString = "(GMT$sign$formattedHours:$formattedMinutes)"
        return timeZone.find { it.contains(searchString) } ?: noTimezone
    }

    fun getApiTimezone(displayTimezone: String): String {
        val regex = """\(GMT([+-])(\d{2}):(\d{2})\)""".toRegex()
        val matchResult = regex.find(displayTimezone)
        return if (matchResult != null) {
            val (sign, hours, minutes) = matchResult.destructured
            val formattedSign = if (sign == "+") "" else "-"
            "$formattedSign$hours:$minutes"
        } else {
            "00:00"
        }
    }

    private fun getSeason(next: Boolean): Pair<String, Int> {
        var newSeason = if (next) currentSeason + 1 else currentSeason - 1
        var newYear = currentYear
        if (newSeason > 3) {
            newSeason = 0
            newYear++
        } else if (newSeason < 0) {
            newSeason = 3
            newYear--
        }
        return seasons[newSeason] to newYear
    }

    val currentSeasons = listOf(
        getSeason(false),
        seasons[currentSeason] to currentYear,
        getSeason(true)
    )

    fun loginIntent(context: Context) {
        val clientID = 14959
        try {
            CustomTabsIntent.Builder().build().launchUrl(
                context,
                "https://anilist.co/api/v2/oauth/authorize?client_id=$clientID&response_type=token".toUri()
            )
        } catch (_: ActivityNotFoundException) {
            openLinkInBrowser("https://anilist.co/api/v2/oauth/authorize?client_id=$clientID&response_type=token")
        }
    }

    fun getSavedToken(): Boolean {
        token = PrefManager.getVal(PrefName.AnilistToken, null as String?)
        return !token.isNullOrEmpty()
    }

    /**
     * Restores the saved session into memory, without any network call.
     *
     * The normal restore happens on the way through MainActivity, but a cold start doesn't always
     * go that way: tapping a notification or a media link opens a details activity directly. Those
     * screens then query AniList with no token, so the response carries no `mediaListEntry` and the
     * user's progress comes back empty; [userid] being null also hides the fav button and turns
     * "Add to list" into a login prompt. Both are read back from prefs here so the very first query
     * of the process is already authenticated.
     *
     * [initialized] is deliberately left alone: this is the cached identity, and `getUserId` still
     * needs to fetch the full viewer data (score format, title language, custom lists) once.
     */
    fun restoreSession() {
        if (!getSavedToken()) return
        if (userid == null) userid =
            PrefManager.getVal(PrefName.AnilistUserId, null as String?)?.toIntOrNull()
        if (username == null) username =
            PrefManager.getVal(PrefName.AnilistUserName, null as String?)?.takeIf { it.isNotBlank() }
        // Cached alongside the name, so the avatar survives a cold start and is there offline.
        // Without it the name came back and the picture did not, and the two disagreed everywhere
        // they are shown together.
        if (avatar == null) avatar =
            PrefManager.getVal(PrefName.AnilistAvatar, null as String?)?.takeIf { it.isNotBlank() }
    }

    fun removeSavedToken() {
        token = null
        username = null
        adult = false
        userid = null
        avatar = null
        bg = null
        episodesWatched = null
        chapterRead = null
        animeCount = null
        minutesWatched = null
        mangaCount = null
        volumesRead = null
        animeMeanScore = null
        mangaMeanScore = null
        PrefManager.removeVal(PrefName.AnilistToken)
        PrefManager.removeVal(PrefName.AnilistAvatar)
        //logout from comments api
        CommentsAPI.logout()

    }

    suspend inline fun <reified T : Any> executeQuery(
        query: String,
        variables: String = "",
        force: Boolean = false,
        useToken: Boolean = true,
        show: Boolean = false,
        cache: Int? = null
    ): T? {
        return try {
            if (show) Logger.log("Anilist Query: $query")
            // AniList is refusing traffic and told us so; sending anyway would only add to what it
            // is trying to shed. The banner AnilistApiStatus raised is already explaining why.
            if (AnilistApiStatus.isPaused()) {
                throw Exception(AnilistApiStatus.pausedMessage())
            }
            // Serving out a rate limit. The badge is already counting it down, so this only has to
            // keep the request from being sent — a toast per blocked query was a burst of identical
            // popups saying a number that changed while they were on screen.
            if (AnilistRateLimit.isLimited()) {
                throw Exception(AnilistRateLimit.waitMessage())
            }
            val data = mapOf(
                "query" to query,
                "variables" to variables
            )
            val headers = mutableMapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Accept" to "application/json"
            )

            if (token != null || force) {
                if (token != null && useToken) headers["Authorization"] = "Bearer $token"

                val json = client.post(
                    "https://graphql.anilist.co/",
                    headers,
                    data = data,
                    cacheTime = cache ?: 10
                )
                val remaining = json.headers["X-RateLimit-Remaining"]?.toIntOrNull() ?: -1
                val reset = json.headers["X-RateLimit-Reset"]?.toLongOrNull()
                Logger.log("Remaining requests: $remaining")
                if (json.code == 429) {
                    AnilistRateLimit.limit(json.headers["Retry-After"]?.toIntOrNull(), reset)
                    throw Exception(AnilistRateLimit.waitMessage())
                }
                // AniList counts every response down, so a window can be served out *before*
                // spending a request on being refused: at zero remaining the next call is a 429 by
                // definition. Only acted on when the reset time came with it — guessing how long to
                // sit out a limit that may not even be reached is worse than finding out.
                if (remaining == 0 && reset != null) {
                    AnilistRateLimit.limit(null, reset)
                }

                // Maintenance, an IP block or a plain outage: pauses further queries and puts a
                // banner up, so the rest of the screen's requests stop here instead of each
                // discovering the same dead API on its own.
                AnilistApiStatus.check(json.code, json.text)?.let { outage ->
                    throw Exception(outage)
                }

                if (json.code == 403 || json.code == 400) {
                    val obj = try {
                        JSONObject(json.text)
                    } catch (_: Exception) {
                        null
                    }
                    val message = obj?.optJSONArray("errors")?.let { errors ->
                        if (errors.length() > 0) errors.getJSONObject(0)
                            .getString("message") else "Forbidden (error ${json.code})"
                    } ?: "Forbidden (error ${json.code})"

                    if (message.contains("Invalid token")) {
                        if (!show) snackString("Anilist token expired, please login again")
                    } else {
                        if (!show) snackString("Error fetching Anilist data: $message")
                    }

                    throw Exception(message)
                }

                json.parsed()
            } else null
        } catch (e: Exception) {
            // Don't show error for cancellation - it's normal when navigating or closing
            if (e is java.util.concurrent.CancellationException || e.message?.contains("Job was cancelled") == true) {
                Logger.log("Anilist Query cancelled (normal): ${e.message}")
            } else {
                if (show) snackString("Error fetching Anilist data: ${e.message}")
                Logger.log("Anilist Query Error: ${e.message}")
            }
            null
        }
    }
}

