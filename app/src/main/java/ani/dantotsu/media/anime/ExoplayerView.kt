package ani.dantotsu.media.anime

import android.animation.ObjectAnimator
import ani.dantotsu.media.history.WatchHistory
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Icon
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Animatable
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.AudioManager.AUDIOFOCUS_GAIN
import android.media.AudioManager.AUDIOFOCUS_LOSS
import android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
import android.media.AudioManager.STREAM_MUSIC
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings.System
import android.util.AttributeSet
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_B
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_N
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.math.MathUtils.clamp
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.C.TRACK_TYPE_VIDEO
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DEPRESSED
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.mediarouter.app.MediaRouteButton
import ani.dantotsu.GesturesListener
import ani.dantotsu.NoPaddingArrayAdapter
import ani.dantotsu.R
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.connections.handoff.HandoffBottomSheet
import ani.dantotsu.connections.handoff.HandoffManager
import ani.dantotsu.connections.handoff.HandoffPayload
import ani.dantotsu.media.screenshot.ClipDialogFragment
import ani.dantotsu.media.screenshot.ClipOutput
import ani.dantotsu.media.screenshot.ClipSubtitleOverlay
import ani.dantotsu.media.screenshot.ScreenshotDialogFragment
import ani.dantotsu.media.screenshot.ScreenshotUtil
import ani.dantotsu.media.screenshot.SubtitleCueBuffer
import ani.dantotsu.brightnessConverter
import ani.dantotsu.circularReveal
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.discord.PresenceSources
import ani.dantotsu.connections.discord.RPCManager
import ani.dantotsu.connections.discord.RPC
import ani.dantotsu.connections.updateProgress
import ani.dantotsu.databinding.ActivityExoplayerBinding
import ani.dantotsu.defaultHeaders
import ani.dantotsu.download.DownloadsManager.Companion.getSubDirectory
import ani.dantotsu.download.video.Helper
import ani.dantotsu.dp
import ani.dantotsu.getCurrentBrightnessValue
import ani.dantotsu.getLanguageCode
import ani.dantotsu.hideSystemBars
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.isOnline
import ani.dantotsu.logError
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.SubtitleDownloader
import ani.dantotsu.okHttpClient
import ani.dantotsu.others.AniSkip
import ani.dantotsu.others.AniSkip.getType
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.others.ResettableTimer
import ani.dantotsu.others.Xubtitle
import ani.dantotsu.others.getSerialized
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.HAnimeSources
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.SubtitleType
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.settings.PlayerSettingsActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.startMainActivity
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toPx
import ani.dantotsu.toast
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.LeakDetection
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.anggrayudi.storage.file.extension
import com.bumptech.glide.Glide
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.lagradost.nicehttp.ignoreAllSSLErrors
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.os.SystemClock
import okhttp3.Dns
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.InetAddress
import java.util.Calendar
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.core.net.toUri

