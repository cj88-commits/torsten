package com.torsten.app.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class DownloadedPlaylistInfo(
    val playlistId: String,
    val name: String,
    val songCount: Int,
    val coverArtId: String?,
    val downloadedAt: Long,
    val songIds: List<String> = emptyList(),
)

private val Context.downloadedPlaylistDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "downloaded_playlists")

class DownloadedPlaylistStore(private val context: Context) {

    private val gson = Gson()
    private val KEY = stringPreferencesKey("playlist_list")
    private val listType = object : TypeToken<List<DownloadedPlaylistInfo>>() {}.type

    val downloadedPlaylists: Flow<List<DownloadedPlaylistInfo>> =
        context.downloadedPlaylistDataStore.data.map { prefs ->
            val json = prefs[KEY] ?: return@map emptyList()
            runCatching { gson.fromJson<List<DownloadedPlaylistInfo>>(json, listType) }
                .getOrDefault(emptyList())
        }

    suspend fun save(info: DownloadedPlaylistInfo) {
        context.downloadedPlaylistDataStore.edit { prefs ->
            val current = readList(prefs)
            val updated = current.filter { it.playlistId != info.playlistId } + info
            prefs[KEY] = gson.toJson(updated)
        }
    }

    suspend fun remove(playlistId: String) {
        context.downloadedPlaylistDataStore.edit { prefs ->
            val current = readList(prefs)
            prefs[KEY] = gson.toJson(current.filter { it.playlistId != playlistId })
        }
    }

    private fun readList(prefs: Preferences): List<DownloadedPlaylistInfo> {
        val json = prefs[KEY] ?: return emptyList()
        return runCatching { gson.fromJson<List<DownloadedPlaylistInfo>>(json, listType) }
            .getOrDefault(emptyList())
    }
}
