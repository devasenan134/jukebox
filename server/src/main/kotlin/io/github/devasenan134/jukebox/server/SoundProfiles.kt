package io.github.devasenan134.jukebox.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One of someone's own equalizer profiles (Settings › Sound in the apps): ten band gains in dB. A deleted one is
 * kept as [deleted] so the deletion reaches their other devices instead of the profile coming back from one.
 */
@Serializable
data class SoundProfileDto(
    val id: String,
    val name: String,
    val gains: List<Float>,
    /** When it was last changed, on the device that changed it. The newest change wins. */
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** One of someone's equalizer profiles changed on one of their devices: the others sync again. */
@Serializable @SerialName("soundProfilesChanged")
data object SoundProfilesChangedEvent : Event

/**
 * Each person's equalizer profiles, the same on their phone, the website and the Mac app. Only the profiles are
 * kept here: whether the equalizer is on, and which curve is playing, stay with each device (the Mac's speakers
 * and the phone's IEMs want different ones).
 *
 * A device sends all of its profiles and gets everyone's back: for each profile, the newest change wins, so
 * changes made on two devices while one was offline both survive.
 */
class SoundProfiles(private val db: Db, private val hub: Hub) {
    private val json = Json

    suspend fun list(user: UserDto): List<SoundProfileDto> = db.read {
        query("SELECT id, name, gains, updated_at, deleted FROM sound_profiles WHERE user_id = ? ORDER BY rowid", user.id) { rs ->
            SoundProfileDto(rs.getString(1), rs.getString(2), json.decodeFromString(rs.getString(3)), rs.getLong(4), rs.getInt(5) == 1)
        }
    }

    /** Takes in a device's profiles (newer changes replace older ones) and returns all of them. */
    suspend fun sync(user: UserDto, profiles: List<SoundProfileDto>): List<SoundProfileDto> {
        if (profiles.size > MAX) throw ApiError(HttpStatusCode.BadRequest, "Too many profiles")
        profiles.forEach(::check)
        val changed = db.tx {
            val stored = query("SELECT id, updated_at FROM sound_profiles WHERE user_id = ?", user.id) { it.getString(1) to it.getLong(2) }.toMap()
            val newer = profiles.filter { p -> stored[p.id].let { it == null || p.updatedAt > it } }
            if (stored.size + newer.count { it.id !in stored } > MAX) throw ApiError(HttpStatusCode.BadRequest, "Too many profiles")
            for (p in newer) {
                update(
                    """INSERT INTO sound_profiles (user_id, id, name, gains, updated_at, deleted) VALUES (?, ?, ?, ?, ?, ?)
                       ON CONFLICT (user_id, id) DO UPDATE SET name = excluded.name, gains = excluded.gains,
                       updated_at = excluded.updated_at, deleted = excluded.deleted""",
                    user.id, p.id, p.name.trim(), json.encodeToString(p.gains), p.updatedAt, if (p.deleted) 1 else 0,
                )
            }
            newer.isNotEmpty()
        }
        if (changed) hub.send(listOf(user.id), SoundProfilesChangedEvent)
        return list(user)
    }

    private fun check(p: SoundProfileDto) {
        val ok = p.id.startsWith("custom:") && p.id.length <= 64 &&
            (p.deleted || p.name.isNotBlank()) && p.name.length <= 40 &&
            p.gains.size == BANDS && p.gains.all { it.isFinite() && it in -MAX_DB..MAX_DB }
        if (!ok) throw ApiError(HttpStatusCode.BadRequest, "That isn't an equalizer profile")
    }

    private companion object {
        const val BANDS = 10
        const val MAX_DB = 12f
        /** Profiles (deleted ones included) kept per person. */
        const val MAX = 200
    }
}
