package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.Fuzzy
import io.github.devasenan134.jukebox.server.query
import io.github.devasenan134.jukebox.server.queryOne
import io.github.devasenan134.jukebox.server.update
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection

private val log = LoggerFactory.getLogger("jukebox.people")

/**
 * When two spellings name the same person. Tuned on the real library (2026-10-03), where wrong merges are
 * worse than missed ones:
 *
 * - The initials must match ("S. Balasubramanian" is never "S.P. Balasubrahmanyam"), and so must the number
 *   of full words.
 * - Full words are compared by how they sound (the search's spelling folding: th/t, ai/e, y/i…). With two or
 *   more initials and a long word the bar is lower, since a coincidence is unlikely.
 * - One bare word (no initials) needs much more: a rare spelling (under a fifth of the main one's credits),
 *   the same first and last letter, and one small typo at most (a vowel changed, a letter added or dropped,
 *   never one consonant for another). Hariharan and Haricharan stay two people.
 */
object PersonNames {
    data class Parts(val initials: String, val words: List<String>)

    fun parts(name: String): Parts {
        val tokens = name.lowercase().split(Regex("""[\s.\-_]+""")).filter { it.isNotEmpty() }
        return Parts(tokens.filter { it.length == 1 }.joinToString(""), tokens.filter { it.length > 1 }.map(Fuzzy::sound))
    }

    fun same(main: String, mainCredits: Int, other: String, otherCredits: Int): Boolean {
        val a = parts(main)
        val b = parts(other)
        if (a.initials != b.initials || a.words.size != b.words.size || a.words.isEmpty() || a.words.maxOf { it.length } < 4) return false
        if (a.words.size == 1 && a.initials.isEmpty()) {
            val x = main.trim().lowercase()
            val y = other.trim().lowercase()
            if (x.first() != y.first() || x.last() != y.last()) return false
            if (otherCredits > maxOf(3.0, 0.2 * mainCredits)) return false
            return smallTypo(a.words[0], b.words[0])
        }
        val bar = if (a.initials.length >= 2 && a.words.maxOf { it.length } >= 8) 0.72 else 0.82
        return a.words.zip(b.words).all { (x, y) -> similarity(x, y) >= bar }
    }

    private const val VOWELS = "aeiou"

    /** At most one edit: a vowel for a vowel, or a letter added or dropped. */
    fun smallTypo(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        if (a.length == b.length) {
            val diff = a.indices.filter { a[it] != b[it] }
            return diff.size == 1 && a[diff[0]] in VOWELS && b[diff[0]] in VOWELS
        }
        val (long, short) = if (a.length > b.length) a to b else b to a
        return long.indices.any { long.removeRange(it, it + 1) == short }
    }

    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur.also { cur = prev }
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }
}

@Serializable
data class PeopleReport(val people: Int, val merged: Int, val forced: Int, val keptApart: Int)

/**
 * Folds spellings of one person into the main spelling (the one with the most credits) after each scan.
 * Each person is compared with main spellings only, never with another variant, so A ≈ B ≈ C can't chain
 * two different people together.
 *
 * [overrides] (optional, one per line) corrects it by hand:
 *   same: S.P.B = S.P. Balasubrahmanyam
 *   different: Hariharan != Haricharan
 */
class PeopleMerger(private val db: Db, private val overrides: File? = null) {
    private class Person(val id: String, val name: String, val credits: Int, val roles: Set<String>)

