package ani.dantotsu.media.manga.translation

import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Translates pages as they scroll into view, one at a time.
 *
 * Asking page by page is fine for checking a bubble and hopeless for reading a chapter, so this is
 * what makes the feature usable — but it is also the thing that spends an API quota with nobody
 * pressing anything, which is why it is off by default and why every decision here is on the
 * cautious side of the same line.
 *
 * **One page at a time, never in parallel.** Two pages in flight would halve the wait and double
 * the rate at which a free tier is hit, and a page the reader has already scrolled past is a page
 * whose translation nobody will ever see. Serial also means the page being looked at finishes
 * before the lookahead behind it starts.
 *
 * **The queue is replaced, not appended to.** Every scroll hands over what is on screen now,
 * nearest first, and whatever was queued for where the reader used to be is dropped. Appending
 * would build a backlog that translates the chapter in the order it was scrolled through rather
 * than the order it is being read in.
 *
 * **A page is attempted once.** A failure that repeats is a failure that repeats on every scroll
 * event, which for a dead API key is a burst of doomed requests rather than one. Anything worth
 * trying again — the user fixing that key — comes back through [reset].
 */
class AutoTranslator(
    private val scope: CoroutineScope,
    /**
     * Whether a run may happen at all right now — the feature on, this media asking for it, and an
     * engine that can be used. Asked on every call rather than once, since all three can change
     * while the reader is open.
     */
    private val active: () -> Boolean,
    /**
     * Translates one page.
     *
     * @return false where the failure is one that will happen again for every other page too — no
     *   key, a rejected key, a model the provider does not serve — so the run stops rather than
     *   walking the chapter making the same doomed request.
     */
    private val translate: suspend (MangaImage) -> Boolean,
) {

    /** Pages already tried, so a scroll back over them does not spend the quota twice. */
    private val attempted = HashSet<String>()

    private val queue = ArrayList<MangaImage>()
    private var job: Job? = null

    /** Stopped by a failure that would repeat; cleared only by [reset]. */
    private var halted = false

    /**
     * What the reader is showing, and what it is about to show, nearest first.
     *
     * Safe to call on every scroll event: it does nothing at all unless the set of untried pages
     * has changed.
     */
    fun onVisible(pages: List<MangaImage>) {
        if (halted || !active()) return
        val wanted = pages.filter { page ->
            val key = page.url.url
            key !in attempted && TranslatedPages[key] == null
        }
        if (wanted.isEmpty()) {
            queue.clear()
            return
        }
        // Nothing to do only while something is already working through this exact list. Without
        // that condition a run that ended — cancelled, or halted and then reset — would never
        // restart, because the queue it left behind still matches what is on screen.
        if (job?.isActive == true && wanted.map { it.url.url } == queue.map { it.url.url }) return
        queue.clear()
        queue += wanted
        pump()
    }

    private fun pump() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (queue.isNotEmpty() && isActive) {
                val next = queue.removeAt(0)
                val key = next.url.url
                if (!attempted.add(key)) continue
                if (TranslatedPages[key] != null) continue
                // One line per page, which is the only trace this run leaves. Nobody pressed
                // anything to start it, so without this "nothing happened" and "nothing was ever
                // asked for" are the same log.
                Logger.log("MTL auto: translating $key")
                if (!translate(next)) {
                    halted = true
                    queue.clear()
                    return@launch
                }
            }
        }
    }

    /** Stops the run and forgets what was queued, leaving what has been tried remembered. */
    fun cancel() {
        job?.cancel()
        job = null
        queue.clear()
    }

    /**
     * Starts over, as after the user changes engine, key or target language.
     *
     * Pages already translated stay translated — see [TranslatedPages] on why an answer once given
     * is kept — so this only reopens the ones that were never done.
     */
    fun reset() {
        cancel()
        attempted.clear()
        halted = false
    }
}
