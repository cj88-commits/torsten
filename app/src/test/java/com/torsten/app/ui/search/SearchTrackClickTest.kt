package com.torsten.app.ui.search

import com.torsten.app.data.db.entity.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTrackClickTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun song(
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
        duration = 180,
        bitRate = null,
        suffix = null,
        contentType = null,
        starred = false,
        localFilePath = null,
        lastUpdated = 0L,
    )

    /** Five-track album in shuffled order to verify sorting. */
    private val albumSongs = listOf(
        song("s3", 3),
        song("s1", 1),
        song("s5", 5),
        song("s2", 2),
        song("s4", 4),
    )

    // Helper to unwrap the pair
    private fun buildQueue(tapped: SongEntity, songs: List<SongEntity>) =
        SearchTrackClickHandler.buildQueue(tapped, songs)

    // ── Queue construction ────────────────────────────────────────────────────

    @Test
    fun `buildQueue returns full album not just from tapped track onwards`() {
        val tapped = song("s3", 3)
        val (queue, _) = buildQueue(tapped, albumSongs)
        assertEquals("Full album must be in queue", 5, queue.size)
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), queue.map { it.id })
    }

    @Test
    fun `buildQueue startIndex is 0 when first track tapped`() {
        val tapped = song("s1", 1)
        val (_, startIndex) = buildQueue(tapped, albumSongs)
        assertEquals(0, startIndex)
    }

    @Test
    fun `buildQueue startIndex is 1 when second track tapped`() {
        val tapped = song("s2", 2)
        val (_, startIndex) = buildQueue(tapped, albumSongs)
        assertEquals(1, startIndex)
    }

    @Test
    fun `buildQueue startIndex is correct for middle track`() {
        val tapped = song("s3", 3) // track 3 = index 2 in 1-indexed sorted list
        val (_, startIndex) = buildQueue(tapped, albumSongs)
        assertEquals(2, startIndex)
    }

    @Test
    fun `buildQueue full album size matches albumSongs size`() {
        val tapped = song("s4", 4)
        val (queue, _) = buildQueue(tapped, albumSongs)
        assertEquals(albumSongs.size, queue.size)
    }

    @Test
    fun `tracks before tapped track ARE in the queue`() {
        val tapped = song("s4", 4)
        val (queue, _) = buildQueue(tapped, albumSongs)
        val ids = queue.map { it.id }
        assertTrue("s1 should be present before start", "s1" in ids)
        assertTrue("s2 should be present before start", "s2" in ids)
        assertTrue("s3 should be present before start", "s3" in ids)
    }

    @Test
    fun `queue is sorted by disc then track number`() {
        val tapped = song("s1", 1)
        val (queue, _) = buildQueue(tapped, albumSongs)
        val trackNumbers = queue.map { it.trackNumber }
        assertEquals(trackNumbers.sorted(), trackNumbers)
    }

    @Test
    fun `clicking first track gives startIndex 0 and full album`() {
        val tapped = song("s1", 1)
        val (queue, startIndex) = buildQueue(tapped, albumSongs)
        assertEquals(5, queue.size)
        assertEquals(0, startIndex)
        assertEquals("s1", queue[startIndex].id)
    }

    @Test
    fun `clicking last track gives startIndex at last position`() {
        val tapped = song("s5", 5)
        val (queue, startIndex) = buildQueue(tapped, albumSongs)
        assertEquals(5, queue.size)
        assertEquals(4, startIndex)
        assertEquals("s5", queue[startIndex].id)
    }

    @Test
    fun `clicking middle track gives correct startIndex`() {
        val tapped = song("s3", 3)
        val (queue, startIndex) = buildQueue(tapped, albumSongs)
        assertEquals(5, queue.size)
        assertEquals(2, startIndex)
        assertEquals("s3", queue[startIndex].id)
    }

    @Test
    fun `queue element at startIndex is always the tapped song`() {
        listOf("s1" to 1, "s2" to 2, "s3" to 3, "s4" to 4, "s5" to 5).forEach { (id, track) ->
            val tapped = song(id, track)
            val (queue, startIndex) = buildQueue(tapped, albumSongs)
            assertEquals("queue[$startIndex] must be tapped song $id", id, queue[startIndex].id)
        }
    }

    // ── Correct album loaded ──────────────────────────────────────────────────

    @Test
    fun `queue songs all share the same albumId as tapped song`() {
        val tapped = song("s2", 2)
        val (queue, _) = buildQueue(tapped, albumSongs)
        assertTrue(queue.all { it.albumId == tapped.albumId })
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `single-song album returns queue of 1 and startIndex 0`() {
        val only = song("solo", 1, albumId = "album-single")
        val (queue, startIndex) = buildQueue(only, listOf(only))
        assertEquals(1, queue.size)
        assertEquals("solo", queue.first().id)
        assertEquals(0, startIndex)
    }

    @Test
    fun `queue contains no duplicate song IDs`() {
        val tapped = song("s1", 1)
        val duplicated = albumSongs + albumSongs
        val (queue, _) = buildQueue(tapped, duplicated)
        val ids = queue.map { it.id }
        assertEquals("Expected no duplicates", ids.distinct(), ids)
    }

    @Test
    fun `track numbers in full queue are in ascending order`() {
        val tapped = song("s2", 2)
        val (queue, _) = buildQueue(tapped, albumSongs)
        val trackNumbers = queue.map { it.trackNumber }
        assertEquals(trackNumbers.sorted(), trackNumbers)
    }

    @Test
    fun `startIndex falls back to 0 when tapped song not found in album list`() {
        val notInAlbum = song("missing", 99)
        val (queue, startIndex) = buildQueue(notInAlbum, albumSongs)
        assertEquals(0, startIndex)
        assertEquals(5, queue.size) // still returns full sorted album
    }
}
