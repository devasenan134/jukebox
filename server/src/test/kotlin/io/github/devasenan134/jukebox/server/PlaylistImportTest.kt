package io.github.devasenan134.jukebox.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistFilesTest {
    @Test
    fun `an Exportify CSV with quotes, ISRCs and lengths`() {
        val csv = """
            "Track URI","Track Name","Artist Name(s)","Album Name","Duration (ms)","ISRC"
            "spotify:track:1","Naa Ready (From ""Leo"")","Anirudh Ravichander, Thalapathy Vijay","Naa Ready","245000","INS181300123"
            "spotify:track:2","Kanave Kanave","Anirudh Ravichander","David","284000",""
        """.trimIndent()
        val p = PlaylistFiles.parse(csv, "Mine")
        assertEquals(2, p.tracks.size)
        assertEquals(ImportedTrack("Naa Ready (From \"Leo\")", listOf("Anirudh Ravichander, Thalapathy Vijay"), "Naa Ready", 245_000, "INS181300123"), p.tracks[0])
        assertNull(p.tracks[1].isrc)
        assertEquals("Mine", p.name)
    }

    @Test
    fun `M3U with lengths, and plain lines`() {
        val m3u = "#EXTM3U\n#EXTINF:240,Anirudh - Kanave Kanave\n/music/kanave.mp3\n#EXTINF:-1,Roja\nroja.mp3\n"
        val tracks = PlaylistFiles.parse(m3u, "x").tracks
        assertEquals(2, tracks.size)
        assertEquals(240_000, tracks[0].durationMs)
        assertEquals(listOf("Kanave Kanave", "Anirudh"), tracks[0].artists)
        assertEquals("Roja", tracks[1].title)

        val lines = PlaylistFiles.parse("1. Kanave Kanave - Anirudh\n\n• Chinna Chinna Aasai\n", "x").tracks
        assertEquals(2, lines.size)
        assertEquals("Chinna Chinna Aasai", lines[1].title)
    }

    @Test
    fun `an Apple Music playlist export keeps the playlist's order`() {
        val xml = """<?xml version="1.0"?><plist version="1.0"><dict><key>Tracks</key><dict>
            <key>11</key><dict><key>Track ID</key><integer>11</integer><key>Name</key><string>Roja &amp; Co</string><key>Artist</key><string>Minmini</string><key>Album</key><string>Roja</string><key>Total Time</key><integer>300000</integer></dict>
            <key>22</key><dict><key>Track ID</key><integer>22</integer><key>Name</key><string>Kaariga</string><key>Artist</key><string>Chinmayi</string></dict>
            </dict><key>Playlists</key><array><dict><key>Name</key><string>Car songs</string><key>Description</key><string></string><key>Playlist Items</key><array>
            <dict><key>Track ID</key><integer>22</integer></dict><dict><key>Track ID</key><integer>11</integer></dict></array></dict></array></dict></plist>"""
        val p = PlaylistFiles.parse(xml, "x")
        assertEquals("Car songs", p.name)
        assertEquals(listOf("Kaariga", "Roja & Co"), p.tracks.map { it.title })
        assertEquals(300_000, p.tracks[1].durationMs)
    }
}

class ImportMatcherTest {
    private fun song(id: String, title: String, album: String, singer: String, seconds: Int, composer: String = "Composer") =
        LibrarySong(id, title, album, "a-$album", singer, listOf(Person("p-$singer", singer)), Person("c-$composer", composer), 2020, seconds, "Tamil", 0, false)

    private val songs = listOf(
        song("s1", "Kanave Kanave", "David", "Anirudh", 284, "Anirudh"),
        song("s2", "Naa Ready", "Leo", "Vijay", 245, "Anirudh"),
        song("s3", "Kadhal", "Old Movie", "S. P. Balasubrahmanyam", 300),
        song("s4", "Kadhal", "New Movie", "Sid Sriram", 200),
    )
    private val matcher = ImportMatcher(LibrarySnapshot(songs, arrayOfNulls(songs.size), emptyMap(), emptyMap(), FloatArray(songs.size), FloatArray(songs.size), "v1"))

    @Test
    fun `titles clean up and name their movie`() {
        assertEquals("Naa Ready" to "Leo", ImportMatcher.cleanTitle("Naa Ready (From \"Leo\")"))
        assertEquals("Kanave Kanave" to null, ImportMatcher.cleanTitle("Kanave Kanave (feat. Someone) - Remastered 2011"))
        assertEquals(listOf("A", "B", "C", "D"), ImportMatcher.splitArtists("A, B & C feat. D"))
    }

