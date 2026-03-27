package com.torsten.app.data.recommendation

import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.db.dao.AlbumDao
import com.torsten.app.data.db.dao.ArtistMbidCacheDao
import com.torsten.app.data.db.dao.ArtistTopTracksCacheDao
import com.torsten.app.data.db.dao.SongDao
import com.torsten.app.data.db.entity.AlbumEntity
import com.torsten.app.data.db.entity.ArtistMbidCacheEntity
import com.torsten.app.data.db.entity.ArtistTopTracksCacheEntity
import com.torsten.app.data.db.entity.DownloadState
import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.data.metabrainz.ListenBrainzClient
import com.torsten.app.data.metabrainz.MusicBrainzClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Collections

data class ArtistTopResult(
    val displayTracks: List<SongEntity>,
    val fullTracks: List<SongEntity>,
)

class ArtistTopTracksRepository(
    private val serverConfigStore: ServerConfigStore,
    private val songDao: SongDao,
    private val albumDao: AlbumDao,
    private val artistMbidCacheDao: ArtistMbidCacheDao,
    private val artistTopTracksCacheDao: ArtistTopTracksCacheDao,
    private val musicBrainzClient: MusicBrainzClient,
    private val listenBrainzClient: ListenBrainzClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = Collections.synchronizedSet(mutableSetOf<String>())

    fun prefetchIfNeeded(artistId: String, artistName: String) {
        if (artistId.isBlank() || artistName.isBlank()) return
        if (inFlight.contains(artistId)) {
            Timber.tag("[ArtistTop]").d("prefetch already in flight, skipping '%s'", artistName)
            return
        }
        scope.launch {
            val cached = artistTopTracksCacheDao.getByArtistId(artistId)
            if (cached != null && cached.isValid()) {
                Timber.tag("[ArtistTop]").d("prefetch skipped, cache valid '%s'", artistName)
                return@launch
            }
            inFlight.add(artistId)
            try {
                Timber.tag("[ArtistTop]").d("prefetch triggered for '%s'", artistName)
                getTopTracks(artistId, artistName)
            } finally {
                inFlight.remove(artistId)
            }
        }
    }

    suspend fun getTopTracks(artistId: String, artistName: String): ArtistTopResult {
        // 0. Cache check — skip LB entirely if fresh result is cached
        val cached = artistTopTracksCacheDao.getByArtistId(artistId)
        if (cached != null && cached.isValid()) {
            val ids = cached.trackIds.split(",").filter { it.isNotEmpty() }
            val songMap = ids.mapNotNull { songDao.getById(it) }.associateBy { it.id }
            val ordered = ids.mapNotNull { songMap[it] }
            if (ordered.isNotEmpty()) {
                Timber.tag("[ArtistTop]").d("Cache hit for $artistName: ${ordered.size} tracks")
                val byId = songDao.getByArtistId(artistId)
                val byName = songDao.getSongsByArtistName(artistName)
                val allSongs = (byId + byName).distinctBy { it.id }
                Timber.tag("[ArtistTop]").d("Cache hit allSongs for $artistName: byId=${byId.size} byName=${byName.size} merged=${allSongs.size}")
                return ArtistTopResult(
                    displayTracks = ArtistTopTracksSelector.selectTopFive(ordered, allSongs, artistName),
                    fullTracks = ArtistTopTracksSelector.selectFullQueue(ordered),
                )
            }
        }

        // 1. Initial pool
        val initialPool = songDao.getByArtistId(artistId)
        Timber.tag("[ArtistTop]").d("Initial song pool for $artistName: ${initialPool.size} songs")

        // 2. Hydrate missing album songs
        hydrateAlbums(artistId, artistName)

        // 3. Re-query merged pool (by artistId + by albumArtistName for compilations/features)
        val byId = songDao.getByArtistId(artistId)
        val byName = songDao.getSongsByArtistName(artistName)
        val songPool = (byId + byName).distinctBy { it.id }
            .sortedWith(compareBy({ it.albumId }, { it.discNumber }, { it.trackNumber }))
        Timber.tag("[ArtistTop]").d("Merged song pool for $artistName: ${songPool.size} songs")

        // 4. Resolve MBID
        val mbid = resolveMbid(artistId, artistName) ?: run {
            Timber.tag("[ArtistTop]").d("MBID not resolved for $artistName — falling back to title sort")
            return localFallback(songPool, artistName)
        }

        // 5. Fetch LB stats
        val candidates = listenBrainzClient.getArtistRecordingStats(mbid)
        Timber.tag("[ArtistTop]").d("LB candidates for $artistName: ${candidates.size}")
        if (candidates.isEmpty()) {
            Timber.tag("[ArtistTop]").d("No LB candidates for $artistName — falling back to title sort")
            return localFallback(songPool, artistName)
        }

        // 6. Match
        val matched = ArtistTopTracksSelector.matchCandidates(candidates, songPool)
        Timber.tag("[ArtistTop]").d("Total matches for $artistName: ${matched.size}")
        if (matched.isEmpty()) return localFallback(songPool, artistName)

        // 7. Build result
        val fullTracks = ArtistTopTracksSelector.selectFullQueue(matched)
        val displayTracks = ArtistTopTracksSelector.selectTopFive(matched, songPool, artistName)
        Timber.tag("[ArtistTop]").d(
            "Display top 5 for $artistName: [${displayTracks.joinToString { "'${it.title}' (album=${it.albumId})" }}]",
        )

        // Cache the LB-ranked result for 24 hours
        artistTopTracksCacheDao.upsert(
            ArtistTopTracksCacheEntity(
                artistId = artistId,
                trackIds = fullTracks.joinToString(",") { it.id },
                cachedAt = System.currentTimeMillis(),
            )
        )

        return ArtistTopResult(displayTracks = displayTracks, fullTracks = fullTracks)
    }

    private suspend fun hydrateAlbums(artistId: String, artistName: String) {
        val config = serverConfigStore.serverConfig.first()
        if (!config.isConfigured) return

        val client = SubsonicApiClient(config)
        val artistWithAlbums = runCatching { client.getArtist(artistId) }.getOrNull() ?: return

        val allAlbums = artistWithAlbums.album.orEmpty()
        val unvisited = allAlbums.filter { songDao.getByAlbum(it.id).isEmpty() }
        val skipped = allAlbums.size - unvisited.size

        val fetched = java.util.concurrent.atomic.AtomicInteger(0)
        coroutineScope {
            unvisited.map { albumDto ->
                async {
                    Timber.tag("[ArtistTop]").d("Fetching album '${albumDto.name}' for $artistName")
                    runCatching {
                        val albumWithSongs = client.getAlbum(albumDto.id)
                        val now = System.currentTimeMillis()
                        val songs = albumWithSongs.song.orEmpty().map { dto ->
                            // Gson bypasses Kotlin null-safety; guard title explicitly.
                            @Suppress("SENSELESS_COMPARISON")
                            val safeTitle = if (dto.title == null) "" else dto.title
                            // Use the outer artistId as fallback when the song DTO omits it —
                            // this ensures getByArtistId can find these songs later.
                            val songArtistId = dto.artistId?.takeIf { it.isNotEmpty() } ?: artistId
                            SongEntity(
                                id = dto.id,
                                albumId = albumDto.id,
                                artistId = songArtistId,
                                title = safeTitle,
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
                        // Ensure the album entity exists so getSongsByArtistName JOIN works.
                        // Uses IGNORE conflict strategy — skips existing rows to preserve download state.
                        albumDao.insertIfAbsent(listOf(AlbumEntity(
                            id = albumDto.id,
                            artistId = albumDto.artistId?.takeIf { it.isNotEmpty() } ?: artistId,
                            artistName = albumDto.artist ?: artistName,
                            title = albumDto.name,
                            year = albumDto.year,
                            genre = albumDto.genre,
                            songCount = albumWithSongs.songCount,
                            duration = albumWithSongs.duration,
                            coverArtId = albumDto.coverArt,
                            starred = albumDto.starred != null,
                            downloadState = DownloadState.NONE,
                            downloadProgress = 0,
                            downloadedAt = null,
                            lastUpdated = now,
                        )))
                        fetched.incrementAndGet()
                    }.onFailure { e ->
                        Timber.tag("[ArtistTop]").w("Failed to fetch album ${albumDto.id}: ${e.message}")
                    }
                }
            }.awaitAll()
        }

        val poolNow = songDao.getByArtistId(artistId).size
        Timber.tag("[ArtistTop]").d(
            "Hydration for $artistName: fetched ${fetched.get()} new, skipped $skipped cached, pool now $poolNow songs",
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

    private fun localFallback(songs: List<SongEntity>, artistName: String = ""): ArtistTopResult {
        val sorted = songs.sortedBy { it.title }
        return ArtistTopResult(
            displayTracks = ArtistTopTracksSelector.selectTopFive(emptyList(), songs, artistName),
            fullTracks = sorted.take(20),
        )
    }
}
