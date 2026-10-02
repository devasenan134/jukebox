package io.github.devasenan134.jukebox.server.library

import java.io.File

/** Real (tiny) audio files for tests, made with ffmpeg: each sounds different, tagged like the organizer tags the library. */
object TestAudio {
    fun song(root: File, path: String, hz: Int, tags: Map<String, String>, bitrate: String = "128k", seconds: Int = 20): File {
        val file = File(root, path).apply { parentFile.mkdirs() }
        val meta = tags.flatMap { (k, v) -> listOf("-metadata", "$k=$v") }
        val cmd = listOf("ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i",
            "aevalsrc=0.5*sin(2*PI*($hz+${hz / 20}*t)*t)+0.3*sin(2*PI*${hz * 3 / 2}*t)*gt(mod(t\\,${hz % 3 + 1})\\,0.5):s=22050:d=$seconds",
            "-c:a", "aac", "-b:a", bitrate, "-movflags", "use_metadata_tags") + meta + file.path
        run(cmd)
        return file
    }

    /** The organizer's layout: `Airaa (2019)/0N - Title.m4a`, the score in `Airaa (2019) (Original Background Score)/`. */
    fun airaa(root: File, n: Int, title: String, hz: Int, score: Boolean = false, singers: String = "Sathya Prakash") = song(
        root, if (score) "Airaa (2019) (Original Background Score)/0$n - $title.m4a" else "Airaa (2019)/0$n - $title.m4a", hz,
        mapOf("title" to title, "album" to if (score) "Airaa (Original Background Score)" else "Airaa", "artist" to singers,
            "album_artist" to "Sundaramurthy K.S.", "composer" to "Sundaramurthy K.S.", "date" to "2019", "track" to "$n/3",
            "disc" to "1/1", "DISCSUBTITLE" to if (score) "" else "Soundtrack", "genre" to "Tamil"),
    )

    /** A small JPEG. */
    fun jpeg(file: File): File {
        file.parentFile.mkdirs()
        run(listOf("ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i", "color=c=red:s=256x256", "-frames:v", "1", file.path))
        return file
    }

    private fun run(cmd: List<String>) {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { out }
    }
}
