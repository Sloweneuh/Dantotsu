package ani.dantotsu.media.screenshot

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Turns an exported clip into an animated GIF.
 *
 * GIFs are produced from the mp4 rather than from the source stream, so everything already baked
 * into the clip — subtitles, the share card — carries over for free, and the expensive decode of
 * the original stream happens only once.
 *
 * Frames are pulled with [MediaMetadataRetriever] rather than a hand-rolled MediaCodec loop. It
 * seeks per frame and is the slow part of the whole operation, but a GIF is nearly always a short
 * trim of a few seconds, and the alternative is several hundred lines of decoder plumbing plus a
 * YUV conversion. The work runs off the main thread with progress reported throughout.
 */
object ClipGifTranscoder {

    /**
     * Writes [source] to [output] as a GIF at [fps], scaled down to at most [maxWidth] wide.
     * [onProgress] reports 0..100.
     */
    suspend fun transcode(
        source: File,
        output: File,
        fps: Int,
        maxWidth: Int,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.Default) {
        runCatching { run(source, output, fps, maxWidth, onProgress) }
    }

    private suspend fun run(
        source: File,
        output: File,
        fps: Int,
        maxWidth: Int,
        onProgress: (Int) -> Unit,
    ): File {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)

            val durationMs = retriever.meta(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: 0
            val sourceWidth = retriever.meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: 0
            val sourceHeight = retriever.meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: 0
            require(durationMs > 0 && sourceWidth > 0 && sourceHeight > 0) { "unreadable clip" }

            val width = minOf(maxWidth, sourceWidth).coerceAtLeast(2)
            val height = (sourceHeight.toLong() * width / sourceWidth).toInt().coerceAtLeast(2)
            val delayMs = (1000 / fps).coerceAtLeast(20)
            val frameCount = ((durationMs.toLong() * fps) / 1000).toInt().coerceIn(1, MAX_FRAMES)
            val stepUs = durationMs * 1000L / frameCount

            // Pass one: a palette that suits the whole clip, not just its first shot.
            val samples = ArrayList<Int>()
            val sampleStep = (frameCount / GifEncoder.PALETTE_SAMPLE_FRAMES).coerceAtLeast(1)
            var sampled = 0
            var index = 0
            while (index < frameCount) {
                coroutineContext.ensureActive()
                retriever.frameAt(index * stepUs, width, height)?.let {
                    GifEncoder.sampleColors(it, samples)
                    it.recycle()
                    sampled++
                }
                onProgress(PALETTE_SHARE * sampled / GifEncoder.PALETTE_SAMPLE_FRAMES)
                index += sampleStep
            }
            require(samples.isNotEmpty()) { "no frames decoded" }

            // Pass two: encode straight to disk, one frame at a time.
            BufferedOutputStream(FileOutputStream(output)).use { stream ->
                val encoder = GifEncoder(stream, width, height)
                encoder.begin(samples.toIntArray())
                samples.clear()
                for (frame in 0 until frameCount) {
                    coroutineContext.ensureActive()
                    val bitmap = retriever.frameAt(frame * stepUs, width, height) ?: continue
                    encoder.addFrame(bitmap, delayMs)
                    bitmap.recycle()
                    onProgress(PALETTE_SHARE + (100 - PALETTE_SHARE) * (frame + 1) / frameCount)
                }
                encoder.finish()
            }
            return output
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * The frame nearest [timeUs]. On API 27+ the decoder scales for us, which avoids materialising
     * a full-resolution bitmap per frame just to shrink it.
     */
    private fun MediaMetadataRetriever.frameAt(timeUs: Long, width: Int, height: Int): Bitmap? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST, width, height
                )
            } else {
                getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }
        }.getOrNull()

    private fun MediaMetadataRetriever.meta(key: Int): Int? =
        extractMetadata(key)?.toIntOrNull()

    /** Percentage of the reported progress spent building the palette. */
    private const val PALETTE_SHARE = 15

    /** A ceiling so a long trim at a high frame rate can't turn into a gigabyte of GIF. */
    private const val MAX_FRAMES = 900
}
