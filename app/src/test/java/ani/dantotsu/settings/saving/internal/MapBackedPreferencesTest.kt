package ani.dantotsu.settings.saving.internal

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SharedPreferences behaviour both app stores rely on, against a fake store that records what
 * it is asked to persist. (On the JVM the main-looper check is null == null, so listeners run
 * inline, which is what the assertions below lean on.)
 */
class MapBackedPreferencesTest {

    private class Recording(initial: Map<String, Any> = emptyMap()) :
        MapBackedPreferences(initial, "test") {
        val persisted = mutableListOf<Pair<Boolean, Map<String, Any?>>>()
        override fun persist(cleared: Boolean, writes: Map<String, Any?>): Boolean {
            synchronized(persisted) { persisted += cleared to writes }
            return true
        }
    }

    @Test
    fun `apply is visible at once and persisted`() {
        val prefs = Recording()
        prefs.edit().putString("a", "1").putInt("b", 2).apply()
        assertEquals("1", prefs.getString("a", null))
        assertEquals(2, prefs.getInt("b", 0))
        prefs.edit().putLong("c", 3L).commit() // waits, and so for the apply queued before it
        assertEquals(listOf(false to mapOf("a" to "1", "b" to 2), false to mapOf("c" to 3L)), prefs.persisted)
    }

    @Test
    fun `unchanged values are not persisted again`() {
        val prefs = Recording(mapOf("a" to "1"))
        assertTrue(prefs.edit().putString("a", "1").commit())
        assertTrue(prefs.persisted.isEmpty())
    }

    @Test
    fun `remove and null put both delete`() {
        val prefs = Recording(mapOf("a" to "1", "b" to "2"))
        prefs.edit().remove("a").putString("b", null).commit()
        assertFalse(prefs.contains("a"))
        assertFalse(prefs.contains("b"))
        assertEquals(listOf(false to mapOf<String, Any?>("a" to null, "b" to null)), prefs.persisted)
    }

    @Test
    fun `clear runs before the editor's own puts`() {
        val prefs = Recording(mapOf("a" to "1", "b" to "2"))
        prefs.edit().putString("c", "3").clear().commit()
        assertEquals(mapOf("c" to "3"), prefs.all)
        assertEquals(true, prefs.persisted.single().first)
    }

    @Test
    fun `a getter of the wrong type throws like the platform's`() {
        val prefs = Recording(mapOf("a" to "text"))
        assertThrows(ClassCastException::class.java) { prefs.getInt("a", 0) }
        assertEquals(7, prefs.getInt("missing", 7))
        assertNull(prefs.getString("missing", null))
    }

    @Test
    fun `string sets are copied in and out`() {
        val prefs = Recording()
        val set = mutableSetOf("x")
        prefs.edit().putStringSet("s", set).commit()
        set += "y"
        assertEquals(setOf("x"), prefs.getStringSet("s", null))
        prefs.getStringSet("s", null)!!.add("z")
        assertEquals(setOf("x"), prefs.getStringSet("s", null))
    }

    @Test
    fun `listeners hear each changed key, and null after a clear`() {
        val prefs = Recording(mapOf("a" to "1"))
        val heard = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> heard += key }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("a", "1").putString("b", "2").commit()
        assertEquals(listOf<String?>("b"), heard)
        heard.clear()
        prefs.edit().clear().commit()
        assertEquals(listOf<String?>(null), heard)
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        heard.clear()
        prefs.edit().putString("c", "3").commit()
        assertTrue(heard.isEmpty())
    }
}
