package ani.dantotsu.media.anime

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import ani.dantotsu.R
import ani.dantotsu.defaultHeaders
import ani.dantotsu.parsers.Video
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.util.concurrent.Executors

/**
 * Casting from the player: the Google Cast session, which takes the episode to the TV from where
 * the phone was and brings it back the same way, or — with the internal caster off — handing the
 * stream to Web Video Caster. Reaches the player through [Host], which the activity is.
 */
@UnstableApi
class PlayerCast(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
    private val host: Host,
) : SessionAvailabilityListener {

    interface Host {
        /** Only read once [isInitialized]: it is the activity's lateinit player. */
        val exoPlayer: ExoPlayer
        val isInitialized: Boolean
        /** The episode's media item, once the player has one. */
        val mediaItem: MediaItem?
        /** [item] with the episode's title and artwork, for the TV and the cast notification. */
        fun withMetadata(item: MediaItem): MediaItem
        fun savePosition(position: Long)
        /** Playback moved between the phone and the TV, or started or stopped on the TV. */
        fun onActivePlayerChanged()
        fun onCastPlaying()
        fun onCastPlayWhenReadyChanged(playWhenReady: Boolean)
    }

    var player: CastPlayer? = null
        private set
    private var apiAvailable = false

    /** The episode is playing on a cast device rather than on the phone. */
    val isCasting: Boolean
        get() = player != null && playerView.player === player

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
            host.onCastPlayWhenReadyChanged(playWhenReady)

        // The phone's own player reports nothing while casting, so presence follows the cast
        // session's playback instead.
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) host.onCastPlaying()
            host.onActivePlayerChanged()
        }
    }

    fun setUp() {
        apiAvailable = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(activity) == ConnectionResult.SUCCESS
        // The shared CastContext is created asynchronously. Reading the task's result straight
        // away threw "not yet complete" the first time in every process, which left casting
        // without a player: the button connected to the TV and nothing ever played on it.
        if (apiAvailable) try {
            CastContext.getSharedInstance(activity, Executors.newSingleThreadExecutor())
                .addOnSuccessListener(activity) { context -> setUpPlayer(context) }
                .addOnFailureListener(activity) { apiAvailable = false }
        } catch (e: Exception) {
            apiAvailable = false
        }
    }

    /** The cast button: Google Cast's route picker, or [external] with the internal caster off. */
    fun setUpButton(button: CustomCastButton, external: () -> Unit) {
        if (PrefManager.getVal(PrefName.UseInternalCast)) {
            try {
                CastButtonFactory.setUpMediaRouteButton(activity, button)
                button.dialogFactory = CustomCastThemeFactory()
            } catch (e: Exception) {
                apiAvailable = false
            }
        } else {
            button.setCastCallback(external)
        }
    }

    private fun setUpPlayer(context: CastContext) {
        if (activity.isDestroyed || player != null) return
        player = CastPlayer(context).apply {
            setSessionAvailabilityListener(this@PlayerCast)
            // Once, here: it used to be added on every session start, so each reconnect stacked
            // another copy of it.
            addListener(listener)
        }
        // A session that was already up (casting from the previous episode) carries on with
        // this one.
        if (player?.isCastSessionAvailable == true) onCastSessionAvailable()
    }

    /**
     * Sends the current episode to the cast device, from where the phone was. Called when a
     * session starts, and again for each new episode or server while one is up.
     */
    fun startCasting() {
        val cast = player ?: return
        if (!apiAvailable) {
            snackString(activity.getString(R.string.cast_unavailable))
            return
        }
        if (!host.isInitialized) return
        val item = host.mediaItem ?: return
        // A cast device fetches the stream itself: a file on this phone is out of its reach.
        val scheme = item.localConfiguration?.uri?.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            snackString(activity.getString(R.string.cast_local_unsupported))
            return
        }
        val exoPlayer = host.exoPlayer
        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
        cast.setMediaItem(host.withMetadata(item), position)
        cast.playWhenReady = true
        cast.prepare()
        playerView.player = cast
        // Stopped rather than paused, so the phone stops buffering what it isn't showing;
        // it keeps its media source and only needs preparing again on the way back.
        exoPlayer.stop()
        if (position > 5000) host.savePosition(position)
        host.onActivePlayerChanged()
    }

    /**
     * Back on the phone, from where the TV got to. The phone's player still has the episode's
     * media source — with its separate audio tracks and subtitles, which handing it the bare
     * media item again (as this used to) dropped — so it is only prepared again and seeked.
     */
    private fun stopCasting() {
        val position = player?.takeIf { playerView.player === it }?.currentPosition ?: -1L
        if (!host.isInitialized) return
        val exoPlayer = host.exoPlayer
        playerView.player = exoPlayer
        exoPlayer.prepare()
        if (position > 0) {
            exoPlayer.seekTo(position)
            host.savePosition(position)
        }
        // Back on the phone: report its player, not the stopped cast session.
        host.onActivePlayerChanged()
    }

    override fun onCastSessionAvailable() {
        if (apiAvailable && !activity.isDestroyed) startCasting()
    }

    override fun onCastSessionUnavailable() = stopCasting()

    /**
     * The cast session belongs to the app, not the player screen, so it carries on; only this
     * screen's hold on it goes (the availability listener would otherwise leak the activity).
     */
    fun release() {
        player?.setSessionAvailabilityListener(null)
        player?.release()
        player = null
    }

    companion object {
        /** Hands the stream to Web Video Caster, or to its store page when it isn't installed. */
        fun openInWebVideoCaster(
            activity: AppCompatActivity,
            video: Video,
            subtitleUrl: String?,
            title: String,
            poster: String?,
        ) {
            val shareVideo = Intent(Intent.ACTION_VIEW)
            shareVideo.setDataAndType(video.file.url.toUri(), "video/*")
            shareVideo.setPackage("com.instantbits.cast.webvideo")
            subtitleUrl?.let { shareVideo.putExtra("subtitle", it) }
            shareVideo.putExtra("title", title)
            shareVideo.putExtra("poster", poster)
            val headers = Bundle()
            defaultHeaders.forEach { headers.putString(it.key, it.value) }
            video.file.headers?.forEach { headers.putString(it.key, it.value) }
            shareVideo.putExtra("android.media.intent.extra.HTTP_HEADERS", headers)
            shareVideo.putExtra("secure_uri", true)
            try {
                activity.startActivity(shareVideo)
            } catch (ex: ActivityNotFoundException) {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, "market://details?id=com.instantbits.cast.webvideo".toUri())
                )
            }
        }
    }
}
