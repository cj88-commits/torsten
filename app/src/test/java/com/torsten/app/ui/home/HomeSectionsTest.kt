package com.torsten.app.ui.home

import com.torsten.app.data.api.dto.AlbumDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSectionsTest {

    private fun album(id: String, artistId: String? = null) =
        AlbumDto(id = id, name = "Album $id", artistId = artistId)

    // ── Continue Listening vs Recently Played ────────────────────────────────

    @Test
    fun `continue listening album does not appear in recently played row`() {
        val recent = (1..12).map { album("r$it", "artist_$it") }
        val sections = buildHomeSections(recent, emptyList(), emptyList())

        val heroId = sections.continueListening?.id
        assertNotNull("Expected a continue listening album", heroId)
        assertFalse(
            "Continue Listening album '$heroId' also appears in Recently Played",
            sections.recentlyPlayed.any { it.id == heroId },
        )
    }

    @Test
    fun `recently played is empty when there is only one recent album`() {
        val sections = buildHomeSections(listOf(album("only")), emptyList(), emptyList())
        assertEquals("only", sections.continueListening?.id)
        assertTrue(
            "Recently Played should be empty when only the hero exists",
            sections.recentlyPlayed.isEmpty(),
        )
    }

    @Test
    fun `recently played contains up to 10 albums not including the hero`() {
        val recent = (1..15).map { album("r$it") }
        val sections = buildHomeSections(recent, emptyList(), emptyList())

        assertEquals("r1", sections.continueListening?.id)
        assertEquals(10, sections.recentlyPlayed.size)
        assertFalse(
            "Hero r1 must not be in Recently Played",
            sections.recentlyPlayed.any { it.id == "r1" },
        )
        // Should contain r2..r11
        assertEquals("r2", sections.recentlyPlayed.first().id)
        assertEquals("r11", sections.recentlyPlayed.last().id)
    }

    @Test
    fun `continue listening is null when recent list is empty`() {
        val sections = buildHomeSections(emptyList(), emptyList(), emptyList())
        assertNull(sections.continueListening)
        assertTrue(sections.recentlyPlayed.isEmpty())
    }

    // ── screenAlbumIds completeness ──────────────────────────────────────────

    @Test
    fun `screenAlbumIds includes the continue listening hero`() {
        val recent = (1..5).map { album("r$it") }
        val sections = buildHomeSections(recent, emptyList(), emptyList())
        assertTrue(
            "screenAlbumIds must include the continue listening hero",
            sections.continueListening?.id in sections.screenAlbumIds,
        )
    }

    @Test
    fun `screenAlbumIds includes all recently played albums`() {
        val recent = (1..12).map { album("r$it") }
        val sections = buildHomeSections(recent, emptyList(), emptyList())
        sections.recentlyPlayed.forEach { album ->
            assertTrue(
                "screenAlbumIds missing recently played album '${album.id}'",
                album.id in sections.screenAlbumIds,
            )
        }
    }

    // ── No cross-section duplicates ──────────────────────────────────────────

    @Test
    fun `no album ID appears in more than one home section`() {
        val recent   = (1..12).map { album("r$it",  "AR$it") }
        val frequent = (1..12).map { album("f$it",  "AF$it") }
        val newest   = (1..10).map { album("n$it",  "AN$it") }
        val sections = buildHomeSections(recent, frequent, newest)

        val heroIds = listOfNotNull(sections.continueListening?.id)
        val rpIds   = sections.recentlyPlayed.map { it.id }
        val naIds   = sections.newAdditions.map   { it.id }
        val mpIds   = sections.mostPlayed.map     { it.id }

        val all = heroIds + rpIds + naIds + mpIds
        assertEquals(
            "Duplicate album ID found across Home sections",
            all.distinct(),
            all,
        )
    }
}
