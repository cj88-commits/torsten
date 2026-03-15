package com.recordcollection.app.ui.navigation

sealed class Screen(val route: String) {
    data object Settings : Screen("settings")
    data object Albums : Screen("albums")
    data object Random : Screen("random")
    data object Artists : Screen("artists")
    data object AlbumDetail : Screen("album_detail/{albumId}") {
        fun createRoute(albumId: String) = "album_detail/$albumId"
    }
    data object ArtistDetail : Screen("artist_detail/{artistId}") {
        fun createRoute(artistId: String) = "artist_detail/$artistId"
    }
    data object NowPlaying : Screen("now_playing")
    data object ServerConfig : Screen("server_config?firstLaunch={firstLaunch}") {
        fun createRoute(isFirstLaunch: Boolean) = "server_config?firstLaunch=$isFirstLaunch"
    }
}
