package com.torsten.app.ui.home

import com.torsten.app.data.api.dto.AlbumDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouComposerTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun album(
        id: String,
        artistId: String? = null,
    ) = AlbumDto(
        id       = id,
        name     = "Album $id",
        artistId = artistId,
        artist   = artistId?.let { "Artist $it" },
    )

    /** Returns N albums all from the same artist. */
    private fun albumsForArtist(artistId: String, count: Int, idPrefix: String = artistId) =
        (1..count).map { album("${idPrefix}_$it", artistId) }

    /** Returns N albums each from a unique artist. */
    private fun diverseAlbums(count: Int, idPrefix: String = "a") =
        (1..count).map { album("${idPrefix}_$it", artistId = "artist_$it") }

    // ── R1: No duplicate album IDs ────────────────────────────────────────────

    @Test
    fun `no duplicate album IDs when all buckets contain the same albums`() {
        // Same albums appear in recent and frequent — they must not be duplicated.
        val shared = diverseAlbums(8)
        val result = ForYouComposer.compose(
            recent   = shared,
            frequent = shared,
            starred  = shared,
            newest   = shared,
        )
        val ids = result.map { it.id }
        assertEquals("Duplicate IDs found", ids.distinct(), ids)
    }

    @Test
    fun `no duplicate album IDs with heavily overlapping inputs`() {
        val base    = diverseAlbums(15)
        val recent  = base.take(10)
        val freq    = base.drop(3)          // overlaps with recent at indices 3–9
        val starred = base.drop(5).take(5)  // overlaps with both
        val newest  = base.takeLast(4)
        val result  = ForYouComposer.compose(recent, freq, starred, newest)
        val ids     = result.map { it.id }
        assertEquals("Duplicate IDs found", ids.distinct(), ids)
    }

    // ── R2: Unique artist in first 8 positions (strict zone) ─────────────────

    @Test
    fun `no artist appears twice in first 8 items given sufficient diversity`() {
        // 20 albums, each from a unique artist — plenty of diversity to satisfy R2.
        val albums = diverseAlbums(20)
        val result = ForYouComposer.compose(
            recent   = albums.take(10),
            frequent = albums.drop(10),
            starred  = emptyList(),
            newest   = emptyList(),
        )
        val first8ArtistIds = result.take(8).map { it.artistId }
        assertEquals(
            "Artist appeared twice in first 8 positions",
            first8ArtistIds.distinct(), first8ArtistIds,
        )
    }

    @Test
    fun `artist that dominates recent does not repeat in first 8 items`() {
        // Artist A has 6 albums in recent; the remaining recent + all frequent are diverse.
        val dominantArtist = albumsForArtist("A", 6, idPrefix = "r")
        val diverseRecent  = diverseAlbums(4, "r_other")
        val diverse        = diverseAlbums(12, "f")
        val result = ForYouComposer.compose(
            recent   = dominantArtist + diverseRecent,
            frequent = diverse,
            starred  = emptyList(),
            newest   = emptyList(),
        )
        val first8 = result.take(8)
        val countInFirst8 = first8.count { it.artistId == "A" }
        assertTrue(
            "Artist A appeared $countInFirst8 times in first 8 (expected ≤1)",
            countInFirst8 <= 1,
        )
    }

    // ── R3: No consecutive same artist ───────────────────────────────────────

    @Test
    fun `no two consecutive items share an artist (diverse input)`() {
        val result = ForYouComposer.compose(
            recent   = diverseAlbums(10, "r"),
            frequent = diverseAlbums(10, "f"),
            starred  = diverseAlbums(5, "s"),
            newest   = diverseAlbums(5, "n"),
        )
        for (i in 0 until result.size - 1) {
            assertNotEquals(
                "Consecutive same artist at positions $i and ${i + 1}: ${result[i].artistId}",
                result[i].artistKey(), result[i + 1].artistKey(),
            )
        }
    }

    @Test
    fun `no consecutive same artist even when one artist dominates all buckets`() {
        // Worst case: all input albums are from artist X.
        val allSameArtist = albumsForArtist("X", 30, idPrefix = "x")
        val result = ForYouComposer.compose(
            recent   = allSameArtist.take(12),
            frequent = allSameArtist.drop(2).take(12),
            starred  = allSameArtist.drop(4).take(5),
            newest   = allSameArtist.drop(6).take(5),
        )
        // maxPerArtist=2 means at most 2 items, so consecutive cannot happen unless result=1.
        assertTrue("Expected ≤ 2 items from single-artist input", result.size <= 2)
        // If there are 2 items, they must not be consecutive same artist.
        if (result.size == 2) {
            assertNotEquals(
                "Two consecutive items from same artist",
                result[0].artistKey(), result[1].artistKey(),
            )
        }
    }

    @Test
    fun `no consecutive same artist across a 25 item result`() {
        // 5 artists × 5 albums each — worst-case clustering within each bucket.
        val clustered = (1..5).flatMap { a -> albumsForArtist("AR$a", 5, "a${a}") }
        val result = ForYouComposer.compose(
            recent   = clustered,
            frequent = clustered.shuffled(),
            starred  = emptyList(),
            newest   = emptyList(),
        )
        for (i in 0 until result.size - 1) {
            assertNotEquals(
                "Consecutive same artist at $i and ${i + 1}",
                result[i].artistKey(), result[i + 1].artistKey(),
            )
        }
    }

    // ── R4: Max 2 albums per artist in full row ───────────────────────────────

    @Test
    fun `no artist appears more than 2 times in the full result`() {
        val result = ForYouComposer.compose(
            recent   = albumsForArtist("A", 8, "r") + diverseAlbums(4, "r"),
            frequent = albumsForArtist("A", 8, "f") + diverseAlbums(4, "f"),
            starred  = albumsForArtist("A", 5, "s"),
            newest   = albumsForArtist("A", 5, "n"),
        )
        val countA = result.count { it.artistId == "A" }
        assertTrue("Artist A appeared $countA times (max allowed: 2)", countA <= 2)
    }

    @Test
    fun `max 2 per artist enforced across all artists`() {
        // 3 artists × 10 albums each — every artist should be capped at 2.
        val albums = (1..3).flatMap { a -> albumsForArtist("AR$a", 10, "a$a") }
        val result = ForYouComposer.compose(
            recent   = albums,
            frequent = albums.reversed(),
            starred  = emptyList(),
            newest   = emptyList(),
        )
        result.groupBy { it.artistId }.forEach { (artistId, items) ->
            assertTrue(
                "Artist $artistId appeared ${items.size} times (max allowed: 2)",
                items.size <= 2,
            )
        }
    }

    @Test
    fun `custom maxPerArtist=1 enforces single appearance per artist`() {
        val result = ForYouComposer.compose(
            recent      = diverseAlbums(10, "r"),
            frequent    = diverseAlbums(10, "r"),  // same artist IDs — only R1 blocks these
            starred     = emptyList(),
            newest      = emptyList(),
            maxPerArtist = 1,
        )
        result.groupBy { it.artistId }.forEach { (artistId, items) ->
            assertEquals("Artist $artistId appeared ${items.size} times", 1, items.size)
        }
    }

    // ── R5: Screen-visible artists excluded from first 8 positions ────────────

    @Test
    fun `artist from screenArtists does not appear in first 8 positions`() {
        // Artist "HERO" is visible above For You (e.g. in Continue Listening).
        val screenArtists = setOf("HERO")
        val heroAlbums    = albumsForArtist("HERO", 5, idPrefix = "hero")
        val otherAlbums   = diverseAlbums(20, "other")
        val result = ForYouComposer.compose(
            recent        = heroAlbums + otherAlbums.take(5),
            frequent      = heroAlbums + otherAlbums.drop(5).take(5),
            starred       = emptyList(),
            newest        = otherAlbums.drop(10),
            screenArtists = screenArtists,
        )
        val heroCountInFirst8 = result.take(8).count { it.artistId == "HERO" }
        assertEquals(
            "Screen-visible artist HERO appeared in first 8 positions",
            0, heroCountInFirst8,
        )
    }

    @Test
    fun `artist from screenArtists can appear after position 8`() {
        val screenArtists = setOf("HERO")
        val heroAlbums    = albumsForArtist("HERO", 3, idPrefix = "hero")
        val otherAlbums   = diverseAlbums(20, "other")
        val result = ForYouComposer.compose(
            recent        = otherAlbums.take(8) + heroAlbums,
            frequent      = otherAlbums.drop(8),
            starred       = emptyList(),
            newest        = emptyList(),
            screenArtists = screenArtists,
        )
        // HERO may appear, but not before position 8
        val heroPositions = result.mapIndexedNotNull { idx, a ->
            if (a.artistId == "HERO") idx else null
        }
        heroPositions.forEach { pos ->
            assertTrue("HERO appeared at position $pos (must be ≥ 8)", pos >= 8)
        }
    }

    @Test
    fun `multiple screenArtists all excluded from first 8`() {
        val screenArtists = setOf("A", "B", "C")
        val screenAlbums  = listOf("A", "B", "C").flatMap { albumsForArtist(it, 3, it) }
        val other         = diverseAlbums(20, "o")
        val result = ForYouComposer.compose(
            recent        = screenAlbums + other.take(5),
            frequent      = screenAlbums + other.drop(5),
            starred       = emptyList(),
            newest        = emptyList(),
            screenArtists = screenArtists,
        )
        val first8ArtistIds = result.take(8).map { it.artistId }.toSet()
        screenArtists.forEach { sa ->
            assertFalse(
                "Screen artist '$sa' found in first 8 positions",
                sa in first8ArtistIds,
            )
        }
    }

    // ── Multi-bucket composition ───────────────────────────────────────────────

    @Test
    fun `result draws from multiple source buckets`() {
        // Each bucket has unique IDs so we can identify which bucket an album came from.
        val recentAlbums = diverseAlbums(10, "r")
        val favAlbums    = diverseAlbums(10, "f")   // different artistIds, not in recent
        val discAlbums   = diverseAlbums(6,  "s")   // starred, not in recent or frequent
        val newAlbums    = diverseAlbums(6,  "n")   // newest, not in other buckets

        val recentIds  = recentAlbums.map { it.id }.toSet()
        val favIds     = favAlbums.map { it.id }.toSet()
        val discIds    = discAlbums.map { it.id }.toSet()
        val newIds     = newAlbums.map { it.id }.toSet()

        val result = ForYouComposer.compose(
            recent   = recentAlbums,
            frequent = favAlbums,
            starred  = discAlbums,
            newest   = newAlbums,
        )
        val resultIds = result.map { it.id }.toSet()

        // All four buckets should contribute at least one item
        val fromRecent = resultIds.intersect(recentIds)
        val fromFav    = resultIds.intersect(favIds)
        val fromDisc   = resultIds.intersect(discIds)
        val fromNew    = resultIds.intersect(newIds)

        assertTrue("RECENT bucket contributed nothing (got ${fromRecent.size})", fromRecent.isNotEmpty())
        assertTrue("FAVOURITES bucket contributed nothing (got ${fromFav.size})", fromFav.isNotEmpty())
        assertTrue("REDISCOVERY bucket contributed nothing (got ${fromDisc.size})", fromDisc.isNotEmpty())
        assertTrue("EXPLORATION bucket contributed nothing (got ${fromNew.size})", fromNew.isNotEmpty())
    }

    @Test
    fun `no single bucket dominates the first 8 positions`() {
        // With all buckets populated, no bucket should take more than 5 of the first 8 slots.
        val recent   = diverseAlbums(12, "r")
        val frequent = diverseAlbums(12, "f")
        val starred  = diverseAlbums(10, "s")
        val newest   = diverseAlbums(8,  "n")
        val result   = ForYouComposer.compose(recent, frequent, starred, newest)

        val recentIds  = recent.map { it.id }.toSet()
        val first8     = result.take(8)
        val recentInFirst8 = first8.count { it.id in recentIds }

        assertTrue(
            "RECENT dominated first 8 positions ($recentInFirst8 out of 8)",
            recentInFirst8 <= 5,
        )
    }

    // ── Sparse / edge-case inputs ─────────────────────────────────────────────

    @Test
    fun `returns empty list when all inputs are empty`() {
        val result = ForYouComposer.compose(
            recent   = emptyList(),
            frequent = emptyList(),
            starred  = emptyList(),
            newest   = emptyList(),
        )
        assertTrue("Expected empty result", result.isEmpty())
    }

    @Test
    fun `result is non-empty when only one bucket has items`() {
        val result = ForYouComposer.compose(
            recent   = diverseAlbums(5),
            frequent = emptyList(),
            starred  = emptyList(),
            newest   = emptyList(),
        )
        assertFalse("Expected non-empty result with only RECENT populated", result.isEmpty())
    }

    @Test
    fun `handles sparse recent gracefully and fills from other buckets`() {
        // Recent has only 1 item; other buckets are rich.
        val result = ForYouComposer.compose(
            recent   = listOf(album("r1", "AR")),
            frequent = diverseAlbums(12, "f"),
            starred  = diverseAlbums(8,  "s"),
            newest   = diverseAlbums(6,  "n"),
        )
        assertTrue("Expected at least 10 items", result.size >= 10)
        val ids = result.map { it.id }
        assertEquals("Duplicate IDs found", ids.distinct(), ids)
    }

    @Test
    fun `handles empty starred without degrading result quality`() {
        // starred is non-fatal — its absence should not reduce output meaningfully.
        val result = ForYouComposer.compose(
            recent   = diverseAlbums(10, "r"),
            frequent = diverseAlbums(10, "f"),
            starred  = emptyList(),
            newest   = diverseAlbums(6,  "n"),
        )
        assertTrue("Expected at least 10 items without starred", result.size >= 10)
    }

    @Test
    fun `result never exceeds maxItems`() {
        val result = ForYouComposer.compose(
            recent   = diverseAlbums(20, "r"),
            frequent = diverseAlbums(20, "f"),
            starred  = diverseAlbums(10, "s"),
            newest   = diverseAlbums(10, "n"),
            maxItems = 15,
        )
        assertTrue("Expected ≤ 15 items, got ${result.size}", result.size <= 15)
    }

    @Test
    fun `result is deterministic given the same inputs`() {
        val recent   = diverseAlbums(10, "r")
        val frequent = diverseAlbums(10, "f")
        val starred  = diverseAlbums(5,  "s")
        val newest   = diverseAlbums(5,  "n")

        val r1 = ForYouComposer.compose(recent, frequent, starred, newest)
        val r2 = ForYouComposer.compose(recent, frequent, starred, newest)

        assertEquals("Result was not deterministic", r1, r2)
    }

    // ── Combined rule verification ────────────────────────────────────────────

    @Test
    fun `all hard rules hold simultaneously on a realistic mixed input`() {
        // Simulates real-world skew: 3 dominant artists across all buckets.
        val recent = (albumsForArtist("POP", 4, "rp") +
            albumsForArtist("ROCK", 3, "rr") +
            diverseAlbums(3, "r_other"))
        val frequent = (albumsForArtist("POP", 4, "fp") +
            albumsForArtist("INDIE", 4, "fi") +
            diverseAlbums(4, "f_other"))
        val starred  = albumsForArtist("ROCK", 3, "sr") + diverseAlbums(4, "s_other")
        val newest   = diverseAlbums(8, "n")
        val screen   = setOf("POP")

        val result = ForYouComposer.compose(recent, frequent, starred, newest,
            screenArtists = screen)

        // R1: no duplicates
        val ids = result.map { it.id }
        assertEquals("Duplicate IDs found", ids.distinct(), ids)

        // R3: no consecutive same artist
        for (i in 0 until result.size - 1) {
            assertNotEquals(
                "Consecutive same artist at positions $i and ${i + 1}",
                result[i].artistKey(), result[i + 1].artistKey(),
            )
        }

        // R4: max 2 per artist
        result.groupBy { it.artistId }.forEach { (artistId, items) ->
            assertTrue("Artist $artistId appeared ${items.size} times", items.size <= 2)
        }

        // R2: unique artist in first 8
        val first8Artists = result.take(8).map { it.artistId }
        assertEquals("Artist duplicated in first 8", first8Artists.distinct(), first8Artists)

        // R5: screen artists not in first 8
        val first8ArtistSet = first8Artists.filterNotNull().toSet()
        screen.forEach { sa ->
            assertFalse("Screen artist '$sa' found in first 8 positions", sa in first8ArtistSet)
        }
    }

    // ── AlbumDto.artistKey() extension behaviour ──────────────────────────────

    @Test
    fun `albums without artistId are treated as independent artists`() {
        // Two albums with no artistId should both appear (they're treated as unique artists).
        val a1 = album("x1", artistId = null)
        val a2 = album("x2", artistId = null)
        val result = ForYouComposer.compose(
            recent   = listOf(a1, a2),
            frequent = emptyList(),
            starred  = emptyList(),
            newest   = emptyList(),
        )
        assertTrue("Album x1 missing from result", result.any { it.id == "x1" })
        assertTrue("Album x2 missing from result", result.any { it.id == "x2" })
    }

    // ── Cross-section album deduplication (screenAlbumIds) ───────────────────

    @Test
    fun `albums from recentlyPlayed are not shown again in ForYou`() {
        // HomeScreen shows recentlyPlayed = recent.take(10).
        // None of those IDs should appear in the ForYou row.
        val recent   = diverseAlbums(12, "r")
        val frequent = diverseAlbums(12, "f")
        val starred  = diverseAlbums(6,  "s")
        val newest   = diverseAlbums(6,  "n")

        val recentlyPlayedIds = recent.take(10).map { it.id }.toSet()
        val result = ForYouComposer.compose(
            recent          = recent,
            frequent        = frequent,
            starred         = starred,
            newest          = newest,
            screenAlbumIds  = recentlyPlayedIds,
        )
        val overlap = result.map { it.id }.toSet().intersect(recentlyPlayedIds)
        assertTrue(
            "Albums already shown in Recently Played appeared in For You: $overlap",
            overlap.isEmpty(),
        )
    }

    @Test
    fun `albums from newAdditions are not shown again in ForYou`() {
        // HomeScreen shows newAdditions = newest.
        // None of those IDs should appear in the ForYou row.
        val recent   = diverseAlbums(12, "r")
        val frequent = diverseAlbums(12, "f")
        val newest   = diverseAlbums(10, "n")

        val newAdditionIds = newest.map { it.id }.toSet()
        val result = ForYouComposer.compose(
            recent         = recent,
            frequent       = frequent,
            starred        = emptyList(),
            newest         = newest,
            screenAlbumIds = newAdditionIds,
        )
        val overlap = result.map { it.id }.toSet().intersect(newAdditionIds)
        assertTrue(
            "Albums already shown in New Additions appeared in For You: $overlap",
            overlap.isEmpty(),
        )
    }

    @Test
    fun `albums from mostPlayed are not shown again in ForYou`() {
        // HomeScreen shows mostPlayed = frequent.take(10).
        // None of those IDs should appear in the ForYou row.
        val recent   = diverseAlbums(12, "r")
        val frequent = diverseAlbums(15, "f")
        val newest   = diverseAlbums(8,  "n")

        val mostPlayedIds = frequent.take(10).map { it.id }.toSet()
        val result = ForYouComposer.compose(
            recent         = recent,
            frequent       = frequent,
            starred        = emptyList(),
            newest         = newest,
            screenAlbumIds = mostPlayedIds,
        )
        val overlap = result.map { it.id }.toSet().intersect(mostPlayedIds)
        assertTrue(
            "Albums already shown in Most Played appeared in For You: $overlap",
            overlap.isEmpty(),
        )
    }

    @Test
    fun `no album from any visible home section appears in ForYou`() {
        // Simulate exactly what HomeViewModel does:
        //   recentlyPlayed  = recent.take(10)
        //   newAdditions    = newest
        //   mostPlayed      = frequent.take(10)
        //   screenAlbumIds  = union of those three sets
        val recent   = diverseAlbums(12, "r")
        val frequent = diverseAlbums(15, "f")
        val starred  = diverseAlbums(8,  "s")
        val newest   = diverseAlbums(10, "n")

        val screenAlbumIds = (
            recent.take(10).map { it.id } +
            newest.map { it.id } +
            frequent.take(10).map { it.id }
        ).toSet()

        val result = ForYouComposer.compose(
            recent         = recent,
            frequent       = frequent,
            starred        = starred,
            newest         = newest,
            screenAlbumIds = screenAlbumIds,
        )
        val overlap = result.map { it.id }.toSet().intersect(screenAlbumIds)
        assertTrue(
            "ForYou contained ${overlap.size} album(s) already visible elsewhere: $overlap",
            overlap.isEmpty(),
        )
    }

    @Test
    fun `ForYou still returns results when recent and newest are fully excluded`() {
        // Even if recent and newest are all excluded (shown in other sections),
        // ForYou should still draw from frequent and starred.
        val recent   = diverseAlbums(10, "r")
        val frequent = diverseAlbums(15, "f")
        val starred  = diverseAlbums(8,  "s")
        val newest   = diverseAlbums(8,  "n")

        val screenAlbumIds = (recent.map { it.id } + newest.map { it.id }).toSet()

        val result = ForYouComposer.compose(
            recent         = recent,
            frequent       = frequent,
            starred        = starred,
            newest         = newest,
            screenAlbumIds = screenAlbumIds,
        )
        assertTrue("Expected results from frequent/starred when recent+newest excluded", result.isNotEmpty())
        val overlap = result.map { it.id }.toSet().intersect(screenAlbumIds)
        assertTrue("Excluded albums appeared in ForYou: $overlap", overlap.isEmpty())
    }

    @Test
    fun `screenAlbumIds overrides all four source buckets`() {
        // All albums across all buckets are screen-visible — result must be empty.
        val albums   = diverseAlbums(20, "a")
        val excluded = albums.map { it.id }.toSet()
        val result   = ForYouComposer.compose(
            recent         = albums.take(12),
            frequent       = albums.take(12),
            starred        = albums.take(8),
            newest         = albums.take(8),
            screenAlbumIds = excluded,
        )
        assertTrue("Expected empty result when all albums are screen-visible", result.isEmpty())
    }

    @Test
    fun `all hard rules still hold when screenAlbumIds filters heavily`() {
        // With most of recent excluded, ForYou must still satisfy R1, R3, R4.
        val recent   = diverseAlbums(12, "r")
        val frequent = diverseAlbums(12, "f")
        val starred  = diverseAlbums(6,  "s")
        val newest   = diverseAlbums(6,  "n")

        // Exclude first 10 of recent (simulates recentlyPlayed = recent.take(10))
        val screenAlbumIds = recent.take(10).map { it.id }.toSet()

        val result = ForYouComposer.compose(
            recent         = recent,
            frequent       = frequent,
            starred        = starred,
            newest         = newest,
            screenAlbumIds = screenAlbumIds,
        )

        // R1: no duplicates
        val ids = result.map { it.id }
        assertEquals("Duplicate IDs found after heavy screenAlbumIds filter", ids.distinct(), ids)

        // R1 + screenAlbumIds: no excluded album in result
        val overlap = ids.toSet().intersect(screenAlbumIds)
        assertTrue("Excluded album appeared in ForYou: $overlap", overlap.isEmpty())

        // R3: no consecutive same artist
        for (i in 0 until result.size - 1) {
            assertNotEquals(
                "Consecutive same artist at $i and ${i + 1}",
                result[i].artistKey(), result[i + 1].artistKey(),
            )
        }

        // R4: max 2 per artist
        result.groupBy { it.artistId }.forEach { (artistId, items) ->
            assertTrue("Artist $artistId appeared ${items.size} times (max 2)", items.size <= 2)
        }
    }

    // ── Helper to access internal artistKey() extension in tests ─────────────

    private fun AlbumDto.artistKey(): String = with(ForYouComposer) { artistKey() }
}
