package ani.dantotsu.parsers

import ani.dantotsu.media.Media
import me.xdrop.fuzzywuzzy.FuzzySearch
import java.text.Normalizer
import java.util.Locale

/**
 * Picks which of a source's search results is the media the user opened — the decision behind
 * [BaseParser.autoSearch].
 *
 * A source names an entry however its site does: "Solo Leveling (Official)", "Shingeki no Kyojin
 * S4", or "Kimetsu no Yaiba" for what AniList calls "Demon Slayer". So both sides are reduced to a
 * comparable [TitleKey] first, and every result is scored and ranked rather than the first plausible
 * one being taken — the entry that gets picked here is the chapter/episode list the user reads.
 */
object SourceMatcher {

    /**
     * Minimum score to use a candidate at all; below this the source counts as having no match, and
     * the read/watch screen offers manual search (or hops to the next source).
     *
     * Set where one significant extra word on a short title fails — "Naruto Shippuden" scores 73
     * against "Naruto" — because opening the wrong series' chapter list is worse than reporting
     * nothing. Titles a source merely decorates ("… (Official)", "… Digital Colored Comics") clear it
     * comfortably, as do franchise entries covering a sequel media (~82).
     */
    const val ACCEPT = 75

    /** Score that ends the search early — no later query is going to beat this candidate. */
    const val CONFIDENT = 92

    /** Cap on searches per [BaseParser.autoSearch] call, so a hard-to-find title can't cost N round trips. */
    const val MAX_QUERIES = 3

    /** Cap on titles compared per candidate, to bound the work on media with long synonym lists. */
    private const val MAX_TARGETS = 12

    private const val MAX_OTHER_NAMES = 8

    /**
     * A title reduced to what can be compared. [strict] keeps every meaningful word; [core] also
     * drops release noise ("official", "colored", …); season and part numbers are pulled out of both
     * so "Season 2", "2nd Season" and "Overlord II" all describe the same thing.
     */
    data class TitleKey(
        val strict: String,
        val core: String,
        val season: Int?,
        val part: Int?
    ) {
        val isUsable get() = strict.isNotEmpty()

        /** An unstated season is the first one, so "Naruto" and "Naruto Season 1" agree. */
        val seasonNumber get() = season ?: 1
        val partNumber get() = part ?: 1

        /** Identity for de-duping titles that differ only in wording, not in which entry they name. */
        val id get() = "$strict|$season|$part"
    }

    data class Match(val response: ShowResponse, val score: Int)

    /**
     * How well one result matches the media, 0..100: the best [similarity] across every name the
     * [candidate] advertises and every title the media is known by.
     */
    fun score(candidate: ShowResponse, targets: List<TitleKey>): Int {
        if (targets.isEmpty()) return 0
        val names = ArrayList<String>(1 + minOf(candidate.otherNames.size, MAX_OTHER_NAMES))
        names.add(candidate.name)
        names.addAll(candidate.otherNames.take(MAX_OTHER_NAMES))
        var best = 0
        for (name in names) {
            val key = key(name)
            for (target in targets) {
                val score = similarity(key, target)
                if (score >= 100) return 100
                if (score > best) best = score
            }
        }
        return best
    }

    /**
     * The best-scoring result, or null when [results] is empty. The score is returned with it so the
     * caller decides what is good enough — nothing here filters by [ACCEPT].
     */
    fun best(results: List<ShowResponse>, targets: List<TitleKey>): Match? {
        var best: Match? = null
        for (candidate in results) {
            val score = score(candidate, targets)
            val incumbent = best
            val better = when {
                incumbent == null -> true
                score > incumbent.score -> true
                score < incumbent.score -> false
                else -> hasMoreContent(candidate, incumbent.response)
            }
            if (better) best = Match(candidate, score)
        }
        return best
    }

    /** Every title a candidate is worth comparing against, native script included. */
    fun targets(media: Media): List<TitleKey> {
        val seen = mutableSetOf<String>()
        return titles(media)
            .map { key(it) }
            .filter { it.isUsable && seen.add(it.id) }
            .take(MAX_TARGETS)
    }

    /**
     * The titles worth *searching* for, best first.
     *
     * Latin-script only: a source's search box is indexed by whatever the site writes its entries
     * in, and a native-script query on a site that lists romaji returns nothing — the same reason
     * [ani.dantotsu.media.SourceSearchDialogFragment] offers Latin titles only. A media with no
     * Latin title at all falls back to searching what it does have.
     */
    fun queries(media: Media): List<String> {
        val all = titles(media)
        val latin = all.filter(::isLatinScript)
        val seen = mutableSetOf<String>()
        return latin.ifEmpty { all }.filter { seen.add(key(it).id) }
    }

