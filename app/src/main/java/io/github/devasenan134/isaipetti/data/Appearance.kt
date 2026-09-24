package io.github.devasenan134.isaipetti.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Light, dark, or whatever the phone is set to. */
enum class ThemeMode(val label: String) { System("Auto"), Light("Light"), Dark("Dark") }

/** How the app looks, chosen in Settings and kept on the phone. */
class Appearance(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(
        prefs.getString(MODE, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
    )
    val mode: StateFlow<ThemeMode> = _mode

    private val _wallpaper = MutableStateFlow(prefs.getBoolean(WALLPAPER, false))
    /** Use the colours Android picks from the wallpaper ("Material You") instead of Graphite & mango. */
    val wallpaper: StateFlow<Boolean> = _wallpaper

    fun setMode(mode: ThemeMode) {
        _mode.value = mode
        prefs.edit { putString(MODE, mode.name) }
    }

    fun setWallpaper(on: Boolean) {
        _wallpaper.value = on
        prefs.edit { putBoolean(WALLPAPER, on) }
    }

    companion object {
        private const val MODE = "mode"
        private const val WALLPAPER = "wallpaper"

        /** Wallpaper colours exist from Android 12 on. */
        val wallpaperSupported get() = Build.VERSION.SDK_INT >= 31
    }
}
