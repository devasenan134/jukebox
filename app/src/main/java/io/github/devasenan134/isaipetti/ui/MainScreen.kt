package io.github.devasenan134.isaipetti.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.toRoute
import io.github.devasenan134.isaipetti.PendingOpen
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.ui.home.HomeScreen
import io.github.devasenan134.isaipetti.ui.library.AlbumScreen
import io.github.devasenan134.isaipetti.ui.library.AlbumsScreen
import io.github.devasenan134.isaipetti.ui.library.ArtistScreen
import io.github.devasenan134.isaipetti.ui.library.ArtistsScreen
import io.github.devasenan134.isaipetti.ui.library.PlaylistScreen
import io.github.devasenan134.isaipetti.ui.player.MiniPlayer
import io.github.devasenan134.isaipetti.ui.player.PlayerScreen
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.search.SearchScreen
import io.github.devasenan134.isaipetti.ui.settings.SettingsScreen
import io.github.devasenan134.isaipetti.ui.settings.UpdateDialog
import io.github.devasenan134.isaipetti.ui.social.ChatScreen
import io.github.devasenan134.isaipetti.ui.social.SocialScreen
import kotlinx.serialization.Serializable

// Each screen is a "route". Routes with an id carry it along when navigating.
@Serializable object HomeRoute
@Serializable object AlbumsRoute
@Serializable object ArtistsRoute
@Serializable object SearchRoute
@Serializable data class AlbumRoute(val id: String)
@Serializable data class ArtistRoute(val id: String)
@Serializable data class PlaylistRoute(val id: String)
@Serializable object SocialRoute
@Serializable data class ChatRoute(val id: Long)
@Serializable object SettingsRoute

/** Navigation actions that screens can call. */
class Nav(
    val openAlbum: (String) -> Unit,
    val openArtist: (String) -> Unit,
    val openPlaylist: (String) -> Unit,
    val openChat: (Long) -> Unit,
    val openSettings: () -> Unit,
    val back: () -> Unit,
)

private data class Tab(val label: String, val route: Any, val icon: @Composable () -> Painter)