    /**
     * Titles of one media, most likely to match a source first.
     *
     * Manga leads with [Media.mangaName] (romaji for Japanese series) because manga sources index
     * romaji far more often than English; anime leads with the English/MAL name.
     */
    private fun titles(media: Media): List<String> {
        val ordered = if (media.manga != null) {
            listOf(media.mangaName(), media.name, media.nameRomaji, media.userPreferredName, media.nameMAL)
        } else {
            listOf(media.mainName(), media.nameRomaji, media.userPreferredName, media.nameMAL)
        }
        return (ordered + media.synonyms).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
    }

    /**
     * 0..100 for how much a source's [candidate] title looks like the media's [target] title.
     *
     * Directional, because extra words mean opposite things depending on the side they are on. A
     * candidate that adds words is a *more specific* entry — "Naruto Shippuden" for "Naruto" is a
     * different show — and is scored down by the blend. A candidate that drops them is the *whole
     * franchise* under its shared prefix, which is how most manga sources are laid out ("Bleach" for
     * "Bleach: Thousand-Year Blood War"), and stays usable.
     *
     * The blend itself: [FuzzySearch.tokenSetRatio] calls any subset a perfect match, which is how
     * "Naruto" scores 100 against "Naruto Shippuden", while [FuzzySearch.tokenSortRatio] is what
     * notices the extra words.
     */
    fun similarity(candidate: TitleKey, target: TitleKey): Int {
        if (!candidate.isUsable || !target.isUsable) return 0
        var textual = if (candidate.strict == target.strict || candidate.core == target.core) {
            100
        } else {
            (FuzzySearch.tokenSetRatio(candidate.core, target.core) * 2 +
                FuzzySearch.tokenSortRatio(candidate.core, target.core) * 3) / 5
        }

        // The franchise-entry case above. A leading prefix is what makes it recognisable: "Bleach"
        // introduces "Bleach: Thousand-Year Blood War", where "Titan" does not introduce "Attack on
        // Titan" — it is a word out of the middle of an unrelated-looking match.
        if (candidate.core.length >= 3 && target.core.startsWith("${candidate.core} ")) {
            val dropped = target.core.count { it == ' ' } - candidate.core.count { it == ' ' }
            textual = maxOf(textual, 100 - 6 * minOf(dropped, 4))
        }

        // Season/part disagreement is what makes a wrong entry look right: the same words, a
        // different show. Two *stated* numbers that contradict each other are disqualifying. One
        // side merely not stating a number is normal — plenty of sources fold every season into a
        // single entry — so that only ranks lower and can still be used when nothing better exists.
        if (candidate.season != null && target.season != null && candidate.season != target.season) {
            return minOf(textual, 45)
        }
        if (candidate.part != null && target.part != null && candidate.part != target.part) {
            return minOf(textual, 50)
        }

        var score = textual
        if (candidate.seasonNumber != target.seasonNumber) score -= 18
        if (candidate.partNumber != target.partNumber) score -= 10
        return score.coerceIn(0, 100)
    }

    /**
     * Words a source decorates an entry with that say nothing about which series it is — release
     * tags, edition labels, and the language markers adult sources tack onto every title.
     */
    private val NOISE_TOKENS = setOf(
        "the", "a", "an",
        "official", "colored", "coloured", "color", "colour", "fancolored", "fanmade",
        "digital", "remastered", "uncensored", "censored", "decensored", "raw", "raws",
        "scan", "scans", "english", "eng",
        "hd", "bd", "complete", "completed", "ongoing", "reupload", "version"
    )

    private val SEASON_WORDS = setOf("season", "seasons")
    private val PART_WORDS = setOf("part", "parts", "cour")

    private val ORDINAL_WORDS = mapOf(
        "first" to 1, "1st" to 1, "second" to 2, "2nd" to 2, "third" to 3, "3rd" to 3,
        "fourth" to 4, "4th" to 4, "fifth" to 5, "5th" to 5, "sixth" to 6, "6th" to 6,
        "seventh" to 7, "7th" to 7, "eighth" to 8, "8th" to 8, "ninth" to 9, "9th" to 9,
        "tenth" to 10, "10th" to 10
    )

