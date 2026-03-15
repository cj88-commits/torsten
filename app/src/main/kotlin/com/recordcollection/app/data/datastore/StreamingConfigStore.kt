package com.recordcollection.app.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

private val Context.streamingConfigStore: DataStore<Preferences>
    by preferencesDataStore(name = "streaming_config")

class StreamingConfigStore(private val context: Context) {

    private object Keys {
        val WIFI_FORMAT = stringPreferencesKey("wifi_format")
        val WIFI_MAX_BITRATE = intPreferencesKey("wifi_max_bitrate")
        val MOBILE_FORMAT = stringPreferencesKey("mobile_format")
        val MOBILE_MAX_BITRATE = intPreferencesKey("mobile_max_bitrate")
    }

    val streamingConfig: Flow<StreamingConfig> = context.streamingConfigStore.data.map { prefs ->
        StreamingConfig(
            wifiFormat = prefs[Keys.WIFI_FORMAT] ?: "raw",
            wifiMaxBitRate = prefs[Keys.WIFI_MAX_BITRATE] ?: 0,
            mobileFormat = prefs[Keys.MOBILE_FORMAT] ?: "opus",
            mobileMaxBitRate = prefs[Keys.MOBILE_MAX_BITRATE] ?: 96,
        )
    }

    suspend fun save(config: StreamingConfig) {
        Timber.tag("[DB]").d(
            "Saving streaming config: wifi=%s@%dkbps mobile=%s@%dkbps",
            config.wifiFormat, config.wifiMaxBitRate,
            config.mobileFormat, config.mobileMaxBitRate,
        )
        context.streamingConfigStore.edit { prefs ->
            prefs[Keys.WIFI_FORMAT] = config.wifiFormat
            prefs[Keys.WIFI_MAX_BITRATE] = config.wifiMaxBitRate
            prefs[Keys.MOBILE_FORMAT] = config.mobileFormat
            prefs[Keys.MOBILE_MAX_BITRATE] = config.mobileMaxBitRate
        }
    }
}
