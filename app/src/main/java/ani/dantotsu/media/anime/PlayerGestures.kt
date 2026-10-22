package ani.dantotsu.media.anime

import android.animation.ObjectAnimator
import android.graphics.drawable.Animatable
import android.media.AudioManager
import android.media.AudioManager.STREAM_MUSIC
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.math.MathUtils.clamp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import ani.dantotsu.GesturesListener
import ani.dantotsu.R
import ani.dantotsu.brightnessConverter
import ani.dantotsu.circularReveal
import ani.dantotsu.getCurrentBrightnessValue
import ani.dantotsu.hideSystemBars
import ani.dantotsu.others.ResettableTimer
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.slider.Slider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.TimerTask
import kotlin.math.roundToInt

/**
 * The player's touch controls: tapping to show and hide the controller, double-tap and D-pad
 * seeking with its ripple, the brightness and volume drags on either side, long-press for double
 * speed and the sideways drag to seek. Reaches the player through [Host], which the activity is.
 */
@UnstableApi
class PlayerGestures(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
    private val keyMap: MutableMap<Int, (() -> Unit)?>,
    private val host: Host,
) {
    interface Host {
        /** Only read once [isInitialized]: it is the activity's lateinit player. */
        val exoPlayer: ExoPlayer
        val isInitialized: Boolean
        /** The lock button is on: gestures do nothing. */
        val locked: Boolean
        fun checkNotch()
    }

    private val exoPlayer: ExoPlayer get() = host.exoPlayer
    private val isInitialized: Boolean get() = host.isInitialized
    private val locked: Boolean get() = host.locked
    private val handler = Handler(Looper.getMainLooper())

    private val exoBrightness: Slider = playerView.findViewById(R.id.exo_brightness)
    private val exoVolume: Slider = playerView.findViewById(R.id.exo_volume)
    private val exoBrightnessCont: View = playerView.findViewById(R.id.exo_brightness_cont)
    private val exoVolumeCont: View = playerView.findViewById(R.id.exo_volume_cont)

    private var isSeeking = false
    private var isFastForwarding = false

    fun setUp() {
        val audioManager = activity.applicationContext.getSystemService(AppCompatActivity.AUDIO_SERVICE) as AudioManager
        val gestureSpeed = (300 * PrefManager.getVal<Float>(PrefName.AnimationSpeed)).toLong()
        // Player UI Visibility Handler
        val brightnessRunnable =
            Runnable {
                if (exoBrightnessCont.alpha == 1f) {
                    activity.lifecycleScope.launch {
                        ObjectAnimator
                            .ofFloat(exoBrightnessCont, "alpha", 1f, 0f)
                            .setDuration(gestureSpeed)
                            .start()
                        delay(gestureSpeed)
                        exoBrightnessCont.visibility = View.GONE
                        host.checkNotch()
                    }
                }
            }
        val volumeRunnable =
            Runnable {
                if (exoVolumeCont.alpha == 1f) {
                    activity.lifecycleScope.launch {
                        ObjectAnimator
                            .ofFloat(exoVolumeCont, "alpha", 1f, 0f)
                            .setDuration(gestureSpeed)
                            .start()
                        delay(gestureSpeed)
                        exoVolumeCont.visibility = View.GONE
                        host.checkNotch()
                    }
                }
            }
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.GONE) {
                    activity.hideSystemBars()
                    brightnessRunnable.run()
                    volumeRunnable.run()
                }
            },
        )
        val overshoot = AnimationUtils.loadInterpolator(activity, R.anim.over_shoot)
        val controllerDuration = (300 * PrefManager.getVal<Float>(PrefName.AnimationSpeed)).toLong()

        fun handleController() {
            if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) !activity.isInPictureInPictureMode else true) {
                if (playerView.isControllerFullyVisible) {
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_controller),
                            "alpha",
                            1f,
                            0f,
                        ).setDuration(controllerDuration)
                        .start()
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_bottom_cont),
                            "translationY",
                            0f,
                            128f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_timeline_cont),
                            "translationY",
                            0f,
                            128f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_top_cont),
                            "translationY",
                            0f,
                            -128f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                    playerView.postDelayed({ playerView.hideController() }, controllerDuration)
                } else {
                    host.checkNotch()
                    playerView.showController()
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_controller),
                            "alpha",
                            0f,
                            1f,
                        ).setDuration(controllerDuration)
                        .start()
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_bottom_cont),
                            "translationY",
                            128f,
                            0f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_timeline_cont),
                            "translationY",
                            128f,
                            0f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_top_cont),
                            "translationY",
                            -128f,
                            0f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                }
            }
        }

        playerView.findViewById<View>(R.id.exo_full_area).setOnClickListener {
            handleController()
        }

        val rewindText = playerView.findViewById<TextView>(R.id.exo_fast_rewind_anim)
        val forwardText = playerView.findViewById<TextView>(R.id.exo_fast_forward_anim)
        val fastForwardCard = playerView.findViewById<View>(R.id.exo_fast_forward)
        val fastRewindCard = playerView.findViewById<View>(R.id.exo_fast_rewind)

        // Seeking
        val seekTimerF = ResettableTimer()
        val seekTimerR = ResettableTimer()
        var seekTimesF = 0
        var seekTimesR = 0

        fun seek(
            forward: Boolean,
            event: MotionEvent? = null,
        ) {
            val seekTime = PrefManager.getVal<Int>(PrefName.SeekTime)
            val (card, text) =
                if (forward) {
                    val text = "+${seekTime * ++seekTimesF}"
                    forwardText.text = text
                    handler.post { exoPlayer.seekTo(exoPlayer.currentPosition + seekTime * 1000) }
                    fastForwardCard to forwardText
                } else {
                    val text = "-${seekTime * ++seekTimesR}"
                    rewindText.text = text
                    handler.post { exoPlayer.seekTo(exoPlayer.currentPosition - seekTime * 1000) }
                    fastRewindCard to rewindText
                }

            //region Double Tap Animation
            val showCardAnim = ObjectAnimator.ofFloat(card, "alpha", 0f, 1f).setDuration(300)
            val showTextAnim = ObjectAnimator.ofFloat(text, "alpha", 0f, 1f).setDuration(150)

            fun startAnim() {
                showTextAnim.start()

                (text.compoundDrawables[1] as Animatable).apply {
                    if (!isRunning) start()
                }

                if (!isSeeking && event != null) {
                    playerView.hideController()
                    card.circularReveal(event.x.toInt(), event.y.toInt(), !forward, 800)
                    showCardAnim.start()
                }
            }

            fun stopAnim() {
                handler.post {
                    showCardAnim.cancel()
                    showTextAnim.cancel()
                    ObjectAnimator.ofFloat(card, "alpha", card.alpha, 0f).setDuration(150).start()
                    ObjectAnimator.ofFloat(text, "alpha", 1f, 0f).setDuration(150).start()
                }
            }
            //endregion

            startAnim()

            isSeeking = true

            if (forward) {
                seekTimerR.reset(
                    object : TimerTask() {
                        override fun run() {
                            isSeeking = false
                            stopAnim()
                            seekTimesF = 0
                        }
                    },
                    850,
                )
            } else {
                seekTimerF.reset(
                    object : TimerTask() {
                        override fun run() {
                            isSeeking = false
                            stopAnim()
                            seekTimesR = 0
                        }
                    },
                    850,
                )
            }
        }

        playerView.findViewById<View>(R.id.exo_fast_forward_button_cont).visibility = View.VISIBLE
        playerView.findViewById<View>(R.id.exo_fast_rewind_button_cont).visibility = View.VISIBLE
        playerView.findViewById<ImageButton>(R.id.exo_fast_forward_button).setOnClickListener {
            if (isInitialized) {
                exoPlayer.seekForward()
            }
        }
        playerView.findViewById<ImageButton>(R.id.exo_fast_rewind_button).setOnClickListener {
            if (isInitialized) {
                exoPlayer.seekBack()
            }
        }

        keyMap[KEYCODE_DPAD_RIGHT] = { seek(true) }
        keyMap[KEYCODE_DPAD_LEFT] = { seek(false) }

        // Screen Gestures
        if (PrefManager.getVal<Boolean>(PrefName.Gestures) || PrefManager.getVal<Boolean>(PrefName.DoubleTap)) {
            fun doubleTap(
                forward: Boolean,
                event: MotionEvent,
            ) {
                if (!locked && isInitialized && PrefManager.getVal<Boolean>(PrefName.DoubleTap)) {
                    seek(forward, event)
                }
            }

            // Brightness
            var brightnessTimer = Timer()
            exoBrightnessCont.visibility = View.GONE

            fun brightnessHide() {
                brightnessTimer.cancel()
                brightnessTimer.purge()
                val timerTask: TimerTask =
                    object : TimerTask() {
                        override fun run() {
                            handler.post(brightnessRunnable)
                        }
                    }
                brightnessTimer = Timer()
                brightnessTimer.schedule(timerTask, 3000)
            }
            exoBrightness.value = (getCurrentBrightnessValue(activity) * 10f)

            exoBrightness.addOnChangeListener { _, value, _ ->
                val lp = activity.window.attributes
                lp.screenBrightness =
                    brightnessConverter((value.takeIf { !it.isNaN() } ?: 0f) / 10, false)
                activity.window.attributes = lp
                brightnessHide()
            }

            // Volume
            var volumeTimer = Timer()
            exoVolumeCont.visibility = View.GONE

            val volumeMax = audioManager.getStreamMaxVolume(STREAM_MUSIC)
            exoVolume.value = audioManager.getStreamVolume(STREAM_MUSIC).toFloat() / volumeMax * 10

            fun volumeHide() {
                volumeTimer.cancel()
                volumeTimer.purge()
                val timerTask: TimerTask =
                    object : TimerTask() {
                        override fun run() {
                            handler.post(volumeRunnable)
                        }
                    }
                volumeTimer = Timer()
                volumeTimer.schedule(timerTask, 3000)
            }
            exoVolume.addOnChangeListener { _, value, _ ->
                val volume = ((value.takeIf { !it.isNaN() } ?: 0f) / 10 * volumeMax).roundToInt()
                audioManager.setStreamVolume(STREAM_MUSIC, volume, 0)
                volumeHide()
            }
            val fastForward = playerView.findViewById<TextView>(R.id.exo_fast_forward_text)

            fun fastForward() {
                isFastForwarding = true
                exoPlayer.setPlaybackSpeed(exoPlayer.playbackParameters.speed * 2)
                fastForward.visibility = View.VISIBLE
                val speedText = "${exoPlayer.playbackParameters.speed}x"
                fastForward.text = speedText
            }

            fun stopFastForward() {
                if (isFastForwarding) {
                    isFastForwarding = false
                    exoPlayer.setPlaybackSpeed(exoPlayer.playbackParameters.speed / 2)
                    fastForward.visibility = View.GONE
                }
            }

            // Swipe to seek: a sideways drag on either side previews a seek in the same pill the
            // 2x label uses, and seeks when the finger lifts. A full-width swipe is two minutes.
            var swipeSeekFrom: Long? = null
            var swipeSeekDx = 0f
            fun clock(ms: Long): String {
                val s = ms / 1000
                return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
                else "%d:%02d".format(s / 60, s % 60)
            }
            fun swipeSeekTarget(): Long {
                val from = swipeSeekFrom ?: return 0L
                val width = playerView.width.takeIf { it > 0 } ?: return from
                val offset = (swipeSeekDx / width * SWIPE_SEEK_FULL_WIDTH_MS).toLong()
                val duration = exoPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                return (from + offset).coerceIn(0L, duration)
            }
            fun swipeSeek(distanceX: Float) {
                if (locked || !isInitialized || isFastForwarding || !PrefManager.getVal<Boolean>(PrefName.Gestures)) return
                if (swipeSeekFrom == null) {
                    swipeSeekFrom = exoPlayer.currentPosition
                    swipeSeekDx = 0f
                }
                // distanceX is the last position minus the new one: a drag to the right is negative.
                swipeSeekDx -= distanceX
                val target = swipeSeekTarget()
                val delta = target - (swipeSeekFrom ?: target)
                val sign = if (delta < 0) "−" else "+"
                fastForward.text = "$sign${clock(kotlin.math.abs(delta))} · ${clock(target)}"
                fastForward.visibility = View.VISIBLE
            }
            fun finishSwipeSeek(commit: Boolean) {
                if (swipeSeekFrom == null) return
                if (commit) exoPlayer.seekTo(swipeSeekTarget())
                swipeSeekFrom = null
                fastForward.visibility = View.GONE
            }
            // A touch that ends any way but a lift (cancelled by a system gesture, say) must still
            // drop the 2x speed and a half-made seek, or playback stays fast.
            fun endTouch(event: MotionEvent) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP -> { stopFastForward(); finishSwipeSeek(commit = true) }
                    MotionEvent.ACTION_CANCEL -> { stopFastForward(); finishSwipeSeek(commit = false) }
                }
            }

            // FastRewind (Left Panel)
            val fastRewindDetector =
                GestureDetector(
                    activity,
                    object : GesturesListener() {
                        override val lockScrollAxis = true

                        override fun onScrollXClick(y: Float) = swipeSeek(y)

                        override fun onLongClick(event: MotionEvent) {
                            if (PrefManager.getVal(PrefName.FastForward)) fastForward()
                        }

                        override fun onDoubleClick(event: MotionEvent) {
                            doubleTap(false, event)
                        }

                        override fun onScrollYClick(y: Float) {
                            if (!locked && PrefManager.getVal(PrefName.Gestures)) {
                                exoBrightness.value = clamp(exoBrightness.value + y / 100, 0f, 10f)
                                if (exoBrightnessCont.visibility != View.VISIBLE) {
                                    exoBrightnessCont.visibility = View.VISIBLE
                                }
                                exoBrightnessCont.alpha = 1f
                            }
                        }

                        override fun onSingleClick(event: MotionEvent) =
                            if (isSeeking) doubleTap(false, event) else handleController()
                    },
                )
            val rewindArea = playerView.findViewById<View>(R.id.exo_rewind_area)
            rewindArea.isClickable = true
            rewindArea.setOnTouchListener { v, event ->
                fastRewindDetector.onTouchEvent(event)
                endTouch(event)
                v.performClick()
                true
            }

            // FastForward (Right Panel)
            val fastForwardDetector =
                GestureDetector(
                    activity,
                    object : GesturesListener() {
                        override val lockScrollAxis = true

                        override fun onScrollXClick(y: Float) = swipeSeek(y)

                        override fun onLongClick(event: MotionEvent) {
                            if (PrefManager.getVal(PrefName.FastForward)) fastForward()
                        }

                        override fun onDoubleClick(event: MotionEvent) {
                            doubleTap(true, event)
                        }

                        override fun onScrollYClick(y: Float) {
                            if (!locked && PrefManager.getVal(PrefName.Gestures)) {
                                exoVolume.value = clamp(exoVolume.value + y / 100, 0f, 10f)
                                if (exoVolumeCont.visibility != View.VISIBLE) {
                                    exoVolumeCont.visibility = View.VISIBLE
                                }
                                exoVolumeCont.alpha = 1f
                            }
                        }

                        override fun onSingleClick(event: MotionEvent) =
                            if (isSeeking) doubleTap(true, event) else handleController()
                    },
                )
            val forwardArea = playerView.findViewById<View>(R.id.exo_forward_area)
            forwardArea.isClickable = true
            forwardArea.setOnTouchListener { v, event ->
                fastForwardDetector.onTouchEvent(event)
                endTouch(event)
                v.performClick()
                true
            }
        }
    }

    companion object {
        /** How far a swipe across the whole player width seeks. */
        private const val SWIPE_SEEK_FULL_WIDTH_MS = 120_000f
    }
}
