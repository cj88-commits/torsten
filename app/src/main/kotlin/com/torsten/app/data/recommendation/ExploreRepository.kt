package com.torsten.app.data.recommendation

import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.api.dto.AlbumDto
import com.torsten.app.data.api.dto.ArtistDto
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.db.entity.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

data class ExplorePayload(
    val artist: ArtistDto,
    val album: AlbumDto,
    val topTracks: List<SongEntity>,
    val topTracksError: Boolean,
)

class ExploreRepository(
    private val serverConfigStore: ServerConfigStore,
    private val artistTopTracksRepository: ArtistTopTracksRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _pool = MutableStateFlow<List<ExplorePayload>>(emptyList())
    val pool: StateFlow<List<ExplorePayload>> = _pool.asStateFlow()
    private val inFlightCount = AtomicInteger(0)

    companion object {
        private const val POOL_SIZE = 3
    }

    init { refillPool() }

    fun consumeNext(): ExplorePayload? {
        var result: ExplorePayload? = null
        _pool.update { current ->
            if (current.isEmpty()) current
            else { result = current.first(); current.drop(1) }
        }
        refillPool()
        return result
    }

    suspend fun awaitNext(): ExplorePayload? {
        consumeNext()?.let { return it }
        return withTimeoutOrNull(30_000L) {
            var result: ExplorePayload? = null
            while (result == null) {
                pool.first { it.isNotEmpty() }
                result = consumeNext()
            }
            result
        }
    }

    private fun refillPool() {
        val needed = (POOL_SIZE - _pool.value.size - inFlightCount.get()).coerceAtLeast(0)
        if (needed == 0) return
        repeat(needed) {
            inFlightCount.incrementAndGet()
            scope.launch {
                try {
                    val payload = fetchPayload()
                    if (payload != null) {
                        _pool.update { current ->
                            if (current.size < POOL_SIZE) current + payload else current
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("[Explore]").w(e, "Pool slot fetch failed")
                } finally {
                    inFlightCount.decrementAndGet()
                }
            }
        }
    }

    private suspend fun fetchPayload(): ExplorePayload? {
        val config = serverConfigStore.serverConfig.first()
        if (!config.isConfigured) return null

        val client = SubsonicApiClient(config)
        val (artists, albums) = coroutineScope {
            val artistsJob = async { client.getArtists() }
            val albumsJob = async { client.getAlbumList2("alphabeticalByName", 500) }
            Pair(artistsJob.await(), albumsJob.await())
        }
        if (artists.isEmpty() || albums.isEmpty()) return null

        val artist = artists.random()
        val album = albums.random()

        var topTracks = emptyList<SongEntity>()
        var topTracksError = false
        try {
            val result = artistTopTracksRepository.getTopTracks(artist.id, artist.name)
            topTracks = result.fullTracks.take(10)
        } catch (e: Exception) {
            Timber.tag("[Explore]").w(e, "Top tracks failed for '${artist.name}'")
            topTracksError = true
        }

        return ExplorePayload(
            artist = artist,
            album = album,
            topTracks = topTracks,
            topTracksError = topTracks.isEmpty() || topTracksError,
        )
    }
}
