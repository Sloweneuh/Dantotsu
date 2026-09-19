package ani.dantotsu.connections.animethemes

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import ani.dantotsu.R
import ani.dantotsu.media.screenshot.ClipOutput
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Saving a theme to disk.
 *
 * AnimeThemes publishes each theme as one or two webm rips and an ogg of the audio, which is more
 * than most people want to carry around: a phone-sized copy, or just the song in a format every
 * music app reads. So the picker lists the files as they come plus a set of re-encodes, and the
 * re-encodes run through the same media3 [Transformer] the clip exporter uses — Transformer reads
 * the source straight off the network, so nothing is downloaded twice.
 *
 * Only heights *below* the source are offered: upscaling a 1080p BD rip would cost minutes of
 * encoding to produce a bigger, worse file.
 */
@OptIn(UnstableApi::class)
object AnimeThemeDownloader {

    /**
     * Ladder offered for re-encodes, filtered down to what is smaller than the source, each with
     * the bitrate it is encoded at.
     *
     * The bitrate is pinned rather than left to media3, which derives one from the frame rate:
     * these are 60fps sequences, so a "360p" copy came out barely smaller than the 1080p source
     * it was made from, which defeats the point of asking for it.
     */
    private val HEIGHTS = linkedMapOf(
        1080 to 4_000_000,
        720 to 2_500_000,
        480 to 1_200_000,
        360 to 700_000,
        144 to 250_000
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notificationId = Notifications.ID_DOWNLOAD_THEME

    /** Audio bitrates offered for a re-encode, in kbps. */
    val AUDIO_BITRATES = listOf(128, 192, 256, 320)

    enum class Kind { VIDEO, AUDIO }

    /**
     * Containers that can actually be written on the device. Video re-encodes go to mp4 because
     * media3 has no WebM muxer, so webm is only ever the published file passed through; the same
     * goes for ogg on the audio side. MP3 is absent on purpose: Android ships no MP3 encoder, and
     * nothing in the app bundles one.
     */
    enum class Format(val extension: String, val kind: Kind, val reEncodes: Boolean) {
        WEBM("webm", Kind.VIDEO, false),
        MP4("mp4", Kind.VIDEO, true),
        OGG("ogg", Kind.AUDIO, false),
        M4A("m4a", Kind.AUDIO, true);
    }

    /**
     * What the picker builds: a published file to read, and what to turn it into. [height] and
     * [audioBitrate] are set only when the file is re-encoded.
     */
    data class Option(
        val url: String,
        /** Without extension. */
        val fileName: String,
        val format: Format,
        val height: Int? = null,
        val audioBitrate: Int? = null
    ) {
        val reEncodes get() = format.reEncodes
        val extension get() = format.extension
        val audioOnly get() = format.kind == Kind.AUDIO
    }

    /** The published copies of this cut, best first — the "source" the rest is derived from. */
    fun sources(version: AnimeThemeVersion): List<AnimeThemeVideo> = version.videos

    /** Formats writable from [source]: passthrough only when the file already exists in it. */
    fun formats(source: AnimeThemeVideo, kind: Kind): List<Format> = when (kind) {
        Kind.VIDEO -> listOfNotNull(
            Format.WEBM.takeIf { ext(source.link) == "webm" },
            Format.MP4
        )
        Kind.AUDIO -> listOfNotNull(
            source.audio?.let { audio -> Format.OGG.takeIf { ext(audio) == "ogg" } },
            Format.M4A
        )
    }

    /** Heights up to the source's own — upscaling only costs time and bytes. */
    fun heights(source: AnimeThemeVideo): List<Int> {
        val sourceHeight = source.resolution ?: return HEIGHTS.keys.toList()
        return HEIGHTS.keys.filter { it <= sourceHeight }
    }

    fun option(
        track: AnimeThemeTrack,
        version: AnimeThemeVersion,
        source: AnimeThemeVideo,
        format: Format,
        height: Int? = null,
        audioBitrate: Int? = null
    ): Option {
        val stem = stem(track, version)
        return when (format) {
            // Passed through: the file as AnimeThemes published it, named after its own quality.
            Format.WEBM -> Option(
                url = source.link,
                fileName = "$stem-${source.tags ?: source.resolution?.let { "${it}p" } ?: "video"}",
                format = format
            )
            Format.OGG -> Option(
                url = source.audio ?: source.link,
                fileName = "$stem-audio",
                format = format
            )
            Format.MP4 -> Option(
                url = source.link,
                fileName = "$stem-${height ?: source.resolution ?: 0}p",
                format = format,
                height = height ?: source.resolution
            )
            // Decoding the audio file is cheaper than pulling the whole video for its soundtrack.
            Format.M4A -> Option(
                url = source.audio ?: source.link,
                fileName = "$stem-${audioBitrate ?: AUDIO_BITRATES.first()}kbps",
                format = format,
                audioBitrate = audioBitrate ?: AUDIO_BITRATES.first()
            )
        }
    }

    /**
     * "SousouNoFrieren-ED1", from AnimeThemes' own basename with its quality tag taken off, so
     * the quality in the name is the one that was actually produced. A version number joins it
     * when the theme was re-cut, which is the only way two saves of one theme differ.
     */
    private fun stem(track: AnimeThemeTrack, version: AnimeThemeVersion): String {
        val video = version.videos.firstOrNull()
        val base = video?.link?.substringAfterLast('/')?.substringBeforeLast('.')?.let { name ->
            video.tags?.let { name.removeSuffix("-$it") } ?: name
        } ?: listOfNotNull(track.animeSlug, track.slug).joinToString("-").ifBlank { "theme" }
        return if (track.versions.size > 1 && version.version != null)
            "${base}v${version.version}" else base
    }

    /**
     * Files taken as published go to the system download manager; re-encodes run here and land
     * in Downloads when they finish.
     */
    fun start(context: Context, option: Option) {
        val app = context.applicationContext
        if (!option.reEncodes) {
            enqueue(app, option)
            return
        }
        snackString(context.getString(R.string.theme_converting))
        scope.launch { reEncode(app, option) }
    }

    /** A file that needs no work is the download manager's job, notification and all. */
    private fun enqueue(context: Context, option: Option) {
        try {
            val fileName = "${option.fileName}.${option.extension}"
            val request = DownloadManager.Request(option.url.toUri())
                .setTitle(fileName)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "Dantotsu/$fileName"
                )
            val manager =
                context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            snackString(context.getString(R.string.downloading))
        } catch (e: Throwable) {
            Logger.log(e)
            snackString(context.getString(R.string.error_message, e.message ?: ""))
        }
    }

