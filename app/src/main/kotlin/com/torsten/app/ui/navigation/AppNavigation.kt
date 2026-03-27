package com.torsten.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.torsten.app.data.api.SubsonicApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.ui.albumdetail.AlbumDetailScreen
import com.torsten.app.ui.albumdetail.AlbumDetailViewModel
import com.torsten.app.ui.albumdetail.AlbumDetailViewModelFactory
import com.torsten.app.ui.albums.AlbumGridScreen
import com.torsten.app.ui.albums.AlbumGridViewModel
import com.torsten.app.ui.albums.AlbumGridViewModelFactory
import com.torsten.app.ui.artistdetail.ArtistDetailScreen
import com.torsten.app.ui.artistdetail.ArtistDetailViewModel
import com.torsten.app.ui.artistdetail.ArtistDetailViewModelFactory
import com.torsten.app.ui.artists.ArtistListScreen
import com.torsten.app.ui.artists.ArtistListViewModel
import com.torsten.app.ui.artists.ArtistListViewModelFactory
import com.torsten.app.ui.common.DarkBackground
import com.torsten.app.ui.common.MiniPlayer
import com.torsten.app.ui.albumlist.AlbumListScreen
import com.torsten.app.ui.albumlist.AlbumListViewModel
import com.torsten.app.ui.albumlist.AlbumListViewModelFactory
import com.torsten.app.ui.genre.GenreScreen
import com.torsten.app.ui.genre.GenreViewModel
import com.torsten.app.ui.genre.GenreViewModelFactory
import com.torsten.app.ui.home.HomeScreen
import com.torsten.app.ui.home.HomeViewModel
import com.torsten.app.ui.home.HomeViewModelFactory
import com.torsten.app.ui.playlists.PlaylistDetailScreen
import com.torsten.app.ui.playlists.PlaylistDetailViewModel
import com.torsten.app.ui.playlists.PlaylistDetailViewModelFactory
import com.torsten.app.ui.playlists.PlaylistsScreen
import com.torsten.app.ui.playlists.PlaylistsViewModel
import com.torsten.app.ui.playlists.PlaylistsViewModelFactory
import com.torsten.app.ui.search.SearchViewModel
import com.torsten.app.ui.search.SearchViewModelFactory
import com.torsten.app.ui.nowplaying.NowPlayingScreen
import com.torsten.app.ui.playback.PlaybackViewModel
import com.torsten.app.ui.playback.PlaybackViewModelFactory
import com.torsten.app.ui.downloads.DownloadsScreen
import com.torsten.app.ui.downloads.DownloadsViewModel
import com.torsten.app.ui.downloads.DownloadsViewModelFactory
import com.torsten.app.ui.queue.QueueScreen
import com.torsten.app.ui.random.RandomScreen
import com.torsten.app.ui.random.RandomViewModel
import com.torsten.app.ui.random.RandomViewModelFactory
import com.torsten.app.ui.search.SearchScreen
import com.torsten.app.ui.settings.ServerConfigScreen
import com.torsten.app.ui.settings.SettingsScreen
import com.torsten.app.ui.settings.SettingsViewModel
import com.torsten.app.ui.settings.SettingsViewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Routes where the bottom navigation bar is shown
private val tabRoutes = setOf(
    Screen.Home.route,
    Screen.Search.route,
    // Library section — Albums is the root; Random, Artists, Playlists are part of the Library flow
    Screen.Albums.route,
    Screen.Random.route,
    Screen.Artists.route,
    Screen.Playlists.route,
    Screen.Downloads.route,
)