@UnstableApi
@SuppressLint("ClickableViewAccessibility")
class ExoplayerView :
    AppCompatActivity(),
    Player.Listener {
    private val resumeWindow = "resumeWindow"
    private val resumePosition = "resumePosition"
    private val playerFullscreen = "playerFullscreen"
    private val playerOnPlay = "playerOnPlay"

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var cacheFactory: CacheDataSource.Factory
    private lateinit var playbackParameters: PlaybackParameters
    private lateinit var mediaItem: MediaItem
    private lateinit var mediaSource: MergingMediaSource
    private var mediaSession: MediaSession? = null

    private lateinit var binding: ActivityExoplayerBinding
    private lateinit var playerView: PlayerView
    private lateinit var exoPlay: ImageButton
    private lateinit var exoSource: ImageButton
    private lateinit var exoSettings: ImageButton
    private lateinit var exoSubtitle: ImageButton
    private lateinit var exoSubtitleView: SubtitleView
    private lateinit var exoAudioTrack: ImageButton
    private lateinit var exoRotate: ImageButton
    private lateinit var exoSpeed: ImageButton
    private lateinit var exoScreen: ImageButton
    private lateinit var exoNext: ImageButton
    private lateinit var exoPrev: ImageButton
    private lateinit var exoPip: ImageButton
    private lateinit var exoBrightness: Slider
    private lateinit var exoVolume: Slider
    private lateinit var exoBrightnessCont: View
    private lateinit var exoVolumeCont: View
    private lateinit var animeTitle: TextView
    private lateinit var videoInfo: TextView
    private lateinit var episodeTitle: Spinner
    private lateinit var customSubtitleView: Xubtitle
    private var assHandler: AssHandler? = null
    private var assSubtitleView: io.github.peerless2012.ass.media.widget.AssSubtitleView? = null
    private lateinit var assMediaSourceFactory: DefaultMediaSourceFactory

    /**
     * Text subtitles seen recently, so a clip taken after the fact can still burn them in. libass
     * tracks don't need this — they can be re-rendered at any timestamp. See [SubtitleCueBuffer].
     */
    private val subtitleCues = SubtitleCueBuffer(CLIP_CUE_WINDOW_MS)

    private var orientationListener: OrientationEventListener? = null

    private var hasExtSubtitles = false
    private var audioLanguages = mutableListOf<Pair<String, String>>()

    companion object {
        var initialized = false
        lateinit var media: Media

        /** The next start resumes without asking; set by restartForSettings, which recreates. */
        private var resumeSilently = false

        private const val DEFAULT_MIN_BUFFER_MS = 900000
        private const val DEFAULT_MAX_BUFFER_MS = 900000

        /**
         * Memory the buffer may fill, which is what actually bounds it: ExoPlayer stops loading at
         * whichever of the duration and this comes first, and its own default (~138 MB, back
         * buffer included) is only about four minutes of a 1080p stream. Half the heap — buffered
         * media lives in the Java heap, and largeHeap makes that 512 MB on most devices — leaves
         * the rest of the app plenty, the pixels of cached images being native memory.
         */
        private val TARGET_BUFFER_BYTES: Int
            get() = (Runtime.getRuntime().maxMemory() / 2)
                .coerceIn(64L * 1024 * 1024, 384L * 1024 * 1024).toInt()
        private const val BACK_BUFFER_MS = 60_000
        private const val BUFFER_FOR_PLAYBACK_MS = 2500
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5000
        private const val PIP_SEEK_INCREMENT_MS = 10_000L

        /** How far back the subtitle cue buffer keeps history — the longest clip anyone can ask for. */
        private const val CLIP_CUE_WINDOW_MS = 5 * 60 * 1000L

        /** Width the clip sheet's end thumbnail is scaled down to before being handed over. */
        private const val CLIP_THUMBNAIL_WIDTH = 320

        /** Marks the background playback notification's intent; see onNewIntent. */
        private const val EXTRA_FROM_NOTIFICATION = "ani.dantotsu.player.FROM_NOTIFICATION"

        /** How long a chooser hand-off keeps picture-in-picture suppressed. */
        private const val PIP_SUPPRESS_MS = 2000L

    }

    private lateinit var episode: Episode
    private lateinit var episodes: MutableMap<String, Episode>
    private lateinit var episodeArr: List<String>
    private lateinit var episodeTitleArr: ArrayList<String>
    private var currentEpisodeIndex = 0
    private var epChanging = false

    private var extractor: VideoExtractor? = null
    private var video: Video? = null
    private var subtitle: Subtitle? = null

    private var notchHeight: Int = 0
    private var currentWindow = 0
    private var playbackPosition: Long = 0
    private var episodeLength: Float = 0f

    /**
     * Whether the episode on screen has actually played, here or on a cast device. Progress is only
     * sent for one that has: a stream that never starts still reports a position — the saved one
     * it was asked to resume at — and the first episode counts as "started" on opening alone.
     */
    private var playedThisEpisode = false
    private var isFullscreen: Int = 0
    private var isInitialized = false
    private var isPlayerPlaying = true
    private var changingServer = false
    private var interacted = false

    private var pipEnabled = false

    /** Deadline until which [onUserLeaveHint] won't start PiP; see [suppressPipForChooser]. */
    private var suppressPipUntil = 0L
    private var isEnteringPip = false
    private val pip = PlayerPip(this) { if (isInitialized) exoPlayer else null }
    private var pipExitTimestamp: Long = 0
    private var pendingPiPExit: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var cast: PlayerCast
    private lateinit var bottomBar: PlayerBottomBar
    private lateinit var gestures: PlayerGestures
    val model: MediaDetailsViewModel by viewModels()

    private lateinit var skipStamps: PlayerSkipStamps
    private var locked = false

    var rotation = 0

    override fun onAttachedToWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val displayCutout = window.decorView.rootWindowInsets.displayCutout
            if (displayCutout != null) {
                if (displayCutout.boundingRects.size > 0) {
                    notchHeight =
                        min(
                            displayCutout.boundingRects[0].width(),
                            displayCutout.boundingRects[0].height(),
                        )
                    checkNotch()
                }
            }
        }
        super.onAttachedToWindow()
    }

    private fun checkNotch() {
        if (notchHeight != 0) {
            val orientation = resources.configuration.orientation
            playerView
                .findViewById<View>(R.id.exo_controller_margin)
                .updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        marginStart = notchHeight
                        marginEnd = notchHeight
                        topMargin = 0
                    } else {
                        topMargin = notchHeight
                        marginStart = 0
                        marginEnd = 0
                    }
                }
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_buffering).translationY =
                (if (orientation == Configuration.ORIENTATION_LANDSCAPE) 0 else (notchHeight + 8.toPx)).dp
            exoBrightnessCont.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginEnd =
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) notchHeight else 0
            }
            exoVolumeCont.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart =
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) notchHeight else 0
            }
        }
    }

    /** Shrinks a captured frame to thumbnail size; a full 1080p bitmap is a lot to hand around. */
    private fun clipThumbnail(frame: Bitmap?): Bitmap? {
        if (frame == null || frame.width <= 0) return null
        if (frame.width <= CLIP_THUMBNAIL_WIDTH) return frame
        val height = (frame.height.toLong() * CLIP_THUMBNAIL_WIDTH / frame.width)
            .toInt().coerceAtLeast(1)
        return runCatching {
            Bitmap.createScaledBitmap(frame, CLIP_THUMBNAIL_WIDTH, height, true)
                .also { if (it !== frame) frame.recycle() }
        }.getOrDefault(frame)
    }

    /**
     * Bundles up what a clip export needs from the live player: the stream itself, the data source
     * that can re-read it (headers and warm cache included), and whichever subtitle track is on.
     */
    private fun buildClipPayload(endFrame: Bitmap?): ClipDialogFragment.Payload? {
        // Both are set up together with the player; if the episode hasn't loaded there is nothing
        // to re-cut, so the caller reports the failure rather than crashing on the lateinit.
        if (!::mediaItem.isInitialized || !::cacheFactory.isInitialized) return null
        val handler = assHandler
        val subtitles = when {
            handler?.hasTracks() == true && handler.track != null ->
                ClipDialogFragment.Subtitles.Ass {
                    // A renderer of its own: the player's is sized for the screen, and an export
                    // would have to fight it over the frame size for every frame.
                    runCatching {
                        handler.ass.createRender().apply {
                            setCacheLimit(2048, 256)
                            setTrack(handler.track)
                        }
                    }.getOrNull()
                }

            !subtitleCues.isEmpty() ->
                ClipDialogFragment.Subtitles.Text(subtitleCues, PlayerSubtitleStyle.forClip(this))

            else -> null
        }
        return ClipDialogFragment.Payload(
            mediaItem = mediaItem,
            dataSourceFactory = cacheFactory,
            subtitles = subtitles,
            endFrame = endFrame,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityExoplayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pip.register()

        // Renders from a previous session are done being shared by now; a new playback session is
        // the safe point to drop them, rather than on dismiss while a share may still be reading.
        ClipOutput.clearWorkDir(this)


        hideSystemBarsExtendView()

        onBackPressedDispatcher.addCallback(this) {
            finishAndRemoveTask()
        }

        playerView = findViewById(R.id.player_view)
        bottomBar = PlayerBottomBar(playerView)
        cast = PlayerCast(this, playerView, object : PlayerCast.Host {
            override val exoPlayer: ExoPlayer get() = this@ExoplayerView.exoPlayer
            override val isInitialized: Boolean get() = this@ExoplayerView.isInitialized
            override val mediaItem: MediaItem?
                get() = if (this@ExoplayerView::mediaItem.isInitialized) this@ExoplayerView.mediaItem else null
            override fun withMetadata(item: MediaItem) = item.withEpisodeMetadata()
            override fun savePosition(position: Long) =
                PrefManager.setCustomVal("${media.id}_${media.anime!!.selectedEpisode}", position)
            override fun onActivePlayerChanged() = discordRPC()
            override fun onCastPlaying() {
                playedThisEpisode = true
            }
            override fun onCastPlayWhenReadyChanged(playWhenReady: Boolean) {
                (exoPlay.drawable as Animatable?)?.start()
                Glide.with(this@ExoplayerView)
                    .load(if (playWhenReady) R.drawable.anim_play_to_pause else R.drawable.anim_pause_to_play)
                    .into(exoPlay)
            }
        }).also { it.setUp() }
        exoPlay = playerView.findViewById(androidx.media3.ui.R.id.exo_play)
        exoSource = playerView.findViewById(R.id.exo_source)
        exoSettings = playerView.findViewById(R.id.exo_settings)
        exoSubtitle = playerView.findViewById(R.id.exo_sub)
        exoAudioTrack = playerView.findViewById(R.id.exo_audio)
        exoSubtitleView = playerView.findViewById(androidx.media3.ui.R.id.exo_subtitles)
        exoRotate = playerView.findViewById(R.id.exo_rotate)
        exoSpeed = playerView.findViewById(androidx.media3.ui.R.id.exo_playback_speed)
        exoScreen = playerView.findViewById(R.id.exo_screen)
        exoBrightness = playerView.findViewById(R.id.exo_brightness)
        exoVolume = playerView.findViewById(R.id.exo_volume)
        exoBrightnessCont = playerView.findViewById(R.id.exo_brightness_cont)
        exoVolumeCont = playerView.findViewById(R.id.exo_volume_cont)
        exoPip = playerView.findViewById(R.id.exo_pip)
        customSubtitleView = playerView.findViewById(R.id.customSubtitleView)

        animeTitle = playerView.findViewById(R.id.exo_anime_title)
        episodeTitle = playerView.findViewById(R.id.exo_ep_sel)

        playerView.controllerShowTimeoutMs = 5000

        val audioManager = applicationContext.getSystemService(AUDIO_SERVICE) as AudioManager

        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus({ focus ->
            when (focus) {
                AUDIOFOCUS_LOSS_TRANSIENT, AUDIOFOCUS_LOSS -> if (isInitialized) exoPlayer.pause()
            }
        }, AUDIO_CONTENT_TYPE_MOVIE, AUDIOFOCUS_GAIN)

        if (System.getInt(contentResolver, System.ACCELEROMETER_ROTATION, 0) != 1) {
            if (PrefManager.getVal(PrefName.RotationPlayer)) {
                orientationListener =
                    object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_UI) {
                        override fun onOrientationChanged(orientation: Int) {
                            when (orientation) {
                                in 45..135 -> {
                                    if (rotation != ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                                        exoRotate.visibility = View.VISIBLE
                                    }
                                    rotation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                                }

                                in 225..315 -> {
                                    if (rotation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                                        exoRotate.visibility = View.VISIBLE
                                    }
                                    rotation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                }

                                in 315..360, in 0..45 -> {
                                    if (rotation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                                        exoRotate.visibility = View.VISIBLE
                                    }
                                    rotation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                }
                            }
                        }
                    }
                orientationListener?.enable()
            }

            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            exoRotate.setOnClickListener {
                requestedOrientation = rotation
                it.visibility = View.GONE
            }
        }

        if (savedInstanceState != null) {
            currentWindow = savedInstanceState.getInt(resumeWindow)
            playbackPosition = savedInstanceState.getLong(resumePosition)
            isFullscreen = savedInstanceState.getInt(playerFullscreen)
            isPlayerPlaying = savedInstanceState.getBoolean(playerOnPlay)
        }

        // BackButton
        playerView.findViewById<ImageButton>(R.id.exo_back).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Play Pause
        exoPlay.setOnClickListener {
            if (isInitialized) {
                isPlayerPlaying = exoPlayer.isPlaying
                (exoPlay.drawable as Animatable?)?.start()
                val remote = cast.player?.takeIf { cast.isCasting }
                if (remote?.playWhenReady ?: isPlayerPlaying) {
                    Glide.with(this).load(R.drawable.anim_play_to_pause).into(exoPlay)
                    exoPlayer.pause()
                    remote?.pause()
                } else {
                    if (remote != null) {
                        Glide.with(this).load(R.drawable.anim_pause_to_play).into(exoPlay)
                        remote.play()
                    } else if (!isPlayerPlaying) {
                        Glide.with(this).load(R.drawable.anim_pause_to_play).into(exoPlay)
                        exoPlayer.play()
                    }
                }
            }
        }

        // Picture-in-picture: the button is the choice, so it is there wherever PiP is supported;
        // going there on Home is PlayerHomeAction's to decide.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            pipEnabled = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            if (pipEnabled) {
                exoPip.visibility = View.VISIBLE
                exoPip.setOnClickListener {
                    enterPipMode()
                }
            } else {
                exoPip.visibility = View.GONE
            }
        }

        // Lock Button
        val container = playerView.findViewById<View>(R.id.exo_controller_cont)
        val screen = playerView.findViewById<View>(R.id.exo_black_screen)
        val lockButton = playerView.findViewById<ImageButton>(R.id.exo_unlock)
        val timeline =
            playerView.findViewById<ExtendedTimeBar>(androidx.media3.ui.R.id.exo_progress)
        playerView.findViewById<ImageButton>(R.id.exo_lock).setOnClickListener {
            locked = true
            screen.visibility = View.GONE
            container.visibility = View.GONE
            lockButton.visibility = View.VISIBLE
            timeline.setForceDisabled(true)
        }
        lockButton.setOnClickListener {
            locked = false
            screen.visibility = View.VISIBLE
            container.visibility = View.VISIBLE
            it.visibility = View.GONE
            timeline.setForceDisabled(false)
        }

        skipStamps = PlayerSkipStamps(this, playerView, model, object : PlayerSkipStamps.Host {
            override val exoPlayer: ExoPlayer get() = this@ExoplayerView.exoPlayer
            override val isInitialized: Boolean get() = this@ExoplayerView.isInitialized
        })

        gestures = PlayerGestures(this, playerView, keyMap, object : PlayerGestures.Host {
            override val exoPlayer: ExoPlayer get() = this@ExoplayerView.exoPlayer
            override val isInitialized: Boolean get() = this@ExoplayerView.isInitialized
            override val locked: Boolean get() = this@ExoplayerView.locked
            override fun checkNotch() = this@ExoplayerView.checkNotch()
        }).also { it.setUp() }

        // Handle Media
        if (!initialized) return startMainActivity(this)
        model.setMedia(media)
        title = media.userPreferredName
        episodes = media.anime?.episodes ?: return startMainActivity(this)

        videoInfo = playerView.findViewById(R.id.exo_video_info)

        model.watchSources = if (media.isAdult) HAnimeSources else AnimeSources

        model.epChanged.observe(this) {
            epChanging = !it
        }

        // Anime Title
        animeTitle.text = media.userPreferredName

        episodeArr = episodes.keys.toList()
        currentEpisodeIndex = episodeArr.indexOf(media.anime!!.selectedEpisode!!)

        episodeTitleArr = arrayListOf()
        episodes.forEach {
            val episode = it.value
            val cleanedTitle = MediaNameAdapter.removeEpisodeNumberCompletely(episode.title ?: "")
            episodeTitleArr.add(
                "Episode ${episode.number}${if (episode.filler) " [Filler]" else ""}${if (cleanedTitle.isNotBlank() && cleanedTitle != "null") ": $cleanedTitle" else ""}",
            )
        }

        // Episode Change
        fun change(index: Int) {
            if (isInitialized) {
                changingServer = false
                PrefManager.setCustomVal(
                    "${media.id}_${episodeArr[currentEpisodeIndex]}",
                    exoPlayer.currentPosition,
                )
                val prev = episodeArr[currentEpisodeIndex]
                skipStamps.loaded = false
                episodeLength = 0f
                playedThisEpisode = false
                media.anime!!.selectedEpisode = episodeArr[index]
                model.setMedia(media)
                model.epChanged.postValue(false)
                model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!, "change")
                model.onEpisodeClick(
                    media,
                    media.anime!!.selectedEpisode!!,
                    this.supportFragmentManager,
                    false,
                    prev,
                )
            }
        }

        // EpisodeSelector
        // White: this spinner floats over the video, not the app's own themed background.
        episodeTitle.adapter =
            NoPaddingArrayAdapter(this, R.layout.item_dropdown, episodeTitleArr, Color.WHITE)
        episodeTitle.setSelection(currentEpisodeIndex)
        episodeTitle.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?,
                    p1: View?,
                    position: Int,
                    p3: Long,
                ) {
                    if (position != currentEpisodeIndex) {
                        skipStamps.resetHint()
                        change(position)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>) {}
            }

        // Next Episode
        exoNext = playerView.findViewById(R.id.exo_next_ep)
        exoNext.setOnClickListener {
            if (isInitialized) {
                nextEpisode { i ->
                    updateAniProgress()
                    skipStamps.resetHint()
                    change(currentEpisodeIndex + i)
                }
            }
        }
        // Prev Episode
        exoPrev = playerView.findViewById(R.id.exo_prev_ep)
        exoPrev.setOnClickListener {
            if (currentEpisodeIndex > 0) {
                skipStamps.resetHint()
                change(currentEpisodeIndex - 1)
            } else {
                snackString(getString(R.string.first_episode))
            }
        }

        model.getEpisode().observe(this) { ep ->
            hideSystemBars()
            if (ep != null && !epChanging) {
                episode = ep
                media.selected = model.loadSelected(media)
                model.setMedia(media)
                currentEpisodeIndex = episodeArr.indexOf(ep.number)
                episodeTitle.setSelection(currentEpisodeIndex)
                if (isInitialized) releasePlayer()
                playbackPosition =
                    PrefManager.getCustomVal(
                        "${media.id}_${ep.number}",
                        0,
                    )
                initPlayer()
                WatchHistory.record(media, MediaType.ANIME, ep.number, ep.title)
                preloading = false
                updateProgress()
            }
        }

        // FullScreen
        isFullscreen = PrefManager.getCustomVal("${media.id}_fullscreenInt", isFullscreen)
        playerView.resizeMode =
            when (isFullscreen) {
                0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }

        exoScreen.setOnClickListener {
            if (isFullscreen < 2) isFullscreen += 1 else isFullscreen = 0
            playerView.resizeMode =
                when (isFullscreen) {
                    0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            snackString(
                when (isFullscreen) {
                    0 -> "Original"
                    1 -> "Zoom"
                    2 -> "Stretch"
                    else -> "Original"
                },
            )
            PrefManager.setCustomVal("${media.id}_fullscreenInt", isFullscreen)
        }

        // Cast
        if (PrefManager.getVal(PrefName.Cast)) {
            playerView.findViewById<CustomCastButton>(R.id.exo_cast).also { button ->
                button.visibility = View.VISIBLE
                cast.setUpButton(button) { castExternally() }
            }
        }

        // Settings: the sheet, as in the readers; the full page is a row inside it.
        exoSettings.setOnClickListener {
            if (supportFragmentManager.findFragmentByTag(PlayerSettingsSheet.TAG) != null) return@setOnClickListener
            settingsSheetOpen = true
            PlayerSettingsSheet().show(supportFragmentManager, PlayerSettingsSheet.TAG)
        }

        // Continue on another device. Extension-only media (id < 0) can't be re-fetched
        // elsewhere, so hide the button for it.
        playerView.findViewById<ImageButton>(R.id.exo_handoff).apply {
            isVisible = media.id >= 0
            setOnClickListener {
                val episode = media.anime?.selectedEpisode
                PrefManager.setCustomVal("${media.id}_$episode", exoPlayer.currentPosition)
                lifecycleScope.launch {
                    // The matched extension entry rides along so the receiver loads the episode
                    // list directly instead of re-searching the source by title.
                    val sourceMedia = withContext(Dispatchers.IO) {
                        runCatching {
                            model.watchSources?.get(media.selected!!.sourceIndex)
                                ?.loadSavedShowResponse(media.id)
                        }.getOrNull()
                    }
                    HandoffBottomSheet.send(
                        HandoffPayload(
                            mediaId = media.id,
                            isMAL = false,
                            isAnime = true,
                            mediaType = "ANIME",
                            title = media.userPreferredName,
                            cover = media.cover,
                            sourceName = model.watchSources?.names?.getOrNull(media.selected!!.sourceIndex),
                            number = episode,
                            positionMs = exoPlayer.currentPosition,
                            server = media.anime?.episodes?.get(episode)?.selectedExtractor,
                            sourceMedia = HandoffPayload.encodeShowResponse(sourceMedia),
                        )
                    ).show(supportFragmentManager, "handoff")
                }
            }
        }

        // Screenshot the current frame. Uses PixelCopy so the video SurfaceView (and subtitles)
        // are captured, which requires API 24+.
        playerView.findViewById<ImageButton>(R.id.exo_screenshot).apply {
            setImageResource(ScreenshotUtil.screenshotIcon(this@ExoplayerView))
            setOnClickListener {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    snackString(getString(R.string.screenshot_unsupported))
                    return@setOnClickListener
                }
                val episode = media.anime?.selectedEpisode
                val position = exoPlayer.currentPosition
                // Freeze on the exact frame the user tapped, then hide the controls before the grab.
                val wasPlaying = exoPlayer.isPlaying
                if (wasPlaying) exoPlayer.pause()
                playerView.hideController()
                playerView.postDelayed({
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@postDelayed
                    ScreenshotUtil.captureVideoFrame(
                        playerView.videoSurfaceView,
                        listOfNotNull(assSubtitleView, exoSubtitleView, customSubtitleView),
                    ) { bitmap ->
                        if (bitmap == null) {
                            if (wasPlaying) exoPlayer.play()
                            snackString(getString(R.string.screenshot_failed))
                            return@captureVideoFrame
                        }
                        ScreenshotDialogFragment.newInstance(
                            screenshot = bitmap,
                            title = media.userPreferredName,
                            titleOptions = media.mainTitleOptions(),
                            coverUrl = media.cover,
                            numberLabel = episode ?: "",
                            progressLabel = ScreenshotUtil.formatTimestamp(position),
                            sourceLabel = model.watchSources?.names
                                ?.getOrNull(media.selected!!.sourceIndex),
                            isAnime = true,
                        ).apply {
                            // Resume where we left off once the user closes the review sheet.
                            onDismissed = {
                                if (wasPlaying && !isDestroyed && !isFinishing) exoPlayer.play()
                            }
                        }.show(supportFragmentManager, "screenshot")
                    }
                }, 100)
            }
        }

        // Clip the seconds leading up to now. Unlike the screenshot button this doesn't capture the
        // screen at all — the range is re-cut from the source stream, which is what keeps the
        // original resolution and frame rate. See ClipExporter.
        playerView.findViewById<ImageButton>(R.id.exo_clip).setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                snackString(getString(R.string.clip_unsupported))
                return@setOnClickListener
            }
            val position = exoPlayer.currentPosition
            val window = PrefManager.getVal<Int>(PrefName.ClipDurationSeconds) * 1000L
            val start = (position - window).coerceAtLeast(0L)
            if (position - start < 1000L) {
                snackString(getString(R.string.clip_too_short))
                return@setOnClickListener
            }

            val wasPlaying = exoPlayer.isPlaying
            if (wasPlaying) exoPlayer.pause()
            playerView.hideController()

            // The clip's window ends here, on this frame, so grab it now: it's the sheet's end
            // thumbnail, exact and immediate, where making the preview seek forward to find it
            // again is neither.
            ScreenshotUtil.captureVideoFrame(playerView.videoSurfaceView, emptyList()) { frame ->
                val payload = buildClipPayload(clipThumbnail(frame))
                if (payload == null) {
                    if (wasPlaying) exoPlayer.play()
                    snackString(getString(R.string.clip_failed))
                    return@captureVideoFrame
                }
                ClipDialogFragment.newInstance(
                    payload = payload,
                    title = media.userPreferredName,
                    titleOptions = media.mainTitleOptions(),
                    coverUrl = media.cover,
                    numberLabel = media.anime?.selectedEpisode ?: "",
                    sourceLabel = model.watchSources?.names?.getOrNull(media.selected!!.sourceIndex),
                    windowStartMs = start,
                    windowEndMs = position,
                ).apply {
                    onDismissed = {
                        if (wasPlaying && !isDestroyed && !isFinishing) exoPlayer.play()
                    }
                }.show(supportFragmentManager, "clip")
            }
        }

        // Speed
        val speeds =
            if (PrefManager.getVal(PrefName.CursedSpeeds)) {
                arrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f, 10f, 25f, 50f)
            } else {
                arrayOf(
                    0.25f,
                    0.33f,
                    0.5f,
                    0.66f,
                    0.75f,
                    1f,
                    1.15f,
                    1.25f,
                    1.33f,
                    1.5f,
                    1.66f,
                    1.75f,
                    2f,
                )
            }

        val speedsName = speeds.map { "${it}x" }.toTypedArray()
        // var curSpeed = loadData("${media.id}_speed", this) ?: settings.defaultSpeed
        val speedsLength = speeds.size
        val savedIndex = PrefManager.getCustomVal(
            "${media.id}_speed",
            PrefManager.getVal<Int>(PrefName.DefaultSpeed),
        )
        var curSpeed = savedIndex.coerceIn(0, speedsLength - 1)


        playbackParameters = PlaybackParameters(speeds[curSpeed])
        var speed: Float
        exoSpeed.setOnClickListener {
            customAlertDialog().apply {
                setTitle(R.string.speed)
                singleChoiceItems(speedsName, curSpeed) { i ->
                    PrefManager.setCustomVal("${media.id}_speed", i)
                    speed = speeds.getOrNull(i) ?: 1f
                    curSpeed = i
                    playbackParameters = PlaybackParameters(speed)
                    exoPlayer.playbackParameters = playbackParameters
                    hideSystemBars()
                }
                setOnCancelListener { hideSystemBars() }
                show()
            }
        }

        // Sleep timer: a set time, or the end of the episode playing. The button shows in the
        // accent colour while one is set.
        val sleepButton = playerView.findViewById<ImageButton>(R.id.exo_sleep_timer)
        sleepTimer = PlayerSleepTimer(
            onExpire = {
                exoPlayer.pause()
                playerView.showController()
                snackString(getString(R.string.player_sleep_paused))
            },
            onChange = {
                sleepButton.imageTintList = ColorStateList.valueOf(
                    if (sleepTimer?.isActive == true) {
                        MaterialColors.getColor(sleepButton, androidx.appcompat.R.attr.colorPrimary)
                    } else {
                        Color.WHITE
                    }
                )
            },
        )
        sleepButton.setOnClickListener {
            val timer = sleepTimer ?: return@setOnClickListener
            val minutes = listOf(15, 30, 45, 60, 90)
            val names = (listOf(getString(R.string.novel_tts_sleep_off),
                getString(R.string.player_sleep_end_of_episode)) +
                minutes.map { getString(R.string.novel_tts_sleep_minutes, it) }).toTypedArray()
            // A countdown shows as the first choice at least as long as what is left.
            val current = when {
                timer.endOfEpisode -> 1
                timer.minutesLeft > 0 -> 2 + minutes.indexOfFirst { it >= timer.minutesLeft }
                    .let { if (it < 0) minutes.lastIndex else it }
                else -> 0
            }
            customAlertDialog().apply {
                if (timer.minutesLeft > 0) setTitle(getString(R.string.player_sleep_left, timer.minutesLeft))
                else setTitle(R.string.novel_tts_sleep_timer)
                singleChoiceItems(names, current) { i ->
                    when (i) {
                        0 -> timer.cancel()
                        1 -> timer.setEndOfEpisode()
                        else -> timer.setMinutes(minutes[i - 2])
                    }
                    hideSystemBars()
                }
                setOnCancelListener { hideSystemBars() }
                show()
            }
        }

        // Always tracked, not only with autoplay on at the start: it can be switched on from the
        // settings sheet mid-episode, and would then find nobody had ever touched the screen.
        run {
            var touchTimer = Timer()

            fun touched() {
                interacted = true
                touchTimer.apply {
                    cancel()
                    purge()
                }
                touchTimer = Timer()
                touchTimer.schedule(
                    object : TimerTask() {
                        override fun run() {
                            interacted = false
                        }
                    },
                    1000 * 60 * 60,
                )
            }
            playerView.findViewById<View>(R.id.exo_touch_view).setOnTouchListener { _, _ ->
                touched()
                false
            }
        }

        isFullscreen = PrefManager.getVal(PrefName.Resize)
        playerView.resizeMode =
            when (isFullscreen) {
                0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }

        preloading = false
        val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
        val showProgressDialog =
            if (PrefManager.getVal(PrefName.AskIndividualPlayer)) {
                PrefManager.getCustomVal(
                    "${media.id}_progressDialog",
                    true,
                )
            } else {
                false
            }
        if (!incognito &&
            showProgressDialog &&
            Anilist.userid != null &&
            if (media.isAdult) {
                PrefManager.getVal(
                    PrefName.UpdateForHPlayer,
                )
            } else {
                true
            }
        ) {
            customAlertDialog().apply {
                setTitle(getString(R.string.auto_update, media.userPreferredName))
                setCancelable(false)
                setPosButton(R.string.yes) {
                    PrefManager.setCustomVal(
                        "${media.id}_progressDialog",
                        false,
                    )
                    PrefManager.setCustomVal(
                        "${media.id}_save_progress",
                        true,
                    )
                    model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!, "invoke")
                }
                setNegButton(R.string.no) {
                    PrefManager.setCustomVal(
                        "${media.id}_progressDialog",
                        false,
                    )
                    PrefManager.setCustomVal(
                        "${media.id}_save_progress",
                        false,
                    )
                    toast(getString(R.string.reset_auto_update))
                    model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!, "invoke")
                }
                setOnCancelListener { hideSystemBars() }
                show()
            }
        } else {
            model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!, "invoke")
        }

        // Start the recursive Fun
        if (PrefManager.getVal(PrefName.TimeStampsEnabled)) {
            skipStamps.start()
        }
    }

    private fun discordRPC() {
        if (!this::episode.isInitialized) return
        val context = this
        val ep = episode
        if (RPCManager.isAllowed(context)) {
            lifecycleScope.launch {
                val source = PresenceSources.forMedia(media, model.watchSources)

                // Whichever player is actually playing: while casting, the phone's own player is
                // stopped, and reading it would report the episode paused — and auto-clear it —
                // for as long as it plays on the TV.
                val active: Player = cast.player?.takeIf { playerView.player === it } ?: exoPlayer
                val now = java.lang.System.currentTimeMillis()
                val currentPosMs = active.currentPosition.coerceAtLeast(0L)
                val safeDurationMs = if (active.duration > 0 && active.duration != C.TIME_UNSET) active.duration else 1440000L // default 24 mins

                // If paused, we don't send timestamps so the timer stops
                val isPaused = if (active === exoPlayer) !isPlayerPlaying else !active.isPlaying
                val startTimestamp = if (isPaused) null else now - currentPosMs
                val endTimestamp = if (isPaused) null else (now - currentPosMs) + safeDurationMs

                val stateText = "Episode ${ep.number}/${media.anime?.totalEpisodes ?: "??"}"
                val finalState = if (isPaused) "Paused - $stateText" else stateText

                val rpcData = RPC.Companion.RPCData(
                    applicationId = Discord.application_Id,
                    kind = RPC.Kind.ANIME,
                    isPaused = isPaused,
                    type = RPC.Type.WATCHING,
                    activityName = media.userPreferredName,
                    details = ep.title?.takeIf { it.isNotEmpty() } ?: getString(R.string.episode_num, ep.number),
                    startTimestamp = startTimestamp,
                    stopTimestamp = endTimestamp,
                    state = finalState,
                    largeImage = media.cover?.let { RPC.Link(media.userPreferredName, it) },
                    smallImage = null,
                    source = source,
                )
                RPCManager.setPresence(context, rpcData)
            }
        }
    }

    private fun initPlayer() {
        checkNotch()

        PrefManager.setCustomVal(
            "${media.id}_current_ep",
            media.anime!!.selectedEpisode!!,
        )

        @Suppress("UNCHECKED_CAST")
        val list =
            (
                    PrefManager.getNullableCustomVal(
                        "continueAnimeList",
                        listOf<Int>(),
                        List::class.java,
                    ) as List<Int>
                    ).toMutableList()
        if (list.contains(media.id)) list.remove(media.id)
        list.add(media.id)
        PrefManager.setCustomVal("continueAnimeList", list)
        // Same event, recorded once more where anime and manga share an ordering — see ContinueHistory.
        ani.dantotsu.widgets.ContinueHistory.record(media.id, isAnime = true)
        ani.dantotsu.widgets.WidgetRefresh.onContinueChanged(this)

        lifecycleScope.launch(Dispatchers.IO) {
            extractor?.onVideoStopped(video)
        }

        val ext = episode.extractors?.find { it.server.name == episode.selectedExtractor } ?: return
        extractor = ext
        video = ext.videos.getOrNull(episode.selectedVideo) ?: return
        val lang = PlayerSources.SUB_LANGUAGES[PrefManager.getVal(PrefName.SubLanguage)]
        subtitle = intent.getSerialized("subtitle")
            ?: when (
                val subLang: String? =
                    PrefManager.getNullableCustomVal(
                        "subLang_${media.id}",
                        null,
                        String::class.java
                    )
            ) {
                null -> {
                    when (episode.selectedSubtitle) {
                        null -> null
                        -1 ->
                            ext.subtitles.find {
                                it.language.contains(lang, ignoreCase = true) ||
                                        it.language.contains(
                                            getLanguageCode(lang),
                                            ignoreCase = true
                                        )
                            }

                        else -> ext.subtitles.getOrNull(episode.selectedSubtitle!!)
                    }
                }

                "None" -> ext.subtitles.let { null }
                else -> ext.subtitles.find { it.language == subLang }
            }

        // Subtitles
        hasExtSubtitles = ext.subtitles.isNotEmpty()
        if (hasExtSubtitles) {
            exoSubtitle.isVisible = hasExtSubtitles
            exoSubtitle.setOnClickListener {
                subClick()
            }
        }
        val sub = PlayerSources.subtitleConfigurations(ext, video!!, hasExtSubtitles)

        lifecycleScope.launch(Dispatchers.IO) {
            ext.onVideoPlayed(video)
        }

        cacheFactory = PlayerSources.cacheFactory(this, video)

        // Set up libass for ASS/SSA subtitle rendering.
        // We use OVERLAY_OPEN_GL to render subtitles in a dedicated hardware-accelerated
        // TextureView via a background HandlerThread.
        if (assHandler == null) {
            Logger.log("Libass: Creating AssHandler with OVERLAY_OPEN_GL")
            assHandler = AssHandler(
                AssRenderType.OVERLAY_OPEN_GL,
                AssHandlerConfig(glyphSize = 2048, cacheSize = 256)
            )
            // Inject the dedicated AssSubtitleTextureView into the video frame hierarchy.
            Logger.log("Libass: Injecting AssSubtitleView into exo_content_frame")
            val contentFrame = playerView.findViewById<androidx.media3.ui.AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            val assView = io.github.peerless2012.ass.media.widget.AssSubtitleView(this, assHandler!!)
            assView.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            contentFrame?.addView(assView)
            assSubtitleView = assView
        }
        val handler = assHandler!!
        val assSubtitleParserFactory = AssSubtitleParserFactory(handler)
        val extractorsFactory = DefaultExtractorsFactory()
            .withAssMkvSupport(assSubtitleParserFactory, handler)
        assMediaSourceFactory = DefaultMediaSourceFactory(cacheFactory, extractorsFactory)
        assMediaSourceFactory.setSubtitleParserFactory(assSubtitleParserFactory)

        val mimeType =
            when (video?.format) {
                VideoType.M3U8 -> MimeTypes.APPLICATION_M3U8
                VideoType.DASH -> MimeTypes.APPLICATION_MPD
                else -> MimeTypes.APPLICATION_MP4
            }

        val downloadedMediaItem = if (ext.server.offline) PlayerSources.downloadedItem(this, ext) else null

        mediaItem =
            if (downloadedMediaItem == null) {
                val builder = MediaItem.Builder().setUri(video!!.file.url).setMimeType(mimeType)
                Logger.log("url: ${video!!.file.url}")
                Logger.log("mimeType: $mimeType")
                builder.setSubtitleConfigurations(sub)
                builder.build()
            } else {
                if (sub.isNotEmpty()) {
                    val addedSubsDownloadedMediaItem = downloadedMediaItem.buildUpon()
                    val addLanguage = sub[0].buildUpon().setLanguage("en").build()
                    addedSubsDownloadedMediaItem.setSubtitleConfigurations(listOf(addLanguage))
                    episode.selectedSubtitle = 0
                    addedSubsDownloadedMediaItem.build()
                } else {
                    downloadedMediaItem
                }
            }.withEpisodeMetadata()

        val audioSources = PlayerSources.audioSources(ext, cacheFactory, audioLanguages)
        val videoMediaSource =
            assMediaSourceFactory
                .createMediaSource(mediaItem)
        mediaSource = MergingMediaSource(videoMediaSource, *audioSources)

        // Source
        exoSource.setOnClickListener {
            sourceClick()
        }

        // Quality Track
        trackSelector = DefaultTrackSelector(this)
        val parameters =
            trackSelector
                .buildUponParameters()
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                .setSelectUndeterminedTextLanguage(true)
                .setAllowAudioMixedMimeTypeAdaptiveness(true)
                .setAllowMultipleAdaptiveSelections(true)
                .setPreferredTextLanguage(subtitle?.language ?: Locale.getDefault().language)
                .setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE)
                .setRendererDisabled(TRACK_TYPE_VIDEO, false)
                .setRendererDisabled(TRACK_TYPE_AUDIO, false)
                .setRendererDisabled(TRACK_TYPE_TEXT, false)
                .setMaxVideoSize(1, 1)
        // .setOverrideForType(TrackSelectionOverride(trackSelector, TRACK_TYPE_VIDEO))
        if (PrefManager.getVal(PrefName.SettingsPreferDub)) {
            parameters.setPreferredAudioLanguage(Locale.getDefault().language)
        }
        trackSelector.setParameters(parameters)

        // A restart for settings picks up where it was without asking; see restartForSettings.
        val silent = resumeSilently
        resumeSilently = false
        if (playbackPosition != 0L && !changingServer && !silent && !PrefManager.getVal<Boolean>(PrefName.AlwaysContinue)) {
            val time =
                String.format(
                    "%02d:%02d:%02d",
                    TimeUnit.MILLISECONDS.toHours(playbackPosition),
                    TimeUnit.MILLISECONDS.toMinutes(playbackPosition) -
                            TimeUnit.HOURS.toMinutes(
                                TimeUnit.MILLISECONDS.toHours(
                                    playbackPosition,
                                ),
                            ),
                    TimeUnit.MILLISECONDS.toSeconds(playbackPosition) -
                            TimeUnit.MINUTES.toSeconds(
                                TimeUnit.MILLISECONDS.toMinutes(
                                    playbackPosition,
                                ),
                            ),
                )
            customAlertDialog().apply {
                setTitle(getString(R.string.continue_from, time))
                setCancelable(false)
                setPosButton(getString(R.string.yes)) {
                    buildExoplayer()
                }
                setNegButton(getString(R.string.no)) {
                    playbackPosition = 0L
                    buildExoplayer()
                }
                show()
            }
        } else {
            buildExoplayer()
        }
    }

    private fun buildExoplayer() {
        // Player
        val loadControl =
            DefaultLoadControl
                .Builder()
                // A minute behind is plenty for the usual skip back; further than that comes
                // from the disk cache, and every byte kept here is one less buffered ahead.
                .setBackBuffer(BACK_BUFFER_MS, true)
                .setBufferDurationsMs(
                    DEFAULT_MIN_BUFFER_MS,
                    DEFAULT_MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                .build()

        hideSystemBars()

        val useExtensionDecoder = PrefManager.getVal<Boolean>(PrefName.UseAdditionalCodec)
        val decoder =
            if (useExtensionDecoder) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            } else {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            }
        // We use NextRenderersFactory to provide FFmpeg video/audio rendering when
        // Additional Codec Support is enabled. Since we now use OVERLAY_OPEN_GL,
        // it doesn't matter that FfmpegVideoRenderer bypasses the video effects pipeline.
        val baseRenderersFactory =
            NextRenderersFactory(this).apply {
                setEnableDecoderFallback(true)
                setExtensionRendererMode(decoder)
            }

        val handler = assHandler!!
        Logger.log("Libass: Calling baseRenderersFactory.withAssSupport()")
        val renderersFactory = baseRenderersFactory.withAssSupport(handler)

        exoPlayer =
            ExoPlayer
                .Builder(this, renderersFactory)
                .setMediaSourceFactory(assMediaSourceFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setSeekBackIncrementMs(PIP_SEEK_INCREMENT_MS)
                .setSeekForwardIncrementMs(PIP_SEEK_INCREMENT_MS)
                // Held only while playing: with the screen off (background playback) the CPU and
                // Wi-Fi would otherwise sleep under a stream mid-episode.
                .setWakeMode(C.WAKE_MODE_NETWORK)
                // Unplugged headphones pause rather than switch to the speaker.
                .setHandleAudioBecomingNoisy(true)
                .build()
        playerView.player = exoPlayer

        // init() must be called before prepare() so it receives onTracksChanged.
        Logger.log("Libass: Calling handler.init(exoPlayer)")
        handler.init(exoPlayer)

        exoPlayer.apply {
            playWhenReady = true
            this.playbackParameters = this@ExoplayerView.playbackParameters
            setMediaSource(mediaSource)
            prepare()
            // Back off from a position at or past the end, which would otherwise resume at EOF.
            // Guard against junk written by older builds: a stored C.TIME_UNSET is negative and
            // would compare as "before" every position, zeroing the resume point.
            PrefManager
                .getCustomVal(
                    "${media.id}_${media.anime!!.selectedEpisode}_max",
                    Long.MAX_VALUE,
                ).takeIf { it != Long.MAX_VALUE && it > 0 }
                ?.let { if (it <= playbackPosition) playbackPosition = max(0, it - 5000) }
            seekTo(playbackPosition)
        }

        exoPlayer.addListener(
            object : Player.Listener {
                var activeSubtitles = ArrayDeque<String>(3)
                var lastSubtitle: String? = null
                var lastPosition: Long = 0

                override fun onCues(cueGroup: CueGroup) {
                    val libassActive = assHandler?.hasTracks() == true || subtitle?.type == SubtitleType.ASS
                    if (libassActive) {
                        exoSubtitleView.visibility = View.GONE
                        customSubtitleView.visibility = View.GONE
                        customSubtitleView.text = ""
                        return
                    }

                    // Text cues exist only while they play, so remember them for a while: a clip
                    // is always taken after the moment it wants, and burning subtitles back in is
                    // the only way to get them into the export. libass tracks returned above are
                    // exempt — those can be re-rendered from the track at any timestamp.
                    subtitleCues.record(
                        exoPlayer.currentPosition,
                        cueGroup.cues.mapNotNull { it.text?.toString() }
                    )

                    if (PrefManager.getVal<Boolean>(PrefName.TextviewSubtitles)) {
                        exoSubtitleView.visibility = View.GONE
                        customSubtitleView.visibility = View.VISIBLE
                        val newCues = cueGroup.cues.map { it.text.toString() ?: "" }

                        if (newCues.isEmpty()) {
                            customSubtitleView.text = ""
                            activeSubtitles.clear()
                            lastSubtitle = null
                            lastPosition = 0
                            return
                        }

                        val currentPosition = exoPlayer.currentPosition

                        if ((lastSubtitle?.length
                                ?: 0) < 20 || (lastPosition != 0L && currentPosition - lastPosition > 1500)
                        ) {
                            activeSubtitles.clear()
                        }

                        for (newCue in newCues) {
                            if (newCue !in activeSubtitles) {
                                if (activeSubtitles.size >= 2) {
                                    activeSubtitles.removeLast()
                                }
                                activeSubtitles.addFirst(newCue)
                                lastSubtitle = newCue
                                lastPosition = currentPosition
                            }
                        }

                        customSubtitleView.text = activeSubtitles.joinToString("\n")
                    } else {
                        customSubtitleView.text = ""
                        customSubtitleView.visibility = View.GONE
                        exoSubtitleView.visibility = View.VISIBLE
                    }
                }
            },
        )

        PlayerSubtitleStyle.applyTo(this, customSubtitleView)
        PlayerSubtitleStyle.applyTo(this, playerView)

        try {
            val rightNow = Calendar.getInstance()
            mediaSession =
                MediaSession
                    .Builder(this, exoPlayer)
                    .setId(rightNow.timeInMillis.toString())
                    // Tapping the background playback notification comes back to this player.
                    .setSessionActivity(
                        PendingIntent.getActivity(
                            this,
                            0,
                            Intent(this, ExoplayerView::class.java)
                                .putExtra(EXTRA_FROM_NOTIFICATION, true)
                                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    .setMediaButtonPreferences(
                        listOf(
                            CommandButton
                                .Builder(CommandButton.ICON_SKIP_BACK)
                                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                                .setCustomIconResId(R.drawable.ic_round_replay_10_24)
                                .build(),
                            CommandButton
                                .Builder(CommandButton.ICON_SKIP_FORWARD)
                                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                                .setCustomIconResId(R.drawable.ic_round_forward_10_24)
                                .build(),
                        ),
                    )
                    .build()
        } catch (e: Exception) {
            toast(e.toString())
        }

        exoPlayer.addListener(this)
        exoPlayer.addAnalyticsListener(EventLogger())
        isInitialized = true

        // A new episode or server while casting goes to the TV, not to the phone: the session
        // only announces itself when it starts, so nothing else would send it there.
        if (cast.player?.isCastSessionAvailable == true) cast.startCasting()

        if (!hasExtSubtitles && !PrefManager.getVal<Boolean>(PrefName.Subtitles)) {
            onSetTrackGroupOverride(dummyTrack, TRACK_TYPE_TEXT)
        }

        val isDisabled = (subtitle == null && hasExtSubtitles)
        exoPlayer.trackSelectionParameters =
            exoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(TRACK_TYPE_TEXT, isDisabled)
                .build()
    }

    private fun releasePlayer() {
        isPlayerPlaying = exoPlayer.playWhenReady
        playbackPosition = exoPlayer.currentPosition
        skipStamps.resetHint()
        exoSubtitleView.removeAllViews()
        exoPlayer.release()
        VideoCache.release()
        mediaSession?.release()
        // The single biggest object this app holds: a retained ExoPlayer keeps its buffers,
        // renderers and the Activity that built it alive. LeakCanary watches Activities on its
        // own but knows nothing about a player released by hand, so say so explicitly.
        LeakDetection.watch(exoPlayer, "ExoPlayer released by ExoplayerView.releasePlayer")
        mediaSession?.let { LeakDetection.watch(it, "MediaSession released by ExoplayerView.releasePlayer") }
        RPCManager.clearPresence(this, RPC.Kind.ANIME)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (isInitialized) {
            outState.putInt(resumeWindow, exoPlayer.currentMediaItemIndex)
            outState.putLong(resumePosition, exoPlayer.currentPosition)
        }
        outState.putInt(playerFullscreen, isFullscreen)
        outState.putBoolean(playerOnPlay, isPlayerPlaying)
        super.onSaveInstanceState(outState)
    }

    private fun sourceClick() {
        changingServer = true

        media.selected!!.server = null
        // Don't clobber a saved/handoff-seeded resume position with 0 when the current stream
        // never got far enough to report a real position (e.g. it failed during preparation).
        if (exoPlayer.currentPosition > 0) {
            PrefManager.setCustomVal(
                "${media.id}_${media.anime!!.selectedEpisode}",
                exoPlayer.currentPosition,
            )
        }
        model.saveSelected(media.id, media.selected!!)
        model.onEpisodeClick(
            media,
            episode.number,
            this.supportFragmentManager,
            launch = false,
        )
    }

    private fun subClick() {
        PrefManager.setCustomVal(
            "${media.id}_${media.anime!!.selectedEpisode}",
            exoPlayer.currentPosition,
        )
        model.saveSelected(media.id, media.selected!!)
        SubtitleDialogFragment().show(supportFragmentManager, "dialog")
    }

    // Background playback (PlayerBackgroundPlay): while the player is out of sight and still
    // playing, its media session is hosted by PlayerBackgroundService. The video track stays
    // selected — switching it off and on re-prepares the stream there, an audible gap — and its
    // frames are simply dropped with no surface to draw on.

    /** Playing out of sight right now, the session hosted by [PlayerBackgroundService]. */
    private var inBackground = false

    /** When [onWindowFocusChanged] last paused for lost focus, which can come just before onPause. */
    private var focusPausedAt = 0L

    /**
     * The focus pause, held back a moment while background playback is on: focus can go just
     * before the screen turns off, and pausing then only to play again in onPause was a hiccup in
     * the audio. Cancelled by going to the background or getting focus back.
     */
    private val focusPause = Runnable {
        if (isInitialized && !inBackground) {
            exoPlayer.pause()
            focusPausedAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Whether leaving now should keep the audio going: the setting is on, something is playing
     * (or was until focus went a moment ago), and it isn't going to a TV or a PiP window instead.
     */
    private fun shouldPlayInBackground(): Boolean {
        // Home follows its own setting; the screen going off (or anything else covering the
        // player) follows background playback's.
        val wanted = if (leftByHome) PlayerHomeAction.current() == PlayerHomeAction.BACKGROUND
        else PrefManager.getVal<Boolean>(PrefName.PlayerBackgroundPlay)
        if (!wanted) return false
        if (!isInitialized || mediaSession == null || isFinishing) return false
        if (cast.isCasting || isEnteringPip || isInPictureInPictureMode) return false
        val justFocusPaused = wasPlaying && SystemClock.elapsedRealtime() - focusPausedAt < 1000
        return exoPlayer.isPlaying || justFocusPaused
    }

    private fun enterBackground() {
        handler.removeCallbacks(focusPause)
        inBackground = true
        if (!exoPlayer.isPlaying) exoPlayer.play()
        PlayerBackgroundService.start(this, mediaSession ?: return)
    }

    private fun leaveBackground() {
        if (!inBackground) return
        inBackground = false
        PlayerBackgroundService.stop(this)
    }

    override fun onPause() {
        super.onPause()
        orientationListener?.disable()
        if (isInitialized) {
            if (shouldPlayInBackground()) {
                enterBackground()
            } else if (!cast.isCasting && !isEnteringPip) {
                // Leaving the app pauses the phone, never the TV. This used to ask whether the
                // cast player was stopped, which is null — so never true — on a device without
                // Play Services, and there the episode went on playing in the background.
                playerView.player?.pause()
            }
            if (exoPlayer.currentPosition > 5000) {
                PrefManager.setCustomVal(
                    "${media.id}_${media.anime!!.selectedEpisode}",
                    exoPlayer.currentPosition,
                )
            }
        }
    }

    /**
     * Keeps the next [onUserLeaveHint] from starting picture-in-picture.
     *
     * Opening a share sheet is not the user leaving the player, but some platforms deliver
     * `onUserLeaveHint` when the chooser appears, so the player would shrink into PiP behind it.
     * Handing off to a chooser is always something this activity initiated, so it says so
     * beforehand and the hint is ignored for a moment.
     *
     * Emulated devices are exempt. On WSA the chooser is only surfaced by the window state
     * actually changing — suppressing PiP there stops the share sheet appearing at all, which is a
     * far worse outcome than the player briefly shrinking behind it.
     */
    fun suppressPipForChooser() {
        if (HandoffManager.isVirtualDevice(this)) return
        suppressPipUntil = SystemClock.elapsedRealtime() + PIP_SUPPRESS_MS
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The activity handles rotation itself (see configChanges in the manifest), so nothing is
        // re-inflated and anything orientation-dependent has to be refreshed by hand. The
        // screenshot glyph is a portrait or landscape frame, so it would otherwise keep showing
        // the shape of whichever way up the player happened to start.
        if (!::playerView.isInitialized) return
        playerView.findViewById<ImageButton>(R.id.exo_screenshot)
            ?.setImageResource(ScreenshotUtil.screenshotIcon(this))
    }

    /** Left by the Home button or the recents gesture, as opposed to the screen going off. */
    private var leftByHome = false

    override fun onUserLeaveHint() {
        // A share sheet this player opened is not the user leaving; see suppressPipForChooser.
        if (SystemClock.elapsedRealtime() < suppressPipUntil) return super.onUserLeaveHint()
        if (pipEnabled && PlayerHomeAction.current() == PlayerHomeAction.PIP) {
            enterPipMode()
        } else {
            leftByHome = true
            super.onUserLeaveHint()
        }
    }

    override fun onResume() {
        super.onResume()
        leftByHome = false
        leaveBackground()
        orientationListener?.enable()
        hideSystemBars()
        if (isInitialized) {
            playerView.onResume()
            playerView.useController = true
        }
    }

    override fun onStop() {
        // Always pause playback when the activity stops unless we're currently in PiP mode — or
        // casting: the episode is on the TV, and leaving the app shouldn't stop it there.
        if (!inBackground && !isInPictureInPictureMode() && isInitialized && !cast.isCasting) {
            playerView.player?.pause()
        }
        super.onStop()
    }

    private var wasPlaying = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        // If we're in the process of entering PiP, skip focus-based pause/resume handling
        if (isEnteringPip) {
            super.onWindowFocusChanged(hasFocus)
            return
        }

        if (pendingPiPExit) {
            // If activity regained focus shortly after exiting PiP, user returned to the app.
            val elapsed = java.lang.System.currentTimeMillis() - pipExitTimestamp
            pendingPiPExit = false
            if (hasFocus && elapsed < 2000L && isInitialized && wasPlaying) {
                exoPlayer.play()
            }
        }
        // Playing out of sight is the point of background playback; losing focus to the lock
        // screen or another app must not pause it.
        // Nor the player's own "more" menu, which takes focus while it is open.
        val ownMenu = (::bottomBar.isInitialized && bottomBar.ownsFocusChange) || settingsSheetOpen ||
            SystemClock.elapsedRealtime() - settingsSheetClosedAt < 500
        if (PrefManager.getVal(PrefName.FocusPause) && !epChanging && !inBackground && !ownMenu) {
            if (isInitialized && !hasFocus) wasPlaying = exoPlayer.isPlaying
            if (hasFocus) {
                handler.removeCallbacks(focusPause)
                if (isInitialized && wasPlaying) exoPlayer.play()
            } else if (isInitialized) {
                if (PrefManager.getVal(PrefName.PlayerBackgroundPlay) ||
                    PlayerHomeAction.current() == PlayerHomeAction.BACKGROUND
                ) {
                    handler.postDelayed(focusPause, 500)
                } else {
                    focusPause.run()
                }
            }
        }
        super.onWindowFocusChanged(hasFocus)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) playedThisEpisode = true
        if (!isBuffering) {
            isPlayerPlaying = isPlaying
            playerView.keepScreenOn = isPlaying
            pip.refresh()
            (exoPlay.drawable as Animatable?)?.start()
            if (!this.isDestroyed) {
                Glide
                    .with(this)
                    .load(if (isPlaying) R.drawable.anim_play_to_pause else R.drawable.anim_pause_to_play)
                    .into(exoPlay)
            }
            discordRPC()
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            discordRPC()
        }
    }

    override fun onRenderedFirstFrame() {
        super.onRenderedFirstFrame()
        // An HLS playlist without #EXT-X-ENDLIST has no duration at all, so this can be
        // C.TIME_UNSET — which is hugely *negative*. Comparing against it raw makes every check
        // below true, which is what silently threw resumed playback back to 0.
        val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        if (duration != null) {
            PrefManager.setCustomVal(
                "${media.id}_${media.anime!!.selectedEpisode}_max",
                duration,
            )
        }
        val height = (exoPlayer.videoFormat ?: return).height
        val width = (exoPlayer.videoFormat ?: return).width

        pip.aspectRatio = Rational(width, height)

        videoInfo.text = getString(R.string.video_quality, height)

        if (duration != null) {
            if (duration < playbackPosition) {
                exoPlayer.seekTo(0)
            }

            // if playbackPosition is within 92% of the episode length, reset it to 0
            if (playbackPosition > duration.toFloat() * 0.92) {
                playbackPosition = 0
                exoPlayer.seekTo(0)
            }
        }

        if (duration != null && !skipStamps.loaded && PrefManager.getVal(PrefName.TimeStampsEnabled)) {
            lifecycleScope.launch(Dispatchers.IO) {
                model.loadTimeStamps(
                    media.idMAL,
                    media.anime
                        ?.selectedEpisode
                        ?.trim()
                        ?.toIntOrNull(),
                    duration / 1000,
                    PrefManager.getVal(PrefName.UseProxyForTimeStamps),
                    extractor?.timestamps.orEmpty(),
                )
            }
        }
    }

    // Link Preloading
    private var preloading = false

    private fun updateProgress() {
        if (isInitialized) {
            if (exoPlayer.currentPosition.toFloat() / exoPlayer.duration >
                PrefManager.getVal<Float>(
                    PrefName.WatchPercentage,
                )
            ) {
                preloading = true
                nextEpisode(false) { i ->
                    val ep = episodes[episodeArr[currentEpisodeIndex + i]] ?: return@nextEpisode
                    val selected = media.selected ?: return@nextEpisode
                    lifecycleScope.launch(Dispatchers.IO) {
                        // The next episode is downloaded: it is already the one that will play,
                        // so there is no stream worth warming up for it.
                        if (model.loadDownloadedEpisode(ep, media)) return@launch
                        if (media.selected!!.server != null) {
                            model.loadEpisodeSingleVideo(ep, selected, false)
                        } else {
                            model.loadEpisodeVideos(ep, selected.sourceIndex, false)
                        }
                    }
                }
            }
        }
        if (!preloading) {
            handler.postDelayed({
                updateProgress()
            }, 2500)
        }
    }

    fun onSetTrackGroupOverride(
        trackGroup: Tracks.Group,
        type: @C.TrackType Int,
        index: Int = 0,
    ) {
        val isDisabled = trackGroup.getTrackFormat(0).language == "none"
        exoPlayer.trackSelectionParameters =
            exoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(TRACK_TYPE_TEXT, isDisabled)
                .setOverrideForType(
                    TrackSelectionOverride(trackGroup.mediaTrackGroup, index),
                ).build()
        if (type == TRACK_TYPE_TEXT) {
            PlayerSubtitleStyle.applyTo(this, playerView)
            PlayerSubtitleStyle.applyTo(this, customSubtitleView)
        }
        playerView.subtitleView?.alpha =
            when (isDisabled) {
                false -> PrefManager.getVal(PrefName.SubAlpha)
                true -> 0f
            }
    }

    private val dummyTrack =
        Tracks.Group(
            TrackGroup("Dummy Track", Format.Builder().apply { setLanguage("none") }.build()),
            true,
            intArrayOf(1),
            booleanArrayOf(false),
        )

    override fun onTracksChanged(tracks: Tracks) {
        val audioTracks: ArrayList<Tracks.Group> = arrayListOf()
        val subTracks: ArrayList<Tracks.Group> = arrayListOf(dummyTrack)
        tracks.groups.forEach {
            println(
                "Track__: $it\nTrack__: ${it.length}\nTrack__: ${it.isSelected}\n" +
                        "Track__: ${it.type}\nTrack__: ${it.mediaTrackGroup.id}",
            )
            when (it.type) {
                TRACK_TYPE_AUDIO -> {
                    if (it.isSupported(true)) audioTracks.add(it)
                }

                TRACK_TYPE_TEXT -> {
                    if (!hasExtSubtitles) {
                        if (it.isSupported(true)) subTracks.add(it)
                        return@forEach
                    }
                }
            }
        }
        exoAudioTrack.isVisible = audioTracks.size > 1
        exoAudioTrack.setOnClickListener {
            TrackGroupDialogFragment(this, audioTracks, TRACK_TYPE_AUDIO, audioLanguages)
                .show(supportFragmentManager, "dialog")
        }
        if (!hasExtSubtitles) {
            exoSubtitle.isVisible = subTracks.size > 1
            exoSubtitle.setOnClickListener {
                TrackGroupDialogFragment(this, subTracks, TRACK_TYPE_TEXT)
                    .show(supportFragmentManager, "dialog")
            }
        }
    }

    /**
     * Settings the player only reads as it starts — the buttons it sets up, the decoder, the track
     * preferences — which a change on the full page would otherwise leave waiting for the next
     * time the player is opened. Noted before the page opens; if any differ on the way back, the
     * player restarts itself where it was (see [restartForSettings]).
     */
    private val restartPrefs = listOf(
        PrefName.Cast, PrefName.UseInternalCast, PrefName.SkipTime, PrefName.RotationPlayer,
        PrefName.Resize, PrefName.CursedSpeeds, PrefName.DefaultSpeed, PrefName.UseAdditionalCodec,
        PrefName.TimeStampsEnabled, PrefName.UseProxyForTimeStamps, PrefName.SettingsPreferDub,
        PrefName.SubLanguage,
    )

    private fun restartPrefValues(): Map<PrefName, Any?> =
        restartPrefs.associateWith { PrefManager.getVal<Any?>(it) }

    private var settingsBefore: Map<PrefName, Any?>? = null

    /**
     * Starts the player afresh on the same episode and position, so settings read at start take
     * effect — without the "continue from" question, which the user didn't ask for here.
     */
    private fun restartForSettings() {
        if (isInitialized) {
            PrefManager.setCustomVal("${media.id}_${media.anime!!.selectedEpisode}", exoPlayer.currentPosition)
        }
        resumeSilently = true
        toast(getString(R.string.player_restarted_for_settings))
        recreate()
    }

    /** The full Player settings page, from the settings sheet; the episode waits for it. */
    fun openFullPlayerSettings() {
        settingsBefore = restartPrefValues()
        if (isInitialized) {
            PrefManager.setCustomVal("${media.id}_${media.anime!!.selectedEpisode}", exoPlayer.currentPosition)
            exoPlayer.pause()
        }
        onChangeSettings.launch(
            Intent(this, PlayerSettingsActivity::class.java).apply { putExtra("subtitle", subtitle) }
        )
    }

    // The settings sheet takes window focus while it is open, which "pause when not in focus"
    // would otherwise take for the user leaving; it is the player's own, as the "more" menu is.
    private var settingsSheetOpen = false
    private var settingsSheetClosedAt = 0L

    fun onSettingsSheetClosed() {
        settingsSheetOpen = false
        settingsSheetClosedAt = SystemClock.elapsedRealtime()
    }

    /** Sets up the gesture areas if gestures were switched on; for the sheet's switches. */
    fun refreshGestures() {
        if (::gestures.isInitialized) gestures.refresh()
    }

    /** The OP/ED skip button shows whether auto-skip is on; for the sheet's switch of it. */
    fun refreshSkipButton() = skipStamps.refreshAutoSkip()

    /**
     * Puts the subtitle settings back on the screen after a change: the track switched on or off
     * as "show subtitles" says, and the style redrawn. For the settings sheet, live, and on the
     * way back from the full page.
     */
    fun applySubtitlePrefs() {
        if (!isInitialized) return
        if (!hasExtSubtitles) {
            exoPlayer.currentTracks.groups.forEach { trackGroup ->
                if (trackGroup.type == TRACK_TYPE_TEXT) {
                    onSetTrackGroupOverride(
                        if (PrefManager.getVal(PrefName.Subtitles)) trackGroup else dummyTrack,
                        TRACK_TYPE_TEXT,
                    )
                }
            }
        }
        applySubtitleStyle()
    }

    /** Redraws the subtitles in the current style, leaving the track alone; for the sheet's sliders. */
    fun applySubtitleStyle() {
        if (!::playerView.isInitialized) return
        PlayerSubtitleStyle.applyTo(this, playerView)
        PlayerSubtitleStyle.applyTo(this, customSubtitleView)
    }

    private val onChangeSettings =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { _: ActivityResult ->
            applySubtitlePrefs()
            refreshGestures()
            val before = settingsBefore
            settingsBefore = null
            if (before != null && before != restartPrefValues()) {
                restartForSettings()
            } else if (isInitialized) {
                exoPlayer.play()
            }
        }

    override fun onPlayerError(error: PlaybackException) {
        when (error.errorCode) {
            // Source/content problems where a different server is likely to work: reopen the
            // server picker instead of dead-ending on a frozen player. This also recovers a
            // handoff that auto-selected the sender's server but got a malformed/empty stream
            // when it was re-extracted on this device (e.g. ERROR_CODE_PARSING_CONTAINER_MALFORMED).
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                -> {
                toast("Source Exception : ${error.message}")
                isPlayerPlaying = true
                sourceClick()
            }

            // The platform's own software decoder chokes on some codec profiles (e.g. AAC
            // "Main") that the FFmpeg-based fallback decoder handles fine. Only worth the
            // specific hint when that fallback isn't already on - if it is, this is a
            // different failure and the raw error is more useful than a wrong suggestion.
            PlaybackException.ERROR_CODE_DECODING_FAILED
                -> {
                if (!PrefManager.getVal<Boolean>(PrefName.UseAdditionalCodec)) {
                    toast(
                        getString(
                            R.string.decoding_failed_enable_additional_codec,
                            getString(R.string.use_additional_codec)
                        ),
                        Toast.LENGTH_LONG
                    )
                } else {
                    toast(
                        "Player Error ${error.errorCode} (${error.errorCodeName}) : ${error.message}",
                        Toast.LENGTH_LONG
                    )
                }
                Injekt.get<CrashlyticsInterface>().logException(error)
            }

            else -> {
                toast("Player Error ${error.errorCode} (${error.errorCodeName}) : ${error.message}")
                Injekt.get<CrashlyticsInterface>().logException(error)
            }
        }
    }

    private var isBuffering = true
    private var sleepTimer: PlayerSleepTimer? = null

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == ExoPlayer.STATE_READY) {
            exoPlayer.play()
            if (episodeLength == 0f) {
                episodeLength = exoPlayer.duration.toFloat()
                discordRPC()
            }
        }
        isBuffering = playbackState == Player.STATE_BUFFERING
        // A sleep timer set for the end of this episode stops here, before autoplay moves on.
        if (playbackState == Player.STATE_ENDED && sleepTimer?.onEpisodeEnded() == true) {
            super.onPlaybackStateChanged(playbackState)
            return
        }
        if (playbackState == Player.STATE_ENDED && PrefManager.getVal(PrefName.AutoPlay)) {
            if (inBackground) {
                // Loading the next episode can need the server picker, which can't show out of
                // sight; the episode just ends, and the notification stays to start it again.
            } else if (interacted) {
                exoNext.performClick()
            } else {
                toast(getString(R.string.autoplay_cancelled))
            }
        }
        super.onPlaybackStateChanged(playbackState)
    }

    private fun updateAniProgress() {
        if (!playedThisEpisode) return
        val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
        // Whichever player has the episode: watched on the TV counts as watched.
        val position = (cast.player?.takeIf { cast.isCasting } ?: exoPlayer).currentPosition
        val episodeEnd =
            episodeLength > 0f &&
            position / episodeLength >
                    PrefManager.getVal<Float>(
                        PrefName.WatchPercentage,
                    )
        val episode0 = currentEpisodeIndex == 0 && PrefManager.getVal(PrefName.ChapterZeroPlayer)
        if (!incognito && (episodeEnd || episode0) && Anilist.userid != null
        ) {
            if (PrefManager.getCustomVal(
                    "${media.id}_save_progress",
                    true,
                ) &&
                (if (media.isAdult) PrefManager.getVal(PrefName.UpdateForHPlayer) else true)
            ) {
                if (episode0 && !episodeEnd) {
                    updateProgress(media, "0")
                } else {
                    media.anime!!.selectedEpisode?.apply {
                        updateProgress(media, this)
                    }
                }
            }
        }
    }

    private fun nextEpisode(
        toast: Boolean = true,
        runnable: ((Int) -> Unit),
    ) {
        var isFiller = true
        var i = 1
        while (isFiller) {
            if (episodeArr.size > currentEpisodeIndex + i) {
                isFiller =
                    if (PrefManager.getVal(PrefName.AutoSkipFiller)) {
                        episodes[episodeArr[currentEpisodeIndex + i]]?.filler
                            ?: false
                    } else {
                        false
                    }
                if (!isFiller) runnable.invoke(i)
                i++
            } else {
                if (toast) {
                    toast(getString(R.string.no_next_episode))
                }
                isFiller = false
            }
        }
    }

    @SuppressLint("UnsafeIntentLaunch")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The background playback notification: back to the player as it is.
        if (intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)) return
        finishAndRemoveTask()
        startActivity(intent)
    }

    override fun onDestroy() {
        if (inBackground) {
            inBackground = false
            PlayerBackgroundService.stop(this)
        }
        sleepTimer?.cancel()
        sleepTimer = null
        pip.unregister()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        CoroutineScope(Dispatchers.IO).launch {
            tryWithSuspend(true) {
                extractor?.onVideoStopped(video)
            }
        }

        if (isInitialized) {
            updateAniProgress()
            skipStamps.resetHint()
            releasePlayer()
        }
        if (::cast.isInitialized) cast.release()

        super.onDestroy()
        // Not when this is a restart (restartForSettings recreates the activity): removing the
        // task would take the new player with it.
        if (!isChangingConfigurations) finishAndRemoveTask()
    }

    /** Hands the episode to Web Video Caster, with the selected external subtitle. */
    private fun castExternally() {
        val video = video ?: return
        PlayerCast.openInWebVideoCaster(
            this,
            video,
            // This used to send the video's own url as the subtitle whenever the episode had no
            // external ones but a subtitle was still set.
            subtitle?.file?.url?.takeIf { hasExtSubtitles },
            media.userPreferredName + " : Ep " + episodeTitleArr[currentEpisodeIndex],
            episode.thumb?.url ?: media.cover,
        )
    }

    // Enter PiP Mode
    private fun enterPipMode() {
        wasPlaying = isPlayerPlaying
        if (!pipEnabled) return
        isEnteringPip = true
        pip.enter()
    }

    private fun onPiPChanged(isInPictureInPictureMode: Boolean) {
        isEnteringPip = false
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            orientationListener?.disable()
        } else {
            orientationListener?.enable()
        }
        if (isInitialized) {
            PrefManager.setCustomVal(
                "${media.id}_${episode.number}",
                exoPlayer.currentPosition,
            )
            // If we're exiting PiP, mark timestamp and wait to see if activity regains focus.
            // Resume playback only when the user explicitly returns to the activity (regains focus).
            if (!isInPictureInPictureMode()) {
                pipExitTimestamp = java.lang.System.currentTimeMillis()
                pendingPiPExit = true
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        onPiPChanged(isInPictureInPictureMode)
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        onPiPChanged(isInPictureInPictureMode())
        super.onPictureInPictureUiStateChanged(pipState)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        onPiPChanged(isInPictureInPictureMode)
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    private val keyMap: MutableMap<Int, (() -> Unit)?> =
        mutableMapOf(
            KEYCODE_DPAD_RIGHT to null,
            KEYCODE_DPAD_LEFT to null,
            KEYCODE_SPACE to { exoPlay.performClick() },
            KEYCODE_N to { exoNext.performClick() },
            KEYCODE_B to { exoPrev.performClick() },
        )

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (keyMap.containsKey(event.keyCode)) {
            (event.action == ACTION_UP).also {
                if (isInitialized && it) keyMap[event.keyCode]?.invoke()
            }
        } else {
            super.dispatchKeyEvent(event)
        }

    /** Title and artwork, for the cast device's screen and the playback notification. */
    private fun MediaItem.withEpisodeMetadata(): MediaItem {
        val episodeLine = getString(R.string.episode_num, episode.number) +
            (episode.title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        return buildUpon().setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(media.userPreferredName)
                // The system's media controls read the second line from the artist, and showed
                // "null" with only the subtitle set.
                .setArtist(episodeLine)
                .setSubtitle(episodeLine)
                .setArtworkUri((episode.thumb?.url ?: media.cover)?.toUri())
                .build()
        ).build()
    }

    @SuppressLint("ViewConstructor")
    class ExtendedTimeBar(
        context: Context,
        attrs: AttributeSet?,
    ) : DefaultTimeBar(context, attrs) {
        private var enabled = false
        private var forceDisabled = false

        override fun setEnabled(enabled: Boolean) {
            this.enabled = enabled
            super.setEnabled(!forceDisabled && this.enabled)
        }

        fun setForceDisabled(forceDisabled: Boolean) {
            this.forceDisabled = forceDisabled
            isEnabled = enabled
        }
    }
}

class CustomCastButton : MediaRouteButton {
    private var castCallback: (() -> Unit)? = null

    fun setCastCallback(castCallback: () -> Unit) {
        this.castCallback = castCallback
    }

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr,
    )

    override fun performClick(): Boolean =
        if (PrefManager.getVal(PrefName.UseInternalCast)) {
            super.performClick()
        } else {
            castCallback?.let { it() }
            true
        }
}
