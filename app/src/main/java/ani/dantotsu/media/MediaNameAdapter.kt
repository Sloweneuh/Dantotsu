package ani.dantotsu.media

import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

object MediaNameAdapter {

    private const val REGEX_ITEM = "[\\s:.\\-]*(\\d+\\.?\\d*)[\\s:.\\-]*"
    private const val REGEX_PART_NUMBER = "(?<!part\\s)\\b(\\d+)\\b"
    private const val REGEX_EPISODE =
        "(episode|episodio|ep|e)${REGEX_ITEM}\\(?\\s*(sub|subbed|dub|dubbed)*\\s*\\)?\\s*"
    private const val REGEX_SEASON = "(season|s)[\\s:.\\-]*(\\d+)[\\s:.\\-]*"
    private const val REGEX_SUBDUB = "^(soft)?[\\s-]*(sub|dub|mixed)(bed|s)?\\s*$"
    private const val REGEX_CHAPTER = "(chapter|chap|ch|c)${REGEX_ITEM}"

    fun setSubDub(text: String, typeToSetTo: SubDubType): String? {
        val subdubPattern: Pattern = Pattern.compile(REGEX_SUBDUB, Pattern.CASE_INSENSITIVE)
        val subdubMatcher: Matcher = subdubPattern.matcher(text)

        return if (subdubMatcher.find()) {
            val soft = subdubMatcher.group(1)
            val subdub = subdubMatcher.group(2)
            val bed = subdubMatcher.group(3) ?: ""

            val toggled = when (typeToSetTo) {
                SubDubType.SUB -> "sub"
                SubDubType.DUB -> "dub"
                SubDubType.NULL -> ""
            }
            val toggledCasePreserved =
                if (subdub?.get(0)?.isUpperCase() == true || soft?.get(0)
                        ?.isUpperCase() == true
                ) toggled.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(
                        Locale.ROOT
                    ) else it.toString()
                } else toggled

            subdubMatcher.replaceFirst(toggledCasePreserved + bed)
        } else {
            null
        }
    }

    fun getSubDub(text: String): SubDubType {
        val subdubPattern: Pattern = Pattern.compile(REGEX_SUBDUB, Pattern.CASE_INSENSITIVE)
        val subdubMatcher: Matcher = subdubPattern.matcher(text)

        return if (subdubMatcher.find()) {
            val subdub = subdubMatcher.group(2)?.lowercase(Locale.ROOT)
            when (subdub) {
                "sub" -> SubDubType.SUB
                "dub" -> SubDubType.DUB
                else -> SubDubType.NULL
            }
        } else {
            SubDubType.NULL
        }
    }

    enum class SubDubType {
        SUB, DUB, NULL
    }

    fun findSeasonNumber(text: String): Int? {
        val seasonPattern: Pattern = Pattern.compile(REGEX_SEASON, Pattern.CASE_INSENSITIVE)
        val seasonMatcher: Matcher = seasonPattern.matcher(text)

        return if (seasonMatcher.find()) {
            // Safely parse possible unicode digits (like 𝟷) and variants
            seasonMatcher.group(2)?.let { parseIntSafe(it) }
        } else {
            text.toIntOrNull()
        }
    }

    fun findEpisodeNumber(text: String): Float? {
        val episodePattern: Pattern = Pattern.compile(REGEX_EPISODE, Pattern.CASE_INSENSITIVE)
        val episodeMatcher: Matcher = episodePattern.matcher(text)

        return if (episodeMatcher.find()) {
            if (episodeMatcher.group(2) != null) {
                parseFloatSafe(episodeMatcher.group(2))
            } else {
                val failedEpisodeNumberPattern: Pattern =
                    Pattern.compile(REGEX_PART_NUMBER, Pattern.CASE_INSENSITIVE)
                val failedEpisodeNumberMatcher: Matcher =
                    failedEpisodeNumberPattern.matcher(text)
                if (failedEpisodeNumberMatcher.find()) {
                    parseFloatSafe(failedEpisodeNumberMatcher.group(1))
                } else {
                    null
                }
            }
        } else {
            parseFloatSafe(text)
        }
    }

    fun removeEpisodeNumber(text: String): String {
        val regexPattern = Regex(REGEX_EPISODE, RegexOption.IGNORE_CASE)
        val removedNumber = text.replace(regexPattern, "").ifEmpty {
            text
        }
        val letterPattern = Regex("[a-zA-Z]")
        return if (letterPattern.containsMatchIn(removedNumber)) {
            removedNumber
        } else {
            text
        }
    }


    fun removeEpisodeNumberCompletely(text: String): String {
        val regexPattern = Regex(REGEX_EPISODE, RegexOption.IGNORE_CASE)
        val removedNumber = text.replace(regexPattern, "")
        return if (removedNumber.equals(text, true)) {  // if nothing was removed
            val failedEpisodeNumberPattern =
                Regex(REGEX_PART_NUMBER, RegexOption.IGNORE_CASE)
            failedEpisodeNumberPattern.replace(removedNumber) { mr ->
                mr.value.replaceFirst(mr.groupValues[1], "")
            }
        } else {
            removedNumber
        }
    }

    fun findChapterNumber(text: String): Float? {
        val pattern: Pattern = Pattern.compile(REGEX_CHAPTER, Pattern.CASE_INSENSITIVE)
        val matcher: Matcher = pattern.matcher(text)

        return if (matcher.find()) {
            parseFloatSafe(matcher.group(2))
        } else {
            val failedChapterNumberPattern: Pattern =
                Pattern.compile(REGEX_PART_NUMBER, Pattern.CASE_INSENSITIVE)
            val failedChapterNumberMatcher: Matcher =
                failedChapterNumberPattern.matcher(text)
            if (failedChapterNumberMatcher.find()) {
                parseFloatSafe(failedChapterNumberMatcher.group(1))
            } else {
                parseFloatSafe(text)
            }
        }
    }

    // Helpers to safely parse numbers which may contain unicode digits
    private fun parseFloatSafe(input: String?): Float? {
        if (input == null) return null
        val norm = normalizeNumberString(input) ?: return null
        return try {
            norm.toFloatOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun parseIntSafe(input: String?): Int? {
        if (input == null) return null
        val norm = normalizeNumberString(input) ?: return null
        return try {
            // Try integer first
            norm.toIntOrNull() ?: norm.toFloatOrNull()?.toInt()
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeNumberString(input: String): String? {
        val sb = StringBuilder()
        var foundDigit = false
        var i = 0
        while (i < input.length) {
            // Use fully-qualified java.lang.Character to access codePointAt (avoids collision with kotlin.Char)
            val cp = java.lang.Character.codePointAt(input, i)
            val digit = java.lang.Character.getNumericValue(cp)
            if (digit in 0..9) {
                sb.append(('0'.code + digit).toChar())
                foundDigit = true
            } else if (cp == '.'.code || cp == '\uFF0E'.code || cp == '·'.code || cp == ','.code) {
                // normalize various decimal separators to '.'
                sb.append('.')
                foundDigit = true
            } else if (cp == '-'.code || cp == '+'.code) {
                sb.appendCodePoint(cp)
            }
            i += java.lang.Character.charCount(cp)
        }
        return if (foundDigit) sb.toString() else null
    }
}