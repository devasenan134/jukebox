package io.github.devasenan134.jukebox.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The server cleans names as fulfil.py does: both are checked against the same cases (scripts/requests/test_titles.py). */
class TitlesTest {
    @Test
    fun `the shared cases`() {
        val cases = File("../scripts/requests/title-cases.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(cases.size >= 10)
        for (line in cases) {
            val (raw, title, film) = line.split('\t').let { Triple(it[0], it[1], it.getOrElse(2) { "" }) }
            assertEquals(title to film.ifEmpty { null }, Titles.song(raw), raw)
        }
    }

    @Test
    fun `albums are their film`() {
        assertEquals("Vetri Mugam", Titles.album("Vetri Mugam (Original Motion Pictures Soundtrack)"))
        assertEquals("Kumki", Titles.album("Kumki (Original Motion Picture Soundtrack), Vol. 2"))
        assertEquals("I (Hindi)", Titles.album("I (Hindi) [Original Motion Picture Soundtrack]"))
    }
}
