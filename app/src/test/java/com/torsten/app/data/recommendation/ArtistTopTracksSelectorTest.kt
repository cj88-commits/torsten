package com.torsten.app.data.recommendation

import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.data.metabrainz.LbRecordingCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistTopTracksSelectorTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun song(
        id: String,
        title: String,
        albumId: String,
        trackNumber: Int = 1,
        discNumber: Int = 1,
    ) = SongEntity(
        id = id,
        albumId = albumId,
        artistId = "artist1",
        title = title,
        trackNumber = trackNumber,
        discNumber = discNumber,
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

        val result = ArtistTopTracksSelector.selectTopFive(songs, songs)

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

        val result = ArtistTopTracksSelector.selectTopFive(ranked, ranked)

        // First pass: s1, s2, s4. Second pass appends s3.
        assertEquals(listOf(s1, s2, s4, s3), result)
    }

    @Test
    fun `top five returns fewer than 5 if artist has fewer than 5 songs`() {
        val songs = listOf(
            song("s1", "Track 1", "album1"),
            song("s2", "Track 2", "album2"),
        )

        val result = ArtistTopTracksSelector.selectTopFive(songs, songs)

        assertEquals(2, result.size)
    }

    // ── selectTopFive — ordering ──────────────────────────────────────────────

    @Test
    fun `first song in top five is the highest LB ranked matched song`() {
        val s1 = song("s1", "Track 1", "album1")
        val songs = (1..5).map { song("s$it", "Track $it", "album$it") }

        val result = ArtistTopTracksSelector.selectTopFive(songs, songs)

        assertEquals(s1, result.first())
    }

    @Test
    fun `top five is stable across multiple calls with same input`() {
        val songs = (1..10).map { song("s$it", "Track $it", "album$it") }

        val r1 = ArtistTopTracksSelector.selectTopFive(songs, songs)
        val r2 = ArtistTopTracksSelector.selectTopFive(songs, songs)

        assertEquals(r1, r2)
    }

    // ── selectTopFive — minimum 5 guarantee ──────────────────────────────────

    @Test
    fun `selectTopFive returns 5 tracks when artist has exactly 5 songs and 0 LB matches`() {
        val allSongs = (1..5).map { song("s$it", "Track $it", "album${(it + 1) / 2}") }

        val result = ArtistTopTracksSelector.selectTopFive(emptyList(), allSongs)

        assertEquals(5, result.size)
    }

    @Test
    fun `selectTopFive returns 5 tracks when artist has 10 songs and 2 LB matches`() {
        val allSongs = (1..10).map { song("s$it", "Track $it", "album${(it + 1) / 2}") }
        val lbMatched = listOf(allSongs[0], allSongs[1])

        val result = ArtistTopTracksSelector.selectTopFive(lbMatched, allSongs)

        assertEquals(5, result.size)
    }

    @Test
    fun `selectTopFive returns 5 tracks when artist has 5 songs across 2 albums and 2 LB matches`() {
        val album1Songs = (1..3).map { song("a1s$it", "A1 Track $it", "album1", trackNumber = it) }
        val album2Songs = (1..2).map { song("a2s$it", "A2 Track $it", "album2", trackNumber = it) }
        val allSongs = album1Songs + album2Songs
        val lbMatched = listOf(album1Songs[0], album2Songs[0])

        val result = ArtistTopTracksSelector.selectTopFive(lbMatched, allSongs)

        assertEquals(5, result.size)
    }

    @Test
    fun `selectTopFive returns min(n, 5) when artist has fewer than 5 songs`() {
        val allSongs = (1..3).map { song("s$it", "Track $it", "album$it") }

        val result = ArtistTopTracksSelector.selectTopFive(emptyList(), allSongs)

        assertEquals(3, result.size)
    }

    @Test
    fun `selectTopFive LB matched songs appear before fill songs`() {
        val lbSong = song("lb1", "Popular Track", "album1")
        val fillSong1 = song("f1", "Fill Track 1", "album2")
        val fillSong2 = song("f2", "Fill Track 2", "album3")
        val allSongs = listOf(lbSong, fillSong1, fillSong2)

        val result = ArtistTopTracksSelector.selectTopFive(listOf(lbSong), allSongs)

        assertEquals(lbSong, result[0])
        assertTrue(result.containsAll(listOf(fillSong1, fillSong2)))
    }

    @Test
    fun `selectTopFive diversity first pass prefers one song per album`() {
        // 2 albums, 3 songs each — with 0 LB matches, diversity fill should pick 1 from each album
        val album1Songs = (1..3).map { song("a1s$it", "A1 Track $it", "album1", trackNumber = it) }
        val album2Songs = (1..3).map { song("a2s$it", "A2 Track $it", "album2", trackNumber = it) }
        val allSongs = album1Songs + album2Songs

        val result = ArtistTopTracksSelector.selectTopFive(emptyList(), allSongs)

        // First 2 results should be one per album (diversity pass), then second pass fills from same albums
        val firstTwoAlbums = result.take(2).map { it.albumId }.toSet()
        assertEquals(2, firstTwoAlbums.size)
    }

    @Test
    fun `selectTopFive diversity second pass allows same album if needed to reach 5`() {
        // Only 1 album with 5 songs — must allow same album to reach 5
        val allSongs = (1..5).map { song("s$it", "Track $it", "album1", trackNumber = it) }

        val result = ArtistTopTracksSelector.selectTopFive(emptyList(), allSongs)

        assertEquals(5, result.size)
        assertTrue(result.all { it.albumId == "album1" })
    }

    // ── selectTopFive — single-album artist fill ──────────────────────────────

    @Test
    fun `selectTopFive with single album artist fills to 5 when 3 LB matches and 10 songs available`() {
        val allSongs = (1..10).map { song("s$it", "Track $it", "album1", trackNumber = it) }
        val lbMatched = allSongs.take(3)

        val result = ArtistTopTracksSelector.selectTopFive(lbMatched, allSongs)

        assertEquals(5, result.size)
    }

    @Test
    fun `selectTopFive relaxed fill allows multiple songs from same album`() {
        // Single album — diversity pass blocks all fill candidates, relaxed pass must allow them
        val allSongs = (1..10).map { song("s$it", "Track $it", "album1", trackNumber = it) }
        val lbMatched = allSongs.take(3)

        val result = ArtistTopTracksSelector.selectTopFive(lbMatched, allSongs)

        assertEquals(5, result.size)
        assertTrue(result.all { it.albumId == "album1" })
    }

    @Test
    fun `selectTopFive single album artist 3 LB matches fills to 5 from same album`() {
        val allSongs = (1..7).map { song("s$it", "Track $it", "album1", trackNumber = it) }
        val lbMatched = listOf(allSongs[0], allSongs[2], allSongs[4])

        val result = ArtistTopTracksSelector.selectTopFive(lbMatched, allSongs)

        assertEquals(5, result.size)
        assertEquals(allSongs[0], result[0]) // highest-ranked LB song is first
    }

    @Test
    fun `selectTopFive allArtistSongs empty returns only LB matches without crash`() {
        val lbSongs = (1..3).map { song("s$it", "Track $it", "album$it") }

        val result = ArtistTopTracksSelector.selectTopFive(lbSongs, emptyList())

        assertEquals(3, result.size)
        assertEquals(lbSongs, result)
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

    // ── buildTopTrackQueue ────────────────────────────────────────────────────

    private fun makeSongs(count: Int, albumId: String, idPrefix: String = albumId) =
        (1..count).map { song("${idPrefix}s$it", "$albumId Track $it", albumId, trackNumber = it) }

    // ── Return type ───────────────────────────────────────────────────────────

    @Test
    fun `buildTopTrackQueue returns Pair of queue and startIndex`() {
        val topFive = (1..5).map { song("s$it", "Track $it", "album$it") }
        val result = ArtistTopTracksSelector.buildTopTrackQueue(topFive[0], topFive, topFive)
        // Should be a Pair — destructuring must compile and produce the right types
        val (queue, startIndex) = result
        assertTrue(queue.isNotEmpty())
        assertTrue(startIndex >= 0)
    }

    // ── startIndex correctness ────────────────────────────────────────────────

    @Test
    fun `startIndex is 0 when first top track tapped`() {
        val topFive = (1..5).map { song("s$it", "Track $it", "album$it") }
        val (_, startIndex) = ArtistTopTracksSelector.buildTopTrackQueue(topFive[0], topFive, topFive)
        assertEquals(0, startIndex)
    }

    @Test
    fun `startIndex is 2 when third top track tapped`() {
        val topFive = (1..5).map { song("s$it", "Track $it", "album$it") }
        val (_, startIndex) = ArtistTopTracksSelector.buildTopTrackQueue(topFive[2], topFive, topFive)
        assertEquals(2, startIndex)
    }

    @Test
    fun `startIndex is 4 when fifth top track tapped`() {
        val topFive = (1..5).map { song("s$it", "Track $it", "album$it") }
        val (_, startIndex) = ArtistTopTracksSelector.buildTopTrackQueue(topFive[4], topFive, topFive)
        assertEquals(4, startIndex)
    }

    @Test
    fun `tapped song appears at correct startIndex in returned queue`() {
        val topFive = (1..5).map { song("s$it", "Track $it", "album$it") }
        val extras = (1..15).map { song("e$it", "Extra $it", "extraAlbum$it") }
        val allSongs = topFive + extras
        // Test all five tappable positions
        topFive.forEachIndexed { idx, tapped ->
            val (queue, startIndex) = ArtistTopTracksSelector.buildTopTrackQueue(tapped, topFive, allSongs)
            assertEquals("queue[startIndex] must be the tapped song at position $idx",
                tapped.id, queue[startIndex].id)
        }
    }

    // ── Queue contents ────────────────────────────────────────────────────────

    @Test
    fun `full queue size is min 20 totalArtistSongs`() {
        val topFive = (1..5).map { song("t$it", "Top $it", "topAlbum$it") }
        val extras = (1..20).map { song("e$it", "Extra $it", "extraAlbum${it % 4}") }
        val allSongs = topFive + extras

        val (queue, _) = ArtistTopTracksSelector.buildTopTrackQueue(topFive[0], topFive, allSongs)

        assertEquals(20, queue.size)
    }

    @Test
    fun `full queue size is capped at artist song count when fewer than 20`() {
        val topFive = (1..5).map { song("t$it", "Top $it", "topAlbum$it") }
        val extras = (1..3).map { song("e$it", "Extra $it", "extraAlbum$it") }
        val allSongs = topFive + extras // 8 total

        val (queue, _) = ArtistTopTracksSelector.buildTopTrackQueue(topFive[0], topFive, allSongs)

        assertEquals(8, queue.size)
    }

    @Test
    fun `tracks before tapped track are present in queue at indices 0 until startIndex`() {
        val topFive = (1..5).map { song("s$it", "Track $it", "album$it") }
        val tapped = topFive[3] // index 3
        val (queue, startIndex) = ArtistTopTracksSelector.buildTopTrackQueue(tapped, topFive, topFive)
        assertEquals(3, startIndex)
        // Positions 0..2 must contain the first three top-five songs
        assertEquals(topFive[0].id, queue[0].id)
        assertEquals(topFive[1].id, queue[1].id)
        assertEquals(topFive[2].id, queue[2].id)
    }

    @Test
    fun `top five appear in original order at start of queue`() {
        val s1 = song("s1", "Track 1", "album1")
        val s2 = song("s2", "Track 2", "album2")
        val s3 = song("s3", "Track 3", "album3")
        val s4 = song("s4", "Track 4", "album4")
        val s5 = song("s5", "Track 5", "album5")
        val topFive = listOf(s1, s2, s3, s4, s5)
        val extras = (1..15).map { song("e$it", "Extra $it", "extraAlbum$it") }
        val allSongs = topFive + extras

        val (queue, _) = ArtistTopTracksSelector.buildTopTrackQueue(s3, topFive, allSongs)

        // Top five must be in original order, NOT rotated
        assertEquals(listOf(s1, s2, s3, s4, s5), queue.take(5))
    }

    @Test
    fun `no rotation — top five never reordered regardless of which track tapped`() {
        val topFive = (1..5).map { song("s$it", "Track $it", "album$it") }
        val extras = (1..15).map { song("e$it", "Extra $it", "extraAlbum$it") }
        val allSongs = topFive + extras
        val expectedTopFiveIds = topFive.map { it.id }

        topFive.forEach { tapped ->
            val (queue, _) = ArtistTopTracksSelector.buildTopTrackQueue(tapped, topFive, allSongs)
            assertEquals(
                "Top five must be in original order when '${tapped.id}' is tapped",
                expectedTopFiveIds,
                queue.take(5).map { it.id },
            )
        }
    }

    @Test
    fun `positions 5 to 19 contain no song from top five`() {
        val topFive = (1..5).map { song("t$it", "Top $it", "topAlbum$it") }
        val extras = (1..15).map { song("e$it", "Extra $it", "extraAlbum${it % 3}") }
        val allSongs = topFive + extras
        val topFiveIds = topFive.map { it.id }.toSet()

        val (queue, _) = ArtistTopTracksSelector.buildTopTrackQueue(topFive[0], topFive, allSongs)

        val tail = queue.drop(5)
        assertTrue("Tail must not be empty", tail.isNotEmpty())
        assertTrue(
            "Positions 5–19 must not contain any top-five song",
            tail.none { it.id in topFiveIds },
        )
    }

    @Test
    fun `positions 5 to 19 are shuffled not in album order`() {
        val topFive = (1..5).map { song("t$it", "Top $it", "topAlbum$it") }
        val extras = (1..15).map { song("e$it", "Extra $it", "extraAlbum${(it - 1) / 5}") }
        val allSongs = topFive + extras

        // Run 5 times; at least two tails must differ (P(all identical) ≈ 1/(15!)^4 ≈ 0)
        val tails = (1..5).map {
            val (queue, _) = ArtistTopTracksSelector.buildTopTrackQueue(topFive[0], topFive, allSongs)
            queue.drop(5)
        }
        val allSame = tails.all { it == tails[0] }
        assertTrue("Positions 5–19 should be shuffled — all 5 runs produced the same order", !allSame)
    }

    @Test
    fun `no duplicate song IDs in queue`() {
        val topFive = (1..5).map { song("t$it", "Top $it", "topAlbum") }
        val extras = (1..15).map { song("e$it", "Extra $it", "extraAlbum${it % 3}") }
        val allSongs = topFive + extras

        val (queue, _) = ArtistTopTracksSelector.buildTopTrackQueue(topFive[0], topFive, allSongs)

        val ids = queue.map { it.id }
        assertEquals("Queue must have no duplicate IDs", ids.distinct(), ids)
    }

    @Test
    fun `no song appears twice in the queue`() {
        val topFive = (1..5).map { song("t$it", "Top $it", "topAlbum") }
        val extras = (1..15).map { song("e$it", "Extra $it", "extraAlbum${it % 3}") }
        val allSongs = topFive + extras

        val (queue, _) = ArtistTopTracksSelector.buildTopTrackQueue(topFive[0], topFive, allSongs)

        val ids = queue.map { it.id }
        assertEquals(ids.distinct(), ids)
    }
}
