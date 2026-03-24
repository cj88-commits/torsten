package com.torsten.app.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.torsten.app.data.api.SubsonicApiClient
import com.torsten.app.data.db.AppDatabase
import com.torsten.app.data.download.CoverArtPrefetchWorker
import com.torsten.app.data.db.entity.AlbumEntity
import com.torsten.app.data.db.entity.ArtistEntity
import com.torsten.app.data.db.entity.DownloadState
import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.data.db.entity.SyncMetadataEntity
import com.torsten.app.data.datastore.ServerConfigStore
import com.torsten.app.data.network.ConnectivityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import retrofit2.HttpException
import timber.log.Timber
import java.net.SocketTimeoutException

private const val SYNC_THROTTLE_MS = 15 * 60 * 1_000L
private const val KEY_LAST_SYNC = "lastSuccessfulSyncAt"
private const val ALBUM_PAGE_SIZE = 500

enum class SyncErrorType { TIMEOUT, AUTH_FAILED, SERVER_ERROR, NETWORK_ERROR }

class SyncRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val configStore: ServerConfigStore,
    private val connectivityMonitor: ConnectivityMonitor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /** Emits a [SyncErrorType] whenever a sync attempt fails. */
    private val _syncError = MutableSharedFlow<SyncErrorType>(extraBufferCapacity = 1)
    val syncError: SharedFlow<SyncErrorType> = _syncError.asSharedFlow()

    /**
     * Triggers a full artist + album sync. Silently skipped when:
     *  - already syncing
     *  - offline
     *  - no server config
     *  - last sync was within 15 minutes (unless [force] = true)
     *
     * Songs are NOT fetched here — call [fetchAndCacheSongs] lazily on album detail open.
     */
    fun triggerSync(force: Boolean = false) {
        if (_syncState.value == SyncState.SYNCING) return
        scope.launch { sync(force) }
    }

    private suspend fun sync(force: Boolean) {
        if (!connectivityMonitor.isOnline.value) {
            Timber.tag("[Sync]").d("Offline — skipping sync")
            return
        }

        if (!force) {
            val lastSync = db.syncMetadataDao().getValue(KEY_LAST_SYNC)?.toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - lastSync < SYNC_THROTTLE_MS) {
                Timber.tag("[Sync]").d("Throttled — last sync was < 15 min ago")
                return
            }
        }

        val config = configStore.serverConfig.first()
        if (!config.isConfigured) {
            Timber.tag("[Sync]").d("No server config — skipping sync")
            return
        }

        _syncState.value = SyncState.SYNCING
        Timber.tag("[Sync]").i("Sync started")

        runCatching {
            val client = SubsonicApiClient(config)
            val now = System.currentTimeMillis()

            // --- Artists ---
            val artistDtos = client.getArtists()
            val existingArtistImageUrls = db.artistDao().getAll().associate { it.id to it.artistImageUrl }
            val artistEntities = artistDtos.map { dto ->
                ArtistEntity(
                    id = dto.id,
                    name = dto.name,
                    albumCount = dto.albumCount,
                    starred = dto.starred != null,
                    lastUpdated = now,
                    artistImageUrl = existingArtistImageUrls[dto.id],
                )
            }
            db.artistDao().upsertAll(artistEntities)
            Timber.tag("[Sync]").i("Upserted %d artists", artistEntities.size)

            // --- Albums (paginated) ---
            val allAlbumDtos = buildList {
                var offset = 0
                while (true) {
                    val page = client.getAlbumList2("alphabeticalByName", ALBUM_PAGE_SIZE, offset)
                    addAll(page)
                    if (page.size < ALBUM_PAGE_SIZE) break
                    offset += page.size
                }
            }
            val albumEntities = allAlbumDtos.map { dto ->
                // Preserve any existing download state — only set NONE for new rows.
                val existing = db.albumDao().getById(dto.id)
                AlbumEntity(
                    id = dto.id,
                    artistId = dto.artistId.orEmpty(),
                    artistName = dto.artist.orEmpty(),
                    title = dto.name,
                    year = dto.year,
                    genre = dto.genre,
                    songCount = dto.songCount,
                    duration = dto.duration,
                    coverArtId = dto.coverArt,
                    starred = dto.starred != null,
                    downloadState = existing?.downloadState ?: DownloadState.NONE,
                    downloadProgress = existing?.downloadProgress ?: 0,
                    downloadedAt = existing?.downloadedAt,
                    lastUpdated = now,
                )
            }
            db.albumDao().upsertAll(albumEntities)
            Timber.tag("[Sync]").i("Upserted %d albums", albumEntities.size)

            // Record success timestamp
            db.syncMetadataDao().setValue(SyncMetadataEntity(KEY_LAST_SYNC, now.toString()))
        }.onSuccess {
            Timber.tag("[Sync]").i("Sync complete")
            _syncState.value = SyncState.IDLE
            enqueueCoverArtPrefetch()
        }.onFailure { e ->
            Timber.tag("[Sync]").e(e, "Sync failed")
            _syncState.value = SyncState.ERROR
            _syncError.tryEmit(e.toSyncErrorType())
        }
    }

    private fun enqueueCoverArtPrefetch() {
        val request = OneTimeWorkRequestBuilder<CoverArtPrefetchWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "cover_art_prefetch",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Fetches songs for a single album from the API and caches them in Room.
     * Call this lazily when an Album Detail screen opens. No-op if offline or unconfigured.
     */
    suspend fun fetchAndCacheSongs(albumId: String) {
        if (!connectivityMonitor.isOnline.value) return

        val config = configStore.serverConfig.first()
        if (!config.isConfigured) return

        runCatching {
            val client = SubsonicApiClient(config)
            val albumWithSongs = client.getAlbum(albumId)
            val now = System.currentTimeMillis()

            // Re-read existing rows so we can carry localFilePath forward.
            // upsertAll uses OnConflictStrategy.REPLACE, which would otherwise overwrite the
            // file paths written by DownloadWorker and break offline playback.
            val existingPaths = db.songDao().getByAlbum(albumId).associate { it.id to it.localFilePath }

            // If the album is not in the local DB (e.g. it was discovered via getAlbumList2
            // on the Home screen but the library has never been synced), insert it now so that
            // AlbumDetailScreen can display the title and cover art immediately.
            if (db.albumDao().getById(albumId) == null) {
                db.albumDao().upsertAll(listOf(
                    AlbumEntity(
                        id = albumWithSongs.id,
                        title = albumWithSongs.name,
                        artistId = albumWithSongs.artistId.orEmpty(),
                        artistName = albumWithSongs.artist.orEmpty(),
                        year = albumWithSongs.year,
                        genre = albumWithSongs.genre,
                        songCount = albumWithSongs.songCount,
                        duration = albumWithSongs.duration,
                        coverArtId = albumWithSongs.coverArt,
                        starred = albumWithSongs.starred != null,
                        downloadState = DownloadState.NONE,
                        downloadProgress = 0,
                        downloadedAt = null,
                        lastUpdated = now,
                    )
                ))
                Timber.tag("[Sync]").d("Cached album entity for %s", albumId)
            }

            val songEntities = albumWithSongs.song.orEmpty().map { dto ->
                // Gson bypasses Kotlin null-safety; guard title explicitly.
                @Suppress("SENSELESS_COMPARISON")
                val safeTitle = if (dto.title == null) "" else dto.title
                SongEntity(
                    id = dto.id,
                    albumId = albumId,
                    artistId = dto.artistId.orEmpty(),
                    title = safeTitle,
                    trackNumber = dto.track ?: 0,
                    discNumber = dto.discNumber ?: 1,
                    duration = dto.duration ?: 0,
                    bitRate = dto.bitRate,
                    suffix = dto.suffix,
                    contentType = dto.contentType,
                    starred = dto.starred != null,
                    localFilePath = existingPaths[dto.id],
                    lastUpdated = now,
                )
            }
            db.songDao().upsertAll(songEntities)
            Timber.tag("[Sync]").d("Cached %d songs for album %s", songEntities.size, albumId)
        }.onFailure { e ->
            Timber.tag("[Sync]").e(e, "Failed to cache songs for album %s", albumId)
        }
    }
}

private fun Throwable.toSyncErrorType(): SyncErrorType = when {
    this is SocketTimeoutException || cause is SocketTimeoutException -> SyncErrorType.TIMEOUT
    this is HttpException && code() == 401 -> SyncErrorType.AUTH_FAILED
    this is HttpException && code() >= 500 -> SyncErrorType.SERVER_ERROR
    message?.contains("Wrong username or password", ignoreCase = true) == true -> SyncErrorType.AUTH_FAILED
    message?.contains("error code 40", ignoreCase = true) == true -> SyncErrorType.AUTH_FAILED
    else -> SyncErrorType.NETWORK_ERROR
}
