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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
    private val fetchMutexes = ConcurrentHashMap<String, Mutex>()

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
        Timber.tag("[ArtistTop]").d("getTopTracks: artistName='$artistName' artistId='$artistId'")
        val mutex = fetchMutexes.computeIfAbsent(artistId) { Mutex() }
        return mutex.withLock { getTopTracksLocked(artistId, artistName) }
    }

    private suspend fun getTopTracksLocked(artistId: String, artistName: String): ArtistTopResult {

        // 0. Cache check — skip network calls if a fresh result is cached
        val cached = artistTopTracksCacheDao.getByArtistId(artistId)
        if (cached != null && cached.isValid()) {
            val ids = cached.trackIds.split(",").filter { it.isNotEmpty() }
            val songMap = ids.mapNotNull { songDao.getById(it) }.associateBy { it.id }
            val ordered = ids.mapNotNull { songMap[it] }
            if (ordered.isNotEmpty()) {
                val allSongs = (songDao.getByArtistId(artistId) + songDao.getSongsByArtistName(artistName))
                    .distinctBy { it.id }
                val displayTracks = ArtistTopTracksSelector.selectTopFive(ordered, allSongs, artistName)
                // If we couldn't fill 5 tracks from the cached list but the library has more,
                // the cache is stale (written when the song pool was incomplete). Bust it.
                if (displayTracks.size < minOf(5, allSongs.size)) {
                    Timber.tag("[ArtistTop]").d(
                        "Cache produced only ${displayTracks.size} tracks but library has ${allSongs.size} songs — invalidating",
                    )
                    artistTopTracksCacheDao.deleteByArtistId(artistId)
                    // Fall through to fresh fetch
                } else {
                    Timber.tag("[ArtistTop]").d("Cache hit for '$artistName': ${ordered.size} songs → ${displayTracks.size} display")
                    return ArtistTopResult(
                        displayTracks = displayTracks,
                        fullTracks = ArtistTopTracksSelector.selectFullQueue(ordered),
                    )
                }
            }
        }

        // 1. Hydrate albums: fetch any new albums from the server, then load songs from
        // ALL known albums (server list + local DB). Returns the full song list directly.
        val localSongs = hydrateAlbums(artistId, artistName)

        // 2. Build song pool — union with artist-id/name queries as a belt-and-suspenders
        // fallback for any tracks not covered by the album-based load above.
        val songPool = (localSongs
            + songDao.getByArtistId(artistId)
            + songDao.getSongsByArtistName(artistName))
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.albumId }, { it.discNumber }, { it.trackNumber }))
        Timber.tag("[ArtistTop]").d("Local songs pool size: ${songPool.size}")
        Timber.tag("[ArtistTop]").d("Local songs containing 'beautiful': ${songPool.filter { "beautiful" in it.title.lowercase() }.map { it.title }}")

        // 3. Resolve MBID
        val mbid = resolveMbid(artistId, artistName) ?: run {
            Timber.tag("[ArtistTop]").d("MBID not resolved for '$artistName' — local fallback")
            return localFallback(songPool, artistName)
        }

        // 4. Fetch ListenBrainz top recording stats
        val candidates = listenBrainzClient.getArtistRecordingStats(mbid)
        Timber.tag("[ArtistTop]").d("LB candidates for '$artistName': ${candidates.size}")
        if (candidates.isEmpty()) return localFallback(songPool, artistName)

        // 5. Match against library by normalised title
        val matched = ArtistTopTracksSelector.matchCandidates(candidates, songPool)
        Timber.tag("[ArtistTop]").d("Matched for '$artistName': ${matched.size}")
        if (matched.isEmpty()) return localFallback(songPool, artistName)

        Timber.tag("[ArtistTop]").d("All matched songs (pre-diversity): ${matched.map { it.title }}")

        // 6. Build result with backfill: if fewer than 10 LB matches, pad from unmatched
        // Navidrome tracks (shuffled) so the queue always reaches 10 (or total available).
        // Order: top LB match → remaining matches shuffled → backfill shuffled.
        val matchedIds = matched.map { it.id }.toSet()
        val backfill = songPool.filter { it.id !in matchedIds }.shuffled()
        val orderedQueue = (matched.take(1) + matched.drop(1).shuffled() + backfill)
            .distinctBy { it.id }
        Timber.tag("[ArtistTop]").d(
            "Queue for '$artistName': ${matched.size} matched + ${backfill.size} backfill candidates",
        )

        val fullTracks = ArtistTopTracksSelector.selectFullQueue(orderedQueue)
        val displayTracks = ArtistTopTracksSelector.selectTopFive(matched, songPool, artistName)
        Timber.tag("[ArtistTop]").d(
            "Display top 5 for '$artistName': [${displayTracks.joinToString { "'${it.title}' (album=${it.albumId})" }}]",
        )

        // Cache for 24 hours
        artistTopTracksCacheDao.upsert(
            ArtistTopTracksCacheEntity(
                artistId = artistId,
                trackIds = fullTracks.joinToString(",") { it.id },
                cachedAt = System.currentTimeMillis(),
            )
        )

        return ArtistTopResult(displayTracks = displayTracks, fullTracks = fullTracks)
    }

    private suspend fun hydrateAlbums(artistId: String, artistName: String): List<SongEntity> {
        val config = serverConfigStore.serverConfig.first()
        if (!config.isConfigured) return emptyList()

        val client = SubsonicApiClient(config)
        val serverAlbums = runCatching { client.getArtist(artistId) }
            .getOrNull()?.album.orEmpty()

        if (serverAlbums.isEmpty()) {
            Timber.tag("[ArtistTop]").w("No albums from server for '$artistName'")
            return emptyList()
        }

        val localSongs = mutableListOf<SongEntity>()

        for (albumDto in serverAlbums) {
            val existingSongs = songDao.getByAlbum(albumDto.id)
            val isCorrupt = existingSongs.isNotEmpty() && existingSongs.size < 3
            if (isCorrupt) {
                Timber.tag("[ArtistTop]").w(
                    "Album '${albumDto.name}': corrupt cache (${existingSongs.size} song(s)) — evicting",
                )
                songDao.deleteByAlbum(albumDto.id)
            }
            val wasCached = existingSongs.isNotEmpty() && !isCorrupt

            val songs: List<SongEntity> = if (wasCached) {
                existingSongs
            } else {
                runCatching {
                    val albumWithSongs = client.getAlbum(albumDto.id)
                    val now = System.currentTimeMillis()
                    val fetched = albumWithSongs.song.orEmpty().map { dto ->
                        @Suppress("SENSELESS_COMPARISON")
                        val safeTitle = if (dto.title == null) "" else dto.title
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
                            artistName = dto.artist.orEmpty(),
                            albumArtistName = albumDto.artist ?: artistName,
                        )
                    }
                    songDao.upsertAll(fetched)
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
                    fetched
                }.getOrElse { e ->
                    Timber.tag("[ArtistTop]").w("Failed to fetch album '${albumDto.name}': ${e.message}")
                    emptyList()
                }
            }

            localSongs.addAll(songs)
            Timber.tag("[ArtistTop]").d(
                "Album '${albumDto.name}': added ${songs.size} songs to pool (cached=$wasCached, corrupt=$isCorrupt)",
            )
        }

        return localSongs
    }

    private suspend fun resolveMbid(artistId: String, artistName: String): String? {
        val cached = artistMbidCacheDao.getByArtistId(artistId)
        if (cached != null && cached.isValid()) {
            Timber.tag("[ArtistTop]").d("MBID cache hit for '$artistName': ${cached.mbid}")
            return cached.mbid
        }
        val mbid = musicBrainzClient.getArtistMbid(artistName) ?: return null
        artistMbidCacheDao.upsert(
            ArtistMbidCacheEntity(artistId = artistId, mbid = mbid, cachedAt = System.currentTimeMillis()),
        )
        Timber.tag("[ArtistTop]").d("Resolved MBID for '$artistName': $mbid")
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
