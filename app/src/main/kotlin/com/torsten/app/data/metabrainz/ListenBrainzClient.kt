package com.torsten.app.data.metabrainz

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

data class LbRecordingCandidate(val trackName: String, val listenCount: Int)

class ListenBrainzClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun getArtistRecordingStats(mbid: String): List<LbRecordingCandidate> {
        return try {
            val url = "https://api.listenbrainz.org/1/popularity/top-recordings-for-artist/$mbid"
            val request = Request.Builder().url(url).build()
            val (statusCode, bodyText) = httpClient.newCall(request).execute().use { resp ->
                resp.code to (resp.body?.string() ?: "")
            }

            if (statusCode != 200 || bodyText.isEmpty()) {
                Timber.tag("[ArtistTop]").w(
                    "LB popularity fetch failed for $mbid: HTTP $statusCode, body=${bodyText.take(200)}",
                )
                return emptyList()
            }

            // Response is a direct JSON array, not nested under payload
            val recordings = org.json.JSONArray(bodyText)

            val candidates = mutableListOf<LbRecordingCandidate>()
            for (i in 0 until recordings.length()) {
                val rec = recordings.getJSONObject(i)
                val name = rec.optString("recording_name", "")
                val count = rec.optInt("total_listen_count", 0)
                if (name.isNotEmpty()) candidates.add(LbRecordingCandidate(name, count))
            }

            if (candidates.isEmpty()) {
                Timber.tag("[ArtistTop]").w(
                    "LB returned empty candidates for $mbid (HTTP $statusCode), raw=${bodyText.take(200)}",
                )
            }

            candidates.sortedByDescending { it.listenCount }
        } catch (e: Exception) {
            Timber.tag("[ArtistTop]").w("Stats fetch failed for $mbid: ${e.message}")
            emptyList()
        }
    }
}