    /** "I" is left out: a trailing lone "i" is a word far more often than a season number. */
    private val ROMAN_NUMERALS = mapOf(
        "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6,
        "vii" to 7, "viii" to 8, "ix" to 9, "x" to 10
    )

    private val COMPACT_SEASON = "^s(\\d{1,2})$".toRegex()
    private val COMBINING_MARKS = "\\p{Mn}+".toRegex()
    private val WHITESPACE = "\\s+".toRegex()
    private val APOSTROPHES = charArrayOf('\'', '’', 'ʼ', '‘', '`')

    /**
     * Reduces one raw title to a [TitleKey]. Public so callers can pre-compute keys once instead of
     * per comparison.
     */
    fun key(raw: String): TitleKey {
        val flat = flatten(raw)
        if (flat.isEmpty()) return TitleKey("", "", null, null)

        val tokens = flat.split(' ')
        val base = mutableListOf<String>()
        var season: Int? = null
        var part: Int? = null
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val following = tokens.getOrNull(i + 1)?.let(::number)
            val preceding = base.lastOrNull()?.let(::number)
            when {
                // Catches "season 2", "2nd season", and the bare "final season" (which keeps
                // "final" as a word, the only thing that distinguishes it).
                token in SEASON_WORDS -> when {
                    following != null -> {
                        if (season == null) season = following
                        i += 2
                    }

                    preceding != null -> {
                        base.removeAt(base.lastIndex)
                        if (season == null) season = preceding
                        i++
                    }

                    else -> i++
                }

                token in PART_WORDS -> when {
                    following != null -> {
                        if (part == null) part = following
                        i += 2
                    }

                    preceding != null -> {
                        base.removeAt(base.lastIndex)
                        if (part == null) part = preceding
                        i++
                    }

                    else -> i++
                }

                base.isNotEmpty() && COMPACT_SEASON.matches(token) -> {
                    if (season == null) season = token.drop(1).toInt()
                    i++
                }

                else -> {
                    base.add(token)
                    i++
                }
            }
        }
        // A trailing roman numeral is a sequel number ("Overlord IV"), but only where a title
        // precedes it — on its own it is the title.
        if (season == null && base.size > 1) {
            ROMAN_NUMERALS[base.last()]?.let {
                season = it
                base.removeAt(base.lastIndex)
            }
        }

        val strict = base.joinToString(" ")
        val core = base.filterNot { it in NOISE_TOKENS }.joinToString(" ").ifEmpty { strict }
        return TitleKey(strict, core, season, part)
    }

    /**
     * Case, accents, and punctuation removed: "Fate/Zero" and "Fate Zero", "Don't" and "dont",
     * "Fullmetal Alchemist & Co" and "… and Co" all reduce to the same words. Letters outside Latin
     * survive, so native-script titles stay comparable to each other.
     */
    private fun flatten(raw: String): String {
        val ascii = COMBINING_MARKS.replace(Normalizer.normalize(raw, Normalizer.Form.NFKD), "")
        val builder = StringBuilder(ascii.length)
        for (char in ascii.lowercase(Locale.ROOT)) {
            when {
                char in APOSTROPHES -> Unit
                char == '&' -> builder.append(" and ")
                char.isLetterOrDigit() -> builder.append(char)
                else -> builder.append(' ')
            }
        }
        return WHITESPACE.replace(builder.toString().trim(), " ")
    }

    private fun number(token: String): Int? =
        token.toIntOrNull()?.takeIf { it in 1..99 } ?: ORDINAL_WORDS[token]

    private fun isLatinScript(title: String): Boolean = title.all { char ->
        char.code in 0x0020..0x007E ||   // Basic ASCII
            char.code in 0x00A0..0x00FF || // Latin-1 Supplement
            char.code in 0x0100..0x017F || // Latin Extended-A
            char.code in 0x0180..0x024F    // Latin Extended-B
    }

    /**
     * Separates two results the titles couldn't: a partial re-upload from the entry that carries the
     * whole series. Whichever has more chapters/episodes wins.
     *
     * Deliberately not measured against AniList's own count. That count is null for most releasing
     * manga and stale for the rest, so "closest to AniList" would rank an entry that stops at chapter
     * 150 above one that actually has 200 — the opposite of what a reader wants. Most parsers leave
     * [ShowResponse.total] null anyway, in which case the source's own result order decides.
     */
    private fun hasMoreContent(candidate: ShowResponse, incumbent: ShowResponse): Boolean {
        val new = candidate.total ?: return false
        val old = incumbent.total ?: return true
        return new > old
    }
}
