package ani.dantotsu.connections

import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.kitsu.Kitsu
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.simkl.Simkl
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Restores every connected account's saved session, once per process.
 *
 * Each tracker keeps its token in a plain in-memory field that starts out null, and the only thing
 * that ever filled those fields was the home screen's load — so a session that never passed through
 * [ani.dantotsu.MainActivity] had every tracker but AniList still looking signed out. A notification
 * tap, a media deep link, a widget, an app shortcut, or the system simply restoring the process
 * straight back into the reader or the player all arrive that way.
 *
 * What that cost was silent. Every mirror write begins by checking its own token and giving up when
 * there isn't one — `MALQueries.editList` logs and returns, `KitsuSync.isEnabled` reports the
 * tracker as off, and so on — so finishing an episode or a chapter from one of those entry points
 * updated AniList, showed the usual confirmation, and quietly mirrored nothing. It looked like the
 * secondary trackers were dropping updates at random; what actually varied was how the app had been
 * opened. The list-comparison screen was reached through the home screen by definition, which is
 * why comparing always worked and was left to pick up everything the mirrors had missed.
 *
 * AniList already had exactly this fixed, by [Anilist.restoreSession] in `App.onCreate` — it can be
 * restored synchronously because its token doesn't expire. The rest can't: an expired token has to
 * be refreshed over the network, so they are restored here in the background and awaited by
 * whoever needs them.
 *
 * Restoring in one place also means the refresh happens once. Two concurrent restores of the same
 * expired token would both post the refresh token, and a service that rotates it on use invalidates
 * the second exchange.
 */
object TrackerSessions {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Started on first touch and never repeated: [start] from `App.onCreate` gets it going early
     * enough that nothing usually has to wait, and [await] covers whatever gets there first.
     */
    private val restore: Deferred<Unit> by lazy {
        scope.async {
            // Independent of one another, and each may go to the network to refresh, so together.
            listOf(
                async { runCatching { MAL.getSavedToken() } },
                async { runCatching { Kitsu.getSavedToken() } },
                async { runCatching { Simkl.getSavedToken() } },
                async { runCatching { MangaBaka.getSavedToken() } },
                async { runCatching { MangaUpdates.getSavedToken() } },
                async { runCatching { Discord.getSavedToken() } },
            ).awaitAll()
            // Prefs-only and already done in `App.onCreate`; repeated here so that awaiting this is
            // the single answer to "are the accounts loaded yet".
            runCatching { Anilist.getSavedToken() }
            Logger.log(
                "TrackerSessions restored — anilist=${Anilist.token != null} mal=${MAL.token != null} " +
                    "kitsu=${Kitsu.token != null} simkl=${Simkl.token != null} " +
                    "mangabaka=${MangaBaka.token != null}"
            )
        }
    }

    /** Kicks the restore off without waiting for it. */
    fun start() {
        restore
    }

    /**
     * Suspends until every saved session has been restored, or found to be absent. Call this before
     * anything that writes to a tracker, so a push started from a cold entry point waits for the
     * accounts instead of concluding they aren't there.
     *
     * Bounded, because callers sit in front of work that matters more than this does: the AniList
     * update in [ani.dantotsu.connections.updateProgress] runs after the wait, and a token endpoint
     * that hangs must not be able to hold up the one write the user is actually watching for. Past
     * the bound the mirrors go on with whatever sessions are up, which is no worse than what they
     * did before any of this — and the restore is still running, so the next push gets them.
     */
    suspend fun await() {
        runCatching { withTimeoutOrNull(RESTORE_TIMEOUT_MS) { restore.await() } }
    }

    private const val RESTORE_TIMEOUT_MS = 15_000L
}
