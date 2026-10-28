package io.github.devasenan134.isaipetti.ui.mixes

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import coil3.compose.AsyncImage
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.data.Mix
import io.github.devasenan134.isaipetti.data.MixArt
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

// Mix covers drawn on the phone (web/src/ui/mixArt.tsx on the website): one design per kind of mix, chosen with
// its picture, colours and words by the server (MixArt in MixMaker.kt). Each design is laid out on a 280 x 280
// grid and scales with the tile, so the same cover works as a thumbnail and as a page header.

private val Anton = FontFamily(Font(R.font.cover_anton))
private val Mono = FontFamily(Font(R.font.cover_space_mono_bold, FontWeight.Bold))
private val Serif = FontFamily(Font(R.font.cover_fraunces_semibold_italic, FontWeight.SemiBold, FontStyle.Italic))
private val Hand = FontFamily(Font(R.font.cover_permanent_marker))

private fun hex(value: String): Color = runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(Color(0xFF777777))

/** [color] mixed with black (t < 0) or white (t > 0). */
private fun shade(color: Color, t: Float): Color {
    val target = if (t < 0) 0f else 1f
    val a = abs(t)
    return Color(color.red + (target - color.red) * a, color.green + (target - color.green) * a, color.blue + (target - color.blue) * a)
}

/**
 * A two-tone photo: dark parts in [dark], light parts in [light], a little more contrast. The same colour matrix
 * as the website's, so both draw the same picture.
 */
private fun duotone(dark: Color, light: Color): ColorFilter {
    val s = floatArrayOf(dark.red, dark.green, dark.blue)
    val h = floatArrayOf(light.red, light.green, light.blue)
    val k = 1.2f
    val b = -0.1f
    val m = FloatArray(20)
    for (i in 0..2) {
        val d = h[i] - s[i]
        m[i * 5] = d * k * 0.2126f
        m[i * 5 + 1] = d * k * 0.7152f
        m[i * 5 + 2] = d * k * 0.0722f
        m[i * 5 + 4] = (s[i] + d * b) * 255f
    }
    m[18] = 1f
    return ColorFilter.colorMatrix(ColorMatrix(m))
}

private val Faded = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    0.67f, 0.42f, 0.10f, 0f, 6f,
    0.19f, 0.83f, 0.09f, 0f, 6f,
    0.15f, 0.29f, 0.62f, 0f, 4f,
    0f, 0f, 0f, 1f, 0f,
)))

private val Muted = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.55f) })

/** Lengths on the 280-unit grid. */
private class Grid(val unit: Dp) {
    fun u(n: Number): Dp = unit * n.toFloat()
    fun at(left: Number, top: Number, width: Number, height: Number): Modifier =
        Modifier.offset(u(left), u(top)).size(u(width), u(height))
}

/** A font size that fits [text] in [width] units on at most [lines] lines ([charWidth]: a letter's width in ems). */
private fun fit(text: String, width: Float, max: Float, charWidth: Float, lines: Int = 1) =
    min(max, width * lines / (text.length.coerceAtLeast(1) * charWidth))

/** The cover of [mix] drawn from its [art], square, filling [modifier]'s width. */
@Composable
fun MixArtCover(mix: Mix, art: MixArt, modifier: Modifier = Modifier, corner: Dp = 0.dp) {
    BoxWithConstraints(modifier.aspectRatio(1f).clip(RoundedCornerShape(corner)).background(hex(art.ground))) {
        val g = remember(maxWidth) { Grid(maxWidth / 280f) }
        Design(g, mix, art)
    }
}

@Composable
private fun Words(
    g: Grid, text: String, size: Float, family: FontFamily, color: Color, modifier: Modifier = Modifier,
    spacing: Float = 0f, lineHeight: Float = 1f, maxLines: Int = 1, align: TextAlign? = null, weight: FontWeight? = null,
) {
    val density = LocalDensity.current
    with(density) {
        Text(
            text, modifier = modifier, color = color, fontFamily = family, fontSize = g.u(size).toSp(),
            letterSpacing = g.u(spacing).toSp(), lineHeight = g.u(size * lineHeight).toSp(), maxLines = maxLines,
            overflow = TextOverflow.Clip, softWrap = maxLines > 1, textAlign = align, fontWeight = weight,
        )
    }
}

