package io.github.devasenan134.jukebox.server

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** Who made every mix, playlist and station the app didn't get from a person. */
const val AUTHOR = "Jukebox"

@Serializable data class PersonDto(val id: String, val name: String)

@Serializable
data class MixSong(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val coverArt: String? = null,
    val duration: Int = 0,
    val year: Int? = null,
    val artists: List<PersonDto> = emptyList(),
)

/** A mix, playlist or station made by Jukebox. In lists, [songs] is left out and only [songCount] is set. */
@Serializable
data class MixDto(
    val id: String,
    /** "daily", "discover", "repeat", "rewind", "new", "friends", "popular", "mood", "decade", "composer", "singer" or "radio". */
    val kind: String,
    val title: String,
    /** One short line for the tile ("Ilaiyaraaja, Deva and more"). */
    val subtitle: String,
    /** A sentence or two for the mix's page. */
    val description: String,
    val author: String = AUTHOR,
    /** Up to 4 cover art ids (the Subsonic getCoverArt) for the tile. */
    val covers: List<String> = emptyList(),
    /** Round picture for composer and singer mixes, and their stations. */
    val round: Boolean = false,
    /** The tile's colour, "#RRGGBB". */
    val color: String,
    val songCount: Int = 0,
    /** How often it changes on its own: "daily", "weekly" or "live" (whenever you play or the library changes). */
    val refresh: String,
    /** When its songs last changed (milliseconds since 1970). */
    val updatedAt: Long = 0,
    /** A station: the app asks for more songs as it plays, so it never ends. */
    val endless: Boolean = false,
    /** Picked for this person's taste (the app shows "Made for <name>"); false for ones everyone gets alike. */
    val personal: Boolean = false,
    val songs: List<MixSong> = emptyList(),
) {
    fun summary() = copy(songs = emptyList(), songCount = songs.size.takeIf { it > 0 } ?: songCount)
}

@Serializable data class MixSection(val id: String, val title: String, val mixes: List<MixDto>)

/** How often someone skipped a song early vs. listened to it (reported by the app). */
data class SkipStats(val skips: Int, val listens: Int)

/**
 * Makes every mix for one person out of the library and their listening. It only calculates:
 * reading the data and saving anything is done by [MixService].
 *
 * Scores used below are all on the same scale (roughly "how many standard deviations better than an
 * average song"), so they can be added together.
 */
