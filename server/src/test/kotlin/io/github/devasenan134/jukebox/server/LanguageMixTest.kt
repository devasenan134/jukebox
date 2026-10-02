package io.github.devasenan134.jukebox.server

import java.time.LocalDate
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A library where Tamil kuthu songs and fast English dance tracks sound almost the same (as they do
 * to the analyzer): mixes must still keep them apart.
 */
private object TwoWorlds {
    private const val DIM = 12
    private val random = Random(3)

    // Dimension 0: the shared "loud, fast, big beat" sound. 1: a touch of Indian film music. 2: a touch of Western pop.
    // 3: party songs (every third song, in both languages).
    fun sound(indian: Boolean, party: Boolean) = unit(FloatArray(DIM) { d ->
        when (d) {
            0 -> 1f
            1 -> if (indian) 0.25f else 0f
            2 -> if (indian) 0f else 0.25f
            3 -> if (party) 0.6f else 0f
            else -> 0.08f * random.nextFloat()
        }
    })

    private val deva = Person("deva", "Deva")
    private val srikanth = Person("srikanth", "Srikanth Deva")

    /** Tamil kuthu: only the first song of each movie is tagged; the rest are known by their movie and composer. */
    fun tamil(i: Int) = LibrarySong(
        id = "ta$i", title = "Kuthu Paattu $i", album = "Padam ${i / 6}", albumId = "padam${i / 6}", artist = "Gana Bala",
        singers = listOf(Person("gana", "Gana Bala")), composer = if (i % 2 == 0) deva else srikanth, year = 2005 + i % 10,
        duration = 240, genre = if (i % 6 == 0) "Tamil" else "", addedAt = 0, karaoke = false,
    )

    /** English dance tracks: some tagged Pop, some with English titles, and some with no clue at all. */
    fun english(i: Int) = LibrarySong(
        id = "en$i",
        title = when (i % 3) { 0 -> "Shake It Tonight $i"; 1 -> "Party Anthem $i"; else -> "Zumba $i" },
        album = "Club Hits ${i / 5}", albumId = "club${i / 5}", artist = "DJ ${i % 4}",
        singers = listOf(Person("dj${i % 4}", "DJ ${i % 4}")), composer = Person("producer${i % 3}", "Producer ${i % 3}"),
        year = 2012 + i % 8, duration = 200, genre = if (i % 3 == 1) "Pop" else "", addedAt = 0, karaoke = false,
    )

    const val TAMIL = 120
    const val ENGLISH = 60

    fun library(): LibrarySnapshot {
        val songs = (0 until TAMIL).map { tamil(it) to sound(indian = true, party = it % 3 == 0) } +
            (0 until ENGLISH).map { english(it) to sound(indian = false, party = it % 3 == 0) }
        val sound = songs.map { it.second }.toTypedArray<FloatArray?>()
        val prompts = mapOf(
            "indianfilm" to FloatArray(DIM) { if (it == 1) 1f else 0f },
            "western" to FloatArray(DIM) { if (it == 2) 1f else 0f },
            "party" to FloatArray(DIM) { if (it == 3) 1f else 0f },
            "kuthu" to FloatArray(DIM) { if (it == 3) 1f else 0f },
        )
        return LibrarySnapshot(songs.map { it.first }, sound, moodScores(sound, prompts), prompts, FloatArray(songs.size) { 120f }, FloatArray(songs.size) { -8f }, "v1")
    }

    fun unit(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / norm }
    }
}

class LanguagesTest {
    @Test
    fun `a song's own clues are its tag, its title's script and English words`() {
        fun song(title: String, genre: String = "", album: String = "X") =
            LibrarySong("s", title, album, "a", "", emptyList(), null, 2000, 200, genre, 0, false)
        assertEquals(mapOf("Tamil" to 3.0), Languages.ownEvidence(song("Kanave Kanave", "Tamil")))
        assertEquals(mapOf("Hindi" to 3.0), Languages.ownEvidence(song("तुम ही हो")))
        assertEquals(mapOf("Tamil" to 3.0), Languages.ownEvidence(song("கண்ணே கலைமானே")))
        // An English title is only a hint (Tamil films have songs called "Run for Your Life" too).
        assertEquals(mapOf("English" to 0.6), Languages.ownEvidence(song("Shape of You")))
        assertEquals(mapOf("English" to 1.0), Languages.ownEvidence(song("Mersalaayitten", "Hip-Hop/Rap")))
        assertEquals(mapOf("Telugu" to 2.5), Languages.ownEvidence(song("Jeeva Nadhi (Telugu)", album = "Baahubali")))
        assertEquals(mapOf("Tamil" to 2.5), Languages.ownEvidence(song("Kodana Kodi", album = "Saroja - Tamil")))
        // Transliterated Tamil has no English words.
        assertEquals(emptyMap(), Languages.ownEvidence(song("Vaathi Coming")))
    }

    @Test
    fun `movies, people and sound fill in the songs without tags`() {
        val lib = TwoWorlds.library()
        val languages = lib.language
        val tamil = (0 until TwoWorlds.TAMIL).map { languages[it] }
        val english = (TwoWorlds.TAMIL until lib.songs.size).map { languages[it] }
        assertTrue(tamil.all { it == "Tamil" }, "every kuthu song is Tamil: $tamil")
        // Even "Zumba 2", with no tag and no English words, is English: by its producer, its album and its sound.
        assertTrue(english.all { it == "English" }, "every dance track is English: $english")
    }
}

