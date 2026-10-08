package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.insert
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TranscoderTest {

    @Test
    fun `transcoder creates opus mobile copy and retrieves it`() = runBlocking {
        val dir = File.createTempFile("transcoder-test", "").apply { delete(); mkdirs(); deleteOnExit() }
        val musicDir = File(dir, "music").apply { mkdirs() }
        val transcodeDir = File(dir, "transcoded")
        val dbFile = File(dir, "jukebox.db").path
        val db = Db(dbFile)

        val audioFile = TestAudio.song(
            musicDir, "song.m4a", hz = 440,
            tags = mapOf("title" to "Test Song", "artist" to "Singer", "album" to "Album"),
            seconds = 2
        )

        db.tx {
            insert("INSERT INTO libraries (id, name, path, kind, language) VALUES (1, 'tamil', ?, 'film', 'tamil')", musicDir.path)
            insert("INSERT INTO recordings (id, title, title_key, version, duration_ms, created_at) VALUES ('rec-test-1', 'Test Song', 'testsong', 'original', 2000, 1000)")
            insert("INSERT INTO files (library_id, path, size, mtime, recording_id, format, duration_ms, scanned_at) VALUES (1, 'song.m4a', ?, 1000, 'rec-test-1', 'm4a', 2000, 1000)", audioFile.length())
        }

        val transcoder = Transcoder(
            db = db,
            audioTools = AudioTools(),
            transcodeDir = transcodeDir,
            roots = { mapOf(1L to musicDir) },
        )

        // 1. Initial find is null
        assertEquals(null, transcoder.find("rec-test-1"))

        // 2. Transcode one creates the opus file
        val opus = transcoder.transcodeOne("rec-test-1")
        assertNotNull(opus)
        assertTrue(opus.isFile)
        assertTrue(opus.length() > 0)
        assertEquals("rec-test-1.opus", opus.name)

        // 3. Find returns the created file
        val found = transcoder.find("rec-test-1")
        assertEquals(opus.path, found?.path)

        // 4. Batch processing recognizes existing file and skips it
        val batchDone = transcoder.processBatch()
        assertEquals(0, batchDone)
    }
}
