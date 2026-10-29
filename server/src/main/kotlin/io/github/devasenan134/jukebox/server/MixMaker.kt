package io.github.devasenan134.jukebox.server

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
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

/**
 * How the apps draw a mix's cover: one design per kind of mix (docs/ANALYZER_MIXES_AND_STORAGE.md, "Covers"),
 * with its picture, colours and words worked out here so every app draws the same thing.
 */
@Serializable
data class MixArt(
    /** "sleeve", "cassette", "sky", "crate", "spin", "polaroid", "photos", "lp", "single", "reel", "cd", "diecut", "paper", "turntable" or "tuner". */
    val style: String,
    /** The small line in capitals ("DAILY MIX", "WEEK 44 · 30 NEW SONGS"). */
    val kicker: String = "",
    /** The big words ("01", "Kasu Panam", "80s"). */
    val headline: String = "",
    /** The picture: a person's ("ar-…") or a cover ("al-…"); none: the mix's covers. */
    val picture: String? = null,
    /** The background, and the colour drawn on it: text, and the light half of a two-tone photo. */
    val ground: String,
    val ink: String,
    /** For "sky": "dawn", "day", "dusk", "night" or "rain". */
    val variant: String? = null,
    /** A sticker or dial reading: "FRI 31 OCT", "96.4 FM". */
    val stamp: String? = null,
)

/** A mix, playlist or station made by Jukebox. In lists, [songs] is left out and only [songCount] is set. */
@Serializable
data class MixDto(
    val id: String,
    /**
     * "daily", "daylist", "discover", "fresh", "repeat", "rewind", "new", "friends", "because", "deepcuts", "gems",
     * "artistmix", "topic", "season", "popular", "mood", "decade", "score", "composer", "singer" or "radio".
     */
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
    /** Shown with a "New" badge: back on your Home after a week or more away, or a weekly mix on its new week. */
    val isNew: Boolean = false,
    /** How to draw its cover; none: the old way, from [covers] and [color]. */
    val art: MixArt? = null,
    val songs: List<MixSong> = emptyList(),
) {
    fun summary() = copy(songs = emptyList(), songCount = songs.size.takeIf { it > 0 } ?: songCount)
}

@Serializable data class MixSection(val id: String, val title: String, val mixes: List<MixDto>)

/**
 * How much each sign counts when songs are compared ([MixMaker.similarTo]) and how much a song being well known
 * counts in suggestions. MixEval (test code) scores other settings against people's own playlists and listening.
 */
data class Tuning(
    /** A big service's listeners play the songs together (JioSaavn's recommendations). */
    val links: Float = 3f,
    /** People here play them together, or put them in one playlist. */
    val together: Float = 3f,
    /** They sound alike. */
    val sound: Float = 1f,
    // Composer and era count more than they used to: songs one person plays in a row share a composer 4 times
    // in 10, and nearly every song of their playlists shares one with the rest (MixEval, 2026-10-28).
    val composer: Float = 2f,
    val singer: Float = 0.8f,
    val year: Float = 1.2f,
    /** Songs that are played a lot on that service come a little sooner in suggestions. */
    val fame: Float = 0.25f,
)

/** How often someone skipped a song early vs. listened to it (reported by the app). */
data class SkipStats(val skips: Int, val listens: Int)

