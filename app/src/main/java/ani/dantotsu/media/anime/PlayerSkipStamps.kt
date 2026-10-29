package ani.dantotsu.media.anime

import android.app.Dialog
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import ani.dantotsu.R
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.others.AniSkip
import ani.dantotsu.others.AniSkip.getType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import com.google.android.material.slider.Slider

/**
 * Skipping in the player: the opening, ending and recap markers (AniSkip and the extension's own
 * timestamps) with their skip button and auto-skip, and the fixed "skip N seconds" button.
 * Reaches the player through [Host], which the activity is.
 */
@UnstableApi
class PlayerSkipStamps(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
    private val model: MediaDetailsViewModel,
    private val host: Host,
) {
    interface Host {
        /** Only read once [isInitialized]: it is the activity's lateinit player. */
        val exoPlayer: ExoPlayer
        val isInitialized: Boolean

        /** An ending marker has just started. True when the host took it over (the countdown). */
        fun onEndingReached(): Boolean = false

        /** Every check, twice a second, with the position: for what isn't tied to a marker. */
        fun onPosition(positionMs: Long) {}
    }

    private val exoPlayer: ExoPlayer get() = host.exoPlayer
    private val isInitialized: Boolean get() = host.isInitialized
    private val handler = Handler(Looper.getMainLooper())

    private val exoSkipOpEd: ImageButton = playerView.findViewById(R.id.exo_skip_op_ed)
    private val exoSkip: View = playerView.findViewById(R.id.exo_skip)
    private val exoSkipMini: View = playerView.findViewById(R.id.exo_skip_mini)
    private val exoSkipMiniText: TextView = exoSkipMini.findViewById(R.id.exo_skip_mini_text)
    private val skipTimeButton: View = playerView.findViewById(R.id.exo_skip_timestamp)
    private val skipTimeText: TextView = skipTimeButton.findViewById(R.id.exo_skip_timestamp_text)
    private val timeStampText: TextView = playerView.findViewById(R.id.exo_time_stamp_text)

    /** Whether this episode's timestamps have come in; cleared for each new episode. */
    var loaded = false

    // The skip button's auto-hide: shown, then shrunk to the mini button after a few seconds.
    private var disappeared = false
    private var functionstarted = false

    /** Where this episode's ending starts, in ms, when it has a marker for one. */
    fun endingStartMs(): Long? = model.timeStamps.value
        ?.filter { it.skipType == "ed" || it.skipType == "mixed-ed" }
        ?.minOfOrNull { (it.interval.startTime * 1000).toLong() }

    /**
     * Lets ending markers after [positionMs] fire again, once a seek has gone back before them —
     * so the "Up next" card comes back when the ending is reached a second time.
     */
    fun rearmEndings(positionMs: Long) {
        val after: (AniSkip.Stamp) -> Boolean = {
            (it.skipType == "ed" || it.skipType == "mixed-ed") && it.interval.startTime * 1000 > positionMs
        }
        skippedTimeStamps.removeAll(after)
        offeredEndings.removeAll(after)
    }

    /** Lets the next marker show its full skip button again. */
    fun resetHint() {
        disappeared = false
        functionstarted = false
    }

    /** Shows whether auto-skip is on, after it was switched elsewhere (the settings sheet). */
    fun refreshAutoSkip() {
        exoSkipOpEd.alpha = if (PrefManager.getVal(PrefName.AutoSkipOPED)) 1f else 0.3f
    }

    /** Starts checking the position against the markers, twice a second. */
    fun start() = updateTimeStamp()

    init {
        // TimeStamps
        model.timeStamps.observe(activity) { it ->
            loaded = true
            Logger.log(
                "PlayerSkipStamps: ${it?.joinToString { s -> "${s.skipType} ${s.interval.startTime}-${s.interval.endTime}" } ?: "none"}"
            )
            exoSkipOpEd.visibility =
                if (it != null) {
                    val adGroups =
                        it
                            .flatMap {
                                listOf(
                                    it.interval.startTime.toLong() * 1000,
                                    it.interval.endTime.toLong() * 1000,
                                )
                            }.toLongArray()
                    val playedAdGroups =
                        it
                            .flatMap {
                                listOf(false, false)
                            }.toBooleanArray()
                    playerView.setExtraAdGroupMarkers(adGroups, playedAdGroups)
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }

        exoSkipOpEd.alpha = if (PrefManager.getVal(PrefName.AutoSkipOPED)) 1f else 0.3f
        exoSkipOpEd.setOnClickListener {
            if (PrefManager.getVal(PrefName.AutoSkipOPED)) {
                snackString(activity.getString(R.string.disabled_auto_skip))
                PrefManager.setVal(PrefName.AutoSkipOPED, false)
            } else {
                snackString(activity.getString(R.string.auto_skip))
                PrefManager.setVal(PrefName.AutoSkipOPED, true)
            }
            exoSkipOpEd.alpha = if (PrefManager.getVal(PrefName.AutoSkipOPED)) 1f else 0.3f
        }


        // Skip Time Button
        var skipTime = PrefManager.getVal<Int>(PrefName.SkipTime)
        if (skipTime > 0) {
            exoSkip.findViewById<TextView>(R.id.exo_skip_time).text = skipTime.toString()
            exoSkip.setOnClickListener {
                if (isInitialized) {
                    exoPlayer.seekTo(exoPlayer.currentPosition + skipTime * 1000)
                }
            }
            exoSkip.setOnLongClickListener {
                val dialog = Dialog(activity, R.style.MyPopup)
                dialog.setContentView(R.layout.item_seekbar_dialog)
                dialog.setCancelable(true)
                dialog.setCanceledOnTouchOutside(true)
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                if (skipTime <= 120) {
                    dialog.findViewById<Slider>(R.id.seekbar).value = skipTime.toFloat()
                } else {
                    dialog.findViewById<Slider>(R.id.seekbar).value = 120f
                }
                dialog.findViewById<Slider>(R.id.seekbar).addOnChangeListener { _, value, _ ->
                    skipTime = value.toInt()
                    // saveData(player, settings)
                    PrefManager.setVal(PrefName.SkipTime, skipTime)
                    playerView.findViewById<TextView>(R.id.exo_skip_time).text =
                        skipTime.toString()
                    dialog.findViewById<TextView>(R.id.seekbar_value).text =
                        skipTime.toString()
                }
                dialog
                    .findViewById<Slider>(R.id.seekbar)
                    .addOnSliderTouchListener(
                        object : Slider.OnSliderTouchListener {
                            override fun onStartTrackingTouch(slider: Slider) {}

                            override fun onStopTrackingTouch(slider: Slider) {
                                dialog.dismiss()
                            }
                        },
                    )
                dialog.findViewById<TextView>(R.id.seekbar_title).text =
                    activity.getString(R.string.skip_time)
                dialog.findViewById<TextView>(R.id.seekbar_value).text =
                    skipTime.toString()
                @Suppress("DEPRECATION")
                dialog.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                dialog.show()
                true
            }
        } else {
            exoSkip.visibility = View.GONE
        }
    }

    private var currentTimeStamp: AniSkip.Stamp? = null
    private var skippedTimeStamps: MutableList<AniSkip.Stamp> = mutableListOf()

    /** Endings [Host.onEndingReached] has been called for, so the next-episode card comes up once. */
    private val offeredEndings = mutableSetOf<AniSkip.Stamp>()

    private fun updateTimeStamp() {
        if (activity.isDestroyed) return
        if (isInitialized) {
            host.onPosition(exoPlayer.currentPosition)
            val playerCurrentTime = exoPlayer.currentPosition / 1000
            currentTimeStamp =
                model.timeStamps.value?.find { timestamp ->
                    timestamp.interval.startTime < playerCurrentTime &&
                            playerCurrentTime < (timestamp.interval.endTime - 1)
                }

            val new = currentTimeStamp
            timeStampText.text =
                if (new != null) {
                    fun disappearSkip() {
                        functionstarted = true
                        skipTimeButton.visibility = View.VISIBLE
                        exoSkip.visibility = View.GONE
                        skipTimeText.text = new.skipType.getType()
                        skipTimeButton.setOnClickListener {
                            exoPlayer.seekTo((new.interval.endTime * 1000).toLong())
                        }
                        var timer: CountDownTimer? = null

                        fun cancelTimer() {
                            timer?.cancel()
                            timer = null
                            return
                        }
                        timer =
                            object : CountDownTimer(5000, 1000) {
                                override fun onTick(millisUntilFinished: Long) {
                                    if (new == null) {
                                        skipTimeButton.visibility = View.GONE
                                        exoSkip.isVisible =
                                            PrefManager.getVal<Int>(PrefName.SkipTime) > 0
                                        exoSkipMini.visibility = View.GONE
                                        disappeared = false
                                        functionstarted = false
                                        cancelTimer()
                                    }
                                }

                                override fun onFinish() {
                                    skipTimeButton.visibility = View.GONE
                                    exoSkip.isVisible =
                                        PrefManager.getVal<Int>(PrefName.SkipTime) > 0
                                    // show small persistent skip icon next to skip time button
                                    exoSkipMiniText.text = new.skipType.getType()
                                    exoSkipMini.visibility = View.VISIBLE
                                    exoSkipMini.setOnClickListener {
                                        exoPlayer.seekTo((new.interval.endTime * 1000).toLong())
                                    }
                                    disappeared = true
                                    functionstarted = false
                                    cancelTimer()
                                }
                            }
                        timer?.start()
                    }
                    if (PrefManager.getVal(PrefName.ShowTimeStampButton)) {
                        if (!functionstarted && !disappeared && PrefManager.getVal(PrefName.AutoHideTimeStamps)) {
                            disappearSkip()
                        } else if (!PrefManager.getVal<Boolean>(PrefName.AutoHideTimeStamps)) {
                            skipTimeButton.visibility = View.VISIBLE
                            exoSkip.visibility = View.GONE
                            exoSkipMini.visibility = View.GONE
                            skipTimeText.text = new.skipType.getType()
                            skipTimeButton.setOnClickListener {
                                exoPlayer.seekTo((new.interval.endTime * 1000).toLong())
                            }
                        }
                    }
                    // AniSkip files an ending that overlaps the episode's last scene as "mixed-ed".
                    // Asked once per ending, whatever the answer: an "Up next" offer the user
                    // cancelled must not come straight back on the next check.
                    val ending = new.skipType == "ed" || new.skipType == "mixed-ed"
                    val tookOver = ending && offeredEndings.add(new) && host.onEndingReached()
                    if (tookOver) {
                        skippedTimeStamps.add(new)
                    } else if (PrefManager.getVal(PrefName.AutoSkipOPED) &&
                        (new.skipType == "op" || new.skipType == "ed") &&
                        !skippedTimeStamps.contains(new)
                    ) {
                        exoPlayer.seekTo((new.interval.endTime * 1000).toLong())
                        skippedTimeStamps.add(new)
                    }
                    if (PrefManager.getVal(PrefName.AutoSkipRecap) &&
                        new.skipType == "recap" &&
                        !skippedTimeStamps.contains(
                            new,
                        )
                    ) {
                        exoPlayer.seekTo((new.interval.endTime * 1000).toLong())
                        skippedTimeStamps.add(new)
                    }
                    new.skipType.getType()
                } else {
                    disappeared = false
                    functionstarted = false
                    skipTimeButton.visibility = View.GONE
                    exoSkip.isVisible = PrefManager.getVal<Int>(PrefName.SkipTime) > 0
                    exoSkipMini.visibility = View.GONE
                    ""
                }
        }
        handler.postDelayed({
            updateTimeStamp()
        }, 500)
    }
}
