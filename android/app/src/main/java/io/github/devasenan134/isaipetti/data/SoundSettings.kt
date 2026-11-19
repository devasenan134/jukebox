package io.github.devasenan134.isaipetti.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The equalizer (Settings › Sound): ten bands an octave apart, built-in profiles and your own, kept on the phone.
 * The same bands, filters and built-in profiles as the website and Mac app (web/src/player/equalizer.ts), so a
 * profile sounds the same everywhere. [io.github.devasenan134.isaipetti.playback.EqualizerProcessor] plays it.
 */
object Eq {
    /** Centre frequencies of the bands (Hz). */
    val BANDS = intArrayOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    /** Each band's width: about an octave. */
    const val Q = 1.41
    /** How far a band goes up or down (dB). */
    const val MAX_DB = 12f

    val FLAT = List(BANDS.size) { 0f }

    /**
     * Built-in profiles: tuning for headphones and IEMs, and genres (like the presets music players have). Ids start
     * with "builtin:"; they can't be changed or deleted, only saved as your own. Flat comes first (Reset uses it).
     */
    val BUILTIN = listOf(
        EqProfile("builtin:flat", "Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), EqGroup.Tuning),
        EqProfile("builtin:bass", "Bass Booster", listOf(5.5f, 4.5f, 3.5f, 2.5f, 1f, 0f, 0f, 0f, 0f, 0f), EqGroup.Tuning),
        EqProfile("builtin:subbass", "Sub-bass", listOf(6f, 4f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), EqGroup.Tuning),
        EqProfile("builtin:bassreducer", "Bass Reducer", listOf(-5.5f, -4.5f, -3.5f, -2.5f, -1f, 0f, 0f, 0f, 0f, 0f), EqGroup.Tuning),
        EqProfile("builtin:bright", "Treble Booster", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2.5f, 3.5f, 4.5f, 5.5f), EqGroup.Tuning),
        EqProfile("builtin:treblereducer", "Treble Reducer", listOf(0f, 0f, 0f, 0f, 0f, -1f, -2.5f, -3.5f, -4.5f, -5.5f), EqGroup.Tuning),
        EqProfile("builtin:tametreble", "Tame Treble", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, -2f, -4f, -2f), EqGroup.Tuning),
        EqProfile("builtin:vshape", "V-Shape", listOf(5f, 4f, 2.5f, 0f, -2f, -2f, 0f, 2.5f, 4f, 4.5f), EqGroup.Tuning),
        EqProfile("builtin:warm", "Warm", listOf(3f, 3f, 2f, 1f, 0f, 0f, -1f, -1.5f, -2f, -2.5f), EqGroup.Tuning),
        EqProfile("builtin:vocal", "Vocal Booster", listOf(-1.5f, -3f, -3f, 1.5f, 3.5f, 3.5f, 3f, 1.5f, 0f, -1.5f), EqGroup.Tuning),
        EqProfile("builtin:loudness", "Loudness", listOf(6f, 4f, 0f, 0f, -2f, 0f, -1f, -5f, 5f, 1f), EqGroup.Tuning),
        EqProfile("builtin:deep", "Deep", listOf(5f, 3.5f, 1.5f, 1f, 3f, 2.5f, 1.5f, -2f, -3.5f, -4.5f), EqGroup.Tuning),
        EqProfile("builtin:smallspeakers", "Small Speakers", listOf(5.5f, 4.5f, 3.5f, 2.5f, 1f, 0f, -1f, -2.5f, -3.5f, -4.5f), EqGroup.Tuning),
        EqProfile("builtin:spoken", "Spoken Word", listOf(-3.5f, -0.5f, 0f, 0.5f, 3.5f, 4.5f, 5f, 4.5f, 2.5f, 0f), EqGroup.Tuning),
        EqProfile("builtin:acoustic", "Acoustic", listOf(5f, 5f, 4f, 1f, 2f, 1.5f, 3.5f, 4f, 3.5f, 2f), EqGroup.Genre),
        EqProfile("builtin:classical", "Classical", listOf(4.5f, 3.5f, 3f, 2.5f, -1.5f, -1.5f, 0f, 2f, 3f, 3.5f), EqGroup.Genre),
        EqProfile("builtin:dance", "Dance", listOf(3.5f, 6.5f, 5f, 0f, 2f, 3.5f, 5f, 4.5f, 3.5f, 0f), EqGroup.Genre),
        EqProfile("builtin:electronic", "Electronic", listOf(4f, 4f, 1f, 0f, -2f, 2f, 1f, 1f, 4f, 5f), EqGroup.Genre),
        EqProfile("builtin:hiphop", "Hip-Hop", listOf(5f, 4f, 1.5f, 3f, -1f, -1f, 1.5f, -0.5f, 2f, 3f), EqGroup.Genre),
        EqProfile("builtin:jazz", "Jazz", listOf(4f, 3f, 1.5f, 2f, -1.5f, -1.5f, 0f, 1.5f, 3f, 3.5f), EqGroup.Genre),
        EqProfile("builtin:latin", "Latin", listOf(4.5f, 3f, 0f, 0f, -1.5f, -1.5f, -1.5f, 0f, 3f, 4.5f), EqGroup.Genre),
        EqProfile("builtin:lounge", "Lounge", listOf(-3f, -1.5f, -0.5f, 1.5f, 4f, 2.5f, 0f, -1.5f, 2f, 1f), EqGroup.Genre),
        EqProfile("builtin:piano", "Piano", listOf(3f, 2f, 0f, 2.5f, 3f, 1.5f, 3.5f, 4.5f, 3f, 3.5f), EqGroup.Genre),
        EqProfile("builtin:pop", "Pop", listOf(-1.5f, -1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f, -1.5f), EqGroup.Genre),
        EqProfile("builtin:rnb", "R&B", listOf(2.5f, 7f, 5.5f, 1.5f, -2f, -1.5f, 2.5f, 2.5f, 3f, 3.5f), EqGroup.Genre),
        EqProfile("builtin:rock", "Rock", listOf(5f, 4f, 3f, 1.5f, -0.5f, -1f, 0.5f, 2.5f, 3.5f, 4.5f), EqGroup.Genre),
    )

    /** A peaking filter's coefficients (b0, b1, b2, a0, a1, a2), from the Audio EQ Cookbook (as Web Audio uses). */
    fun peaking(f0: Double, gainDb: Double, fs: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40)
        val w0 = 2 * PI * f0 / fs
        val alpha = sin(w0) / (2 * Q)
        val c = cos(w0)
        return doubleArrayOf(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    /** The response in dB at frequency [f] (Hz) of the bands with these gains, at sample rate [fs]. */
    fun responseDb(gains: List<Float>, f: Double, fs: Double = 48_000.0): Double {
        val w = 2 * PI * f / fs
        val c1 = cos(w)
        val c2 = cos(2 * w)
        var db = 0.0
        gains.forEachIndexed { i, g ->
            if (g == 0f || BANDS[i] >= fs * 0.45) return@forEachIndexed
            val k = peaking(BANDS[i].toDouble(), g.toDouble(), fs)
            val (b0, b1, b2) = Triple(k[0], k[1], k[2])
            val (a0, a1, a2) = Triple(k[3], k[4], k[5])
            // |H(e^jw)|² for a biquad.
            val num = b0 * b0 + b1 * b1 + b2 * b2 + 2 * (b0 * b1 + b1 * b2) * c1 + 2 * b0 * b2 * c2
            val den = a0 * a0 + a1 * a1 + a2 * a2 + 2 * (a0 * a1 + a1 * a2) * c1 + 2 * a0 * a2 * c2
            db += 10 * log10(num / den)
        }
        return db
    }

    /** Frequencies the curve is drawn and checked at: 20 Hz to 20 kHz, evenly spaced on a log scale. */
    val CURVE_FREQS = DoubleArray(121) { 20 * 1000.0.pow(it / 120.0) }

    /**
     * The preamp (dB, zero or less): turns everything down by the curve's highest boost, so boosted bands don't
     * clip. Without it, +6 dB of bass on a loud song distorts.
     */
    fun preampDb(gains: List<Float>): Float {
        val peak = maxOf(0.0, CURVE_FREQS.maxOf { responseDb(gains, it) })
        return -(peak * 10).roundToInt() / 10f
    }

    fun clamp(gain: Float) = ((gain * 2).roundToInt() / 2f).coerceIn(-MAX_DB, MAX_DB)

    fun same(a: List<Float>, b: List<Float>) = a.size == b.size && a.indices.all { abs(a[it] - b[it]) < 0.05f }
}

