package com.recordcollection.app.data.api

import com.recordcollection.app.BuildConfig
import com.recordcollection.app.data.api.auth.SubsonicAuthInterceptor
import com.recordcollection.app.data.api.auth.SubsonicTokenAuth
import com.recordcollection.app.data.api.auth.SubsonicTokenAuth.asQueryString
import com.recordcollection.app.data.api.auth.TrustAllCerts
import com.recordcollection.app.data.api.dto.AlbumDto
import com.recordcollection.app.data.api.dto.AlbumWithSongsDto
import com.recordcollection.app.data.api.dto.ArtistDto
import com.recordcollection.app.data.api.dto.ArtistWithAlbumsDto
import com.recordcollection.app.data.datastore.ServerConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Facade over the Subsonic REST API.
 *
 * Create a new instance whenever [ServerConfig] changes — the client is bound
 * to a single set of credentials and a single server URL.
 *
 * Streaming and cover-art methods return fully-qualified URLs with auth params
 * embedded so they can be handed directly to ExoPlayer or Coil without those
 * libraries needing to know about Subsonic auth.
 */
class SubsonicApiClient(private val config: ServerConfig) {

    private val baseUrl: String = config.serverUrl.trimEnd('/') + "/"
    private val service: SubsonicApiService

    init {
        val okHttpClient = buildOkHttpClient()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        service = retrofit.create(SubsonicApiService::class.java)
    }

    // -------------------------------------------------------------------------
    // Connection test
    // -------------------------------------------------------------------------

    /**
     * Pings the server and returns a human-readable server name string,
     * e.g. "Navidrome 0.53.3" or "Subsonic (API 1.16.1)" as a fallback.
     */
    suspend fun ping(): String {
        val body = service.ping().response
        checkStatus(body.status, body.error?.message)
        return when {
            body.type != null && body.serverVersion != null ->
                "${body.type.replaceFirstChar { it.uppercase() }} ${body.serverVersion}"
            body.type != null -> body.type.replaceFirstChar { it.uppercase() }
            else -> "Subsonic server (API ${body.version})"
        }
    }

    // -------------------------------------------------------------------------
    // Artists
    // -------------------------------------------------------------------------

    /** Returns all artists, flattened from the server's alphabetical index grouping. */
    suspend fun getArtists(): List<ArtistDto> {
        val body = service.getArtists().response
        checkStatus(body.status, body.error?.message)
        return body.artists?.index
            ?.flatMap { it.artist.orEmpty() }
            .orEmpty()
    }

