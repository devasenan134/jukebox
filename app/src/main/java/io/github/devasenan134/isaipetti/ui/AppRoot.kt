package io.github.devasenan134.isaipetti.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.IsaipettiApp
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.login.LoginScreen

/** Shows the login screen until there are saved credentials, then the main app. */
@Composable
fun AppRoot(app: IsaipettiApp) {
    val credentials by app.session.credentials.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalApp provides app) {
        if (credentials == null) LoginScreen() else MainScreen()
    }
}
