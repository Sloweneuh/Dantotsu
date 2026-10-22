package ani.dantotsu.media.anime

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ani.dantotsu.R
import ani.dantotsu.util.Logger

/**
 * Keeps an episode playing once the player is out of sight (PlayerBackgroundPlay): the screen off,
 * or the user gone to another app. It owns nothing: the player and its media session stay the
 * player activity's, and this only hosts that session, which is what keeps the process in the
 * foreground and gives the notification its controls. Media3 builds and updates the notification
 * from the session.
 *
 * Started by the player as it goes out of sight while playing, and stopped as soon as it is back,
 * so the notification is there only while it is needed.
 */
@UnstableApi
class PlayerBackgroundService : MediaSessionService() {

    companion object {
        /** The session being hosted, handed over by the player. */
        private var session: MediaSession? = null
        @SuppressLint("StaticFieldLeak") // Cleared in onDestroy.
        private var instance: PlayerBackgroundService? = null

        /** Stop was asked for before the service got going; it stops once it has. */
        private var stopRequested = false

        val isRunning: Boolean get() = instance != null

        /**
         * Hosts [session] in the foreground. Must be called while the player is still visible —
         * from onPause — as Android only lets a foreground service start from the foreground.
         */
        fun start(context: Context, session: MediaSession) {
            this.session = session
            stopRequested = false
            instance?.let {
                it.host(session)
                return
            }
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, PlayerBackgroundService::class.java),
                )
            }.onFailure { Logger.log("Player: could not start background playback — ${it.message}") }
        }

        fun stop(context: Context) {
            val running = instance
            if (running == null) {
                // Started but not yet created: it still owes the system a foreground
                // notification, so it posts one and then stops (see onStartCommand).
                if (session != null) stopRequested = true
                return
            }
            session = null
            running.release()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.player_background_notification)
                .build()
                .apply { setSmallIcon(R.drawable.ic_round_headphones_24) },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        session?.let { runCatching { host(it) } }
        if (stopRequested || session == null) {
            // The player was back before this got going.
            stopRequested = false
            session = null
            release()
        }
        return result
    }

    /** Whether a notification has gone up in the foreground, as a started service must. */
    private var foregrounded = false

    /** Adds [session] and posts its notification in the foreground straight away. */
    private fun host(session: MediaSession) {
        sessions.filter { it !== session }.forEach { removeSession(it) }
        if (session !in sessions) addSession(session)
        // A service started with startForegroundService must be in the foreground within seconds,
        // and Media3 only asks for that while the player is playing; asked for here regardless.
        onUpdateNotification(session, true)
        foregrounded = true
    }

    private fun release() {
        // Leaving without ever having been in the foreground is a crash for a service started
        // with startForegroundService, so a bare notification goes up first, to be taken down
        // with it.
        if (!foregrounded) runCatching {
            NotificationManagerCompat.from(this).createNotificationChannel(
                NotificationChannelCompat.Builder(
                    DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_LOW,
                ).setName(getString(R.string.player_background_notification)).build(),
            )
            startForeground(
                DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                NotificationCompat.Builder(this, DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_round_headphones_24)
                    .setContentTitle(getString(R.string.player_background_notification))
                    .build(),
            )
            foregrounded = true
        }
        sessions.forEach { removeSession(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recents: the player activity is gone with the task, so the audio stops. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.pause()
        release()
    }

    override fun onDestroy() {
        sessions.forEach { removeSession(it) }
        instance = null
        super.onDestroy()
    }
}