// Routes that belong to the Library tab (so Library tab stays highlighted)
private val libraryRoutes = setOf(Screen.Albums.route, Screen.Random.route, Screen.Artists.route, Screen.Playlists.route)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val serverConfigStore = ServerConfigStore(context)

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in tabRoutes

    // Set to true by the Startup check when the server is unreachable/unconfigured
    var pendingConnectSnackbar by remember { mutableStateOf(false) }

    // Callback updated by the Random composable; invoked when the Library tab is re-tapped while on Random
    var randomReselectCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Single PlaybackViewModel shared across the whole nav graph
    val playbackViewModel: PlaybackViewModel = viewModel(
        factory = PlaybackViewModelFactory(context),
    )
    val playbackState by playbackViewModel.state.collectAsStateWithLifecycle()
    val isOnline by playbackViewModel.isOnline.collectAsStateWithLifecycle()

    // Shared PlaylistsViewModel scoped to the navigation graph
    val playlistsViewModel: PlaylistsViewModel = viewModel(factory = PlaylistsViewModelFactory(context))
    val playlistsState by playlistsViewModel.state.collectAsStateWithLifecycle()

    // Playlist picker state — set to a song ID to show the picker sheet
    var playlistPickerSongId by remember { mutableStateOf<String?>(null) }
    val appScope = rememberCoroutineScope()

    // Show playlist picker sheet
    if (playlistPickerSongId != null) {
        PlaylistPickerSheet(
            playlists = playlistsState.playlists,
            onDismiss = { playlistPickerSongId = null },
            onPick = { playlistId ->
                val songId = playlistPickerSongId ?: return@PlaylistPickerSheet
                playlistPickerSongId = null
                playlistsViewModel.addTrackToPlaylist(playlistId, songId)
            },
        )
    }

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
            startDestination = Screen.Startup.route,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
            // Default: fade for tab-to-tab transitions
            enterTransition = { fadeIn(animationSpec = tween(250)) },
            exitTransition = { fadeOut(animationSpec = tween(200)) },
            popEnterTransition = { fadeIn(animationSpec = tween(250)) },
            popExitTransition = { fadeOut(animationSpec = tween(200)) },
        ) {
            // ── Startup check ─────────────────────────────────────────────────
            composable(Screen.Startup.route) {
                LaunchedEffect(Unit) {
                    val config = serverConfigStore.serverConfig.first()
                    if (config.isConfigured) {
                        val reachable = withTimeoutOrNull(3_000L) {
                            withContext(Dispatchers.IO) {
                                runCatching { SubsonicApiClient(config).ping() }.isSuccess
                            }
                        } ?: false

                        if (reachable) {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Startup.route) { inclusive = true }
                            }
                        } else {
                            pendingConnectSnackbar = true
                            navController.navigate(Screen.Settings.route) {
                                popUpTo(Screen.Startup.route) { inclusive = true }
                            }
                        }
                    } else {
                        pendingConnectSnackbar = true
                        navController.navigate(Screen.Settings.route) {
                            popUpTo(Screen.Startup.route) { inclusive = true }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0A0A)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
                }
            }

            composable(Screen.Home.route) {
                val vm: HomeViewModel = viewModel(factory = HomeViewModelFactory(context))
                HomeScreen(
                    viewModel = vm,
                    onAlbumClick = { albumId, title ->
                        navController.navigate(Screen.AlbumDetail.createRoute(albumId, title))
                    },
                    onGenreClick = { genre ->
                        navController.navigate(Screen.Genre.createRoute(genre))
                    },
                    onSeeAll = { listType, title ->
                        navController.navigate(Screen.AlbumList.createRoute(listType, title))
                    },
                    onSettingsClick = {
                        navController.navigate(Screen.Settings.route) {
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Screen.Search.route) {
                val vm: SearchViewModel = viewModel(factory = SearchViewModelFactory(context))
                SearchScreen(
                    viewModel         = vm,
                    playbackViewModel = playbackViewModel,
                    onAlbumClick      = { albumId, title ->
                        navController.navigate(Screen.AlbumDetail.createRoute(albumId, title))
                    },
                    onArtistClick     = { artistId ->
                        navController.navigate(Screen.ArtistDetail.createRoute(artistId))
                    },
                    onAddToPlaylist   = { songId -> playlistPickerSongId = songId },
                    onGenreClick      = { genre ->
                        navController.navigate(Screen.Genre.createRoute(genre))
                    },
                    onStartInstantMix = { seed ->
                        if (currentRoute != Screen.NowPlaying.route) {
                            navController.navigate(Screen.NowPlaying.route)
                        }
                        appScope.launch {
                            val config = serverConfigStore.serverConfig.first()
                            playbackViewModel.startInstantMix(seed, config)
                        }
                    },
                )
            }

            composable(
                Screen.Queue.route,
                enterTransition = { slideInVertically(initialOffsetY = { it }, animationSpec = tween(280)) },
                exitTransition = { slideOutVertically(targetOffsetY = { it }, animationSpec = tween(240)) },
                popEnterTransition = { slideInVertically(initialOffsetY = { it }, animationSpec = tween(280)) },
                popExitTransition = { slideOutVertically(targetOffsetY = { it }, animationSpec = tween(240)) },
            ) {
                QueueScreen(
                    playbackViewModel = playbackViewModel,
                    navController = navController,
                    onNavigateToLibrary = {
                        navController.navigate(Screen.Albums.route) {
                            popUpTo(Screen.Home.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }

            composable(Screen.Downloads.route) {
                val vm: DownloadsViewModel = viewModel(factory = DownloadsViewModelFactory(context))
                DownloadsScreen(
                    viewModel = vm,
                    isOnline = isOnline,
                    onAlbumClick = { albumId, title ->
                        navController.navigate(Screen.AlbumDetail.createRoute(albumId, title))
                    },
                    onPlaylistClick = { playlistId, name ->
                        navController.navigate(Screen.PlaylistDetail.createRoute(playlistId, name))
                    },
                    onBrowseLibrary = {
                        navController.navigate(Screen.Albums.route) {
                            popUpTo(Screen.Home.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }

            composable(Screen.Settings.route) { settingsEntry ->
                val vm: SettingsViewModel = viewModel(settingsEntry, factory = SettingsViewModelFactory(context))
                val isFirstLaunch = navController.previousBackStackEntry == null

                // Show "Connect to your server" snackbar when arriving from a failed startup check
                LaunchedEffect(pendingConnectSnackbar) {
                    if (pendingConnectSnackbar) {
                        pendingConnectSnackbar = false
                        vm.showConnectSnackbar()
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
                        val popped = navController.popBackStack(Screen.Home.route, inclusive = false)
                        if (!popped) {
                            navController.navigate(Screen.Home.route) {
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

            // ── Library tab screens ───────────────────────────────────────────

            composable(Screen.Albums.route) {
                val vm: AlbumGridViewModel = viewModel(
                    factory = AlbumGridViewModelFactory(context),
                )
                AlbumGridScreen(
                    viewModel = vm,
                    onAlbumClick = { albumId, title ->
                        navController.navigate(Screen.AlbumDetail.createRoute(albumId, title))
                    },
                    onNavigateToArtists = {
                        navController.navigate(Screen.Artists.route) { launchSingleTop = true }
                    },
                    onNavigateToPlaylists = {
                        navController.navigate(Screen.Playlists.route) { launchSingleTop = true }
                    },
                )
            }

            composable(Screen.Random.route) {
                val vm: RandomViewModel = viewModel(factory = RandomViewModelFactory(context))
                LaunchedEffect(vm) { randomReselectCallback = vm::shuffle }
                RandomScreen(
                    viewModel = vm,
                    onAlbumClick = { albumId, title ->
                        navController.navigate(Screen.AlbumDetail.createRoute(albumId, title))
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
                    onNavigateToAlbums = {
                        navController.navigate(Screen.Albums.route) {
                            popUpTo(Screen.Albums.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onNavigateToPlaylists = {
                        navController.navigate(Screen.Playlists.route) { launchSingleTop = true }
                    },
                )
            }

            composable(Screen.Playlists.route) {
                PlaylistsScreen(
                    viewModel = playlistsViewModel,
                    onPlaylistClick = { id, name ->
                        navController.navigate(Screen.PlaylistDetail.createRoute(id, name))
                    },
                    onNavigateToAlbums = {
                        navController.navigate(Screen.Albums.route) {
                            popUpTo(Screen.Albums.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onNavigateToArtists = {
                        navController.navigate(Screen.Artists.route) {
                            popUpTo(Screen.Albums.route)
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(
                route = Screen.AlbumDetail.route,
                arguments = listOf(
                    navArgument("albumId") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                ),
                enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(tween(300)) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = tween(300)) + fadeOut(tween(200)) },
                popEnterTransition = { slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = tween(300)) + fadeIn(tween(200)) },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(tween(200)) },
            ) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getString("albumId").orEmpty()
                val initialTitle = backStackEntry.arguments?.getString("title").orEmpty()
                val vm: AlbumDetailViewModel = viewModel(factory = AlbumDetailViewModelFactory(context, albumId))
                AlbumDetailScreen(
                    viewModel = vm,
                    playbackViewModel = playbackViewModel,
                    initialTitle = initialTitle,
                    onNavigateUp = { navController.navigateUp() },
                    onNavigateToArtist = { artistId ->
                        navController.navigate(Screen.ArtistDetail.createRoute(artistId))
                    },
                    onAddToPlaylist = { songId -> playlistPickerSongId = songId },
                    onStartInstantMix = { seed ->
                        if (currentRoute != Screen.NowPlaying.route) {
                            navController.navigate(Screen.NowPlaying.route)
                        }
                        appScope.launch {
                            val config = serverConfigStore.serverConfig.first()
                            playbackViewModel.startInstantMix(seed, config)
                        }
                    },
                )
            }

            composable(
                route = Screen.ArtistDetail.route,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
                enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(tween(300)) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = tween(300)) + fadeOut(tween(200)) },
                popEnterTransition = { slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = tween(300)) + fadeIn(tween(200)) },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(tween(200)) },
            ) { backStackEntry ->
                val artistId = backStackEntry.arguments?.getString("artistId").orEmpty()
                val vm: ArtistDetailViewModel = viewModel(factory = ArtistDetailViewModelFactory(context, artistId))
                ArtistDetailScreen(
                    viewModel = vm,
                    playbackViewModel = playbackViewModel,
                    onAlbumClick = { albumId, title ->
                        navController.navigate(Screen.AlbumDetail.createRoute(albumId, title))
                    },
                    onNavigateUp = { navController.navigateUp() },
                    onStartInstantMix = { seed ->
                        if (currentRoute != Screen.NowPlaying.route) {
                            navController.navigate(Screen.NowPlaying.route)
                        }
                        appScope.launch {
                            val config = serverConfigStore.serverConfig.first()
                            playbackViewModel.startInstantMix(seed, config)
                        }
                    },
                )
            }

            composable(
                route = Screen.PlaylistDetail.route,
                arguments = listOf(
                    navArgument("playlistId") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                ),
                enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(tween(300)) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = tween(300)) + fadeOut(tween(200)) },
                popEnterTransition = { slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = tween(300)) + fadeIn(tween(200)) },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(tween(200)) },
            ) { backStackEntry ->
                val playlistId = backStackEntry.arguments?.getString("playlistId").orEmpty()
                val initialName = backStackEntry.arguments?.getString("name").orEmpty()
                val vm: PlaylistDetailViewModel = viewModel(factory = PlaylistDetailViewModelFactory(context, playlistId))
                PlaylistDetailScreen(
                    viewModel = vm,
                    playbackViewModel = playbackViewModel,
                    initialName = initialName,
                    onNavigateUp = { navController.navigateUp() },
                    onAddToPlaylist = { songId -> playlistPickerSongId = songId },
                    onStartInstantMix = { seed ->
                        if (currentRoute != Screen.NowPlaying.route) {
                            navController.navigate(Screen.NowPlaying.route)
                        }
                        appScope.launch {
                            val config = serverConfigStore.serverConfig.first()
                            playbackViewModel.startInstantMix(seed, config)
                        }
                    },
                )
            }

            composable(
                route = Screen.Genre.route,
                arguments = listOf(navArgument("genreName") { type = NavType.StringType }),
                enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(tween(300)) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = tween(300)) + fadeOut(tween(200)) },
                popEnterTransition = { slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = tween(300)) + fadeIn(tween(200)) },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(tween(200)) },
            ) { backStackEntry ->
                val genre = backStackEntry.arguments?.getString("genreName").orEmpty()
                val vm: GenreViewModel = viewModel(factory = GenreViewModelFactory(context, genre))
                GenreScreen(
                    viewModel = vm,
                    genre = genre,
                    onAlbumClick = { albumId, title ->
                        navController.navigate(Screen.AlbumDetail.createRoute(albumId, title))
                    },
                    onNavigateUp = { navController.navigateUp() },
                )
            }

            composable(
                route = Screen.AlbumList.route,
                arguments = listOf(
                    navArgument("listType") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                ),
                enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(tween(300)) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = tween(300)) + fadeOut(tween(200)) },
                popEnterTransition = { slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = tween(300)) + fadeIn(tween(200)) },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(tween(200)) },
            ) { backStackEntry ->
                val listType = backStackEntry.arguments?.getString("listType").orEmpty()
                val title = backStackEntry.arguments?.getString("title").orEmpty()
                val vm: AlbumListViewModel = viewModel(factory = AlbumListViewModelFactory(context, listType))
                AlbumListScreen(
                    viewModel = vm,
                    title = title,
                    onAlbumClick = { albumId, albumTitle ->
                        navController.navigate(Screen.AlbumDetail.createRoute(albumId, albumTitle))
                    },
                    onNavigateUp = { navController.navigateUp() },
                )
            }

            composable(
                Screen.NowPlaying.route,
                enterTransition = { slideInVertically(initialOffsetY = { it }, animationSpec = tween(350)) },
                exitTransition = {
                    // Queue slides over NowPlaying — keep NowPlaying frozen underneath.
                    if (targetState.destination.route == Screen.Queue.route) fadeOut(tween(0))
                    else slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300))
                },
                popEnterTransition = {
                    // Queue is dismissed — NowPlaying reappears instantly underneath.
                    if (initialState.destination.route == Screen.Queue.route) fadeIn(tween(0))
                    else slideInVertically(initialOffsetY = { it }, animationSpec = tween(350))
                },
                popExitTransition = { slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) },
            ) {
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
                    onNavigateToQueue = {
                        // Push Queue on top of NowPlaying so it slides over it;
                        // pressing back (or swiping down) returns to NowPlaying.
                        navController.navigate(Screen.Queue.route) {
                            launchSingleTop = true
                        }
                    },
                    onStartInstantMix = {
                        playbackViewModel.startInstantMixForCurrentSong()
                    },
                )
            }
        }
    }
}