/** A list of songs Jukebox handed to this person on [day] (a mix they opened, or suggestions for a playlist). */
class ShownList(val mixId: String, val day: LocalDate, val songs: List<Int>)

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
    /** What this person was shown before today, so songs they keep passing over make room for others. */
    private val shown: List<ShownList> = emptyList(),
    /** Their time zone, for the time of day (daylist) and the day a week starts. */
    private val zone: ZoneId = ZoneOffset.UTC,
    private val tuning: Tuning = Tuning(),
) {
    private val n = lib.songs.size
    private val songs = lib.songs

    /** Songs this person keeps skipping: left out of their mixes. */
    private val avoid: Set<Int> = skips.filter { (_, s) -> s.skips >= 2 && s.skips >= 2 * s.listens }.keys

    private fun eligible(i: Int) = !songs[i].karaoke && i !in avoid
    private fun eligibleSong(i: Int) = eligible(i) && songs[i].duration >= 60 && !songs[i].score
    private fun eligibleScore(i: Int) = eligible(i) && songs[i].duration >= 20 && songs[i].score

    private val heard: Set<Int> = history.playCount.keys + history.plays.map { it.first }

    // ---------- What you were shown ----------
    //
    // A song Jukebox showed you that you didn't go on to play comes up a little less next time, more so
    // each time it's passed over, and it recovers over a few weeks (or at once, when you play it). Only
    // what was shown before a mix's own day counts (today for daily mixes, Monday for weekly ones), so
    // opening a mix doesn't change it under you.

    private val monday: LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private val fatigueAsOf = java.util.concurrent.ConcurrentHashMap<LocalDate, Map<Int, Float>>()

    /** How much each song was passed over before [day] (0 if not at all, at most [MAX_FATIGUE]). */
    private fun fatigue(day: LocalDate = today): Map<Int, Float> = fatigueAsOf.getOrPut(day) {
        val shownOn = HashMap<Int, MutableSet<LocalDate>>()
        for (list in shown) {
            if (!list.day.isBefore(day) || list.day.isBefore(day.minusDays(56))) continue
            list.songs.forEach { shownOn.getOrPut(it) { mutableSetOf() } += list.day }
        }
        buildMap {
            for ((i, days) in shownOn) {
                val played = history.lastPlayed[i]?.let { LocalDate.ofInstant(Instant.ofEpochMilli(it), zone) }
                val passed = days.filter { played == null || played.isBefore(it) }
                val f = 0.7f * passed.sumOf { exp(-(day.toEpochDay() - it.toEpochDay()) / 14.0) }.toFloat()
                if (f > 0.05f) put(i, f.coerceAtMost(MAX_FATIGUE))
            }
        }
    }

    private fun tired(i: Int, day: LocalDate = today) = fatigue(day)[i] ?: 0f

    /** These scores, less how much each song was passed over before [day] ([factor] of it). */
    private fun FloatArray.lessTired(day: LocalDate = today, factor: Float = 1f): FloatArray {
        for ((i, f) in fatigue(day)) this[i] -= factor * f
        return this
    }

    /** Songs earlier Discover Weeklies showed you (the 8 weeks before this one): this week's has others. */
    private val shownInDiscover: Set<Int> by lazy {
        shown.filter { it.mixId == "discover" && it.day.isBefore(monday) && !it.day.isBefore(monday.minusWeeks(8)) }.flatMapTo(HashSet()) { it.songs }
    }

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
            if (!hasTaste) a += 0.3f * ln(1.0 + (popularity[i] ?: 0)).toFloat() + 0.4f * known(i)
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

    /** Whether a weekly mix that changes on [day] is in its first two days (it shows "New"). */
    private fun newWeek(day: DayOfWeek) = today.dayOfWeek == day || today.dayOfWeek == day.plus(1)
    private val weekRandom get() = Random(personSeed * 17 + today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay())

    // ---------- Home ----------

    /**
     * The first three rows are made from this person's listening ("Made for <name>" in the app): Made for
     * you (the app puts what you played lately right after it), Your top mixes, which rotate from day to
     * day, and Your stations. The rest showcase the library: moods, scores, composer and artist stations, charts, decades.
     */
    fun home(): List<MixSection> {
        val madeForYou = buildList {
            daylist()?.let(::add)
            addAll(dailyMixes())
            discover()?.let(::add)
            freshForYou()?.let(::add)
            onRepeat()?.let(::add)
            rewind()?.let(::add)
            friendsMix()?.let(::add)
        }
        val top = topMixes()
        val inTop = top.map { it.id }.toSet()
        val stations = yourStations()
        val charts = listOfNotNull(popular(), newArrivals())
        // Composers and singers are on Home as stations only (their This Is mixes open from their pages).
        return listOf(
            MixSection("made-for-you", "Made for you", madeForYou.map(::personalized)),
            MixSection("top-mixes", "Your top mixes", top),
            MixSection("your-stations", "Your stations", stations.map { it.copy(personal = true) }),
            // A mix already in Your top mixes isn't shown twice.
            MixSection("moods", "Moods and vibes", MOODS.filter { "mood-${it.key}" !in inTop }.mapNotNull { mood(it) }),
            MixSection("scores", "Themes and background scores", scoreMixes().filter { it.id !in inTop }),
            MixSection("artist-stations", "Composer and artist stations", artistStations(skip = stations.mapNotNull { radioSeed(it.id) }.map { it.second }.toSet())),
            MixSection("charts", "Popular and new", charts),
            MixSection("decades", "Through the decades", decades().filter { it.id !in inTop }),
        ).filter { it.mixes.isNotEmpty() }
    }

    /** Any mix by id, including ones not on Home (like a composer's mix opened from their page). */
    fun byId(id: String): MixDto? = findById(id)?.let(::personalized)

    /**
     * Marks mixes made from this person's listening (see [PERSONAL_KINDS]). Showcases (moods, decades,
     * composers, singers, charts, their stations) are for everyone, though moods and decades lean towards your taste.
     */
    private fun personalized(mix: MixDto): MixDto = mix.copy(
        personal = mix.personal || mix.kind in PERSONAL_KINDS,
    )

    private fun findById(id: String): MixDto? {
        val parts = id.split("-", limit = 2)
        val rest = parts.getOrNull(1)?.split("-").orEmpty()
        return when (parts[0]) {
            "daily" -> dailyMixes().firstOrNull { it.id == id }
            "daylist" -> daylist()
            "discover" -> discover()
            "fresh" -> freshForYou()
            "because" -> rest.singleOrNull()?.let { lib.index[it] }?.let { because(it) }
            "deepcuts" -> deepCuts()
            "gems" -> hiddenGems()
            "amix" -> if (rest.size == 2) lib.people[rest[1]]?.let { artistMix(it, composer = rest[0] == "c") } else null
            "cmood" -> if (rest.size == 2) lib.people[rest[0]]?.let { p -> MOODS.firstOrNull { it.key == rest[1] }?.let { composerMood(p, it) } } else null
            "cdecade" -> if (rest.size == 2) lib.people[rest[0]]?.let { p -> rest[1].toIntOrNull()?.let { composerDecade(p, it) } } else null
            "pair" -> if (rest.size == 2) lib.people[rest[0]]?.let { c -> lib.people[rest[1]]?.let { pair(c, it) } } else null
            "season" -> SEASONS.firstOrNull { it.key == parts.getOrNull(1) }?.let { season(it) }
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

        // What you've played in the last two weeks leads, so the mixes follow what you're into lately.
        val lately = history.plays.filter { it.second > now - 14 * DAY }.groupingBy { it.first }.eachCount()
        val used = mutableSetOf<Int>()
        return groups.mapIndexed { number, (language, group) ->
            val random = Random(personSeed * 31 + today.toEpochDay() * 7 + number)
            val favourites = pick(
                group.associateWith { ((weights[it] ?: 0.0) + 1.5 * ln(1.0 + (lately[it] ?: 0))).toFloat() }, 15, random, pool = 40, scoreOnly = false,
            )
            // Songs like today's favourites (not the whole side of your taste), so the new ones change from day to day.
            val scores = similarTo(favourites, language)
            for (i in 0 until n) scores[i] += 0.3f * affinity[i]
            scores.lessTired()
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

    /** This week's Discover Weekly songs (none if there's too little to go on). */
    private val discoverSongs: List<Int> by lazy {
        if (likedSongs.size < 3) return@lazy emptyList()
        val scores = similarTo(likedSongs.take(60), languages = yourLanguages.keys)
        for (i in 0 until n) scores[i] += 0.3f * affinity[i] + 0.4f * ln(1.0 + (friendsPlays[i] ?: 0)).toFloat() + tuning.fame * known(i)
        scores.lessTired(monday)
        pickBlend(
            scores.indices.associateWith { scores[it] }, 30, weekRandom, pool = 150, blend = yourLanguages,
            exclude = heard + history.starred + history.playlisted.keys + shownInDiscover, maxPerAlbum = 1, maxPerPerson = 5, scoreOnly = false,
        )
    }

    /** 30 songs you haven't heard yet, picked for how you listen, and not ones it showed you in the last weeks. Changes on Mondays. */
    fun discover(): MixDto? {
        val list = discoverSongs
        if (list.size < 10) return null
        return mix(
            "discover", "discover", "Discover Weekly", list, refresh = "weekly", subtitle = "New to you, picked for you",
            description = "Your weekly mixtape of songs you haven't played yet, chosen for how you listen. Updates every Monday.",
        ).copy(isNew = newWeek(DayOfWeek.MONDAY))
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

    // ---------- Made for you: new ones ----------

    /** The part of the day it is where this person is (night until 5 am). */
    private val dayPart: DayPart = Instant.ofEpochMilli(now).atZone(zone).hour.let(::partOf)

    /**
     * A mix for this time of day, named after it ("Tuesday night melodies"): what you usually play around
     * this time, and new songs with the feeling that fits it best. Changes through the day.
     */
    fun daylist(): MixDto? {
        if (!hasTaste || !lib.hasSound) return null
        val part = dayPart
        val atThisTime = history.plays
            .filter { (i, at) -> at > now - 60 * DAY && eligibleSong(i) && partOf(Instant.ofEpochMilli(at).atZone(zone).hour) == part }
            .groupingBy { it.first }.eachCount()
        val seeds = atThisTime.keys.toList().ifEmpty { likedSongs.take(60) }
        val random = Random(personSeed * 37 + today.toEpochDay() * 4 + DAY_PARTS.indexOf(part))
        // The feeling that fits what you play at this time best, with some variety from day to day.
        // A song whose labels rule a feeling out counts against it (it doesn't fit); one we know nothing about doesn't count.
        val vibe = part.vibes.mapNotNull { key -> MOODS.firstOrNull { it.key == key }?.takeIf { moodMembers(it) != null } }
            .maxByOrNull { m ->
                val fit = seeds.mapNotNull { i -> moodFit(m, i).let { if (!it.isNaN()) it else if (lib.labels[i] != null) -1f else null } }
                    .takeIf { it.isNotEmpty() }?.average() ?: 0.0
                fit + 0.5 * gumbel(random)
            } ?: return null
        val members = moodMembers(vibe) ?: return null
        val like = similarTo(seeds.take(80), languages = yourLanguages.keys)
        val scores = members.mapValues { (i, fit) -> fit + 0.5f * like[i] + 0.4f * affinity[i] - tired(i) }.filterValues { !it.isNaN() }
        // Your own songs for this time, unless they're the opposite of the feeling.
        val yours = atThisTime.filterKeys { i -> fitsVibe(vibe, i) }
            .mapValues { (i, count) -> (ln(1.0 + count) + 0.3 * (weights[i] ?: 0.0)).toFloat() }
        val familiar = pick(yours, 10, random, pool = 30)
        val fresh = pickBlend(scores, 40 - familiar.size, random, pool = 150, blend = yourLanguages, exclude = familiar.toSet())
        if (fresh.size < 15) return null
        val list = interleave(familiar, fresh)
        val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return mix(
            "daylist", "daylist", "$weekday ${part.name} ${vibe.noun}", list, refresh = "live", subtitle = "Changes through the day",
            description = "Your ${weekday.lowercase()} ${part.name}: ${vibe.noun}, and what you usually play around this time. Changes through the day.",
        )
    }

    /**
     * Whether one of your songs for this time of day fits its feeling: not ruled out by its labels, and sounding
     * at least somewhat like it; a song we know nothing about (no labels, not analyzed) gets the benefit of the doubt.
     */
    private fun fitsVibe(vibe: Mood, i: Int): Boolean {
        val fit = moodFit(vibe, i)
        return if (lib.labels[i] != null || lib.sound[i] != null) !fit.isNaN() && fit >= 0.5f else true
    }

    /** Songs added to the library in the two weeks before Friday that suit you. Changes on Fridays. */
    fun freshForYou(): MixDto? {
        if (!hasTaste) return null
        val added = songs.indices.filter { eligibleSong(it) && songs[it].addedAt > 0 }
        if (added.isEmpty()) return null
        // As for New Arrivals: the first big import isn't new.
        val importedAt = added.map { songs[it].addedAt }.sorted()[added.size / 2]
        val friday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))
        val until = friday.atStartOfDay(zone).toInstant().toEpochMilli()
        val since = maxOf(importedAt + DAY, until - 14 * DAY)
        val scores = added.filter { songs[it].addedAt in since until until && it !in heard && speaks(it, yourLanguages.keys) }
            .associateWith { affinity[it] - tired(it, friday) }
        val list = pick(scores, 30, Random(personSeed * 19 + friday.toEpochDay()), pool = 60, maxPerAlbum = 3, maxPerPerson = 10)
        if (list.size < 5) return null
        return mix(
            "fresh", "fresh", "Fresh for You", list, refresh = "weekly", subtitle = "New in the library, for your taste",
            description = "Songs added to the library in the two weeks before Friday, the ones that suit you first. Updates every Friday.",
        ).copy(isNew = newWeek(DayOfWeek.FRIDAY))
    }

    // ---------- Your top mixes ----------
    //
    // A pool of mixes made from your listening (Because you played..., composer mixes, a composer's
    // melodies or 90s, moods and decades you play, deep cuts, hidden gems, the season's songs), of which
    // Home shows a different handful each day: the ones that suit you best come up most often.

    /** A mix that could be in Your top mixes: how well it suits you, its sort (for variety) and whose it is. */
    private class Candidate(val id: String, val fit: Double, val sort: String, val person: String? = null)

    fun topMixes(): List<MixDto> {
        if (!hasTaste && !hasScoreListening) return emptyList()
        val candidates = mutableListOf<Candidate>()
        SEASONS.filter { it.on(today) }.forEach { candidates += Candidate("season-${it.key}", 10.0, "season") }
        if (hasTaste) {
            becauseAnchors().forEach { candidates += Candidate("because-${songs[it].id}", 3.0, "because") }
            candidates += Candidate("deepcuts", 1.6, "deepcuts")
            candidates += Candidate("gems", 1.3, "gems")
            val composers = composerLove.entries.filter { (lib.byComposer[it.key]?.size ?: 0) >= 15 }.sortedByDescending { it.value }.take(4)
            for ((id, love) in composers) {
                candidates += Candidate("amix-c-$id", 1.0 + 2 * love, "artist", id)
                val theirs = lib.byComposer.getValue(id).filter(::eligibleSong)
                // Only feelings you listen to: how a song sounds isn't a sure sign of every mood (a composer's "devotional" may not be).
                for (mood in MOODS) {
                    if (mood.noun == null) continue
                    val members = moodMembers(mood) ?: continue
                    val share = moodShare(mood)
                    if (share >= 0.08 && theirs.count { it in members } >= 15) {
                        candidates += Candidate("cmood-$id-${mood.key}", 0.4 + 1.2 * love + 3 * share, "topic", id)
                    }
                }
                val decades = theirs.filter { songs[it].year > 1900 }.groupingBy { songs[it].year / 10 * 10 }.eachCount().filterValues { it >= 25 }
                if (decades.size >= 2) decades.keys.forEach { candidates += Candidate("cdecade-$id-$it", 0.5 + 1.5 * love, "topic", id) }
            }
            singerLove.entries.filter { (lib.bySinger[it.key]?.size ?: 0) >= 15 && composers.none { c -> c.key == it.key } }
                .sortedByDescending { it.value }.take(2)
                .forEach { (id, love) -> candidates += Candidate("amix-s-$id", 0.8 + 1.5 * love, "artist", id) }
            topPairs().forEach { (c, singer, fit) -> candidates += Candidate("pair-$c-$singer", fit, "topic", c) }
            MOODS.mapNotNull { m -> moodMembers(m)?.let { m to moodShare(m) } }.filter { it.second >= 0.05 }
                .sortedByDescending { it.second }.take(3).forEach { (m, share) -> candidates += Candidate("mood-${m.key}", 0.8 + 3 * share, "mood") }
            likedSongs.filter { songs[it].year > 1900 }.groupingBy { songs[it].year / 10 * 10 }.eachCount()
                .mapValues { it.value.toDouble() / likedSongs.size }.filterValues { it >= 0.1 }.entries
                .sortedByDescending { it.value }.take(2).forEach { (start, share) -> candidates += Candidate("decade-$start", 0.6 + 2 * share, "decade") }
        }
        if (hasScoreListening) SCORE_VIBES.forEach { candidates += Candidate("score-${it.key}", 0.9, "score") }

        val random = dayRandom
        val ranked = candidates.map { it to it.fit + 0.7 * gumbel(random) }.sortedByDescending { it.second }.map { it.first }
        val perSort = HashMap<String, Int>()
        val perPerson = HashMap<String, Int>()
        // One of a composer's melodies, mass songs or 90s a day: they'd share too many songs.
        val topicOf = HashSet<String>()
        val out = mutableListOf<MixDto>()
        for (c in ranked) {
            if (out.size >= TOP_MIXES) break
            if ((perSort[c.sort] ?: 0) >= (TOP_MIX_CAPS[c.sort] ?: 1)) continue
            if (c.person != null && (perPerson[c.person] ?: 0) >= 2) continue
            if (c.sort == "topic" && c.person != null && c.person in topicOf) continue
            val mix = findById(c.id) ?: continue
            if (c.sort == "topic" && c.person != null) topicOf += c.person
            out += mix.copy(personal = true)
            perSort.merge(c.sort, 1, Int::plus)
            c.person?.let { perPerson.merge(it, 1, Int::plus) }
        }
        return out
    }

    /** How much of your favourite songs have [mood] (0 to 1). */
    private fun moodShare(mood: Mood): Double {
        val members = moodMembers(mood) ?: return 0.0
        return if (likedSongs.isEmpty()) 0.0 else likedSongs.count { it in members }.toDouble() / likedSongs.size
    }

    /** Up to 3 songs you played this past week, a different few each day, one per movie. */
    private fun becauseAnchors(): List<Int> {
        val week = history.plays.filter { (i, at) -> at > now - 7 * DAY && (eligibleSong(i) || eligibleScore(i)) }.groupingBy { it.first }.eachCount()
        return week.entries.sortedByDescending { it.value }.take(8).map { it.key }.shuffled(dayRandom).distinctBy { songs[it].albumId }.take(3)
    }

    /** The composer and singer pairs you love most (3 or more favourites together), with how much. */
    private fun topPairs(): List<Triple<String, String, Double>> {
        val together = HashMap<Pair<String, String>, Double>()
        for (i in likedSongs) {
            val c = songs[i].composer?.id ?: continue
            songs[i].singers.forEach { s -> if (s.id != c) together.merge(c to s.id, weights[i] ?: 0.0, Double::plus) }
        }
        val counts = likedSongs.flatMap { i -> songs[i].composer?.id?.let { c -> songs[i].singers.map { c to it.id } }.orEmpty() }.groupingBy { it }.eachCount()
        val best = together.values.maxOrNull() ?: return emptyList()
        return together.entries.filter { (counts[it.key] ?: 0) >= 3 }.sortedByDescending { it.value }.take(3)
            .map { (pair, w) -> Triple(pair.first, pair.second, 0.6 + 1.2 * w / best) }
    }

    /** [anchor] and 29 songs that go with it. */
    fun because(anchor: Int): MixDto? {
        val s = songs[anchor]
        if (!eligibleSong(anchor) && !eligibleScore(anchor)) return null
        val language = lib.language[anchor]
        val scores = similarTo(listOf(anchor), language)
        for (i in 0 until n) scores[i] += 0.25f * affinity[i]
        scores.lessTired()
        val random = Random(personSeed * 29 + today.toEpochDay() * 3 + anchor)
        val list = listOf(anchor) + pick(
            scores, 29, random, pool = 100, exclude = setOf(anchor), maxPerAlbum = 2, maxPerPerson = 8,
            languages = setOfNotNull(language), scoreOnly = s.score,
        )
        if (list.size < 10) return null
        return mix(
            "because-${s.id}", "because", "Because you played ${s.title}", list, refresh = "daily", subtitle = namesIn(list),
            description = "Songs that go with ${s.title} (${s.album}), which you've been playing lately.",
        )
    }

    /** A composer's or singer's songs (the ones you like first) and music like theirs, half and half. */
    fun artistMix(person: Person, composer: Boolean): MixDto? {
        val theirs = (if (composer) lib.byComposer[person.id] else lib.bySinger[person.id]).orEmpty().filter(::eligibleSong)
        if (theirs.size < 8) return null
        val language = mainLanguage(theirs)
        val random = Random(personSeed * 23 + today.toEpochDay() * 5 + person.id.hashCode())
        val own = theirs.associateWith { i ->
            (weights[i] ?: 0.0).toFloat() + 0.5f * affinity[i] + 0.2f * ln(1.0 + (popularity[i] ?: 0)).toFloat() - tired(i)
        }
        val mine = pick(own, 25, random, pool = 60, maxPerAlbum = 3, maxPerPerson = 25, languages = setOfNotNull(language))
        val like = similarTo(theirs.sortedByDescending { weights[it] ?: 0.0 }.take(40), language)
        for (i in 0 until n) like[i] += 0.3f * affinity[i]
        like.lessTired()
        val others = pick(like, 25, random, pool = 120, exclude = theirs.toSet(), maxPerAlbum = 2, maxPerPerson = 5, languages = setOfNotNull(language))
        val list = interleave(mine, others, fromA = 1, fromB = 1)
        if (list.size < 15) return null
        return mix(
            "amix-${if (composer) "c" else "s"}-${person.id}", "artistmix", "${person.name} Mix", list, refresh = "daily",
            subtitle = namesIn(list, first = person.name), covers = listOf("ar-${person.id}"), round = true,
            description = "${person.name} and music like theirs, picked for you. Updates every day.",
        )
    }

    /** A composer's songs with one feeling: "Ilaiyaraaja Melodies". */
    fun composerMood(person: Person, mood: Mood): MixDto? {
        val noun = mood.noun ?: return null
        val members = moodMembers(mood) ?: return null
        val theirs = lib.byComposer[person.id].orEmpty().filter { it in members && eligibleSong(it) }
        if (theirs.size < 15) return null
        val scores = theirs.associateWith { i -> members.getValue(i) + 0.5f * affinity[i] + 0.3f * (weights[i] ?: 0.0).toFloat() - tired(i) }
        val random = Random(personSeed * 41 + today.toEpochDay() + (person.id + mood.key).hashCode())
        val list = pick(scores, 30, random, pool = 80, maxPerAlbum = 3, maxPerPerson = 30, languages = setOfNotNull(mainLanguage(theirs)))
        if (list.size < 20) return null  // after 3 per movie, a few films aren't enough
        return mix(
            "cmood-${person.id}-${mood.key}", "topic", "${person.name} ${titleCase(noun)}", list, refresh = "daily",
            subtitle = namesIn(list, first = person.name), color = mood.color,
            description = "${titleCase(noun)} composed by ${person.name}, the ones that suit you first. Updates every day.",
        )
    }

    /** A composer's songs from one decade: "90s Ilaiyaraaja". */
    fun composerDecade(person: Person, start: Int): MixDto? {
        val theirs = lib.byComposer[person.id].orEmpty().filter { eligibleSong(it) && songs[it].year in start until start + 10 }
        if (theirs.size < 25) return null
        val scores = theirs.associateWith { i ->
            (weights[i] ?: 0.0).toFloat() + 0.5f * affinity[i] + 0.3f * ln(1.0 + (popularity[i] ?: 0)).toFloat() - tired(i)
        }
        val random = Random(personSeed * 43 + today.toEpochDay() + person.id.hashCode() + start)
        val list = pick(scores, 30, random, pool = 80, maxPerAlbum = 3, maxPerPerson = 30)
        if (list.size < 20) return null  // after 3 per movie, a few films aren't enough
        return mix(
            "cdecade-${person.id}-$start", "topic", "${decadeName(start)} ${person.name}", list, refresh = "daily",
            subtitle = namesIn(list, first = person.name), color = DECADE_COLORS[(start / 10) % DECADE_COLORS.size],
            description = "${person.name}'s songs from $start to ${start + 9}, the ones that suit you first. Updates every day.",
        )
    }

    /** Songs one composer made with one singer: "Ilaiyaraaja & S. Janaki". */
    fun pair(composer: Person, singer: Person): MixDto? {
        val theirs = lib.byComposer[composer.id].orEmpty().filter { i -> eligibleSong(i) && songs[i].singers.any { it.id == singer.id } }
        if (theirs.size < 10) return null
        val scores = theirs.associateWith { i ->
            (weights[i] ?: 0.0).toFloat() + 0.5f * affinity[i] + 0.3f * ln(1.0 + (popularity[i] ?: 0)).toFloat() - tired(i)
        }
        val random = Random(personSeed * 47 + today.toEpochDay() + (composer.id + singer.id).hashCode())
        val list = pick(scores, 30, random, pool = 80, maxPerAlbum = 3, maxPerPerson = 30)
        return mix(
            "pair-${composer.id}-${singer.id}", "topic", "${composer.name} & ${singer.name}", list, refresh = "daily",
            subtitle = "Composed by ${composer.name}, sung by ${singer.name}",
            description = "Songs ${composer.name} composed and ${singer.name} sang, the ones that suit you first. Updates every day.",
        )
    }

    /** Songs you've never played from the albums you play most. Changes on Mondays. */
    fun deepCuts(): MixDto? {
        val love = HashMap<String, Double>()
        for (i in likedSongs) love.merge(songs[i].albumId, (weights[i] ?: 0.0).coerceAtLeast(0.0), Double::plus)
        for (album in history.starredAlbums) love.merge(album, 2.0, Double::plus)
        val albums = love.entries.sortedByDescending { it.value }.take(30)
        val most = albums.firstOrNull()?.value?.takeIf { it > 0 } ?: return null
        val scores = HashMap<Int, Float>()
        for ((album, l) in albums) {
            for (i in lib.byAlbum[album].orEmpty()) {
                if (i in heard || i in history.starred || !eligibleSong(i)) continue
                scores[i] = (2 * l / most).toFloat() + 0.5f * affinity[i] - tired(i, monday)
            }
        }
        val list = pick(scores, 30, Random(personSeed * 53 + monday.toEpochDay()), pool = 80, maxPerAlbum = 3, maxPerPerson = 10)
        if (list.size < 10) return null
        return mix(
            "deepcuts", "deepcuts", "Deep Cuts", list, refresh = "weekly", subtitle = "From albums you love",
            description = "Songs you've never played from the albums you play the most. Updates every Monday.",
        ).copy(isNew = newWeek(DayOfWeek.MONDAY))
    }

    /** Songs for your taste from albums nobody on this server has played. Changes on Mondays. */
    fun hiddenGems(): MixDto? {
        if (likedSongs.size < 3) return null
        val played = popularity.filterValues { it > 0 }.keys.mapTo(HashSet()) { songs[it].albumId }
        val skip = heard + history.starred + history.playlisted.keys + discoverSongs
        val like = similarTo(likedSongs.take(60), languages = yourLanguages.keys)
        val scores = HashMap<Int, Float>()
        for (i in 0 until n) {
            // Less known on the big service too: a real find, not just something nobody here got to.
            if (songs[i].albumId in played || i in skip || like[i].isNaN() || known(i) > 0.5f) continue
            scores[i] = 0.5f * like[i] + affinity[i] - 0.4f * known(i) - tired(i, monday)
        }
        val list = pickBlend(
            scores, 30, Random(personSeed * 59 + monday.toEpochDay()), pool = 150, blend = yourLanguages,
            exclude = skip, maxPerAlbum = 1, maxPerPerson = 4,
        )
        if (list.size < 15) return null
        return mix(
            "gems", "gems", "Hidden Gems", list, refresh = "weekly", subtitle = "From albums nobody here has played",
            description = "Songs for your taste from albums no one on this server has played yet. Updates every Monday.",
        ).copy(isNew = newWeek(DayOfWeek.MONDAY))
    }

    /** Festivals and seasons: Pongal, rain songs, Deepavali... while it's their time. */
    data class Season(
        val key: String,
        val title: String,
        val subtitle: String,
        /** Words in a song's title that make it this season's (each matches the start of a word: "mazhai" also finds "mazhaiyil"). */
        val words: Set<String>,
        /** Descriptions from the analyzer for its feeling, and how much each counts. */
        val moods: Map<String, Float>,
        val color: String,
        val on: (LocalDate) -> Boolean,
    )

    fun season(season: Season): MixDto? {
        if (!season.on(today)) return null
        val feelings = season.moods.filterKeys { it in lib.moods }
        val scores = HashMap<Int, Float>()
        for (i in 0 until n) {
            if (!eligibleSong(i)) continue
            val title = songs[i].title.lowercase()
            val titleWords = title.split(NOT_LETTERS)
            val named = season.words.count { w -> if (' ' in w) w in title else titleWords.any { it.startsWith(w) } } +
                (lib.labels[i]?.themes?.count { it in SEASON_THEMES[season.key].orEmpty() } ?: 0)
            val feel = if (feelings.isEmpty() || lib.sound[i] == null) Float.NaN
            else feelings.entries.sumOf { (k, w) -> (lib.moods.getValue(k)[i] * w).toDouble() }.toFloat() / feelings.values.sum()
            if (named == 0 && (feel.isNaN() || feel < 1.2f)) continue
            scores[i] = 3f * named + (if (feel.isNaN()) 0f else feel) + 0.4f * affinity[i] - tired(i)
        }
        val random = Random(personSeed * 61 + today.toEpochDay() + season.key.hashCode())
        val list = pickBlend(scores, 40, random, pool = 150, blend = yourLanguages, maxPerAlbum = 2)
        if (list.size < 20) return null
        return mix(
            "season-${season.key}", "season", season.title, list, refresh = "daily", subtitle = season.subtitle, color = season.color,
            description = "${season.subtitle}. Songs named for it first, then ones with its feeling. Updates every day.",
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
        /** What its songs are called in titles ("melodies": "Ilaiyaraaja Melodies", "Tuesday night melodies"); null if it doesn't make one. */
        val noun: String? = null,
    )

    fun mood(mood: Mood): MixDto? {
        val fits = moodMembers(mood) ?: return null
        // The songs that suit you come first (for someone who hasn't played anything yet, it's the same for all).
        val members = if (hasTaste) fits.mapValues { (i, fit) -> fit + 0.5f * affinity[i] } else fits
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
    /**
     * How well song [i] fits [mood] (NaN: it can't be in it). A song's labels (a language model's, from what it
     * knows of the song and its lyrics) decide whether it can: a kuthu at full energy is never Chill, whatever
     * it sounds like to the analyzer. How it sounds then sorts the ones that fit. Songs without labels go by sound.
     */
    private fun moodFit(mood: Mood, i: Int): Float {
        if (!eligibleSong(i)) return Float.NaN
        val heard = soundFit(mood, i)
        val label = lib.labels[i] ?: return heard
        val fit = labelFit(mood, label) ?: return Float.NaN
        return fit + 0.5f * (if (heard.isNaN()) 0f else heard)
    }

    /**
     * What a song's labels say about [mood]: null if it can't be in it, else how well it fits. Moods that are a
     * kind of music (Kuthu, Mass, Devotional...) need the label; broad feelings (Chill, Happy, Sad, Melody,
     * Romance) take songs not named for them only if they sound very much like it.
     */
    private fun labelFit(mood: Mood, label: SongLabel): Float? {
        val calm = mood.energy < 0f || mood.maxEnergy != null
        if (calm && label.energy >= 4) return null
        if (mood.energy >= 1f && label.energy <= 2) return null
        if (mood.key == "focus") return if (label.instrumental) 1.5f else null
        if (mood.key == "retro") return 0f
        if (mood.key in label.moods) return if (label.sure) 2f else 1.3f
        return when {
            mood.key !in BROAD_MOODS -> null
            label.sure -> -0.8f
            else -> -0.3f
        }
    }

    /** How well song [i] fits [mood] by how it sounds (the analyzer's descriptions); NaN if it can't be in it. */
    private fun soundFit(mood: Mood, i: Int): Float {
        if (lib.sound[i] == null) return Float.NaN
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

    private val membersOf = java.util.concurrent.ConcurrentHashMap<String, Map<Int, Float>>()

    /** Songs that clearly have this mood, with how strongly; null if the library wasn't analyzed or has too few. */
    private fun moodMembers(mood: Mood): Map<Int, Float>? =
        membersOf.getOrPut(mood.key) { findMoodMembers(mood) ?: emptyMap() }.takeIf { it.isNotEmpty() }

    private fun findMoodMembers(mood: Mood): Map<Int, Float>? {
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
        // The decade's hits (played most here, and on the big service), the ones that suit you first.
        val scores = members.associateWith { ln(1.0 + (popularity[it] ?: 0)).toFloat() + 0.8f * known(it) + if (hasTaste) 0.7f * affinity[it] else 0f }
        val list = pickBlend(scores, 50, Random(today.toEpochDay() * 7 + start), pool = 250, blend = yourLanguages, scoreOnly = false)
        return mix(
            "decade-$start", "decade", "${decadeName(start)} Mix", list, refresh = "daily", subtitle = namesIn(list), color = DECADE_COLORS[(start / 10) % DECADE_COLORS.size],
            description = "Songs from $start to ${start + 9}, the most played and the ones that suit you first. Updates every day.",
        )
    }

    /**
     * Composers or singers for Home: from the library's 30 biggest (the most songs, and the most played by
     * everyone) and the ones you play most, a different set each day; the ones you play come up most often.
     */
    fun topPeople(composer: Boolean, count: Int = 8): List<Person> {
        val source = if (composer) lib.byComposer else lib.bySinger
        val love = if (composer) composerLove else singerLove
        val biggest = source.entries.filter { it.value.size >= 8 }
            .sortedByDescending { (_, list) -> list.size + 5 * list.sumOf { popularity[it] ?: 0 } }.map { it.key }.take(PEOPLE_POOL)
        val yours = love.entries.filter { (source[it.key]?.size ?: 0) >= 8 }.sortedByDescending { it.value }.map { it.key }.take(5)
        val random = Random(personSeed * 71 + today.toEpochDay() * 2 + if (composer) 1 else 0)
        return (biggest + yours).distinct()
            .map { id -> id to 3.0 * (love[id] ?: 0.0) - 0.05 * biggest.indexOf(id).let { if (it < 0) PEOPLE_POOL else it } + gumbel(random) }
            .sortedByDescending { it.second }.mapNotNull { lib.people[it.first] }.take(count)
    }

    /**
     * Only their songs: the ones that suit you and the most played first, with a fresh selection each day.
     * (Music that sounds like theirs is what their station is for.)
     */
    fun personMix(person: Person, composer: Boolean): MixDto? {
        val theirs = (if (composer) lib.byComposer[person.id] else lib.bySinger[person.id]).orEmpty().filter(::eligibleSong)
        if (theirs.size < 5) return null
        val random = Random(today.toEpochDay() * 5 + person.id.hashCode())
        val scores = theirs.associateWith { ln(1.0 + (popularity[it] ?: 0)).toFloat() + known(it) }
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

    /**
     * Your stations, from what you've played lately: a few songs of this past week (a different few each day,
     * not the ones Because you played has), the composer you've played most lately, Discovery, and the feeling
     * you play most; then the songs you like most. None until you've played something.
     */
    fun yourStations(): List<MixDto> {
        if (!hasTaste && !hasScoreListening) return emptyList()
        val ids = mutableListOf<String>()
        val because = becauseAnchors().toSet()
        history.plays.filter { (i, at) -> at > now - 7 * DAY && i !in because && (eligibleSong(i) || eligibleScore(i)) }
            .groupingBy { it.first }.eachCount().entries.sortedByDescending { it.value }.take(10).map { it.key }
            .shuffled(Random(personSeed * 67 + today.toEpochDay())).distinctBy { songs[it].albumId }.take(3)
            .forEach { ids += "radio-song-${songs[it].id}" }
        val lately = history.plays.filter { it.second > now - 14 * DAY }.mapNotNull { songs[it.first].composer?.id }.groupingBy { it }.eachCount()
        (lately.maxByOrNull { it.value }?.key ?: composerLove.maxByOrNull { it.value }?.key)
            ?.takeIf { (lib.byComposer[it]?.size ?: 0) >= 15 }?.let { ids += "radio-composer-$it" }
        if (hasTaste) {
            if (radioSeed(DISCOVERY) != null) ids += DISCOVERY
            MOODS.mapNotNull { m -> moodMembers(m)?.let { m to moodShare(m) } }.maxByOrNull { it.second }
                ?.takeIf { it.second >= 0.05 }?.let { ids += "radio-mood-${it.first.key}" }
        }
        for (i in liked) {
            if (ids.size >= YOUR_STATIONS) break
            if ((eligibleSong(i) || eligibleScore(i)) && i !in because) ids += "radio-song-${songs[i].id}"
        }
        return ids.distinct().take(YOUR_STATIONS).map(::stationSummary)
    }

    /** Stations for 4 composers and 4 singers, a different set each day (see [topPeople]); none for the people in [skip] (Your stations has them). */
    fun artistStations(skip: Set<String> = emptySet()): List<MixDto> {
        val composers = topPeople(composer = true, count = 12).filter { it.id !in skip }.take(4)
        // Someone who composes and sings (Anirudh) gets one station, not two.
        val singers = topPeople(composer = false, count = 16).filter { s -> composers.none { it.id == s.id } && s.id !in skip }.take(4)
        return composers.map { stationSummary("radio-composer-${it.id}") } + singers.map { stationSummary("radio-singer-${it.id}") }
    }

    private fun stationSummary(id: String): MixDto {
        val (kind, key) = radioSeed(id) ?: error("bad station $id")
        val title: String
        val covers: List<String>
        var color = colorFor(id)
        var description = "Plays endlessly: songs that sound like this, picked as you listen."
        when (kind) {
            "song" -> songs[lib.index.getValue(key)].let { title = "${it.title} Radio"; covers = listOf(it.coverArt) }
            "album" -> songs[lib.byAlbum.getValue(key).first()].let { title = "${it.album} Radio"; covers = listOf(it.coverArt) }
            "mood" -> MOODS.first { it.key == key }.let { m ->
                title = "${m.title.removeSuffix(" Mix")} Radio"
                covers = moodMembers(m).orEmpty().entries.sortedByDescending { it.value + affinity[it.key] }.map { songs[it.key].coverArt }.distinct().take(4)
                color = m.color
                description = "Plays endlessly: ${m.subtitle.lowercase()}, the songs that suit you first."
            }
            "decade" -> key.toInt().let { d ->
                title = "${decadeName(d)} Radio"
                covers = songs.indices.filter { songs[it].year in d until d + 10 }.sortedByDescending { affinity[it] }.map { songs[it].coverArt }.distinct().take(4)
                color = DECADE_COLORS[(d / 10) % DECADE_COLORS.size]
                description = "Plays endlessly: songs from $d to ${d + 9}, the ones that suit you first."
            }
            "discovery" -> {
                title = "Discovery Station"
                covers = likedSongs.take(4).map { songs[it].coverArt }
                description = "Plays endlessly: songs you haven't heard yet, picked for you as you listen."
            }
            else -> lib.people.getValue(key).let { title = "${it.name} Radio"; covers = listOf("ar-${it.id}") }
        }
        val dial = pick(id, DARK_COLOURS)
        // Every station has its own place on the dial, 88.0 to 107.9.
        val frequency = 880 + Math.floorMod(id.hashCode(), 200)
        val art = MixArt(
            "tuner", "JUKEBOX RADIO", title.removeSuffix(" Radio").removeSuffix(" Station"),
            picture = if (kind == "composer" || kind == "singer") "ar-$key" else covers.firstOrNull().takeIf { kind == "song" || kind == "album" },
            ground = dial.first, ink = dial.second, stamp = "${frequency / 10}.${frequency % 10} FM",
        )
        return MixDto(
            id = id, kind = "radio", title = title, subtitle = "Station", endless = true, refresh = "live", description = description,
            covers = covers, round = kind == "composer" || kind == "singer", color = color, updatedAt = now, art = art,
        )
    }

    /**
     * ("song" | "album" | "composer" | "singer" | "mood" | "decade" | "discovery", key) from "radio-song-<id>",
     * "radio-mood-chill", "radio-decade-1990" or [DISCOVERY]; null if there's no such station (or too few songs for it).
     */
    fun radioSeed(id: String): Pair<String, String>? {
        val parts = id.split("-", limit = 3)
        if (parts.size != 3 || parts[0] != "radio") return null
        val (kind, key) = parts[1] to parts[2]
        val known = when (kind) {
            "song" -> key in lib.index
            "album" -> key in lib.byAlbum
            "composer", "singer" -> key in lib.people
            "mood" -> MOODS.firstOrNull { it.key == key }?.let { moodMembers(it) } != null
            "decade" -> key.toIntOrNull()?.let { d -> songs.indices.count { eligibleSong(it) && songs[it].year in d until d + 10 } >= 20 } == true
            "discovery" -> key == "new" && likedSongs.size >= 3
            else -> false
        }
        return if (known) kind to key else null
    }

    /**
     * The next [count] songs of a station, skipping [exclude] (what it already played) and steering away from
     * songs like [skipped] (the ones skipped while it played). The first batch of a song's station starts with that song.
     */
    fun radio(id: String, exclude: Set<String>, count: Int, random: Random, skipped: Collection<String> = emptyList()): MixDto? {
        val (kind, key) = radioSeed(id) ?: return null
        val skip = (exclude + skipped).mapNotNull { lib.index[it] }.toSet()
        val avoid = skippedLike(skipped.mapNotNull { lib.index[it] })
        if (kind == "mood" || kind == "decade" || kind == "discovery") {
            val list = tasteStation(kind, key, skip, count, random, avoid)
            return stationSummary(id).copy(songs = list.map(::song), songCount = list.size)
        }
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
        val scores = similarTo(seeds, language).lessTired(factor = 0.5f)
        for (i in 0 until n) scores[i] += tuning.fame * known(i)
        if (kind == "song") for (i in 0 until n) scores[i] += 0.25f * affinity[i]
        if (avoid != null) for (i in 0 until n) scores[i] -= avoid[i]
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

    /**
     * The next songs of a station about a feeling, a decade, or songs new to you (Discovery): in the languages
     * you listen to (a feeling that belongs to one language stays in it), the ones that suit you first.
     */
    private fun tasteStation(kind: String, key: String, skip: Set<Int>, count: Int, random: Random, avoid: FloatArray?): List<Int> {
        var blend = yourLanguages
        val scores: Map<Int, Float> = when (kind) {
            "mood" -> {
                val mood = MOODS.first { it.key == key }
                when {
                    mood.language != null -> blend = mapOf(mood.language to 1.0)
                    mood.oneLanguage && homeLanguage != null -> blend = mapOf(homeLanguage!! to 1.0)
                }
                moodMembers(mood).orEmpty().mapValues { (i, fit) -> fit + 0.6f * affinity[i] - 0.5f * tired(i) }
            }
            "decade" -> {
                val start = key.toInt()
                songs.indices.filter { eligibleSong(it) && songs[it].year in start until start + 10 }
                    .associateWith { affinity[it] + 0.3f * ln(1.0 + (popularity[it] ?: 0)).toFloat() - 0.5f * tired(it) }
            }
            else -> {
                val like = similarTo(likedSongs.take(60), languages = yourLanguages.keys)
                val known = heard + history.starred + history.playlisted.keys
                (0 until n).filter { it !in known && !like[it].isNaN() }.associateWith { 0.5f * like[it] + affinity[it] - 0.5f * tired(it) }
            }
        }
        val steered = if (avoid == null) scores else scores.mapValues { (i, v) -> v - avoid[i] }
        return pickBlend(steered, count, random, pool = 150, blend = blend, exclude = skip, maxPerAlbum = 2, maxPerPerson = 6)
    }

    /**
     * How much to hold each song back for being like the ones skipped in this station (how they sound, who
     * made and sang them), so the station turns away from them; null if nothing was skipped.
     */
    private fun skippedLike(skipped: List<Int>): FloatArray? {
        if (skipped.isEmpty()) return null
        val like = similarTo(skipped.takeLast(20))
        return FloatArray(n) { i -> (like[i].takeIf { !it.isNaN() } ?: 0f).coerceAtLeast(0f) }
    }

    /** Songs to add to a playlist made of [songIds], best first. [page] > 0 gives the next ones. */
    fun recommend(songIds: List<String>, count: Int, page: Int): List<MixSong> {
        val seeds = songIds.mapNotNull { lib.index[it] }
        if (seeds.isEmpty()) return emptyList()
        val isScorePlaylist = seeds.count { songs[it].score } > seeds.size / 2
        // A playlist can be in more than one language on purpose: suggest in each one it has plenty of.
        val known = seeds.mapNotNull { lib.language[it] }
        val languages = known.groupingBy { it }.eachCount().filter { it.value >= 0.25 * known.size }.keys
        val scores = similarTo(seeds, languages = languages).lessTired(factor = 0.5f)
        for (i in 0 until n) scores[i] += 0.2f * affinity[i] + tuning.fame * known(i)
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
        if (tuning.sound > 0f) centroid(seeds.toList(), null)?.let { c ->
            val sound = zScores(FloatArray(n) { i -> lib.sound[i]?.let { dot(it, c) } ?: Float.NaN })
            for (i in 0 until n) scores[i] += tuning.sound * (if (sound[i].isNaN()) -1f else sound[i])
        }
        if (!together.isEmpty && tuning.together > 0f) {
            val withSeeds = together.with(seeds, n)
            for (i in 0 until n) scores[i] += tuning.together * withSeeds[i]
        }
        if (tuning.links > 0f) {
            val linked = linkedWith(seeds)
            for (i in 0 until n) scores[i] += tuning.links * linked[i]
        }
        val composers = share(seeds) { i -> listOfNotNull(songs[i].composer?.id) }
        val singers = share(seeds) { i -> songs[i].singers.map { it.id } }
        val years = seeds.map { songs[it].year }.filter { it > 1900 }.sorted()
        val year = years.getOrNull(years.size / 2)
        for (i in 0 until n) {
            val s = songs[i]
            scores[i] += tuning.composer * (s.composer?.let { composers[it.id] } ?: 0.0).toFloat()
            scores[i] += tuning.singer * (s.singers.maxOfOrNull { singers[it.id] ?: 0.0 } ?: 0.0).toFloat()
            if (year != null && s.year > 1900) scores[i] += tuning.year * exp(-abs(s.year - year) / 6.0).toFloat()
            if (languages.isNotEmpty()) {
                val l = lib.language[i]
                if (l == null) scores[i] -= 1f else if (l !in languages) scores[i] = Float.NaN
            }
        }
        return scores
    }

    /**
     * How strongly each song is linked to [seeds] by a big service's listeners: a seed's own recommendations
     * (the first ones most), and less, songs that recommend the seed. Many seeds pointing at one song add up,
     * scaled by √seeds so a long playlist doesn't drown everything else.
     */
    private fun linkedWith(seeds: Collection<Int>): FloatArray {
        val out = FloatArray(n)
        for (s in seeds) {
            lib.links[s].forEachIndexed { rank, j -> out[j] += 1f / (1f + 0.15f * rank) }
            lib.linkedBy[s].forEach { (j, rank) -> out[j] += 0.7f / (1f + 0.15f * rank) }
        }
        val scale = 1f / sqrt(seeds.size.toFloat())
        for (i in 0 until n) out[i] *= scale
        return out
    }

    /** How well known song [i] is on the big service (a z-score; 0 if unknown). */
    private fun known(i: Int): Float = lib.fame[i].takeIf { !it.isNaN() } ?: 0f

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

    private fun decadeName(start: Int) = if (start >= 2000) "${start}s" else "${start % 100}s"

    private fun titleCase(words: String) = words.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** Random noise for picking "the best, with some variety" (Gumbel: adding it and taking the top is a weighted draw). */
    private fun gumbel(random: Random) = -ln(-ln(random.nextDouble().coerceIn(1e-12, 1 - 1e-12)))

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
    ): MixDto {
        val pictures = covers ?: list.map { songs[it].albumId }.distinct().take(4).map { "al-$it" }
        return MixDto(
            id = id, kind = kind, title = title, subtitle = subtitle, description = description, refresh = refresh,
            covers = pictures, round = round, color = color, songs = list.map(::song), songCount = list.size,
            art = artFor(id, kind, title, list, pictures),
        )
    }

    // ---------- Covers ----------

    /** The design for a mix's cover (see [MixArt]). */
    private fun artFor(id: String, kind: String, title: String, list: List<Int>, covers: List<String>): MixArt? {
        val rest = id.substringAfter('-', "")
        val first = covers.firstOrNull()
        val dark = pick(id, DARK_COLOURS)
        val bright = pick(id, BRIGHT_COLOURS)
        fun sky(variant: String, kicker: String, headline: String = title) =
            MixArt("sky", kicker, headline, first, "#1d1650", "#fff4ec", variant = variant)
        return when (kind) {
            "daily" -> MixArt("sleeve", "DAILY MIX", rest.padStart(2, '0'), leadPerson(list), dark.first, dark.second)
            "daylist" -> sky(
                SKY_FOR_PART.getValue(dayPart.name),
                "${today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase()} · ${dayPart.name.uppercase()}",
            )
            "discover" -> MixArt(
                "cassette", "WEEK ${monday.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)} · ${list.size} NEW SONGS", title,
                ground = "#ff5a3c", ink = "#fff4ea",
            )
            "fresh", "new" -> MixArt(
                "crate", if (kind == "fresh") "NEW IN THE LIBRARY" else "JUST ADDED", title, ground = "#f3ede2", ink = "#1e1712",
                stamp = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY)).let {
                    "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}".uppercase()
                },
            )
            "repeat" -> MixArt("spin", "", "ON REPEAT", first, "#b3122e", "#ffe7d6")
            "popular" -> MixArt("spin", "", "TOP 50 · MOST PLAYED", first, dark.first, dark.second)
            "score" -> MixArt("spin", "", title.uppercase(), first, dark.first, dark.second)
            "rewind" -> MixArt("polaroid", "◀◀ REW", title, first, "#2c5d63", "#f5f1e6")
            "friends" -> MixArt("photos", "WHAT YOUR FRIENDS PLAY", title, ground = "#2a2140", ink = "#f4efe4")
            "gems" -> MixArt("photos", "NOBODY HERE HAS PLAYED THESE", title, ground = "#24332d", ink = "#f4efe4")
            "because" -> MixArt("single", "BECAUSE YOU PLAYED", title.removePrefix("Because you played "), first, bright.first, bright.second)
            "deepcuts" -> MixArt("paper", "NEVER PLAYED", "DEEP CUTS", first, "#1d1a17", "#3a2210")
            "artistmix", "composer", "singer" -> {
                val person = rest.substringAfterLast('-')
                val name = lib.people[person]?.name ?: title
                MixArt("lp", if (kind == "artistmix") "MIX" else "THIS IS", name.uppercase(), "ar-$person", dark.first, dark.second)
            }
            "topic" -> when (id.substringBefore('-')) {
                "cmood" -> {
                    val person = lib.people[rest.substringBefore('-')]
                    MixArt("diecut", person?.name?.uppercase().orEmpty(), title.removePrefix(person?.name.orEmpty()).trim(), person?.let { "ar-${it.id}" }, bright.first, bright.second)
                }
                "cdecade" -> {
                    val person = lib.people[rest.substringBefore('-')]
                    val start = rest.substringAfter('-').toIntOrNull() ?: 2000
                    decadeArt(start, person?.name?.uppercase().orEmpty(), first, dark)
                }
                else -> {
                    val (c, s) = rest.split('-').let { lib.people[it[0]] to lib.people.getOrNull(it, 1) }
                    MixArt("lp", "TOGETHER", title.uppercase(), c?.let { "ar-${it.id}" }, dark.first, dark.second).also { if (s == null) return it }
                }
            }
            "decade" -> decadeArt(rest.toIntOrNull() ?: 2000, "THE MOST PLAYED", first, dark)
            "season" -> sky(SKY_FOR_SEASON[rest] ?: "dusk", SEASONS.firstOrNull { it.key == rest }?.subtitle?.uppercase().orEmpty())
            "mood" -> {
                val mood = MOODS.firstOrNull { it.key == rest }
                val name = title.removeSuffix(" Mix")
                if (rest in ENERGETIC_MOODS) MixArt("turntable", mood?.subtitle?.uppercase().orEmpty(), name.uppercase(), first, "#171717", "#ffffff")
                else sky(SKY_FOR_MOOD[rest] ?: "dusk", mood?.subtitle?.uppercase().orEmpty(), name)
            }
            else -> null
        }
    }

    private fun decadeArt(start: Int, kicker: String, cover: String?, colours: Pair<String, String>) =
        MixArt(if (start < 1990) "reel" else "cd", kicker, decadeName(start), cover, colours.first, colours.second)

    /** The person most of [list] is by (its composer), for a mix's photo. */
    private fun leadPerson(list: List<Int>): String? =
        list.mapNotNull { songs[it].composer?.id }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.let { "ar-$it" }

    private fun <T> Map<String, T>.getOrNull(parts: List<String>, i: Int) = parts.getOrNull(i)?.let { this[it] }

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
        val PERSONAL_KINDS = setOf(
            "daily", "daylist", "discover", "fresh", "repeat", "rewind", "friends", "because", "deepcuts", "gems", "artistmix", "topic", "season",
        )

        /** Colour pairs for covers: a dark ground with a bright colour on it, and a bright ground with a dark one. */
        val DARK_COLOURS = listOf(
            "#1d1147" to "#ff8a3d", "#06303a" to "#6ee7c8", "#3b0a24" to "#ff5d8f", "#2a0f3d" to "#ffd166", "#0f3b3a" to "#ff9f1c",
            "#12333a" to "#7be0d0", "#3a1c12" to "#ffb38a", "#2b1840" to "#d7b3ff", "#0f2a1d" to "#9fe870", "#13213f" to "#8fb8ff",
            "#4b2bd1" to "#e6ddff", "#1b2a4a" to "#ffc857", "#3d1520" to "#ffb4a2", "#102a43" to "#9ae6ff",
        )
        val BRIGHT_COLOURS = listOf(
            "#e9b44c" to "#2a1a02", "#f05d3b" to "#2a0700", "#ff7a45" to "#2b0d00", "#7be0d0" to "#0b2a2e", "#ffd166" to "#2a1a00",
            "#9fe870" to "#12300a", "#ff8fab" to "#3a0a1c", "#8fb8ff" to "#0c1a3a", "#d7b3ff" to "#2a1240",
        )
        private fun <T> pick(id: String, from: List<T>) = from[Math.floorMod(id.hashCode() * 31 + 7, from.size)]
        private val SKY_FOR_PART = mapOf("morning" to "dawn", "afternoon" to "day", "evening" to "dusk", "night" to "night")
        private val SKY_FOR_SEASON = mapOf("rain" to "rain", "pongal" to "dawn", "valentine" to "dusk", "deepavali" to "night", "christmas" to "night", "newyear" to "night")
        private val SKY_FOR_MOOD = mapOf(
            "chill" to "night", "romance" to "dusk", "happy" to "dawn", "sad" to "rain", "melody" to "dusk", "devotional" to "dawn",
            "classical" to "day", "focus" to "day", "sleep" to "night", "retro" to "dusk",
        )
        private val ENERGETIC_MOODS = setOf("party", "kuthu", "mass", "workout")

        /** Feelings rather than kinds of music: a song not labelled with one can still be in it if it sounds the part. */
        private val BROAD_MOODS = setOf("chill", "happy", "sad", "melody", "romance")

        /** Themes (song labels) that belong to a season. */
        private val SEASON_THEMES = mapOf(
            "rain" to setOf("rain"), "valentine" to setOf("love"), "deepavali" to setOf("festival", "celebration"),
            "pongal" to setOf("festival", "village"), "christmas" to setOf("devotion", "festival"), "newyear" to setOf("celebration", "dance"),
        )

        /** The Discovery station: endless songs new to you. */
        const val DISCOVERY = "radio-discovery-new"

        /** How many of the library's biggest composers (or singers) Home's rows rotate through, and how many stations Your stations has. */
        private const val PEOPLE_POOL = 30
        private const val YOUR_STATIONS = 7

        /** The sort of mix an id is, for statistics: "daily-3" and "daily-1" are both "Daily Mixes". */
        fun family(mixId: String): String = when (mixId.substringBefore('-')) {
            "daily" -> "Daily Mixes"
            "daylist" -> "Time of day"
            "discover" -> "Discover Weekly"
            "fresh" -> "Fresh for You"
            "repeat" -> "On Repeat"
            "rewind" -> "Rewind"
            "friends" -> "Friends Mix"
            "because" -> "Because you played"
            "deepcuts" -> "Deep Cuts"
            "gems" -> "Hidden Gems"
            "amix" -> "Composer and singer mixes"
            "cmood", "cdecade", "pair" -> "Composer melodies, decades, pairs"
            "season" -> "Seasons"
            "mood" -> "Moods"
            "decade" -> "Decades"
            "score" -> "Background scores"
            "composer", "singer" -> "This Is"
            "popular" -> "Top 50"
            "new" -> "New Arrivals"
            "radio" -> "Stations"
            else -> "Other"
        }

        /** The most a song's score drops for being passed over again and again. */
        const val MAX_FATIGUE = 3f

        /** How many mixes Your top mixes shows, and at most how many of each sort. */
        const val TOP_MIXES = 10
        val TOP_MIX_CAPS = mapOf("season" to 2, "because" to 2, "artist" to 2, "topic" to 3, "mood" to 2, "decade" to 1, "score" to 2)

        private val NOT_LETTERS = Regex("""[^\p{L}]+""")

        /** A part of the day, from [from] o'clock, and the feelings its daylist can have. */
        class DayPart(val name: String, val from: Int, val vibes: List<String>)

        val DAY_PARTS = listOf(
            DayPart("morning", 5, listOf("devotional", "melody", "happy", "chill")),
            DayPart("afternoon", 12, listOf("happy", "party", "melody", "romance")),
            DayPart("evening", 17, listOf("romance", "melody", "happy", "classical")),
            DayPart("night", 21, listOf("chill", "sad", "romance", "melody")),
        )

        fun partOf(hour: Int): DayPart = DAY_PARTS.lastOrNull { hour >= it.from } ?: DAY_PARTS.last()

        private fun between(day: LocalDate, from: MonthDay, to: MonthDay) = MonthDay.from(day).let { !it.isBefore(from) && !it.isAfter(to) }

        /** Deepavali's date, which moves with the moon. */
        private val DEEPAVALI = listOf("2026-11-08", "2027-10-29", "2028-10-17", "2029-11-05", "2030-10-26").map(LocalDate::parse)

        val SEASONS = listOf(
            Season(
                "pongal", "Pongal Kondattam", "Harvest festival songs", setOf("pongal", "pongalo", "jallikattu", "sankranti", "sankranthi", "bhogi", "kummi"),
                mapOf("kuthu" to 1f, "happy" to 0.5f), "#C0561B",
            ) { between(it, MonthDay.of(1, 10), MonthDay.of(1, 17)) },
            Season(
                "valentine", "Kadhal Week", "Love songs for Valentine's week", setOf("kadhal", "kaadhal", "prema", "pyaar", "ishq", "love"),
                mapOf("romantic" to 1f, "melody" to 0.4f), "#B03A5B",
            ) { between(it, MonthDay.of(2, 8), MonthDay.of(2, 14)) },
            Season(
                "rain", "Rain Songs", "For grey skies and the smell of rain",
                setOf("mazhai", "mazha", "megham", "megam", "megame", "meghame", "thooral", "saaral", "rain", "monsoon", "varsham", "vaana", "baarish", "barish", "barsaat", "sawan", "saawan"),
                mapOf("melody" to 0.6f, "chill" to 0.4f, "romantic" to 0.4f), "#3B6E8F",
            ) { between(it, MonthDay.of(6, 15), MonthDay.of(8, 31)) || between(it, MonthDay.of(10, 15), MonthDay.of(12, 10)) },
            Season(
                "deepavali", "Deepavali Celebration", "Festival songs for Deepavali", setOf("deepavali", "diwali", "deepam", "pattasu", "pataka", "patakha"),
                mapOf("happy" to 0.8f, "party" to 0.6f), "#E0A21B",
            ) { day -> DEEPAVALI.any { !day.isBefore(it.minusDays(7)) && !day.isAfter(it.plusDays(1)) } },
            Season(
                "christmas", "Christmas Songs", "Carols and Christmas cheer", setOf("christmas", "xmas", "jingle", "santa", "carol", "bethlehem", "yesu", "jesus", "merry"),
                mapOf("devotional" to 0.6f, "happy" to 0.4f), "#1E7F4E",
            ) { between(it, MonthDay.of(12, 15), MonthDay.of(12, 26)) },
            Season(
                "newyear", "New Year Party", "Songs to ring in the new year", setOf("new year", "newyear", "happy new"),
                mapOf("party" to 1f), "#D2462D",
            ) { between(it, MonthDay.of(12, 27), MonthDay.of(12, 31)) || between(it, MonthDay.of(1, 1), MonthDay.of(1, 2)) },
        )

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
                maxEnergy = 0.3f, maxRhythm = 0.3f, vetoes = mapOf("party" to 1.0f), group = "calm", noun = "chill",
            ),
            Mood("romance", "Romance Mix", "Love songs and duets", mapOf("romantic" to 1f, "melody" to 0.4f), color = "#B03A5B", noun = "love songs"),
            Mood("happy", "Feel Good Mix", "Bright, happy songs", mapOf("happy" to 1f), energy = 0.3f, color = "#E0A21B", noun = "feel-good songs"),
            Mood("party", "Party Mix", "Loud, fast, made for dancing", mapOf("party" to 1f, "kuthu" to 0.5f), energy = 1f, color = "#D2462D", noun = "party songs"),
            Mood(
                "kuthu", "Kuthu Mix", "Folk beats and thappu drums", mapOf("kuthu" to 1f), energy = 0.5f, color = "#C0561B", language = "Tamil",
                vetoes = mapOf("melody" to 1.0f, "sad" to 1.0f), indianSound = true, noun = "kuthu songs",
            ),
            Mood(
                "mass", "Kollywood Mass", "Hero intros and mass beats", mapOf("heroic" to 1f, "kuthu" to 0.5f, "party" to 0.3f), energy = 1f,
                color = "#A3271F", language = "Tamil", vetoes = mapOf("melody" to 1.2f, "sad" to 1.0f), indianSound = true, noun = "mass songs",
            ),
            Mood("sad", "Sad Songs", "For the heavy-hearted", mapOf("sad" to 1f), energy = -0.3f, color = "#4A5A7A", noun = "sad songs"),
            Mood("melody", "Melody Mix", "Soft film melodies", mapOf("melody" to 1f), color = "#5B7F4A", oneLanguage = true, noun = "melodies"),
            Mood("workout", "Workout Mix", "Big energy to keep you moving", mapOf("heroic" to 1f, "party" to 0.5f), energy = 1f, color = "#8C2F39"),
            Mood("devotional", "Devotional", "Bhakti songs and hymns", mapOf("devotional" to 1f), color = "#B5651D", oneLanguage = true, noun = "devotional songs"),
            Mood("classical", "Carnatic Touch", "Songs with a classical soul", mapOf("classical" to 1f), color = "#6D4C8F", oneLanguage = true, noun = "classical songs"),
            Mood(
                "focus", "Focus", "Mostly instrumental, easy to work to", mapOf("instrumental" to 1f, "chill" to 0.4f), energy = -0.5f, color = "#2F6B6B",
                maxRhythm = 0.5f, requires = mapOf("instrumental" to 1.5f), vetoes = mapOf("party" to 1.5f), group = "calm",
            ),
            Mood(
                "sleep", "Sleep", "Lullabies and quiet songs", mapOf("lullaby" to 1f, "chill" to 0.7f), energy = -1f, color = "#28335C",
                maxEnergy = -0.2f, maxRhythm = -0.3f, vetoes = mapOf("party" to 0.5f), group = "calm", noun = "lullabies",
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
