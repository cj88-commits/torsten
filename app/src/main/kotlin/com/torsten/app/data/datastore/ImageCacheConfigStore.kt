package com.torsten.app.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

private val Context.imageCacheDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "image_cache_config")

class ImageCacheConfigStore(private val context: Context) {

    private object Keys {
        val CACHE_SIZE_LIMIT_MB = intPreferencesKey("cache_size_limit_mb")
    }

    /** Image disk-cache size limit in MB. Default 1 024 (1 GB). */
    val cacheSizeLimitMb: Flow<Int> = context.imageCacheDataStore.data.map { prefs ->
        prefs[Keys.CACHE_SIZE_LIMIT_MB] ?: 1024
    }

    suspend fun setCacheSizeLimitMb(limitMb: Int) {
        Timber.tag("[DB]").d("Setting imageCacheSizeLimitMb=%d", limitMb)
        context.imageCacheDataStore.edit { prefs ->
            prefs[Keys.CACHE_SIZE_LIMIT_MB] = limitMb
        }
    }
}