private val tabs = listOf(
    Tab("Home", HomeRoute) { rememberVectorPainter(Icons.Filled.Home) },
    Tab("Movies", AlbumsRoute) { painterResource(R.drawable.ic_album) },
    Tab("Composers", ArtistsRoute) { rememberVectorPainter(Icons.Filled.Person) },
    Tab("Search", SearchRoute) { rememberVectorPainter(Icons.Filled.Search) },
    Tab("Friends", SocialRoute) { painterResource(R.drawable.ic_group) },
)

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    // Which bottom tab you're in. Screens opened from a tab (a movie, a chat, settings) stay in that tab.
    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    val nav = Nav(
        openAlbum = { navController.navigate(AlbumRoute(it)) },
        openArtist = { navController.navigate(ArtistRoute(it)) },
        openPlaylist = { navController.navigate(PlaylistRoute(it)) },
        openChat = { navController.navigate(ChatRoute(it)) },
        openSettings = { navController.navigate(SettingsRoute) },
        back = { navController.popBackStack() },
    )

    // Open the chat or Friends tab from a tapped notification.
    val app = LocalApp.current
    val pending by app.pendingOpen.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val target = pending ?: return@LaunchedEffect
        app.pendingOpen.value = null
        playerOpen = false
        currentTab = tabs.indexOfFirst { it.route == SocialRoute }
        navController.navigate(SocialRoute) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
        }
        if (target is PendingOpen.Chat) nav.openChat(target.conversationId)
    }

    // A newer version on GitHub: offer it once (until "Later"; it's always in Settings too).
    val update by app.updates.available.collectAsStateWithLifecycle()
    var updateClosed by rememberSaveable { mutableStateOf(false) }
    update?.takeIf { !updateClosed && !app.updates.isDismissed(it) }?.let {
        UpdateDialog(it, onDismiss = { updateClosed = true })
    }

    // Android 13+ needs permission to show notifications. Ask once, when the app first opens.
    if (Build.VERSION.SDK_INT >= 33) {
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                Column {
                    MiniPlayer(onOpen = { playerOpen = true })
                    // Unread chats + friend requests show as a badge on the Friends tab.
                    val social = LocalApp.current.social
                    val conversations by social.conversations.collectAsStateWithLifecycle()
                    val requests by social.requests.collectAsStateWithLifecycle()
                    val friendsBadge = conversations.sumOf { it.unread } + requests.incoming.size
                    NavigationBar {
                        tabs.forEachIndexed { index, tab ->
                            val selected = index == currentTab
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    // Tapping the tab you're on goes back to its main screen.
                                    if (selected) {
                                        navController.popBackStack(tab.route, inclusive = false)
                                        return@NavigationBarItem
                                    }
                                    // Standard bottom-tab behaviour: one copy of each tab, keep its state.
                                    currentTab = index
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    if (tab.route == SocialRoute && friendsBadge > 0) {
                                        BadgedBox(badge = { Badge { Text("$friendsBadge") } }) { Icon(tab.icon(), contentDescription = null) }
                                    } else {
                                        Icon(tab.icon(), contentDescription = null)
                                    }
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            // Material "shared axis" transition, as in most Android apps: the old screen fades out quickly
            // while the new one fades in and moves a short way sideways. Android's back gesture plays the
            // same motion in reverse, following the finger. Switching tabs just fades.
            NavHost(
                navController,
                startDestination = HomeRoute,
                modifier = Modifier.padding(padding),
                enterTransition = { if (targetState.isTab()) fadeIn(tween(200)) else sharedAxisIn(forward = true) },
                exitTransition = { if (targetState.isTab()) fadeOut(tween(200)) else sharedAxisOut(forward = true) },
                popEnterTransition = { sharedAxisIn(forward = false) },
                popExitTransition = { sharedAxisOut(forward = false) },
                // Without these, a slow back swipe uses the library's own "shrink the page" animation instead.
                predictivePopEnterTransition = { sharedAxisIn(forward = false) },
                predictivePopExitTransition = { sharedAxisOut(forward = false) },
            ) {
                screen<HomeRoute> { HomeScreen(nav) }
                screen<AlbumsRoute> { AlbumsScreen(nav) }
                screen<ArtistsRoute> { ArtistsScreen(nav) }
                screen<SearchRoute> { SearchScreen(nav) }
                screen<AlbumRoute> { AlbumScreen(it.toRoute<AlbumRoute>().id, nav) }
                screen<ArtistRoute> { ArtistScreen(it.toRoute<ArtistRoute>().id, nav) }
                screen<PlaylistRoute> { PlaylistScreen(it.toRoute<PlaylistRoute>().id, nav) }
                screen<SocialRoute> { SocialScreen(nav) }
                screen<ChatRoute> { ChatScreen(it.toRoute<ChatRoute>().id, nav) }
                screen<SettingsRoute> { SettingsScreen(nav) }
            }
        }

        // The full-screen player slides up over everything.
        AnimatedVisibility(
            visible = playerOpen,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            PlayerScreen(
                onClose = { playerOpen = false },
                onOpenAlbum = {
                    playerOpen = false
                    nav.openAlbum(it)
                },
            )
        }
        BackHandler(enabled = playerOpen) { playerOpen = false }
    }
}

private const val AXIS_MS = 300
private const val AXIS_FADE_OUT_MS = 90

private fun sharedAxisIn(forward: Boolean): EnterTransition =
    slideInHorizontally(tween(AXIS_MS, easing = FastOutSlowInEasing)) { if (forward) it / 10 else -it / 10 } +
        fadeIn(tween(AXIS_MS - AXIS_FADE_OUT_MS, delayMillis = AXIS_FADE_OUT_MS, easing = LinearOutSlowInEasing))

private fun sharedAxisOut(forward: Boolean): ExitTransition =
    slideOutHorizontally(tween(AXIS_MS, easing = FastOutSlowInEasing)) { if (forward) -it / 10 else it / 10 } +
        fadeOut(tween(AXIS_FADE_OUT_MS, easing = FastOutLinearInEasing))

private fun NavBackStackEntry.isTab() = tabs.any { destination.hasRoute(it.route::class) }

/** A destination with a solid background, so screens don't show through each other while sliding. */
private inline fun <reified T : Any> NavGraphBuilder.screen(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable<T> {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content(it) }
}
