package com.torsten.app.data.repository

import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.dto.PlaylistDto
import com.torsten.app.data.api.dto.PlaylistWithTracksDto
import com.torsten.app.data.api.dto.SongDto
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.db.dao.AlbumDao
import com.torsten.app.data.db.dao.PlaylistTrackDao
import com.torsten.app.data.db.entity.DownloadState
import com.torsten.app.data.db.entity.PlaylistTrackEntity
import com.torsten.app.data.download.DownloadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import timber.log.Timber

class PlaylistRepository(
    private val configStore: ServerConfigStore,
    private val downloadRepository: DownloadRepository? = null,
    private val playlistTrackDao: PlaylistTrackDao? = null,
    private val albumDao: AlbumDao? = null,
) {

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

    suspend fun renamePlaylist(playlistId: String, name: String) {
        runCatching { client().renamePlaylist(playlistId, name) }
            .onFailure { Timber.tag("[Playlists]").e(it, "renamePlaylist failed") }
            .getOrThrow()
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

    /** Enqueues a download job for each track in the playlist that is not already downloaded. */
    suspend fun downloadPlaylist(playlistId: String, tracks: List<SongDto>) {
        val repo = downloadRepository ?: return
        tracks.forEach { song -> repo.downloadTrack(song, playlistId) }
        Timber.tag("[Download]").d("Enqueued playlist download: %d tracks for playlist %s", tracks.size, playlistId)
    }

    /** Observes the combined download state of all tracks in the playlist. */
    fun observePlaylistDownloadState(songIds: List<String>): Flow<DownloadState> {
        val repo = downloadRepository ?: return flowOf(DownloadState.NONE)
        if (songIds.isEmpty()) return flowOf(DownloadState.NONE)
        return repo.observeSongDownloadStates(songIds).map { states ->
            val downloaded = states.count { it }
            when {
                downloaded == states.size -> DownloadState.COMPLETE
                downloaded > 0 -> DownloadState.PARTIAL
                else -> DownloadState.NONE
            }
        }
    }

    /** Cancels pending track downloads for this playlist. */
    fun cancelPlaylistDownload(playlistId: String) {
        downloadRepository?.cancelPlaylistTrackDownloads(playlistId)
    }

    /** Deletes all local track files for the given song IDs. */
    suspend fun deletePlaylistDownload(songIds: List<String>) {
        downloadRepository?.deleteTrackDownloads(songIds)
    }

    // ─── Playlist track cache ─────────────────────────────────────────────────

    /**
     * Returns cached playlist tracks from Room, enriched with artist name and cover art
     * by joining against the albums table. Returns an empty list if nothing is cached.
     */
    suspend fun getCachedPlaylistTracks(playlistId: String): List<SongDto> {
        val dao = playlistTrackDao ?: return emptyList()
        val songs = dao.getTracksForPlaylist(playlistId)
        if (songs.isEmpty()) return emptyList()

        // Enrich with album data (artistName, coverArtId) from the local albums table
        val albumsById = albumDao
            ?.let { aDao ->
                songs.map { it.albumId }.distinct()
                    .mapNotNull { aDao.getById(it) }
                    .associateBy { it.id }
            }
            ?: emptyMap()

        return songs.map { song ->
            val album = albumsById[song.albumId]
            SongDto(
                id          = song.id,
                title       = song.title,
                albumId     = song.albumId,
                artistId    = song.artistId,
                duration    = song.duration,
                bitRate     = song.bitRate,
                suffix      = song.suffix,
                contentType = song.contentType,
                artist      = album?.artistName,
                coverArt    = album?.coverArtId,
            )
        }
    }

    /**
     * Persists the playlist → song mapping so it can be served offline.
     * Replaces any existing cache for this playlist.
     */
    suspend fun cachePlaylistTracks(playlistId: String, songs: List<SongDto>) {
        val dao = playlistTrackDao ?: return
        val entities = songs.mapIndexed { index, song ->
            PlaylistTrackEntity(
                playlistId = playlistId,
                songId     = song.id,
                trackOrder = index,
            )
        }
        dao.deleteTracksForPlaylist(playlistId)
        dao.upsertTracks(entities)
        Timber.tag("[Playlists]").d("Cached %d tracks for playlist %s", entities.size, playlistId)
    }
}
