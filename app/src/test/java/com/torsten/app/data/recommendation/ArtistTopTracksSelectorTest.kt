package com.torsten.app.data.recommendation

import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.data.metabrainz.LbRecordingCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistTopTracksSelectorTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun song(id: String, title: String, albumId: String) = SongEntity(
        id = id,
        albumId = albumId,
        artistId = "artist1",
        title = title,
        trackNumber = 1,
        discNumber = 1,
        duration = 180,
        bitRate = null,
        suffix = null,
        contentType = null,
        starred = false,
        localFilePath = null,
        lastUpdated = 0L,
    )

    private fun lb(title: String, count: Int = 100) = LbRecordingCandidate(title, count)

    // ── normaliseTitle ────────────────────────────────────────────────────────

    @Test
    fun `normaliseTitle strips parentheses content`() {
        assertEquals("love", ArtistTopTracksSelector.normaliseTitle("Love (Live)"))
    }

    @Test
    fun `normaliseTitle strips feat`() {
        assertEquals("song", ArtistTopTracksSelector.normaliseTitle("Song feat. Artist"))
    }

    @Test
    fun `normaliseTitle lowercases`() {
        assertEquals("hello", ArtistTopTracksSelector.normaliseTitle("HELLO"))
    }

    @Test
    fun `normaliseTitle strips non-alphanumeric`() {
        assertEquals("love", ArtistTopTracksSelector.normaliseTitle("L-O-V-E"))
    }

    // ── matchCandidates — tier 1 exact match ─────────────────────────────────

    @Test
    fun `exact title match returns song at LB rank position`() {
        val candidates = listOf(lb("Hey Jude"), lb("Let It Be"))
        val s1 = song("s1", "Hey Jude", "album1")
        val s2 = song("s2", "Let It Be", "album2")

        val result = ArtistTopTracksSelector.matchCandidates(candidates, listOf(s1, s2))

        assertEquals(listOf(s1, s2), result)
    }

    @Test
    fun `exact match is case insensitive`() {
        val candidates = listOf(lb("HEY JUDE"))
        val s1 = song("s1", "hey jude", "album1")

        val result = ArtistTopTracksSelector.matchCandidates(candidates, listOf(s1))

        assertEquals(listOf(s1), result)
    }

    @Test
    fun `higher ranked LB candidate appears before lower ranked`() {
        val candidates = listOf(lb("Hey Jude"), lb("Let It Be"))
        val s1 = song("s1", "Hey Jude", "album1")
        val s2 = song("s2", "Let It Be", "album2")

        val result = ArtistTopTracksSelector.matchCandidates(candidates, listOf(s2, s1))

        assertEquals(s1, result[0])
        assertEquals(s2, result[1])
    }

    // ── matchCandidates — tier 2 substring ───────────────────────────────────

    @Test
    fun `substring match works for truncated titles`() {
        // "Hey Jude extended" (norm: "hey jude extended", 17 chars) contains
        // "Hey Jude" (norm: "hey jude", 8 chars) → tier 2
        val candidates = listOf(lb("Hey Jude extended"))
        val s1 = song("s1", "Hey Jude", "album1")

        val result = ArtistTopTracksSelector.matchCandidates(candidates, listOf(s1))

        assertEquals(listOf(s1), result)
    }

    @Test
    fun `substring match requires minimum 8 chars`() {
        // normA = "song" (4 chars) — too short for tier 2 even though
        // "song title here" contains "song". Trigram score is also below threshold.
        val candidates = listOf(lb("song"))
        val s1 = song("s1", "song title here", "album1")

        val result = ArtistTopTracksSelector.matchCandidates(candidates, listOf(s1))

        assertTrue(result.isEmpty())
    }

    // ── matchCandidates — tier 3 trigram ─────────────────────────────────────

    @Test
    fun `trigram match above 0 55 threshold returns song`() {
        // "imagine" (7 chars, < 8 so tier 2 blocked) vs "imagined" (8 chars)
        // trigram Jaccard = 5/6 ≈ 0.833 → tier 3 match
        val candidates = listOf(lb("imagine"))
        val s1 = song("s1", "imagined", "album1")

        val result = ArtistTopTracksSelector.matchCandidates(candidates, listOf(s1))

        assertEquals(listOf(s1), result)
    }

    @Test
    fun `trigram match below 0 55 threshold returns nothing`() {
        // "abcdefgh" and "xyzwvuts" share no trigrams → score = 0 < 0.55
        val candidates = listOf(lb("abcdefgh"))
        val s1 = song("s1", "xyzwvuts", "album1")

        val result = ArtistTopTracksSelector.matchCandidates(candidates, listOf(s1))

        assertTrue(result.isEmpty())
    }

    // ── matchCandidates — deduplication ──────────────────────────────────────

    @Test
    fun `duplicate LB candidates with same normalised title only match once`() {
        // Both "Hey Jude" and "Hey Jude (Live)" normalise to "hey jude"
        val candidates = listOf(lb("Hey Jude"), lb("Hey Jude (Live)"))
        val s1 = song("s1", "Hey Jude", "album1")
        val s2 = song("s2", "Come Together", "album2")

        val result = ArtistTopTracksSelector.matchCandidates(candidates, listOf(s1, s2))

        assertEquals(listOf(s1), result)
    }

    @Test
    fun `same song matched by two candidates only appears once in result`() {
        // First candidate matches s1; second candidate would also match s1
        // (via tier 2 substring) but s1 is already used → only s1 in result
        val candidates = listOf(lb("Hey Jude"), lb("Hey Jude - Remaster"))
        val s1 = song("s1", "Hey Jude", "album1")

        val result = ArtistTopTracksSelector.matchCandidates(candidates, listOf(s1))

        assertEquals(listOf(s1), result)
    }

    // ── selectTopFive — diversity ─────────────────────────────────────────────

    @Test
    fun `top five contains at most one song per album in first pass`() {
        val songs = listOf(
            song("s1", "Track 1", "album1"),
            song("s2", "Track 2", "album1"), // same album as s1
            song("s3", "Track 3", "album2"),
            song("s4", "Track 4", "album3"),
            song("s5", "Track 5", "album4"),
            song("s6", "Track 6", "album5"),
        )

        val result = ArtistTopTracksSelector.selectTopFive(songs)

        // All 5 slots filled from 5 distinct albums
        assertEquals(5, result.size)
        val albumCounts = result.groupBy { it.albumId }
        // Each album appears at most once in the first 5 (first-pass constraint met)
        // s2 is only included in the second pass if needed — here 5 distinct albums fill it
        assertTrue(albumCounts.values.all { it.size == 1 })
    }

    @Test
    fun `top five preserves LB rank order within diversity constraint`() {
        // s3 is rank 3 but shares album with s1 → skipped in first pass, appended in second
        val s1 = song("s1", "Track 1", "album1")
        val s2 = song("s2", "Track 2", "album2")
        val s3 = song("s3", "Track 3", "album1") // duplicate album
        val s4 = song("s4", "Track 4", "album3")
        val ranked = listOf(s1, s2, s3, s4)

        val result = ArtistTopTracksSelector.selectTopFive(ranked)

        // First pass: s1, s2, s4. Second pass appends s3.
        assertEquals(listOf(s1, s2, s4, s3), result)
    }

    @Test
    fun `top five returns fewer than 5 if fewer than 5 songs provided`() {
        val songs = listOf(
            song("s1", "Track 1", "album1"),
            song("s2", "Track 2", "album2"),
        )

        val result = ArtistTopTracksSelector.selectTopFive(songs)

        assertEquals(2, result.size)
    }

    // ── selectTopFive — ordering ──────────────────────────────────────────────

    @Test
    fun `first song in top five is the highest LB ranked matched song`() {
        val s1 = song("s1", "Track 1", "album1")
        val songs = (1..5).map { song("s$it", "Track $it", "album$it") }

        val result = ArtistTopTracksSelector.selectTopFive(songs)

        assertEquals(s1, result.first())
    }

    @Test
    fun `top five is stable across multiple calls with same input`() {
        val songs = (1..10).map { song("s$it", "Track $it", "album$it") }

        val r1 = ArtistTopTracksSelector.selectTopFive(songs)
        val r2 = ArtistTopTracksSelector.selectTopFive(songs)

        assertEquals(r1, r2)
    }

    // ── selectFullQueue ───────────────────────────────────────────────────────

    @Test
    fun `full queue returns up to 20 songs`() {
        val songs = (1..25).map { song("s$it", "Track $it", "album$it") }

        val result = ArtistTopTracksSelector.selectFullQueue(songs)

        assertEquals(20, result.size)
    }

    @Test
    fun `full queue is in LB rank order`() {
        val songs = (1..10).map { song("s$it", "Track $it", "album$it") }

        val result = ArtistTopTracksSelector.selectFullQueue(songs)

        assertEquals(songs, result)
    }
}
