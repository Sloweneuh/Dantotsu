package ani.dantotsu.settings.saving.internal

/**
 * How [SqlitePreferences] stores a preference value in one TEXT column: a type code beside the
 * text form. Covers exactly the types SharedPreferences can hold, and round-trips each exactly.
 */
internal object PrefValueCodec {

    const val STRING = 0
    const val INT = 1
    const val LONG = 2
    const val FLOAT = 3
    const val BOOLEAN = 4
    const val STRING_SET = 5

    /** The type code and text for [value], or null for a type SharedPreferences can't store. */
    fun encode(value: Any): Pair<Int, String>? = when (value) {
        is String -> STRING to value
        is Int -> INT to value.toString()
        is Long -> LONG to value.toString()
        // Float.toString is the shortest form that parses back to the same float.
        is Float -> FLOAT to value.toString()
        is Boolean -> BOOLEAN to if (value) "1" else "0"
        is Set<*> -> STRING_SET to encodeSet(value)
        else -> null
    }

    fun decode(type: Int, text: String): Any = when (type) {
        STRING -> text
        INT -> text.toInt()
        LONG -> text.toLong()
        FLOAT -> text.toFloat()
        BOOLEAN -> text == "1"
        STRING_SET -> decodeSet(text)
        else -> throw IllegalArgumentException("Unknown preference type $type")
    }

    // Length-prefixed ("3:abc5:hello"), so an element may contain any character, separators
    // included, and the empty set and a set holding "" stay distinct.
    private fun encodeSet(set: Set<*>): String = buildString {
        set.forEach { element ->
            val s = element as String
            append(s.length).append(':').append(s)
        }
    }

    private fun decodeSet(text: String): Set<String> {
        val out = HashSet<String>()
        var i = 0
        while (i < text.length) {
            val colon = text.indexOf(':', i)
            require(colon > i) { "Malformed string set at $i" }
            val len = text.substring(i, colon).toInt()
            val start = colon + 1
            require(start + len <= text.length) { "Truncated string set at $i" }
            out.add(text.substring(start, start + len))
            i = start + len
        }
        return out
    }
}
