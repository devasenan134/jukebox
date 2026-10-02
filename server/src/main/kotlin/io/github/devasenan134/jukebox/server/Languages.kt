package io.github.devasenan134.jukebox.server

/**
 * Works out which language each song is in, so a mix stays in one language (a Tamil kuthu song and
 * a fast English dance track can sound alike, but don't belong in the same mix).
 *
 * Few songs are tagged, so the evidence is gathered in steps, most reliable first:
 *  1. the song's own tag ("Tamil", or a Western genre like "Pop" for English), the script its title
 *     is written in (Tamil, Devanagari...), and English words in its title;
 *  2. what its movie, composer, singers and lyricists are known for (a movie is one language, and
 *     people mostly work in one or two);
 *  3. whether it sounds like Indian film music or Western pop (from the analyzer's descriptions);
 *  4. the songs that sound most like it, when they agree strongly.
 * A song nothing is known about stays null.
 */
object Languages {
    /** Tags that name a language (lowercase). */
    private val NAMES = mapOf(
        "tamil" to "Tamil", "kollywood" to "Tamil",
        "telugu" to "Telugu", "tollywood" to "Telugu",
        "hindi" to "Hindi", "bollywood" to "Hindi",
        "malayalam" to "Malayalam", "mollywood" to "Malayalam",
        "kannada" to "Kannada", "sandalwood" to "Kannada",
        "english" to "English",
        "bengali" to "Bengali", "marathi" to "Marathi", "punjabi" to "Punjabi",
    )

    /** Western genres: evidence for English, but weaker (an Indian song can be tagged "Pop" too). */
    private val WESTERN = setOf(
        "pop", "rock", "hip-hop", "hip hop", "hip-hop/rap", "rap", "dance", "electronic", "edm", "house", "r&b", "r&b/soul",
        "soul", "country", "alternative", "indie", "metal", "jazz", "blues", "reggae", "punk", "k-pop", "singer/songwriter",
    )

    /** Unicode blocks of Indian scripts: a title written in one tells the language. */
    private val SCRIPTS = listOf(
        0x0B80..0x0BFF to "Tamil",
        0x0C00..0x0C7F to "Telugu",
        0x0C80..0x0CFF to "Kannada",
        0x0D00..0x0D7F to "Malayalam",
        0x0900..0x097F to "Hindi",
        0x0980..0x09FF to "Bengali",
        0x0A00..0x0A7F to "Punjabi",
    )

    /**
     * Common English words. Tamil (or Telugu, Hindi) titles written in English letters rarely contain
     * them, while English titles usually do. But film songs often have English titles ("Run for Your
     * Life" is from a Tamil movie), so this is only a hint: it never decides on its own.
     */
    private val ENGLISH_WORDS = setOf(
        "the", "you", "your", "my", "me", "i", "i'm", "im", "love", "of", "and", "in", "on", "to", "a", "is", "it", "it's",
        "don't", "dont", "can't", "we", "night", "baby", "heart", "like", "all", "for", "with", "be", "what", "this", "that",
        "just", "girl", "boy", "never", "feel", "time", "life", "world", "go", "get", "got", "let", "one", "up", "down",
        "away", "home", "tonight", "forever", "wanna", "gonna", "yeah", "oh", "are", "was", "when", "where", "no",
    )

    /** How much each kind of evidence counts. */
    private const val TAG = 3.0
    /** A language named in the title or movie: "Jeeva Nadhi (Telugu)", "Saroja - Tamil". */
    private const val NAMED = 2.5
    private const val SCRIPT = 3.0
    private const val WESTERN_TAG = 1.0
    private const val ENGLISH_TITLE = 0.6

    /** A language needs this share of the evidence to be chosen. */
    private const val SURE = 0.6

