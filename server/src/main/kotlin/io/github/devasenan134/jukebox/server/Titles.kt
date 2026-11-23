package io.github.devasenan134.jukebox.server

/**
 * Song and album names as the library writes them, whoever named them: JioSaavn (the library's own names), iTunes,
 * Spotify, YouTube, masstamilan. The one place the server cleans a name; scripts/requests/fulfil.py's `song_name`
 * follows the same rules, and both are checked against scripts/requests/title-cases.tsv.
 */
object Titles {
    /** "(From "Leo")", "(From the movie Leo)", "(From"Baasha")", "- From "Master"": the film a song is from. */
    private val from = Regex(
        """[(\[]\s*from(?:\s+|\s*(?=["“”']))(?:the\s+)?(?:movie\s+|film\s+)?["“”']?([^"“”')\]]+)["“”']?\s*[)\]]|\s+-\s+from\s+["“”']([^"“”']+)["“”']""",
        RegexOption.IGNORE_CASE,
    )
    private val noise = Regex(
        """\s*[(\[](?:feat\.?|ft\.?|with|prod\.?|remaster(?:ed)?|\d{4}\s+remaster(?:ed)?|original motion picture soundtrack|lyric(?:al)? video|official.*?|full video|audio|video song|hd|4k)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val dashNoise = Regex("""\s+-\s+(?:remaster(?:ed)?.*|\d{4} remaster.*|from .*|live.*|radio edit|single version)$""", RegexOption.IGNORE_CASE)
    private const val LANGUAGES = "tamil|telugu|hindi|malayalam|kannada|marathi|bengali|punjabi|english"
    /** A word that only says which language's version it is ("Hukum - Thalaivar Alappara - Tamil"), not part of the name. */
    val language = Regex("""(?:$LANGUAGES)(?:\s+version)?""", RegexOption.IGNORE_CASE)
    private val languageTag = Regex("""(?:\s+[-–—]\s*(?:$LANGUAGES)(?:\s+version)?|\s*[(\[](?:$LANGUAGES)(?:\s+version)?[)\]])\s*$""", RegexOption.IGNORE_CASE)
    private val soundtrack = Regex("""\s*[(\[](?:Original\s+)?(?:Motion\s+Pictures?\s+|Movie\s+)?Sound\s*track[)\]]""", RegexOption.IGNORE_CASE)
    private val ep = Regex("""\s+-\s+(?:EP|Single)$""", RegexOption.IGNORE_CASE)
    /** "Kumki (Original Motion Picture Soundtrack), Vol. 1": a later edition of the film's album, not another film. */
    private val volume = Regex("""\s*(?:,\s*|\s-\s|[(\[])\s*Vol(?:ume|\.)?\s*\d+\s*[)\]]?$""", RegexOption.IGNORE_CASE)
    private val spaces = Regex("\\s+")

    /** JioSaavn writes its names with HTML entities ("Hukum Reloaded - Tamil (From &quot;Jailer 2&quot;)"). */
    fun unescape(text: String) = text.replace("&quot;", "\"").replace("&#039;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /**
     * A song's name without "(From "Film")", "(feat. …)", "- Remastered", "- Tamil" and the like, and the film it
     * named: "Hukum - Thalaivar Alappara - Tamil (From "Jailer")" → "Hukum - Thalaivar Alappara" and "Jailer".
     */
    fun song(raw: String): Pair<String, String?> {
        val text = unescape(raw)
        val film = from.find(text)?.let { m -> (m.groups[1] ?: m.groups[2])?.value?.trim() }
        val title = text.replace(from, " ").replace(noise, " ").replace(dashNoise, "").replace(spaces, " ").trim()
            .replace(languageTag, "").trim()
        return title.ifEmpty { text.trim() } to film
    }

    /** Whether a name says which film it's from ("One Name (From "Jailer 2")"): a single, not the film's album. */
    fun namesFilm(raw: String) = from.containsMatchIn(unescape(raw))

    /** A film's album as the film: "David (Original Motion Picture Soundtrack) - EP" → "David", "Kumki (…), Vol. 2" → "Kumki". */
    fun album(raw: String) = unescape(raw).replace(soundtrack, "").replace(ep, "").trim().replace(volume, "").trim()
}
