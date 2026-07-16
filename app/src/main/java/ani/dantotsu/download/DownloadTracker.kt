package ani.dantotsu.download

import android.content.Context
import android.content.Intent
import ani.dantotsu.download.anime.AnimeDownloaderService
import ani.dantotsu.download.anime.AnimeServiceDataSingleton
import ani.dantotsu.download.manga.MangaDownloaderService
import ani.dantotsu.download.manga.MangaServiceDataSingleton
import ani.dantotsu.download.novel.NovelDownloaderService
import ani.dantotsu.download.novel.NovelServiceDataSingleton
import ani.dantotsu.media.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DownloadState { QUEUED, DOWNLOADING, FAILED }

/**
 * A single in-flight (queued or downloading) item shown in the download queue UI.
 *
 * [serviceKey] is the per-service task key used to cancel/reorder within that service's queue
 * (manga: chapter, anime: getTaskName(), novel: chapter). [coverUrl] is only a network fallback —
 * the UI prefers the locally saved cover so it renders offline.
 */
data class DownloadItem(
    val id: String,
    val type: MediaType,
    val mediaId: Int,
    val serviceKey: String,
    val title: String,
    val coverUrl: String?,
    val label: String,
    val state: DownloadState = DownloadState.QUEUED,
    val percent: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val speedBps: Long = 0,
    val etaMs: Long = -1,
)

/**
 * Process-wide observable registry of active + queued downloads across all three services.
 * The services report into it; the download queue UI observes [items] and drives cancel/reorder.
 */
object DownloadTracker {
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    val activeCount: Int get() = _items.value.size

    // Total items added in the current download session (since the queue was last empty),
    // so the global progress ring only fills to 100% once every download is done — including
    // items added after it started. Reset when the queue drains.
    @Volatile
    private var sessionTotal = 0

    @Synchronized
    fun enqueue(item: DownloadItem) {
        if (_items.value.any { it.id == item.id }) return
        if (_items.value.isEmpty()) sessionTotal = 0
        sessionTotal++
        _items.value = _items.value + item
    }

    /**
     * Overall progress across the whole session (0-100): completed/cancelled items count as
     * done, in-flight items contribute their own percent, and the denominator grows as new
     * items are queued — so it reaches 100% only when nothing is left.
     */
    @Synchronized
    fun overallPercent(): Int {
        val items = _items.value
        if (sessionTotal <= 0) return 0
        val finished = (sessionTotal - items.size).coerceAtLeast(0)
        val inProgress = items.sumOf { it.percent.coerceIn(0, 100) } / 100.0
        return ((finished + inProgress) / sessionTotal * 100).toInt().coerceIn(0, 100)
    }

    @Synchronized
    fun markDownloading(id: String) {
        _items.value = _items.value.map {
            if (it.id == id) it.copy(state = DownloadState.DOWNLOADING) else it
        }
    }

    @Synchronized
    fun updateProgress(
        id: String,
        percent: Int,
        bytesDone: Long,
        bytesTotal: Long,
        speedBps: Long,
        etaMs: Long
    ) {
        _items.value = _items.value.map {
            if (it.id == id) it.copy(
                state = DownloadState.DOWNLOADING,
                percent = percent,
                bytesDone = bytesDone,
                bytesTotal = bytesTotal,
                speedBps = speedBps,
                etaMs = etaMs
            ) else it
        }
    }

    @Synchronized
    fun markFailed(id: String) {
        _items.value = _items.value.map {
            if (it.id == id) it.copy(state = DownloadState.FAILED) else it
        }
    }

    @Synchronized
    fun remove(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
    }

    /** Removes any items matching a service task key (used by service cancel receivers). */
    @Synchronized
    fun removeByKey(type: MediaType, serviceKey: String) {
        _items.value = _items.value.filterNot { it.type == type && it.serviceKey == serviceKey }
    }

    @Synchronized
    fun clear() {
        _items.value = emptyList()
    }

    /** Builds the stable id for an item from its type + media + service key. */
    fun idOf(type: MediaType, title: String, serviceKey: String): String =
        "$type:$title:$serviceKey"

    /**
     * Cancels a download (queued or in-progress). Reuses each service's existing
     * ACTION_CANCEL_DOWNLOAD receiver, which also deletes any partially downloaded files.
     */
    fun cancel(context: Context, item: DownloadItem) {
        val intent = when (item.type) {
            MediaType.MANGA -> Intent(MangaDownloaderService.ACTION_CANCEL_DOWNLOAD)
                .putExtra(MangaDownloaderService.EXTRA_CHAPTER, item.serviceKey)

            MediaType.ANIME -> Intent(AnimeDownloaderService.ACTION_CANCEL_DOWNLOAD)
                .putExtra(AnimeDownloaderService.EXTRA_TASK_NAME, item.serviceKey)

            MediaType.NOVEL -> Intent(NovelDownloaderService.ACTION_CANCEL_DOWNLOAD)
                .putExtra(NovelDownloaderService.EXTRA_CHAPTER, item.serviceKey)
        }
        context.sendBroadcast(intent)
        remove(item.id)
    }

    /** Moves the queued item [fromId] to the position of [toId]. Active items don't move. */
    @Synchronized
    fun moveQueued(fromId: String, toId: String) {
        val list = _items.value.toMutableList()
        val from = list.indexOfFirst { it.id == fromId }
        val to = list.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0 || from == to) return
        if (list[from].state != DownloadState.QUEUED || list[to].state != DownloadState.QUEUED) return
        val moved = list.removeAt(from)
        list.add(to, moved)
        _items.value = list
        syncQueueOrder(moved.type)
    }

    /** Applies a full display order (e.g. after a drag), then syncs the service queues. */
    @Synchronized
    fun applyQueuedOrder(orderedIds: List<String>) {
        val byId = _items.value.associateBy { it.id }
        val ordered = orderedIds.mapNotNull { byId[it] }
        val rest = _items.value.filter { it.id !in orderedIds }
        _items.value = ordered + rest
        _items.value.map { it.type }.distinct().forEach { syncQueueOrder(it) }
    }

    /** Pushes the queued display order down into the matching service's actual queue. */
    private fun syncQueueOrder(type: MediaType) {
        val orderedKeys = _items.value
            .filter { it.type == type && it.state == DownloadState.QUEUED }
            .map { it.serviceKey }
        when (type) {
            MediaType.MANGA -> MangaServiceDataSingleton.reorderQueue(orderedKeys)
            MediaType.ANIME -> AnimeServiceDataSingleton.reorderQueue(orderedKeys)
            MediaType.NOVEL -> NovelServiceDataSingleton.reorderQueue(orderedKeys)
        }
    }
}