@Composable
private fun Pic(id: String?, modifier: Modifier, size: Int = 400, filter: ColorFilter? = null, alignment: Alignment = Alignment.Center) {
    val app = LocalApp.current
    AsyncImage(
        model = app.api.coverUrl(id, size), contentDescription = null, contentScale = ContentScale.Crop,
        colorFilter = filter, alignment = alignment, modifier = modifier.background(Color.White.copy(alpha = 0.08f)),
    )
}

/** Faces are near the top of a photo. */
private val Face = BiasAlignment(0f, -0.5f)

/** Vinyl seen from above: fine grooves. */
@Composable
private fun grooves(g: Grid): Brush {
    val step = with(LocalDensity.current) { g.u(3).toPx() }
    return Brush.radialGradient(
        0f to Color(0xFF121212), 0.5f to Color(0xFF121212), 0.5f to Color(0xFF1F1F1F), 1f to Color(0xFF1F1F1F),
        radius = step, tileMode = TileMode.Repeated,
    )
}

/** A record seen from above, [label] in the middle. */
@Composable
private fun Record(g: Grid, left: Number, top: Number, size: Float, labelSize: Float = size * 0.32f, hole: Float = 8f, label: @Composable BoxScope.() -> Unit) {
    Box(g.at(left, top, size, size).clip(CircleShape).background(grooves(g)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(g.u(labelSize)).clip(CircleShape), content = label)
        if (hole > 0) Box(Modifier.size(g.u(hole)).clip(CircleShape).background(Color(0xFF121212)))
    }
}

/** Draws in grid units over the whole cover. */
@Composable
private fun Drawing(onDraw: DrawScope.() -> Unit) {
    Canvas(Modifier.fillMaxSize()) { scale(size.width / 280f, size.height / 280f, pivot = Offset.Zero) { onDraw() } }
}

