package ani.dantotsu.settings.saving.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PrefValueCodecTest {

    private fun roundTrip(value: Any): Any {
        val (type, text) = PrefValueCodec.encode(value)!!
        return PrefValueCodec.decode(type, text)
    }

    @Test
    fun `every SharedPreferences type round-trips with its own type`() {
        val values = listOf<Any>(
            "", "plain", "with:colons:and\nnewlines", "ünïcødé 日本語",
            0, -1, Int.MAX_VALUE, Int.MIN_VALUE,
            0L, Long.MAX_VALUE, Long.MIN_VALUE, 1_727_000_000_000L,
            0f, -0.5f, 0.1f, Float.MAX_VALUE, Float.MIN_VALUE, 1.0E-10f,
            true, false,
        )
        for (value in values) {
            val back = roundTrip(value)
            assertEquals(value, back)
            assertEquals(value::class, back::class)
        }
    }

    @Test
    fun `string sets round-trip, separators and empties included`() {
        val sets = listOf(
            emptySet(),
            setOf(""),
            setOf("a"),
            setOf("12||Some Title", "3:4", ":", "", "long ".repeat(50)),
        )
        for (set in sets) assertEquals(set, roundTrip(set))
    }

    @Test
    fun `an int and a long stay distinct`() {
        assertEquals(PrefValueCodec.INT, PrefValueCodec.encode(5)!!.first)
        assertEquals(PrefValueCodec.LONG, PrefValueCodec.encode(5L)!!.first)
    }

    @Test
    fun `unsupported values are refused, not mangled`() {
        assertNull(PrefValueCodec.encode(5.0))
        assertNull(PrefValueCodec.encode(listOf("a")))
    }

    @Test
    fun `malformed sets fail loudly`() {
        assertThrows(Exception::class.java) { PrefValueCodec.decode(PrefValueCodec.STRING_SET, "5:abc") }
        assertThrows(Exception::class.java) { PrefValueCodec.decode(PrefValueCodec.STRING_SET, "abc") }
    }
}
