package ani.dantotsu.tv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.handoff.HandoffNavigator
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable

/**
 * The TV's own "Continue watching" row (Android TV's Watch Next): what's being watched here, so an
 * episode carries on from the TV's home screen without opening the app first. One entry a title —
 * the episode under way, at where it was left, or the next one to start — kept to the AniList
 * "Continue watching" list, as TV mode's home has it. Selecting one opens the title and plays it.
 *
 * Only on a TV, the only place there's such a row; nothing is written while incognito.
 */
object TvWatchNext {

    private const val SCHEME = "dantotsutv"
    private const val HOST = "watch"
    private const val PROVIDER_PREFIX = "dantotsu:"

    /** Most titles kept in the row: the launcher shows a handful, and the rest only crowd it. */
    private const val MAX_ENTRIES = 10

    private fun available(context: Context) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && TvMode.isTvDevice(context) &&
            !PrefManager.getVal<Boolean>(PrefName.Incognito)

    /** The row brought in line with [continuing], the user's titles under way, most recent first. */
    fun sync(context: Context, continuing: List<Media>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !available(context)) return
        runCatching { syncNow(context, continuing.filter { it.anime != null }.take(MAX_ENTRIES)) }
            .onFailure { Logger.log("TvWatchNext: sync failed: ${it.message}") }
    }

    /** The player left [media] at [number]: that episode, at [positionMs] of [durationMs], first in the row. */
    fun watched(context: Context, media: Media, number: String, positionMs: Long, durationMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !available(context) || media.id < 0) return
        runCatching {
            // Watched to the end, as the player and the episode list judge it: what's next is the
            // next episode, from its start.
            val done = ani.dantotsu.media.anime.watchedToEnd(positionMs, durationMs)
            val shown = if (done) nextNumber(number) ?: return@runCatching remove(context, media.id) else number
            upsert(context, media, shown, if (done) 0L else positionMs, durationMs, System.currentTimeMillis())
        }.onFailure { Logger.log("TvWatchNext: update failed: ${it.message}") }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun syncNow(context: Context, continuing: List<Media>) {
        val existing = entries(context)
        val keep = continuing.mapTo(HashSet()) { it.id }
        existing.filterKeys { it !in keep }.values.forEach { delete(context, it.rowId) }
        val now = System.currentTimeMillis()
        continuing.forEachIndexed { i, media ->
            val number = ((media.userProgress ?: 0) + 1).toString()
            // Not yet out: nothing to carry on with. The next to air isn't out; up to the total is.
            val anime = media.anime
            val released = when {
                anime == null -> false
                anime.nextAiringEpisode != null -> number.toInt() < anime.nextAiringEpisode!!
                anime.totalEpisodes != null -> number.toInt() <= anime.totalEpisodes!!
                else -> true
            }
            if (!released) {
                existing[media.id]?.let { delete(context, it.rowId) }
                return@forEachIndexed
            }
            val position = PrefManager.getCustomVal("${media.id}_$number", 0L)
            val duration = PrefManager.getCustomVal("${media.id}_${number}_max", 0L).takeIf { it != Long.MAX_VALUE } ?: 0L
            val entry = existing[media.id]
            // One the user took out of the row stays out, until there's another episode to offer.
            if (entry != null && !entry.browsable && entry.number == number) return@forEachIndexed
            // Most recent first: the order the list came in.
            upsert(context, media, number, position, duration, entry?.engaged?.takeIf { entry.number == number } ?: (now - i))
        }
    }

    private fun nextNumber(number: String): String? = number.toFloatOrNull()?.let { (it.toInt() + 1).toString() }

    private class Entry(val rowId: Long, val number: String?, val browsable: Boolean, val engaged: Long)

    @RequiresApi(Build.VERSION_CODES.O)
    private fun entries(context: Context): Map<Int, Entry> {
        val out = HashMap<Int, Entry>()
        context.contentResolver.query(
            TvContract.WatchNextPrograms.CONTENT_URI,
            arrayOf(
                TvContract.WatchNextPrograms._ID,
                TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val provider = c.getString(1) ?: continue
                val id = provider.removePrefix(PROVIDER_PREFIX).takeIf { provider.startsWith(PROVIDER_PREFIX) }?.toIntOrNull() ?: continue
                out[id] = Entry(c.getLong(0), c.getString(2), c.getInt(3) == 1, c.getLong(4))
            }
        }
        return out
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun upsert(context: Context, media: Media, number: String, positionMs: Long, durationMs: Long, engaged: Long) {
        val values = ContentValues().apply {
            put(TvContract.WatchNextPrograms.COLUMN_TYPE, TvContract.WatchNextPrograms.TYPE_TV_EPISODE)
            put(
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                if (positionMs > 0) TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                else TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT,
            )
            put(TvContract.WatchNextPrograms.COLUMN_TITLE, media.userPreferredName)
            put(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, number)
            put(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, context.getString(R.string.tv_episode_n, number))
            (media.banner ?: media.cover)?.let { put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, it) }
            put(
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                if (media.banner != null) TvContract.WatchNextPrograms.ASPECT_RATIO_16_9
                else TvContract.WatchNextPrograms.ASPECT_RATIO_2_3,
            )
            put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, intentUri(media.id, number).toString())
            put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, PROVIDER_PREFIX + media.id)
            if (positionMs > 0) put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, positionMs.toInt())
            if (durationMs > 0) put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, durationMs.toInt())
            put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, engaged)
            put(TvContract.WatchNextPrograms.COLUMN_BROWSABLE, 1)
        }
        val existing = entries(context)[media.id]
        if (existing != null) {
            context.contentResolver.update(
                ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, existing.rowId), values, null, null,
            )
        } else {
            context.contentResolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, values)
        }
    }

    private fun remove(context: Context, mediaId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        entries(context)[mediaId]?.let { delete(context, it.rowId) }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun delete(context: Context, rowId: Long) {
        context.contentResolver.delete(ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, rowId), null, null)
    }

    private fun intentUri(mediaId: Int, number: String): Uri =
        Uri.Builder().scheme(SCHEME).authority(HOST).appendQueryParameter("id", mediaId.toString())
            .appendQueryParameter("ep", number).build()

    /** The title and episode an entry's link names, or null when it isn't one of ours. */
    fun parse(uri: Uri?): Pair<Int, String>? {
        if (uri?.scheme != SCHEME || uri.host != HOST) return null
        val id = uri.getQueryParameter("id")?.toIntOrNull() ?: return null
        return id to (uri.getQueryParameter("ep") ?: return null)
    }
}

/**
 * Where the TV home screen's "Continue watching" entries lead: the title's TV page, playing the
 * episode, as a handoff to this TV does.
 */
class TvWatchNextActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (id, number) = TvWatchNext.parse(intent?.data) ?: return finish()
        lifecycleScope.launch {
            val media = withContext(Dispatchers.IO) {
                if (Anilist.token == null) Anilist.getSavedToken()
                runCatching { Anilist.query.getMedia(id) }.getOrNull()
            }
            if (media == null) {
                snackString(getString(R.string.handoff_media_not_found))
            } else {
                startActivity(
                    Intent(this@TvWatchNextActivity, TvMediaActivity::class.java)
                        .putExtra("media", media as Serializable)
                        .putExtra(HandoffNavigator.EXTRA_IS_ANIME, true)
                        .putExtra(HandoffNavigator.EXTRA_AUTO_START, true)
                        .putExtra(HandoffNavigator.EXTRA_NUMBER, number)
                )
            }
            finish()
        }
    }
}
