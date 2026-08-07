package ani.dantotsu.media.manga.mangareader

import ani.dantotsu.media.manga.mangareader.BaseImageAdapter.Companion.loadBitmap
import ani.dantotsu.parsers.MangaImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Decodes the pages just past the viewport into the caches the on-screen load reads from, so they
 * are already there by the time the reader reaches them.
 *
 * The scrolling reader had nothing doing this. [PreloadLinearLayoutManager] looks like it does, and
 * `preloadAmount` is presented as how many pages to load ahead, but the only caller of
 * `collectAdjacentPrefetchPositions` is RecyclerView's `GapWorker` — which runs off the scroll
 * itself and hands it the scroll delta, and the override returns immediately when that delta is
 * zero. So the setting really meant "prefetch further ahead *while a scroll is in flight*": a page
 * one screen down would sit untouched for as long as you left it there and only begin loading once
 * it came into view. Paged mode never had the problem, since `ViewPager2.offscreenPageLimit` binds
 * its neighbours eagerly whether or not anything is moving.
 *
 * Warming a cache instead of driving RecyclerView is what makes this independent of scrolling.
 * [BaseImageAdapter.loadBitmap] puts what it decodes into [ani.dantotsu.media.manga.MangaCache]
 * (extension sources) or Glide's memory cache (plain urls), under a key built from the url *and*
 * the transforms — hence [MangaReaderActivity.pageTransforms] as the single place both this and the
 * real load build that list, because a mismatch would fill a key nothing ever reads.
 *
 * Everything here runs on the main thread. The window scan walks adapter lists that are mutated
 * there (a chapter appended mid-scroll), and `loadBitmap` moves to IO for the fetch and decode by
 * itself.
 */
class PagePrefetcher(
    private val activity: MangaReaderActivity,
    /** Passed through to [BaseImageAdapter.loadBitmap]; it shapes the bitmap that gets cached. */
    private val maxHeightOverride: Int?,
    /** The page(s) shown at a position — empty where the position holds something else. */
    private val pagesAt: (position: Int) -> List<MangaImage>,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Urls fetched or in flight, so repeated calls don't queue the same page twice. */
    private val claimed = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val workers = mutableListOf<Job>()

    private var anchor = 0
    private var window = 0

    /** Set once a scan finds the whole window already warm; cleared when the window moves. */
    private var exhausted = false

    /**
     * Warm the [count] pages after [position]. Meant to be called on every scroll event: it only
     * moves the window, and a worker already running picks the new one up before its next fetch.
     */
    fun warmAfter(position: Int, count: Int) {
        if (count <= 0) return
        val moved = position != anchor || count != window
        anchor = position
        window = count
        // Standing still with nothing left to fetch must not start a worker per frame. `onScrolled`
        // fires on every frame of a fling, and a scan that finds the window already warm is pure
        // garbage in the reader's hottest path. The cost of the guard is that pages appearing under
        // a stationary window — the next chapter being appended — wait for the next scroll, which
        // is the point at which they start to matter anyway.
        if (!moved && exhausted) return
        exhausted = false
        workers.removeAll { it.isCompleted }
        repeat(WORKERS - workers.size) { workers += scope.launch { drain() } }
    }

    /** Drops what's in flight, for good — the chapter changed, or the reader is going away. */
    fun cancel() {
        scope.cancel()
        workers.clear()
        claimed.clear()
    }

    private suspend fun drain() {
        while (true) {
            val (url, image) = nextWanted() ?: run { exhausted = true; return }
            val bitmap = with(activity) {
                loadBitmap(image.url, activity.pageTransforms(image), maxHeightOverride)
            }
            if (bitmap == null) {
                // Let the page be tried again, but stop this pass. A failure here is nearly always
                // the connection rather than the one page, and the fetch path reports each one to
                // the user — marching on through the window would turn a dropped connection into a
                // column of toasts about pages nobody has even reached yet.
                claimed.remove(url)
                return
            }
        }
    }

    /** The next unclaimed page in the window, re-read each time so scrolling redirects the work. */
    private fun nextWanted(): Pair<String, MangaImage>? {
        for (position in anchor + 1..anchor + window) {
            pagesAt(position).forEach { image ->
                val url = image.url.url
                if (isWorthWarming(url) && claimed.add(url)) return url to image
            }
        }
        return null
    }

    /**
     * Only the remote paths through [BaseImageAdapter.loadBitmap] keep their result: a downloaded
     * page is decoded straight off disk with both Glide caches turned off, and a pdf page is
     * rendered on demand, so warming either would just pay for the decode twice.
     */
    private fun isWorthWarming(url: String): Boolean = url.startsWith("http")

    private companion object {
        /** Concurrent decodes — enough to hide latency without crowding out the visible page. */
        const val WORKERS = 2
    }
}
