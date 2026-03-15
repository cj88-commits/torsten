package com.recordcollection.app.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.recordcollection.app.data.datastore.ServerConfigStore
import com.recordcollection.app.ui.albumdetail.AlbumDetailScreen
import com.recordcollection.app.ui.albumdetail.AlbumDetailViewModel
import com.recordcollection.app.ui.albumdetail.AlbumDetailViewModelFactory
import com.recordcollection.app.ui.albums.AlbumGridScreen
import com.recordcollection.app.ui.albums.AlbumGridViewModel
import com.recordcollection.app.ui.albums.AlbumGridViewModelFactory
import com.recordcollection.app.ui.random.RandomScreen
import com.recordcollection.app.ui.random.RandomViewModel
import com.recordcollection.app.ui.random.RandomViewModelFactory
import com.recordcollection.app.ui.artistdetail.ArtistDetailScreen
import com.recordcollection.app.ui.artistdetail.ArtistDetailViewModel
import com.recordcollection.app.ui.artistdetail.ArtistDetailViewModelFactory
import com.recordcollection.app.ui.artists.ArtistListScreen
import com.recordcollection.app.ui.artists.ArtistListViewModel
import com.recordcollection.app.ui.artists.ArtistListViewModelFactory
import com.recordcollection.app.ui.common.DarkBackground
import com.recordcollection.app.ui.common.MiniPlayer
import com.recordcollection.app.ui.nowplaying.NowPlayingScreen
import com.recordcollection.app.ui.playback.PlaybackViewModel
import com.recordcollection.app.ui.playback.PlaybackViewModelFactory
import com.recordcollection.app.ui.settings.ServerConfigScreen
import com.recordcollection.app.ui.settings.SettingsScreen
import com.recordcollection.app.ui.settings.SettingsViewModel
import com.recordcollection.app.ui.settings.SettingsViewModelFactory
import kotlinx.coroutines.flow.first

// Routes for which the bottom navigation bar is visible
private val tabRoutes = setOf(Screen.Albums.route, Screen.Random.route, Screen.Artists.route)

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val serverConfigStore = ServerConfigStore(context)

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in tabRoutes

    // Callback updated by the Random composable; invoked when the tab is re-tapped
    var randomReselectCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Single PlaybackViewModel shared across the whole nav graph
    val playbackViewModel: PlaybackViewModel = viewModel(
        factory = PlaybackViewModelFactory(context),
    )
    val playbackState by playbackViewModel.state.collectAsStateWithLifecycle()
    val isOnline by playbackViewModel.isOnline.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            Column {
                MiniPlayer(
                    state = playbackState,
                    isOnline = isOnline,
                    onPlayPause = playbackViewModel::playPause,
                    onTap = {
                        if (currentRoute != Screen.NowPlaying.route) {
                            navController.navigate(Screen.NowPlaying.route)
                        }
                    },
                )
                if (showBottomBar) {
                    AppBottomBar(
                        currentRoute = currentRoute,
                        navController = navController,
                        onRandomReselect = { randomReselectCallback?.invoke() },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Settings.route,
            // Only apply bottom padding here; each screen's Scaffold handles its own top (status bar) insets.
            // Applying the full innerPadding causes double status-bar padding on all screens.
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            composable(Screen.Settings.route) { settingsEntry ->
                val vm: SettingsViewModel = viewModel(settingsEntry, factory = SettingsViewModelFactory(context))
                val isFirstLaunch = navController.previousBackStackEntry == null

                if (isFirstLaunch) {
                    LaunchedEffect(Unit) {
                        if (serverConfigStore.serverConfig.first().isConfigured) {
                            navController.navigate(Screen.Albums.route) {
                                popUpTo(Screen.Settings.route) { inclusive = true }
                            }
                        }
                    }
                }

                SettingsScreen(
                    viewModel = vm,
                    onNavigateUp = if (isFirstLaunch) null else ({ navController.navigateUp() }),
                    onNavigateToServerConfig = {
                        navController.navigate(Screen.ServerConfig.createRoute(isFirstLaunch))
                    },
                )
            }

            composable(
                route = Screen.ServerConfig.route,
                arguments = listOf(navArgument("firstLaunch") {
                    type = NavType.BoolType
                    defaultValue = false
                }),
            ) { backStackEntry ->
                val isFirstLaunch = backStackEntry.arguments?.getBoolean("firstLaunch") ?: false
                // Remember the Settings entry once — calling getBackStackEntry during the exit
                // animation (after Settings is popped) throws IllegalArgumentException.
                val settingsEntry = remember(navController) {
                    navController.getBackStackEntry(Screen.Settings.route)
                }
                val vm: SettingsViewModel = viewModel(settingsEntry, factory = SettingsViewModelFactory(context))

                // Collect navigateToGrid here (not inside ServerConfigScreen) so the navigation
                // call is issued from the NavHost composable scope, which is safer.
                LaunchedEffect(vm) {
                    vm.navigateToGrid.collect {
                        val popped = navController.popBackStack(Screen.Albums.route, inclusive = false)
                        if (!popped) {
                            navController.navigate(Screen.Albums.route) {
                                popUpTo(Screen.Settings.route) { inclusive = true }
                            }
                        }
                    }
                }

                ServerConfigScreen(
                    viewModel = vm,
                    isFirstLaunch = isFirstLaunch,
                    onNavigateUp = { navController.navigateUp() },
                )
            }

            composable(Screen.Albums.route) {
                val vm: AlbumGridViewModel = viewModel(
                    factory = AlbumGridViewModelFactory(context),
                )
                AlbumGridScreen(
                    viewModel = vm,
                    onOpenSettings = { navController.navigate(Screen.Settings.route) },
                    onAlbumClick = { albumId, _ ->
                        navController.navigate(Screen.AlbumDetail.createRoute(albumId))
                    },
                )
            }

            composable(Screen.Random.route) {
                val vm: RandomViewModel = viewModel(factory = RandomViewModelFactory(context))
                LaunchedEffect(vm) { randomReselectCallback = vm::shuffle }
                RandomScreen(
                    viewModel = vm,
                    onAlbumClick = { albumId, _ ->
                        navController.navigate(Screen.AlbumDetail.createRoute(albumId))
                    },
                )
            }

            composable(Screen.Artists.route) {
                val vm: ArtistListViewModel = viewModel(factory = ArtistListViewModelFactory(context))
                ArtistListScreen(
                    viewModel = vm,
                    onArtistClick = { artistId ->
                        navController.navigate(Screen.ArtistDetail.createRoute(artistId))
                    },
                )
            }

            composable(
                route = Screen.AlbumDetail.route,
                arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getString("albumId").orEmpty()
                val vm: AlbumDetailViewModel = viewModel(factory = AlbumDetailViewModelFactory(context, albumId))
                AlbumDetailScreen(
                    viewModel = vm,
                    playbackViewModel = playbackViewModel,
                    onNavigateUp = { navController.navigateUp() },
                    onNavigateToArtist = { artistId ->
                        navController.navigate(Screen.ArtistDetail.createRoute(artistId))
                    },
                )
            }

            composable(
                route = Screen.ArtistDetail.route,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val artistId = backStackEntry.arguments?.getString("artistId").orEmpty()
                val vm: ArtistDetailViewModel = viewModel(factory = ArtistDetailViewModelFactory(context, artistId))
                ArtistDetailScreen(
                    viewModel = vm,
                    onAlbumClick = { albumId, _ ->
                        navController.navigate(Screen.AlbumDetail.createRoute(albumId))
                    },
                    onNavigateUp = { navController.navigateUp() },
                )
            }

            composable(Screen.NowPlaying.route) {
                NowPlayingScreen(
                    playbackViewModel = playbackViewModel,
                    isOnline = isOnline,
                    onNavigateUp = { navController.navigateUp() },
                    onNavigateToArtist = { artistId ->
                        navController.navigate(Screen.ArtistDetail.createRoute(artistId))
                    },
                    onNavigateToAlbum = { albumId ->
                        // Pop NowPlaying first, then navigate to album detail
                        navController.popBackStack()
                        navController.navigate(Screen.AlbumDetail.createRoute(albumId))
                    },
                )
            }
        }
    }
}

