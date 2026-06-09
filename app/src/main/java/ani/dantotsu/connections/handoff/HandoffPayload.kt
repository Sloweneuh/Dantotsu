package ani.dantotsu.connections.handoff

import android.net.Uri
import android.os.Build
import android.util.Base64
import ani.dantotsu.media.Media
import com.google.gson.Gson
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
) : Serializable {

    fun toJson(): String = Gson().toJson(this)

    val hasProgress: Boolean get() = number != null
    val isMangaUpdates: Boolean get() = muSeriesId != null

    /** MangaUpdates series URL, rebuildable by [muSeriesId] alone on the receiver. */
    val mangaUpdatesUrl: String?
        get() = muSeriesId?.let { "https://www.mangaupdates.com/series/${it.toString(36)}" }

    /** A scannable deep link encoding this payload (QR-code fallback transport). */
    fun toDeepLink(): String {
        val data = Base64.encodeToString(
            toJson().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$SCHEME://$HOST?d=$data"
    }

    companion object {
        // A dedicated scheme/host (not the shared "dantotsu://") so the QR link can't also match
        // the broadly-scoped Discord/AniList login filters and trigger an "open with" chooser.
        const val SCHEME = "dantotsuhandoff"
        const val HOST = "media"

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
        fun mediaOnly(media: Media, sourceName: String? = null): HandoffPayload = HandoffPayload(
            mediaId = media.id,
            isMAL = false,
            isAnime = media.anime != null,
            mediaType = if (media.anime != null) "ANIME" else "MANGA",
            title = media.userPreferredName,
            cover = media.cover,
            sourceName = sourceName,
            muSeriesId = media.muSeriesId,
        )
    }
}
