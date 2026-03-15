package com.recordcollection.app.data.api

import com.recordcollection.app.data.api.dto.AlbumListResponseDto
import com.recordcollection.app.data.api.dto.AlbumResponseDto
import com.recordcollection.app.data.api.dto.ArtistInfo2ResponseDto
import com.recordcollection.app.data.api.dto.ArtistResponseDto
import com.recordcollection.app.data.api.dto.ArtistsResponseDto
import com.recordcollection.app.data.api.dto.PingResponseDto
import com.recordcollection.app.data.api.dto.StarResponseDto
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
}
