package com.torsten.app.data.api

import com.torsten.app.data.api.dto.AlbumListResponseDto
import com.torsten.app.data.api.dto.AlbumResponseDto
import com.torsten.app.data.api.dto.ArtistInfo2ResponseDto
import com.torsten.app.data.api.dto.ArtistResponseDto
import com.torsten.app.data.api.dto.ArtistsResponseDto
import com.torsten.app.data.api.dto.GenresResponseDto
import com.torsten.app.data.api.dto.PingResponseDto
import com.torsten.app.data.api.dto.PlaylistResponseDto
import com.torsten.app.data.api.dto.PlaylistsResponseDto
import com.torsten.app.data.api.dto.Search3ResponseDto
import com.torsten.app.data.api.dto.StarResponseDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit interface for the Subsonic REST API.
 *
 * Auth params (u, t, s, v, c, f) are injected by [SubsonicAuthInterceptor] —
 * they must NOT appear here. Every suspend function maps 1:1 to an API endpoint.
 *
 * Endpoints that produce binary data (stream, getCoverArt) are not Retrofit calls;
 * their URLs are built by [SubsonicApiClient] with auth params embedded.
 */
internal interface SubsonicApiService {

    @GET("rest/ping")
    suspend fun ping(): PingResponseDto

    @GET("rest/getArtists")
    suspend fun getArtists(): ArtistsResponseDto

    @GET("rest/getArtist")
    suspend fun getArtist(
        @Query("id") id: String,
    ): ArtistResponseDto

    @GET("rest/getArtistInfo2")
    suspend fun getArtistInfo2(
        @Query("id") id: String,
    ): ArtistInfo2ResponseDto

    @GET("rest/getAlbum")
    suspend fun getAlbum(
        @Query("id") id: String,
    ): AlbumResponseDto

    /**
     * @param type One of: alphabeticalByName, byYear, byGenre, recent, starred
     * @param size Maximum number of albums to return (max 500).
     * @param offset Offset into the list for paging.
     */
    @GET("rest/getAlbumList2")
    suspend fun getAlbumList2(
        @Query("type") type: String,
        @Query("size") size: Int,
        @Query("offset") offset: Int,
    ): AlbumListResponseDto

    @GET("rest/getGenres")
    suspend fun getGenres(): GenresResponseDto

    @GET("rest/search3")
    suspend fun search3(
        @Query("query") query: String,
        @Query("songCount") songCount: Int = 5,
        @Query("albumCount") albumCount: Int = 5,
        @Query("artistCount") artistCount: Int = 5,
        @Query("songOffset") songOffset: Int = 0,
        @Query("albumOffset") albumOffset: Int = 0,
        @Query("artistOffset") artistOffset: Int = 0,
    ): Search3ResponseDto

    @GET("rest/star")
    suspend fun star(
        @Query("id") id: String? = null,
        @Query("albumId") albumId: String? = null,
        @Query("artistId") artistId: String? = null,
    ): StarResponseDto

    @GET("rest/unstar")
    suspend fun unstar(
        @Query("id") id: String? = null,
        @Query("albumId") albumId: String? = null,
        @Query("artistId") artistId: String? = null,
    ): StarResponseDto

    /** submission=true → scrobble; submission=false → now-playing notification */
    @GET("rest/scrobble")
    suspend fun scrobble(
        @Query("id") id: String,
        @Query("submission") submission: Boolean = true,
        @Query("time") time: Long? = null,
    ): StarResponseDto

    @GET("rest/getPlaylists")
    suspend fun getPlaylists(): PlaylistsResponseDto

    @GET("rest/getPlaylist")
    suspend fun getPlaylist(@Query("id") id: String): PlaylistResponseDto

    @GET("rest/createPlaylist")
    suspend fun createPlaylist(@Query("name") name: String): PlaylistResponseDto

    /** Pass songIdToAdd to append a track, songIndexToRemove to remove by position. */
    @GET("rest/updatePlaylist")
    suspend fun updatePlaylist(
        @Query("playlistId") playlistId: String,
        @Query("songIdToAdd") songIdToAdd: String? = null,
        @Query("songIndexToRemove") songIndexToRemove: Int? = null,
    ): StarResponseDto

    @GET("rest/deletePlaylist")
    suspend fun deletePlaylist(@Query("id") id: String): StarResponseDto
}