// ─── Bottom navigation bar ────────────────────────────────────────────────────

@Composable
private fun AppBottomBar(
    currentRoute: String?,
    navController: NavController,
    onRandomReselect: () -> Unit,
) {
    NavigationBar(containerColor = Color(0xFF0F0F1F)) {
        NavigationBarItem(
            selected = currentRoute == Screen.Albums.route,
            onClick = {
                navController.navigate(Screen.Albums.route) {
                    popUpTo(Screen.Albums.route) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            icon = { Icon(Icons.Filled.Home, contentDescription = "Albums") },
            label = { Text("Albums") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color.White,
                unselectedIconColor = Color.White.copy(alpha = 0.5f),
                unselectedTextColor = Color.White.copy(alpha = 0.5f),
                indicatorColor = Color(0xFF2D2D4E),
            ),
        )
        NavigationBarItem(
            selected = currentRoute == Screen.Random.route,
            onClick = {
                if (currentRoute == Screen.Random.route) {
                    onRandomReselect()
                } else {
                    navController.navigate(Screen.Random.route) {
                        popUpTo(Screen.Albums.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            icon = { Icon(Icons.Filled.Shuffle, contentDescription = "Random") },
            label = { Text("Random") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color.White,
                unselectedIconColor = Color.White.copy(alpha = 0.5f),
                unselectedTextColor = Color.White.copy(alpha = 0.5f),
                indicatorColor = Color(0xFF2D2D4E),
            ),
        )
        NavigationBarItem(
            selected = currentRoute == Screen.Artists.route,
            onClick = {
                navController.navigate(Screen.Artists.route) {
                    popUpTo(Screen.Albums.route) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            icon = { Icon(Icons.Filled.Person, contentDescription = "Artists") },
            label = { Text("Artists") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color.White,
                unselectedIconColor = Color.White.copy(alpha = 0.5f),
                unselectedTextColor = Color.White.copy(alpha = 0.5f),
                indicatorColor = Color(0xFF2D2D4E),
            ),
        )
    }
}