@Composable
private fun BoxScope.Design(g: Grid, mix: Mix, art: MixArt) {
    val covers = mix.covers
    val first = art.picture ?: covers.firstOrNull()
    val ink = hex(art.ink)
    val ground = hex(art.ground)
    when (art.style) {
        // Daily Mix: a record sleeve with its vinyl sliding out; the mix's main artist in two tones.
        "sleeve" -> {
            Record(g, 116, 22, 236f, labelSize = 70f) { Box(Modifier.fillMaxSize().background(ink)) }
            Box(g.at(0, 0, 212, 280).shadow(g.u(10))) {
                Pic(first, Modifier.fillMaxSize(), 500, duotone(ground, ink), BiasAlignment(0.1f, -0.5f))
            }
            val light = shade(ink, 0.6f)
            Words(g, "JUKEBOX · DAILY", 10f, Mono, light, Modifier.offset(g.u(16), g.u(14)), spacing = 2f)
            Column(Modifier.align(Alignment.BottomStart).padding(start = g.u(14), bottom = g.u(12))) {
                Words(g, art.kicker, 11f, Mono, light, spacing = 3f)
                Words(g, art.headline, 96f, Anton, light, lineHeight = 0.95f)
            }
        }

        // Discover Weekly: a mixtape, the title written on its label.
        "cassette" -> {
            val stripe = with(LocalDensity.current) { g.u(31).toPx() }
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(
                0f to ground, 0.5f to ground, 0.5f to shade(ground, 0.12f), 1f to shade(ground, 0.12f),
                start = Offset.Zero, end = Offset(stripe, stripe), tileMode = TileMode.Repeated,
            )))
            Box(g.at(24, 58, 232, 150).rotate(-7f).shadow(g.u(12), RoundedCornerShape(g.u(12))).clip(RoundedCornerShape(g.u(12))).background(Color(0xFF1B1B1D))) {
                Box(g.at(12, 10, 208, 74).clip(RoundedCornerShape(g.u(6))).background(Color(0xFFF3EAD7))) {
                    Box(g.at(0, 0, 208, 12).background(Color(0xFF2F6BFF)))
                    Box(g.at(10, 48, 188, 1).background(Color(0xFFC9BFA9)))
                    Box(g.at(10, 62, 188, 1).background(Color(0xFFC9BFA9)))
                    Words(g, art.headline, fit(art.headline, 168f, 24f, 0.5f), Hand, Color(0xFF1D2A6B), Modifier.offset(g.u(10), g.u(16)).rotate(-2f))
                    Box(g.at(182, 15, 18, 18).border(g.u(2), Color(0xFF1D2A6B), RoundedCornerShape(g.u(3))), contentAlignment = Alignment.Center) {
                        Words(g, "A", 12f, Mono, Color(0xFF1D2A6B))
                    }
                }
                Box(g.at(50, 92, 132, 34).clip(RoundedCornerShape(g.u(17))).background(Color(0xFF2B2B2E)))
                Box(g.at(86, 102, 60, 14).clip(RoundedCornerShape(g.u(3))).background(Color(0xFF5A3A28)))
                Canvas(g.at(56, 97, 24, 24)) { spool() }
                Canvas(g.at(152, 97, 24, 24)) { spool() }
                Box(g.at(44, 134, 144, 16).clip(GenericShape { s, _ ->
                    moveTo(s.width * 0.08f, 0f); lineTo(s.width * 0.92f, 0f); lineTo(s.width, s.height); lineTo(0f, s.height); close()
                }).background(Color(0xFF2B2B2E)))
            }
            Words(g, art.kicker, 11f, Mono, ink, Modifier.offset(g.u(16), g.u(250)), spacing = 2f)
        }

        // Time of day, seasons and calm moods: a sky, the cover as its sun or moon.
        "sky" -> Sky(g, art, first)

        // Fresh for You, New Arrivals: covers pulled out of a crate, a sticker.
        "crate" -> {
            Pic(covers.getOrElse(1) { first }, g.at(22, 40, 128, 128).rotate(-12f).shadow(g.u(8)))
            Pic(covers.getOrElse(2) { first }, g.at(130, 34, 128, 128).rotate(10f).shadow(g.u(8)))
            Pic(covers.firstOrNull(), g.at(76, 52, 128, 128).shadow(g.u(10)))
            Column(g.at(186, 132, 76, 76).rotate(14f).clip(CircleShape).background(Color(0xFFE63B2E)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                Words(g, "FRESH", 22f, Anton, Color.White)
                art.stamp?.let { Words(g, it, 9f, Mono, Color.White, spacing = 1f) }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = g.u(16), end = g.u(16), bottom = g.u(12))) {
                Words(g, art.kicker, 10f, Mono, Color(0xFF8A6D4A), spacing = 2f)
                Words(g, art.headline, fit(art.headline, 248f, 34f, 0.55f), FontFamily.Default, ink, spacing = -1f, weight = FontWeight.Black)
            }
        }

        // On Repeat, Top 50, scores: a spinning record, the words round its rim.
        "spin" -> {
            Record(g, 40, 40, 200f, labelSize = 80f) { Pic(first, Modifier.fillMaxSize(), 200) }
            val context = LocalContext.current
            val typeface = remember { ResourcesCompat.getFont(context, R.font.cover_space_mono_bold) }
            val text = remember(art.headline) { "${art.headline} • ".repeat(ceil(70.0 / (art.headline.length + 3)).toInt().coerceAtLeast(2)) }
            Drawing {
                drawIntoCanvas { canvas ->
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.typeface = typeface; textSize = 13f; color = ink.toArgb(); letterSpacing = 3f / 13f
                    }
                    // Fit the words round the whole rim (like the website's textLength).
                    val width = paint.measureText(text)
                    paint.letterSpacing += (741f - width) / (text.length * 13f)
                    val path = android.graphics.Path().apply { addCircle(140f, 140f, 118f, android.graphics.Path.Direction.CW) }
                    canvas.nativeCanvas.save()
                    canvas.nativeCanvas.rotate(180f, 140f, 140f)
                    canvas.nativeCanvas.drawTextOnPath(text, path, 0f, 0f, paint)
                    canvas.nativeCanvas.restore()
                }
            }
        }

        // Rewind: an old favourite as a faded polaroid.
        "polaroid" -> {
            Words(g, art.kicker, 15f, Mono, ink, Modifier.offset(g.u(14), g.u(12)), spacing = 1f)
            Words(g, "SP 0:45:12", 12f, Mono, ink, Modifier.align(Alignment.TopEnd).padding(top = g.u(13), end = g.u(14)))
            Box(g.at(66, 48, 156, 192).rotate(5f).shadow(g.u(10)).background(Color(0xFFF7F3EA))) {
                Pic(first, g.at(12, 12, 132, 132), filter = Faded)
                Words(g, art.headline, fit(art.headline, 140f, 24f, 0.5f), Hand, Color(0xFF2B2B2B), Modifier.align(Alignment.BottomCenter).padding(bottom = g.u(8)))
            }
            Box(g.at(112, 38, 74, 22).rotate(-4f).background(Color(0xCCF0E2BE)))
        }

        // Hidden Gems, Friends Mix: photos scattered on a table.
        "photos" -> {
            Polaroid(g, covers.getOrElse(1) { first }, 18, 26, -10f)
            Polaroid(g, covers.getOrElse(2) { first }, 150, 18, 9f)
            Polaroid(g, covers.firstOrNull(), 84, 60, -2f)
            Column(Modifier.align(Alignment.BottomStart).padding(start = g.u(16), end = g.u(16), bottom = g.u(12))) {
                Words(g, art.kicker, 10f, Mono, shade(ink, -0.3f), spacing = 2f)
                Words(g, art.headline, fit(art.headline, 248f, 36f, 0.52f), Hand, ink, lineHeight = 1.05f)
            }
        }

        // Composer and singer mixes: an LP, its record sliding out the top; the artist in two tones.
        "lp" -> {
            Record(g, 30, -46, 220f, labelSize = 64f, hole = 0f) { Box(Modifier.fillMaxSize().background(ink)) }
            Box(g.at(0, 66, 280, 214).shadow(g.u(10))) { Pic(first, Modifier.fillMaxSize(), 500, duotone(ground, ink), BiasAlignment(0f, -0.64f)) }
            val light = shade(ink, 0.6f)
            Column(Modifier.align(Alignment.BottomStart).padding(start = g.u(16), end = g.u(16), bottom = g.u(12))) {
                Words(g, art.kicker, 11f, Mono, light, spacing = 3f)
                Words(g, art.headline, fit(art.headline, 248f, 50f, 0.44f, 2), Anton, light, lineHeight = 0.95f, maxLines = 2)
            }
        }

        // Because you played: the song as a 7" single.
        "single" -> {
            Words(g, art.kicker, 10f, Mono, shade(ink, 0.25f), Modifier.offset(g.u(16), g.u(16)), spacing = 2f)
            Words(g, art.headline, fit(art.headline, 248f, 36f, 0.48f, 2), Serif, ink, Modifier.offset(g.u(16), g.u(32)).fillMaxWidth(0.9f), maxLines = 2)
            Record(g, 100, 96, 166f, labelSize = 80f, hole = 0f) {
                Box(Modifier.fillMaxSize().background(Color(0xFFC0392B)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(g.u(40)).clip(CircleShape).background(ground))
                }
            }
            Pic(first, g.at(16, 104, 158, 158).shadow(g.u(10)))
        }

        // 60s–80s decades: a reel-to-reel tape recorder, drawn simply.
        "reel" -> {
            Drawing {
                val cream = Color(0xFFF2E6C9)
                val tape = Color(0xFFC9893D)
                drawPath(Path().apply { moveTo(28f, 128f); lineTo(140f, 152f); lineTo(252f, 116f) }, tape, style = Stroke(2f))
                drawRoundRect(cream, Offset(128f, 146f), Size(24f, 12f), androidx.compose.ui.geometry.CornerRadius(3f))
                reel(Offset(78f, 82f), 62f, 30f, 42f, 12f, 9f, tape, cream, ground, full = true)
                reel(Offset(206f, 76f), 54f, 24f, 34f, 11f, 8f, tape, cream, ground, full = false)
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = g.u(16), bottom = g.u(10))) {
                Words(g, art.kicker, 11f, Mono, Color(0xFFF2E6C9), spacing = 3f)
                Words(g, art.headline, 78f, Anton, ink, lineHeight = 0.95f)
            }
        }

        // 90s–2000s decades: a CD with the cover printed on it.
        "cd" -> {
            Box(g.at(82, 14, 186, 186).shadow(g.u(12), CircleShape).clip(CircleShape).background(Brush.sweepGradient(listOf(
                Color(0xFFD8DBE6), Color(0xFFF6CFE9), Color(0xFFC9ECFF), Color(0xFFDCFFE2), Color(0xFFFFF3C2), Color(0xFFF6CFE9), Color(0xFFD8DBE6),
            ))).padding(g.u(5)).clip(CircleShape), contentAlignment = Alignment.Center) {
                Pic(first, Modifier.fillMaxSize())
                Box(Modifier.size(g.u(60)).clip(CircleShape).background(Color(0xBFEBEEFA)))
                Box(Modifier.size(g.u(24)).clip(CircleShape).background(ground))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = g.u(16), end = g.u(16), bottom = g.u(12))) {
                Words(g, art.headline, 58f, Anton, shade(ink, 0.7f), lineHeight = 0.95f)
                Words(g, art.kicker, 11f, Mono, ink, spacing = 2f)
            }
        }

        // A composer's mood: a die-cut sleeve, the record's label (the artist) showing through.
        "diecut" -> {
            Box(g.at(54, 18, 172, 172).clip(CircleShape).background(Color(0xFF121212)), contentAlignment = Alignment.Center) {
                Pic(first, Modifier.size(g.u(148)).clip(CircleShape), 500, duotone(shade(ground, -0.65f), shade(ground, 0.7f)), Face)
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = g.u(16), end = g.u(16), bottom = g.u(12))) {
                Words(g, art.kicker, 11f, Mono, ink.copy(alpha = 0.85f), spacing = 3f)
                Words(g, art.headline, fit(art.headline, 248f, 34f, 0.48f), Serif, ink)
            }
        }

        // Deep Cuts: a record half out of its paper inner sleeve.
        "paper" -> {
            Record(g, 96, 40, 200f, labelSize = 76f) { Pic(first, Modifier.fillMaxSize(), 200) }
            Box(g.at(16, 30, 176, 220).shadow(g.u(10)).background(Color(0xFFC9A274))) {
                Box(g.at(62, 74, 52, 52).clip(CircleShape).background(ground))
                Column(Modifier.align(Alignment.BottomStart).padding(start = g.u(14), bottom = g.u(12))) {
                    Words(g, art.kicker, 9f, Mono, Color(0xFF5B3A1C), spacing = 2f)
                    Words(g, art.headline.replace(' ', '\n'), 44f, Anton, ink, lineHeight = 0.95f, maxLines = 2)
                }
            }
        }

        // Energetic moods: a turntable from above, the cover on the platter.
        "turntable" -> {
            Box(g.at(14, 64, 200, 200).shadow(g.u(6), CircleShape).clip(CircleShape).background(Color(0xFF9AA0A6)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(g.u(188)).clip(CircleShape).background(Color(0xFFD7263D)))
            }
            Record(g, 26, 76, 176f, labelSize = 64f, hole = 0f) { Pic(first, Modifier.fillMaxSize(), 200) }
            Drawing {
                drawCircle(Color(0xFF3A3A3C), 16f, Offset(244f, 96f))
                drawCircle(Color(0xFFC9CCD0), 7f, Offset(244f, 96f))
                drawPath(Path().apply { moveTo(244f, 96f); lineTo(238f, 196f); lineTo(196f, 230f) }, Color(0xFFC9CCD0), style = Stroke(5f, cap = StrokeCap.Round))
                rotate(-38f, Offset(193f, 230f)) { drawRoundRect(Color(0xFFE9EAEC), Offset(182f, 224f), Size(22f, 13f), androidx.compose.ui.geometry.CornerRadius(2f)) }
                drawRoundRect(Color(0xFF2C2C2E), Offset(236f, 214f), Size(8f, 48f), androidx.compose.ui.geometry.CornerRadius(3f))
                drawRoundRect(Color(0xFFD7263D), Offset(232f, 234f), Size(16f, 8f), androidx.compose.ui.geometry.CornerRadius(2f))
            }
            Column(Modifier.padding(start = g.u(16), top = g.u(12), end = g.u(16))) {
                Words(g, art.headline, fit(art.headline, 210f, 36f, 0.44f), Anton, ink, lineHeight = 1f)
                Words(g, art.kicker, 10f, Mono, Color(0xFFFF8A96), spacing = 2f)
            }
        }

        // Stations: an old radio's face, the artist in the speaker, a dial tuned to the station.
        "tuner" -> Tuner(g, mix, art)

        else -> Pic(covers.firstOrNull(), Modifier.fillMaxSize())
    }
}

