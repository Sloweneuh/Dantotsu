package ani.dantotsu.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cases SourceMatcher's own documentation describes, pinned down. */
class SourceMatcherTest {

    private fun score(candidate: String, target: String) =
        SourceMatcher.similarity(SourceMatcher.key(candidate), SourceMatcher.key(target))

    @Test
    fun `season spellings agree`() {
        assertEquals(2, SourceMatcher.key("Overlord Season 2").season)
        assertEquals(2, SourceMatcher.key("Overlord 2nd Season").season)
        assertEquals(2, SourceMatcher.key("Overlord II").season)
        assertEquals(2, SourceMatcher.key("Overlord S2").season)
    }

    @Test
    fun `a lone roman numeral is the title`() {
        val key = SourceMatcher.key("X")
        assertNull(key.season)
        assertEquals("x", key.strict)
    }

    @Test
    fun `punctuation, case and accents are ignored`() {
        assertEquals(SourceMatcher.key("Fate/Zero").strict, SourceMatcher.key("fate zero").strict)
        assertEquals(SourceMatcher.key("Don't Toy With Me").strict, SourceMatcher.key("Dont toy with me").strict)
        assertEquals(SourceMatcher.key("Pokémon").strict, SourceMatcher.key("Pokemon").strict)
    }

    @Test
    fun `release noise doesn't stop a match`() {
        assertTrue(score("Solo Leveling (Official)", "Solo Leveling") >= SourceMatcher.CONFIDENT)
    }

    @Test
    fun `a more specific entry falls below acceptance`() {
        assertTrue(score("Naruto Shippuden", "Naruto") < SourceMatcher.ACCEPT)
    }

    @Test
    fun `a franchise entry under its shared prefix is accepted`() {
        assertTrue(score("Bleach", "Bleach: Thousand-Year Blood War") >= SourceMatcher.ACCEPT)
    }

    @Test
    fun `contradicting seasons are disqualified`() {
        assertTrue(score("Overlord Season 3", "Overlord Season 2") < SourceMatcher.ACCEPT)
    }

    @Test
    fun `best prefers the higher score, then more content`() {
        val targets = listOf(SourceMatcher.key("Solo Leveling"))
        val partial = ShowResponse("Solo Leveling", "a", "", total = 50)
        val full = ShowResponse("Solo Leveling", "b", "", total = 200)
        val other = ShowResponse("Solo Leveling Ragnarok", "c", "", total = 500)

        assertEquals("b", SourceMatcher.best(listOf(partial, other, full), targets)!!.response.link)
    }

    @Test
    fun `endorsed needs a specific query and a narrow answer`() {
        val hit = ShowResponse("The Story of the Invincible Merchant's Rise in Another World", "a", "")
        val query = "Muteki Shounin no Isekai Nariagari Monogatari"
        assertEquals(SourceMatcher.ENDORSED, SourceMatcher.endorsed(query, listOf(hit))!!.score)
        assertNull(SourceMatcher.endorsed("Bleach", listOf(hit)))
        assertNull(SourceMatcher.endorsed(query, listOf(hit, hit, hit)))
    }
}
