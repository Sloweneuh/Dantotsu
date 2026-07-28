package ani.dantotsu.media.screenshot

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.effect.OverlayEffect
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.media.anime.ExoplayerView
import ani.dantotsu.databinding.BottomSheetClipBinding
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.StoragePermissions.Companion.downloadsPermission
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import io.github.peerless2012.ass.AssRender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Review/compose sheet for an anime clip, the moving-picture counterpart to
 * [ScreenshotDialogFragment].
 *
 * The player captures a window ending at the moment the user tapped — the last 30s by default — and
 * this sheet is where that window gets trimmed down and dressed up. The preview loops the selected
 * range live, so trimming is direct manipulation rather than a guess.
 *
 * Nothing is rendered until Save/Share: the card here is a WYSIWYG stand-in, and on export the same
 * layout is drawn to a bitmap with a hole where the video goes, which [ClipExporter] composites the
 * real frames into. See [buildCard].
 */
@OptIn(UnstableApi::class)
class ClipDialogFragment : CaptureSheetFragment() {

    private var _binding: BottomSheetClipBinding? = null
    private val binding get() = _binding!!

    /** The stream, subtitles and data source needed to re-cut the clip; see [Payload]. */
    private var payload: Payload? = null

    /** Loops the trimmed range in the preview. */
    private var previewPlayer: ExoPlayer? = null

    /** libass renderer driving the preview's burned-in subtitles, if the track is ASS. */
    private var previewAssRender: AssRender? = null

    /** Preview audio starts off — the sheet loops a few seconds over and over. */
    private var previewMuted = true

    /** Suspends the loop while a trim handle or the seek bar is being dragged. */
    private var scrubbing = false

    /** When the preview was last seeked; see [seekPreview]. */
    private var lastSeekAt = 0L

    /** Frames already seen, so scrubbing shows one instantly. */
    private var storyboard: ClipStoryboard? = null
    private var primed = false

    private var thumbnailJob: Job? = null
    private var gifPreviewJob: Job? = null

    /** Identifies the GIF currently previewed, so an unchanged clip isn't re-encoded. */
    private var gifPreviewKey: String? = null
    private var gifPreviewFile: File? = null

    private var exportJob: Job? = null

    /** Trim bounds, relative to the start of the captured window. */
    private var trimStartMs = 0L
    private var trimEndMs = 0L

    private var videoWidth = 0
    private var videoHeight = 0

    private var selectedTitle: String = ""

    var onDismissed: (() -> Unit)? = null

    private val title get() = arguments?.getString(ARG_TITLE).orEmpty()
    private val titleOptions get() = arguments?.getStringArrayList(ARG_TITLE_OPTIONS).orEmpty()
    private val coverUrl get() = arguments?.getString(ARG_COVER)
    private val numberLabel get() = arguments?.getString(ARG_NUMBER).orEmpty()
    private val sourceLabel get() = arguments?.getString(ARG_SOURCE)
    private val windowStartMs get() = arguments?.getLong(ARG_WINDOW_START) ?: 0L
    private val windowEndMs get() = arguments?.getLong(ARG_WINDOW_END) ?: 0L
    private val windowDurationMs get() = (windowEndMs - windowStartMs).coerceAtLeast(1L)

    /** The card and trim controls. */
    private val stage get() = binding.clipStage

    /** The scrolling options column. */
    private val options get() = binding.clipOptions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        payload = pending
        pending = null
        selectedTitle = title
        trimEndMs = windowDurationMs
        storyboard = ClipStoryboard(windowDurationMs)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetClipBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (payload == null) {
            snackString(getString(R.string.clip_failed))
            dismissAllowingStateLoss()
            return
        }

