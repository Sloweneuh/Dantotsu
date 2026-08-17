package ani.dantotsu.media.novel.novelreader

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ani.dantotsu.R
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a novel being read aloud alive, and puts its controls in the notification shade.
 *
 * It holds no state of its own — [NovelTts] does — and exists for two things Android will not give
 * a plain object: a process that survives the reader being closed and the screen going off, and a
 * notification the user can pause from without going back into the app. Listening to a novel is
 * something people do with the phone in a pocket, so both are the feature rather than trimmings.
 */
class NovelTtsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        private const val ACTION_TOGGLE = "ani.dantotsu.novel.tts.TOGGLE"
        private const val ACTION_NEXT = "ani.dantotsu.novel.tts.NEXT"
        private const val ACTION_PREVIOUS = "ani.dantotsu.novel.tts.PREVIOUS"
        private const val ACTION_STOP = "ani.dantotsu.novel.tts.STOP"

        fun start(context: Context) {
            val intent = Intent(context, NovelTtsService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Logger.log("Novel TTS: could not start the playback service — ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, NovelTtsService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Posted before anything else: a foreground service that has not shown its notification
        // within a few seconds is killed, and the first state emission may be a moment away.
        startForeground(NovelTts.state.value)
        scope.launch {
            NovelTts.state.collectLatest { state ->
                if (!state.active) {
                    stopSelf()
                    return@collectLatest
                }
                notify(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> NovelTts.toggle()
            ACTION_NEXT -> NovelTts.next()
            ACTION_PREVIOUS -> NovelTts.previous()
            ACTION_STOP -> {
                NovelTts.stop()
                return START_NOT_STICKY
            }
        }
        // Not sticky: restarting this with no engine, no book and no chapter list would put a dead
        // set of controls in the shade. Speech resumes by opening the reader again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForeground(state: NovelTtsState) {
        val notification = build(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_NOVEL_TTS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(Notifications.ID_NOVEL_TTS, notification)
        }
    }

    private fun notify(state: NovelTtsState) {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(Notifications.ID_NOVEL_TTS, build(state))
        }.onFailure {
            // Notifications can be denied outright; playback itself is unaffected.
            Logger.log("Novel TTS: could not update the playback notification — ${it.message}")
        }
    }

    private fun build(state: NovelTtsState): android.app.Notification {
        val playing = state.playing
        val title = state.chapterTitle.ifBlank { getString(R.string.novel_tts) }
        val text = when {
            state.preparing -> getString(R.string.novel_tts_preparing)
            state.novelTitle.isNotBlank() -> state.novelTitle
            else -> state.sentence
        }

        return NotificationCompat.Builder(this, Notifications.CHANNEL_NOVEL_TTS)
            .setSmallIcon(R.drawable.ic_round_headphones_24)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (state.total > 0) "${state.index + 1}/${state.total}" else null)
            .setContentIntent(readerIntent())
            .setOngoing(playing)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setProgress(state.total.coerceAtLeast(1), state.index + 1, state.preparing)
            .addAction(
                R.drawable.ic_round_skip_previous_24,
                getString(R.string.previous),
                action(ACTION_PREVIOUS),
            )
            .addAction(
                if (playing) R.drawable.ic_round_pause_24 else R.drawable.ic_round_play_arrow_24,
                getString(if (playing) R.string.pause else R.string.play),
                action(ACTION_TOGGLE),
            )
            .addAction(
                R.drawable.ic_round_skip_next_24,
                getString(R.string.next),
                action(ACTION_NEXT),
            )
            .addAction(
                R.drawable.ic_round_close_24,
                getString(R.string.stop),
                action(ACTION_STOP),
            )
            .setDeleteIntent(action(ACTION_STOP))
            .build()
    }

    private fun action(name: String): PendingIntent {
        val intent = Intent(this, NovelTtsService::class.java).setAction(name)
        return PendingIntent.getService(
            this,
            name.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Tapping the notification goes back to what is being read.
     *
     * The reader takes the book as the intent's data, and the chapter list it pages through lives in
     * [ani.dantotsu.parsers.novel.lnreader.LNReaderSession] — a singleton, so this only reopens
     * where it left off while the process is the one that started playing. That is the same process
     * this service is keeping alive, so in practice it always is.
     */
    private fun readerIntent(): PendingIntent? = runCatching {
        // Already a provider URI — the reader is never handed a bare file:// path, since passing
        // one to another component throws.
        val uri = NovelTts.currentBookUri() ?: return null
        val intent = Intent(this, NovelReaderActivity::class.java)
            .setData(uri)
            .putExtra(NovelReaderActivity.EXTRA_LN_SESSION, true)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }.getOrNull()
}
