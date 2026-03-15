package com.recordcollection.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import com.recordcollection.app.RecordCollectionApp
import com.recordcollection.app.data.api.SubsonicApiClient
import com.recordcollection.app.data.datastore.DownloadConfigStore
import com.recordcollection.app.data.datastore.ImageCacheConfigStore
import com.recordcollection.app.data.datastore.PlaybackConfigStore
import com.recordcollection.app.data.datastore.ServerConfig
import com.recordcollection.app.data.datastore.ServerConfigStore
import com.recordcollection.app.data.datastore.StreamingConfig
import com.recordcollection.app.data.datastore.StreamingConfigStore
import com.recordcollection.app.data.download.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

// ─── Quality options ──────────────────────────────────────────────────────────

enum class WifiQuality(val label: String, val format: String, val maxBitRate: Int) {
    ORIGINAL("Original (no transcoding)", "raw", 0),
    OPUS_32("Opus 32 kbps", "opus", 32),
    OPUS_64("Opus 64 kbps", "opus", 64),
    OPUS_96("Opus 96 kbps", "opus", 96),
    OPUS_128("Opus 128 kbps", "opus", 128),
    OPUS_192("Opus 192 kbps", "opus", 192),
}

enum class MobileQuality(val label: String, val format: String, val maxBitRate: Int) {
    ORIGINAL("Original (no transcoding)", "raw", 0),
    OPUS_32("Opus 32 kbps", "opus", 32),
    OPUS_64("Opus 64 kbps", "opus", 64),
    OPUS_96("Opus 96 kbps", "opus", 96),
    OPUS_128("Opus 128 kbps", "opus", 128),
    OPUS_192("Opus 192 kbps", "opus", 192),
}

enum class DownloadFormat(val label: String, val format: String) {
    ORIGINAL("Original", "raw"),
    FLAC("FLAC", "flac"),
    MP3("MP3", "mp3"),
    OPUS("Opus", "opus"),
    AAC("AAC", "aac"),
}

enum class DownloadBitRate(val label: String, val maxBitRate: Int) {
    ORIGINAL("Original", 0),
    KBPS_320("320 kbps", 320),
    KBPS_192("192 kbps", 192),
    KBPS_128("128 kbps", 128),
}

enum class ImageCacheSizeLimit(val label: String, val mb: Int) {
    MB_250("250 MB", 250),
    MB_500("500 MB", 500),
    MB_1024("1 GB", 1024),
    MB_2048("2 GB", 2048),
}

private fun StreamingConfig.toWifiQuality(): WifiQuality =
    WifiQuality.entries.firstOrNull { it.format == wifiFormat && it.maxBitRate == wifiMaxBitRate }
        ?: WifiQuality.ORIGINAL

private fun StreamingConfig.toMobileQuality(): MobileQuality =
    MobileQuality.entries.firstOrNull { it.format == mobileFormat && it.maxBitRate == mobileMaxBitRate }
        ?: MobileQuality.OPUS_96

// ─── UI state ─────────────────────────────────────────────────────────────────

