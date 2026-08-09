package ani.dantotsu.connections.anilist

import ani.dantotsu.R
import ani.dantotsu.currActivity
import ani.dantotsu.getAppString
import ani.dantotsu.util.AppNotices
import ani.dantotsu.util.Logger
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/**
 * Whether graphql.anilist.co is currently refusing traffic, and how long the app stays off it.
 *
 * AniList asks clients to expect this (https://docs.anilist.co/guide/considerations): during severe
 * outages it lowers the rate limits or suspends the API outright, and it can block an IP that keeps
 * hammering it. Three refusals are worth telling apart, because the right response to each differs:
 *
 *  - [Kind.DISABLED] — the documented maintenance state: `403` with a GraphQL error explaining that
 *    the API has been turned off. Retrying achieves nothing until AniList turns it back on.
 *  - [Kind.BLOCKED] — a manual IP block. Those requests never reach the origin, so the body is the
 *    edge's own page rather than GraphQL JSON. Continuing to send is what earns a longer block.
 *  - [Kind.DOWN] — an ordinary outage: 5xx, or a 2xx that isn't JSON (a proxy or captive portal
 *    page). Usually short-lived, so the pause is short too.
 *
 * The point of holding this state at all is that one failure previously taught the app nothing. A
 * home screen alone fires a dozen queries; each one hit a dead API, waited for its own timeout and
 * raised its own error, so a maintenance window looked like the app being broken a dozen times over
 * and kept the traffic coming from precisely the clients AniList was trying to shed. Here the first
 * refusal stops the rest of them, and the pause lengthens if the API is still refusing when it ends.
 *
 * State is per-process on purpose: an outage that outlives a restart is re-detected by the first
 * query of the next launch, and one that doesn't shouldn't keep the app off a working API.
 */
object AnilistApiStatus {

    enum class Kind { DISABLED, BLOCKED, DOWN }

    /** How long requests pause the first time each refusal is seen. */
    private val BASE_PAUSE_MS = mapOf(
        Kind.DISABLED to 10 * 60 * 1000L,
        Kind.BLOCKED to 30 * 60 * 1000L,
        Kind.DOWN to 60 * 1000L,
    )

    private const val MAX_PAUSE_MS = 30 * 60 * 1000L

    /**
     * Phrases AniList uses when it is the API itself that has been turned off, rather than
     * something wrong with this particular request. Matched case-insensitively against the first
     * GraphQL error message.
     */
    private val DISABLED_MARKERS = listOf(
        "temporarily disabled",
        "temporarily suspended",
        "stability issues",
        "maintenance",
        "unavailable",
    )

    /** A 403 about *this client* — an expired token — is not an outage and must not pause anything. */
    private val AUTH_MARKERS = listOf("token", "authoriz", "authenticat", "unauthorized")

    /** What the API is doing, or null when it last answered normally. */
    @Volatile
    var kind: Kind? = null
        private set

    /** AniList's own wording, when it sent any — always better than anything written here. */
    @Volatile
    var serverMessage: String? = null
        private set

    /** When requests may resume, as epoch millis. Doubles as the identity of an outage window. */
    @Volatile
    var pausedUntil: Long = 0L
        private set

    /** Consecutive windows that ended in the same refusal, backing the pause off as they mount. */
    @Volatile
    private var strikes = 0

    fun isPaused(): Boolean = kind != null && System.currentTimeMillis() < pausedUntil

    fun remainingSeconds(): Long =
        ((pausedUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

    /** Banner headline: what the user is looking at. */
    fun title(): String = getAppString(
        when (kind) {
            Kind.DISABLED -> R.string.anilist_api_unavailable
            Kind.BLOCKED -> R.string.anilist_api_blocked
            else -> R.string.anilist_not_responding
        }
    )

    /** Banner body: AniList's explanation when it gave one, ours when it didn't. */
    fun detail(): String = serverMessage ?: getAppString(
        when (kind) {
            Kind.DISABLED -> R.string.anilist_api_unavailable_info
            Kind.BLOCKED -> R.string.anilist_api_blocked_info
            else -> R.string.anilist_down
        }
    )

    /** What a query refused by the pause reports to its caller. */
    fun pausedMessage(): String = "${title()} · " +
            String.format(Locale.US, getAppString(R.string.anilist_api_retry_in), remainingSeconds())

    /**
     * Classifies a response, and starts or ends a pause accordingly.
     *
     * @return the message to fail the query with when AniList is refusing traffic, or null when the
     *   response is this query's own business — a healthy body, or an error (a bad query, an expired
     *   token) that says nothing about the API's availability and so must be left to the caller.
     */
    fun check(code: Int, body: String): String? {
        val isJson = body.trimStart().startsWith("{")
        val outage = when {
            // Handled by the caller's own Retry-After bookkeeping; not an outage.
            code == 429 -> null
            // Documented maintenance response. Only an auth failure shares this status code.
            code == 403 && isJson -> firstError(body).let { message ->
                if (message != null && AUTH_MARKERS.any { message.contains(it, true) }) null
                else Kind.DISABLED to message
            }
            // Never reached the origin: the edge answered with a block page of its own.
            code == 403 -> Kind.BLOCKED to null
            // 400 is normally a malformed query, so only AniList's own wording makes it an outage.
            code == 400 && isJson -> firstError(body)?.takeIf { message ->
                DISABLED_MARKERS.any { message.contains(it, true) }
            }?.let { Kind.DISABLED to it }

            code !in 200..299 || !isJson -> Kind.DOWN to null
            else -> null
        }

        if (outage == null) {
            if (code in 200..299 && isJson) clear(resetStrikes = true)
            return null
        }
        return begin(outage.first, outage.second)
    }

    /**
     * Lets the user overrule the pause, for the case the backoff can't judge: AniList came back
     * before the window ended. The strike count survives so that a retry into a still-dead API
     * resumes the backoff where it left off instead of restarting at a minute.
     */
    fun retryNow() {
        Logger.log("AnilistApiStatus: user asked to retry before the pause ended")
        clear(resetStrikes = false)
    }

    fun clear(resetStrikes: Boolean = true) {
        if (kind == null && strikes == 0) return
        kind = null
        serverMessage = null
        pausedUntil = 0L
        if (resetStrikes) strikes = 0
    }

    private fun begin(newKind: Kind, message: String?): String {
        // A different refusal is a different problem; it backs off from its own baseline.
        if (newKind != kind) strikes = 0
        strikes = (strikes + 1).coerceAtMost(8)
        val base = BASE_PAUSE_MS.getValue(newKind)
        kind = newKind
        serverMessage = message?.takeIf { it.isNotBlank() }
        pausedUntil = System.currentTimeMillis() +
                (base shl (strikes - 1)).coerceIn(base, MAX_PAUSE_MS)
        Logger.log(
            "AnilistApiStatus: $newKind (strike $strikes), pausing queries for " +
                    "${remainingSeconds()}s — ${serverMessage ?: "no message"}"
        )
        // Raised here rather than left to the next screen change: an outage that started while the
        // user was sitting on a screen otherwise shows as that screen quietly failing to load.
        MainScope().launch {
            currActivity()?.let { runCatching { AppNotices.showPending(it) } }
        }
        return detail()
    }

    /** The first GraphQL error message in a response body, which is where AniList puts the reason. */
    private fun firstError(body: String): String? = runCatching {
        JSONObject(body).optJSONArray("errors")
            ?.takeIf { it.length() > 0 }
            ?.getJSONObject(0)
            ?.optString("message")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
