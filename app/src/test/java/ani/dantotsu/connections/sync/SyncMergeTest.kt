package ani.dantotsu.connections.sync

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {

    private val gson = Gson()

    private fun payload(vararg prefs: Pair<String, String>): String =
        gson.toJson(mapOf("ui" to prefs.associate { (k, v) -> k to mapOf("type" to "String", "value" to v) }))

    private fun values(json: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val ui = (gson.fromJson(json, Map::class.java)["ui"] as Map<String, Map<String, Any?>>?).orEmpty()
        return ui.mapValues { it.value["value"] }
    }

    @Test
    fun `a change on one side only is not a conflict`() {
        val base = payload("Theme" to "Dark", "Reader" to "LTR")
        val local = payload("Theme" to "Light", "Reader" to "LTR")
        val remote = payload("Theme" to "Dark", "Reader" to "RTL")

        val result = SyncMerge.merge(base, local, remote)!!

        assertTrue(result.conflicts.isEmpty())
        assertEquals(mapOf("Theme" to "Light", "Reader" to "RTL"), values(result.preferringLocal))
        assertEquals(values(result.preferringLocal), values(result.preferringRemote))
    }

    @Test
    fun `both sides changing a key differently is a conflict`() {
        val base = payload("Theme" to "Dark")
        val result = SyncMerge.merge(base, payload("Theme" to "Light"), payload("Theme" to "Amoled"))!!

        assertEquals(listOf("Theme"), result.conflicts.map { it.key })
        assertEquals("Light", values(result.preferringLocal)["Theme"])
        assertEquals("Amoled", values(result.preferringRemote)["Theme"])
    }

    @Test
    fun `a key deleted on one side stays deleted`() {
        val base = payload("Theme" to "Dark", "Gone" to "x")
        val result = SyncMerge.merge(base, payload("Theme" to "Dark"), base)!!

        assertTrue(result.conflicts.isEmpty())
        assertEquals(mapOf("Theme" to "Dark"), values(result.preferringRemote))
    }

    @Test
    fun `without a baseline every difference is a conflict`() {
        val result = SyncMerge.merge(null, payload("Theme" to "Light"), payload("Theme" to "Dark"))!!
        assertEquals(listOf("Theme"), result.conflicts.map { it.key })
    }

    @Test
    fun `unparseable input gives up`() {
        assertNull(SyncMerge.merge(null, "not json", payload("Theme" to "Dark")))
    }
}
