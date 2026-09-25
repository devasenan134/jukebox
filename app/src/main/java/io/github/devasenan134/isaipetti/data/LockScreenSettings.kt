package io.github.devasenan134.isaipetti.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lyrics over the lock screen, chosen in Settings and kept on the phone. */
class LockScreenSettings(context: Context) {
    private val prefs = context.getSharedPreferences("lockscreen", Context.MODE_PRIVATE)

    private val _lyrics = MutableStateFlow(prefs.getBoolean(LYRICS, false))
    /** Show the lyrics page over the lock screen when the phone is locked while music plays. */
    val lyrics: StateFlow<Boolean> = _lyrics

    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean(KEEP_ON, true))
    /** Keep the screen on while that page shows and music plays. */
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn

    fun setLyrics(on: Boolean) {
        _lyrics.value = on
        prefs.edit { putBoolean(LYRICS, on) }
    }

    fun setKeepScreenOn(on: Boolean) {
        _keepScreenOn.value = on
        prefs.edit { putBoolean(KEEP_ON, on) }
    }

    private companion object {
        const val LYRICS = "lyrics"
        const val KEEP_ON = "keepScreenOn"
    }
}
