package io.github.devasenan134.isaipetti.ui.settings

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.data.Eq
import io.github.devasenan134.isaipetti.data.EqGroup
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import kotlin.math.abs
import kotlin.math.ln

/**
 * Settings › Sound (SoundSettings.tsx on the website): the equalizer. Built-in profiles and your own, and the
 * curve, with a handle per band to drag up and down (or pick a band and step it with − and +).
 */
@Composable
fun SoundCard() {
    val app = LocalApp.current
    val sound = app.sound
    val signedIn = app.session.social.collectAsStateWithLifecycle().value != null
    val context = LocalContext.current
    val s by sound.state.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf<Naming?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf<Int?>(null) }
    val chosen = s.chosen
    val edited = chosen != null && !Eq.same(chosen.gains, s.gains)
    // The chosen profile, if it's one of yours (those can be saved over, renamed and deleted).
    val own = chosen?.takeIf { it.isOwn }
    val preamp = remember(s.gains) { Eq.preampDb(s.gains) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sound", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Equalizer", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "For your headphones or IEMs: choose a profile, or drag the bands and save your own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.enabled, onCheckedChange = sound::setEnabled)
            }

            listOf(
                "Yours" to s.own,
                "Tuning" to Eq.BUILTIN.filter { it.group == EqGroup.Tuning },
                "Genres" to Eq.BUILTIN.filter { it.group == EqGroup.Genre },
            ).filter { it.second.isNotEmpty() }.forEach { (title, list) ->
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { p ->
                        FilterChip(
                            selected = s.enabled && p.id == s.profileId,
                            onClick = { sound.choose(p.id) },
                            label = { Text(if (p.id == s.profileId && edited) "${p.name} •" else p.name) },
                        )
                    }
                }
            }

            EqGraph(
                gains = s.gains,
                active = active,
                onActive = { active = it },
                onBand = sound::setBand,
                modifier = Modifier.alpha(if (s.enabled) 1f else 0.5f),
            )

            // Fine steps for the band last touched.
            active?.let { band ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${freqLabel(Eq.BANDS[band])}Hz  ${formatDb(s.gains[band])}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { sound.setBand(band, s.gains[band] - 0.5f) }) { Text("−", fontSize = 20.sp) }
                    TextButton(onClick = { sound.setBand(band, 0f) }) { Text("0") }
                    TextButton(onClick = { sound.setBand(band, s.gains[band] + 0.5f) }) { Text("+", fontSize = 20.sp) }
                }
            }

            Text(
                buildString {
                    append(
                        when {
                            !s.enabled -> "Off: music plays as it is."
                            chosen != null -> chosen.name + if (edited) " (changed)" else ""
                            else -> "Your changes, not saved as a profile"
                        },
                    )
                    if (s.enabled && preamp < 0) append(" · Preamp ${"%.1f".format(preamp)} dB, so boosts don't distort")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (own != null && edited) {
                    FilledTonalButton(onClick = {
                        sound.saveChosen()
                        Toast.makeText(context, "Saved ${own.name}", Toast.LENGTH_SHORT).show()
                    }) { Text("Save") }
                }
                FilledTonalButton(onClick = { naming = Naming.New }) { Text("Save as new") }
                if (own != null) {
                    TextButton(onClick = { naming = Naming.Rename }) { Text("Rename") }
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = { sound.choose(Eq.BUILTIN.first().id) }) { Text("Reset to flat") }
            }

            if (signedIn) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Sync my profiles", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (s.sync) "Profiles you save are on the website and the Mac app too. The equalizer's on/off and the curve playing stay with each device."
                            else "Your profiles are kept on this phone only.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.sync, onCheckedChange = sound::setSync)
                }
            }
        }
    }

    naming?.let { mode ->
        ProfileNameDialog(
            title = if (mode == Naming.New) "Save profile" else "Rename profile",
            initial = if (mode == Naming.Rename) own?.name.orEmpty() else "",
            onDismiss = { naming = null },
            onSave = { name ->
                if (mode == Naming.New) sound.saveAs(name) else own?.let { sound.rename(it.id, name) }
                naming = null
                Toast.makeText(context, "Saved $name", Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (confirmDelete && own != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${own.name}?") },
            text = { Text("The sound stays as it is until you choose another profile.") },
            confirmButton = {
                Button(onClick = {
                    sound.delete(own.id)
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private enum class Naming { New, Rename }

private fun freqLabel(f: Int) = if (f >= 1000) "${f / 1000}k" else "$f"

private fun formatDb(db: Float) = (if (db > 0) "+" else "") + "%.1f dB".format(db)

private const val MIN_F = 20.0
private const val MAX_F = 20_000.0

/**
 * The curve: the sound the bands make together, with a handle on each band. A drag has to start near a handle,
 * so scrolling Settings past the graph doesn't change the sound.
 */
@Composable
private fun EqGraph(
    gains: List<Float>,
    active: Int?,
    onActive: (Int) -> Unit,
    onBand: (Int, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.onSurfaceVariant)
    val current by rememberUpdatedState(gains)
    val setActive by rememberUpdatedState(onActive)
    val setBand by rememberUpdatedState(onBand)
    val curve = remember(gains) { Eq.CURVE_FREQS.map { it to Eq.responseDb(gains, it).toFloat().coerceIn(-Eq.MAX_DB, Eq.MAX_DB) } }

    Canvas(
        modifier.fillMaxWidth().height(200.dp).pointerInput(Unit) {
            val padLeft = 30.dp.toPx()
            val padRight = 10.dp.toPx()
            val padTop = 10.dp.toPx()
            val padBottom = 20.dp.toPx()
            val reach = 28.dp.toPx()
            fun x(f: Double) = padLeft + (ln(f / MIN_F) / ln(MAX_F / MIN_F)).toFloat() * (size.width - padLeft - padRight)
            fun y(db: Float) = padTop + (Eq.MAX_DB - db) / (2 * Eq.MAX_DB) * (size.height - padTop - padBottom)
            fun dbAt(py: Float) = Eq.MAX_DB - (py - padTop) / (size.height - padTop - padBottom) * 2 * Eq.MAX_DB
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val band = Eq.BANDS.indices.minBy { abs(x(Eq.BANDS[it].toDouble()) - down.position.x) }
                setActive(band)
                // Only a touch near the band's handle drags it.
                if (abs(y(current[band]) - down.position.y) > reach) return@awaitEachGesture
                down.consume()
                drag(down.id) { change ->
                    setBand(band, dbAt(change.position.y))
                    change.consume()
                }
            }
        },
    ) {
        val padLeft = 30.dp.toPx()
        val padRight = 10.dp.toPx()
        val padTop = 10.dp.toPx()
        val padBottom = 20.dp.toPx()
        fun x(f: Double) = padLeft + (ln(f / MIN_F) / ln(MAX_F / MIN_F)).toFloat() * (size.width - padLeft - padRight)
        fun y(db: Float) = padTop + (Eq.MAX_DB - db) / (2 * Eq.MAX_DB) * (size.height - padTop - padBottom)

        for (db in listOf(-12f, -6f, 0f, 6f, 12f)) {
            drawLine(colors.outlineVariant, Offset(padLeft, y(db)), Offset(size.width - padRight, y(db)), strokeWidth = if (db == 0f) 1.5.dp.toPx() else 1.dp.toPx())
            val label = measurer.measure(if (db > 0) "+${db.toInt()}" else "${db.toInt()}", labelStyle)
            drawText(label, topLeft = Offset(padLeft - 4.dp.toPx() - label.size.width, y(db) - label.size.height / 2))
        }
        Eq.BANDS.forEach { f ->
            val px = x(f.toDouble())
            drawLine(colors.outlineVariant, Offset(px, padTop), Offset(px, size.height - padBottom), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f)))
            val label = measurer.measure(freqLabel(f), labelStyle)
            drawText(label, topLeft = Offset(px - label.size.width / 2, size.height - label.size.height))
        }

        val line = Path()
        curve.forEachIndexed { i, (f, db) -> if (i == 0) line.moveTo(x(f), y(db)) else line.lineTo(x(f), y(db)) }
        val area = Path().apply {
            addPath(line)
            lineTo(x(MAX_F), y(0f))
            lineTo(x(MIN_F), y(0f))
            close()
        }
        drawPath(area, colors.primary.copy(alpha = 0.14f), style = Fill)
        drawPath(line, colors.primary, style = Stroke(width = 2.5.dp.toPx(), join = StrokeJoin.Round))

        Eq.BANDS.forEachIndexed { i, f ->
            val center = Offset(x(f.toDouble()), y(gains[i]))
            val r = (if (active == i) 9 else 7).dp.toPx()
            drawCircle(if (active == i) colors.primary else colors.surfaceContainerHighest, r, center)
            drawCircle(colors.primary, r, center, style = Stroke(2.5.dp.toPx()))
        }
    }
}

@Composable
private fun ProfileNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("A name, e.g. the IEMs it's for.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true)
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