    @Test
    fun `a song is found by its title, spelled differently`() {
        val row = matcher.match(ImportedTrack("Kanavae Kanavae", listOf("Anirudh Ravichander"), durationMs = 284_500))
        assertEquals("s1", row.match?.id)
        assertTrue(row.sure)
    }

    @Test
    fun `the movie in the title tells it`() {
        assertEquals("s2", matcher.match(ImportedTrack("Naa Ready (From \"Leo\")", listOf("Someone Else"))).match?.id)
    }

    @Test
    fun `songs with the same name are told apart by singer and length`() {
        assertEquals("s4", matcher.match(ImportedTrack("Kadhal", listOf("Sid Sriram"))).match?.id)
        assertEquals("s3", matcher.match(ImportedTrack("Kadhal", durationMs = 299_000)).match?.id)
        // Nothing to tell them apart: not chosen, both offered.
        val unsure = matcher.match(ImportedTrack("Kadhal"))
        assertNull(unsure.match)
        assertEquals(setOf("s3", "s4"), unsure.choices.map { it.id }.toSet())
    }

    @Test
    fun `a YouTube title with the movie and singer in it`() {
        val row = matcher.match(ImportedTrack("Naa Ready - Lyric Video | Leo | Thalapathy Vijay | Anirudh", listOf("Sony Music South")))
        assertEquals("s2", row.match?.id)
    }

    @Test
    fun `a song the library doesn't have isn't matched to something else`() {
        val row = matcher.match(ImportedTrack("Shape of You", listOf("Ed Sheeran"), durationMs = 233_000))
        assertNull(row.match)
        assertFalse(row.sure)
    }

    @Test
    fun `an ISRC match wins`() {
        assertEquals("s3", matcher.match(ImportedTrack("Something else"), isrcSongId = "s3").match?.id)
    }
}

class PlaylistPagesTest {
    @Test
    fun `Spotify's embed page`() {
        val html = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"type":"playlist","name":"Car songs",
            "trackList":[{"title":"Kanave Kanave","subtitle":"Anirudh Ravichander, Someone","duration":284000}]}}}}}}</script>"""
        val p = PlaylistPages.spotifyEmbed(html)
        assertEquals("Car songs", p.name)
        assertEquals(ImportedTrack("Kanave Kanave", listOf("Anirudh Ravichander", "Someone"), null, 284_000), p.tracks.single())
        assertFalse(p.truncated)
    }

    @Test
    fun `an Apple Music page gives catalog ids to request`() {
        val html = """<script type="application/json" id="serialized-server-data">{"data":[{"data":{"sections":[
            {"itemKind":"containerDetailHeaderLockup","items":[{"title":"Hits","contentDescriptor":{"kind":"playlist"}}]},
            {"itemKind":"trackLockup","items":[{"title":"Naa Ready","duration":245000,"subtitleLinks":[{"title":"Anirudh"}],"tertiaryLinks":[{"title":"Leo"}],
              "contentDescriptor":{"kind":"song","identifiers":{"storeAdamID":"123"}}}]}]}}]}</script>"""
        val p = PlaylistPages.appleMusic(html)
        assertEquals("Hits", p.name)
        assertEquals(ImportedTrack("Naa Ready", listOf("Anirudh"), "Leo", 245_000, catalogId = "itunes-song-123"), p.tracks.single())
    }

    @Test
    fun `a YouTube playlist page, old and new layouts`() {
        val html = """<meta property="og:title" content="Tamil &amp; more"><script>var ytInitialData = {"contents":[
            {"playlistVideoRenderer":{"title":{"runs":[{"text":"Kanave Kanave | David"}]},"shortBylineText":{"runs":[{"text":"Anirudh - Topic"}]},"lengthSeconds":"284"}},
            {"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO","contentImage":{"thumbnailViewModel":{"overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[{"thumbnailBadgeViewModel":{"icon":{"sources":[]},"text":"4:05"}}]}}]}},
             "metadata":{"lockupMetadataViewModel":{"title":{"content":"Naa Ready - Lyric Video | Leo"},"metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"Sony Music South"}}]}]}}}}}}
            ]};</script>"""
        val p = PlaylistPages.youtube(html)
        assertEquals("Tamil & more", p.name)
        assertEquals(ImportedTrack("Kanave Kanave | David", listOf("Anirudh"), null, 284_000), p.tracks[0])
        assertEquals(ImportedTrack("Naa Ready - Lyric Video | Leo", listOf("Sony Music South"), null, 245_000), p.tracks[1])
    }
}