/** Where Settings lists a built-in profile. */
enum class EqGroup { Tuning, Genre }

@Serializable
data class EqProfile(
    val id: String,
    val name: String,
    val gains: List<Float>,
    /** Built-in ones: where Settings lists them. */
    val group: EqGroup? = null,
    /** Your own: when it last changed (the newest change wins when your devices sync). */
    val updatedAt: Long = 0,
    /** Your own, deleted: kept so the deletion reaches your other devices. */
    val deleted: Boolean = false,
) {
    val isOwn get() = id.startsWith("custom:")
}

/** One of your own profiles as the server keeps it (SoundProfiles.kt there). */
@Serializable
data class SoundProfileDto(val id: String, val name: String, val gains: List<Float>, val updatedAt: Long, val deleted: Boolean = false)

/** What the equalizer is set to. */
data class EqState(
    val enabled: Boolean = false,
    /** What's playing now (may be a profile you've changed and not saved). */
    val gains: List<Float> = Eq.FLAT,
    /** The profile last chosen or saved, or null. */
    val profileId: String? = Eq.BUILTIN.first().id,
    /** Your own profiles (deleted ones too, marked). */
    val custom: List<EqProfile> = emptyList(),
    /** Keep your own profiles the same on all your devices (through your account). */
    val sync: Boolean = true,
) {
    /** Your own profiles, without the deleted ones. */
    val own get() = custom.filter { !it.deleted }
    val profiles get() = Eq.BUILTIN + own
    val chosen get() = profiles.firstOrNull { it.id == profileId }
}

