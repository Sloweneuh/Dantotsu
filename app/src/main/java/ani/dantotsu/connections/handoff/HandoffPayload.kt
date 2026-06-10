package ani.dantotsu.connections.handoff

import android.net.Uri
import android.os.Build
import android.util.Base64
import ani.dantotsu.media.Media
import ani.dantotsu.parsers.ShowResponse
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/** This device's display name, stamped onto outgoing handoffs so the receiver can show it. */
private fun localDeviceName(): String =
    "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Dantotsu device" }

/**
 * The minimal state needed to resume a media on another device.
 *
 * Identity is the AniList/MAL media id (or, for MangaUpdates media, [muSeriesId]), so the
 * receiver can re-fetch the full [Media] over the network instead of shipping the whole object.
 * Progress is optional: a "media only" handoff (e.g. from a details page) leaves
 * [number]/[page]/[positionMs] null and simply opens the media on the other device.
 *
 * [sourceName] is the extension source the sender was reading/watching with; the receiver uses
 * it to verify the same extension is installed before trying to open the reader/player directly.
 */
data class HandoffPayload(
    val mediaId: Int,
    val isMAL: Boolean,
    val isAnime: Boolean,
    val mediaType: String?,          // "ANIME" / "MANGA", forwarded to Anilist.query.getMedia
    val title: String?,             // for display on the receiving device only
    val cover: String? = null,      // cover image URL, for the receiving device's prompt only
    val sourceName: String? = null, // extension source name used by the sender
    val number: String? = null,     // chapter or episode number, null = media-only handoff
    val page: Long? = null,         // manga page within [number]
    val positionMs: Long? = null,   // anime playback position within [number]
    val trackProgress: Boolean = true, // sender's "save progress" choice, so the receiver's
                                       // multi-chapter reader doesn't re-prompt on auto-open
    val muSeriesId: Long? = null,   // set => MangaUpdates media (opened via MU deep link)
    val server: String? = null,     // video server the sender was watching on
    val senderName: String = localDeviceName(), // the sending device's name, for the prompt
    val sourceMedia: String? = null, // Base64(Java-serialized ShowResponse) the sender matched in
                                     // its extension, so the receiver can load the exact chapter/
                                     // episode list directly instead of re-searching by title
                                     // (which can miss or match the wrong entry, leaving the list
                                     // empty). Carried as a blob because the ShowResponse holds an
                                     // SManga/SAnime that Gson can't round-trip.
) : Serializable {

    /** Full payload (with [sourceMedia]) for the discovery transports, which have no size limit. */
    fun toJson(): String = Gson().toJson(this)

    /** The extension entry the sender matched, decoded back from [sourceMedia]. */
    val decodedSourceMedia: ShowResponse? get() = sourceMedia?.let { decodeShowResponse(it) }

    val hasProgress: Boolean get() = number != null
    val isMangaUpdates: Boolean get() = muSeriesId != null

    /** MangaUpdates series URL, rebuildable by [muSeriesId] alone on the receiver. */
    val mangaUpdatesUrl: String?
        get() = muSeriesId?.let { "https://www.mangaupdates.com/series/${it.toString(36)}" }

    /**
     * A scannable deep link encoding this payload (QR-code fallback transport).
     *
     * [sourceMedia] is dropped from the embedded data: a serialized ShowResponse is far too large
     * to fit in a scannable QR code. When [cloudCode] is supplied, the receiver can instead pull
     * the *full* payload (incl. sourceMedia) from [CloudHandoff] by that code for an exact source
     * match; the embedded lightweight data stays as an offline fallback (title search) for when the
     * cloud is unreachable.
     */
    fun toDeepLink(cloudCode: String? = null): String {
        val json = (if (sourceMedia == null) this else copy(sourceMedia = null)).let { Gson().toJson(it) }
        val data = Base64.encodeToString(
            json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val code = if (!cloudCode.isNullOrEmpty()) "&$QUERY_CODE=$cloudCode" else ""
        return "$SCHEME://$HOST?d=$data$code"
    }

    companion object {
        // A dedicated scheme/host (not the shared "dantotsu://") so the QR link can't also match
        // the broadly-scoped Discord/AniList login filters and trigger an "open with" chooser.
        const val SCHEME = "dantotsuhandoff"
        const val HOST = "media"
        // Query params: "d" = Base64 lightweight payload, "c" = optional CloudHandoff code that
        // resolves the full payload (with sourceMedia) for a smoother, exact-match transition.
        const val QUERY_CODE = "c"

        fun fromJson(json: String): HandoffPayload? = runCatching {
            Gson().fromJson(json, HandoffPayload::class.java)
        }.getOrNull()

        fun fromDeepLink(uri: Uri): HandoffPayload? {
            if (uri.scheme != SCHEME || uri.host != HOST) return null
            val data = uri.getQueryParameter("d") ?: return null
            return runCatching {
                fromJson(String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
            }.getOrNull()
        }

        /** Builds a media-only payload (no chapter/episode/position). */
        fun mediaOnly(
            media: Media,
            sourceName: String? = null,
            sourceMedia: ShowResponse? = null,
        ): HandoffPayload = HandoffPayload(
            mediaId = media.id,
            isMAL = false,
            isAnime = media.anime != null,
            mediaType = if (media.anime != null) "ANIME" else "MANGA",
            title = media.userPreferredName,
            cover = media.cover,
            sourceName = sourceName,
            muSeriesId = media.muSeriesId,
            sourceMedia = encodeShowResponse(sourceMedia),
        )

        /** Serializes a [ShowResponse] (incl. its SManga/SAnime) to a Base64 blob for the payload. */
        fun encodeShowResponse(response: ShowResponse?): String? = response?.let {
            runCatching {
                val bytes = ByteArrayOutputStream().apply {
                    ObjectOutputStream(this).use { oos -> oos.writeObject(response) }
                }.toByteArray()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }.getOrNull()
        }

        private fun decodeShowResponse(blob: String): ShowResponse? = runCatching {
            ObjectInputStream(ByteArrayInputStream(Base64.decode(blob, Base64.NO_WRAP))).use {
                it.readObject() as? ShowResponse
            }
        }.getOrNull()
    }
}
