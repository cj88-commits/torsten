package com.torsten.app.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

private val Context.recentSearchesStore: DataStore<Preferences>
    by preferencesDataStore(name = "recent_searches")

private const val SEPARATOR = "\u001F" // ASCII unit separator — safe inside any search query
private const val MAX_ENTRIES = 10

class RecentSearchesStore(private val context: Context) {

    private object Keys {
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
    }

    val recentSearches: Flow<List<String>> = context.recentSearchesStore.data.map { prefs ->
        prefs[Keys.RECENT_SEARCHES]
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    suspend fun add(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        context.recentSearchesStore.edit { prefs ->
            val current = prefs[Keys.RECENT_SEARCHES]
                ?.split(SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            // Move to front, deduplicate, cap at MAX_ENTRIES
            val updated = (listOf(trimmed) + current.filter { it != trimmed })
                .take(MAX_ENTRIES)
            prefs[Keys.RECENT_SEARCHES] = updated.joinToString(SEPARATOR)
            Timber.tag("[DB]").d("Recent searches updated — %d entries", updated.size)
        }
    }

    suspend fun remove(query: String) {
        context.recentSearchesStore.edit { prefs ->
            val current = prefs[Keys.RECENT_SEARCHES]
                ?.split(SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            prefs[Keys.RECENT_SEARCHES] = current.filter { it != query }.joinToString(SEPARATOR)
        }
    }

    suspend fun clearAll() {
        Timber.tag("[DB]").d("Clearing recent searches")
        context.recentSearchesStore.edit { it.remove(Keys.RECENT_SEARCHES) }
    }
}
