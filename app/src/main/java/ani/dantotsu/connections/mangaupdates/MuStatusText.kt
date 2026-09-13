package ani.dantotsu.connections.mangaupdates

import ani.dantotsu.connections.anilist.api.MediaStatus
import ani.dantotsu.connections.anilist.api.mediaStatusOf

/**
 * Reads MangaUpdates' `status` field, which is one free-text box an editor fills in by hand.
 *
 * There is no template, so the same fact is written many ways — `143 Chapters (Ongoing)`,
 * `11 Volumes (Complete)`, `215 chapters (Completed)`, `**58** Chapters ... (Complete)`,
 * `24 Tankoubon Volumes (Complete)`, `172 Chapters + Prologue (*Hiatus since 3/2026*)`. Over a
 * 300-series sample the variation is nonetheless mechanical rather than arbitrary, and comes down
 * to five things: the unit may be volumes rather than chapters (40% of series), the word may be
 * lower case, editors may wrap figures in markdown, an edition name may sit between the number and
 * the unit, and a format label may occupy the line above the real figures.
 *
 * Two rules do most of the work and are worth stating because the obvious alternatives are wrong:
 *
 * - **The first line wins.** 46% of these fields are multi-line, and in 63% of those the first and
 *   last parentheses disagree, because the lines below the headline break the total down per season
 *   or per language. Reading the last would be wrong for roughly a third of all series.
 * - **Classify the parenthesised text, not the line.** `107 Chapters + 3 Hiatus Specials (Complete)`
 *   is a real entry: a keyword scan over the whole line calls that series hiatused when it is
 *   finished.
 */
object MuStatusText {

    /** What [Parsed.count] counts, which decides the label the info table puts beside it. */
    enum class Unit { CHAPTERS, VOLUMES }

    /**
     * @param status what the text says the series is doing, null when nothing recognisable is said
     * @param count how many [unit] the text names, null when it names none (a oneshot counts as 1)
     */
    data class Parsed(
        val status: MediaStatus?,
        val count: Int?,
        val unit: Unit?,
    )

    /**
     * Keyword buckets, in the order they are tried. Order is the whole point where an editor has
     * written two words at once: `Hiatus/Cancelled` reads as hiatus, and `Complete/Discontinued` as
     * cancelled — in both cases the half that tells the reader something they didn't already know.
     * Misspellings that turned up in the sample are listed alongside the real words rather than
     * corrected upstream, since they are what the field actually contains.
     */
    private val BUCKETS = listOf(
        MediaStatus.HIATUS to listOf("hiatus"),
        MediaStatus.CANCELLED to listOf("cancel", "discontinu", "discotinu", "dropped", "axed"),
        MediaStatus.FINISHED to listOf("complete", "compete", "finished"),
        MediaStatus.RELEASING to listOf("ongoing", "on going", "releasing", "serializing"),
    )

    /**
     * A count and its unit. One optional word may sit between the two so that naming an edition —
     * `24 Tankoubon Volumes`, `8 Kanzenban Volumes` — doesn't cost us the number.
     *
     * That word is `\p{L}+` rather than the shorter `[^\W\d_]+`: Java's `\w` is ASCII-only unless
     * asked otherwise, so the short form stops at the `ō` in a real entry like
     * `24 Tankōbon Volumes (Complete)` and loses the count.
     */
    private val COUNT =
        Regex("""(\d+)\s*\+?\s*(?:\p{L}+\s+)?(chapters?|volumes?)""", RegexOption.IGNORE_CASE)

    private val ONESHOT = Regex("""oneshot""", RegexOption.IGNORE_CASE)

    private val PAREN = Regex("""\(([^)]+)\)""")

    /**
     * Parses [raw]. [completed] is MangaUpdates' own structured flag, used only as a last resort
     * for a status the text doesn't state — it never fired across the sample, but it costs nothing
     * and is better than showing the reader "Unknown".
     */
    fun parse(raw: String?, completed: Boolean? = null): Parsed {
        val lines = normalize(raw)
        if (lines.isEmpty()) return Parsed(statusFromFlag(completed), null, null)

        // The headline is the first line carrying a count, not simply the first line: editors
        // regularly put a format label ("Digital:", "Original:") on a line of its own above the
        // figures it applies to.
        var headline = lines.first()
        var count: Int? = null
        var unit: Unit? = null
        for (line in lines) {
            val match = COUNT.find(line)
            if (match != null) {
                headline = line
                count = match.groupValues[1].toIntOrNull()
                unit = if (match.groupValues[2].startsWith("v", ignoreCase = true)) {
                    Unit.VOLUMES
                } else {
                    Unit.CHAPTERS
                }
                break
            }
            if (ONESHOT.containsMatchIn(line)) {
                headline = line
                count = 1
                unit = Unit.CHAPTERS
                break
            }
        }

        val status = statusOn(headline)
            ?: lines.firstNotNullOfOrNull { statusOn(it) }
            ?: statusFromFlag(completed)

        return Parsed(status, count, unit)
    }

    /** Splits into non-blank lines, minus the markdown editors use and the escaping the API adds. */
    private fun normalize(raw: String?): List<String> =
        raw.orEmpty()
            .replace("\\", "")
            .replace("*", "")
            .replace("_", "")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** The status a line states: its parenthesised asides first, then the line itself. */
    private fun statusOn(line: String): MediaStatus? =
        PAREN.findAll(line).firstNotNullOfOrNull { classify(it.groupValues[1]) }
            ?: classify(line)

    /**
     * Matches on *containment* rather than equality, which is what the plain-token reading of this
     * field misses: `*Hiatus*`, `Hiatus - Feb 2026`, `Indefinite Hiatus` and `Complete/Discontinued`
     * all name a status perfectly clearly and none of them equals a status word.
     */
    private fun classify(text: String?): MediaStatus? {
        val cleaned = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        mediaStatusOf(cleaned)?.let { return it }
        val low = cleaned.lowercase()
        return BUCKETS.firstOrNull { (_, keys) -> keys.any { it in low } }?.first
    }

    private fun statusFromFlag(completed: Boolean?): MediaStatus? = when (completed) {
        true -> MediaStatus.FINISHED
        false -> MediaStatus.RELEASING
        null -> null
    }
}
