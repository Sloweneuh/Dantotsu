package ani.dantotsu.connections.handoff

import android.content.Context
import android.content.Intent
import android.net.Uri
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.snackString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Serializable

/**
 * Applies a received [HandoffPayload] on this device: seeds the saved progress (so the
 * chapter/episode resumes at the exact page/timestamp) and opens the right details screen,
 * auto-launching straight into the reader/player when the handoff carried a position.
 *
 * Works for AniList/MAL media (re-fetched via [Anilist]) and MangaUpdates media (reopened via
 * the MangaUpdates series deep link that [MUMediaDetailsActivity] already understands).
 *
 * Auto-launch and the source-installed check are performed by the read/watch fragment once its
 * sources have loaded; this navigator only forwards the request via intent extras.
 */
object HandoffNavigator {

    const val EXTRA_AUTO_START = "handoffAutoStart"
    const val EXTRA_NUMBER = "handoffNumber"
    const val EXTRA_SOURCE = "handoffSourceName"
    const val EXTRA_IS_ANIME = "handoffIsAnime"
    const val EXTRA_SERVER = "handoffServer"
    const val EXTRA_SOURCE_MEDIA = "handoffSourceMedia"
    const val EXTRA_TITLE = "handoffTitle"
    const val EXTRA_COVER = "handoffCover"
    const val EXTRA_SENDER = "handoffSender"

    /** Must be called from a coroutine; an AniList handoff performs a network fetch. */
    suspend fun navigate(context: Context, payload: HandoffPayload) {
        if (payload.isMangaUpdates) navigateMangaUpdates(context, payload)
        else navigateAniList(context, payload)
    }

    private suspend fun navigateAniList(context: Context, payload: HandoffPayload) {
        val media = withContext(Dispatchers.IO) {
            // A cold start via the QR/deep link (app was closed) skips the normal session
            // restore, so the AniList token may not be loaded yet. Without it getMedia comes
            // back without the user's list entry (their progress). Restore it first.
            if (Anilist.token == null) Anilist.getSavedToken()
            Anilist.query.getMedia(payload.mediaId, payload.isMAL, payload.mediaType)
        }
        if (media == null) {
            withContext(Dispatchers.Main) {
                snackString(context.getString(R.string.handoff_media_not_found))
            }
            return
        }
        if (payload.hasProgress) {
            seedProgress(media.id, payload)
            media.cameFromContinue = true
        }
        withContext(Dispatchers.Main) {
            snackString(context.getString(R.string.handoff_received, media.userPreferredName))
            context.startActivity(
                Intent(context, MediaDetailsActivity::class.java)
                    .putExtra("media", media as Serializable)
                    .applyHandoffExtras(payload)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private suspend fun navigateMangaUpdates(context: Context, payload: HandoffPayload) {
        val url = payload.mangaUpdatesUrl ?: return
        // MU media ids are the series id truncated to Int; the reader keys progress by it.
        if (payload.hasProgress) seedProgress(payload.mediaId, payload)
        withContext(Dispatchers.Main) {
            snackString(context.getString(R.string.handoff_received, payload.title ?: ""))
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .setClass(context, MUMediaDetailsActivity::class.java)
                    .applyHandoffExtras(payload)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Seeds the resume keys the reader/player read on open (mirrors what they write). */
    private fun seedProgress(id: Int, payload: HandoffPayload) {
        val number = payload.number ?: return
        if (payload.isAnime) {
            PrefManager.setCustomVal("${id}_current_ep", number)
            payload.positionMs?.let { PrefManager.setCustomVal("${id}_$number", it) }
        } else {
            PrefManager.setCustomVal("${id}_current_chp", number)
            payload.page?.let { PrefManager.setCustomVal("${id}_$number", it) }
            // Carry over the sender's progress-tracking choice so the multi-chapter reader
            // doesn't prompt on auto-open (the fragment skips the dialog for handoffs).
            PrefManager.setCustomVal("${id}_save_progress", payload.trackProgress)
        }
    }

    private fun Intent.applyHandoffExtras(payload: HandoffPayload): Intent {
        // Always forward the source name so the receiver can select the same extension,
        // even for media-only handoffs that don't auto-start a chapter/episode.
        payload.sourceName?.let { putExtra(EXTRA_SOURCE, it) }
        // Forward the sender's matched extension entry so the read/watch fragment can seed it and
        // load the chapter/episode list directly, rather than re-searching the source by title.
        payload.decodedSourceMedia?.let { putExtra(EXTRA_SOURCE_MEDIA, it as Serializable) }
        putExtra(EXTRA_IS_ANIME, payload.isAnime)
        // Display info for the receiving device's loading overlay, so the user can see what's
        // being opened instead of a blank spinner.
        payload.title?.let { putExtra(EXTRA_TITLE, it) }
        payload.cover?.let { putExtra(EXTRA_COVER, it) }
        putExtra(EXTRA_SENDER, payload.senderName)
        if (payload.hasProgress) {
            putExtra(EXTRA_AUTO_START, true)
            putExtra(EXTRA_NUMBER, payload.number)
            payload.server?.let { putExtra(EXTRA_SERVER, it) }
        }
        return this
    }
}
