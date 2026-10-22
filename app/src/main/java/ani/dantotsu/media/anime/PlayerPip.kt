package ani.dantotsu.media.anime

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import ani.dantotsu.R
import ani.dantotsu.logError

/**
 * The player's picture-in-picture window: the video's shape and the replay, play/pause and
 * forward buttons it carries. [player] is the player those buttons drive, or null before one exists.
 */
class PlayerPip(
    private val activity: Activity,
    private val player: () -> Player?,
) {
    /** The video's shape, for the window; set once the first frame says what it is. */
    var aspectRatio = Rational(16, 9)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val player = player() ?: return
            when (intent?.action) {
                PLAY_PAUSE -> if (player.isPlaying) player.pause() else player.play()
                REPLAY -> player.seekBack()
                FORWARD -> player.seekForward()
            }
        }
    }

    fun register() = ContextCompat.registerReceiver(
        activity,
        receiver,
        IntentFilter().apply {
            addAction(PLAY_PAUSE)
            addAction(REPLAY)
            addAction(FORWARD)
        },
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )

    fun unregister() {
        runCatching { activity.unregisterReceiver(receiver) }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun params(): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .setActions(actions())
            .build()

    /** Re-sends the buttons while in the window, so play/pause shows the right one. */
    fun refresh() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.isInPictureInPictureMode) {
            activity.setPictureInPictureParams(params())
        }
    }

    @Suppress("DEPRECATION")
    fun enter() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.enterPictureInPictureMode(params())
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                activity.enterPictureInPictureMode()
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun actions(): List<RemoteAction> {
        val playing = player()?.isPlaying == true
        return listOf(
            action(R.drawable.ic_round_replay_10_24, R.string.replay_10_seconds, REPLAY, 13),
            action(
                if (playing) R.drawable.ic_round_pause_24 else R.drawable.ic_round_play_arrow_24,
                if (playing) R.string.pause else R.string.play,
                PLAY_PAUSE,
                12,
            ),
            action(R.drawable.ic_round_forward_10_24, R.string.forward_10_seconds, FORWARD, 14),
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun action(icon: Int, title: Int, intentAction: String, requestCode: Int) = RemoteAction(
        Icon.createWithResource(activity, icon),
        activity.getString(title),
        activity.getString(title),
        PendingIntent.getBroadcast(
            activity,
            requestCode,
            Intent(intentAction).setPackage(activity.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    )

    private companion object {
        const val PLAY_PAUSE = "ani.dantotsu.action.PIP_PLAY_PAUSE"
        const val REPLAY = "ani.dantotsu.action.PIP_REPLAY"
        const val FORWARD = "ani.dantotsu.action.PIP_FORWARD"
    }
}
