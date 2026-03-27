package com.torsten.app.data.recommendation

import com.torsten.app.data.db.entity.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstantMixSelectorTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /**
     * [artistName] maps to [SongEntity.artistId] — SongEntity has no artistName field.
     * InstantMixSelector.matchCandidates compares song.artistId against candidate.artistName,
     * so test fixtures set artistId to the same string used in the candidate.
     */
    private fun song(
        id: String,
        title: String,
        artistName: String,
        albumId: String = "album_$id",
    ) = SongEntity(
        id = id,
        albumId = albumId,
        artistId = artistName,
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

    private fun lbCandidate(title: String, artist: String) =
        LbRadioCandidate(recordingName = title, artistName = artist)

    // ── matchCandidates ───────────────────────────────────────────────────────

    @Test
    fun `exact artist and title match returns song`() {
        val s1 = song("s1", "Hey Jude", "beatles")
        val result = InstantMixSelector.matchCandidates(
            listOf(lbCandidate("Hey Jude", "beatles")),
            listOf(s1),
        )
        assertEquals(listOf(s1), result)
    }

    @Test
    fun `match is case insensitive`() {
        val s1 = song("s1", "HEY JUDE", "BEATLES")
        val result = InstantMixSelector.matchCandidates(
            listOf(lbCandidate("hey jude", "beatles")),
            listOf(s1),
        )
        assertEquals(listOf(s1), result)
    }

    @Test
    fun `unmatched candidate returns nothing`() {
        // Artist matches but title does not
        val s1 = song("s1", "Come Together", "beatles")
        val result = InstantMixSelector.matchCandidates(
            listOf(lbCandidate("Hey Jude", "beatles")),
            listOf(s1),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `duplicate candidates only match once`() {
        val s1 = song("s1", "Hey Jude", "beatles")
        val result = InstantMixSelector.matchCandidates(
            listOf(lbCandidate("Hey Jude", "beatles"), lbCandidate("Hey Jude", "beatles")),
            listOf(s1),
        )
        assertEquals(1, result.size)
        assertEquals(s1, result.first())
    }

    @Test
    fun `songs matched in LB candidate order not local pool order`() {
        val s1 = song("s1", "Come Together", "beatles")
        val s2 = song("s2", "Hey Jude", "beatles")
        // Pool order: [Come Together, Hey Jude]. LB order: [Hey Jude, Come Together].
        val result = InstantMixSelector.matchCandidates(
            listOf(lbCandidate("Hey Jude", "beatles"), lbCandidate("Come Together", "beatles")),
            listOf(s1, s2),
        )
        assertEquals(listOf(s2, s1), result) // LB rank order, not pool order
    }

    // ── applyDiversityCap ─────────────────────────────────────────────────────

    @Test
    fun `max 5 songs per artist enforced`() {
        // 4 artists × 6 songs = 24 total. Strict cap-5 gives 4×5=20 == target → no relaxation.
        val songs = (1..4).flatMap { a ->
            (1..6).map { t -> song("a${a}t$t", "Track", "artist$a") }
        }
        val result = InstantMixSelector.applyDiversityCap(songs, target = 20, initialCap = 5, relaxedCap = 8)
        assertEquals(20, result.size)
        result.groupBy { it.artistId }.values.forEach { group ->
            assertTrue("Expected ≤5 per artist, got ${group.size}", group.size <= 5)
        }
    }

    @Test
    fun `artist with fewer than 5 songs not affected`() {
        // 3 songs from one artist — below the cap, all should be returned.
        val songs = (1..3).map { song("s$it", "Track $it", "artistA") }
        val result = InstantMixSelector.applyDiversityCap(songs, target = 20, initialCap = 5, relaxedCap = 8)
        assertEquals(3, result.size)
    }

    @Test
    fun `multiple artists each capped independently`() {
        // artistA: 10 songs, artistB: 3 songs.
        // Strict cap-5: 5+3=8 < target(20) → relax.
        // Relaxed cap-8: artistA capped at 8, artistB all 3 included.
        val songsA = (1..10).map { song("a$it", "Track A$it", "artistA") }
        val songsB = (1..3).map { song("b$it", "Track B$it", "artistB") }
        val result = InstantMixSelector.applyDiversityCap(
            songsA + songsB, target = 20, initialCap = 5, relaxedCap = 8,
        )
        assertEquals(8, result.count { it.artistId == "artistA" })
        assertEquals(3, result.count { it.artistId == "artistB" })
    }

    @Test
    fun `relaxed cap allows up to 8 when needed to reach target`() {
        // Single artist with 10 songs. Strict cap-5 → 5 songs < target(20) → relax.
        // Relaxed cap-8 → 8 songs returned.
        val songs = (1..10).map { song("s$it", "Track $it", "artistA") }
        val result = InstantMixSelector.applyDiversityCap(songs, target = 20, initialCap = 5, relaxedCap = 8)
        assertEquals(8, result.size)
        assertEquals(8, result.count { it.artistId == "artistA" })
    }

    @Test
    fun `result never exceeds target`() {
        // 10 artists × 10 songs = 100 songs. Strict cap-5 gives 50 ≥ target(20) → no relaxation.
        val songs = (1..10).flatMap { a ->
            (1..10).map { t -> song("a${a}t$t", "Track", "artist$a") }
        }
        val result = InstantMixSelector.applyDiversityCap(songs, target = 20, initialCap = 5, relaxedCap = 8)
        assertEquals(20, result.size)
    }

    // ── mergeAndDeduplicate ───────────────────────────────────────────────────

    @Test
    fun `songs present in both lists appear only once`() {
        val s1 = song("s1", "Track 1", "artistA")
        val s2 = song("s2", "Track 2", "artistB")
        val result = InstantMixSelector.mergeAndDeduplicate(
            lbMatched = listOf(s1),
            subsonicCandidates = listOf(s1, s2),
        )
        assertEquals(listOf(s1, s2), result)
    }

    @Test
    fun `LB matched songs appear before Subsonic songs`() {
        val lb1 = song("lb1", "LB Track", "artistA")
        val sub1 = song("sub1", "Subsonic Track", "artistB")
        val result = InstantMixSelector.mergeAndDeduplicate(listOf(lb1), listOf(sub1))
        assertEquals(listOf(lb1, sub1), result)
    }

    @Test
    fun `empty LB match returns subsonic candidates only`() {
        val sub1 = song("s1", "Track", "artistA")
        val result = InstantMixSelector.mergeAndDeduplicate(emptyList(), listOf(sub1))
        assertEquals(listOf(sub1), result)
    }

    @Test
    fun `empty subsonic returns LB matches only`() {
        val lb1 = song("s1", "Track", "artistA")
        val result = InstantMixSelector.mergeAndDeduplicate(listOf(lb1), emptyList())
        assertEquals(listOf(lb1), result)
    }

    // ── fillToTarget ──────────────────────────────────────────────────────────

    @Test
    fun `fills to exactly 20 from uncapped pool`() {
        val capped = (1..15).map { song("c$it", "Capped $it", "artistA") }
        val pool = (1..30).map { song("p$it", "Pool $it", "artistB") }
        val result = InstantMixSelector.fillToTarget(capped, pool, target = 20)
        assertEquals(20, result.size)
    }

    @Test
    fun `does not exceed target even if pool is large`() {
        val capped = (1..5).map { song("c$it", "Capped $it", "artistA") }
        val pool = (1..100).map { song("p$it", "Pool $it", "artistB") }
        val result = InstantMixSelector.fillToTarget(capped, pool, target = 20)
        assertEquals(20, result.size)
    }

    @Test
    fun `returns all songs if pool smaller than target`() {
        // 5 capped + 10 in pool (no overlap) = 15 total, can't reach target of 20.
        val capped = (1..5).map { song("c$it", "Capped $it", "artistA") }
        val pool = (1..10).map { song("p$it", "Pool $it", "artistB") }
        val result = InstantMixSelector.fillToTarget(capped, pool, target = 20)
        assertEquals(15, result.size)
    }

    @Test
    fun `fillToTarget with capped=14 and uncappedPool=30 returns exactly 20`() {
        // capped has 14 songs (artist-diverse); uncappedPool has 30 (same 14 + 16 extra)
        val capped = (1..14).map { song("c$it", "Capped $it", "artist$it") }
        val extra = (1..16).map { song("e$it", "Extra $it", "extra$it") }
        val uncappedPool = capped + extra // 30 songs, 16 not in capped
        val result = InstantMixSelector.fillToTarget(capped, uncappedPool, target = 20)
        assertEquals(20, result.size)
    }

    @Test
    fun `fillToTarget with capped=14 and uncappedPool=5 unique extras returns 19`() {
        // capped has 14; uncappedPool has 14 + 5 extras = 19 unique total
        val capped = (1..14).map { song("c$it", "Capped $it", "artist$it") }
        val extra = (1..5).map { song("e$it", "Extra $it", "extra$it") }
        val uncappedPool = capped + extra
        val result = InstantMixSelector.fillToTarget(capped, uncappedPool, target = 20)
        assertEquals(19, result.size) // only 19 unique songs available
    }

    @Test
    fun `fillToTarget with capped=20 returns exactly 20 without touching uncappedPool`() {
        val capped = (1..20).map { song("c$it", "Capped $it", "artist$it") }
        val uncappedPool = (1..30).map { song("p$it", "Pool $it", "extra$it") }
        val result = InstantMixSelector.fillToTarget(capped, uncappedPool, target = 20)
        assertEquals(20, result.size)
        // All result songs should come from capped, not uncappedPool
        val cappedIds = capped.map { it.id }.toSet()
        assertTrue(result.all { it.id in cappedIds })
    }

    @Test
    fun `fillToTarget result contains no duplicate song IDs`() {
        // capped and uncappedPool overlap heavily — dedup must hold
        val shared = (1..10).map { song("s$it", "Shared $it", "artist$it") }
        val cappedOnly = (1..5).map { song("c$it", "Capped $it", "cappedOnly$it") }
        val poolOnly = (1..10).map { song("p$it", "Pool $it", "poolOnly$it") }
        val capped = shared + cappedOnly
        val uncappedPool = shared + cappedOnly + poolOnly
        val result = InstantMixSelector.fillToTarget(capped, uncappedPool, target = 20)
        val ids = result.map { it.id }
        assertEquals("Duplicate IDs found", ids.distinct(), ids)
    }

    // ── assembleFinalMix ──────────────────────────────────────────────────────

    @Test
    fun `assembleFinalMix with single seed and empty pool returns just the seed`() {
        val seed = song("seed", "Seed", "ArtistA")
        val result = InstantMixSelector.assembleFinalMix(seed, emptyList())
        assertEquals(listOf(seed), result)
    }

    @Test
    fun `assembleFinalMix called twice with same input returns identical result`() {
        val seed = song("seed", "Seed", "ArtistA")
        val pool = (1..5).map { song("s$it", "Track $it", "ArtistB") }
        val r1 = InstantMixSelector.assembleFinalMix(seed, pool)
        val r2 = InstantMixSelector.assembleFinalMix(seed, pool)
        assertEquals(r1, r2)
    }

    @Test
    fun `assembleFinalMix with pool of 25 returns exactly 20`() {
        val seed = song("seed", "Seed", "ArtistA")
        val pool = (1..25).map { song("s$it", "Track $it", "ArtistB") }
        val result = InstantMixSelector.assembleFinalMix(seed, pool, target = 20)
        assertEquals(20, result.size)
    }

    @Test
    fun `assembleFinalMix with pool of 19 returns 20 (seed + 19)`() {
        val seed = song("seed", "Seed", "ArtistA")
        val pool = (1..19).map { song("s$it", "Track $it", "ArtistB") }
        val result = InstantMixSelector.assembleFinalMix(seed, pool, target = 20)
        assertEquals(20, result.size)
    }

    @Test
    fun `assembleFinalMix with pool smaller than target returns all available`() {
        val seed = song("seed", "Seed", "ArtistA")
        val pool = (1..5).map { song("s$it", "Track $it", "ArtistB") }
        val result = InstantMixSelector.assembleFinalMix(seed, pool, target = 20)
        assertEquals(6, result.size) // seed + 5
    }

    // ── interleaveMix — seed artist positioning ───────────────────────────────

    @Test
    fun `seed is always at index 0`() {
        val seed = song("seed", "Seed", "ArtistA")
        val pool = (1..19).map { song("s$it", "Track $it", "ArtistB") }
        val result = InstantMixSelector.interleaveMix(seed, pool, target = 20)
        assertEquals(seed, result.first())
    }

    @Test
    fun `second song is from same artist as seed`() {
        val seed = song("1", "Song A", "ArtistA")
        val pool = listOf(
            song("2", "Song B", "ArtistA"), // same artist as seed
            song("3", "Song C", "ArtistB"),
        )
        val result = InstantMixSelector.interleaveMix(seed, pool, target = 3)
        assertEquals("ArtistA", result[1].artistId)
    }

    @Test
    fun `third song is from a different artist than seed`() {
        val seed = song("1", "Song A", "ArtistA")
        val pool = listOf(
            song("2", "Song B", "ArtistA"),
            song("3", "Song C", "ArtistB"),
            song("4", "Song D", "ArtistA"),
        )
        val result = InstantMixSelector.interleaveMix(seed, pool, target = 4)
        assertTrue(result[2].artistId != seed.artistId)
    }

    // ── interleaveMix — no consecutive same artist after position 1 ───────────

    @Test
    fun `no two consecutive songs share an artist after index 1`() {
        val seed = song("1", "Song A", "ArtistA")
        val pool = listOf(
            song("2", "Song B", "ArtistA"),
            song("3", "Song C", "ArtistB"),
            song("4", "Song D", "ArtistA"),
            song("5", "Song E", "ArtistB"),
            song("6", "Song F", "ArtistC"),
        )
        val result = InstantMixSelector.interleaveMix(seed, pool)
        for (i in 2 until result.size - 1) {
            assertNotEquals(
                "Consecutive same artist at positions $i and ${i + 1}",
                result[i].artistId, result[i + 1].artistId,
            )
        }
    }

    @Test
    fun `interleaving holds across full 20 track mix`() {
        // 4 artists × 5 songs in clustered order (worst case for grouping)
        val seed = song("seed", "Seed", "A0") // distinct artist not in pool
        val pool = (1..4).flatMap { a -> (1..5).map { t -> song("a${a}t$t", "Track", "A$a") } }
        val result = InstantMixSelector.interleaveMix(seed, pool, target = 20)
        assertEquals(20, result.size)
        for (i in 2 until result.size - 1) {
            assertNotEquals(
                "Consecutive same artist at $i and ${i + 1}",
                result[i].artistId, result[i + 1].artistId,
            )
        }
    }

    // ── interleaveMix — edge cases ────────────────────────────────────────────

    @Test
    fun `if only one artist available consecutive same artist is acceptable`() {
        val seed = song("seed", "Seed", "ArtistA")
        val pool = (1..4).map { song("s$it", "Track $it", "ArtistA") }
        val result = InstantMixSelector.interleaveMix(seed, pool)
        assertEquals(5, result.size) // seed + 4, all same artist — no crash, no gaps
        assertEquals(seed, result.first())
    }

    @Test
    fun `result is exactly target size when pool is large enough`() {
        val seed = song("seed", "Seed", "ArtistA")
        val pool = (1..25).map { song("s$it", "Track $it", "ArtistB") }
        val result = InstantMixSelector.interleaveMix(seed, pool, target = 20)
        assertEquals(20, result.size)
    }

    @Test
    fun `seed not duplicated if already in pool`() {
        val seed = song("seed", "Seed", "ArtistA")
        val pool = listOf(seed) + (1..19).map { song("s$it", "Track $it", "ArtistB") }
        val result = InstantMixSelector.interleaveMix(seed, pool, target = 20)
        assertEquals(20, result.size)
        assertEquals(seed, result.first())
        assertEquals(1, result.count { it.id == "seed" })
    }

    // ── interleaveMix — size guarantees ──────────────────────────────────────

    @Test
    fun `interleaveMix with 30 song pool returns exactly 20`() {
        val seed = song("seed", "Seed", "ArtistA")
        val pool = (1..30).map { song("s$it", "Track $it", "Artist${it % 5}") }
        val result = InstantMixSelector.interleaveMix(seed, pool, target = 20)
        assertEquals(20, result.size)
    }

    @Test
    fun `interleaveMix with 15 song pool returns 16 (seed + 15)`() {
        val seed = song("seed", "Seed", "ArtistX")
        val pool = (1..15).map { song("s$it", "Track $it", "Artist${it % 4}") }
        val result = InstantMixSelector.interleaveMix(seed, pool, target = 20)
        assertEquals(16, result.size)
    }

    @Test
    fun `interleaveMix with single artist 30 songs returns exactly 20`() {
        // All songs from same artist — deferred flush ensures we still reach 20
        val seed = song("seed", "Seed", "ArtistA")
        val pool = (1..30).map { song("s$it", "Track $it", "ArtistA") }
        val result = InstantMixSelector.interleaveMix(seed, pool, target = 20)
        assertEquals(20, result.size)
    }

    // ── interleaveMix — ordering preservation ────────────────────────────────

    @Test
    fun `higher ranked songs appear before lower ranked within interleave constraint`() {
        // Pool in rank order: s2(A), s3(B), s4(B), s5(A)
        // s4(B) gets deferred behind s3(B); s5(A) is inserted before s4(B) is flushed
        // → within each artist, original pool rank order is maintained
        val seed = song("1", "Seed", "ArtistA")
        val pool = listOf(
            song("2", "Rank1-A", "ArtistA"),
            song("3", "Rank2-B", "ArtistB"),
            song("4", "Rank3-B", "ArtistB"),
            song("5", "Rank4-A", "ArtistA"),
        )
        val result = InstantMixSelector.interleaveMix(seed, pool)
        // Within ArtistA: s2 (rank 1) must come before s5 (rank 4)
        val idxS2 = result.indexOfFirst { it.id == "2" }
        val idxS5 = result.indexOfFirst { it.id == "5" }
        assertTrue("ArtistA rank order violated: s2=$idxS2, s5=$idxS5", idxS2 < idxS5)
        // Within ArtistB: s3 (rank 2) must come before s4 (rank 3)
        val idxS3 = result.indexOfFirst { it.id == "3" }
        val idxS4 = result.indexOfFirst { it.id == "4" }
        assertTrue("ArtistB rank order violated: s3=$idxS3, s4=$idxS4", idxS3 < idxS4)
    }

    // ── End-to-end pipeline (getMix equivalent) ───────────────────────────────
    //
    // These tests exercise the full applyDiversityCap → fillToTarget → interleaveMix
    // pipeline with realistic pool sizes, mirroring what getMix() produces after
    // fetching from LB and Subsonic.

    private fun runPipeline(
        seed: SongEntity,
        pool: List<SongEntity>,
        target: Int = 20,
    ): List<SongEntity> {
        val capped = InstantMixSelector.applyDiversityCap(pool, target = target - 1, initialCap = 5, relaxedCap = 8)
        val filled = InstantMixSelector.fillToTarget(capped, pool, target = target - 1)
        return InstantMixSelector.interleaveMix(seed, filled, target = target)
    }

    @Test
    fun `getMix result is always min(20, availableSongs) regardless of LB match count`() {
        val seed = song("seed", "Seed", "SeedArtist")

        // Large pool (≥ 19 companions) → always 20
        val largePool = (1..25).map { song("s$it", "Track $it", "Artist${it % 6}") }
        assertEquals(20, runPipeline(seed, largePool).size)

        // Small pool (5 companions) → 6 (seed + 5)
        val smallPool = (1..5).map { song("s$it", "Track $it", "Artist${it % 3}") }
        assertEquals(6, runPipeline(seed, smallPool).size)
    }

    @Test
    fun `getMix with 0 LB matches and 25 Subsonic candidates returns 20`() {
        // Simulates: lbMatchCount=0, Subsonic batch returns 25 diverse songs
        val seed = song("seed", "Seed", "Newkid")
        val pool = (1..25).map { song("sub$it", "Similar $it", "SimilarArtist${it % 8}") }
        val result = runPipeline(seed, pool)
        assertEquals(20, result.size)
    }

    @Test
    fun `getMix with 5 LB matches and 20 Subsonic candidates returns 20`() {
        // Simulates: 5 LB-matched songs + 20 Subsonic songs (no overlap) = 25 total
        val seed = song("seed", "Seed", "Newkid")
        val lbSongs = (1..5).map { song("lb$it", "LB Track $it", "LbArtist$it") }
        val subSongs = (1..20).map { song("sub$it", "Sub Track $it", "SubArtist${it % 5}") }
        val pool = lbSongs + subSongs
        val result = runPipeline(seed, pool)
        assertEquals(20, result.size)
    }

    @Test
    fun `getMix with 5 LB matches and 10 Subsonic candidates returns 15 when pool has 14 unique`() {
        // Simulates: 5 LB-matched + 10 Subsonic but 1 Subsonic song duplicates an LB song
        // → 14 unique companion songs → seed + 14 = 15
        val seed = song("seed", "Seed", "Newkid")
        val lbSongs = (1..5).map { song("lb$it", "LB Track $it", "LbArtist$it") }
        // First Subsonic song is a duplicate of lbSongs[0]; remaining 9 are unique
        val subSongs = listOf(lbSongs[0]) + (1..9).map { song("sub$it", "Sub Track $it", "SubArtist$it") }
        val pool = (lbSongs + subSongs).distinctBy { it.id } // 14 unique
        assertEquals(14, pool.size)
        val result = runPipeline(seed, pool)
        assertEquals(15, result.size) // seed + 14
    }

    @Test
    fun `getMix pipeline produces no duplicate song IDs`() {
        val seed = song("seed", "Seed", "SeedArtist")
        val pool = (1..30).map { song("s$it", "Track $it", "Artist${it % 7}") }
        val result = runPipeline(seed, pool)
        val ids = result.map { it.id }
        assertEquals("Duplicate IDs in mix", ids.distinct(), ids)
    }

    @Test
    fun `getMix pipeline with single artist 30 songs reaches 20`() {
        // Even with all songs from one artist, mix still reaches 20 (deferred flush)
        val seed = song("seed", "Seed", "SeedArtist")
        val pool = (1..30).map { song("s$it", "Track $it", "SameArtist") }
        val result = runPipeline(seed, pool)
        assertEquals(20, result.size)
    }
}
