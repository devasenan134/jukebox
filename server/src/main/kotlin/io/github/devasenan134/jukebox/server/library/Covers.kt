package io.github.devasenan134.jukebox.server.library

import io.github.devasenan134.jukebox.server.Db
import io.github.devasenan134.jukebox.server.query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

private val log = LoggerFactory.getLogger("jukebox.covers")

/**
 * Cover pictures at the sizes the apps ask for. The artwork folder keeps each picture once (named by its hash);
 * smaller copies go in `sized/` the first time they're asked for, and [warm] makes the common ones ahead of time
 * so a page full of new covers doesn't wait for them.
 */
class Covers(private val artworkDir: File, private val tools: AudioTools) {

    fun original(hash: String, mime: String): File? = File(artworkDir, hash + if (mime == "image/png") ".png" else ".jpg").takeIf { it.isFile }

    /** The picture at [size] × [size] at most, as a file and its type; the original when it's big anyway. */
    fun sized(hash: String, mime: String, size: Int?): Pair<File, String>? {
        val original = original(hash, mime) ?: return null
        if (size == null || size <= 0 || size >= 1500) return original to mime
        val sized = File(artworkDir, "sized/$hash-$size.jpg")
        if (!sized.isFile) {
            val bytes = resize(original, size) ?: tools.resize(original, size) ?: return original to mime
            sized.parentFile.mkdirs()
            val tmp = File(sized.parentFile, "${sized.name}.${Thread.currentThread().id}.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(sized)
        }
        return sized to "image/jpeg"
    }

    /** Makes the [sizes] lists and rows use for every picture that doesn't have them yet. */
    suspend fun warm(db: Db, sizes: List<Int> = WARM_SIZES): Int = withContext(Dispatchers.IO) {
        val all = db.read { query("SELECT DISTINCT hash, mime FROM artwork") { it.getString(1) to it.getString(2) } }
        var made = 0
        for ((hash, mime) in all) for (size in sizes) {
            if (File(artworkDir, "sized/$hash-$size.jpg").isFile) continue
            runCatching { sized(hash, mime, size) }.onSuccess { made++ }
        }
        if (made > 0) log.info("Made {} small covers ahead of time", made)
        made
    }

    companion object {
        /** Song rows and the mini player (150), tiles (300). */
        val WARM_SIZES = listOf(150, 300)

        /**
         * [image] scaled to fit [size] × [size], as JPEG, in this process. Halving in steps keeps it smooth
         * (one big bilinear jump looks grainy) and is several times quicker than area averaging.
         */
        fun resize(image: File, size: Int): ByteArray? = runCatching {
            var img: BufferedImage = ImageIO.read(image) ?: return null
            val scale = minOf(1.0, size.toDouble() / maxOf(img.width, img.height))
            val w = maxOf(1, (img.width * scale).toInt())
            val h = maxOf(1, (img.height * scale).toInt())
            do {
                val nw = maxOf(w, img.width / 2)
                val nh = maxOf(h, img.height / 2)
                val out = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
                out.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    drawImage(img, 0, 0, nw, nh, null)
                    dispose()
                }
                img = out
            } while (img.width > w || img.height > h)
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val params = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.85f }
            ByteArrayOutputStream().also { bytes ->
                ImageIO.createImageOutputStream(bytes).use { stream ->
                    writer.output = stream
                    writer.write(null, IIOImage(img, null, null), params)
                }
                writer.dispose()
            }.toByteArray()
        }.getOrNull()
    }
}
