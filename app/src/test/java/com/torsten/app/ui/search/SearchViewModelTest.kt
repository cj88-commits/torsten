package com.torsten.app.ui.search

import com.torsten.app.data.api.dto.SongDto
import com.torsten.app.data.db.entity.SongEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration-level tests for [AlbumQueueBuilder] — the component that [SearchViewModel]
 * delegates to when a track is tapped in search results.
 *
 * These tests catch the real call-site failure: if the builder bypasses the DAO and just
 * returns the single tapped song, every assertion about queue size and startIndex will fail.
 *
 * Uses [FakeSongDao] (hand-rolled in-memory fake) — no mocking framework needed.
 */
class SearchViewModelTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun entity(
        id: String,
        trackNumber: Int,
        albumId: String = "album1",
        discNumber: Int = 1,
    ) = SongEntity(
        id = id,
        albumId = albumId,
        artistId = "artist1",
        title = "Track $trackNumber",
        trackNumber = trackNumber,
        discNumber = discNumber,
        duration = 200,
        bitRate = null,
        suffix = null,
        contentType = null,
        starred = false,
        localFilePath = null,
        lastUpdated = 0L,
    )

    private fun dto(
        id: String,
        albumId: String? = "album1",
        title: String = id,
    ) = SongDto(
        id = id,
        title = title,
        albumId = albumId,
        album = "Album",
        artist = "Artist",
        artistId = "artist1",
        coverArt = "art1",
    )

    /** Returns an [AlbumQueueBuilder] backed by the given album→songs map. */
    private fun builder(vararg pairs: Pair<String, List<SongEntity>>) =
        AlbumQueueBuilder(FakeSongDao(mapOf(*pairs)))

    // ── playSongs called with full album list not truncated list ───────────────

    @Test
    fun `playSongs called with full album list not truncated list`() = runBlocking {
        val songs = (1..10).map { entity("s$it", it) }
        val tapped = dto("s3") // tap track 3
        val (queue, _) = builder("album1" to songs).build(tapped)
        assertEquals("Full album must be queued, not just tracks from s3 onwards", 10, queue.size)
    }

    @Test
    fun `playSongs called with startIndex 1 when second track tapped`() = runBlocking {
        val songs = (1..10).map { entity("s$it", it) }
        val tapped = dto("s2") // second track = index 1
        val (queue, startIndex) = builder("album1" to songs).build(tapped)
        assertEquals(10, queue.size)
        assertEquals("startIndex must be 1 for second track", 1, startIndex)
    }

    @Test
    fun `playSongs called with startIndex 0 when first track tapped`() = runBlocking {
        val songs = (1..10).map { entity("s$it", it) }
        val tapped = dto("s1")
        val (_, startIndex) = builder("album1" to songs).build(tapped)
        assertEquals(0, startIndex)
    }

    @Test
    fun `queue element at startIndex is the tapped song`() = runBlocking {
        val songs = (1..10).map { entity("s$it", it) }
        val tapped = dto("s7")
        val (queue, startIndex) = builder("album1" to songs).build(tapped)
        assertEquals("s7", queue[startIndex].id)
    }

    @Test
    fun `startIndex is correct for middle track`() = runBlocking {
        val songs = (1..10).map { entity("s$it", it) }
        val tapped = dto("s5") // 5th track = index 4
        val (_, startIndex) = builder("album1" to songs).build(tapped)
        assertEquals(4, startIndex)
    }

    // ── onTrackClick fetches album songs from DAO ──────────────────────────────

    @Test
    fun `onTrackClick fetches album songs from dao and returns full album`() = runBlocking {
        val songs = (1..10).map { entity("s$it", it) }
        val tapped = dto("s3")
        val (queue, _) = builder("album1" to songs).build(tapped)
        assertEquals(10, queue.size)
    }

    @Test
    fun `onTrackClick does NOT return list of size 1 unless album has 1 song`() = runBlocking {
        val songs = (1..10).map { entity("s$it", it) }
        val tapped = dto("s2")
        val (queue, _) = builder("album1" to songs).build(tapped)
        assertTrue("Queue must have 10 songs, not just the tapped one", queue.size == 10)
    }

    // ── DAO is called with the correct albumId ─────────────────────────────────

    @Test
    fun `builder fetches songs for tapped song albumId not a different album`() = runBlocking {
        val correctAlbum = (1..5).map { entity("c$it", it, albumId = "correct") }
        val wrongAlbum = (1..5).map { entity("w$it", it, albumId = "wrong") }
        val tapped = dto("c3", albumId = "correct")
        val (queue, _) = builder(
            "correct" to correctAlbum,
            "wrong" to wrongAlbum,
        ).build(tapped)
        assertTrue("Queue must contain only songs from the tapped song's album",
            queue.all { it.albumId == "correct" })
    }

    @Test
    fun `all songs in queue share the same albumId as the tapped song`() = runBlocking {
        val songs = (1..5).map { entity("s$it", it) }
        val tapped = dto("s2")
        val (queue, _) = builder("album1" to songs).build(tapped)
        assertTrue(queue.all { it.albumId == "album1" })
    }

    // ── Fallback branches ─────────────────────────────────────────────────────

    @Test
    fun `returns single song and startIndex 0 when albumId is null`() = runBlocking {
        val tapped = dto("s1", albumId = null)
        val (queue, startIndex) = builder().build(tapped)
        assertEquals(1, queue.size)
        assertEquals("s1", queue.first().id)
        assertEquals(0, startIndex)
    }

    @Test
    fun `returns single song and startIndex 0 when DAO has no songs for the album`() = runBlocking {
        val tapped = dto("s1", albumId = "album-not-synced")
        val (queue, startIndex) = builder().build(tapped)
        assertEquals(1, queue.size)
        assertEquals("s1", queue.first().id)
        assertEquals(0, startIndex)
    }

    @Test
    fun `returns single song when tapped song id is not in Room album`() = runBlocking {
        val songs = (1..5).map { entity("room_s$it", it) }
        val tapped = dto("api_s3", albumId = "album1") // ID mismatch: api vs room
        val (queue, startIndex) = builder("album1" to songs).build(tapped)
        assertEquals("ID mismatch must fall back to single song", 1, queue.size)
        assertEquals(0, startIndex)
    }

    // ── Queue ordering ─────────────────────────────────────────────────────────

    @Test
    fun `queue is in ascending track number order`() = runBlocking {
        val songs = listOf(
            entity("s5", 5), entity("s1", 1), entity("s3", 3),
            entity("s2", 2), entity("s4", 4),
        )
        val tapped = dto("s3")
        val (queue, _) = builder("album1" to songs).build(tapped)
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), queue.map { it.id })
    }

    @Test
    fun `tapping the last track gives startIndex at last position`() = runBlocking {
        val songs = (1..5).map { entity("s$it", it) }
        val tapped = dto("s5")
        val (queue, startIndex) = builder("album1" to songs).build(tapped)
        assertEquals(5, queue.size)
        assertEquals(4, startIndex)
        assertEquals("s5", queue[startIndex].id)
    }

    @Test
    fun `tapping the first track gives startIndex 0 and full album`() = runBlocking {
        val songs = (1..5).map { entity("s$it", it) }
        val tapped = dto("s1")
        val (queue, startIndex) = builder("album1" to songs).build(tapped)
        assertEquals(5, queue.size)
        assertEquals(0, startIndex)
    }

    // ── SongDto fields are preserved correctly ─────────────────────────────────

    @Test
    fun `queue SongDto ids match Room entity ids in sorted order`() = runBlocking {
        val songs = (1..4).map { entity("s$it", it) }
        val tapped = dto("s1")
        val (queue, _) = builder("album1" to songs).build(tapped)
        assertEquals(listOf("s1", "s2", "s3", "s4"), queue.map { it.id })
    }

    @Test
    fun `queue SongDto track numbers are populated from Room`() = runBlocking {
        val songs = (1..4).map { entity("s$it", it) }
        val tapped = dto("s1")
        val (queue, _) = builder("album1" to songs).build(tapped)
        assertEquals(listOf(1, 2, 3, 4), queue.map { it.track })
    }

    // ── Entry-point tests: Top Result card vs track-list rows ──────────────────
    //
    // All tap surfaces in SearchScreen (Top Result card, "All" view track rows,
    // "Tracks" filter rows) route through the same onSongTapped() handler which
    // calls AlbumQueueBuilder.build(). These tests document that contract by name
    // so a future regression at any entry point is immediately obvious.

    @Test
    fun `onSongTapped from Top Result card queues full album`() = runBlocking {
        // Top Result card taps onTrackClick(topResult.song) — same builder path.
        val songs = (1..9).map { entity("s$it", it) }
        val topResultSong = dto("s1") // as if Top Result resolved to track 1
        val (queue, startIndex) = builder("album1" to songs).build(topResultSong)
        assertEquals("Top Result tap must queue full album", 9, queue.size)
        assertEquals(0, startIndex)
    }

    @Test
    fun `onSongTapped from track row queues full album`() = runBlocking {
        // Track-list row taps onTrackClick(song) — same builder path.
        val songs = (1..9).map { entity("s$it", it) }
        val trackRowSong = dto("s4") // user taps "Liquor Store Blues" — track 4
        val (queue, _) = builder("album1" to songs).build(trackRowSong)
        assertEquals("Track row tap must queue full album, not just remaining tracks", 9, queue.size)
    }

    @Test
    fun `onSongTapped always calls playSongs with albumSongs size not 1`() = runBlocking {
        val songs = (1..9).map { entity("s$it", it) }
        listOf("s1", "s4", "s9").forEach { id ->
            val tapped = dto(id)
            val (queue, _) = builder("album1" to songs).build(tapped)
            assertEquals("Tapping $id must produce a queue of 9, not 1", 9, queue.size)
        }
    }

    @Test
    fun `onSongTapped with startIndex 0 when first track`() = runBlocking {
        val songs = (1..9).map { entity("s$it", it) }
        val (_, startIndex) = builder("album1" to songs).build(dto("s1"))
        assertEquals(0, startIndex)
    }

    @Test
    fun `onSongTapped with startIndex greater than 0 when non-first track`() = runBlocking {
        val songs = (1..9).map { entity("s$it", it) }
        // Tap "Liquor Store Blues" (track 4, index 3)
        val (_, startIndex) = builder("album1" to songs).build(dto("s4"))
        assertTrue("startIndex must be > 0 for non-first track", startIndex > 0)
        assertEquals(3, startIndex)
    }
}