    /**
     * Each song's language, by position in [songs] (null if unknown). [indianFilm] and [western] are
     * how well each song fits those descriptions (z-scores from the analyzer, NaN if unknown or not
     * analyzed); [sound] is each song's CLAP numbers.
     */
    fun infer(
        songs: List<LibrarySong>,
        sound: Array<FloatArray?> = arrayOfNulls(songs.size),
        indianFilm: FloatArray? = null,
        western: FloatArray? = null,
        /** If given, gets which step (1 to 4, see above) decided each song, for checking the results. */
        steps: IntArray? = null,
    ): Array<String?> {
        val n = songs.size
        // 1. The song's own evidence.
        val own = Array(n) { i -> ownEvidence(songs[i]) }
        val language = arrayOfNulls<String>(n)
        for (i in 0 until n) language[i] = decide(own[i])
        fun mark(step: Int) { steps?.let { s -> for (i in 0 until n) if (language[i] != null && s[i] == 0) s[i] = step } }
        mark(1)

        // 2. What movies and people are known for, twice: songs worked out the first time help the second.
        repeat(2) {
            val byAlbum = mutableMapOf<String, MutableMap<String, Double>>()
            val byPerson = mutableMapOf<String, MutableMap<String, Double>>()
            for (i in 0 until n) {
                val l = language[i] ?: continue
                if (realAlbum(songs[i])) byAlbum.getOrPut(songs[i].albumId) { mutableMapOf() }.merge(l, 1.0, Double::plus)
                for (p in people(songs[i])) byPerson.getOrPut(p) { mutableMapOf() }.merge(l, 1.0, Double::plus)
            }
            for (i in 0 until n) {
                if (language[i] != null) continue
                val votes = own[i].toMutableMap()
                if (realAlbum(songs[i])) byAlbum[songs[i].albumId]?.let { add(votes, it, 2.0) }
                songs[i].composer?.let { c -> byPerson[c.id]?.let { add(votes, it, 1.0) } }
                for (s in songs[i].singers) byPerson[s.id]?.let { add(votes, it, 0.7) }
                for (l in songs[i].lyricists) byPerson[l.id]?.let { add(votes, it, 1.0) }
                language[i] = decide(votes)
            }
        }
        mark(2)

        // 3. Indian film music or Western pop, by sound: only when it's clearly one of them.
        if (indianFilm != null && western != null) {
            val known = (0 until n).mapNotNull { language[it] }.groupingBy { it }.eachCount()
            // An Indian-sounding song takes the library's main Indian language (it has no other clue).
            val mainIndian = known.filterKeys { it != "English" }.maxByOrNull { it.value }?.key
            for (i in 0 until n) {
                if (language[i] != null || indianFilm[i].isNaN() || western[i].isNaN()) continue
                val lean = indianFilm[i] - western[i]
                val englishTitle = own[i]["English"] != null
                if (lean > 1.2f && mainIndian != null && !englishTitle) language[i] = mainIndian
                // Western-style film songs can sound nearly as Western as English ones, so the sound alone must
                // be extreme; an English title as well makes it enough sooner.
                else if (lean < -3f || (englishTitle && lean < -1.5f)) language[i] = "English"
            }
        }
        mark(3)

        // 4. The songs that sound most like it, if nearly all of them agree.
        val labelled = (0 until n).filter { language[it] != null && sound[it] != null }
        if (labelled.size >= 20) {
            val sample = if (labelled.size > 1500) labelled.shuffled(kotlin.random.Random(7)).take(1500) else labelled
            for (i in 0 until n) {
                val v = sound[i] ?: continue
                if (language[i] != null) continue
                val nearest = sample.map { it to dot(v, sound[it]!!) }.sortedByDescending { it.second }.take(15)
                val counts = nearest.groupingBy { language[it.first]!! }.eachCount()
                val (best, count) = counts.maxByOrNull { it.value } ?: continue
                // Not against the song's own sound: an English label for an Indian-sounding song, or the other way round.
                val lean = if (indianFilm != null && western != null) indianFilm[i] - western[i] else Float.NaN
                // English needs a Western sound (known): most of the library is Indian, so a close match to a few English songs isn't enough.
                val disagrees = if (best == "English") lean.isNaN() || lean > -2f else !lean.isNaN() && lean < -3f
                if (count >= 12 && !disagrees) language[i] = best
            }
        }
        mark(4)
        return language
    }

    /** The song's own clues: its tag, its title's script and English words in its title. */
    fun ownEvidence(song: LibrarySong): Map<String, Double> {
        val votes = mutableMapOf<String, Double>()
        val genre = song.genre.lowercase().trim()
        val parts = genre.split(';', ',', '/').map { it.trim() }.filter { it.isNotEmpty() }
        parts.mapNotNull { NAMES[it] }.distinct().forEach { votes.merge(it, TAG, Double::plus) }
        if (genre in WESTERN || parts.any { it in WESTERN }) votes.merge("English", WESTERN_TAG, Double::plus)
        scriptOf(song.title + " " + song.album)?.let { votes.merge(it, SCRIPT, Double::plus) }
        // The title's own mention wins over the movie's ("Jeeva Nadhi (Telugu)" in a Tamil album).
        (namedIn(song.title) ?: namedIn(song.album))?.let { votes.merge(it, NAMED, Double::plus) }
        if (englishWords(song.title) >= 2 || (englishWords(song.title) >= 1 && englishWords(song.album) >= 1)) {
            votes.merge("English", ENGLISH_TITLE, Double::plus)
        }
        return votes
    }

    /** The Indian script [text] is written in, if any. */
    fun scriptOf(text: String): String? {
        val counts = mutableMapOf<String, Int>()
        text.codePoints().forEach { cp -> SCRIPTS.firstOrNull { cp in it.first }?.let { counts.merge(it.second, 1, Int::plus) } }
        return counts.maxByOrNull { it.value }?.key
    }

    /** A language named as a word in [text] ("Kutty - New Tamil", "Baahubali 2 (Telugu)"). */
    fun namedIn(text: String): String? =
        text.lowercase().split(Regex("[^a-z]+")).firstNotNullOfOrNull { word -> NAMES[word]?.takeIf { word in PLAIN_NAMES } }

    /** Only the languages' own names count in titles ("Kollywood" could be anything). */
    private val PLAIN_NAMES = setOf("tamil", "telugu", "hindi", "malayalam", "kannada", "english")

    /** How many common English words [text] has ("Shape of You" has 2, "Kanave Kanave" none). */
    fun englishWords(text: String): Int =
        text.lowercase().substringBefore('(').split(Regex("[^a-z']+")).count { it in ENGLISH_WORDS }

    /** The language with at least [SURE] of the votes, if any. */
    private fun decide(votes: Map<String, Double>): String? {
        val total = votes.values.sum()
        // One singer's usual language (0.7) is enough; an English title alone (0.6) isn't.
        if (total < 0.7) return null
        val (best, weight) = votes.maxByOrNull { it.value } ?: return null
        return best.takeIf { weight / total >= SURE }
    }

    /** Adds [counts] to [votes] as shares, times [weight]. */
    private fun add(votes: MutableMap<String, Double>, counts: Map<String, Double>, weight: Double) {
        val total = counts.values.sum().takeIf { it > 0 } ?: return
        counts.forEach { (l, c) -> votes.merge(l, weight * c / total, Double::plus) }
    }

    /** "[Unknown Album]" gathers songs that have nothing to do with each other: it isn't a movie. */
    private fun realAlbum(song: LibrarySong) = song.albumId.isNotBlank() && !song.album.startsWith("[Unknown") && song.album.isNotBlank()

    private fun people(song: LibrarySong) = listOfNotNull(song.composer?.id) + song.singers.map { it.id } + song.lyricists.map { it.id }
}