    /**
     * Returns the artist's large image URL from an external source (Last.fm / MusicBrainz),
     * or null if the server doesn't support getArtistInfo2 or no image is available.
     * Never throws — failures degrade gracefully to no hero image.
     */
    suspend fun getArtistInfo(id: String): String? = runCatching {
        val body = service.getArtistInfo2(id).response
        body.artistInfo2?.largeImageUrl?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Returns the artist plus their full album list. */
    suspend fun getArtist(id: String): ArtistWithAlbumsDto {
        val body = service.getArtist(id).response
        checkStatus(body.status, body.error?.message)
        return body.artist ?: error("Server returned no artist for id=$id")
    }

    // -------------------------------------------------------------------------
    // Albums
    // -------------------------------------------------------------------------

    /** Returns the album with its full song list. */
    suspend fun getAlbum(id: String): AlbumWithSongsDto {
        val body = service.getAlbum(id).response
        checkStatus(body.status, body.error?.message)
        return body.album ?: error("Server returned no album for id=$id")
    }

    /**
     * Returns a page of albums.
     *
     * @param type  One of: alphabeticalByName, byYear, byGenre, recent, starred
     * @param size  Page size (1–500).
     * @param offset Offset for paging.
     */
    suspend fun getAlbumList2(
        type: String,
        size: Int = 50,
        offset: Int = 0,
    ): List<AlbumDto> {
        val body = service.getAlbumList2(type, size, offset).response
        checkStatus(body.status, body.error?.message)
        return body.albumList2?.album.orEmpty()
    }

    // -------------------------------------------------------------------------
    // URL builders (auth embedded — suitable for Coil / ExoPlayer)
    // -------------------------------------------------------------------------

    /**
     * Returns the full getCoverArt URL with auth params embedded.
     * Pass [size] in pixels; omit to let the server return the original size.
     */
    fun getCoverArtUrl(id: String, size: Int? = null): String {
        val auth = SubsonicTokenAuth.buildAuthParams(config.username, config.password)
        val sizeParam = if (size != null) "&size=$size" else ""
        return "${baseUrl}rest/getCoverArt?id=$id$sizeParam&${auth.asQueryString()}"
    }

    /**
     * Returns the full stream URL with auth params embedded.
     *
     * @param id         Song ID.
     * @param maxBitRate Maximum bit rate in kbps. 0 = serve original quality.
     * @param format     Transcode target: "raw" (no transcode) or "opus".
     */
    fun streamUrl(id: String, maxBitRate: Int = 0, format: String = "raw"): String {
        val auth = SubsonicTokenAuth.buildAuthParams(config.username, config.password)
        return "${baseUrl}rest/stream?id=$id&maxBitRate=$maxBitRate&format=$format&${auth.asQueryString()}"
    }

    // -------------------------------------------------------------------------
    // Starring
    // -------------------------------------------------------------------------

    suspend fun star(
        id: String? = null,
        albumId: String? = null,
        artistId: String? = null,
    ) {
        val body = service.star(id, albumId, artistId).response
        checkStatus(body.status, body.error?.message)
    }

    suspend fun unstar(
        id: String? = null,
        albumId: String? = null,
        artistId: String? = null,
    ) {
        val body = service.unstar(id, albumId, artistId).response
        checkStatus(body.status, body.error?.message)
    }

    // -------------------------------------------------------------------------
    // Scrobbling
    // -------------------------------------------------------------------------

    /**
     * Submits a scrobble or now-playing notification for [songId].
     * Pass [submission] = false for a "now playing" ping, true for a full scrobble.
     * Swallows all errors — scrobble failures must never interrupt playback.
     */
    suspend fun scrobble(songId: String, submission: Boolean = true) {
        runCatching {
            val body = service.scrobble(
                id = songId,
                submission = submission,
                time = if (submission) System.currentTimeMillis() else null,
            ).response
            checkStatus(body.status, body.error?.message)
            if (submission) {
                Timber.tag("[Scrobble]").d("Scrobbled track %s", songId)
            } else {
                Timber.tag("[Scrobble]").d("Now-playing sent for track %s", songId)
            }
        }.onFailure { e ->
            val label = if (submission) "Scrobble" else "Now-playing"
            Timber.tag("[Scrobble]").e(e, "%s failed for track %s", label, songId)
        }
    }

    // -------------------------------------------------------------------------
    // OkHttp construction
    // -------------------------------------------------------------------------

    private fun buildOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(SubsonicAuthInterceptor(config.username, config.password))

        val host = runCatching { URL(config.serverUrl).host }.getOrNull()
        if (host != null && TrustAllCerts.isPrivateHost(host)) {
            TrustAllCerts.applyTrustAll(builder)
        }

        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor { message ->
                // OkHttp logger receives full URLs — only log if no auth params present.
                if (!message.contains("&t=") && !message.contains("?t=")) {
                    Timber.tag("[API]").v(message)
                }
            }.apply { level = HttpLoggingInterceptor.Level.BASIC }
            builder.addNetworkInterceptor(logging)
        }

        return builder.build()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun checkStatus(status: String, errorMessage: String?) {
        if (status != "ok") {
            Timber.tag("[API]").e("Subsonic error: %s", errorMessage ?: "unknown")
            error("Subsonic API error: ${errorMessage ?: "status=$status"}")
        }
    }
}