private fun DrawScope.spool() {
    val w = size.width
    val c = Offset(w / 2, w / 2)
    drawCircle(Color(0xFFE9E3D6), w * 11 / 24, c)
    drawCircle(Color(0xFF2B2B2E), w * 4 / 24, c)
    for (i in 0 until 4) rotate(i * 90f, c) { drawRect(Color(0xFF2B2B2E), Offset(w * 11 / 24, w * 1.5f / 24), Size(w * 2 / 24, w * 4 / 24)) }
}

private fun DrawScope.reel(
    at: Offset, radius: Float, hub: Float, holeDistance: Float, holeRadius: Float, axle: Float,
    tape: Color, cream: Color, ground: Color, full: Boolean,
) {
    translate(at.x, at.y) {
        if (full) drawCircle(tape, radius, Offset.Zero)
        drawCircle(cream, radius, Offset.Zero, style = Stroke(4f))
        drawCircle(if (full) ground else tape, hub, Offset.Zero)
        for (i in 0 until 3) rotate(i * 120f, Offset.Zero) { drawCircle(ground, holeRadius, Offset(0f, -holeDistance)) }
        drawCircle(cream, axle, Offset.Zero)
    }
}

@Composable
private fun Polaroid(g: Grid, id: String?, left: Int, top: Int, turn: Float) {
    Box(g.at(left, top, 112, 132).rotate(turn).shadow(g.u(8)).background(Color(0xFFF7F3EA))) {
        Pic(id, g.at(8, 8, 96, 96), 200)
    }
}

