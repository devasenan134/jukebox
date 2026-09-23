package io.github.devasenan134.isaipetti.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Data that is being fetched from the server: still loading, failed, or ready. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Failed(val message: String) : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
}

/**
 * Loads data once and keeps it while the screen is in the back stack,
 * so going back to a screen doesn't reload it or lose the scroll position.
 */
class LoadViewModel<T>(private val loader: suspend () -> T) : ViewModel() {
    var state by mutableStateOf<Loadable<T>>(Loadable.Loading)
        private set

    init {
        reload()
    }

    fun reload() {
        state = Loadable.Loading
        viewModelScope.launch {
            state = try {
                Loadable.Ready(loader())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loadable.Failed(e.message ?: "Something went wrong")
            }
        }
    }
}

@Composable
fun <T> rememberLoader(key: String, loader: suspend () -> T): LoadViewModel<T> =
    viewModel(key = key) { LoadViewModel(loader) }

/** Shows a spinner, an error with Retry, or [content] once the data is ready. */
@Composable
fun <T> LoadableContent(loader: LoadViewModel<T>, content: @Composable (T) -> Unit) {
    when (val state = loader.state) {
        Loadable.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is Loadable.Failed -> ErrorMessage(state.message, onRetry = loader::reload)
        is Loadable.Ready -> content(state.value)
    }
}

@Composable
fun ErrorMessage(message: String, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            if (onRetry != null) Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
