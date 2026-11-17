package io.github.devasenan134.jukebox.server

import io.github.devasenan134.jukebox.server.library.Listening
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** A liked playlist follows what its owner does with it. */
class PlaylistLikesTest {
    @Test
    fun `a deleted playlist leaves the libraries of people who liked it, a private one is hidden`(): Unit = runBlocking {
        val db = Db(File.createTempFile("jukebox-likes", ".db").apply { delete(); deleteOnExit() }.path)
        db.tx { listOf(1L to "owner", 2L to "fan").forEach { (id, name) -> update("INSERT INTO users (id, username, display_name, created_at) VALUES (?, ?, ?, 0)", id, name, name) } }
        val listening = Listening(db)
        val likes = PlaylistLikes(db)
        val kept = listening.create(1, "Road trip", emptyList())
        val gone = listening.create(1, "Rainy day", emptyList())
        listening.update(1, kept.id, null, null, true, emptyList(), emptyList())
        listening.update(1, gone.id, null, null, true, emptyList(), emptyList())
        likes.like(2, PlaylistRef(kept.id, "Road trip"))
        likes.like(2, PlaylistRef(gone.id, "Rainy day"))
        // Liked before this fix and deleted then: nothing removed the like.
        db.tx { update("INSERT INTO liked_playlists (user_id, playlist_id, playlist_json, liked_at) VALUES (2, 'deleted-long-ago', '{\"id\":\"deleted-long-ago\"}', 0)") }

        listening.delete(1, gone.id)
        assertEquals(listOf("Road trip"), likes.list(2).map { it.name })
        assertEquals(1, db.tx { queryOne("SELECT count(*) FROM liked_playlists") { it.getInt(1) } })

        listening.update(1, kept.id, null, null, false, emptyList(), emptyList())
        assertEquals(emptyList(), likes.list(2)) // private again: hidden, not forgotten
        listening.update(1, kept.id, null, null, true, emptyList(), emptyList())
        assertEquals(listOf("Road trip"), likes.list(2).map { it.name })
    }
}