private class SkyLook(
    val sky: List<Pair<Float, Color>>, val horizon: String, val low: Color, val high: Color,
    val sun: Triple<Int, Int, Int>, val text: Color, val kicker: Color, val stars: Boolean = false,
)

/** The skies: their gradient, what's on the horizon, where the sun or moon sits, and the colours of the words. */
private val Skies = mapOf(
    "dawn" to SkyLook(listOf(0f to Color(0xFFFFE08A), 0.5f to Color(0xFFFFAB5E), 0.66f to Color(0xFFFF7A59)), "sea", Color(0xFFC2185B), Color(0xFFC2185B), Triple(65, 66, 150), Color(0xFFFFF4EC), Color(0xFF7A2D00)),
    "day" to SkyLook(listOf(0f to Color(0xFF5FB4F0), 0.62f to Color(0xFFA9DCFF), 1f to Color(0xFFFFF1CF)), "hills", Color(0xFF1F5C38), Color(0xFF2F7D4F), Triple(168, 26, 92), Color.White, Color(0xFF0D3550)),
    "dusk" to SkyLook(listOf(0f to Color(0xFF2B1B5A), 0.55f to Color(0xFFA23E78), 0.85f to Color(0xFFFF8A5B), 1f to Color(0xFFFFC36B)), "hills", Color(0xFF200A26), Color(0xFF3A1640), Triple(92, 96, 110), Color(0xFFFFF4EC), Color(0xFFFFD0C2)),
    "night" to SkyLook(listOf(0f to Color(0xFF070B22), 0.55f to Color(0xFF1D1650), 0.82f to Color(0xFF6A2C70), 1f to Color(0xFFE2667F)), "hills", Color(0xFF07051A), Color(0xFF120A2A), Triple(150, 22, 104), Color(0xFFFFF3F6), Color(0xFFFFC9D6), stars = true),
    "rain" to SkyLook(listOf(0f to Color(0xFF6D7D8C), 0.55f to Color(0xFF3B4B5B), 1f to Color(0xFF1D2935)), "clouds", Color(0xFF7A8794), Color(0xFF8A97A3), Triple(148, 24, 96), Color(0xFFF4F8FC), Color(0xFFCFE0F0)),
)

