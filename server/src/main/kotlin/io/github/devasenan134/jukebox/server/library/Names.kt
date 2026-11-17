package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Fuzzy

/**
 * How the scanner reads names: album titles, versions of a song, people in a tag. Plain functions, so the
 * rules are easy to test and to change.
 */
object Names {
    private val scoreSuffix = Regex("""\s*[(\[]\s*(original\s+)?(background\s+score|bgm|score)\s*[)\]]\s*$""", RegexOption.IGNORE_CASE)
    private val soundtrackSuffix = Regex(
        """\s*[(\[]\s*(original\s+)?(motion\s+picture\s+)?(soundtrack|ost|deluxe(\s+edition)?|expanded(\s+edition)?|remastered)\s*[)\]]\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val fromFilm = Regex("""\s*\(\s*from\s+["'_].*?["'_]\s*\)""", RegexOption.IGNORE_CASE)
    private val singleTail = Regex("""\s*-\s*(single|ep)\s*$""", RegexOption.IGNORE_CASE)

    /** An album tag without the parts that only say which release it is: (album title, is it the score?). */
    fun album(tag: String): Pair<String, Boolean> {
        var t = tag.trim()
        val score = scoreSuffix.containsMatchIn(t)
        repeat(3) { t = t.replace(scoreSuffix, "").replace(soundtrackSuffix, "").replace(singleTail, "").trim() }
        return (t.ifEmpty { tag.trim() }) to score
    }

    /** Versions of a song, by the word that names them in a title. */
    private val versions = listOf(
        "karaoke" to Regex("""\bkaraoke\b""", RegexOption.IGNORE_CASE),
        "instrumental" to Regex("""\binstrumental\b""", RegexOption.IGNORE_CASE),
        "remix" to Regex("""\b(remix|mix|dj)\b""", RegexOption.IGNORE_CASE),
        "lofi" to Regex("""\b(lo-?fi|slowed|reverb)\b""", RegexOption.IGNORE_CASE),
        "unplugged" to Regex("""\b(unplugged|acoustic)\b""", RegexOption.IGNORE_CASE),
        "reprise" to Regex("""\breprise\b""", RegexOption.IGNORE_CASE),
        "live" to Regex("""\blive\b""", RegexOption.IGNORE_CASE),
        "female" to Regex("""\bfemale\b""", RegexOption.IGNORE_CASE),
        "male" to Regex("""\bmale\b""", RegexOption.IGNORE_CASE),
        "sad" to Regex("""\b(sad|pathos)\b""", RegexOption.IGNORE_CASE),
    )
    private val soundtrackWords = Regex("""\b(original\s+motion\s+picture\s+soundtrack|soundtrack|ost)\b""", RegexOption.IGNORE_CASE)
    private val bracketed = Regex("""\s*[(\[]([^)\]]*)[)\]]""")
    private val dashTail = Regex("""\s+-\s+([^-]+)$""")

    /**
     * (base title, version) of a song title: "Kanave (Karaoke Version)" → ("Kanave", "karaoke"),
     * "Merku Thodarchi Mala - Female" → ("Merku Thodarchi Mala", "female"). Only brackets and a " - " tail
     * that name a version are removed, so "Theme of Airaa" stays as it is.
     */
    fun version(title: String): Pair<String, String> {
        var base = title.replace(fromFilm, "").trim()
        var found = "original"
        fun versionOf(text: String) = versions.firstOrNull { it.second.containsMatchIn(text) }?.first
        bracketed.findAll(base).toList().forEach { m ->
            if (soundtrackWords.containsMatchIn(m.groupValues[1])) base = base.replace(m.value, "")
            else versionOf(m.groupValues[1])?.let { v -> if (found == "original") found = v; base = base.replace(m.value, "") }
        }
        dashTail.find(base)?.let { m -> versionOf(m.groupValues[1])?.let { v -> if (found == "original") found = v; base = base.removeSuffix(m.value) } }
        return base.trim().ifEmpty { title.trim() } to found
    }

    private val separators = Regex("""\s*(,|;|/|&|\band\b|\bfeat\.?|\bft\.|\bwith\b|\s\|\s)\s*""", RegexOption.IGNORE_CASE)

    /** The people named in a tag, in order, without repeats: "A, B & C" → [A, B, C]. */
    fun people(tag: String?): List<String> =
        tag.orEmpty().split(separators).map { person(it) }.filter(::isPerson)
            .distinctBy { Fuzzy.key(it) }

    private val chorus = Regex("""\s+(chorus|group)$""", RegexOption.IGNORE_CASE)
    private val initialsTail = Regex("""\s*\((traditional|[A-Z0-9]{2,6})\)$""")

    /** A name without what isn't part of it: "K J Yesudas Chorus" → "K J Yesudas", "Aaryan Dinesh (ADK)" → "Aaryan Dinesh". */
    private fun person(raw: String): String =
        raw.trim().trim('-', ',').trim().replace(initialsTail, "").replace(chorus, "").trim()

    private val notPeople = setOf(
        "various artists", "various artiste", "various", "unknown", "unknown artist", "[unknown artist]", "n a", "na",
        "chorus", "kids", "group", "traditional", "others", "instrumental",
    )

    /** Whether a name from a tag is a person at all ("Chorus", "Instrumental" and "Various Artists" aren't). */
    fun isPerson(name: String): Boolean = name.length > 1 && name.lowercase().replace(".", " ").trim() !in notPeople

    /** One key for a person however the name is spelled ("M.S. Viswanathan" = "M. S. Viswanathan"). */
    fun personKey(name: String) = Fuzzy.key(name.replace(".", " "))

    /** A title for sorting: lower case, without a leading "the"/"a". */
    fun sortTitle(title: String) = title.lowercase().removePrefix("the ").removePrefix("a ").trim()

    /** "3/12" or "3" → 3. */
    fun number(tag: String?): Int? = tag?.substringBefore('/')?.trim()?.toIntOrNull()

    /** A year from "2019", "2019-05-01" or "05/01/2019". */
    fun year(tag: String?): Int? = tag?.let { Regex("""(19|20)\d\d""").find(it)?.value?.toInt() }

    /** "ta" when most letters are Tamil script, else "en". */
    fun script(text: String): String {
        val letters = text.filter(Char::isLetter)
        return if (letters.isNotEmpty() && letters.count { it in '஀'..'௿' } * 2 > letters.length) "ta" else "en"
    }

    /**
     * Track titles that say the track is background music: "Title Music", "Theme Music", "Mass BGM", "Kodai Mazhai
     * Title Score", "End Credits", "Music Bit 3", "Escape (Theme)". A "Theme Song" is sung, so it stays a song.
     * Checked on every title in the library (2026-10-08): 43 hits outside the score albums, all of them music.
     */
    private val bgmTitleWords = Regex(
        """\bbgms?\b|\bback\s*ground\s+(score|music)\b|\boriginal\s+score\b|\bscore\b|\binstrumental\b|\bend\s+credits?\b""" +
            """|\b(title|theme|thema|theam|theame|theem|dance|chasing|chase|fight|love|sad|climax|interval|mass|entry|intro|opening|ending|end|pathos|comedy|suspense|bit)\s+music\b""" +
            """|\bmusic\s+(bit|track|piece)\b|\b(themes?|theam|theame|theem)\b(?!\s*song)""",
        RegexOption.IGNORE_CASE,
    )

    /** Whether a track's title says it's background music (see [bgmTitleWords]). */
    fun isBgmTitle(title: String): Boolean = bgmTitleWords.containsMatchIn(title)

    /** Whether a track is background score: on a score release, or named like one. */
    fun isScore(releaseKind: String? = null, trackTitle: String? = null): Boolean =
        releaseKind.equals("score", ignoreCase = true) || (trackTitle != null && isBgmTitle(trackTitle))
}
