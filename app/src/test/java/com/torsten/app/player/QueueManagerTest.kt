package com.torsten.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [QueueManager].
 *
 * Uses [String] as the type parameter to avoid any Android / MediaItem dependencies.
 *
 * Layout invariant under test:
 *   items = [played(0..currentIndex-1), current(currentIndex),
 *            upNext(currentIndex+1..currentIndex+upNextCount),
 *            playingFrom(currentIndex+upNextCount+1..end)]
 */
class QueueManagerTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun qm(vararg items: String, current: Int = 0, upNext: Int = 0) =
        QueueManager(items.toList(), currentIndex = current, upNextCount = upNext)

    // ── playNext ──────────────────────────────────────────────────────────────

    @Test
    fun `playNext inserts song immediately after current item`() {
        val result = qm("a", "b", "c").playNext("X")
        assertEquals(listOf("a", "X", "b", "c"), result.items)
        assertEquals(listOf("X"), result.upNextItems)
    }

    @Test
    fun `playNext with two songs preserves insertion order (FIFO)`() {
        val result = qm("a", "b", "c").playNext("X").playNext("Y")
        // X was added first → plays first; Y is queued behind X
        assertEquals(listOf("a", "X", "Y", "b", "c"), result.items)
        assertEquals(listOf("X", "Y"), result.upNextItems)
    }

    @Test
    fun `multiple playNext songs all play before natural queue resumes`() {
        val base = qm("current", "n1", "n2")
            .playNext("u1").playNext("u2").playNext("u3")
        assertEquals(listOf("u1", "u2", "u3"), base.upNextItems)
        // Skip past all three upNext items, then natural queue should resume
        val afterAll = base.skipToNext().skipToNext().skipToNext().skipToNext()
        assertEquals("n1", afterAll.currentItem)
        assertEquals(0, afterAll.upNextCount)
    }

    @Test
    fun `playNext on empty queue starts playing the song`() {
        val result = QueueManager<String>().playNext("song")
        assertEquals("song", result.currentItem)
        assertEquals(1, result.items.size)
        assertEquals(0, result.upNextCount)
    }

    // ── skipToNext + Up Next interaction ──────────────────────────────────────

    @Test
    fun `skipToNext plays playNext song before natural queue`() {
        val qm = qm("current", "natural1", "natural2").playNext("upnext")
        val result = qm.skipToNext()
        assertEquals("upnext", result.currentItem)
    }

    @Test
    fun `skipToNext after playNext song clears it from upNextItems`() {
        val qm = qm("current", "natural1").playNext("upnext")
        val result = qm.skipToNext()
        assertTrue(result.upNextItems.isEmpty())
        assertEquals(listOf("natural1"), result.playingFromItems)
    }

    @Test
    fun `skipToNext advances currentIndex by 1`() {
        val result = qm("a", "b", "c").skipToNext()
        assertEquals(1, result.currentIndex)
        assertEquals("b", result.currentItem)
    }

    @Test
    fun `skipToNext at end of queue does not crash`() {
        val base = qm("a")
        val result = base.skipToNext()
        assertEquals(base, result) // unchanged
        assertEquals("a", result.currentItem)
    }

    @Test
    fun `skipToNext with only upNext items and no natural queue works`() {
        val base = qm("current", "u1", "u2", upNext = 2)
        val r1 = base.skipToNext()
        assertEquals("u1", r1.currentItem)
        assertEquals(1, r1.upNextCount)
        val r2 = r1.skipToNext()
        assertEquals("u2", r2.currentItem)
        assertEquals(0, r2.upNextCount)
        val r3 = r2.skipToNext()
        assertEquals(r2, r3) // can't skip past end
    }

    // ── playSong ──────────────────────────────────────────────────────────────

    @Test
    fun `playSong directly preserves existing upNextItems after new current`() {
        val base = qm("old", "u1", "u2", "pf1", upNext = 2)
        val result = base.playSong("new", listOf("pf1", "pf2"))
        assertEquals("new", result.currentItem)
        assertEquals(listOf("u1", "u2"), result.upNextItems)
    }

    @Test
    fun `playSong directly does not duplicate upNextItems`() {
        val base = qm("old", "u1", "pf1", upNext = 1)
        // "u1" is already in upNextItems — should not appear again in playingFrom
        val result = base.playSong("new", listOf("pf1", "u1", "pf2"))
        assertEquals(listOf("u1"), result.upNextItems)
        assertEquals(listOf("pf1", "pf2"), result.playingFromItems)
    }

    @Test
    fun `playSong from Playing from section re-inserts upNextItems after new index`() {
        val base = qm("current", "u1", "pf1", "pf2", upNext = 1)
        val result = base.playSong("pf2", listOf("pf1", "pf2"))
        assertEquals("pf2", result.currentItem)
        assertEquals(listOf("u1"), result.upNextItems)
        // "pf1" is not "pf2" and not in upNext → appears in playingFrom
        assertEquals(listOf("pf1"), result.playingFromItems)
    }

    @Test
    fun `playSong clears previous natural queue but preserves upNext`() {
        val base = qm("old", "u1", "pf1", "pf2", upNext = 1)
        val result = base.playSong("new", listOf("newpf1", "newpf2"))
        assertEquals("new", result.currentItem)
        assertEquals(listOf("u1"), result.upNextItems)
        assertEquals(listOf("newpf1", "newpf2"), result.playingFromItems)
        assertFalse("pf1 should be gone", "pf1" in result.items)
    }

    // ── skipToIndex ───────────────────────────────────────────────────────────

    @Test
    fun `skipToIndex jumps to correct position`() {
        val result = qm("a", "b", "c", "d").skipToIndex(2)
        assertEquals("c", result.currentItem)
        assertEquals(2, result.currentIndex)
    }

    @Test
    fun `skipToIndex into Playing from section preserves upNextItems after new current`() {
        // items: [current(0), u1(1), u2(2), pf1(3), pf2(4)]
        val base = qm("current", "u1", "u2", "pf1", "pf2", upNext = 2)
        val result = base.skipToIndex(3) // jump to pf1
        assertEquals("pf1", result.currentItem)
        assertEquals(listOf("u1", "u2"), result.upNextItems)
        assertEquals(listOf("pf2"), result.playingFromItems)
    }

    @Test
    fun `skipToIndex within upNext zone advances without moving upNext items`() {
        // items: [current(0), u1(1), u2(2), pf1(3)]
        val base = qm("current", "u1", "u2", "pf1", upNext = 2)
        val result = base.skipToIndex(1) // jump to u1 (first upNext)
        assertEquals("u1", result.currentItem)
        assertEquals(listOf("u2"), result.upNextItems)
        assertEquals(listOf("pf1"), result.playingFromItems)
    }

    // ── played-section invariant ──────────────────────────────────────────────

    @Test
    fun `items before currentIndex are not in upNextItems or playingFromItems`() {
        val qm = qm("p1", "p2", "current", "u1", "pf1", current = 2, upNext = 1)
        assertFalse("p1" in qm.upNextItems)
        assertFalse("p1" in qm.playingFromItems)
        assertFalse("p2" in qm.upNextItems)
        assertFalse("p2" in qm.playingFromItems)
    }

    @Test
    fun `playNext song that has been played is removed from upNextItems`() {
        val base = qm("current", "u1", "pf1", upNext = 1)
        val after = base.skipToNext() // u1 is now current
        assertTrue(after.upNextItems.isEmpty())
        assertEquals("u1", after.currentItem)
    }

    // ── clearUpNext ───────────────────────────────────────────────────────────

    @Test
    fun `clear upNext removes all upNextItems and closes the gap in queue`() {
        val base = qm("current", "u1", "u2", "pf1", upNext = 2)
        val result = base.clearUpNext()
        assertEquals(0, result.upNextCount)
        assertTrue(result.upNextItems.isEmpty())
        assertEquals(listOf("pf1"), result.playingFromItems)
        assertEquals(listOf("current", "pf1"), result.items)
    }

    // ── absoluteIndexOf — index mapping correctness ───────────────────────────

    @Test
    fun `absoluteIndexOf(playingFromIndex) equals currentIndex + 1 + upNextCount + playingFromIndex`() {
        val qm = qm("p1", "current", "u1", "u2", "pf1", "pf2", current = 1, upNext = 2)
        assertEquals(
            qm.currentIndex + 1 + qm.upNextCount + 0,
            qm.absoluteIndexOf(QueueSection.PLAYING_FROM, 0),
        )
        assertEquals(
            qm.currentIndex + 1 + qm.upNextCount + 1,
            qm.absoluteIndexOf(QueueSection.PLAYING_FROM, 1),
        )
        assertEquals(
            qm.currentIndex + 1 + 0,
            qm.absoluteIndexOf(QueueSection.UP_NEXT, 0),
        )
    }

    @Test
    fun `skipToIndex on Playing from item uses absolute queue index not display index`() {
        // items: [current(0), u1(1), pf1(2)]  upNextCount=1
        // display index of pf1 in playingFromItems is 0, absolute index is 2
        val base = qm("current", "u1", "pf1", upNext = 1)
        val absIdx = base.absoluteIndexOf(QueueSection.PLAYING_FROM, 0)
        assertEquals(2, absIdx) // NOT 1 (the display index)
        // Using the raw display index (1) would skip to u1 instead
        val wrongResult = base.skipToIndex(1)
        assertEquals("u1", wrongResult.currentItem)
        // Using absoluteIndexOf gives the correct song
        val correctResult = base.skipToIndex(absIdx)
        assertEquals("pf1", correctResult.currentItem)
    }

    @Test
    fun `skipToIndex with 1 upNext item tapping Playing from first item plays correct song`() {
        val base = qm("current", "u1", "pf1", "pf2", upNext = 1)
        val result = base.skipToIndex(base.absoluteIndexOf(QueueSection.PLAYING_FROM, 0))
        assertEquals("pf1", result.currentItem)
    }

    @Test
    fun `skipToIndex with 2 upNext items tapping Playing from first item plays correct song`() {
        val base = qm("current", "u1", "u2", "pf1", "pf2", upNext = 2)
        val result = base.skipToIndex(base.absoluteIndexOf(QueueSection.PLAYING_FROM, 0))
        assertEquals("pf1", result.currentItem)
    }

    @Test
    fun `skipToIndex with 0 upNext items tapping Playing from first item plays correct song`() {
        val base = qm("current", "pf1", "pf2", upNext = 0)
        val result = base.skipToIndex(base.absoluteIndexOf(QueueSection.PLAYING_FROM, 0))
        assertEquals("pf1", result.currentItem)
    }

    @Test
    fun `song at tapped position is the song that becomes current after skip`() {
        val base = qm("current", "u1", "pf1", "pf2", upNext = 1)
        val tappedDisplayIdx = 1 // pf2 in playingFromItems
        val expectedSong = base.playingFromItems[tappedDisplayIdx]
        val result = base.skipToIndex(base.absoluteIndexOf(QueueSection.PLAYING_FROM, tappedDisplayIdx))
        assertEquals(expectedSong, result.currentItem)
    }

    @Test
    fun `song title at tapped Playing from index matches song title after skipToIndex`() {
        val base = qm("current", "u1", "u2", "The Song", upNext = 2)
        val displayIdx = 0 // "The Song" is playingFromItems[0]
        assertEquals("The Song", base.playingFromItems[displayIdx])
        val result = base.skipToIndex(base.absoluteIndexOf(QueueSection.PLAYING_FROM, displayIdx))
        assertEquals("The Song", result.currentItem)
    }

    @Test
    fun `upNextCount decrements by 1 after skipToNext consumes an Up Next item`() {
        val base = qm("current", "u1", "u2", "pf1", upNext = 2)
        val result = base.skipToNext()
        assertEquals(1, result.upNextCount)
    }

    @Test
    fun `upNextCount unchanged after skipToIndex within Playing from section`() {
        val base = qm("current", "u1", "u2", "pf1", "pf2", upNext = 2)
        val result = base.skipToIndex(base.absoluteIndexOf(QueueSection.PLAYING_FROM, 0))
        assertEquals(2, result.upNextCount)
    }

    @Test
    fun `playing from display indices remain stable after Up Next item is consumed`() {
        val base = qm("current", "u1", "pf1", "pf2", upNext = 1)
        // Before: playingFromItems = ["pf1", "pf2"]
        assertEquals("pf1", base.playingFromItems[0])
        assertEquals("pf2", base.playingFromItems[1])
        // After consuming u1 via skipToNext:
        val after = base.skipToNext() // u1 becomes current, upNextCount → 0
        // Playing from display indices should still point to the same songs
        assertEquals("pf1", after.playingFromItems[0])
        assertEquals("pf2", after.playingFromItems[1])
    }

    // ── absoluteIndexOf formula — explicit value assertions ──────────────────

    @Test
    fun `absoluteIndex formula currentIdx=0 pqSize=2 displayIndex=3 gives 6`() {
        // 0 + 1 + 2 + 3 = 6
        val qm = QueueManager(
            items = listOf("c", "u1", "u2", "pf1", "pf2", "pf3", "pf4"),
            currentIndex = 0, upNextCount = 2,
        )
        assertEquals(6, qm.absoluteIndexOf(QueueSection.PLAYING_FROM, 3))
    }

    @Test
    fun `absoluteIndex formula currentIdx=4 pqSize=1 displayIndex=0 gives 6`() {
        // 4 + 1 + 1 + 0 = 6
        val qm = QueueManager(
            items = listOf("p1", "p2", "p3", "p4", "current", "u1", "pf1", "pf2"),
            currentIndex = 4, upNextCount = 1,
        )
        assertEquals(6, qm.absoluteIndexOf(QueueSection.PLAYING_FROM, 0))
    }

    @Test
    fun `absoluteIndex formula currentIdx=0 pqSize=0 displayIndex=0 gives 1`() {
        // 0 + 1 + 0 + 0 = 1
        val qm = QueueManager(
            items = listOf("current", "pf1", "pf2"),
            currentIndex = 0, upNextCount = 0,
        )
        assertEquals(1, qm.absoluteIndexOf(QueueSection.PLAYING_FROM, 0))
    }

    @Test
    fun `item at absoluteIndexOf PLAYING_FROM matches playingFromItems at that display index`() {
        // items: [current(0), u1(1), u2(2), pf1(3), pf2(4)]
        val qm = qm("current", "u1", "u2", "pf1", "pf2", upNext = 2)
        for (displayIdx in qm.playingFromItems.indices) {
            val absIdx = qm.absoluteIndexOf(QueueSection.PLAYING_FROM, displayIdx)
            assertEquals(
                "items[absoluteIdx] should equal playingFromItems[displayIdx] at $displayIdx",
                qm.playingFromItems[displayIdx],
                qm.items[absIdx],
            )
        }
    }

    @Test
    fun `skipToIndex with absoluteIndexOf gives correct current item without manual pq removal`() {
        // items: [current(0), u1(1), u2(2), pf1(3), pf2(4)]  upNextCount=2
        // skipToIndex correctly handles upNext stripping internally
        val base = qm("current", "u1", "u2", "pf1", "pf2", upNext = 2)
        val absIdx = base.absoluteIndexOf(QueueSection.PLAYING_FROM, 0) // = 3
        val result = base.skipToIndex(absIdx)
        assertEquals("pf1", result.currentItem)
        // Up Next items preserved — no manual removal needed before calling skipToIndex
        assertEquals(listOf("u1", "u2"), result.upNextItems)
        assertEquals(listOf("pf2"), result.playingFromItems)
    }

    // ── applyMixResult ────────────────────────────────────────────────────────

    @Test
    fun `applyMixResult does not change currentIndex`() {
        val base = qm("seed", upNext = 0)
        val result = base.applyMixResult(listOf("seed", "mix1", "mix2", "mix3"))
        assertEquals(0, result.currentIndex)
    }

    @Test
    fun `applyMixResult preserves currently playing item at currentIndex`() {
        val base = qm("seed", "old1", "old2", upNext = 0)
        val result = base.applyMixResult(listOf("seed", "mix1", "mix2"))
        assertEquals("seed", result.currentItem)
    }

    @Test
    fun `applyMixResult preserves upNextItems between current and mix tracks`() {
        // items: [seed(0), u1(1), u2(2), old1(3)]  upNextCount=2
        val base = qm("seed", "u1", "u2", "old1", upNext = 2)
        val result = base.applyMixResult(listOf("seed", "mix1", "mix2"))
        assertEquals(listOf("u1", "u2"), result.upNextItems)
        assertEquals(2, result.upNextCount)
    }

    @Test
    fun `applyMixResult replaces natural queue after upNext with mix tracks`() {
        // items: [seed(0), u1(1), old1(2), old2(3)]  upNextCount=1
        val base = qm("seed", "u1", "old1", "old2", upNext = 1)
        val result = base.applyMixResult(listOf("seed", "mix1", "mix2", "mix3"))
        assertEquals(listOf("mix1", "mix2", "mix3"), result.playingFromItems)
        // old1 and old2 are gone
        assertFalse("old1" in result.items)
        assertFalse("old2" in result.items)
    }

    @Test
    fun `applyMixResult with empty upNext appends mix tracks directly after current`() {
        val base = qm("seed", "old1", upNext = 0)
        val result = base.applyMixResult(listOf("seed", "mix1", "mix2"))
        assertEquals(listOf("mix1", "mix2"), result.playingFromItems)
        assertTrue(result.upNextItems.isEmpty())
    }

    // ── syncCurrentIndex ─────────────────────────────────────────────────────

    @Test
    fun `syncCurrentIndex after natural advancement decrements upNextCount`() {
        // items: [current(0), u1(1), u2(2), pf1(3)]  currentIndex=0, upNextCount=2
        // Media3 advances to index 1 (u1 plays) — that consumed one Up Next slot
        val base = qm("current", "u1", "u2", "pf1", upNext = 2)
        val result = base.syncCurrentIndex(1)
        assertEquals(1, result.currentIndex)
        assertEquals(1, result.upNextCount) // 2 → 1
    }

    @Test
    fun `syncCurrentIndex after natural advancement into Playing from does not change upNextCount`() {
        // currentIndex=2 (last upNext consumed), upNextCount=0; advance to pf1 at index 3
        val base = qm("current", "u1", "pf1", "pf2", current = 1, upNext = 0)
        val result = base.syncCurrentIndex(2)
        assertEquals(2, result.currentIndex)
        assertEquals(0, result.upNextCount) // was already 0, stays 0
    }

    @Test
    fun `syncCurrentIndex keeps upNextCount at 0 minimum`() {
        // upNextCount is already 0; advance should not underflow to -1
        val base = qm("current", "pf1", "pf2", upNext = 0)
        val result = base.syncCurrentIndex(1)
        assertEquals(0, result.upNextCount)
    }

    @Test
    fun `absoluteIndexOf is correct after syncCurrentIndex called`() {
        // Initial: currentIndex=0, upNextCount=2
        // items: [current(0), u1(1), u2(2), pf1(3), pf2(4)]
        val base = qm("current", "u1", "u2", "pf1", "pf2", upNext = 2)
        // After natural advance to index 1 (u1 now current, upNextCount → 1)
        val synced = base.syncCurrentIndex(1)
        // pf1 is still at absolute index 3; synced currentIndex=1, upNextCount=1
        // absoluteIndexOf(PLAYING_FROM, 0) = 1 + 1 + 1 + 0 = 3
        assertEquals(3, synced.absoluteIndexOf(QueueSection.PLAYING_FROM, 0))
        assertEquals("pf1", synced.items[synced.absoluteIndexOf(QueueSection.PLAYING_FROM, 0)])
    }

    // ── structural invariants ─────────────────────────────────────────────────

    @Test
    fun `items list equals played plus current plus upNext plus playingFrom`() {
        val qm = qm("p1", "current", "u1", "u2", "pf1", "pf2", current = 1, upNext = 2)
        val reconstructed =
            qm.playedItems + listOfNotNull(qm.currentItem) + qm.upNextItems + qm.playingFromItems
        assertEquals(qm.items, reconstructed)
    }

    @Test
    fun `total items count equals played plus 1 plus upNextCount plus playingFromCount`() {
        val qm = qm("p1", "p2", "current", "u1", "pf1", "pf2", "pf3", current = 2, upNext = 1)
        val expected = qm.playedItems.size + 1 + qm.upNextCount + qm.playingFromItems.size
        assertEquals(expected, qm.items.size)
    }

    @Test
    fun `no item appears in both upNextItems and playingFromItems`() {
        val qm = qm("current", "u1", "u2", "pf1", "pf2", upNext = 2)
        val intersection = qm.upNextItems.intersect(qm.playingFromItems.toSet())
        assertTrue(intersection.isEmpty())
    }
}