class LanguageMixTest {
    private val now = 1_800_000_000_000L
    private val today = LocalDate.of(2027, 1, 15)
    private val lib = TwoWorlds.library()
    private fun isEnglish(id: String) = id.startsWith("en")

    private fun fan(liked: List<Int>) = History(
        playCount = liked.associateWith { 5 }, lastPlayed = liked.associateWith { now - MixMaker.DAY },
        plays = liked.map { it to now - it * 60_000L }, starred = emptySet(), starredAlbums = emptySet(), starredArtists = emptySet(), rating = emptyMap(),
    )

    private fun maker(history: History, together: Together = Together.EMPTY) =
        MixMaker(lib, history, emptyMap(), popularity = emptyMap(), friendsPlays = emptyMap(), personSeed = 7, today = today, now = now, together = together)

    @Test
    fun `a kuthu fan's mixes and stations have no English songs, however alike they sound`() {
        val m = maker(fan((0 until 40).toList()))
        val daily = m.dailyMixes()
        assertTrue(daily.isNotEmpty())
        daily.forEach { mix -> assertTrue(mix.songs.none { isEnglish(it.id) }, "${mix.title}: ${mix.songs.map { it.id }}") }
        val discover = m.discover()!!
        assertTrue(discover.songs.none { isEnglish(it.id) }, "Discover: ${discover.songs.map { it.id }}")
        val radio = m.radio("radio-song-ta3", emptySet(), 40, Random(1))!!
        assertTrue(radio.songs.none { isEnglish(it.id) }, "a kuthu song's station: ${radio.songs.map { it.id }}")
        // And the other way round: an English track's station stays English.
        val englishRadio = m.radio("radio-song-en4", emptySet(), 30, Random(1))!!
        assertTrue(englishRadio.songs.all { isEnglish(it.id) }, "an English track's station: ${englishRadio.songs.map { it.id }}")
        // Suggestions for a Tamil playlist are Tamil.
        assertTrue(m.recommend((0 until 10).map { "ta$it" }, 20, 0).none { isEnglish(it.id) })
    }

    @Test
    fun `someone who likes both gets both in feeling mixes, but not in a music culture's mix`() {
        // Two thirds Tamil, one third English.
        val liked = (0 until 40).toList() + (TwoWorlds.TAMIL until TwoWorlds.TAMIL + 20).toList()
        val m = maker(fan(liked))
        val party = m.mood(MixMaker.MOODS.first { it.key == "party" })!!.songs.map { it.id }
        val english = party.count { isEnglish(it) }
        assertTrue(english in 10..24, "about a third of the Party Mix is English: $english of ${party.size}")
        // Sprinkled through it, not all at the end.
        assertTrue(party.take(15).any { isEnglish(it) } && party.take(15).any { !isEnglish(it) }, "mixed from the start: $party")
        val kuthu = m.mood(MixMaker.MOODS.first { it.key == "kuthu" })!!.songs.map { it.id }
        assertTrue(kuthu.none { isEnglish(it) }, "Kuthu is Tamil: $kuthu")
        // Someone who only plays Tamil gets no English, even in a feeling mix.
        val tamilOnly = maker(fan((0 until 40).toList())).mood(MixMaker.MOODS.first { it.key == "party" })!!.songs
        assertTrue(tamilOnly.none { isEnglish(it.id) })
    }

    @Test
    fun `someone who likes both gets a Daily Mix for each`() {
        val liked = (0 until 40).toList() + (TwoWorlds.TAMIL until TwoWorlds.TAMIL + 20).toList()
        val daily = maker(fan(liked)).dailyMixes()
        val english = daily.filter { mix -> mix.songs.all { isEnglish(it.id) } }
        val tamil = daily.filter { mix -> mix.songs.none { isEnglish(it.id) } }
        assertEquals(daily.size, english.size + tamil.size, "no mix has both: ${daily.map { d -> d.songs.map { it.id } }}")
        assertTrue(english.isNotEmpty() && tamil.isNotEmpty())
        assertTrue(english.first().description.contains("English songs you love"))
    }

    @Test
    fun `songs people play together come up together`() {
        // People keep playing ta5 right after ta40 (another movie, other composer's turn): a station from ta40 leads to ta5.
        val plays = (0 until 20).flatMap { s ->
            val t = now - s * 3 * 3_600_000L
            listOf(Together.Play(1, lib.index.getValue("ta40"), t), Together.Play(1, lib.index.getValue("ta5"), t + 240_000))
        }
        val together = Together.build(plays, playlists = emptyList())
        val radio = maker(fan((0 until 40).toList()), together).radio("radio-song-ta40", emptySet(), 10, Random(2))!!
        assertTrue("ta5" in radio.songs.map { it.id }, "ta5 follows ta40: ${radio.songs.map { it.id }}")
        // A mix's own plays aren't counted (they're filtered out before [Together.build]); a song on repeat isn't a pair.
        val repeat = Together.build(List(5) { Together.Play(1, 3, now + it * 1000L) }, emptyList())
        assertTrue(repeat.isEmpty)
    }
}