class MixMaker(
    private val lib: LibrarySnapshot,
    private val history: History,
    private val skips: Map<Int, SkipStats>,
    /** Plays per song across everyone. */
    private val popularity: Map<Int, Int>,
    /** Plays per song by this person's friends in the last 30 days. */
    private val friendsPlays: Map<Int, Int>,
    /** Stable per person, so their Daily Mixes keep their character from day to day. */
    private val personSeed: Long,
    private val today: LocalDate,
    private val now: Long = System.currentTimeMillis(),
    /** Which songs people put together (played in one sitting, or in the same playlist). */
    private val together: Together = Together.EMPTY,
) {
    private val n = lib.songs.size
    private val songs = lib.songs

    /** Songs this person keeps skipping: left out of their mixes. */
    private val avoid: Set<Int> = skips.filter { (_, s) -> s.skips >= 2 && s.skips >= 2 * s.listens }.keys

    private fun eligible(i: Int) = !songs[i].karaoke && i !in avoid
    private fun eligibleSong(i: Int) = eligible(i) && songs[i].duration >= 60 && !songs[i].score
    private fun eligibleScore(i: Int) = eligible(i) && songs[i].duration >= 20 && songs[i].score

    private val heard: Set<Int> = history.playCount.keys + history.plays.map { it.first }

    /** How much this person likes each song they've interacted with. */
    val weights: Map<Int, Double> = buildMap {
        val recent = history.plays.filter { it.second > now - 30 * DAY }.groupingBy { it.first }.eachCount()
        val keys = history.playCount.keys + history.starred + history.rating.keys + recent.keys + history.playlisted.keys
        for (i in keys) {
            var w = ln(1.0 + (history.playCount[i] ?: 0)) + 0.5 * ln(1.0 + (recent[i] ?: 0))
            if (i in history.starred) w += 2.0
            w += history.playlisted[i] ?: 0.0
            when (history.rating[i]) {
                5 -> w += 1.5
                4 -> w += 1.0
                1, 2 -> w -= 2.0
            }
            skips[i]?.let { if (it.skips > it.listens) w -= 0.5 * (it.skips - it.listens) }
            put(i, w)
        }
        // Liked movies, composers and singers count a little for each of their songs.
        for (albumId in history.starredAlbums) lib.byAlbum[albumId]?.forEach { if (it !in this) put(it, 0.6) }
        for (artistId in history.starredArtists) {
            (lib.byComposer[artistId].orEmpty() + lib.bySinger[artistId].orEmpty()).forEach { if (it !in this) put(it, 0.4) }
        }
    }

    private val liked: List<Int> = weights.filter { it.value > 0.3 && !songs[it.key].karaoke }.entries
        .sortedByDescending { it.value }.map { it.key }.take(400)

    val likedSongs: List<Int> by lazy { liked.filter { !songs[it].score } }
    val likedScores: List<Int> by lazy { liked.filter { songs[it].score } }
    val hasScoreListening: Boolean by lazy {
        likedScores.isNotEmpty() || history.playCount.any { (i, count) -> count > 0 && songs[i].score }
    }

    val hasTaste get() = likedSongs.size >= 3

    /** This person's taste as one sound (the average of what they like), if the songs were analyzed. */
    private val profile: FloatArray? = if (!lib.hasSound) null else centroid(likedSongs.ifEmpty { liked }, weights)

    private val composerLove: Map<String, Double> = share(liked) { i -> listOfNotNull(songs[i].composer?.id) }
    private val singerLove: Map<String, Double> = share(liked) { i -> songs[i].singers.map { it.id } }

    /** How well each song fits this person's taste overall. */
    val affinity: FloatArray = run {
        val sound = profile?.let { p -> zScores(FloatArray(n) { i -> lib.sound[i]?.let { dot(it, p) } ?: Float.NaN }) }
        FloatArray(n) { i ->
            val s = songs[i]
            var a = sound?.get(i)?.takeIf { !it.isNaN() } ?: 0f
            a += 1.5f * (s.composer?.let { composerLove[it.id] } ?: 0.0).toFloat()
            a += 0.8f * (s.singers.maxOfOrNull { singerLove[it.id] ?: 0.0 } ?: 0.0).toFloat()
            if (!hasTaste) a += 0.3f * ln(1.0 + (popularity[i] ?: 0)).toFloat()
            a
        }
    }

    // ---------- Languages ----------
    //
    // A Tamil kuthu song and a fast English dance track can sound alike, so how a song sounds isn't
    // enough to put songs together. Languages follow your listening:
    //  - mixes made from particular songs (Daily Mixes, stations, suggestions for a playlist) stay in
    //    those songs' language;
    //  - mixes about a music culture (Kuthu, Kollywood Mass, Carnatic, a composer) stay in its language;
    //  - mixes about a feeling (Sad, Happy, Chill...) and Discover blend the languages you listen to,
    //    about as much of each as you play;
    //  - a language you (almost) never play doesn't turn up.

    /** The language most of [items] are in (the ones you like most count more); null if none is known. */
    private fun mainLanguage(items: Collection<Int>): String? = shares(items).maxByOrNull { it.value }?.key

    /** How much of [items] is in each language (0 to 1; the ones you like most count more). */
    private fun shares(items: Collection<Int>): Map<String, Double> {
        val counts = items.mapNotNull { i -> lib.language[i]?.let { it to (weights[i] ?: 1.0).coerceAtLeast(0.1) } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
        val total = counts.values.sum().takeIf { it > 0 } ?: return emptyMap()
        return counts.mapValues { it.value / total }
    }

    /** Your main language (or the library's, before you've played anything). */
    private val homeLanguage: String? by lazy { mainLanguage(liked) ?: mainLanguage(songs.indices.toList()) }

    /** The languages you listen to, and how much of each (a language under 8% of your listening doesn't count). */
    private val yourLanguages: Map<String, Double> by lazy {
        val kept = shares(liked).filter { it.value >= 0.08 }
        val total = kept.values.sum()
        if (kept.isEmpty()) listOfNotNull(homeLanguage).associateWith { 1.0 } else kept.mapValues { it.value / total }
    }

    /** Whether song [i] can be in a mix in [languages] (any if null or empty; songs whose language is unknown always can). */
    private fun speaks(i: Int, languages: Set<String>?) = languages.isNullOrEmpty() || lib.language[i] == null || lib.language[i] in languages

    /**
     * [count] songs by [scores], about as much of each language as [blend] says (the rest from the
     * first language if one runs short), sprinkled through the mix rather than one after another.
     */
    private fun pickBlend(
        scores: Map<Int, Float>, count: Int, random: Random, pool: Int, blend: Map<String, Double>,
        exclude: Set<Int> = emptySet(), maxPerAlbum: Int = 2, maxPerPerson: Int = 20,
        scoreOnly: Boolean = false,
    ): List<Int> {
        if (blend.size <= 1) return pick(scores, count, random, pool, exclude, maxPerAlbum, maxPerPerson, blend.keys, scoreOnly = scoreOnly)
        val main = blend.maxBy { it.value }.key
        val taken = exclude.toMutableSet()
        val parts = blend.entries.sortedByDescending { it.value }.map { (language, share) ->
            // Songs whose language is unknown only fill in for the main language.
            val mine = scores.filterKeys { lib.language[it] == language || (language == main && lib.language[it] == null) }
            val want = (count * share).roundToInt().coerceAtLeast(1)
            val list = pick(mine, want, random, (pool * share).roundToInt().coerceAtLeast(want), taken, maxPerAlbum, maxPerPerson, scoreOnly = scoreOnly)
            taken += list
            list to share
        }.toMutableList()
        val short = count - parts.sumOf { it.first.size }
        if (short > 0) {
            val more = pick(scores, short, random, pool, taken, maxPerAlbum, maxPerPerson, setOf(main), scoreOnly = scoreOnly)
            parts[0] = (parts[0].first + more) to parts[0].second
        }
        // Take from whichever language is furthest behind its share so far.
        val used = IntArray(parts.size)
        val out = mutableListOf<Int>()
        while (out.size < count) {
            val next = parts.indices.filter { used[it] < parts[it].first.size }
                .maxByOrNull { parts[it].second * (out.size + 1) - used[it] } ?: break
            out += parts[next].first[used[next]++]
        }
        return spread(out)
    }

    private val dayRandom get() = Random(personSeed * 31 + today.toEpochDay())
    private val weekRandom get() = Random(personSeed * 17 + today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay())

    // ---------- Home ----------

    /**
     * Two kinds of rows: the first two are made from this person's listening ("Made for <name>" in the
     * app); the rest showcase the library, its composers and singers, and are the same for everyone.
     */
    fun home(): List<MixSection> {
        val scores = scoreMixes()
        val madeForYou = buildList {
            addAll(dailyMixes())
            discover()?.let(::add)
            onRepeat()?.let(::add)
            friendsMix()?.let(::add)
            rewind()?.let(::add)
            if (hasScoreListening) {
                addAll(scores.take(2).map { it.copy(personal = true) })
            }
        }
        val charts = listOfNotNull(popular(), newArrivals())
        val composers = topPeople(composer = true)
        // Someone who composes and sings (Ilaiyaraaja, Anirudh) shows once, as a composer.
        val singers = topPeople(composer = false, count = 16).filter { s -> composers.none { it.id == s.id } }.take(8)
        return listOf(
            MixSection("made-for-you", "Made for you", madeForYou.map(::personalized)),
            MixSection("your-stations", "Your stations", yourStations().map { it.copy(personal = true) }),
            MixSection("scores", "Themes and background scores", scores),
            MixSection("composers", "This is: composers", composers.mapNotNull { personMix(it, composer = true) }),
            MixSection("singers", "This is: artists", singers.mapNotNull { personMix(it, composer = false) }),
            MixSection("moods", "Moods and vibes", MOODS.mapNotNull { mood(it) }),
            MixSection("artist-stations", "Composer and artist stations", artistStations()),
            MixSection("charts", "Popular and new", charts),
            MixSection("decades", "Through the decades", decades()),
        ).filter { it.mixes.isNotEmpty() }
    }

    /** Any mix by id, including ones not on Home (like a composer's mix opened from their page). */
    fun byId(id: String): MixDto? = findById(id)?.let(::personalized)

    /**
     * Marks mixes made from this person's listening: Daily Mixes, Discover Weekly, On Repeat, Friends Mix and
     * Rewind. Showcases (moods, decades, composers, singers, charts, their stations) are the same for everyone.
     */
    private fun personalized(mix: MixDto): MixDto = mix.copy(
        personal = mix.personal || mix.kind in PERSONAL_KINDS,
    )

    private fun findById(id: String): MixDto? {
        val parts = id.split("-", limit = 2)
        return when (parts[0]) {
            "daily" -> dailyMixes().firstOrNull { it.id == id }
            "discover" -> discover()
            "repeat" -> onRepeat()
            "rewind" -> rewind()
            "new" -> newArrivals()
            "friends" -> friendsMix()
            "popular" -> popular()
            "mood" -> MOODS.firstOrNull { it.key == parts.getOrNull(1) }?.let { mood(it) }
            "score" -> scoreMixes().firstOrNull { it.id == id }
            "decade" -> parts.getOrNull(1)?.toIntOrNull()?.let { decade(it) }
            "composer" -> parts.getOrNull(1)?.let { lib.people[it] }?.let { personMix(it, composer = true) }
            "singer" -> parts.getOrNull(1)?.let { lib.people[it] }?.let { personMix(it, composer = false) }
            "radio" -> radio(id, emptySet(), 30, Random(now))
            else -> null
        }
    }

    // ---------- Made for you ----------

    /**
     * Up to 6 mixes, one for each side of your taste: your favourites plus songs that sound like them.
     * Your favourites are split by language first, so each mix is in one language (English favourites
     * get an English mix of their own), and then by sound.
     */
    fun dailyMixes(): List<MixDto> {
        val songLikes = likedSongs
        if (songLikes.size < 4) return emptyList()
        // Favourites whose language is unknown go with your main language.
        val byLanguage = songLikes.groupBy { lib.language[it] ?: homeLanguage }
            .filter { it.value.size >= 4 }.entries
            .sortedByDescending { (_, items) -> items.sumOf { weights[it] ?: 0.0 } }
            .ifEmpty { listOf(java.util.AbstractMap.SimpleEntry(homeLanguage, songLikes)) }
        val perLanguage = byLanguage.map { (language, items) ->
            val groups = if (lib.hasSound) {
                val analyzed = items.filter { lib.sound[it] != null }
                kMeans(analyzed, (analyzed.size / 8).coerceIn(1, 6), Random(personSeed)).filter { it.size >= 3 }
            } else {
                items.groupBy { songs[it].composer?.id }.values.filter { it.size >= 2 }.take(6)
            }.sortedByDescending { g -> g.sumOf { weights[it] ?: 0.0 } }.ifEmpty { listOf(items) }
            groups.map { language to it }
        }
        // Every language you listen to gets at least one mix; the rest go to the biggest sides of your taste.
        val firsts = perLanguage.map { it.first() }
        val rest = perLanguage.flatMap { it.drop(1) }.sortedByDescending { (_, g) -> g.sumOf { weights[it] ?: 0.0 } }
        val groups = (firsts + rest).take(6)
        val several = byLanguage.size > 1

        val used = mutableSetOf<Int>()
        return groups.mapIndexed { number, (language, group) ->
            val random = Random(personSeed * 31 + today.toEpochDay() * 7 + number)
            val favourites = pick(group.associateWith { (weights[it] ?: 0.0).toFloat() }, 15, random, pool = 40, scoreOnly = false)
            val scores = similarTo(group, language)
            for (i in 0 until n) scores[i] += 0.3f * affinity[i]
            val fresh = pick(scores, 50 - favourites.size, random, pool = 150, exclude = favourites.toSet() + used + group, languages = setOfNotNull(language), scoreOnly = false)
            used += fresh
            val list = interleave(favourites, fresh)
            val inLanguage = if (several && language != null) "$language songs you love" else "Songs you love"
            mix(
                id = "daily-${number + 1}", kind = "daily", title = "Daily Mix ${number + 1}", list = list,
                subtitle = namesIn(list), refresh = "daily",
                description = "${namesIn(list)}. $inLanguage, and new ones that sound like them. Updates every day.",
            )
        }
    }

    /** 30 songs you haven't heard yet, picked for how you listen. Changes on Mondays. */
    fun discover(): MixDto? {
        if (likedSongs.size < 3) return null
        val scores = similarTo(likedSongs.take(60), languages = yourLanguages.keys)
        for (i in 0 until n) scores[i] += 0.3f * affinity[i] + 0.4f * ln(1.0 + (friendsPlays[i] ?: 0)).toFloat()
        val list = pickBlend(
            scores.indices.associateWith { scores[it] }, 30, weekRandom, pool = 150, blend = yourLanguages,
            exclude = heard + history.starred + history.playlisted.keys, maxPerAlbum = 1, maxPerPerson = 5, scoreOnly = false,
        )
        if (list.size < 10) return null
        return mix(
            "discover", "discover", "Discover Weekly", list, refresh = "weekly", subtitle = "New to you, picked for you",
            description = "Your weekly mixtape of songs you haven't played yet, chosen for how you listen. Updates every Monday.",
        )
    }

    /** What you've played the most in the last 30 days. */
    fun onRepeat(): MixDto? {
        val since = now - 30 * DAY
        val counts = history.plays.filter { it.second > since }.groupingBy { it.first }.eachCount().ifEmpty {
            history.lastPlayed.filter { it.value > since }.mapValues { history.playCount[it.key] ?: 1 }
        }
        val list = counts.entries.filter { eligibleSong(it.key) }
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { history.lastPlayed[it.key] ?: 0 })
            .map { it.key }.take(30)
        if (list.size < 5) return null
        return mix(
            "repeat", "repeat", "On Repeat", list, refresh = "live", subtitle = "Songs you can't stop playing",
            description = "The songs you've played the most in the last 30 days. Changes as you listen.",
        )
    }

    /** Old favourites you haven't played in a while. */
    fun rewind(): MixDto? {
        val cutoff = now - 45 * DAY
        val list = (history.playCount.filter { it.value >= 2 }.keys + history.starred)
            .filter { eligibleSong(it) && (history.lastPlayed[it] ?: 0) < cutoff }
            .sortedByDescending { weights[it] ?: 0.0 }.take(40)
        if (list.size < 8) return null
        return mix(
            "rewind", "rewind", "Rewind", spread(list.shuffled(dayRandom)), refresh = "daily", subtitle = "Favourites you haven't played lately",
            description = "Songs you loved but haven't played in over six weeks.",
        )
    }

    /** Songs added to the library lately, the ones that fit you first. New songs join as soon as they're added. */
    fun newArrivals(): MixDto? {
        val added = songs.indices.filter { eligibleSong(it) && songs[it].addedAt > 0 }
        if (added.isEmpty()) return null
        // The first big import isn't "new": only songs added after most of the library count.
        val importedAt = added.map { songs[it].addedAt }.sorted()[added.size / 2]
        val list = added.filter { songs[it].addedAt > maxOf(importedAt + DAY, now - 30 * DAY) && speaks(it, yourLanguages.keys) }
            .sortedByDescending { songs[it].addedAt }.take(50)
        if (list.size < 5) return null
        return mix(
            "new", "new", "New Arrivals", spread(list), refresh = "live", subtitle = "Just added to the library",
            description = "Songs added to the library in the last 30 days, newest first. New songs join on their own.",
        )
    }

    /** What your friends have been playing, with the songs that suit you first. */
    fun friendsMix(): MixDto? {
        val scores = friendsPlays.filterKeys { eligibleSong(it) }.mapValues { (i, plays) -> ln(1.0 + plays).toFloat() + 0.3f * affinity[i] }
        val list = pick(scores, 40, dayRandom, pool = 80, languages = yourLanguages.keys, scoreOnly = false)
        if (list.size < 8) return null
        return mix(
            "friends", "friends", "Friends Mix", list, refresh = "daily", subtitle = "What your friends are playing",
            description = "Songs your friends have been playing this month, the ones that suit you first.",
        )
    }

    /** The most played songs by everyone on this server. */
    fun popular(): MixDto? {
        val list = popularity.entries.filter { eligibleSong(it.key) && speaks(it.key, yourLanguages.keys) }.sortedByDescending { it.value }.map { it.key }.take(50)
        if (list.size < 10) return null
        return mix(
            "popular", "popular", "Top 50", list, refresh = "live", subtitle = "Most played by everyone",
            description = "The most played songs by everyone on this server.",
        )
    }

    // ---------- Moods, decades, people ----------

    data class Mood(
        val key: String,
        val title: String,
        val subtitle: String,
        /** Descriptions from the analyzer (see analyzer/analyze.py) that make up this mood, and how much each counts. */
        val parts: Map<String, Float>,
        /** + for loud and fast songs, - for quiet and slow ones. */
        val energy: Float = 0f,
        val color: String,
        /** Songs louder than this (z-score) are left out; songs whose loudness is unknown too. */
        val maxEnergy: Float? = null,
        /** Songs with punchier beats than this (z-score) are left out. */
        val maxRhythm: Float? = null,
        /** Descriptions a song must fit at least this well (z-score), e.g. Focus needs "instrumental". */
        val requires: Map<String, Float> = emptyMap(),
        /** Descriptions a song may fit at most this well, e.g. calm moods leave out party songs. */
        val vetoes: Map<String, Float> = emptyMap(),
        /** Moods in the same group share no songs: each song goes to the one it fits best. */
        val group: String? = null,
        /** A music culture that belongs to one language (Kuthu is Tamil): only songs in it. */
        val language: String? = null,
        /** A music culture, in your main language (film melodies, Carnatic). Otherwise it's a feeling, in the languages you listen to. */
        val oneLanguage: Boolean = false,
        /**
         * Only songs that sound like Indian film music rather than Western pop (Kuthu, Kollywood Mass):
         * a Western-style song from a Tamil film doesn't belong with thappu drums.
         */
        val indianSound: Boolean = false,
    )

    fun mood(mood: Mood): MixDto? {
        val members = moodMembers(mood) ?: return null
        val random = Random(today.toEpochDay() * 13 + mood.key.hashCode())
        // A music culture (Kuthu, Kollywood Mass, Carnatic) stays in its language; a feeling (Sad, Chill) blends yours.
        val list = when {
            mood.language != null -> pick(members, 50, random, pool = 200, maxPerAlbum = 2, languages = setOf(mood.language), scoreOnly = false)
            mood.oneLanguage -> pick(members, 50, random, pool = 200, maxPerAlbum = 2, languages = setOfNotNull(homeLanguage), scoreOnly = false)
            else -> pickBlend(members, 50, random, pool = 200, blend = yourLanguages, maxPerAlbum = 2, scoreOnly = false)
        }
        if (list.size < 20) return null
        return mix(
            "mood-${mood.key}", "mood", mood.title, list, refresh = "daily", subtitle = mood.subtitle, color = mood.color,
            description = "${mood.subtitle}. Picked by listening to every song in the library. Updates every day.",
        )
    }

    private val energyZ by lazy { zScores(lib.energy) }
    private val rhythmZ by lazy { zScores(lib.rhythm) }

    /** How well each song fits [mood] (NaN if it can't be in it at all), before the cut-off. */
    private fun moodFit(mood: Mood, i: Int): Float {
        if (!eligibleSong(i) || lib.sound[i] == null) return Float.NaN
        if (mood.requires.any { (key, min) -> (lib.moods[key]?.get(i) ?: Float.NaN).let { it.isNaN() || it < min } }) return Float.NaN
        if (mood.vetoes.any { (key, max) -> (lib.moods[key]?.get(i) ?: 0f) > max }) return Float.NaN
        if (mood.indianSound) {
            val indian = lib.moods["indianfilm"]?.get(i) ?: Float.NaN
            val western = lib.moods["western"]?.get(i) ?: Float.NaN
            if (!indian.isNaN() && !western.isNaN() && indian - western < 0f) return Float.NaN
        }
        val energy = energyZ[i]
        if (mood.maxEnergy != null && (energy.isNaN() || energy > mood.maxEnergy)) return Float.NaN
        if (mood.maxRhythm != null && (rhythmZ[i].isNaN() || rhythmZ[i] > mood.maxRhythm)) return Float.NaN
        val total = mood.parts.values.sum()
        var s = mood.parts.entries.sumOf { (key, w) -> (lib.moods.getValue(key)[i] * w).toDouble() }.toFloat() / total
        if (mood.energy != 0f && !energy.isNaN()) s += 0.5f * mood.energy * energy
        return s
    }

    /** Songs that clearly have this mood, with how strongly; null if the library wasn't analyzed or has too few. */
    private fun moodMembers(mood: Mood): Map<Int, Float>? {
        if (!lib.hasSound || (mood.parts.keys + mood.requires.keys).any { it !in lib.moods }) return null
        val rivals = MOODS.filter { it.group != null && it.group == mood.group && it != mood && (it.parts.keys + it.requires.keys).all { k -> k in lib.moods } }
        val members = buildMap {
            for (i in 0 until n) {
                val s = moodFit(mood, i)
                if (s.isNaN() || s < 1.0f) continue
                // In a group (Chill, Sleep, Focus), only the mood it fits best gets it.
                if (rivals.any { r -> moodFit(r, i).let { !it.isNaN() && it >= 1.0f && it > s } }) continue
                put(i, s)
            }
        }
        return members.takeIf { it.size >= 20 }
    }

    fun decades(): List<MixDto> {
        val years = songs.indices.filter { eligibleSong(it) && songs[it].year > 1900 && speaks(it, yourLanguages.keys) }.groupBy { songs[it].year / 10 * 10 }
        return years.filter { it.value.size >= 20 }.keys.sortedDescending().mapNotNull { decade(it) }
    }

    fun decade(start: Int): MixDto? {
        val members = songs.indices.filter { eligibleSong(it) && songs[it].year in start until start + 10 && speaks(it, yourLanguages.keys) }
        if (members.size < 20) return null
        val scores = members.associateWith { ln(1.0 + (popularity[it] ?: 0)).toFloat() }
        val list = pickBlend(scores, 50, Random(today.toEpochDay() * 7 + start), pool = 250, blend = yourLanguages, scoreOnly = false)
        val name = if (start >= 2000) "${start}s" else "${start % 100}s"
        return mix(
            "decade-$start", "decade", "$name Mix", list, refresh = "daily", subtitle = namesIn(list), color = DECADE_COLORS[(start / 10) % DECADE_COLORS.size],
            description = "Songs from $start to ${start + 9}, the most played first. Updates every day.",
        )
    }

    /** The composers or singers this person plays most; or, before they've played anything, the library's biggest. */
    /** The library's biggest composers or singers: the most songs, and the most played by everyone. */
    fun topPeople(composer: Boolean, count: Int = 8): List<Person> {
        val source = if (composer) lib.byComposer else lib.bySinger
        val picked = source.entries.sortedByDescending { (_, list) -> list.size + 5 * list.sumOf { popularity[it] ?: 0 } }.map { it.key }
        return picked.filter { (source[it]?.size ?: 0) >= 8 }.mapNotNull { lib.people[it] }.take(count)
    }

    /**
     * Only their songs: the ones that suit you and the most played first, with a fresh selection each day.
     * (Music that sounds like theirs is what their station is for.)
     */
    fun personMix(person: Person, composer: Boolean): MixDto? {
        val theirs = (if (composer) lib.byComposer[person.id] else lib.bySinger[person.id]).orEmpty().filter(::eligibleSong)
        if (theirs.size < 5) return null
        val random = Random(today.toEpochDay() * 5 + person.id.hashCode())
        val scores = theirs.associateWith { ln(1.0 + (popularity[it] ?: 0)).toFloat() }
        // Someone who works in several languages: their main one here.
        val list = pick(scores, 50, random, pool = 120, maxPerAlbum = 3, maxPerPerson = 50, languages = setOfNotNull(mainLanguage(theirs)), scoreOnly = false)
        return mix(
            "${if (composer) "composer" else "singer"}-${person.id}", if (composer) "composer" else "singer", "This Is ${person.name}", list,
            refresh = "daily", subtitle = namesIn(list, first = person.name), covers = listOf("ar-${person.id}"), round = true,
            description = "The essential songs ${if (composer) "composed" else "sung"} by ${person.name}, the most played first. " +
                "For music like theirs, try ${person.name} Radio. Updates every day.",
        )
    }

    // ---------- Stations ----------

    /** Stations from the songs this person plays most (none until they've played some). */
    fun yourStations(): List<MixDto> =
        if (!hasTaste && !hasScoreListening) emptyList() else liked.filter { eligibleSong(it) || eligibleScore(it) }.take(6).map { stationSummary("radio-song-${songs[it].id}") }

    /** Stations for the library's biggest composers and singers, the same for everyone. */
    fun artistStations(): List<MixDto> {
        val composers = topPeople(composer = true, count = 4)
        // Someone who composes and sings (Anirudh) gets one station, not two.
        val singers = topPeople(composer = false, count = 8).filter { s -> composers.none { it.id == s.id } }.take(4)
        return composers.map { stationSummary("radio-composer-${it.id}") } + singers.map { stationSummary("radio-singer-${it.id}") }
    }

    private fun stationSummary(id: String): MixDto {
        val (kind, key) = radioSeed(id) ?: error("bad station $id")
        val title: String
        val covers: List<String>
        when (kind) {
            "song" -> songs[lib.index.getValue(key)].let { title = "${it.title} Radio"; covers = listOf(it.coverArt) }
            "album" -> songs[lib.byAlbum.getValue(key).first()].let { title = "${it.album} Radio"; covers = listOf(it.coverArt) }
            else -> lib.people.getValue(key).let { title = "${it.name} Radio"; covers = listOf("ar-${it.id}") }
        }
        return MixDto(
            id = id, kind = "radio", title = title, subtitle = "Station", endless = true, refresh = "live",
            description = "Plays endlessly: songs that sound like this, picked as you listen.",
            covers = covers, round = kind == "composer" || kind == "singer", color = colorFor(id), updatedAt = now,
        )
    }

    /** ("song" | "album" | "composer" | "singer", id) from "radio-song-<id>", or null if it doesn't exist. */
    fun radioSeed(id: String): Pair<String, String>? {
        val parts = id.split("-", limit = 3)
        if (parts.size != 3 || parts[0] != "radio") return null
        val (kind, key) = parts[1] to parts[2]
        val known = when (kind) {
            "song" -> key in lib.index
            "album" -> key in lib.byAlbum
            "composer", "singer" -> key in lib.people
            else -> false
        }
        return if (known) kind to key else null
    }

    /**
     * The next [count] songs of a station, skipping [exclude] (what it already played). The first batch
     * of a song's station starts with that song.
     */
    fun radio(id: String, exclude: Set<String>, count: Int, random: Random): MixDto? {
        val (kind, key) = radioSeed(id) ?: return null
        val seeds = when (kind) {
            "song" -> listOf(lib.index.getValue(key))
            "album" -> lib.byAlbum.getValue(key)
            "composer" -> lib.byComposer[key].orEmpty()
            else -> lib.bySinger[key].orEmpty()
        }
        val isScoreStation = when (kind) {
            "song" -> songs[seeds.first()].score
            "album" -> seeds.isNotEmpty() && seeds.count { songs[it].score } > seeds.size / 2
            else -> false
        }
        // A station stays in its seed's language (a composer's or singer's: their main one).
        val language = mainLanguage(seeds)
        val scores = similarTo(seeds, language)
        if (kind == "song") for (i in 0 until n) scores[i] += 0.25f * affinity[i]
        val skip = exclude.mapNotNull { lib.index[it] }.toMutableSet()
        val first = if (kind == "song" && exclude.isEmpty()) seeds else emptyList()
        // A composer's or singer's own songs come up often, but not only them.
        val list = first + pick(
            scores, count - first.size, random, pool = 120,
            exclude = skip + first, maxPerAlbum = 2,
            maxPerPerson = if (kind == "composer" || kind == "singer") count else 6,
            languages = setOfNotNull(language),
            scoreOnly = isScoreStation,
        )
        return stationSummary(id).copy(songs = list.map(::song), songCount = list.size)
    }

    /** Songs to add to a playlist made of [songIds], best first. [page] > 0 gives the next ones. */
    fun recommend(songIds: List<String>, count: Int, page: Int): List<MixSong> {
        val seeds = songIds.mapNotNull { lib.index[it] }
        if (seeds.isEmpty()) return emptyList()
        val isScorePlaylist = seeds.count { songs[it].score } > seeds.size / 2
        // A playlist can be in more than one language on purpose: suggest in each one it has plenty of.
        val known = seeds.mapNotNull { lib.language[it] }
        val languages = known.groupingBy { it }.eachCount().filter { it.value >= 0.25 * known.size }.keys
        val scores = similarTo(seeds, languages = languages)
        for (i in 0 until n) scores[i] += 0.2f * affinity[i]
        val ranked = pick(
            scores, count * (page + 1), Random(0), pool = count * (page + 1),
            exclude = seeds.toSet(), maxPerAlbum = 2, scoreOnly = isScorePlaylist,
        )
        return ranked.drop(count * page).map(::song)
    }

    // ---------- the maths ----------

    /**
     * How much each song is like [seeds]: how often people put it with them (the strongest sign), how
     * similar it sounds (if analyzed), plus the same composer, shared singers and a nearby year. Songs in another language than [language] (or [languages]) are
     * left out (NaN); songs whose language is unknown count a little less.
     */
    fun similarTo(seeds: Collection<Int>, language: String? = null, languages: Set<String> = setOfNotNull(language)): FloatArray {
        val scores = FloatArray(n)
        if (seeds.isEmpty()) return scores
        centroid(seeds.toList(), null)?.let { c ->
            val sound = zScores(FloatArray(n) { i -> lib.sound[i]?.let { dot(it, c) } ?: Float.NaN })
            for (i in 0 until n) scores[i] += if (sound[i].isNaN()) -1f else sound[i]
        }
        if (!together.isEmpty) {
            val withSeeds = together.with(seeds, n)
            for (i in 0 until n) scores[i] += 3f * withSeeds[i]
        }
        val composers = share(seeds) { i -> listOfNotNull(songs[i].composer?.id) }
        val singers = share(seeds) { i -> songs[i].singers.map { it.id } }
        val years = seeds.map { songs[it].year }.filter { it > 1900 }.sorted()
        val year = years.getOrNull(years.size / 2)
        for (i in 0 until n) {
            val s = songs[i]
            scores[i] += 1.2f * (s.composer?.let { composers[it.id] } ?: 0.0).toFloat()
            scores[i] += 0.8f * (s.singers.maxOfOrNull { singers[it.id] ?: 0.0 } ?: 0.0).toFloat()
            if (year != null && s.year > 1900) scores[i] += 0.6f * exp(-abs(s.year - year) / 6.0).toFloat()
            if (languages.isNotEmpty()) {
                val l = lib.language[i]
                if (l == null) scores[i] -= 1f else if (l !in languages) scores[i] = Float.NaN
            }
        }
        return scores
    }

    /** The average sound of [items] (weighted), as length 1; null if none were analyzed. */
    private fun centroid(items: List<Int>, weights: Map<Int, Double>?): FloatArray? {
        var sum: FloatArray? = null
        for (i in items) {
            val v = lib.sound[i] ?: continue
            val w = (weights?.get(i) ?: 1.0).coerceAtLeast(0.0).toFloat()
            val s = sum ?: FloatArray(v.size).also { sum = it }
            for (d in v.indices) s[d] += w * v[d]
        }
        return sum?.let(::unit)
    }

    /** Groups sounds into [k] clusters (k-means on the sphere, starting from well spread-out songs). */
    fun kMeans(items: List<Int>, k: Int, random: Random): List<List<Int>> {
        if (items.size <= k) return listOf(items)
        val vectors = items.map { lib.sound[it]!! }
        val centers = mutableListOf(vectors[random.nextInt(vectors.size)])
        while (centers.size < k) {
            val distance = vectors.map { v -> centers.minOf { 1f - dot(v, it) }.coerceAtLeast(0f).toDouble() }
            var r = random.nextDouble() * distance.sum()
            val next = distance.indices.firstOrNull { r -= distance[it]; r <= 0 } ?: distance.indices.last
            centers += vectors[next]
        }
        var assignment = IntArray(items.size) { -1 }
        for (round in 0 until 20) {
            val next = IntArray(items.size) { p -> centers.indices.maxBy { dot(vectors[p], centers[it]) } }
            if (next.contentEquals(assignment)) break
            assignment = next
            for (c in centers.indices) {
                val members = items.indices.filter { assignment[it] == c }
                if (members.isNotEmpty()) centroid(members.map { items[it] }, weights)?.let { centers[c] = it }
            }
        }
        return centers.indices.map { c -> items.indices.filter { assignment[it] == c }.map { items[it] } }
    }

    /**
     * Chooses [count] songs from the best [pool] by score. Better songs are likelier, but there's some
     * variety, and no more than [maxPerAlbum] songs per movie and [maxPerPerson] per composer. The same
     * song twice (in two movies, or a remix) is left out. If the pool runs dry because of those limits,
     * the next best songs fill up the rest.
     */
    fun pick(
        scores: Map<Int, Float>, count: Int, random: Random, pool: Int,
        exclude: Set<Int> = emptySet(), maxPerAlbum: Int = 2, maxPerPerson: Int = 20,
        /** Only songs in these languages (and ones whose language is unknown); any if null. */
        languages: Set<String>? = null,
        scoreOnly: Boolean = false,
    ): List<Int> {
        val ranked = scores.entries.filter { (key, value) ->
            key !in exclude &&
                !value.isNaN() &&
                speaks(key, languages) &&
                if (scoreOnly) eligibleScore(key) else eligibleSong(key)
        }.sortedByDescending { it.value }.map { it.key }
        val chosen = mutableListOf<Int>()
        val titles = exclude.mapTo(mutableSetOf()) { sameSong(it) }
        val perAlbum = mutableMapOf<String, Int>()
        val perPerson = mutableMapOf<String, Int>()
        fun tryAdd(i: Int) {
            if (chosen.size >= count) return
            val s = songs[i]
            if ((perAlbum[s.albumId] ?: 0) >= maxPerAlbum) return
            val person = s.composer?.id
            if (person != null && (perPerson[person] ?: 0) >= maxPerPerson) return
            if (!titles.add(sameSong(i))) return
            chosen += i
            perAlbum.merge(s.albumId, 1, Int::plus)
            if (person != null) perPerson.merge(person, 1, Int::plus)
        }
        // Weighted choice without replacement from the pool: rank r is picked with weight e^(-r/spread).
        val spread = (pool / 5.0).coerceAtLeast(1.0)
        ranked.take(pool).withIndex()
            .map { (r, i) -> i to -ln(random.nextDouble().coerceAtLeast(1e-12)) / exp(-r / spread) }
            .sortedBy { it.second }.forEach { tryAdd(it.first) }
        ranked.drop(pool).forEach(::tryAdd)
        // Keep the best first, loosely, then keep songs from the same movie apart.
        return spread(chosen.sortedByDescending { scores[it] ?: 0f }.chunked(8).flatMap { it.shuffled(random) })
    }

    /** The same song, whichever movie folder or version it's in: "Mersalaayitten (Remix)" = "Mersalaayitten". */
    private fun sameSong(i: Int): String =
        if (songs[i].score) songs[i].title.lowercase().filter { it.isLetterOrDigit() } + "/" + songs[i].albumId
        else songs[i].title.substringBefore('(').substringBefore(" - ").lowercase().filter { it.isLetterOrDigit() } + "/" + (songs[i].composer?.id ?: "")

    private fun pick(
        scores: FloatArray, count: Int, random: Random, pool: Int, exclude: Set<Int> = emptySet(),
        maxPerAlbum: Int = 2, maxPerPerson: Int = 20, languages: Set<String>? = null,
        scoreOnly: Boolean = false,
    ) = pick(scores.indices.associateWith { scores[it] }, count, random, pool, exclude, maxPerAlbum, maxPerPerson, languages, scoreOnly)

    /** Reorders so that two songs from the same movie don't play back to back (when possible). */
    fun spread(list: List<Int>): List<Int> {
        val left = list.toMutableList()
        val out = mutableListOf<Int>()
        while (left.isNotEmpty()) {
            val previous = out.lastOrNull()?.let { songs[it].albumId }
            val next = left.indexOfFirst { songs[it].albumId != previous }.takeIf { it >= 0 } ?: 0
            out += left.removeAt(next)
        }
        return out
    }

    /** [fromA] from [a], [fromB] from [b], and so on (Daily Mixes: a favourite, then two new ones). */
    private fun interleave(a: List<Int>, b: List<Int>, fromA: Int = 1, fromB: Int = 2): List<Int> {
        val out = mutableListOf<Int>()
        val ia = a.iterator()
        val ib = b.iterator()
        while (ia.hasNext() || ib.hasNext()) {
            repeat(fromA) { if (ia.hasNext()) out += ia.next() }
            repeat(fromB) { if (ib.hasNext()) out += ib.next() }
        }
        return spread(out.distinct())
    }

    /** Each key's share of [items] (0..1), e.g. how much of your favourites are Ilaiyaraaja's. */
    private fun share(items: Collection<Int>, keys: (Int) -> List<String>): Map<String, Double> {
        val counts = mutableMapOf<String, Double>()
        for (i in items) {
            val w = (weights[i] ?: 1.0).coerceAtLeast(0.1)
            keys(i).distinct().forEach { counts.merge(it, w, Double::plus) }
        }
        val max = counts.values.maxOrNull() ?: return emptyMap()
        return counts.mapValues { it.value / max }
    }

    /** "Ilaiyaraaja, S. Janaki and more": the composers and singers heard most in a mix. */
    private fun namesIn(list: List<Int>, first: String? = null): String {
        val composers = list.mapNotNull { songs[it].composer?.name }.groupingBy { it }.eachCount()
        val singers = list.flatMap { i -> songs[i].singers.map { it.name } }.groupingBy { it }.eachCount()
        val names = (listOfNotNull(first) + composers.entries.sortedByDescending { it.value }.take(2).map { it.key } +
            singers.entries.sortedByDescending { it.value }.take(2).map { it.key }).distinct().take(3)
        return if (names.isEmpty()) "Songs picked for you" else names.joinToString(", ") + " and more"
    }

    private fun mix(
        id: String, kind: String, title: String, list: List<Int>, refresh: String, subtitle: String, description: String,
        covers: List<String>? = null, round: Boolean = false, color: String = colorFor(id),
    ) = MixDto(
        id = id, kind = kind, title = title, subtitle = subtitle, description = description, refresh = refresh,
        covers = covers ?: list.map { songs[it].albumId }.distinct().take(4).map { "al-$it" },
        round = round, color = color, songs = list.map(::song), songCount = list.size,
    )

    fun song(i: Int): MixSong = songs[i].let { s ->
        MixSong(
            id = s.id, title = s.title, artist = s.artist.takeIf { it.isNotBlank() }, album = s.album, albumId = s.albumId,
            coverArt = s.coverArt, duration = s.duration, year = s.year.takeIf { it > 0 }, artists = s.singers.map { PersonDto(it.id, it.name) },
        )
    }

    data class ScoreVibe(
        val key: String,
        val title: String,
        val subtitle: String,
        val description: String,
        val color: String,
        val moodKey: String,
        val titleKeywords: Set<String>,
    )

    fun scoreMix(vibe: ScoreVibe): MixDto? {
        val scoreIndices = lib.songs.indices.filter { eligibleScore(it) }
        if (scoreIndices.isEmpty()) return null
        val scores = mutableMapOf<Int, Float>()
        for (i in scoreIndices) {
            val s = songs[i]
            var fit = 0f
            if (lib.hasSound) {
                val m = lib.moods[vibe.moodKey]?.get(i) ?: Float.NaN
                if (!m.isNaN()) fit += m
            }
            val titleWords = s.title.lowercase().split(Regex("""\W+""")).filter { it.isNotBlank() }
            val albumWords = s.album.lowercase().split(Regex("""\W+""")).filter { it.isNotBlank() }
            val matches = (titleWords + albumWords).count { it in vibe.titleKeywords }
            fit += matches * 2.0f

            fit += 0.5f * (weights[i] ?: 0.0).toFloat()
            s.composer?.let { c -> fit += 0.8f * (composerLove[c.id] ?: 0.0).toFloat() }
            fit += 0.2f * ln(1.0 + (popularity[i] ?: 0)).toFloat()

            if (fit > -1.0f) {
                scores[i] = fit
            }
        }
        if (scores.isEmpty()) {
            for (i in scoreIndices) {
                scores[i] = ln(1.0 + (popularity[i] ?: 0)).toFloat()
            }
        }
        if (scores.isEmpty()) return null

        val random = Random(today.toEpochDay() * 11 + vibe.key.hashCode())
        val count = minOf(30, scoreIndices.size)
        val list = pick(
            scores, count, random, pool = 80,
            maxPerAlbum = 4, maxPerPerson = 20,
            scoreOnly = true,
        )
        if (list.isEmpty()) return null
        return mix(
            id = "score-${vibe.key}",
            kind = "score",
            title = vibe.title,
            subtitle = vibe.subtitle,
            description = "${vibe.description} Background scores and theme music only. Updates every day.",
            color = vibe.color,
            refresh = "daily",
            list = list,
        )
    }

    fun scoreMixes(): List<MixDto> = SCORE_VIBES.mapNotNull { scoreMix(it) }

    companion object {
        const val DAY = 24 * 60 * 60 * 1000L

        /** Mixes made from someone's own listening; everything else is a showcase, the same for everyone. */
        val PERSONAL_KINDS = setOf("daily", "discover", "repeat", "rewind", "friends")

        val SCORE_VIBES = listOf(
            ScoreVibe(
                key = "romantic-rose",
                title = "Smell the Romantic rose",
                subtitle = "Romantic themes & background scores",
                description = "Soft, intimate themes and background scores from original soundtracks. Immerse yourself in the fragrance of romance.",
                color = "#B03A5B",
                moodKey = "romantic",
                titleKeywords = setOf("romantic", "romance", "love", "heart", "kadhal", "prema", "rose", "kiss", "feel", "melody", "soul", "flute", "soft", "sweet"),
            ),
            ScoreVibe(
                key = "mass-elevation",
                title = "Feel the Mass elevation",
                subtitle = "Hero intros & elevation background scores",
                description = "High-voltage elevation themes and roaring beats. Feel the cinematic rush and adrenaline of the ultimate mass moments.",
                color = "#A3271F",
                moodKey = "heroic",
                titleKeywords = setOf("mass", "elevation", "hero", "intro", "roar", "action", "entry", "badass", "fight", "beast", "rage", "climax", "power", "rule", "don", "king", "punch", "fire", "sword", "hunter"),
            ),
            ScoreVibe(
                key = "tears-solitude",
                title = "Echoes of the Broken Soul",
                subtitle = "Emotional & pathos background scores",
                description = "Melancholic violins and poignant themes from emotional movie scenes. For the reflective and quiet hours.",
                color = "#4A5A7A",
                moodKey = "sad",
                titleKeywords = setOf("sad", "pain", "loss", "tears", "broken", "pathos", "tragedy", "separation", "death", "lonely", "sorrow", "emotional", "crying", "agony"),
            ),
            ScoreVibe(
                key = "breeze-serenity",
                title = "Whispering Wind of Peace",
                subtitle = "Calm, acoustic & soothing themes",
                description = "Gentle acoustic flutes, soft strings and calming scores. A serene soundscape to unwind and breathe.",
                color = "#3B6E8F",
                moodKey = "chill",
                titleKeywords = setOf("chill", "peace", "calm", "serenity", "breeze", "morning", "silence", "gentle", "flute", "acoustic", "rain", "solitude", "sleep", "soothing"),
            ),
            ScoreVibe(
                key = "dark-suspense",
                title = "Shadows of Dark Suspense",
                subtitle = "Thrilling & mystery background scores",
                description = "Ominous basses, ticking clocks and gripping suspense cues. On the edge of your seat.",
                color = "#28335C",
                moodKey = "instrumental",
                titleKeywords = setOf("dark", "suspense", "thrill", "chase", "mystery", "shadow", "horror", "fear", "tension", "crime", "secret", "hunt", "danger", "investigation"),
            ),
            ScoreVibe(
                key = "grandeur-symphony",
                title = "Grandeur of the Symphony",
                subtitle = "Majestic orchestral & classical scores",
                description = "Orchestral sweeps, classical ragas and grandeur. The timeless musical craftsmanship of cinema.",
                color = "#6D4C8F",
                moodKey = "classical",
                titleKeywords = setOf("symphony", "classical", "grand", "theme", "carnatic", "raga", "orchestra", "royal", "destiny", "magic", "title", "overture", "epic", "legend"),
            ),
        )

        val MOODS = listOf(
            Mood(
                "chill", "Chill Mix", "Calm, soft and easy", mapOf("chill" to 1f, "melody" to 0.5f), energy = -1f, color = "#3B6E8F",
                maxEnergy = 0.3f, maxRhythm = 0.3f, vetoes = mapOf("party" to 1.0f), group = "calm",
            ),
            Mood("romance", "Romance Mix", "Love songs and duets", mapOf("romantic" to 1f, "melody" to 0.4f), color = "#B03A5B"),
            Mood("happy", "Feel Good Mix", "Bright, happy songs", mapOf("happy" to 1f), energy = 0.3f, color = "#E0A21B"),
            Mood("party", "Party Mix", "Loud, fast, made for dancing", mapOf("party" to 1f, "kuthu" to 0.5f), energy = 1f, color = "#D2462D"),
            Mood(
                "kuthu", "Kuthu Mix", "Folk beats and thappu drums", mapOf("kuthu" to 1f), energy = 0.5f, color = "#C0561B", language = "Tamil",
                vetoes = mapOf("melody" to 1.0f, "sad" to 1.0f), indianSound = true,
            ),
            Mood(
                "mass", "Kollywood Mass", "Hero intros and mass beats", mapOf("heroic" to 1f, "kuthu" to 0.5f, "party" to 0.3f), energy = 1f,
                color = "#A3271F", language = "Tamil", vetoes = mapOf("melody" to 1.2f, "sad" to 1.0f), indianSound = true,
            ),
            Mood("sad", "Sad Songs", "For the heavy-hearted", mapOf("sad" to 1f), energy = -0.3f, color = "#4A5A7A"),
            Mood("melody", "Melody Mix", "Soft film melodies", mapOf("melody" to 1f), color = "#5B7F4A", oneLanguage = true),
            Mood("workout", "Workout Mix", "Big energy to keep you moving", mapOf("heroic" to 1f, "party" to 0.5f), energy = 1f, color = "#8C2F39"),
            Mood("devotional", "Devotional", "Bhakti songs and hymns", mapOf("devotional" to 1f), color = "#B5651D", oneLanguage = true),
            Mood("classical", "Carnatic Touch", "Songs with a classical soul", mapOf("classical" to 1f), color = "#6D4C8F", oneLanguage = true),
            Mood(
                "focus", "Focus", "Mostly instrumental, easy to work to", mapOf("instrumental" to 1f, "chill" to 0.4f), energy = -0.5f, color = "#2F6B6B",
                maxRhythm = 0.5f, requires = mapOf("instrumental" to 1.5f), vetoes = mapOf("party" to 1.5f), group = "calm",
            ),
            Mood(
                "sleep", "Sleep", "Lullabies and quiet songs", mapOf("lullaby" to 1f, "chill" to 0.7f), energy = -1f, color = "#28335C",
                maxEnergy = -0.2f, maxRhythm = -0.3f, vetoes = mapOf("party" to 0.5f), group = "calm",
            ),
            Mood("retro", "Retro Mix", "Old-school sound", mapOf("retro" to 1f), color = "#8A6A3B", oneLanguage = true),
        )

        private val PALETTE = listOf("#7A3E9D", "#1E7F74", "#B8452E", "#2E5E9E", "#9C6B1C", "#4E7F2E", "#A33A6B", "#4F4F9E", "#2F7F9E", "#8F4A2F")
        private val DECADE_COLORS = listOf("#6B4F3A", "#7A5C2E", "#3A6B5C", "#5C3A6B", "#2E5E7A", "#7A2E4E", "#3E7A2E", "#7A6A2E")

        fun colorFor(id: String) = PALETTE[Math.floorMod(id.substringBefore('-').hashCode() * 31 + id.hashCode(), PALETTE.size)]
    }
}

private fun unit(v: FloatArray): FloatArray {
    val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-9f)
    return FloatArray(v.size) { v[it] / norm }
}
