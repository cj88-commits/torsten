package com.torsten.app.data.recommendation

import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.db.dao.AlbumDao
import com.torsten.app.data.db.dao.ArtistMbidCacheDao
import com.torsten.app.data.db.dao.SongDao
import com.torsten.app.data.db.entity.ArtistMbidCacheEntity
import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.data.metabrainz.ListenBrainzClient
import com.torsten.app.data.metabrainz.MusicBrainzClient
import kotlinx.coroutines.flow.first
import timber.log.Timber

data class ArtistTopResult(
    val displayTracks: List<SongEntity>,
    val fullTracks: List<SongEntity>,
)

class ArtistTopTracksRepository(
    private val serverConfigStore: ServerConfigStore,
    private val songDao: SongDao,
    private val albumDao: AlbumDao,
    private val artistMbidCacheDao: ArtistMbidCacheDao,
    private val musicBrainzClient: MusicBrainzClient,
    private val listenBrainzClient: ListenBrainzClient,
) {

    suspend fun getTopTracks(artistId: String, artistName: String): ArtistTopResult {
        // 1. Initial pool
        val initialPool = songDao.getByArtistId(artistId)
        Timber.tag("[ArtistTop]").d("Initial song pool for $artistName: ${initialPool.size} songs")

        // 2. Hydrate missing album songs
        hydrateAlbums(artistId, artistName)

        // 3. Re-query merged pool (by artistId + by albumArtistName for compilations/features)
        val byId = songDao.getByArtistId(artistId)
        val byName = songDao.getSongsByArtistName(artistName)
        val songPool = (byId + byName).distinctBy { it.id }
        Timber.tag("[ArtistTop]").d("Merged song pool for $artistName: ${songPool.size} songs")

        // 4. Resolve MBID
        val mbid = resolveMbid(artistId, artistName) ?: run {
            Timber.tag("[ArtistTop]").d("MBID not resolved for $artistName — falling back to title sort")
            return localFallback(songPool)
        }

        // 5. Fetch LB stats
        val candidates = listenBrainzClient.getArtistRecordingStats(mbid)
        Timber.tag("[ArtistTop]").d("LB candidates for $artistName: ${candidates.size}")
        if (candidates.isEmpty()) {
            Timber.tag("[ArtistTop]").d("No LB candidates for $artistName — falling back to title sort")
            return localFallback(songPool)
        }

        // 6. Match
        val matched = CatalogueMatcher.match(candidates, songPool)
        Timber.tag("[ArtistTop]").d("Total matches for $artistName: ${matched.size}")
        if (matched.isEmpty()) return localFallback(songPool)

        // 7. Build result
        val fullTracks = matched.take(20).map { it.song }
        val displayTracks = buildDisplayTracks(matched, artistName)
        return ArtistTopResult(displayTracks = displayTracks, fullTracks = fullTracks)
    }

    private suspend fun hydrateAlbums(artistId: String, artistName: String) {
        val config = serverConfigStore.serverConfig.first()
        if (!config.isConfigured) return

        val client = SubsonicApiClient(config)
        val artistWithAlbums = runCatching { client.getArtist(artistId) }.getOrNull() ?: return

        var fetched = 0
        var skipped = 0

        for (albumDto in artistWithAlbums.album.orEmpty()) {
            if (songDao.getByAlbum(albumDto.id).isNotEmpty()) {
                skipped++
                continue
            }
            Timber.tag("[ArtistTop]").d("Fetching album '${albumDto.name}' for $artistName")
            runCatching {
                val albumWithSongs = client.getAlbum(albumDto.id)
                val now = System.currentTimeMillis()
                val songs = albumWithSongs.song.orEmpty().map { dto ->
                    SongEntity(
                        id = dto.id,
                        albumId = albumDto.id,
                        artistId = dto.artistId.orEmpty(),
                        title = dto.title,
                        trackNumber = dto.track ?: 0,
                        discNumber = dto.discNumber ?: 1,
                        duration = dto.duration ?: 0,
                        bitRate = dto.bitRate,
                        suffix = dto.suffix,
                        contentType = dto.contentType,
                        starred = dto.starred != null,
                        localFilePath = null,
                        lastUpdated = now,
                    )
                }
                songDao.upsertAll(songs)
                fetched++
            }.onFailure { e ->
                Timber.tag("[ArtistTop]").w("Failed to fetch album ${albumDto.id}: ${e.message}")
            }
        }

        val poolNow = songDao.getByArtistId(artistId).size
        Timber.tag("[ArtistTop]").d(
            "Hydration for $artistName: fetched $fetched new, skipped $skipped cached, pool now $poolNow songs",
        )
    }

    private suspend fun resolveMbid(artistId: String, artistName: String): String? {
        val cached = artistMbidCacheDao.getByArtistId(artistId)
        if (cached != null && cached.isValid()) {
            Timber.tag("[ArtistTop]").d("MBID cache hit for $artistName: ${cached.mbid}")
            return cached.mbid
        }
        val mbid = musicBrainzClient.getArtistMbid(artistName) ?: return null
        artistMbidCacheDao.upsert(
            ArtistMbidCacheEntity(artistId = artistId, mbid = mbid, cachedAt = System.currentTimeMillis()),
        )
        Timber.tag("[ArtistTop]").d("Resolved MBID for $artistName: $mbid")
        return mbid
    }

    private fun buildDisplayTracks(matched: List<MatchedSong>, artistName: String): List<SongEntity> {
        val seenAlbumIds = mutableSetOf<String>()
        val seenLbTitles = mutableSetOf<String>()
        val result = mutableListOf<SongEntity>()

        // First pass: album-diverse selection
        for (m in matched) {
            if (result.size >= 5) break
            if (m.song.albumId in seenAlbumIds || m.lbCandidateTitle in seenLbTitles) continue
            seenAlbumIds.add(m.song.albumId)
            seenLbTitles.add(m.lbCandidateTitle)
            result.add(m.song)
        }

        // Second pass: fill remaining slots ignoring album diversity
        if (result.size < 5) {
            val resultIds = result.map { it.id }.toHashSet()
            for (m in matched) {
                if (result.size >= 5) break
                if (m.song.id in resultIds || m.lbCandidateTitle in seenLbTitles) continue
                seenLbTitles.add(m.lbCandidateTitle)
                result.add(m.song)
            }
        }

        val displayDesc = result.joinToString { "'${it.title}' (album=${it.albumId})" }
        Timber.tag("[ArtistTop]").d(
            "Display top 5 for $artistName (matched=${matched.size}, unique albums=${seenAlbumIds.size}): [$displayDesc]",
        )
        return result
    }

    private fun localFallback(songs: List<SongEntity>): ArtistTopResult {
        val sorted = songs.sortedBy { it.title }
        return ArtistTopResult(displayTracks = sorted.take(5), fullTracks = sorted.take(20))
    }
}
