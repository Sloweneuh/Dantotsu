package ani.dantotsu.media.screenshot

/**
 * A rolling record of the text subtitles that were on screen during playback.
 *
 * Clips are taken retroactively — the user hits the button *after* the moment they want — and the
 * clip itself is re-cut from the source stream, which carries the subtitle track as data rather
 * than as rendered pixels. ASS/SSA tracks are fine (libass can re-render any timestamp on demand,
 * see [ClipSubtitleOverlay]), but plain text cues arrive from ExoPlayer only as they play. So the
 * player feeds them here as they go, and the exporter replays the window it needs.
 *
 * Only [windowMs] worth of cues are kept; anything older than the longest clip the user could ask
 * for is dropped. Cues are held as text, so the buffer stays tiny regardless of the window.
 */
class SubtitleCueBuffer(private val windowMs: Long) {

    /** A subtitle line and the playback interval it was visible for. */
    data class Cue(val text: String, val startMs: Long, val endMs: Long)

    /** Lines currently on screen, mapped to the position they appeared at. */
    private val open = LinkedHashMap<String, Long>()

    /** Lines that have since disappeared, oldest first. */
    private val closed = ArrayDeque<Cue>()

    /**
     * Records the set of lines visible at [positionMs]. Lines that were showing and no longer are
     * get closed off at this position; lines that weren't showing and now are start here.
     */
    @Synchronized
    fun record(positionMs: Long, texts: List<String>) {
        val visible = texts.filter { it.isNotBlank() }.toSet()

        val gone = open.keys.filter { it !in visible }
        gone.forEach { text ->
            val start = open.remove(text) ?: return@forEach
            // A cue that never advanced would export as a zero-length flash; skip it.
            if (positionMs > start) closed.addLast(Cue(text, start, positionMs))
        }

        visible.forEach { text -> open.getOrPut(text) { positionMs } }

        val cutoff = positionMs - windowMs
        while (closed.isNotEmpty() && closed.first().endMs < cutoff) closed.removeFirst()
    }

    /** True when no text cue has been seen at all, i.e. there is nothing a clip could burn in. */
    @Synchronized
    fun isEmpty() = open.isEmpty() && closed.isEmpty()

    /** Drops everything, e.g. when the episode changes. */
    @Synchronized
    fun clear() {
        open.clear()
        closed.clear()
    }

    /**
     * The cues overlapping `[fromMs, toMs]`, as an immutable snapshot safe to hand to the export
     * thread. Lines still on screen are closed off at [toMs], since that's as far as the clip goes.
     */
    @Synchronized
    fun snapshot(fromMs: Long, toMs: Long): List<Cue> {
        val stillOpen = open.map { (text, start) -> Cue(text, start, toMs) }
        return (closed + stillOpen)
            .filter { it.endMs > fromMs && it.startMs < toMs }
            .sortedBy { it.startMs }
    }
}
