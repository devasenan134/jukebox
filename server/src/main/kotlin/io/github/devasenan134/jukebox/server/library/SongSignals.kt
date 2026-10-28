package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.query
import io.github.devasenan134.jukebox.server.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * What a big music service knows about our songs, which one small server can't learn from its own listening:
 * which songs its listeners play together, and how often each is played. From JioSaavn's recommendations
 * (reco.getreco, saved per song as saavn_reco.jsonl while the library was put together), matched to the
 * library by JioSaavn id. Imported again whenever the file is refreshed; mixes read it (JukeboxLibrary).
 */
class SongSignals(private val db: Db) {

    /** Replaces what an earlier import of JioSaavn's data stored. Lines: {"saavn_id", "ok", "reco": [{"id", "play_count"}...]}. */
    suspend fun importSaavn(file: File): String {
        val json = Json { ignoreUnknownKeys = true }
        val bySaavn = db.read {
            // A recording merged into another counts as the one it became.
            query("SELECT saavn_id, coalesce(merged_into, id) FROM recordings WHERE saavn_id IS NOT NULL AND saavn_id != ''") {
                it.getString(1) to it.getString(2)
            }.toMap()
        }
        val links = mutableListOf<Triple<String, String, Int>>()
        val fame = HashMap<String, Long>()
        var lines = 0
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            lines++
            val row = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEachLine
            if (row["ok"]?.jsonPrimitive?.booleanOrNull == false) return@forEachLine
            val from = row["saavn_id"]?.jsonPrimitive?.contentOrNull?.let(bySaavn::get) ?: return@forEachLine
            var rank = 0
            for (item in row["reco"]?.jsonArray.orEmpty()) {
                val other = item as? JsonObject ?: continue
                val id = other["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val plays = other["play_count"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val to = bySaavn[id]
                // Play counts are given for the recommended songs; a song recommended often keeps its highest.
                if (to != null && plays != null) fame.merge(to, plays, ::maxOf)
                if (to != null && to != from) links += Triple(from, to, rank)
                rank++
            }
        }
        db.tx {
            update("DELETE FROM song_links WHERE source = 'saavn'")
            update("DELETE FROM song_fame WHERE source = 'saavn'")
            prepareStatement("INSERT OR IGNORE INTO song_links (recording_id, other_id, rank, source) VALUES (?, ?, ?, 'saavn')").use { st ->
                for ((from, to, rank) in links) { st.setString(1, from); st.setString(2, to); st.setInt(3, rank); st.addBatch() }
                st.executeBatch()
            }
            prepareStatement("INSERT OR REPLACE INTO song_fame (recording_id, plays, source) VALUES (?, ?, 'saavn')").use { st ->
                for ((id, plays) in fame) { st.setString(1, id); st.setLong(2, plays); st.addBatch() }
                st.executeBatch()
            }
        }
        db.catalogChanged()
        return "Read $lines songs from ${file.name}: ${links.size} links between library songs, play counts for ${fame.size} songs"
    }
}
