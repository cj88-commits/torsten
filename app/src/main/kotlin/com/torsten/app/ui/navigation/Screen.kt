package com.torsten.app.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Search : Screen("search")
    data object Queue : Screen("queue")
    data object Settings : Screen("settings")
    data object Albums : Screen("albums")
    data object Random : Screen("random")
    data object Artists : Screen("artists")
    data object Playlists : Screen("playlists")
    data object AlbumDetail : Screen("album_detail/{albumId}?title={title}") {
        fun createRoute(albumId: String, title: String = "") =
            "album_detail/${Uri.encode(albumId)}?title=${Uri.encode(title)}"
    }
    data object ArtistDetail : Screen("artist_detail/{artistId}") {
        fun createRoute(artistId: String) = "artist_detail/$artistId"
    }
    data object PlaylistDetail : Screen("playlist_detail/{playlistId}?name={name}") {
        fun createRoute(playlistId: String, name: String = "") =
            "playlist_detail/${Uri.encode(playlistId)}?name=${Uri.encode(name)}"
    }
    data object NowPlaying : Screen("now_playing")
    data object Genre : Screen("genre/{genreName}") {
        fun createRoute(genre: String) = "genre/${Uri.encode(genre)}"
    }
    data object AlbumList : Screen("album_list/{listType}?title={title}") {
        fun createRoute(listType: String, title: String = "") =
            "album_list/$listType?title=${Uri.encode(title)}"
    }
    data object Downloads : Screen("downloads")
    data object Explore : Screen("explore")
    data object StarredTracks : Screen("starred_tracks")
    data object Startup : Screen("startup")
    data object ServerConfig : Screen("server_config?firstLaunch={firstLaunch}") {
        fun createRoute(isFirstLaunch: Boolean) = "server_config?firstLaunch=$isFirstLaunch"
    }
}
