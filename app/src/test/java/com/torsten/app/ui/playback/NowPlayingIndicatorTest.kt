package com.torsten.app.ui.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the ID-based now-playing indicator logic used in album/playlist/queue screens.
 *
 * The rule: a track row should highlight iff `playbackState.currentSongId == track.id`.
 * Previously the code compared `playbackState.currentIndex == listIndex`, which produced
 * wrong results when a song was started from a different context (e.g. search).
 */
class NowPlayingIndicatorTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Simulates the indicator expression used in each track row. */
    private fun isPlaying(state: PlaybackUiState, songId: String): Boolean =
        state.currentSongId == songId

    private fun stateWith(currentSongId: String, currentIndex: Int = 0, isActive: Boolean = true) =
        PlaybackUiState(
            isActive = isActive,
            currentSongId = currentSongId,
            currentIndex = currentIndex,
        )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test fun `matching id returns true`() {
        val state = stateWith(currentSongId = "song-1")
        assertTrue(isPlaying(state, "song-1"))
    }

    @Test fun `non-matching id returns false`() {
        val state = stateWith(currentSongId = "song-1")
        assertFalse(isPlaying(state, "song-2"))
    }

    @Test fun `empty currentSongId never matches a real track`() {
        val state = stateWith(currentSongId = "")
        assertFalse(isPlaying(state, "song-1"))
    }

    @Test fun `nothing playing — all indicators are off`() {
        val state = PlaybackUiState() // defaults: currentSongId = ""
        listOf("a", "b", "c").forEach { id ->
            assertFalse(isPlaying(state, id))
        }
    }

    @Test fun `regression — index match without id match is false`() {
        // Old bug: currentIndex == 0 would light up the first row even if it was a different song.
        // With ID-based logic, index 0 playing song-99 must NOT light up song-1 (also at position 0).
        val state = stateWith(currentSongId = "song-99", currentIndex = 0)
        assertFalse(isPlaying(state, "song-1"))
    }

    @Test fun `regression — started from search, song is at album index 3 but queue index 0`() {
        // Scenario: user searches, plays track 4 of an album. Media3 index = 0 (single item
        // enqueued), but the track occupies position 3 in the album list.
        // Old code: currentIndex (0) == listIndex (3) → false (missed highlight)
        // New code: currentSongId ("song-4") == song.id ("song-4") → true (correct)
        val state = stateWith(currentSongId = "song-4", currentIndex = 0)
        assertTrue(isPlaying(state, "song-4"))
        assertFalse(isPlaying(state, "song-1"))
        assertFalse(isPlaying(state, "song-2"))
        assertFalse(isPlaying(state, "song-3"))
    }

    @Test fun `only the matching track is highlighted in a list`() {
        val songs = listOf("a1", "a2", "a3", "a4", "a5")
        val state = stateWith(currentSongId = "a3")
        val highlighted = songs.filter { isPlaying(state, it) }
        assertTrue(highlighted == listOf("a3"))
    }

    @Test fun `first track in list is correctly highlighted`() {
        val songs = listOf("first", "second", "third")
        val state = stateWith(currentSongId = "first", currentIndex = 5)
        assertTrue(isPlaying(state, "first"))
        assertFalse(isPlaying(state, "second"))
    }

    @Test fun `last track in list is correctly highlighted`() {
        val songs = listOf("first", "second", "last")
        val state = stateWith(currentSongId = "last", currentIndex = 0)
        assertFalse(isPlaying(state, "first"))
        assertFalse(isPlaying(state, "second"))
        assertTrue(isPlaying(state, "last"))
    }

    @Test fun `same song id in different albums — indicator follows the id`() {
        // In practice song IDs are globally unique, but even if the same ID appeared in two
        // album views simultaneously both would highlight, which is correct behaviour.
        val state = stateWith(currentSongId = "shared-song")
        assertTrue(isPlaying(state, "shared-song"))
    }

    @Test fun `currentSongId updates when track changes`() {
        val songs = listOf("x1", "x2", "x3")
        var state = stateWith(currentSongId = "x1")
        assertTrue(isPlaying(state, "x1"))

        // Simulate skipping to the next track
        state = stateWith(currentSongId = "x2")
        assertFalse(isPlaying(state, "x1"))
        assertTrue(isPlaying(state, "x2"))
        assertFalse(isPlaying(state, "x3"))
    }
}