        stage.clipTitle.text = selectedTitle
        stage.clipDate.text =
            SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date())
        stage.clipSource.text = sourceLabel.orEmpty()
        stage.clipUsername.text = Anilist.username.orEmpty()

        // Seeded from the shared capture defaults — a clip card is the same card a screenshot uses.
        options.chipMediaInfo.isChecked = PrefManager.getVal(PrefName.ScreenshotShowMediaInfo)
        options.chipDate.isChecked = PrefManager.getVal(PrefName.ScreenshotShowDate)
        options.chipSource.isChecked = PrefManager.getVal(PrefName.ScreenshotShowSource)
        options.chipUserInfo.isChecked = PrefManager.getVal(PrefName.ScreenshotShowUserInfo)
        options.chipAppIcon.isChecked = PrefManager.getVal(PrefName.ScreenshotShowAppLogo)
        options.chipFrame.isChecked = PrefManager.getVal(PrefName.ScreenshotShowFrame)
        options.chipSubtitles.isChecked = PrefManager.getVal(PrefName.ClipBurnSubtitles)

        // Rounded corners are a stills-only idea: on moving footage the curve reads as the video
        // being clipped rather than framed, so the option is hidden here rather than left to do
        // something unwanted. The shared default still applies to screenshots.
        options.chipRounded.isVisible = false
        options.chipRounded.isChecked = false

        val loggedIn = !Anilist.username.isNullOrEmpty()
        options.chipUserInfo.isEnabled = loggedIn
        if (!loggedIn) options.chipUserInfo.isChecked = false

        // Subtitles can only be burned in if there were any to record.
        val hasSubtitles = payload?.subtitles != null
        options.chipSubtitles.isEnabled = hasSubtitles
        if (!hasSubtitles) options.chipSubtitles.isChecked = false

        options.clipFormatGroup.check(
            if (PrefManager.getVal(PrefName.ClipExportAsGif)) R.id.clipFormatGif
            else R.id.clipFormatVideo
        )

        // The end of the window is the moment the clip was taken, so its frame arrived with the
        // payload — no seeking, and correct from the first draw.
        payload?.endFrame?.let {
            stage.clipThumbEnd.setImageBitmap(it)
            storyboard?.put(windowDurationMs, it)
        }

        loadRemoteImages()
        setupTitleSelector()
        setupGifSettings()
        setupTrim()
        setupSeekBar()
        setupPreview()

        listOf(
            options.chipMediaInfo, options.chipDate, options.chipSource, options.chipUserInfo,
            options.chipAppIcon, options.chipFrame
        ).forEach { it.setOnCheckedChangeListener { _, _ -> applyLayout() } }
        // Burning subtitles changes the pixels, so the GIF preview has to be rebuilt for it.
        options.chipSubtitles.setOnCheckedChangeListener { _, _ -> onOutputChanged() }
        options.clipCaptionInput.doOnTextChanged { _, _, _, _ -> applyLayout() }

        options.clipFormatGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) onFormatChanged()
        }

        stage.clipMute.setOnClickListener {
            previewMuted = !previewMuted
            applyPreviewVolume()
        }

        binding.clipBar.clipSave.setOnClickListener { export(share = false) }
        binding.clipBar.clipShare.setOnClickListener { export(share = true) }
        binding.clipProgressCancel.setOnClickListener { exportJob?.cancel() }

        applyLayout()
    }

    // region preview

    /**
     * Prepares the preview on the captured window rather than the trimmed range, so dragging the
     * trim handles only moves the loop points instead of re-preparing the player each time.
     */
    private fun setupPreview() {
        val payload = payload ?: return
        val player = ExoPlayer.Builder(requireContext())
            .apply {
                payload.dataSourceFactory?.let {
                    setMediaSourceFactory(DefaultMediaSourceFactory(it))
                }
            }
            .build()
        previewPlayer = player

        stage.clipPreview.player = player
        player.setMediaItem(
            payload.mediaItem.buildUpon()
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(windowStartMs)
                        .setEndPositionMs(windowEndMs)
                        .setStartsAtKeyFrame(false)
                        .build()
                )
                .build()
        )
        attachPreviewSubtitles(player)
        applyPreviewVolume()
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width <= 0 || size.height <= 0) return
                videoWidth = size.width
                videoHeight = size.height
                sizePreviewToVideo()
            }

            /** The first frame on screen is the cue that seeking will now produce visible results. */
            override fun onRenderedFirstFrame() {
                if (primed) return
                primed = true
                primeThumbnails()
            }
        })
        player.prepare()
        scheduleLoopTick()
    }

    /**
     * Gives the preview the same subtitles the export will burn in.
     *
     * Text cues need nothing — the preview loads the same tracks and [androidx.media3.ui.PlayerView]
     * draws them. ASS is the awkward one: libass draws through a video effect rather than a view,
     * and this player has no libass attached, so the same overlay the exporter uses is installed
     * here too, driven by the player's clock instead of frame timestamps.
     */
    private fun attachPreviewSubtitles(player: ExoPlayer) {
        val render = (payload?.subtitles as? Subtitles.Ass)?.createRender() ?: return
        previewAssRender = render
        // Media3's own parser would emit these cues as plain text on top of what libass draws.
        stage.clipPreview.subtitleView?.isVisible = false
        // ExoPlayer requires effects to be set before prepare().
        player.setVideoEffects(
            listOf(
                OverlayEffect(
                    listOf(
                        ClipSubtitleOverlay(
                            time = ClipSubtitleOverlay.followingPlayer(windowStartMs) {
                                previewPlayer?.currentPosition ?: 0L
                            },
                            videoWidth = 0, // no rescaling here, so the frame is the video
                            videoHeight = 0,
                            assRender = render,
                            cues = emptyList(),
                            textStyle = null,
                        )
                    )
                )
            )
        )
    }

    private fun applyPreviewVolume() {
        previewPlayer?.volume = if (previewMuted) 0f else 1f
        _binding?.clipStage?.clipMute?.setImageResource(
            if (previewMuted) R.drawable.ic_round_volume_off_24
            else R.drawable.ic_round_volume_up_24
        )
    }

    /**
     * Matches the preview's height to the video's aspect, so the card's hole isn't letterboxed and
     * the exported frames land in it exactly.
     *
     * A full-width 16:9 preview is taller than a landscape screen, so when it doesn't fit the card
     * is narrowed until it does rather than the preview being squashed — squashing would put black
     * bars inside the punched hole and bake them into the export. The clip's output resolution is
     * unaffected, since [buildCard] derives its scale from the source video rather than from how
     * large the preview happens to be drawn.
     */
    private fun sizePreviewToVideo() {
        val binding = _binding ?: return
        val preview = binding.clipStage.clipPreview
        if (videoWidth <= 0 || videoHeight <= 0) return

        val available = binding.clipStage.clipCard.let { it.width - it.paddingLeft - it.paddingRight }
        if (available <= 0) {
            // The video size can land before the sheet has been measured; retry once it has.
            preview.doOnNextLayout { sizePreviewToVideo() }
            return
        }

        val maxHeight = (binding.root.height * MAX_PREVIEW_HEIGHT_FRACTION).toInt()
        var width = available
        var height = width * videoHeight / videoWidth
        if (maxHeight > 0 && height > maxHeight) {
            height = maxHeight
            width = height * videoWidth / videoHeight
        }

        preview.updateLayoutParams { this.height = height }
        binding.clipStage.clipCard.updateLayoutParams {
            this.width = if (width < available) {
                width + binding.clipStage.clipCard.paddingLeft +
                    binding.clipStage.clipCard.paddingRight
            } else {
                ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    /** Keeps playback inside the trimmed range and drives the position readout. */
    private fun scheduleLoopTick() {
        val binding = _binding ?: return
        binding.root.postDelayed(object : Runnable {
            override fun run() {
                val player = previewPlayer
                val stage = _binding?.clipStage
                if (player != null && stage != null) {
                    val position = player.currentPosition
                    if (!scrubbing) {
                        if (position >= trimEndMs || position < trimStartMs - LOOP_SLACK_MS) {
                            seekPreview(trimStartMs)
                        }
                        stage.clipSeek.value =
                            position.toFloat().coerceIn(stage.clipSeek.valueFrom, stage.clipSeek.valueTo)
                        // Watching the preview fills the storyboard for free: these frames are
                        // already decoded and on screen, so scrubbing over anything that has been
                        // played is answered without touching the player at all. Only while
                        // playback is actually running and well clear of the last seek, though —
                        // otherwise the position and the picture disagree.
                        val settled =
                            SystemClock.uptimeMillis() - lastSeekAt > CAPTURE_SETTLE_MS
                        if (player.isPlaying && settled && storyboard?.has(position) == false) {
                            captureFrame(position, atStart = null)
                        }
                    }
                    stage.clipPreviewPosition.text =
                        ScreenshotUtil.formatTimestamp(windowStartMs + position)
                }
                _binding?.root?.postDelayed(this, LOOP_TICK_MS)
            }
        }, LOOP_TICK_MS)
    }

    /**
     * The preview scrubber. It spans the trimmed range, not the whole captured window, so it
     * actually reaches its end and gets shorter as the clip is trimmed down — see [updateSeekBounds].
     */
    private fun setupSeekBar() {
        stage.clipSeek.apply {
            valueFrom = 0f
            valueTo = windowDurationMs.toFloat()
            value = 0f
            setLabelFormatter { ScreenshotUtil.formatTimestamp(windowStartMs + it.toLong()) }
            updateSeekBounds()
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) seekPreview(value.toLong())
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    scrubbing = true
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    scrubbing = false
                }
            })
        }
    }

    /**
     * Wires the range up so either end can be dragged. While a handle is held the preview follows
     * that handle instead of looping, so the exact frame being landed on is visible at full size,
     * and the thumbnail beside the slider is refreshed to match.
     */
    private fun setupTrim() {
        val duration = windowDurationMs.toFloat()
        var movedStart = true
        stage.clipRange.apply {
            valueFrom = 0f
            valueTo = duration
            setValues(0f, duration)
            // The handle's bubble should say where in the episode it is, not how many
            // milliseconds into an internal window.
            setLabelFormatter { ScreenshotUtil.formatTimestamp(windowStartMs + it.toLong()) }
            addOnChangeListener { slider, _, fromUser ->
                val values = slider.values
                val newStart = values.getOrElse(0) { 0f }.toLong()
                val newEnd = values.getOrElse(1) { duration }.toLong()
                // Whichever end actually moved is the one to follow.
                movedStart = newStart != trimStartMs
                trimStartMs = newStart
                trimEndMs = newEnd
                updateSeekBounds()
                updateRangeLabel()
                // Only the interval text depends on the trim, so avoid a full card re-layout on
                // every frame of the drag.
                updateIntervalText()
                if (fromUser) {
                    // Once the storyboard exists it carries the live feedback, so the player is
                    // left alone until the drag ends — seeking it per touch event is the
                    // expensive part, and it's exactly what made scrubbing feel sluggish.
                    if (storyboard == null) {
                        seekPreview(if (movedStart) trimStartMs else trimEndMs)
                    }
                    requestThumbnail(movedStart, resumeAfter = false)
                }
            }
            addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: RangeSlider) {
                    thumbnailJob?.cancel()
                    scrubbing = true
                    previewPlayer?.pause()
                }

                override fun onStopTrackingTouch(slider: RangeSlider) {
                    requestThumbnail(movedStart, resumeAfter = true)
                    onOutputChanged()
                }
            })
        }
        updateRangeLabel()
    }

    /**
     * Points the scrubber at the trimmed range.
     *
     * The order matters: a Material slider throws if its value ever sits outside its bounds, so the
     * range is first widened to cover both the old value and the new bounds, then the value is
     * brought inside, and only then are the bounds tightened.
     */
    private fun updateSeekBounds() {
        val seek = _binding?.clipStage?.clipSeek ?: return
        val from = trimStartMs.toFloat()
        val to = trimEndMs.toFloat().coerceAtLeast(from + 1f)
        seek.valueFrom = minOf(from, seek.value)
        seek.valueTo = maxOf(to, seek.value)
        seek.value = seek.value.coerceIn(from, to)
        seek.valueFrom = from
        seek.valueTo = to
    }

    private fun updateRangeLabel() {
        val seconds = (trimEndMs - trimStartMs) / 1000f
        _binding?.clipStage?.clipRangeLabel?.text =
            getString(R.string.clip_range_label, absoluteInterval(), seconds)
    }

    /** The trimmed range as episode timestamps, e.g. `12:04 – 12:34`. */
    private fun absoluteInterval() = ScreenshotUtil.formatInterval(
        windowStartMs + trimStartMs, windowStartMs + trimEndMs
    )

    /** The card's answer to a screenshot's single timestamp: the span the clip covers. */
    private fun updateIntervalText() {
        val stage = _binding?.clipStage ?: return
        stage.clipSubtitle.text =
            listOf(displayNumberLabel(), absoluteInterval()).filter { it.isNotBlank() }
                .joinToString("  •  ")
        stage.clipSubtitle.isVisible = stage.clipSubtitle.text.isNotBlank()
    }

    // endregion

    // region thumbnails

    /**
     * Fills both thumbnails without seeking anything.
     *
     * The preview opens at the start of the window, so the frame already on screen *is* the start
     * thumbnail; the end one was captured from the player the clip was taken from. Earlier versions
     * seeked the preview to each end and tried to detect when the frame had landed, which was the
     * source of a long line of wrong thumbnails — none of that is needed when both frames are
     * already in hand.
     */
    private fun primeThumbnails() {
        captureFrame(trimStartMs, atStart = true)
    }

    /** Instant thumbnail for one end from frames already seen. False if there's nothing near it. */
    private fun showStoryboardFrame(atStart: Boolean): Boolean {
        val stage = _binding?.clipStage ?: return false
        val frame = storyboard?.nearest(if (atStart) trimStartMs else trimEndMs) ?: return false
        (if (atStart) stage.clipThumbStart else stage.clipThumbEnd).setImageBitmap(frame)
        return true
    }

    /**
     * Refreshes one end's thumbnail.
     *
     * The storyboard answers immediately, which is what lets the thumbnail track a finger. Seeking
     * the preview is the fallback for a position not yet covered, and it's debounced because each
     * attempt costs a decode.
     */
    private fun requestThumbnail(atStart: Boolean, resumeAfter: Boolean) {
        val instant = showStoryboardFrame(atStart)
        if (instant && !resumeAfter) return
        thumbnailJob?.cancel()
        thumbnailJob = viewLifecycleOwner.lifecycleScope.launch {
            if (!resumeAfter) delay(THUMBNAIL_DEBOUNCE_MS)
            // On release, refine the storyboard's nearest frame to the exact one being trimmed to.
            seekAndCapture(if (atStart) trimStartMs else trimEndMs, atStart)
            if (resumeAfter) resumeLoop()
        }
    }

    /**
     * Seeks the preview and captures the frame that lands, filing it in the storyboard and, when
     * [atStart] is set, showing it as that end's thumbnail.
     *
     * Knowing when the seek has actually landed is the whole difficulty. Waiting a fixed delay read
     * back the previous frame on a slow seek; waiting on `onRenderedFirstFrame` looked right but
     * that callback also fires for the frame playback starts on, and firing early meant a capture
     * completed against a frame nobody asked for — which is why both trim thumbnails used to show
     * the opening image. So this watches the surface itself and waits for the picture to actually
     * change, which needs no assumptions about renderer callbacks at all.
     */
    private suspend fun seekAndCapture(positionMs: Long, atStart: Boolean?) {
        val player = previewPlayer ?: return
        // Seeking to the very end of the window lands on EOF, where there is no frame to render and
        // nothing to capture, so stop just short of it.
        val target = positionMs.coerceIn(0L, (windowDurationMs - END_BACKOFF_MS).coerceAtLeast(0L))
        val before = frameSignature()
        seekPreview(target)
        if (!awaitFrameChange(before)) {
            // The picture never changed, so whatever is on screen is still the frame from before
            // the seek — it is *not* known to belong to this position. Filing it would put the
            // wrong image at the wrong time in the storyboard and poison every later lookup, so
            // fall back to what the storyboard already knows.
            if (atStart != null) showStoryboardFrame(atStart)
            return
        }
        captureFrame(target, atStart)
    }

    /**
     * Polls the surface until the picture differs from [before]. Returns false if it never did,
     * which means the seek's frame can't be identified — either it hasn't arrived, or the scene is
     * genuinely identical and there was nothing to gain anyway.
     */
    private suspend fun awaitFrameChange(before: Long?): Boolean {
        if (before == null) return false
        val deadline = SystemClock.uptimeMillis() + FRAME_WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            delay(FRAME_POLL_MS)
            if ((frameSignature() ?: continue) != before) return true
        }
        return false
    }

    /** A cheap fingerprint of what's on the preview surface right now. */
    private fun frameSignature(): Long? {
        val texture = _binding?.clipStage?.clipPreview?.videoSurfaceView as? TextureView ?: return null
        val bitmap = runCatching { texture.getBitmap(SIGNATURE_SIZE, SIGNATURE_SIZE) }.getOrNull()
            ?: return null
        val pixels = IntArray(SIGNATURE_SIZE * SIGNATURE_SIZE)
        bitmap.getPixels(pixels, 0, SIGNATURE_SIZE, 0, 0, SIGNATURE_SIZE, SIGNATURE_SIZE)
        bitmap.recycle()
        var hash = 0L
        pixels.forEach { hash = hash * 31 + it }
        return hash
    }

    /**
     * Reads the frame currently on the preview surface. The preview is a TextureView precisely so
     * this is possible — its contents are readable directly, at the size asked for, with no second
     * decoder and no extra read of the source.
     */
    private fun captureFrame(positionMs: Long, atStart: Boolean?) {
        val stage = _binding?.clipStage ?: return
        val texture = stage.clipPreview.videoSurfaceView as? TextureView ?: return
        val target = if (atStart == true) stage.clipThumbStart else stage.clipThumbEnd
        val width = target.width.takeIf { it > 0 } ?: return
        val height = target.height.takeIf { it > 0 } ?: return
        val frame = runCatching { texture.getBitmap(width, height) }.getOrNull() ?: return
        storyboard?.put(positionMs, frame)
        if (atStart != null) target.setImageBitmap(frame)
    }

    /**
     * Every seek of the preview goes through here so the moment is recorded.
     *
     * Right after a seek the player reports the new position while the surface still shows the old
     * frame, so anything captured in that gap would be filed under a time it doesn't belong to.
     * Knowing when the last seek happened is what lets the opportunistic capture stay out of it.
     */
    private fun seekPreview(positionMs: Long) {
        lastSeekAt = SystemClock.uptimeMillis()
        previewPlayer?.seekTo(positionMs)
    }

    private fun resumeLoop() {
        scrubbing = false
        // A GIF preview replaces the video entirely; there's nothing to resume behind it.
        if (isGifSelected()) return
        seekPreview(trimStartMs)
        previewPlayer?.play()
    }

    // endregion

    // region GIF preview

    private fun isGifSelected() =
        _binding?.clipOptions?.clipFormatGroup?.checkedButtonId == R.id.clipFormatGif

    private fun onFormatChanged() {
        val gif = isGifSelected()
        PrefManager.setVal(PrefName.ClipExportAsGif, gif)
        _binding?.clipOptions?.clipGifSettings?.isVisible = gif
        if (gif) refreshGifPreview() else showVideoPreview()
    }

    /**
     * Frame rate and width, alongside the preview that shows what they do. Both are also the saved
     * defaults, so a choice made here carries to the next clip.
     *
     * The preview is rebuilt when a slider is *released* rather than as it moves — each rebuild is
     * a full encode, and re-running it for every intermediate value would be unusable.
     */
    @SuppressLint("SetTextI18n")
    private fun setupGifSettings() {
        val gif = options.clipGifSettings
        gif.isVisible = isGifSelected()

        fun label(view: android.widget.TextView, name: Int, value: Int, format: Int) {
            view.text = "${getString(name)}: ${getString(format, value)}"
        }

        val fps: Int = PrefManager.getVal(PrefName.ClipGifFps)
        val width: Int = PrefManager.getVal(PrefName.ClipGifWidth)
        // A pref restored from elsewhere could sit outside the slider's range, which would throw.
        options.clipGifFps.value =
            fps.toFloat().coerceIn(options.clipGifFps.valueFrom, options.clipGifFps.valueTo)
        options.clipGifWidth.value =
            width.toFloat().coerceIn(options.clipGifWidth.valueFrom, options.clipGifWidth.valueTo)
        label(options.clipGifFpsLabel, R.string.clip_gif_fps, fps, R.string.clip_gif_fps_value)
        label(options.clipGifWidthLabel, R.string.clip_gif_width, width, R.string.clip_gif_width_value)

        options.clipGifFps.addOnChangeListener { _, value, _ ->
            PrefManager.setVal(PrefName.ClipGifFps, value.toInt())
            label(
                options.clipGifFpsLabel, R.string.clip_gif_fps, value.toInt(),
                R.string.clip_gif_fps_value
            )
        }
        options.clipGifWidth.addOnChangeListener { _, value, _ ->
            PrefManager.setVal(PrefName.ClipGifWidth, value.toInt())
            label(
                options.clipGifWidthLabel, R.string.clip_gif_width, value.toInt(),
                R.string.clip_gif_width_value
            )
        }

        val rebuildOnRelease = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = onOutputChanged()
        }
        options.clipGifFps.addOnSliderTouchListener(rebuildOnRelease)
        options.clipGifWidth.addOnSliderTouchListener(rebuildOnRelease)
    }

    /** Anything that changes the exported pixels invalidates a GIF preview built from them. */
    private fun onOutputChanged() {
        if (isGifSelected()) refreshGifPreview()
    }

    private fun showVideoPreview() {
        gifPreviewJob?.cancel()
        val stage = _binding?.clipStage ?: return
        stage.clipGifPreview.isVisible = false
        stage.clipPreviewBusy.isVisible = false
        stage.clipPreviewControls.isVisible = true
        previewPlayer?.play()
    }

    /**
     * Renders the trimmed range as an actual GIF and loops it in place of the video.
     *
     * This runs the real export pipeline rather than approximating one, because the point of
     * looking at a GIF preview is to judge what the frame rate and 256-colour palette do to the
     * clip — an approximation would answer the wrong question. It is correspondingly slow, so the
     * result is cached against the settings that produced it and only rebuilt when they change.
     */
    private fun refreshGifPreview() {
        val payload = payload ?: return
        val fps: Int = PrefManager.getVal(PrefName.ClipGifFps)
        val width: Int = PrefManager.getVal(PrefName.ClipGifWidth)
        val key = "$trimStartMs-$trimEndMs-${options.chipSubtitles.isChecked}-$fps-$width"
        // Nothing that affects the pixels has changed, so re-show what was already encoded rather
        // than spending the whole pipeline again — switching format back and forth is cheap.
        val cached = gifPreviewFile
        if (key == gifPreviewKey && cached?.exists() == true && gifPreviewJob?.isActive != true) {
            showGifPreview(cached)
            return
        }

        gifPreviewJob?.cancel()
        previewPlayer?.pause()
        val startMs = windowStartMs + trimStartMs
        val endMs = windowStartMs + trimEndMs
        if (endMs - startMs < MIN_CLIP_MS) return

        // The card isn't burned into the preview — it's already drawn around it on screen.
        val subtitles = buildSubtitleOverlay(startMs, endMs)

        gifPreviewJob = viewLifecycleOwner.lifecycleScope.launch {
            val stage = _binding?.clipStage ?: return@launch
            stage.clipPreviewControls.isVisible = false
            showPreviewBusy(true, 0)
            val workDir = ClipOutput.workDir(requireContext())
            val source = File(workDir, "preview_${System.currentTimeMillis()}.mp4")
            val gif = File(workDir, "preview_${System.currentTimeMillis()}.gif")
            try {
                val exported = ClipExporter(requireContext(), payload.dataSourceFactory).export(
                    ClipExporter.Request(
                        mediaItem = payload.mediaItem,
                        startMs = startMs,
                        endMs = endMs,
                        output = source,
                        subtitles = subtitles,
                        card = null,
                        includeAudio = false,
                    )
                ) { showPreviewBusy(true, it * VIDEO_SHARE / 100) }.getOrElse {
                    showPreviewBusy(false)
                    snackString(getString(R.string.clip_gif_preview_failed))
                    return@launch
                }

                val result = ClipGifTranscoder.transcode(exported, gif, fps, width) { progress ->
                    val overall = VIDEO_SHARE + progress * (100 - VIDEO_SHARE) / 100
                    _binding?.root?.post { showPreviewBusy(true, overall) }
                }
                exported.delete()

                val file = result.getOrNull()
                showPreviewBusy(false)
                if (file == null) {
                    snackString(getString(R.string.clip_gif_preview_failed))
                    return@launch
                }
                gifPreviewFile?.takeIf { it != file }?.delete()
                gifPreviewKey = key
                gifPreviewFile = file
                showGifPreview(file)
            } catch (cancelled: CancellationException) {
                showPreviewBusy(false)
                throw cancelled
            } finally {
                source.delete()
            }
        }
    }

    /** Loops [file] over the video, which stays behind it (and paused) holding the card's layout. */
    private fun showGifPreview(file: File) {
        val stage = _binding?.clipStage ?: return
        previewPlayer?.pause()
        stage.clipPreviewControls.isVisible = false
        stage.clipGifPreview.isVisible = true
        Glide.with(this).asGif().load(file).into(stage.clipGifPreview)
    }

    private fun showPreviewBusy(visible: Boolean, percent: Int = 0) {
        val stage = _binding?.clipStage ?: return
        stage.clipPreviewBusy.isVisible = visible
        if (!visible) return
        stage.clipPreviewBusyBar.progress = percent.coerceIn(0, 100)
        stage.clipPreviewBusyLabel.text =
            "${getString(R.string.clip_gif_preview_building)} $percent%"
    }

    // endregion

    private fun loadRemoteImages() {
        viewLifecycleOwner.lifecycleScope.launch {
            coverUrl?.let { url ->
                val request = Glide.with(this@ClipDialogFragment).asBitmap().load(url)
                    .transform(CenterCrop(), RoundedCorners(dp(8)))
                    .override(dp(72), dp(108))
                val cover =
                    withContext(Dispatchers.IO) { runCatching { request.submit().get() }.getOrNull() }
                _binding?.clipStage?.clipCover?.setImageBitmap(cover)
            }
            Anilist.avatar?.let { url ->
                val request =
                    Glide.with(this@ClipDialogFragment).asBitmap().load(url).transform(CircleCrop())
                val avatar =
                    withContext(Dispatchers.IO) { runCatching { request.submit().get() }.getOrNull() }
                _binding?.clipStage?.clipAvatar?.setImageBitmap(avatar)
            }
        }
    }

    private fun setupTitleSelector() {
        val titles = titleOptions
        if (titles.size <= 1) {
            options.clipTitleSelectLayout.isVisible = false
            return
        }
        options.clipTitleSelectLayout.isVisible = true
        options.clipTitleSelect.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_titles_dropdown, titles)
        )
        options.clipTitleSelect.setText(selectedTitle, false)
        options.clipTitleSelect.setOnItemClickListener { _, _, position, _ ->
            selectedTitle = titles[position]
            stage.clipTitle.text = selectedTitle
        }
    }

    /**
     * Sources usually label the episode themselves ("Episode 5"), so it's shown verbatim; a bare
     * number gets a prefix so it isn't a lone digit on the card.
     */
    private fun displayNumberLabel(): String {
        val label = numberLabel.trim()
        if (!label.matches(BARE_NUMBER)) return label
        return getString(R.string.episode_num_short, label)
    }

    private fun captionText() = options.clipCaptionInput.text?.toString()?.trim().orEmpty()

    private fun hasDecor(): Boolean {
        val userInfo = options.chipUserInfo.isChecked && !Anilist.username.isNullOrEmpty()
        return captionText().isNotEmpty() || options.chipMediaInfo.isChecked || userInfo ||
            options.chipAppIcon.isChecked
    }

    /** Applies the current toggle state to the live preview card. Mirrors the screenshot composer. */
    private fun applyLayout() {
        val binding = _binding ?: return
        val stage = binding.clipStage
        val frame = options.chipFrame.isChecked
        val mediaInfo = options.chipMediaInfo.isChecked
        val userInfo = options.chipUserInfo.isChecked && !Anilist.username.isNullOrEmpty()
        val appIcon = options.chipAppIcon.isChecked
        val caption = captionText()

        stage.clipCaption.text = caption
        stage.clipCaptionRow.isVisible = caption.isNotEmpty()

        updateIntervalText()

        stage.clipMediaInfo.isVisible = mediaInfo
        options.chipDate.isEnabled = mediaInfo
        options.chipSource.isEnabled = mediaInfo
        val dateVisible = mediaInfo && options.chipDate.isChecked
        val sourceVisible =
            mediaInfo && options.chipSource.isChecked && !sourceLabel.isNullOrBlank()
        stage.clipDate.isVisible = dateVisible
        stage.clipSource.isVisible = sourceVisible

        val extraRows = (if (dateVisible) 1 else 0) + (if (sourceVisible) 1 else 0)
        stage.clipCover.updateLayoutParams {
            width = dp(48 + extraRows * 8)
            height = dp(72 + extraRows * 12)
        }

        stage.clipUserInfo.isVisible = userInfo
        val onMedia = appIcon && mediaInfo && !userInfo
        val onCaption = appIcon && caption.isNotEmpty() && !mediaInfo && !userInfo
        val onFooter = appIcon && !onMedia && !onCaption
        stage.clipLogoInline.isVisible = onMedia
        stage.clipLogoCaption.isVisible = onCaption
        stage.clipLogoFooter.isVisible = onFooter
        stage.clipFooter.isVisible = userInfo || onFooter

        val decor = hasDecor()
        stage.clipDecorContainer.isVisible = decor

        val pad = dp(12)
        when {
            frame -> {
                stage.clipCard.setBackgroundResource(R.drawable.bg_clip_card)
                stage.clipCard.setPadding(pad, pad, pad, pad)
                stage.clipDecorContainer.setPadding(0, 0, 0, 0)
            }

            decor -> {
                stage.clipCard.setBackgroundResource(R.drawable.bg_clip_card)
                stage.clipCard.setPadding(0, 0, 0, 0)
                stage.clipDecorContainer.setPadding(pad, 0, pad, pad)
            }

            else -> {
                stage.clipCard.background = null
                stage.clipCard.setPadding(0, 0, 0, 0)
                stage.clipDecorContainer.setPadding(0, 0, 0, 0)
            }
        }
    }

    // region export

    /**
     * True when the card adds nothing and the clip can be exported as-is at full resolution.
     * Rounded corners don't feature: they're not offered for clips, so a frame and the decor rows
     * are all there is to add.
     */
    private fun isBare(): Boolean {
        val binding = _binding ?: return true
        return !options.chipFrame.isChecked && !binding.clipStage.clipDecorContainer.isVisible
    }

    /**
     * Renders the card to a bitmap with the video area punched out, plus that area's position, for
     * [ClipExporter] to composite frames into.
     *
     * The scale is chosen so the punched-out hole comes out close to the source video's own width:
     * the video is then neither upscaled nor thrown away, and the card's text is redrawn at that
     * resolution rather than being enlarged after the fact.
     */
    private fun buildCard(): ClipExporter.Card? {
        val stage = _binding?.clipStage ?: return null
        val card = stage.clipCard
        val preview = stage.clipPreview
        if (card.width <= 0 || card.height <= 0 || preview.width <= 0 || preview.height <= 0) {
            return null
        }

        val scale = if (videoWidth > 0) {
            (videoWidth.toFloat() / preview.width).coerceIn(MIN_EXPORT_SCALE, MAX_EXPORT_SCALE)
        } else {
            DEFAULT_EXPORT_SCALE
        }

        val offset = IntArray(2)
        preview.getLocationInWindow(offset)
        val cardOffset = IntArray(2)
        card.getLocationInWindow(cardOffset)

        // The hole has to carry the video's exact aspect ratio. Frames are fitted into it during
        // export, so any disagreement between the two comes out as letterboxing — transparent in
        // the effect chain, and black once encoded. Sizing the hole from the preview's laid-out
        // bounds inherited every rounding along the way (integer view heights, even-number
        // alignment, a preview still at its default height because the video size hadn't arrived),
        // which is where the black bars came from. Deriving it from the video instead and centring
        // it in the space the preview occupies leaves nothing to letterbox.
        val availableWidth = even(preview.width * scale)
        val availableHeight = even(preview.height * scale)
        var rectWidth = availableWidth
        var rectHeight = availableHeight
        if (videoWidth > 0 && videoHeight > 0) {
            val heightForWidth = even(availableWidth.toFloat() * videoHeight / videoWidth)
            if (heightForWidth <= availableHeight) {
                rectHeight = heightForWidth
            } else {
                rectWidth = even(availableHeight.toFloat() * videoWidth / videoHeight)
            }
        }
        val left = even(
            (offset[0] - cardOffset[0]) * scale + (availableWidth - rectWidth) / 2f
        )
        val top = even(
            (offset[1] - cardOffset[1]) * scale + (availableHeight - rectHeight) / 2f
        )
        val videoRect = Rect(left, top, left + rectWidth, top + rectHeight)

        // H.264 needs even dimensions, on the frame and on the region inside it alike. Rounding the
        // two independently can leave the region a pixel past the frame, so the frame wins.
        val outWidth = maxOf(even(card.width * scale), videoRect.right)
        val outHeight = maxOf(even(card.height * scale), videoRect.bottom)

        // The on-screen playback controls sit inside the video area and would be drawn here, but
        // the punch below clears exactly that area, so they can never reach the file.
        val bitmap = runCatching {
            Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                canvas.scale(scale, scale)
                card.draw(canvas)
            }
        }.getOrNull() ?: return null

        punchHole(bitmap, videoRect)
        return ClipExporter.Card(bitmap, videoRect)
    }

    /**
     * Clears [rect] out of [bitmap] so the video shows through.
     *
     * Square corners, deliberately. Rounding them looks fine on a still but not on moving footage,
     * where the curve reads as the video being clipped rather than framed — which is also why the
     * rounded-corners option isn't offered here at all.
     */
    private fun punchHole(bitmap: Bitmap, rect: Rect) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        canvas.drawRect(RectF(rect), paint)
    }

    /** Builds the subtitle overlay for the trimmed range, or null when it's off/unavailable. */
    private fun buildSubtitleOverlay(startMs: Long, endMs: Long): ClipSubtitleOverlay? {
        if (_binding?.clipOptions?.chipSubtitles?.isChecked != true) return null
        // A renderer of its own again: the preview already holds one, and libass state is per-frame
        // size, so the two would trample each other.
        val time = ClipSubtitleOverlay.rebasedFrom(startMs)
        return when (val source = payload?.subtitles) {
            is Subtitles.Ass -> source.createRender()?.let { render ->
                ClipSubtitleOverlay(time, videoWidth, videoHeight, render, emptyList(), null)
            }

            is Subtitles.Text -> ClipSubtitleOverlay(
                time, videoWidth, videoHeight, null,
                source.buffer.snapshot(startMs, endMs), source.style
            )

            null -> null
        }
    }

    private fun export(share: Boolean) {
        val payload = payload ?: return
        if (exportJob?.isActive == true) return
        if (!share && !downloadsPermission(requireActivity() as AppCompatActivity)) return

        val startMs = windowStartMs + trimStartMs
        val endMs = windowStartMs + trimEndMs
        if (endMs - startMs < MIN_CLIP_MS) {
            snackString(getString(R.string.clip_too_short)); return
        }

        val asGif = isGifSelected()
        PrefManager.setVal(PrefName.ClipExportAsGif, asGif)
        PrefManager.setVal(PrefName.ClipBurnSubtitles, options.chipSubtitles.isChecked)

        // Built on the main thread while the views are still laid out and settled.
        val card = if (isBare()) null else buildCard()
        val subtitles = buildSubtitleOverlay(startMs, endMs)

        gifPreviewJob?.cancel()
        showProgress(true, R.string.clip_rendering, 0)
        previewPlayer?.pause()

        exportJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val workDir = ClipOutput.workDir(requireContext())
                val video = File(workDir, "clip_${System.currentTimeMillis()}.mp4")

                val exported = ClipExporter(requireContext(), payload.dataSourceFactory).export(
                    ClipExporter.Request(
                        mediaItem = payload.mediaItem,
                        startMs = startMs,
                        endMs = endMs,
                        output = video,
                        subtitles = subtitles,
                        card = card,
                        // GIFs can't carry audio, so don't spend the time encoding it
                        includeAudio = !asGif,
                    )
                ) { progress ->
                    // The mp4 is the whole job for a video, but only the first part for a GIF.
                    val scaled = if (asGif) progress * VIDEO_SHARE / 100 else progress
                    showProgress(true, R.string.clip_rendering, scaled)
                }.getOrElse {
                    finishExport(null, share, asGif, it)
                    return@launch
                }

                if (!asGif) {
                    finishExport(exported, share, asGif = false, error = null)
                    return@launch
                }

                val gif = File(workDir, "clip_${System.currentTimeMillis()}.gif")
                val result = ClipGifTranscoder.transcode(
                    source = exported,
                    output = gif,
                    fps = PrefManager.getVal(PrefName.ClipGifFps),
                    maxWidth = PrefManager.getVal(PrefName.ClipGifWidth),
                ) { progress ->
                    // Unlike the export above, the GIF encode reports from a worker thread.
                    val overall = VIDEO_SHARE + progress * (100 - VIDEO_SHARE) / 100
                    _binding?.root?.post { showProgress(true, R.string.clip_encoding_gif, overall) }
                }
                exported.delete()
                finishExport(
                    result.getOrNull(), share, asGif = true, error = result.exceptionOrNull()
                )
            } catch (cancelled: CancellationException) {
                // Hitting Cancel unwinds through here; put the sheet back the way it was. The
                // dispatcher is Main, so touching views is safe.
                showProgress(false)
                if (!isGifSelected()) previewPlayer?.play()
                throw cancelled
            }
        }
    }

    private fun finishExport(file: File?, share: Boolean, asGif: Boolean, error: Throwable?) {
        showProgress(false)
        if (file == null || !file.exists()) {
            snackString(
                error?.localizedMessage?.let { "${getString(R.string.clip_failed)}: $it" }
                    ?: getString(R.string.clip_failed)
            )
            if (!isGifSelected()) previewPlayer?.play()
            return
        }
        val extension = if (asGif) "gif" else "mp4"
        val mime = if (asGif) ClipOutput.MIME_GIF else ClipOutput.MIME_VIDEO
        if (share) {
            // Raising the chooser isn't the user leaving the player, but some platforms report it
            // as such and the video would drop into picture-in-picture behind it.
            (activity as? ExoplayerView)?.suppressPipForChooser()
            ClipOutput.share(fileName(), file, mime, requireContext())
        } else {
            ClipOutput.saveToDownloads(fileName(), extension, file, requireContext())
        }
        if (!isGifSelected()) previewPlayer?.play()
    }

    @SuppressLint("SetTextI18n")
    private fun showProgress(visible: Boolean, label: Int? = null, percent: Int = 0) {
        val binding = _binding ?: return
        binding.clipProgressOverlay.isVisible = visible
        if (!visible) return
        binding.clipProgressBar.progress = percent.coerceIn(0, 100)
        label?.let { binding.clipProgressLabel.text = "${getString(it)} $percent%" }
    }

    // endregion

    private fun fileName(): String {
        val raw = listOf(selectedTitle, displayNumberLabel(), absoluteInterval())
            .filter { it.isNotBlank() }.joinToString(" - ")
            .ifBlank { getString(R.string.clip) }
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "").take(120)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun even(value: Float) = (value.roundToInt() / 2) * 2

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissed?.invoke()
    }

    override fun onDestroyView() {
        exportJob?.cancel()
        thumbnailJob?.cancel()
        gifPreviewJob?.cancel()
        storyboard?.recycle()
        storyboard = null
        previewPlayer?.release()
        previewPlayer = null
        // The renderer is ours alone; the player's own is untouched by any of this.
        previewAssRender = null
        _binding = null
        super.onDestroyView()
    }

    /** Where a clip's subtitles come from; see [ClipSubtitleOverlay] for why they differ. */
    sealed interface Subtitles {
        /** libass can re-render any timestamp, so the export gets its own renderer on demand. */
        class Ass(val createRender: () -> AssRender?) : Subtitles

        /** Text cues only exist as they play, so they're replayed from what the player recorded. */
        class Text(
            val buffer: SubtitleCueBuffer,
            val style: ClipSubtitleOverlay.TextStyle,
        ) : Subtitles
    }

    /**
     * The parts of the player's setup a clip needs, too large or too live for the argument bundle.
     */
    class Payload(
        val mediaItem: MediaItem,
        val dataSourceFactory: DataSource.Factory?,
        val subtitles: Subtitles?,
        /**
         * The frame the player was sitting on when the clip was taken. A clip's window ends at
         * exactly that moment, so this *is* the end thumbnail — handing it over is both instant and
         * exact, where seeking the preview 30s forward to find it again is neither.
         */
        val endFrame: Bitmap? = null,
    )

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_TITLE_OPTIONS = "titleOptions"
        private const val ARG_COVER = "cover"
        private const val ARG_NUMBER = "number"
        private const val ARG_SOURCE = "source"
        private const val ARG_WINDOW_START = "windowStart"
        private const val ARG_WINDOW_END = "windowEnd"

        private val BARE_NUMBER = Regex("""\d+([.,]\d+)?""")

        private const val LOOP_TICK_MS = 60L
        private const val LOOP_SLACK_MS = 400L
        private const val MIN_CLIP_MS = 500L

        /** How long to wait for a seek to be rendered before capturing whatever is on screen. */
        private const val FRAME_WAIT_MS = 1500L

        /** How often to check whether the seeked frame has appeared. */
        private const val FRAME_POLL_MS = 24L

        /** Side of the tiny grab used to tell one displayed frame from another. */
        private const val SIGNATURE_SIZE = 8

        /** How long after a seek the displayed frame is distrusted for storyboard purposes. */
        private const val CAPTURE_SETTLE_MS = 400L


        /** Quiet period during a drag before the thumbnail for that end is refreshed. */
        private const val THUMBNAIL_DEBOUNCE_MS = 150L

        /** How far short of the window's end to stop, so a capture doesn't land on EOF. */
        private const val END_BACKOFF_MS = 120L

        /**
         * Ceiling on how much of the sheet the preview may take before the card is narrowed.
         *
         * In landscape this is what decides the card's *width*: a full-width preview would be
         * taller than the screen, so the card is narrowed until its height fits, and a higher
         * ceiling therefore means a wider preview. The sheet is one continuous scroll, so trading
         * away some of the fold costs nothing but a scroll.
         */
        private const val MAX_PREVIEW_HEIGHT_FRACTION = 0.55f

        /** Portion of the reported progress the mp4 render accounts for when producing a GIF. */
        private const val VIDEO_SHARE = 55

        private const val MIN_EXPORT_SCALE = 1f
        private const val MAX_EXPORT_SCALE = 4f
        private const val DEFAULT_EXPORT_SCALE = 2f

        /** Transient hand-off of the live player state; read and cleared in [onCreate]. */
        private var pending: Payload? = null

        /**
         * @param windowStartMs/[windowEndMs] the captured window in episode time; the sheet trims
         *   within it, and the full window is what the preview loads
         */
        fun newInstance(
            payload: Payload,
            title: String,
            titleOptions: List<String> = emptyList(),
            coverUrl: String?,
            numberLabel: String,
            sourceLabel: String?,
            windowStartMs: Long,
            windowEndMs: Long,
        ): ClipDialogFragment {
            pending = payload
            return ClipDialogFragment().apply {
                arguments = bundleOf(
                    ARG_TITLE to title,
                    ARG_TITLE_OPTIONS to ArrayList(titleOptions),
                    ARG_COVER to coverUrl,
                    ARG_NUMBER to numberLabel,
                    ARG_SOURCE to sourceLabel,
                    ARG_WINDOW_START to windowStartMs,
                    ARG_WINDOW_END to windowEndMs,
                )
            }
        }
    }
}
