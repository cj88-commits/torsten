package com.torsten.app.data.repository

import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.dto.PlaylistDto
import com.torsten.app.data.api.dto.PlaylistWithTracksDto
import com.torsten.app.data.datastore.ServerConfigStore
import kotlinx.coroutines.flow.first
import timber.log.Timber

class PlaylistRepository(private val configStore: ServerConfigStore) {

    private suspend fun client(): SubsonicApiClient {
        val config = configStore.serverConfig.first()
        require(config.isConfigured) { "Server not configured" }
        return SubsonicApiClient(config)
    }

    suspend fun getPlaylists(): List<PlaylistDto> = runCatching {
        client().getPlaylists()
    }.onFailure { Timber.tag("[Playlists]").e(it, "getPlaylists failed") }
        .getOrDefault(emptyList())

    suspend fun getPlaylist(id: String): PlaylistWithTracksDto =
        client().getPlaylist(id)

    suspend fun createPlaylist(name: String): PlaylistDto? {
        val result = runCatching { client().createPlaylist(name) }.getOrNull() ?: return null
        return PlaylistDto(
            id = result.id,
            name = result.name,
            comment = result.comment,
            songCount = result.songCount,
            duration = result.duration,
            coverArt = result.coverArt,
        )
    }

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) {
        runCatching { client().addTrackToPlaylist(playlistId, trackId) }
            .onFailure { Timber.tag("[Playlists]").e(it, "addTrack failed") }
            .getOrThrow()
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, songIndex: Int) {
        runCatching { client().removeTrackFromPlaylist(playlistId, songIndex) }
            .onFailure { Timber.tag("[Playlists]").e(it, "removeTrack failed") }
            .getOrThrow()
    }

    suspend fun deletePlaylist(id: String) {
        runCatching { client().deletePlaylist(id) }
            .onFailure { Timber.tag("[Playlists]").e(it, "deletePlaylist failed") }
            .getOrThrow()
    }

    /** Returns a full cover-art URL for [coverArtId], or null if not available. */
    fun getCoverArtUrl(coverArtId: String, config: com.torsten.app.data.datastore.ServerConfig, size: Int = 300): String =
        SubsonicApiClient(config).getCoverArtUrl(coverArtId, size)
}
