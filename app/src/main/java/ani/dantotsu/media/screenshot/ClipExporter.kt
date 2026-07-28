package ani.dantotsu.media.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Cuts a section of the currently playing stream back out into an mp4.
 *
 * The clip is re-read from the source rather than recorded off the screen, which is what keeps the
 * original resolution, frame rate and audio intact. Because it's the *source* that gets re-read,
 * the export is handed the player's own [DataSource.Factory] so it inherits the extension's
 * headers and, more importantly, the warm cache — for a clip of something just watched the bytes
 * are usually still local, so this is mostly disk work rather than a second download.
 *
 * Anything that was drawn *over* the video (subtitles, and the share card) has to be re-applied as
 * a video effect, since none of it exists in the source. See [ClipSubtitleOverlay] and [Card].
 */
@OptIn(UnstableApi::class)
class ClipExporter(
    private val context: Context,
    private val dataSourceFactory: DataSource.Factory?,
) {

    /**
     * The share card to composite the clip into: [bitmap] is the card rendered at output size with
     * a hole punched where the video belongs, and [videoRect] is that hole in card pixels.
     */
    data class Card(val bitmap: Bitmap, val videoRect: Rect)

    data class Request(
        val mediaItem: MediaItem,
        val startMs: Long,
        val endMs: Long,
        val output: File,
        val subtitles: ClipSubtitleOverlay? = null,
        val card: Card? = null,
        val includeAudio: Boolean = true,
    )

    @Volatile
    private var transformer: Transformer? = null

    /**
     * Runs the export, reporting 0..100 through [onProgress]. [Transformer] is bound to the looper
     * it's built on, so the whole thing is pinned to the main thread; the heavy lifting happens on
     * the codec threads it spawns internally.
     */
    suspend fun export(request: Request, onProgress: (Int) -> Unit): Result<File> =
        withContext(Dispatchers.Main) {
            coroutineScope {
                val progress = launch { pollProgress(onProgress) }
                try {
                    runCatching { awaitExport(request) }
                } finally {
                    progress.cancel()
                    transformer = null
                }
            }
        }

    private suspend fun awaitExport(request: Request): File =
        suspendCancellableCoroutine { continuation ->
            request.output.parentFile?.mkdirs()
            if (request.output.exists()) request.output.delete()

            val clipped = request.mediaItem.buildUpon()
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(request.startMs)
                        .setEndPositionMs(request.endMs)
                        // The clip rarely starts on a keyframe; without this it would snap back to
                        // the previous one and run long.
                        .setStartsAtKeyFrame(false)
                        .build()
                )
                // Side-loaded subtitle tracks are for the player, not for this: an mp4 export has
                // nowhere to put them, and subtitles that should appear are burned in as an effect.
                .setSubtitleConfigurations(emptyList())
                .build()

            val edited = EditedMediaItem.Builder(clipped)
                .setRemoveAudio(!request.includeAudio)
                .setEffects(Effects(emptyList(), buildVideoEffects(request)))
                .build()

            val builder = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (continuation.isActive) continuation.resume(request.output)
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException,
                    ) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(exception))
                        }
                    }
                })

            // Reusing the player's data source keeps the extension's headers and the warm cache.
            dataSourceFactory?.let {
                builder.setAssetLoaderFactory(
                    ExoPlayerAssetLoader.Factory(
                        context,
                        DefaultDecoderFactory.Builder(context).build(),
                        Clock.DEFAULT,
                        DefaultMediaSourceFactory(it),
                    )
                )
            }

            val built = builder.build().also { transformer = it }
            // Transformer rejects calls from off its own looper, and cancellation can be delivered
            // on any thread, so bounce back to the main thread to stop it.
            continuation.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post { runCatching { built.cancel() } }
            }
            built.start(edited, request.output.absolutePath)
        }

    /** Reports export progress until the surrounding scope is torn down. */
    private suspend fun pollProgress(onProgress: (Int) -> Unit) {
        val holder = ProgressHolder()
        while (coroutineContext.isActive) {
            val current = transformer
            if (current != null &&
                current.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE
            ) {
                onProgress(holder.progress)
            }
            delay(PROGRESS_POLL_MS)
        }
    }

    /**
     * The effect chain, in the order a frame flows through it: scale the video to the size it
     * occupies in the output, burn subtitles on at that size, pad the frame out to the full card,
     * then lay the card art over the padding.
     *
     * Without a card it's just the subtitles — the clip keeps the source's own dimensions.
     */
    private fun buildVideoEffects(request: Request): List<Effect> {
        val effects = mutableListOf<Effect>()
        val card = request.card

        card?.videoRect?.let {
            effects += Presentation.createForWidthAndHeight(
                it.width(), it.height(), Presentation.LAYOUT_SCALE_TO_FIT
            )
        }
        request.subtitles?.let { effects += OverlayEffect(listOf(it)) }
        if (card != null) {
            effects += padInto(card.videoRect, card.bitmap.width, card.bitmap.height)
            effects += OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(card.bitmap)))
        }
        return effects
    }

    /**
     * Expands the frame from [rect]'s size out to [outWidth]x[outHeight], leaving the video sitting
     * at [rect] and the rest transparent.
     *
     * [Crop] works in normalised device coordinates, where the incoming frame spans -1..1 on both
     * axes and values outside that range pad rather than crop. Solving "the input's left edge (-1)
     * must land at pixel `rect.left` of the output" gives the expressions below, and the same
     * vertically — remembering NDC puts +1 at the top.
     */
    private fun padInto(rect: Rect, outWidth: Int, outHeight: Int): Crop {
        val w = rect.width().toFloat()
        val h = rect.height().toFloat()
        val left = -1f - 2f * rect.left / w
        val top = 1f + 2f * rect.top / h
        return Crop(left, left + 2f * outWidth / w, top - 2f * outHeight / h, top)
    }

    fun cancel() {
        transformer?.let { runCatching { it.cancel() } }
        transformer = null
    }

    companion object {
        private const val PROGRESS_POLL_MS = 200L
    }
}
