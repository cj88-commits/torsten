package com.torsten.app.data.api.dto

import com.google.gson.annotations.SerializedName

// ---------------------------------------------------------------------------
// Envelope — every Subsonic JSON response is wrapped in "subsonic-response"
// ---------------------------------------------------------------------------

data class PingResponseDto(
    @SerializedName("subsonic-response") val response: PingBodyDto,
)

data class PingBodyDto(
    val status: String,
    val version: String,
    /** Server software type, e.g. "navidrome". Not present on all servers. */
    val type: String? = null,
    /** Server software version, e.g. "0.53.3". Not present on all servers. */
    val serverVersion: String? = null,
    val error: SubsonicErrorDto? = null,
)

// ---------------------------------------------------------------------------

data class ArtistsResponseDto(
    @SerializedName("subsonic-response") val response: ArtistsBodyDto,
)

data class ArtistsBodyDto(
    val status: String,
    val version: String,
    val artists: ArtistsContainerDto? = null,
    val error: SubsonicErrorDto? = null,
)

data class ArtistsContainerDto(
    val ignoredArticles: String? = null,
    /** Artists grouped by first-letter index. */
    val index: List<ArtistIndexDto>? = null,
)

data class ArtistIndexDto(
    val name: String,
    val artist: List<ArtistDto>? = null,
)

// ---------------------------------------------------------------------------

data class ArtistResponseDto(
    @SerializedName("subsonic-response") val response: ArtistBodyDto,
)

data class ArtistBodyDto(
    val status: String,
    val version: String,
    val artist: ArtistWithAlbumsDto? = null,
    val error: SubsonicErrorDto? = null,
)

// ---------------------------------------------------------------------------

data class AlbumResponseDto(
    @SerializedName("subsonic-response") val response: AlbumBodyDto,
)

data class AlbumBodyDto(
    val status: String,
    val version: String,
    val album: AlbumWithSongsDto? = null,
    val error: SubsonicErrorDto? = null,
)

// ---------------------------------------------------------------------------

data class AlbumListResponseDto(
    @SerializedName("subsonic-response") val response: AlbumListBodyDto,
)

data class AlbumListBodyDto(
    val status: String,
    val version: String,
    val albumList2: AlbumList2ContainerDto? = null,
    val error: SubsonicErrorDto? = null,
)

data class AlbumList2ContainerDto(
    val album: List<AlbumDto>? = null,
)

// ---------------------------------------------------------------------------

data class StarResponseDto(
    @SerializedName("subsonic-response") val response: BaseBodyDto,
)

data class BaseBodyDto(
    val status: String,
    val version: String,
    val error: SubsonicErrorDto? = null,
)

// ---------------------------------------------------------------------------
// Shared entity shapes
// ---------------------------------------------------------------------------

data class ArtistDto(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int = 0,
    /** ISO-8601 date string set when the user has starred this artist. */
    val starred: String? = null,
)

data class ArtistWithAlbumsDto(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val album: List<AlbumDto>? = null,
)

data class AlbumDto(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val genre: String? = null,
    /** ISO-8601 date string set when the user has starred this album. */
    val starred: String? = null,
)

data class AlbumWithSongsDto(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val genre: String? = null,
    val starred: String? = null,
    val song: List<SongDto>? = null,
)

data class SongDto(
    val id: String,
    val title: String,
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val albumArtist: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
    val size: Long? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    val bitRate: Int? = null,
    val genre: String? = null,
    /** ISO-8601 date string set when the user has starred this song. */
    val starred: String? = null,
)

// ---------------------------------------------------------------------------
// Artist info (getArtistInfo2 — biography + external images)
// ---------------------------------------------------------------------------

data class ArtistInfo2ResponseDto(
    @SerializedName("subsonic-response") val response: ArtistInfo2BodyDto,
)

data class ArtistInfo2BodyDto(
    val status: String,
    val version: String,
    val artistInfo2: ArtistInfo2Dto? = null,
    val error: SubsonicErrorDto? = null,
)

data class ArtistInfo2Dto(
    val biography: String? = null,
    val musicBrainzId: String? = null,
    val lastFmUrl: String? = null,
    val smallImageUrl: String? = null,
    val mediumImageUrl: String? = null,
    val largeImageUrl: String? = null,
)

// ---------------------------------------------------------------------------
// Genres (getGenres)
// ---------------------------------------------------------------------------

data class GenresResponseDto(
    @SerializedName("subsonic-response") val response: GenresBodyDto,
)

data class GenresBodyDto(
    val status: String,
    val version: String,
    val genres: GenresContainerDto? = null,
    val error: SubsonicErrorDto? = null,
)

data class GenresContainerDto(
    val genre: List<GenreDto>? = null,
)

/**
 * In the Subsonic JSON format the genre name is the XML text content, serialised
 * as the "value" key in JSON (e.g. {"value":"Rock","songCount":9,"albumCount":3}).
 */