    suspend fun run(): PeopleReport = db.tx {
        val (same, different) = readOverrides()
        // A "different" line undoes an earlier merge: the spelling stands alone again, and the songs credited
        // to the main spelling are read again on the next scan, so each name gets its own credits back.
        for ((x, y) in different) {
            val pair = query("SELECT a.id, b.id FROM people a JOIN people b ON b.merged_into = a.id WHERE (lower(a.name) = ? AND lower(b.name) = ?) OR (lower(a.name) = ? AND lower(b.name) = ?)",
                x, y, y, x) { it.getString(1) to it.getString(2) }
            for ((main, variant) in pair) {
                update("UPDATE people SET merged_into = NULL WHERE id = ?", variant)
                update("""UPDATE files SET mtime = -1 WHERE recording_id IN (SELECT recording_id FROM recording_credits WHERE person_id = ?)
                          OR track_id IN (SELECT t.id FROM tracks t JOIN releases rl ON rl.id = t.release_id
                                          JOIN album_credits c ON c.album_id = rl.album_id WHERE c.person_id = ?)""", main, main)
                log.info("Unmerged {} from {} (overrides file); their songs are read again on the next scan", variant, main)
            }
        }
        val people = query(
            """SELECT p.id, p.name,
                      (SELECT count(*) FROM recording_credits c WHERE c.person_id = p.id) + (SELECT count(*) FROM album_credits c WHERE c.person_id = p.id),
                      (SELECT group_concat(DISTINCT role) FROM (SELECT role FROM recording_credits WHERE person_id = p.id
                                                                UNION SELECT role FROM album_credits WHERE person_id = p.id))
                 FROM people p WHERE p.merged_into IS NULL""",
        ) { Person(it.getString(1), it.getString(2), it.getInt(3), it.getString(4).orEmpty().split(',').filter(String::isNotEmpty).toSet()) }
        val byKey = people.associateBy { Names.personKey(it.name) }
        fun apart(a: Person, b: Person) = different.any { (x, y) -> (x == a.name.lowercase() && y == b.name.lowercase()) || (x == b.name.lowercase() && y == a.name.lowercase()) }

        var merged = 0
        var forced = 0
        var keptApart = 0
        val gone = HashSet<String>()
        // By hand first.
        for ((x, y) in same) {
            val a = people.firstOrNull { it.name.lowercase() == x } ?: byKey[Names.personKey(x)] ?: continue
            val b = people.firstOrNull { it.name.lowercase() == y } ?: byKey[Names.personKey(y)] ?: continue
            if (a.id == b.id || a.id in gone || b.id in gone) continue
            val (main, other) = if (a.credits >= b.credits) a to b else b to a
            merge(other.id, main.id)
            gone += other.id
            forced++
        }
        // Then by rule: most-credited first; each person joins the first main spelling it matches.
        val mains = HashMap<String, MutableList<Person>>()
        for (p in people.sortedByDescending { it.credits }) {
            if (p.id in gone) continue
            val parts = PersonNames.parts(p.name)
            val key = if (parts.words.isEmpty()) null else "${parts.initials}|${parts.words.size}|${parts.words.maxBy { it.length }.take(2)}"
            val match = key?.let { k ->
                mains[k]?.firstOrNull { m ->
                    (m.roles intersect p.roles).isNotEmpty() && PersonNames.same(m.name, m.credits, p.name, p.credits) &&
                        (!apart(m, p) || false.also { keptApart++ })
                }
            }
            if (match != null) {
                merge(p.id, match.id)
                gone += p.id
                merged++
            } else if (key != null) mains.getOrPut(key) { mutableListOf() } += p
        }
        PeopleReport(people.size - gone.size, merged, forced, keptApart).also { if (merged + forced > 0) log.info("People: {}", it) }
    }

    /** Moves [from]'s credits to [into]; [from] stays as a pointer, its name kept as an alias. */
    private fun Connection.merge(from: String, into: String) {
        for (table in listOf("recording_credits", "album_credits")) {
            update("UPDATE OR IGNORE $table SET person_id = ? WHERE person_id = ?", into, from)
            update("DELETE FROM $table WHERE person_id = ?", from)
        }
        val names = (query("SELECT name, coalesce(aliases, '') FROM people WHERE id IN (?, ?)", from, into) { listOf(it.getString(1)) + it.getString(2).split('\n') }
            .flatten().filter(String::isNotBlank) - (queryOne("SELECT name FROM people WHERE id = ?", into) { it.getString(1) } ?: "")).distinct()
        update("UPDATE people SET aliases = ? WHERE id = ?", names.joinToString("\n").ifEmpty { null }, into)
        update("UPDATE people SET merged_into = ? WHERE id = ?", into, from)
        update("UPDATE people SET merged_into = ? WHERE merged_into = ?", into, from)
    }

    private fun readOverrides(): Pair<List<Pair<String, String>>, List<Pair<String, String>>> {
        val lines = overrides?.takeIf { it.isFile }?.readLines().orEmpty().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        fun pairs(prefix: String, sep: String) = lines.filter { it.startsWith(prefix, ignoreCase = true) }
            .mapNotNull { l -> l.substringAfter(':').split(sep).map { it.trim().lowercase() }.takeIf { it.size == 2 && it.all(String::isNotEmpty) }?.let { it[0] to it[1] } }
        return pairs("same", "=") to pairs("different", "!=")
    }
}