class SoundSettings(context: Context) {
    private val prefs = context.getSharedPreferences("sound", Context.MODE_PRIVATE)
    private val profilesSerializer = ListSerializer(EqProfile.serializer())

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<EqState> = _state

    private fun restore(): EqState {
        fun gains(s: String?) = s?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == Eq.BANDS.size }
        return EqState(
            enabled = prefs.getBoolean(ENABLED, false),
            gains = gains(prefs.getString(GAINS, null)) ?: Eq.FLAT,
            profileId = if (prefs.contains(PROFILE)) prefs.getString(PROFILE, null) else Eq.BUILTIN.first().id,
            custom = prefs.getString(CUSTOM, null)
                ?.let { runCatching { Json.decodeFromString(profilesSerializer, it) }.getOrNull() }
                ?.filter { it.gains.size == Eq.BANDS.size }
                .orEmpty(),
            sync = prefs.getBoolean(SYNC, true),
        )
    }

    /** Called when your own profiles change here, or sync is switched on (the app syncs them then). */
    var onProfilesChanged: () -> Unit = {}

    private fun update(change: (EqState) -> EqState) {
        val before = _state.value
        val s = change(before)
        _state.value = s
        prefs.edit {
            putBoolean(ENABLED, s.enabled)
            putString(GAINS, s.gains.joinToString(","))
            putString(PROFILE, s.profileId)
            putString(CUSTOM, Json.encodeToString(profilesSerializer, s.custom))
            putBoolean(SYNC, s.sync)
        }
        if (s.sync && (s.custom != before.custom || !before.sync)) onProfilesChanged()
    }

    fun setSync(on: Boolean) = update { it.copy(sync = on) }

    /** Your profiles as sent to the server. */
    fun toServer(): List<SoundProfileDto> =
        _state.value.custom.map { SoundProfileDto(it.id, it.name, it.gains, it.updatedAt, it.deleted) }

    /**
     * Takes in your profiles from the server: each one's newest change wins (the server's on a tie). If the profile
     * you chose was changed on another device and you hadn't changed its curve here, the new curve plays.
     */
    fun merge(server: List<SoundProfileDto>) {
        val now = _state.value
        val merged = LinkedHashMap(now.custom.associateBy { it.id })
        for (p in server) {
            val had = merged[p.id]
            if (had == null || p.updatedAt >= had.updatedAt) merged[p.id] = EqProfile(p.id, p.name, p.gains, updatedAt = p.updatedAt, deleted = p.deleted)
        }
        val custom = merged.values.toList()
        if (custom == now.custom) return
        val before = now.custom.firstOrNull { it.id == now.profileId }
        val after = custom.firstOrNull { it.id == now.profileId }
        val follow = before != null && after != null && !after.deleted && Eq.same(before.gains, now.gains) && !Eq.same(after.gains, now.gains)
        // Straight to the state (not update()): what came from the server isn't sent back.
        val s = now.copy(
            custom = custom,
            gains = if (follow) after.gains else now.gains,
            profileId = if (after?.deleted == true) null else now.profileId,
        )
        _state.value = s
        prefs.edit {
            putString(GAINS, s.gains.joinToString(","))
            putString(PROFILE, s.profileId)
            putString(CUSTOM, Json.encodeToString(profilesSerializer, s.custom))
        }
    }

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }

    /** Changes one band (dB); the equalizer turns on if it was off. */
    fun setBand(band: Int, gain: Float) = update {
        it.copy(gains = it.gains.toMutableList().also { g -> g[band] = Eq.clamp(gain) }, enabled = true)
    }

    /** Plays with a profile's curve (and turns the equalizer on). */
    fun choose(id: String) = update { s ->
        s.profiles.firstOrNull { it.id == id }?.let { s.copy(gains = it.gains, profileId = id, enabled = true) } ?: s
    }

    /** Saves the current curve as a new profile of yours, and chooses it. */
    fun saveAs(name: String) = update { s ->
        val now = System.currentTimeMillis()
        val p = EqProfile("custom:${now.toString(36)}${(0..9999).random().toString(36)}", name.trim(), s.gains, updatedAt = now)
        s.copy(custom = s.custom + p, profileId = p.id)
    }

    /** Saves the current curve over the chosen profile (only your own). */
    fun saveChosen() = update { s ->
        s.copy(custom = s.custom.map { if (it.id == s.profileId) it.copy(gains = s.gains, updatedAt = System.currentTimeMillis()) else it })
    }

    fun rename(id: String, name: String) = update { s ->
        s.copy(custom = s.custom.map { if (it.id == id) it.copy(name = name.trim(), updatedAt = System.currentTimeMillis()) else it })
    }

    /** Deletes one of your profiles; the curve that's playing stays. */
    fun delete(id: String) = update { s ->
        s.copy(
            custom = s.custom.map { if (it.id == id) it.copy(deleted = true, updatedAt = System.currentTimeMillis()) else it },
            profileId = s.profileId.takeIf { it != id },
        )
    }

    private companion object {
        const val ENABLED = "enabled"
        const val GAINS = "gains"
        const val PROFILE = "profile"
        const val CUSTOM = "custom"
        const val SYNC = "sync"
    }
}
