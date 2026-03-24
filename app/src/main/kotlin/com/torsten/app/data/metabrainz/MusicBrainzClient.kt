package com.torsten.app.data.metabrainz

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MusicBrainzClient {

    private val mutex = Mutex()
    private var lastCallMs = 0L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun getArtistMbid(artistName: String): String? = mutex.withLock {
        val now = System.currentTimeMillis()
        val elapsed = now - lastCallMs
        if (elapsed < 1_000L) delay(1_000L - elapsed)
        lastCallMs = System.currentTimeMillis()

        return@withLock try {
            val encoded = URLEncoder.encode("\"$artistName\"", "UTF-8")
            val url = "https://musicbrainz.org/ws/2/artist/?query=artist:$encoded&fmt=json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Torsten/1.0 (torsten-app)")
                .build()
            val body = httpClient.newCall(request).execute().use { it.body?.string() }
                ?: return@withLock null

            val artists = JSONObject(body).optJSONArray("artists")
                ?: return@withLock null
            if (artists.length() == 0) return@withLock null

            val first = artists.getJSONObject(0)
            val name = first.optString("name", "")
            if (!name.equals(artistName, ignoreCase = true)) return@withLock null

            first.optString("id").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Timber.tag("[ArtistTop]").w("MusicBrainz lookup failed for '$artistName': ${e.message}")
            null
        }
    }
}
