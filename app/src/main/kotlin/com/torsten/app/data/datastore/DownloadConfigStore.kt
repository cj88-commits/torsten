package com.torsten.app.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

private val Context.downloadConfigDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "download_config")

class DownloadConfigStore(private val context: Context) {

    private object Keys {
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val DOWNLOAD_FORMAT = stringPreferencesKey("download_format")
        val DOWNLOAD_MAX_BITRATE = intPreferencesKey("download_max_bitrate")
    }

    /** True (default) = only download over unmetered (WiFi) networks. */
    val wifiOnly: Flow<Boolean> = context.downloadConfigDataStore.data.map { prefs ->
        prefs[Keys.WIFI_ONLY] ?: true
    }

    /** Download format string (default "raw" = original quality). */
    val downloadFormat: Flow<String> = context.downloadConfigDataStore.data.map { prefs ->
        prefs[Keys.DOWNLOAD_FORMAT] ?: "raw"
    }

    /** Download max bit rate in kbps (default 0 = unlimited). */
    val downloadMaxBitRate: Flow<Int> = context.downloadConfigDataStore.data.map { prefs ->
        prefs[Keys.DOWNLOAD_MAX_BITRATE] ?: 0
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        Timber.tag("[DB]").d("Setting download wifiOnly=%b", enabled)
        context.downloadConfigDataStore.edit { prefs ->
            prefs[Keys.WIFI_ONLY] = enabled
        }
    }

    suspend fun setDownloadQuality(format: String, maxBitRate: Int) {
        Timber.tag("[DB]").d("Setting download quality format=%s maxBitRate=%d", format, maxBitRate)
        context.downloadConfigDataStore.edit { prefs ->
            prefs[Keys.DOWNLOAD_FORMAT] = format
            prefs[Keys.DOWNLOAD_MAX_BITRATE] = maxBitRate
        }
    }
}