    private suspend fun reEncode(context: Context, option: Option) {
        val id = notificationId--
        val fileName = "${option.fileName}.${option.extension}"
        val temp = File(context.cacheDir, "animethemes/$fileName")
        temp.parentFile?.mkdirs()
        if (temp.exists()) temp.delete()

        notify(context, id, fileName, 0)
        try {
            coroutineScope {
                val progress = launch { pollProgress(context, id, fileName) }
                try {
                    export(context, option, temp)
                } finally {
                    progress.cancel()
                    transformer = null
                }
            }
            withContext(Dispatchers.IO) {
                ClipOutput.saveToDownloads(option.fileName, option.extension, temp, context)
            }
            cancelNotification(context, id)
        } catch (e: Throwable) {
            Logger.log(e)
            cancelNotification(context, id)
            snackString(context.getString(R.string.theme_convert_failed))
        } finally {
            runCatching { temp.delete() }
        }
    }

    @Volatile
    private var transformer: Transformer? = null

    /** [Transformer] is bound to the looper it is built on, so this all stays on the main thread. */
    private suspend fun export(context: Context, option: Option, output: File): File =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val edited = EditedMediaItem.Builder(MediaItem.fromUri(option.url))
                    .setRemoveVideo(option.audioOnly)
                    .apply {
                        option.height?.let {
                            setEffects(
                                Effects(emptyList(), listOf(Presentation.createForHeight(it)))
                            )
                        }
                    }
                    .build()

                val built = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .apply {
                        val videoBitrate = HEIGHTS[option.height]
                        val audioBitrate = option.audioBitrate?.times(1000)
                        if (videoBitrate != null || audioBitrate != null) {
                            val encoders = DefaultEncoderFactory.Builder(context)
                            videoBitrate?.let {
                                encoders.setRequestedVideoEncoderSettings(
                                    VideoEncoderSettings.Builder().setBitrate(it).build()
                                )
                            }
                            audioBitrate?.let {
                                encoders.setRequestedAudioEncoderSettings(
                                    AudioEncoderSettings.Builder().setBitrate(it).build()
                                )
                            }
                            setEncoderFactory(encoders.build())
                        }
                    }
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            if (continuation.isActive) continuation.resume(output)
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException
                        ) {
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.failure(exception))
                            }
                        }
                    })
                    .build()
                    .also { transformer = it }

                continuation.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post { runCatching { built.cancel() } }
                }
                built.start(edited, output.absolutePath)
            }
        }

    private suspend fun pollProgress(context: Context, id: Int, fileName: String) {
        val holder = ProgressHolder()
        // delay() is the cancellation point: the surrounding job is torn down when the export
        // finishes, which ends the loop.
        while (true) {
            val percent = withContext(Dispatchers.Main) {
                transformer?.takeIf {
                    it.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE
                }?.let { holder.progress }
            }
            if (percent != null) notify(context, id, fileName, percent)
            delay(500)
        }
    }

    private fun notify(context: Context, id: Int, fileName: String, percent: Int) {
        runCatching {
            val notification =
                NotificationCompat.Builder(context, Notifications.CHANNEL_DOWNLOADER_PROGRESS)
                    .setSmallIcon(R.drawable.ic_download_24)
                    .setContentTitle(context.getString(R.string.theme_converting))
                    .setContentText(fileName)
                    .setProgress(100, percent, percent <= 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    private fun cancelNotification(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    /** The container as published — "webm", "ogg" — read off the URL. */
    private fun ext(url: String): String =
        url.substringAfterLast('.', "").takeIf { it.length in 2..4 } ?: "bin"
}
