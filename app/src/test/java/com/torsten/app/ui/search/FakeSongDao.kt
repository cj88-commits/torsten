package com.torsten.app.ui.search

import com.torsten.app.data.db.dao.SongDao
import com.torsten.app.data.db.entity.SongEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory SongDao fake for unit tests.
 * Pre-populate [albumSongs] before the test; all other methods are no-ops.
 */
class FakeSongDao(
    private val albumSongs: Map<String, List<SongEntity>> = emptyMap(),
) : SongDao {

    override suspend fun getByAlbum(albumId: String): List<SongEntity> =
        albumSongs[albumId] ?: emptyList()

    // ── All unused — minimal stubs so the interface compiles ─────────────────
    override fun observeById(id: String): Flow<SongEntity?> = flowOf(null)
    override suspend fun getById(id: String): SongEntity? = null
    override fun observeByAlbum(albumId: String): Flow<List<SongEntity>> = flowOf(emptyList())
    override suspend fun getByArtistId(artistId: String): List<SongEntity> = emptyList()
    override suspend fun getSongsByArtistName(artistName: String): List<SongEntity> = emptyList()
    override suspend fun upsertAll(songs: List<SongEntity>) = Unit
    override suspend fun deleteByAlbum(albumId: String) = Unit
    override suspend fun updateLocalFilePath(songId: String, path: String) = Unit
    override suspend fun clearLocalFilePathsForAlbum(albumId: String) = Unit
    override suspend fun clearLocalFilePath(songId: String) = Unit
    override suspend fun updateStarred(songId: String, starred: Boolean) = Unit
    override fun observeStarredById(songId: String): Flow<Boolean> = flowOf(false)
    override suspend fun clearAllLocalFilePaths() = Unit
}
