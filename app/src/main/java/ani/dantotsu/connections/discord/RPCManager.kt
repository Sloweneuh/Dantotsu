package ani.dantotsu.connections.discord

/**
 * Headless Sessions API implementation ported and adapted from:
 * https://github.com/brahmkshatriya/echo-discord
 */

import android.content.Context
import ani.dantotsu.connections.discord.models.DiscordActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import android.content.Intent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Headless Rich Presence facade.
 *
 * Uses the Discord Headless Sessions API (pure HTTP, no foreground service).
 * Requires an OAuth2 Bearer token with `activities.write` scope (managed by [TokenManager]).
 */
object RPCManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Lazily created HeadlessRPC instance; reset on user logout. */
    private var headlessRpc: HeadlessRPC? = null

    /** Debounce job — only the last setPresence within 500ms fires. */
    private var debounceJob: Job? = null
    private const val DEBOUNCE_MS = 500L

    /** Heartbeat job to keep headless sessions alive (e.g. during 20+ min watch). */
    private var heartbeatJob: Job? = null
    private const val HEARTBEAT_INTERVAL_MS = 9 * 60 * 1000L

    /** Auto-clear job \u2014 clears the RPC if the video is left paused for too long. */
    private var autoClearJob: Job? = null
    private const val AUTO_CLEAR_INTERVAL_MS = 1 * 60 * 1000L

    /** Tracks whether the DiscordService has already been started to avoid redundant calls */
    private var serviceStarted = false

    /**
     * The kind of presence on show \u2014 which also says which screen owns it \u2014 or null when none
     * is. Screens hand off to one another (media page \u2192 player \u2192 back), and the outgoing screen's
     * clear can land after the incoming one's set; [clearPresence] ignores a clear for any other
     * kind, so that late clear can't wipe the new presence.
     */
    @Volatile
    private var owner: RPC.Kind? = null

    /**
     * The opt-in browsing presence of the media page still on screen, kept while something else
     * is showing so it can come back once that ends (an OP/ED stops, the player closes onto it).
     * Dropped when the page itself clears it.
     */
    @Volatile
    private var browsing: Pair<Context, RPC.Companion.RPCData>? = null


    // ΓöÇΓöÇΓöÇ Public API ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

    /**
     * Whether a screen may publish presence at all: logged in to Discord, presence switched on,
     * and neither incognito nor offline.
     */
    fun isAllowed(context: Context): Boolean =
        Discord.token != null &&
            PrefManager.getVal<Boolean>(PrefName.rpcEnabled) &&
            !PrefManager.getVal<Boolean>(PrefName.Incognito) &&
            !PrefManager.getVal<Boolean>(PrefName.OfflineMode) &&
            ani.dantotsu.isOnline(context)

    /**
     * Set / update Discord Rich Presence.
     *
     * @param context Android context (used to locate the token cache directory)
     * @param data    The presence data built by the calling screen
     */
    fun setPresence(context: Context, data: RPC.Companion.RPCData) {
        val current = owner
        when (data.kind) {
            // Remembered even when it can't show yet, so it returns when the foreground ends.
            RPC.Kind.BROWSING -> {
                browsing = context.applicationContext to data
                if (current != null && current != RPC.Kind.BROWSING) return
            }
            // A theme song never displaces what is being watched or read.
            RPC.Kind.MUSIC ->
                if (current == RPC.Kind.ANIME || current == RPC.Kind.MANGA || current == RPC.Kind.NOVEL) return
            else -> Unit
        }
        owner = data.kind
        if (!serviceStarted) {
            runCatching { 
                context.startService(Intent(context, DiscordService::class.java))
                serviceStarted = true
            }.onFailure { e ->
                Logger.log("RPCManager: Failed to start DiscordService (missing manifest entry?): ${e.message}")
            }
        }
        // Cancel any pending auto-clear since we're updating presence

        // Debounce: only the last call within 500ms actually fires
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            Logger.log("RPCManager: Attempting to use Headless RPC...")
            val activity = buildDiscordActivity(data)
            val isPaused = data.isPaused

            runCatching {
                ensureHeadlessRpc(context)?.newActivity(activity)
                Logger.log("RPCManager: Headless RPC update succeeded.")
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Logger.log("RPCManager: HeadlessRPC failed \u2013 ${e.message}")
            }

            // Schedule heartbeat or auto-clear based on playback state
            heartbeatJob?.cancel()
            autoClearJob?.cancel()

            if (isPaused) {
                // Left paused for [AUTO_CLEAR_INTERVAL_MS]: stop advertising it.
                autoClearJob = scope.launch {
                    delay(AUTO_CLEAR_INTERVAL_MS)
                    Logger.log("RPCManager: Auto-clearing Headless RPC due to pause timeout.")
                    clearPresence(context, data.kind)
                }
            } else {
                // If playing continuously, schedule heartbeat
                heartbeatJob = scope.launch {
                    while (true) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        Logger.log("RPCManager: Sending heartbeat for Headless RPC...")
                        runCatching {
                            ensureHeadlessRpc(context)?.newActivity(activity)
                        }.onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Logger.log("RPCManager: HeadlessRPC heartbeat failed \u2013 ${e.message}")
                        }
                    }
                }
            }
        }
    }

    /**
     * Clear / stop Discord Rich Presence — only when [kind] is what is showing, see [owner]. When
     * a page's browsing presence is still waiting underneath, that comes back instead.
     */
    fun clearPresence(context: Context, kind: RPC.Kind) {
        if (kind == RPC.Kind.BROWSING) browsing = null
        if (owner != kind) {
            Logger.log("RPCManager: Ignoring clear for $kind — showing $owner")
            return
        }
        owner = null
        browsing?.let { (browsingContext, data) ->
            Logger.log("RPCManager: $kind ended, back to browsing presence")
            setPresence(browsingContext, data)
            return
        }
        Logger.log("RPCManager: Clearing presence...")
        debounceJob?.cancel()
        heartbeatJob?.cancel()
        autoClearJob?.cancel()

        // Delay stopping the service. If the app is being abruptly killed (swiped from Recents),
        // onTaskRemoved will fire before 2 seconds elapse. If it's a normal exit (user pressed Back),
        // the service will gracefully stop after 2 seconds, preventing Android from leaving zombie service records.
        scope.launch {
            delay(2000)
            // A presence set in the meantime (a screen hand-off) still needs the service's
            // swipe-away cleanup.
            if (owner != null) return@launch
            runCatching {
                context.stopService(Intent(context, DiscordService::class.java))
                serviceStarted = false
            }
        }

        scope.launch {
            val rpc = headlessRpc
            if (rpc == null) {
                Logger.log("RPCManager: Error - headlessRpc is null, cannot clear!")
            } else {
                rpc.runCatching { clear() }
                    .onSuccess { Logger.log("RPCManager: headlessRpc.clear() finished normally") }
                    .onFailure { Logger.log("RPCManager: headlessRpc.clear() threw exception - ${it.message}") }
            }
        }
    }

    /**
     * Clear presence synchronously when the app is swiped from Recents.
     * We use a dedicated Thread and strictly block process termination 
     * for up to 2 seconds to guarantee the HTTP request leaves the device.
     * This uses a raw OkHttp request to completely bypass Coroutine Mutexes
     * and suspend functions, which can easily deadlock during process death.
     */
    fun clearPresenceOnKill(context: Context) {
        owner = null
        browsing = null
        debounceJob?.cancel()
        heartbeatJob?.cancel()
        autoClearJob?.cancel()

        val rpc = headlessRpc
        var accessToken = rpc?.tokenManager?.accessToken
        var sessionToken = rpc?.activityToken

        if (accessToken == null || sessionToken == null) {
            // App was resurrected specifically for onTaskRemoved! Reconstruct from cache.
            val discordDir = File(context.filesDir, "discord")
            accessToken = runCatching { discordDir.resolve("discord_access.txt").readText() }.getOrNull()
            sessionToken = PrefManager.getNullableCustomVal("discord_activity_token", null, String::class.java)
        }

        if (accessToken.isNullOrEmpty() || sessionToken.isNullOrEmpty()) {
            Logger.log("RPCManager: Missing tokens for emergency kill cleanup. Aborting.")
            return
        }

        val thread = Thread {
            try {
                Logger.log("RPCManager: App kill emergency raw cleanup starting...")
                val client = DiscordHttpClient.instance

                // 2. Delete session
                val deletePayload = "{\"token\":\"$sessionToken\"}"
                val delReq = okhttp3.Request.Builder()
                    .url("https://discord.com/api/v10/users/@me/headless-sessions/delete")
                    .header("Authorization", "Bearer $accessToken")
                    .post(deletePayload.toRequestBody("application/json".toMediaType()))
                    .build()
                runCatching { 
                    client.newCall(delReq).execute().use { response ->
                        if (response.isSuccessful) {
                            Logger.log("RPCManager: Emergency deleteSession succeeded")
                        } else {
                            Logger.log("RPCManager: Emergency deleteSession failed: ${response.code}")
                        }
                    } 
                }

                Logger.log("RPCManager: App kill emergency raw cleanup successful")
            } catch (e: Exception) {
                Logger.log("RPCManager: App kill emergency raw cleanup failed - ${e.message}")
            }
        }
        thread.start()
        
        // Block the main thread for max 2 seconds. 
        // This physically prevents Android from terminating the process 
        // until the OkHttp request finishes or the 2 seconds elapse.
        try {
            thread.join(2000) 
        } catch (e: InterruptedException) {
            // Ignore
        }
    }

    @Volatile
    private var staleCleanupJob: Job? = null

    /**
     * Deletes a session a previous process left showing.
     *
     * The session token is saved as soon as Discord hands it over, and a process that dies without
     * warning — replaced by an update, killed by the system, crashed — never gets to delete it: the
     * clears on screen exit are cut off mid-request, and [clearPresenceOnKill] only runs for a swipe
     * from Recents. Discord then goes on showing that last status. A process that has only just
     * started hasn't published anything, so any token still saved at that point belongs to one of
     * those, and goes.
     *
     * Started from `App.onCreate`, and kept alive by the update and boot receivers so it runs even
     * when nobody opens the app. Once per process; later callers get the same job.
     */
    fun cleanupStaleSession(context: Context): Job = synchronized(this) {
        staleCleanupJob ?: scope.launch {
            val stale = PrefManager.getNullableCustomVal("discord_activity_token", null, String::class.java)
            if (stale.isNullOrBlank()) return@launch
            // The delete forgets the token whatever the outcome, so it must not be spent while
            // offline — just after boot, typically — or the session would never be deleted.
            // Kept for the next try instead, which may well be this same process (booted offline,
            // opened later), so this run doesn't count as the once-per-process one.
            if (!ani.dantotsu.isOnline(context)) {
                synchronized(this@RPCManager) { staleCleanupJob = null }
                return@launch
            }
            // The Discord login is restored in the background; without it there is no bearer token.
            ani.dantotsu.connections.TrackerSessions.await()
            if (owner != null) {
                Logger.log("RPCManager: presence already set this run, leaving its session alone")
                return@launch
            }
            val rpc = ensureHeadlessRpc(context.applicationContext) ?: run {
                // Logged out since: the session went with the login.
                PrefManager.removeCustomVal("discord_activity_token")
                return@launch
            }
            Logger.log("RPCManager: deleting a session left by a previous process")
            if (rpc.activityToken == null) rpc.activityToken = stale
            runCatching { rpc.clear() }
                .onFailure { Logger.log("RPCManager: stale session cleanup failed — ${it.message}") }
        }.also { staleCleanupJob = it }
    }

    /**
     * Call this when the user logs out of Discord to release all resources.
     */
    fun reset() {
        owner = null
        browsing = null
        debounceJob?.cancel()
        heartbeatJob?.cancel()
        autoClearJob?.cancel()
        serviceStarted = false
        headlessRpc?.stop()
        headlessRpc = null
    }

    /**
     * Returns the token expiry timestamp in millis, or 0 if unknown/not logged in.
     */
    fun getTokenExpiresAt(): Long {
        return headlessRpc?.tokenManager?.getTokenExpiresAt() ?: 0L
    }

    // ΓöÇΓöÇΓöÇ Private helpers ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

    private fun ensureHeadlessRpc(context: Context): HeadlessRPC? {
        val token = Discord.token ?: return null
        if (headlessRpc == null || headlessRpc?.authToken != token) {
            headlessRpc?.stop()
            headlessRpc = HeadlessRPC(
                authToken = token,
                filesDir = File(context.filesDir, "discord"),
            )
        }
        return headlessRpc
    }

    /**
     * Convert [RPC.Companion.RPCData] to a [DiscordActivity] for the headless API.
     *
     * Discord resolves external image URLs server-side, so we pass URLs directly
     * (no need to proxy through /external-assets).
     */
    private suspend fun buildDiscordActivity(data: RPC.Companion.RPCData): DiscordActivity {
        val source = data.source
        val buttons = mutableListOf<DiscordActivity.Button>()
        // The media's page on the site it came from — the same in either mode.
        if (PresenceSettings.showMediaButton) {
            source?.url?.takeIf { it.isValidUrl() }?.let { url ->
                buttons.add(DiscordActivity.Button(label = source.buttonLabel, url = url))
            }
        }
        // The user's own profile on that site, when logged in there.
        if (PresenceSettings.showProfile) {
            source?.let { PresenceSources.profileUrl(it.site) }?.takeIf { it.isValidUrl() }?.let { url ->
                buttons.add(DiscordActivity.Button(label = "View Profile", url = url))
            }
        }

        // Media mode shows the site's icon; Dantotsu mode, and media from a site with no icon of
        // its own (an extension missing from every added repo), Dantotsu's.
        val (smallIconUrl, smallIconText) = when {
            !PresenceSettings.showSiteIcon -> null to null
            PresenceSettings.mode == PresenceSettings.MODE_MEDIA && source != null ->
                PresenceSources.icon(source) ?: (Discord.small_Image to "Dantotsu")
            else -> Discord.small_Image to "Dantotsu"
        }

        return DiscordActivity(
            applicationId = data.applicationId,
            name = data.activityName?.takeIf { it.isNotBlank() } ?: "Dantotsu",
            platform = "android", // Required by Discord
            type = data.type?.ordinal,
            statusDisplayType = 0,
            details = data.details,
            state = data.state,
                assets = DiscordActivity.Assets(
                    largeImage = data.largeImage?.url?.takeIf { it.isValidUrl() },
                    largeText = data.largeImage?.label?.takeIf { data.largeImage.url.isValidUrl() },
                    largeUrl = null,
                    smallImage = data.smallImage?.url ?: smallIconUrl,
                    smallText = data.smallImage?.label ?: smallIconText,
                    smallUrl = null,
                ),
            timestamps = if (data.startTimestamp != null)
                DiscordActivity.Timestamps(
                    start = data.startTimestamp,
                    end = data.stopTimestamp
                )
            else null,
            buttons = buttons.take(2).takeIf { it.isNotEmpty() },
        )
    }

    /** Validate that a URL is a proper http/https link. */
    private fun String?.isValidUrl(): Boolean {
        return this != null && isNotEmpty() &&
                (startsWith("http://") || startsWith("https://"))
    }


}
