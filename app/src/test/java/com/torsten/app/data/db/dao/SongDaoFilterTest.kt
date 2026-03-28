package com.torsten.app.data.db.dao

import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.ui.search.FakeSongDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [SongDao.getSongsByArtistName] contract.
 *
 * The contract: return songs where EITHER [SongEntity.artistName] OR
 * [SongEntity.albumArtistName] exactly equals the query — not substring,
 * not featuring credits embedded in the track-artist string.
 *
 * Tests run against [FakeSongDao] which mirrors the same exact-match logic
 * used by the Room query. The SQL is validated by instrumented tests.
 */
class SongDaoFilterTest {

    private fun song(
        id: String,
        title: String,
        artistName: String,
        albumArtistName: String,
        albumId: String = "album_$id",
        trackNumber: Int = 1,
    ) = SongEntity(
        id = id,
        albumId = albumId,
        artistId = artistName,
        title = title,
        trackNumber = trackNumber,
        discNumber = 1,
        duration = 180,
        bitRate = null,
        suffix = null,
        contentType = null,
        starred = false,
        localFilePath = null,
        lastUpdated = 0L,
        artistName = artistName,
        albumArtistName = albumArtistName,
    )

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /**
     * "How We Do (Party)" — track by Rita Ora, featuring Avicii.
     * artistName = "Rita Ora ft. Avicii", albumArtistName = "Rita Ora".
     * Should NOT appear on Avicii's page.
     */
    private val howWeDoParty = song(
        id = "hwd",
        title = "How We Do (Party)",
        artistName = "Rita Ora ft. Avicii",
        albumArtistName = "Rita Ora",
        albumId = "album_ritaora",
    )

    /**
     * "Lonely Together" — from the Avicii album.
     * artistName = "Avicii ft. Rita Ora", albumArtistName = "Avicii".
     * Should appear on Avicii's page but NOT on Rita Ora's page.
     */
    private val lonelyTogetherOnAvicii = song(
        id = "lt_avicii",
        title = "Lonely Together",
        artistName = "Avicii ft. Rita Ora",
        albumArtistName = "Avicii",
        albumId = "album_avicii",
    )

    /**
     * Same recording released on a Rita Ora compilation.
     * albumArtistName = "Rita Ora" — should appear on Rita Ora's page.
     */
    private val lonelyTogetherOnRita = song(
        id = "lt_rita",
        title = "Lonely Together",
        artistName = "Avicii ft. Rita Ora",
        albumArtistName = "Rita Ora",
        albumId = "album_ritaora_comp",
    )

    /** Straight Avicii track — no featuring credit. */
    private val wakeMe = song(
        id = "wm",
        title = "Wake Me Up",
        artistName = "Avicii",
        albumArtistName = "Avicii",
        albumId = "album_avicii",
        trackNumber = 2,
    )

    private val allSongs = listOf(howWeDoParty, lonelyTogetherOnAvicii, lonelyTogetherOnRita, wakeMe)
    private val dao = FakeSongDao(songs = allSongs)

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `getSongsByArtistName exact match only - does not return featuring songs`() = runTest {
        val result = dao.getSongsByArtistName("Avicii")
        val ids = result.map { it.id }
        // "How We Do (Party)" has artistName="Rita Ora ft. Avicii" — neither field equals "Avicii"
        assertFalse("Featuring-only track must not appear", "hwd" in ids)
    }

    @Test
    fun `getSongsByArtistName returns songs where albumArtistName matches`() = runTest {
        val result = dao.getSongsByArtistName("Avicii")
        val ids = result.map { it.id }
        // "Lonely Together" on Avicii album has albumArtistName="Avicii"
        assertTrue("Track with matching albumArtistName must appear", "lt_avicii" in ids)
        // "Wake Me Up" has both fields = "Avicii"
        assertTrue("Plain Avicii track must appear", "wm" in ids)
    }

    @Test
    fun `Rita Ora featuring song does not appear on Avicii artist page`() = runTest {
        val result = dao.getSongsByArtistName("Avicii")
        assertFalse(
            "Rita Ora track featuring Avicii must not appear on Avicii page",
            result.any { it.id == "hwd" },
        )
    }

    @Test
    fun `Lonely Together appears on Avicii page when albumArtistName is Avicii`() = runTest {
        val result = dao.getSongsByArtistName("Avicii")
        assertTrue(
            "Lonely Together (Avicii album) must appear on Avicii page",
            result.any { it.id == "lt_avicii" },
        )
    }

    @Test
    fun `Lonely Together appears on Rita Ora page when albumArtistName is Rita Ora`() = runTest {
        val result = dao.getSongsByArtistName("Rita Ora")
        assertTrue(
            "Lonely Together (Rita Ora comp) must appear on Rita Ora page",
            result.any { it.id == "lt_rita" },
        )
    }

    @Test
    fun `Avicii featuring song on Rita Ora album does not appear on Rita Ora page via featuring credit`() = runTest {
        val result = dao.getSongsByArtistName("Rita Ora")
        val ids = result.map { it.id }
        // lonelyTogetherOnAvicii has albumArtistName="Avicii" — should not appear on Rita Ora's page
        assertFalse(
            "Lonely Together from Avicii album must not appear on Rita Ora page",
            "lt_avicii" in ids,
        )
    }
}