// ─── Playlist picker bottom sheet ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistPickerSheet(
    playlists: List<com.torsten.app.data.api.dto.PlaylistDto>,
    onDismiss: () -> Unit,
    onPick: (playlistId: String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
    ) {
        Text(
            "Add to playlist",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
            items(playlists) { playlist ->
                TextButton(
                    onClick = { onPick(playlist.id) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(playlist.name, color = Color.White, modifier = Modifier.weight(1f))
                }
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
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = Color.White,
        selectedTextColor = Color.White,
        unselectedIconColor = Color(0xFF555555),
        unselectedTextColor = Color(0xFF555555),
        indicatorColor = Color.Transparent,
    )

    Column {
    HorizontalDivider(
        thickness = 1.dp,
        color = Color(0xFF111111),
    )
    NavigationBar(containerColor = Color(0xFF0A0A0A)) {
        // ── Home ──────────────────────────────────────────────────────────────
        NavigationBarItem(
            selected = currentRoute == Screen.Home.route,
            onClick = {
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Home.route) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
            label = { Text("Home") },
            colors = itemColors,
        )

        // ── Search ────────────────────────────────────────────────────────────
        NavigationBarItem(
            selected = currentRoute == Screen.Search.route,
            onClick = {
                navController.navigate(Screen.Search.route) {
                    popUpTo(Screen.Home.route) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            icon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
            label = { Text("Search") },
            colors = itemColors,
        )

        // ── Library (Albums / Random / Artists / Playlists) ───────────────────
        NavigationBarItem(
            selected = currentRoute in libraryRoutes,
            onClick = {
                if (currentRoute == Screen.Random.route) {
                    onRandomReselect()
                } else {
                    navController.navigate(Screen.Albums.route) {
                        popUpTo(Screen.Home.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = "Library") },
            label = { Text("Library") },
            colors = itemColors,
        )

        // ── Downloads ─────────────────────────────────────────────────────────
        NavigationBarItem(
            selected = currentRoute == Screen.Downloads.route,
            onClick = {
                navController.navigate(Screen.Downloads.route) {
                    popUpTo(Screen.Home.route) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            icon = { Icon(Icons.Filled.Download, contentDescription = "Offline") },
            label = { Text("Offline") },
            colors = itemColors,
        )
    }
    } // end Column wrapping NavigationBar + divider
}
