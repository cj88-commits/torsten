package com.recordcollection.app.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

private val Context.playbackConfigDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "playback_config")

class PlaybackConfigStore(private val context: Context) {

    private object Keys {
        val REPLAY_GAIN_ENABLED = booleanPreferencesKey("replay_gain_enabled")
        val SCROBBLING_ENABLED = booleanPreferencesKey("scrobbling_enabled")
    }

    val replayGainEnabled: Flow<Boolean> = context.playbackConfigDataStore.data.map { prefs ->
        prefs[Keys.REPLAY_GAIN_ENABLED] ?: false
    }

    val scrobblingEnabled: Flow<Boolean> = context.playbackConfigDataStore.data.map { prefs ->
        prefs[Keys.SCROBBLING_ENABLED] ?: false
    }

    suspend fun setReplayGainEnabled(enabled: Boolean) {
        Timber.tag("[DB]").d("Setting replayGainEnabled=%b", enabled)
        context.playbackConfigDataStore.edit { prefs ->
            prefs[Keys.REPLAY_GAIN_ENABLED] = enabled
        }
    }

    suspend fun setScrobblingEnabled(enabled: Boolean) {
        Timber.tag("[DB]").d("Setting scrobblingEnabled=%b", enabled)
        context.playbackConfigDataStore.edit { prefs ->
            prefs[Keys.SCROBBLING_ENABLED] = enabled
        }
    }
}
