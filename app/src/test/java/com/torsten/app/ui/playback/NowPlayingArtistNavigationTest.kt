package com.torsten.app.ui.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for Now Playing artist navigation correctness.
 *
 * Root cause being guarded: for collaboration tracks (e.g. "Avicii feat. Rita Ora"),
 * [PlaybackUiState.artistId] may contain the track's primary artist (Avicii) while the song
 * lives on a Rita Ora album. [PlaybackUiState.albumArtistId] is populated from the DB after
 * each track change and always reflects the album owner. [navigationArtistId] and
 * [navigationArtistName] must prefer the album values over the track values.
 */
class NowPlayingArtistNavigationTest {

    // ── navigationArtistId ────────────────────────────────────────────────────

    @Test
    fun `tapping artist in NowPlaying navigates using albumArtistId not trackArtistId`() {
        val state = PlaybackUiState(
            artistId = "avicii_id",
            artistName = "Avicii feat. Rita Ora",
            albumArtistId = "rita_ora_id",
            albumArtistName = "Rita Ora",
        )
        assertEquals("rita_ora_id", state.navigationArtistId)
    }

    @Test
    fun `collaboration track on Rita Ora album navigates to Rita Ora not featuring artist`() {
        // "Lonely Together" on the Rita Ora album: track artist is Avicii, album artist is Rita Ora.
        val state = PlaybackUiState(
            currentSongTitle = "Lonely Together",
            artistId = "avicii_id",
            artistName = "Avicii feat. Rita Ora",
            albumArtistId = "rita_ora_id",
            albumArtistName = "Rita Ora",
            albumId = "rita_ora_album_id",
        )
        assertEquals(
            "Expected navigation to Rita Ora's artist page",
            "rita_ora_id",
            state.navigationArtistId,
        )
        assertEquals(
            "Expected display name to be Rita Ora (album artist)",
            "Rita Ora",
            state.navigationArtistName,
        )
    }

    @Test
    fun `collaboration track on Avicii album navigates to Avicii not featuring artist`() {
        // Same song "Lonely Together" but on the Avicii album: album artist is Avicii.
        val state = PlaybackUiState(
            currentSongTitle = "Lonely Together",
            artistId = "rita_ora_id",
            artistName = "Rita Ora feat. Avicii",
            albumArtistId = "avicii_id",
            albumArtistName = "Avicii",
            albumId = "avicii_album_id",
        )
        assertEquals(
            "Expected navigation to Avicii's artist page",
            "avicii_id",
            state.navigationArtistId,
        )
        assertEquals(
            "Expected display name to be Avicii (album artist)",
            "Avicii",
            state.navigationArtistName,
        )
    }

    @Test
    fun `navigationArtistId falls back to artistId when albumArtistId not yet resolved`() {
        // albumArtistId is empty until the DB lookup completes; must not disable navigation.
        val state = PlaybackUiState(
            artistId = "some_artist_id",
            artistName = "Some Artist",
            albumArtistId = "",   // not yet resolved from DB
            albumArtistName = "",
        )
        assertEquals("some_artist_id", state.navigationArtistId)
        assertEquals("Some Artist", state.navigationArtistName)
    }

    @Test
    fun `solo track with matching artist and album artist navigates correctly`() {
        val state = PlaybackUiState(
            artistId = "adele_id",
            artistName = "Adele",
            albumArtistId = "adele_id",
            albumArtistName = "Adele",
        )
        assertEquals("adele_id", state.navigationArtistId)
        assertEquals("Adele", state.navigationArtistName)
    }

    @Test
    fun `navigationArtistId is empty when both artistId and albumArtistId are empty`() {
        // No track playing — navigation must be disabled in the UI.
        val state = PlaybackUiState()
        assertEquals("", state.navigationArtistId)
        assertEquals("", state.navigationArtistName)
    }
}