data class GenreDto(
    @SerializedName("value") val name: String,
    val songCount: Int = 0,
    val albumCount: Int = 0,
)

// ---------------------------------------------------------------------------
// Search (search3)
// ---------------------------------------------------------------------------

data class Search3ResponseDto(
    @SerializedName("subsonic-response") val response: Search3BodyDto,
)

data class Search3BodyDto(
    val status: String,
    val version: String,
    val searchResult3: Search3ResultDto? = null,
    val error: SubsonicErrorDto? = null,
)

data class Search3ResultDto(
    @SerializedName("song")   val song: List<SongDto>? = null,
    @SerializedName("album")  val album: List<AlbumDto>? = null,
    @SerializedName("artist") val artist: List<ArtistDto>? = null,
)

// ---------------------------------------------------------------------------
// Error
// ---------------------------------------------------------------------------

data class SubsonicErrorDto(
    val code: Int,
    val message: String? = null,
)

// ---------------------------------------------------------------------------
// Playlists (getPlaylists / getPlaylist / createPlaylist / updatePlaylist)
// ---------------------------------------------------------------------------

data class PlaylistsResponseDto(
    @SerializedName("subsonic-response") val response: PlaylistsBodyDto,
)

data class PlaylistsBodyDto(
    val status: String,
    val version: String,
    val playlists: PlaylistsContainerDto? = null,
    val error: SubsonicErrorDto? = null,
)

data class PlaylistsContainerDto(
    val playlist: List<PlaylistDto>? = null,
)

data class PlaylistDto(
    val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val created: String? = null,
    val changed: String? = null,
)

data class PlaylistResponseDto(
    @SerializedName("subsonic-response") val response: PlaylistBodyDto,
)

data class PlaylistBodyDto(
    val status: String,
    val version: String,
    val playlist: PlaylistWithTracksDto? = null,
    val error: SubsonicErrorDto? = null,
)

data class PlaylistWithTracksDto(
    val id: String,
    val name: String,
    val comment: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val entry: List<SongDto>? = null,
)

// ---------------------------------------------------------------------------
// Instant Mix (getSimilarArtists2 / getTopSongs / getSimilarSongs2 / getSongsByGenre)
// ---------------------------------------------------------------------------

data class SimilarArtists2ResponseDto(
    @SerializedName("subsonic-response") val response: SimilarArtists2BodyDto,
)

data class SimilarArtists2BodyDto(
    val status: String,
    val version: String,
    val similarArtists2: SimilarArtists2ContainerDto? = null,
    val error: SubsonicErrorDto? = null,
)

data class SimilarArtists2ContainerDto(
    val artist: List<ArtistDto>? = null,
)

data class TopSongsResponseDto(
    @SerializedName("subsonic-response") val response: TopSongsBodyDto,
)

data class TopSongsBodyDto(
    val status: String,
    val version: String,
    val topSongs: TopSongsContainerDto? = null,
    val error: SubsonicErrorDto? = null,
)

data class TopSongsContainerDto(
    val song: List<SongDto>? = null,
)

// ---------------------------------------------------------------------------
// Instant Mix (getSimilarSongs / getSimilarSongs2 / getSongsByGenre)
// ---------------------------------------------------------------------------

data class SimilarSongsResponseDto(
    @SerializedName("subsonic-response") val response: SimilarSongsBodyDto,
)

data class SimilarSongsBodyDto(
    val status: String,
    val version: String,
    val similarSongs: SimilarSongsContainerDto? = null,
    val error: SubsonicErrorDto? = null,
)

data class SimilarSongsContainerDto(
    val song: List<SongDto>? = null,
)

data class SimilarSongs2ResponseDto(
    @SerializedName("subsonic-response") val response: SimilarSongs2BodyDto,
)

data class SimilarSongs2BodyDto(
    val status: String,
    val version: String,
    val similarSongs2: SimilarSongs2ContainerDto? = null,
    val error: SubsonicErrorDto? = null,
)

data class SimilarSongs2ContainerDto(
    val song: List<SongDto>? = null,
)

data class SongsByGenreResponseDto(
    @SerializedName("subsonic-response") val response: SongsByGenreBodyDto,
)

data class SongsByGenreBodyDto(
    val status: String,
    val version: String,
    val songsByGenre: SongsByGenreContainerDto? = null,
    val error: SubsonicErrorDto? = null,
)

data class SongsByGenreContainerDto(
    val song: List<SongDto>? = null,
)

data class RandomSongsResponseDto(
    @SerializedName("subsonic-response") val response: RandomSongsBodyDto,
)

data class RandomSongsBodyDto(
    val status: String,
    val version: String,
    val randomSongs: RandomSongsContainerDto? = null,
    val error: SubsonicErrorDto? = null,
)

data class RandomSongsContainerDto(
    val song: List<SongDto>? = null,
)
