package io.github.devasenan134.jukebox.server

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Which songs people put together: played close together in one sitting, or in the same playlist.
 * It's what streaming services lean on most ("people who play this also play that"): it knows what
 * belongs together in a way the sound alone can't, like a Deva kuthu leading to other Gana songs
 * rather than to any loud dance track.
 *
 * [neighbours] maps a song to the songs most often put with it, each with a strength from 0 to 1.
 */
class Together(val neighbours: Map<Int, List<Pair<Int, Float>>>) {
    /** How much each song goes with [seeds] (0 to 1), from how often people put them together. */
    fun with(seeds: Collection<Int>, n: Int): FloatArray {
        val scores = FloatArray(n)
        if (seeds.isEmpty()) return scores
        for (s in seeds) neighbours[s]?.forEach { (j, w) -> if (j < n) scores[j] += w }
        for (j in 0 until n) scores[j] = (scores[j] / seeds.size).coerceAtMost(1f)
        return scores
    }

    val isEmpty get() = neighbours.isEmpty()

    /** A song played, by position in the library snapshot. */
    data class Play(val user: Long, val song: Int, val at: Long)

    companion object {
        val EMPTY = Together(emptyMap())

        /** A new sitting starts after this long without playing anything. */
        private const val SESSION_GAP_MS = 30 * 60 * 1000L
        /** Songs this close in a sitting count as played together. */
        private const val WINDOW = 3
        /** Songs kept per song. */
        private const val KEEP = 50

        /**
         * From [plays] (only ones not skipped, and not started by a mix: mixes shouldn't teach themselves)
         * and [playlists] (lists of songs). Big playlists count less per pair than small, careful ones.
         */
        fun build(plays: List<Play>, playlists: List<List<Int>>): Together {
            val pairs = mutableMapOf<Long, Float>()
            val totals = mutableMapOf<Int, Float>()
            fun add(a: Int, b: Int, w: Float) {
                if (a == b) return
                val key = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
                pairs.merge(key, w, Float::plus)
                totals.merge(a, w, Float::plus)
                totals.merge(b, w, Float::plus)
            }
            for ((_, userPlays) in plays.groupBy { it.user }) {
                val sorted = userPlays.sortedBy { it.at }
                var session = mutableListOf<Int>()
                var last = Long.MIN_VALUE
                fun close() {
                    for (i in session.indices) for (j in i + 1..minOf(i + WINDOW, session.size - 1)) add(session[i], session[j], 1f)
                    session = mutableListOf()
                }
                for (p in sorted) {
                    if (p.at - last > SESSION_GAP_MS) close()
                    if (session.lastOrNull() != p.song) session += p.song // a song on repeat isn't a pair
                    last = p.at
                }
                close()
            }
            for (list in playlists) {
                val songs = list.distinct().take(300)
                if (songs.size < 2) continue
                val w = (1.0 / ln(2.0 + songs.size)).toFloat()
                for (i in songs.indices) for (j in i + 1 until songs.size) add(songs[i], songs[j], w)
            }
            // Strength: how often together, compared with how often each is played at all (so a hugely
            // popular song isn't "together" with everything).
            val neighbours = mutableMapOf<Int, MutableList<Pair<Int, Float>>>()
            for ((key, c) in pairs) {
                val a = (key shr 32).toInt()
                val b = (key and 0xffffffffL).toInt()
                val strength = (c / sqrt(totals.getValue(a) * totals.getValue(b))).coerceAtMost(1f)
                neighbours.getOrPut(a) { mutableListOf() } += b to strength
                neighbours.getOrPut(b) { mutableListOf() } += a to strength
            }
            return Together(neighbours.mapValues { (_, list) -> list.sortedByDescending { it.second }.take(KEEP) })
        }
    }
}
