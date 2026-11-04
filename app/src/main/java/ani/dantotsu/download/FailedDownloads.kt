package ani.dantotsu.download

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import ani.dantotsu.App
import ani.dantotsu.R
import ani.dantotsu.currActivity
import ani.dantotsu.download.anime.AnimeDownloaderService
import ani.dantotsu.download.anime.AnimeServiceDataSingleton
import ani.dantotsu.download.manga.MangaDownloaderService
import ani.dantotsu.download.manga.MangaServiceDataSingleton
import ani.dantotsu.download.novel.NovelDownloaderService
import ani.dantotsu.download.novel.NovelServiceDataSingleton
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.MediaType
import ani.dantotsu.openMangaUpdatesSeriesInApp
import ani.dantotsu.util.InAppMessages
import ani.dantotsu.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** A download that failed and hasn't been retried or dismissed. */
@Serializable
data class FailedDownload(
    val id: String,
    val type: MediaType,
    val mediaId: Int,
    val muSeriesId: Long? = null,
    val serviceKey: String,
    val title: String,
    val coverUrl: String? = null,
    val label: String,
    val reason: String? = null,
    val failedAt: Long = 0,
)

/**
 * The downloads that failed, kept until they are retried, dismissed or downloaded again: a message
 * can expire before anyone sees it, this list doesn't.
 *
 * Kept in a preferences file of its own, on this device only. It isn't part of [ani.dantotsu.settings.saving.PrefManager]
 * and so never reaches a backup or the cloud: failures belong to this device's storage and network.
 *
 * What retrying needs is held in memory only, since a task holds objects that can't be saved, such
 * as the source's video. After a restart a failure can still be listed and its title reopened, to
 * start the download again from there.
 */
object FailedDownloads {
    private const val PREFS = "failed_downloads"
    private const val KEY = "list"
    private const val MAX_KEPT = 50
    private const val SUMMARY_DELAY_MILLIS = 1_500L

    private val json = Json { ignoreUnknownKeys = true }
    private val main = Handler(Looper.getMainLooper())
    private val requeues = ConcurrentHashMap<String, () -> Unit>()

    private val _items by lazy { MutableStateFlow(load()) }
    val items: StateFlow<List<FailedDownload>> get() = _items.asStateFlow()

    val count: Int get() = _items.value.size

    /** Failures since the last summary message, so that a run of them makes a single one. */
    private var unreported = 0
    private val reportSummary = Runnable {
        val failed = unreported
        unreported = 0
        if (failed <= 0 || currActivity() is DownloadActivity) return@Runnable
        val context = currActivity() ?: App.context ?: return@Runnable
        val text = context.resources.getQuantityString(R.plurals.downloads_failed_summary, failed, failed)
        // TV mode's downloads screen doesn't list failures, and this one isn't built for a remote.
        if (ani.dantotsu.tv.TvMode.isOn(context)) {
            InAppMessages.post(text, long = true)
            return@Runnable
        }
        InAppMessages.post(
            text,
            long = true,
            actionRes = R.string.view,
            onAction = {
                currActivity()?.let { it.startActivity(Intent(it, DownloadActivity::class.java)) }
            },
        )
    }

    private fun prefs() = App.context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(): List<FailedDownload> = try {
        prefs()?.getString(KEY, null)
            ?.let { json.decodeFromString<List<FailedDownload>>(it) }
            .orEmpty()
    } catch (e: Exception) {
        Logger.log(e)
        emptyList()
    }

    private fun set(list: List<FailedDownload>) {
        _items.value = list
        prefs()?.edit()?.putString(KEY, json.encodeToString(list))?.apply()
    }

    /** [requeue] puts the task back on its service's queue. */
    @Synchronized
    fun record(item: DownloadItem, muSeriesId: Long?, reason: String?, requeue: () -> Unit) {
        val entry = FailedDownload(
            id = item.id,
            type = item.type,
            mediaId = item.mediaId,
            muSeriesId = muSeriesId,
            serviceKey = item.serviceKey,
            title = item.title,
            coverUrl = item.coverUrl,
            label = item.label,
            reason = reason?.takeIf { it.isNotBlank() },
            failedAt = System.currentTimeMillis(),
        )
        val list = listOf(entry) + _items.value.filterNot { it.id == entry.id }
        list.drop(MAX_KEPT).forEach { requeues.remove(it.id) }
        set(list.take(MAX_KEPT))
        requeues[entry.id] = requeue
        main.post {
            unreported++
            main.removeCallbacks(reportSummary)
            main.postDelayed(reportSummary, SUMMARY_DELAY_MILLIS)
        }
    }

    /** Whether this session still holds what [retry] needs. */
    fun canRetry(id: String): Boolean = requeues.containsKey(id)

    /** Whether there is a title page to reopen, for a failure left over from an earlier session. */
    fun canOpen(entry: FailedDownload): Boolean = entry.muSeriesId != null || entry.mediaId > 0

    fun retry(context: Context, entry: FailedDownload) {
        val requeue = requeues.remove(entry.id) ?: return
        requeue()
        // Takes the failure off this list too, as queueing it any other way does.
        DownloadTracker.enqueue(
            DownloadItem(
                id = entry.id,
                type = entry.type,
                mediaId = entry.mediaId,
                serviceKey = entry.serviceKey,
                title = entry.title,
                coverUrl = entry.coverUrl,
                label = entry.label,
            )
        )
        startService(context, entry.type)
    }

    fun retryAll(context: Context) {
        _items.value.filter { canRetry(it.id) }.forEach { retry(context, it) }
    }

    fun open(context: Context, entry: FailedDownload) {
        val muId = entry.muSeriesId
        if (muId != null) {
            openMangaUpdatesSeriesInApp("https://www.mangaupdates.com/series/${muId.toString(36)}")
        } else if (entry.mediaId > 0) {
            context.startActivity(
                Intent(context, MediaDetailsActivity::class.java).putExtra("mediaId", entry.mediaId)
            )
        }
    }

    /** Forgets a failure: dismissed, or the same item queued again. */
    @Synchronized
    fun forget(id: String) {
        requeues.remove(id)
        if (_items.value.any { it.id == id }) set(_items.value.filterNot { it.id == id })
    }

    @Synchronized
    fun clear() {
        requeues.clear()
        set(emptyList())
    }

    private fun startService(context: Context, type: MediaType) {
        when (type) {
            MediaType.MANGA -> if (!MangaServiceDataSingleton.isServiceRunning) {
                ContextCompat.startForegroundService(
                    context, Intent(context, MangaDownloaderService::class.java)
                )
                MangaServiceDataSingleton.isServiceRunning = true
            }

            MediaType.ANIME -> if (!AnimeServiceDataSingleton.isServiceRunning) {
                ContextCompat.startForegroundService(
                    context, Intent(context, AnimeDownloaderService::class.java)
                )
                AnimeServiceDataSingleton.isServiceRunning = true
            }

            MediaType.NOVEL -> if (!NovelServiceDataSingleton.isServiceRunning) {
                ContextCompat.startForegroundService(
                    context, Intent(context, NovelDownloaderService::class.java)
                )
                NovelServiceDataSingleton.isServiceRunning = true
            }
        }
    }
}