@Composable
private fun BoxScope.Sky(g: Grid, art: MixArt, picture: String?) {
    val s = Skies[art.variant] ?: Skies.getValue("dusk")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(*s.sky.toTypedArray())))
    if (s.stars) Drawing {
        listOf(Triple(31f, 31f, 1f), Triple(93.5f, 19.5f, 1.5f), Triple(61f, 75f, 1f), Triple(237f, 113f, 1f), Triple(21f, 129f, 1f), Triple(119f, 55f, 1f))
            .forEach { (x, y, r) -> drawCircle(Color.White, r, Offset(x, y)) }
    }
    val (x, y, size) = s.sun
    if (s.stars) {
        Box(g.at(x - 14, y - 14, size + 28, size + 28).clip(CircleShape).background(Color.White.copy(alpha = 0.03f)))
        Box(g.at(x - 6, y - 6, size + 12, size + 12).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)))
    }
    Pic(picture, g.at(x, y, size, size).clip(CircleShape), filter = if (art.variant == "rain") Muted else null)
    when (s.horizon) {
        "sea" -> {
            val step = with(LocalDensity.current) { g.u(9).toPx() }
            Box(g.at(0, 176, 280, 104).background(s.low).background(Brush.verticalGradient(
                0f to Color(0x59FFC88C), 2f / 9 to Color(0x59FFC88C), 2f / 9 to Color.Transparent, 1f to Color.Transparent,
                startY = 0f, endY = step, tileMode = TileMode.Repeated,
            )))
        }
        "hills" -> Drawing {
            translate(0f, 190f) {
                drawPath(Path().apply { moveTo(0f, 52f); cubicTo(40f, 30f, 70f, 36f, 104f, 50f); cubicTo(140f, 64f, 170f, 34f, 210f, 38f); cubicTo(240f, 41f, 262f, 52f, 280f, 46f); lineTo(280f, 90f); lineTo(0f, 90f); close() }, s.high)
                drawPath(Path().apply { moveTo(0f, 70f); cubicTo(50f, 56f, 90f, 62f, 130f, 72f); cubicTo(170f, 82f, 220f, 60f, 280f, 66f); lineTo(280f, 90f); lineTo(0f, 90f); close() }, s.low)
            }
        }
        else -> {
            Drawing {
                drawPath(Path().apply { moveTo(-10f, 92f); cubicTo(10f, 66f, 46f, 64f, 62f, 80f); cubicTo(70f, 56f, 110f, 50f, 124f, 74f); cubicTo(140f, 62f, 168f, 66f, 172f, 88f); cubicTo(190f, 82f, 214f, 90f, 214f, 108f); lineTo(-10f, 108f); close() }, s.high)
                drawPath(Path().apply { moveTo(110f, 120f); cubicTo(124f, 98f, 160f, 96f, 172f, 112f); cubicTo(182f, 92f, 222f, 88f, 236f, 110f); cubicTo(250f, 102f, 284f, 106f, 290f, 124f); lineTo(110f, 124f); close() }, s.low)
                // Rain: thin slanted streaks below the clouds.
                var start = -80f
                while (start < 300f) {
                    drawLine(Color(0x47DCE8F5), Offset(start, 104f), Offset(start + 47f, 280f), 1f)
                    start += 14f
                }
            }
        }
    }
    if (art.kicker.isNotEmpty()) Words(g, art.kicker, 10f, Mono, s.kicker, Modifier.offset(g.u(16), g.u(if (s.horizon == "sea") 14 else 156)), spacing = 2f)
    Words(g, art.headline, fit(art.headline, 248f, 34f, 0.46f, 2), Serif, s.text, Modifier.align(Alignment.BottomStart).padding(start = g.u(16), end = g.u(16), bottom = g.u(12)), maxLines = 2)
}

