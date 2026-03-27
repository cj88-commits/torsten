package com.torsten.app.ui.search

import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.dto.SongDto
import com.torsten.app.data.db.dao.SongDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Builds a playback queue from a tapped search result by fetching all tracks for the song's
 * album from Room and delegating to [SearchTrackClickHandler].
 *
 * Returns the **full album** as a [List<SongDto>] together with the [startIndex] of the
 * tapped song. The caller passes both to [playFromSongDtos] so Media3 loads the whole album
 * and starts at the correct position — matching Album Detail behaviour exactly.
 *
 * Extracted from SearchViewModel so it can be unit-tested with a [FakeSongDao] without
 * needing to construct the full ViewModel (which has network/datastore dependencies).
 *
 * Every fallback branch is logged with a [Timber] tag so logcat shows exactly which guard
 * fired when the queue unexpectedly collapses to a single song.
 *
 * @param apiClientProvider Optional supplier of a [SubsonicApiClient]. When Room returns no
 *   tracks for the album (library not yet synced), the builder falls back to fetching the
 *   album directly from the Subsonic API. Defaults to `{ null }` (no fallback) — used in
 *   unit tests which supply only a [FakeSongDao].
 */
class AlbumQueueBuilder(
    private val songDao: SongDao,
    private val apiClientProvider: () -> SubsonicApiClient? = { null },
) {

    /**
     * @return Pair of (fullAlbumQueue, startIndex). On any fallback the list contains only the
     *         tapped song and startIndex is 0.
     */
    suspend fun build(song: SongDto): Pair<List<SongDto>, Int> = withContext(Dispatchers.IO) {
        Timber.tag(TAG).d(
            "build: song='%s' id=%s albumId=%s",
            song.title, song.id, song.albumId,
        )

        val albumId = song.albumId
        if (albumId == null) {
            Timber.tag(TAG).w(
                "build: albumId is null for song='%s' id=%s — single-song fallback",
                song.title, song.id,
            )
            return@withContext Pair(listOf(song), 0)
        }

        val albumTracks = songDao.getByAlbum(albumId)
        Timber.tag(TAG).d(
            "build: getByAlbum('%s') returned %d tracks: %s",
            albumId, albumTracks.size, albumTracks.map { it.id },
        )

        if (albumTracks.isEmpty()) {
            Timber.tag(TAG).w(
                "build: no tracks in Room for albumId='%s' — trying API fallback",
                albumId,
            )
            return@withContext buildFromApi(song, albumId)
        }

        val tappedEntity = albumTracks.firstOrNull { it.id == song.id }
        if (tappedEntity == null) {
            Timber.tag(TAG).w(
                "build: song id=%s not found in Room album (Room ids=%s) — trying API fallback",
                song.id, albumTracks.map { it.id },
            )
            return@withContext buildFromApi(song, albumId)
        }

        val (entities, startIndex) = SearchTrackClickHandler.buildQueue(tappedEntity, albumTracks)
        val queue = entities.map { entity ->
            SongDto(
                id          = entity.id,
                title       = entity.title,
                album       = song.album,
                albumId     = entity.albumId,
                artist      = song.artist,
                artistId    = entity.artistId,
                track       = entity.trackNumber,
                discNumber  = entity.discNumber,
                duration    = entity.duration,
                bitRate     = entity.bitRate,
                suffix      = entity.suffix,
                contentType = entity.contentType,
                coverArt    = song.coverArt,
                starred     = if (entity.starred) "true" else null,
            )
        }

        Timber.tag(TAG).d(
            "build: queue.size=%d startIndex=%d titles=%s",
            queue.size, startIndex, queue.map { it.title },
        )
        Pair(queue, startIndex)
    }

    /**
     * Fallback: fetch album tracks from the Subsonic API when Room has no data.
     * Songs returned by `getAlbum` are already [SongDto]s — sort by disc/track, find the tapped
     * song by id, return (sortedQueue, startIndex). If the API call fails or the client is
     * unavailable, degrades to a single-song queue.
     */
    private suspend fun buildFromApi(song: SongDto, albumId: String): Pair<List<SongDto>, Int> {
        val client = apiClientProvider()
        if (client == null) {
            Timber.tag(TAG).w("buildFromApi: no API client available — single-song fallback")
            return Pair(listOf(song), 0)
        }
        return runCatching {
            val albumWithSongs = client.getAlbum(albumId)
            val apiTracks = albumWithSongs.song.orEmpty()
            Timber.tag(TAG).d(
                "buildFromApi: getAlbum('%s') returned %d tracks: %s",
                albumId, apiTracks.size, apiTracks.map { it.id },
            )
            if (apiTracks.isEmpty()) {
                Timber.tag(TAG).w("buildFromApi: API returned 0 tracks — single-song fallback")
                return@runCatching Pair(listOf(song), 0)
            }
            val sorted = apiTracks
                .sortedWith(compareBy({ it.discNumber ?: 1 }, { it.track ?: 0 }))
            val startIndex = sorted.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
            Timber.tag(TAG).d(
                "buildFromApi: queue.size=%d startIndex=%d", sorted.size, startIndex,
            )
            Pair(sorted, startIndex)
        }.getOrElse { e ->
            Timber.tag(TAG).e(e, "buildFromApi: API call failed — single-song fallback")
            Pair(listOf(song), 0)
        }
    }

    companion object {
        private const val TAG = "[AlbumQueue]"
    }
}
