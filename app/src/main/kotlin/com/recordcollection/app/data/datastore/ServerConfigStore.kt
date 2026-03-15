package com.recordcollection.app.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

private val Context.serverConfigStore: DataStore<Preferences>
    by preferencesDataStore(name = "server_config")

class ServerConfigStore(private val context: Context) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        // Password is stored as plain text in local app-private DataStore.
        // For production, consider encrypting with EncryptedSharedPreferences.
        val PASSWORD = stringPreferencesKey("password")
    }

    val serverConfig: Flow<ServerConfig> = context.serverConfigStore.data.map { prefs ->
        ServerConfig(
            serverUrl = prefs[Keys.SERVER_URL].orEmpty(),
            username = prefs[Keys.USERNAME].orEmpty(),
            password = prefs[Keys.PASSWORD].orEmpty(),
        )
    }

    suspend fun save(config: ServerConfig) {
        Timber.tag("[DB]").d("Saving server config for host: %s", config.serverUrl)
        context.serverConfigStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = config.serverUrl
            prefs[Keys.USERNAME] = config.username
            // Never log password — see RecordCollectionApp logging conventions.
            prefs[Keys.PASSWORD] = config.password
        }
    }

    suspend fun clear() {
        Timber.tag("[DB]").d("Clearing server config")
        context.serverConfigStore.edit { it.clear() }
    }
}