/** Stations: the dial runs 88–108; the needle sits at the station's frequency. */
@Composable
private fun BoxScope.Tuner(g: Grid, mix: Mix, art: MixArt) {
    val ink = hex(art.ink)
    val ground = hex(art.ground)
    val frequency = art.stamp?.substringBefore(' ')?.toFloatOrNull() ?: 98f
    val needle = 6f + (frequency - 88f) / 20f * 234f
    Row(g.at(14, 12, 252, 14), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
        Words(g, art.kicker, 10f, Mono, ink, spacing = 1.5f)
        Words(g, art.stamp.orEmpty(), 10f, Mono, ink, spacing = 1.5f)
    }
    // The speaker grille: a ring of dots round the artist.
    Drawing {
        val dot = ink.copy(alpha = 0.35f)
        var y = 30f
        while (y < 194f) {
            var x = 58f
            while (x < 222f) {
                val dx = x + 3.5f - 140f
                val dy = y + 3.5f - 112f
                if (dx * dx + dy * dy <= 82f * 82f) drawCircle(dot, 1.3f, Offset(x + 3.5f, y + 3.5f))
                x += 7f
            }
            y += 7f
        }
    }
    Box(g.at(71, 43, 138, 138).clip(CircleShape).background(ink), contentAlignment = Alignment.Center) {
        Box(Modifier.size(g.u(134)).clip(CircleShape).background(ground), contentAlignment = Alignment.Center) {
            if (art.picture != null) {
                Pic(art.picture, Modifier.size(g.u(126)).clip(CircleShape), 400, duotone(shade(ground, -0.35f), ink), Face)
            } else {
                Column(Modifier.size(g.u(126)).clip(CircleShape)) {
                    for (row in 0..1) Row(Modifier.weight(1f)) {
                        for (col in 0..1) Pic(mix.covers.getOrElse(row * 2 + col) { mix.covers.firstOrNull() }, Modifier.weight(1f).fillMaxSize(), 150)
                    }
                }
            }
        }
    }
    Words(
        g, art.headline, fit(art.headline, 250f, 27f, 0.55f), FontFamily.Default, Color.White,
        g.at(8, 186, 264, 34), align = TextAlign.Center, weight = FontWeight.Black, spacing = -0.5f,
    )
    Box(g.at(16, 228, 248, 38).clip(RoundedCornerShape(g.u(4))).background(Color(0xFFEFE6CF))) {
        // The dial's marks, every 7 units (this box is 248 x 38 units, not square).
        Canvas(Modifier.fillMaxSize()) {
            val unit = size.width / 248f
            var x = 0f
            while (x <= 236f) {
                drawLine(Color(0xFF6B5A33), Offset((6f + x) * unit, 5f * unit), Offset((6f + x) * unit, 16f * unit), unit)
                x += 7f
            }
        }
        Row(g.at(6, 22, 236, 12), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            listOf("88", "92", "96", "100", "104", "108").forEach { Words(g, it, 9f, Mono, Color(0xFF6B5A33)) }
        }
        Box(g.at(needle, 0, 2, 38).background(Color(0xFFE63B2E)))
    }
}