data class SettingsUiState(
    // Server
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isTesting: Boolean = false,
    val testResult: TestResult? = null,
    // Streaming
    val wifiQuality: WifiQuality = WifiQuality.ORIGINAL,
    val mobileQuality: MobileQuality = MobileQuality.OPUS_96,
    // Downloads
    val wifiOnlyDownload: Boolean = true,
    val downloadFormat: DownloadFormat = DownloadFormat.ORIGINAL,
    val downloadBitRate: DownloadBitRate = DownloadBitRate.ORIGINAL,
    val showClearDownloadsDialog: Boolean = false,
    // Playback
    val replayGainEnabled: Boolean = false,
    val scrobblingEnabled: Boolean = false,
    // Image cache
    val imageCacheSizeLimit: ImageCacheSizeLimit = ImageCacheSizeLimit.MB_1024,
    val currentCacheSizeMb: Long = 0L,
    val showClearCacheDialog: Boolean = false,
) {
    sealed interface TestResult {
        data class Success(val serverName: String) : TestResult
        data class Failure(val message: String) : TestResult
    }

    val showHttpWarning: Boolean
        get() = serverUrl.trimStart().lowercase().startsWith("http://")

    val canTest: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !isTesting

    /** Bitrate selection is irrelevant for lossless/native formats. */
    val downloadBitRateEnabled: Boolean
        get() = downloadFormat != DownloadFormat.ORIGINAL && downloadFormat != DownloadFormat.FLAC
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

class SettingsViewModel(
    private val appContext: Context,
    private val configStore: ServerConfigStore,
    private val streamingStore: StreamingConfigStore,
    private val playbackStore: PlaybackConfigStore,
    private val downloadConfigStore: DownloadConfigStore,
    private val downloadRepository: DownloadRepository,
    private val imageCacheConfigStore: ImageCacheConfigStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Emits once when the user has successfully saved and should navigate to the grid. */
    private val _navigateToGrid = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToGrid: SharedFlow<Unit> = _navigateToGrid.asSharedFlow()

    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    init {
        viewModelScope.launch {
            // Load all settings in parallel
            launch {
                configStore.serverConfig.collect { saved ->
                    if (saved.isConfigured) {
                        _uiState.update {
                            it.copy(
                                serverUrl = saved.serverUrl,
                                username = saved.username,
                                password = saved.password,
                            )
                        }
                    }
                }
            }
            launch {
                streamingStore.streamingConfig.collect { cfg ->
                    _uiState.update {
                        it.copy(
                            wifiQuality = cfg.toWifiQuality(),
                            mobileQuality = cfg.toMobileQuality(),
                        )
                    }
                }
            }
            launch {
                playbackStore.replayGainEnabled.collect { enabled ->
                    _uiState.update { it.copy(replayGainEnabled = enabled) }
                }
            }
            launch {
                playbackStore.scrobblingEnabled.collect { enabled ->
                    _uiState.update { it.copy(scrobblingEnabled = enabled) }
                }
            }
            launch {
                downloadConfigStore.wifiOnly.collect { wifiOnly ->
                    _uiState.update { it.copy(wifiOnlyDownload = wifiOnly) }
                }
            }
            launch {
                combine(
                    downloadConfigStore.downloadFormat,
                    downloadConfigStore.downloadMaxBitRate,
                ) { format, bitRate -> format to bitRate }
                    .collect { (format, bitRate) ->
                        val dlFormat = DownloadFormat.entries.firstOrNull { it.format == format }
                            ?: DownloadFormat.ORIGINAL
                        val dlBitRate = DownloadBitRate.entries.firstOrNull { it.maxBitRate == bitRate }
                            ?: DownloadBitRate.ORIGINAL
                        _uiState.update { it.copy(downloadFormat = dlFormat, downloadBitRate = dlBitRate) }
                    }
            }
            launch {
                imageCacheConfigStore.cacheSizeLimitMb.collect { mb ->
                    val limit = ImageCacheSizeLimit.entries.firstOrNull { it.mb == mb }
                        ?: ImageCacheSizeLimit.MB_1024
                    _uiState.update { it.copy(imageCacheSizeLimit = limit) }
                }
            }
            launch(Dispatchers.IO) {
                val sizeMb = (SingletonImageLoader.get(appContext).diskCache?.size ?: 0L) / (1024L * 1024L)
                _uiState.update { it.copy(currentCacheSizeMb = sizeMb) }
            }
        }
    }

    // ─── Server ──────────────────────────────────────────────────────────────

    fun onServerUrlChange(value: String) =
        _uiState.update { it.copy(serverUrl = value, testResult = null) }

    fun onUsernameChange(value: String) =
        _uiState.update { it.copy(username = value, testResult = null) }

    fun onPasswordChange(value: String) =
        _uiState.update { it.copy(password = value, testResult = null) }

    fun testConnection() {
        val state = _uiState.value
        if (!state.canTest) return

        _uiState.update { it.copy(isTesting = true, testResult = null) }
        viewModelScope.launch {
            val config = ServerConfig(
                serverUrl = state.serverUrl.trim(),
                username = state.username.trim(),
                password = state.password,
            )
            val result = withContext(Dispatchers.IO) {
                runCatching { SubsonicApiClient(config).ping() }
            }
            result
                .onSuccess { serverName ->
                    Timber.tag("[API]").i("Ping success: %s", serverName)
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            testResult = SettingsUiState.TestResult.Success(serverName),
                        )
                    }
                }
                .onFailure { error ->
                    Timber.tag("[API]").e(error, "Ping failed")
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            testResult = SettingsUiState.TestResult.Failure(
                                error.message ?: "Connection failed",
                            ),
                        )
                    }
                }
        }
    }

    fun saveAndContinue() {
        val state = _uiState.value
        if (state.testResult !is SettingsUiState.TestResult.Success) return
        viewModelScope.launch {
            val config = ServerConfig(
                serverUrl = state.serverUrl.trim(),
                username = state.username.trim(),
                password = state.password,
            )
            configStore.save(config)
            Timber.tag("[DB]").i("Server config saved")
            _navigateToGrid.tryEmit(Unit)
        }
    }

    // ─── Streaming ───────────────────────────────────────────────────────────

    fun setWifiQuality(quality: WifiQuality) {
        _uiState.update { it.copy(wifiQuality = quality) }
        viewModelScope.launch {
            val current = streamingStore.streamingConfig.first()
            streamingStore.save(current.copy(wifiFormat = quality.format, wifiMaxBitRate = quality.maxBitRate))
            Timber.tag("[DB]").d("WiFi quality set: %s", quality.name)
        }
    }

    fun setMobileQuality(quality: MobileQuality) {
        _uiState.update { it.copy(mobileQuality = quality) }
        viewModelScope.launch {
            val current = streamingStore.streamingConfig.first()
            streamingStore.save(current.copy(mobileFormat = quality.format, mobileMaxBitRate = quality.maxBitRate))
            Timber.tag("[DB]").d("Mobile quality set: %s", quality.name)
        }
    }

    // ─── Downloads ───────────────────────────────────────────────────────────

    fun setWifiOnlyDownload(enabled: Boolean) {
        _uiState.update { it.copy(wifiOnlyDownload = enabled) }
        viewModelScope.launch {
            downloadConfigStore.setWifiOnly(enabled)
            Timber.tag("[DB]").d("wifiOnlyDownload=%b", enabled)
        }
    }

    fun setDownloadFormat(format: DownloadFormat) {
        // Reset bitrate to ORIGINAL when switching to a lossless/native format
        val bitRate = if (format == DownloadFormat.ORIGINAL || format == DownloadFormat.FLAC) {
            DownloadBitRate.ORIGINAL
        } else {
            _uiState.value.downloadBitRate
        }
        _uiState.update { it.copy(downloadFormat = format, downloadBitRate = bitRate) }
        viewModelScope.launch {
            downloadConfigStore.setDownloadQuality(format.format, bitRate.maxBitRate)
            Timber.tag("[DB]").d("Download format set: %s", format.name)
        }
    }

    fun setDownloadBitRate(bitRate: DownloadBitRate) {
        _uiState.update { it.copy(downloadBitRate = bitRate) }
        viewModelScope.launch {
            downloadConfigStore.setDownloadQuality(_uiState.value.downloadFormat.format, bitRate.maxBitRate)
            Timber.tag("[DB]").d("Download bitrate set: %s", bitRate.name)
        }
    }

    fun showClearDownloadsDialog() {
        _uiState.update { it.copy(showClearDownloadsDialog = true) }
    }

    fun dismissClearDownloadsDialog() {
        _uiState.update { it.copy(showClearDownloadsDialog = false) }
    }

    fun clearAllDownloads() {
        _uiState.update { it.copy(showClearDownloadsDialog = false) }
        viewModelScope.launch {
            try {
                downloadRepository.clearAllDownloads()
                Timber.tag("[Download]").i("All downloads cleared")
                _snackbarEvent.tryEmit("All downloads cleared")
            } catch (e: Exception) {
                Timber.tag("[Download]").e(e, "Failed to clear downloads")
                _snackbarEvent.tryEmit("Failed to clear downloads")
            }
        }
    }

    // ─── Playback ────────────────────────────────────────────────────────────

    fun setReplayGainEnabled(enabled: Boolean) {
        _uiState.update { it.copy(replayGainEnabled = enabled) }
        viewModelScope.launch {
            playbackStore.setReplayGainEnabled(enabled)
            Timber.tag("[DB]").d("replayGainEnabled=%b", enabled)
        }
    }

    fun setScrobblingEnabled(enabled: Boolean) {
        _uiState.update { it.copy(scrobblingEnabled = enabled) }
        viewModelScope.launch {
            playbackStore.setScrobblingEnabled(enabled)
            Timber.tag("[DB]").d("scrobblingEnabled=%b", enabled)
        }
    }

    // ─── Image cache ─────────────────────────────────────────────────────────

    fun setImageCacheSizeLimit(limit: ImageCacheSizeLimit) {
        _uiState.update { it.copy(imageCacheSizeLimit = limit) }
        viewModelScope.launch {
            imageCacheConfigStore.setCacheSizeLimitMb(limit.mb)
            Timber.tag("[DB]").d("imageCacheSizeLimit=%d MB", limit.mb)
        }
    }

    fun showClearCacheDialog() {
        _uiState.update { it.copy(showClearCacheDialog = true) }
    }

    fun dismissClearCacheDialog() {
        _uiState.update { it.copy(showClearCacheDialog = false) }
    }

    fun clearImageCache() {
        _uiState.update { it.copy(showClearCacheDialog = false) }
        viewModelScope.launch(Dispatchers.IO) {
            SingletonImageLoader.get(appContext).diskCache?.clear()
            _uiState.update { it.copy(currentCacheSizeMb = 0L) }
            _snackbarEvent.tryEmit("Image cache cleared")
            Timber.tag("[Download]").i("Image cache cleared")
        }
    }
}

class SettingsViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = context.applicationContext as RecordCollectionApp
        return SettingsViewModel(
            appContext = app,
            configStore = ServerConfigStore(app),
            streamingStore = StreamingConfigStore(app),
            playbackStore = app.playbackConfigStore,
            downloadConfigStore = app.downloadConfigStore,
            downloadRepository = app.downloadRepository,
            imageCacheConfigStore = app.imageCacheConfigStore,
        ) as T
    }
}
