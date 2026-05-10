package eu.kanade.tachiyomi.animesource.model

import android.net.Uri
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import java.io.Serializable

@kotlinx.serialization.Serializable
data class Track(val url: String, val lang: String) : Serializable

@kotlinx.serialization.Serializable
enum class ChapterType {
    Opening,
    Ending,
    Recap,
    MixedOp,
    Other,
}

@kotlinx.serialization.Serializable
data class TimeStamp(
    val start: Double,
    val end: Double,
    val name: String,
    val type: ChapterType = ChapterType.Other,
)

// Primary constructor matches aniyomi-extensions-lib v16+ exactly. Reordering or
// adding parameters here changes the JVM signature of the synthetic default-args
// constructor that extensions are compiled against — keep it in lockstep with
// upstream to avoid NoSuchMethodError at runtime.
open class Video(
    var videoUrl: String = "",
    val videoTitle: String = "",
    val resolution: Int? = null,
    val bitrate: Int? = null,
    val headers: Headers? = null,
    val preferred: Boolean = false,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
    val mpvArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
    val internalData: String = "",
    val initialized: Boolean = false,
) {

    // Legacy field from the pre-v16 API that distinguished the embed/page URL from
    // the stream URL. Not part of the current primary constructor; kept as a
    // mutable property for the older secondary constructors and Dantotsu's own
    // VideoServer wiring.
    var videoPageUrl: String = ""

    @Deprecated("Use videoTitle instead", ReplaceWith("videoTitle"))
    val quality: String
        get() = videoTitle

    @Deprecated("Use videoPageUrl instead", ReplaceWith("videoPageUrl"))
    val url: String
        get() = videoPageUrl

    // Pre-v16 API constructor still used by older extensions.
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        headers: Headers? = null,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
    ) : this(
        videoUrl = videoUrl ?: "null",
        videoTitle = quality,
        headers = headers,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
    ) {
        this.videoPageUrl = url
    }

    // Pre-v16 API constructor that includes a Uri argument (kept for binary compat).
    @Suppress("UNUSED_PARAMETER")
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        uri: Uri? = null,
        headers: Headers? = null,
    ) : this(url, quality, videoUrl, headers)

    @Transient
    @Volatile
    var status: State = State.QUEUE
        set(value) {
            field = value
        }

    fun copy(
        videoUrl: String = this.videoUrl,
        videoTitle: String = this.videoTitle,
        resolution: Int? = this.resolution,
        bitrate: Int? = this.bitrate,
        headers: Headers? = this.headers,
        preferred: Boolean = this.preferred,
        subtitleTracks: List<Track> = this.subtitleTracks,
        audioTracks: List<Track> = this.audioTracks,
        timestamps: List<TimeStamp> = this.timestamps,
        mpvArgs: List<Pair<String, String>> = this.mpvArgs,
        ffmpegStreamArgs: List<Pair<String, String>> = this.ffmpegStreamArgs,
        ffmpegVideoArgs: List<Pair<String, String>> = this.ffmpegVideoArgs,
        internalData: String = this.internalData,
        initialized: Boolean = this.initialized,
        videoPageUrl: String = this.videoPageUrl,
    ): Video {
        return Video(
            videoUrl = videoUrl,
            videoTitle = videoTitle,
            resolution = resolution,
            bitrate = bitrate,
            headers = headers,
            preferred = preferred,
            subtitleTracks = subtitleTracks,
            audioTracks = audioTracks,
            timestamps = timestamps,
            mpvArgs = mpvArgs,
            ffmpegStreamArgs = ffmpegStreamArgs,
            ffmpegVideoArgs = ffmpegVideoArgs,
            internalData = internalData,
            initialized = initialized,
        ).also { it.videoPageUrl = videoPageUrl }
    }

    enum class State {
        QUEUE,
        LOAD_VIDEO,
        READY,
        ERROR,
    }
}

@kotlinx.serialization.Serializable
data class SerializableVideo(
    val videoUrl: String = "",
    val videoTitle: String = "",
    val resolution: Int? = null,
    val bitrate: Int? = null,
    val headers: List<Pair<String, String>>? = null,
    val preferred: Boolean = false,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
    val mpvArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
    val internalData: String = "",
    val initialized: Boolean = false,
    val videoPageUrl: String = "",
) {

    companion object {
        fun List<Video>.serialize(): String =
            Json.encodeToString(
                this.map { vid ->
                    SerializableVideo(
                        videoUrl = vid.videoUrl,
                        videoTitle = vid.videoTitle,
                        resolution = vid.resolution,
                        bitrate = vid.bitrate,
                        headers = vid.headers?.toList(),
                        preferred = vid.preferred,
                        subtitleTracks = vid.subtitleTracks,
                        audioTracks = vid.audioTracks,
                        timestamps = vid.timestamps,
                        mpvArgs = vid.mpvArgs,
                        ffmpegStreamArgs = vid.ffmpegStreamArgs,
                        ffmpegVideoArgs = vid.ffmpegVideoArgs,
                        internalData = vid.internalData,
                        initialized = vid.initialized,
                        videoPageUrl = vid.videoPageUrl,
                    )
                },
            )

        fun String.toVideoList(): List<Video> =
            Json.decodeFromString<List<SerializableVideo>>(this)
                .map { sVid ->
                    Video(
                        videoUrl = sVid.videoUrl,
                        videoTitle = sVid.videoTitle,
                        resolution = sVid.resolution,
                        bitrate = sVid.bitrate,
                        headers = sVid.headers
                            ?.flatMap { it.toList() }
                            ?.let { Headers.headersOf(*it.toTypedArray()) },
                        preferred = sVid.preferred,
                        subtitleTracks = sVid.subtitleTracks,
                        audioTracks = sVid.audioTracks,
                        timestamps = sVid.timestamps,
                        mpvArgs = sVid.mpvArgs,
                        ffmpegStreamArgs = sVid.ffmpegStreamArgs,
                        ffmpegVideoArgs = sVid.ffmpegVideoArgs,
                        internalData = sVid.internalData,
                        initialized = sVid.initialized,
                    ).also { it.videoPageUrl = sVid.videoPageUrl }
                }
    }
}
