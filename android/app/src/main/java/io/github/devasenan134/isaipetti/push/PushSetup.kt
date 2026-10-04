package io.github.devasenan134.isaipetti.push

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import io.github.devasenan134.isaipetti.data.PushConfig
import kotlinx.serialization.json.Json

/**
 * Starts Firebase (push notifications) with the settings of the friends server you logged in to.
 * Nothing about any Firebase project is built into the app: each friends server has its own and
 * hands its settings to the app. They're saved, so a notification that wakes the app works too.
 */
object PushSetup {
    private const val TAG = "PushSetup"
    private const val PREFS = "push"
    private const val CONFIG = "config"

    /** At app start: use the settings saved last time, if any. */
    fun startSaved(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CONFIG, null) ?: return
        runCatching { init(context, Json.decodeFromString(PushConfig.serializer(), saved)) }
            .onFailure { Log.w(TAG, "Couldn't start push notifications: ${it.message}") }
    }

    /** After login: start Firebase with this server's settings (and remember them). */
    fun start(context: Context, config: PushConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(CONFIG, Json.encodeToString(PushConfig.serializer(), config))
        }
        init(context, config)
    }

    /** On logout: the next server may use a different project. */
    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(CONFIG) }
    }

    private fun init(context: Context, config: PushConfig) {
        val options = FirebaseOptions.Builder()
            .setProjectId(config.projectId)
            .setApplicationId(config.appId)
            .setApiKey(config.apiKey)
            .setGcmSenderId(config.senderId)
            .build()
        val current = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        if (current != null) {
            if (current.options == options) return
            current.delete() // switched to a server with another project
        }
        FirebaseApp.initializeApp(context, options)
    }
}
