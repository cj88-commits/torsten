package com.torsten.app.player

/** Identifies which display section a row index belongs to. */
enum class QueueSection { UP_NEXT, PLAYING_FROM }

/**
 * Pure immutable queue model.
 *
 * Layout invariant:
 *   items = [played(0..currentIndex-1), current(currentIndex),
 *            upNext(currentIndex+1..currentIndex+upNextCount),
 *            playingFrom(currentIndex+upNextCount+1..end)]
 *
 * Every mutating method returns a *new* instance — no side effects.
 * Generic [T] so tests can use String; production wires MediaItem.
 */
data class QueueManager<T>(
    val items: List<T> = emptyList(),
    val currentIndex: Int = 0,
    val upNextCount: Int = 0,
) {
    // ── Derived views ────────────────────────────────────────────────────────

    val currentItem: T?
        get() = items.getOrNull(currentIndex)

    val upNextItems: List<T>
        get() {
            val start = (currentIndex + 1).coerceAtMost(items.size)
            val end = (currentIndex + 1 + upNextCount).coerceAtMost(items.size)
            return items.subList(start, end)
        }

    val playingFromItems: List<T>
        get() {
            val start = (currentIndex + 1 + upNextCount).coerceAtMost(items.size)
            return items.subList(start, items.size)
        }

    val playedItems: List<T>
        get() = items.subList(0, currentIndex.coerceAtMost(items.size))

    // ── Mutations ────────────────────────────────────────────────────────────

    /**
     * Start playing [song]. [naturalQueue] becomes the Playing From section.
     * Existing Up Next items are preserved immediately after the new current.
     * Entries in [naturalQueue] that duplicate Up Next items (or [song] itself)
     * are silently dropped.
     */
    fun playSong(song: T, naturalQueue: List<T>): QueueManager<T> {
        val existingUpNext = upNextItems
        val filteredQueue = naturalQueue.filter { it !in existingUpNext && it != song }
        val newItems = listOf(song) + existingUpNext + filteredQueue
        return QueueManager(newItems, currentIndex = 0, upNextCount = existingUpNext.size)
    }

    /**
     * Queue [item] to play after any previously queued "play next" items (FIFO).
     * If the queue is currently empty [item] becomes the current track immediately.
     */
    fun playNext(item: T): QueueManager<T> {
        if (items.isEmpty()) {
            return QueueManager(listOf(item), currentIndex = 0, upNextCount = 0)
        }
        val insertAt = currentIndex + 1 + upNextCount
        val newItems = items.toMutableList().also { it.add(insertAt, item) }
        return copy(items = newItems, upNextCount = upNextCount + 1)
    }

    /** Alias for [playNext] — appends to end of Up Next zone. */
    fun addToQueue(item: T): QueueManager<T> = playNext(item)

    /**
     * Advance to the next item.
     * If the next item was an Up Next item, [upNextCount] is decremented.
     * Does nothing when already at the last item.
     */
    fun skipToNext(): QueueManager<T> {
        if (currentIndex + 1 >= items.size) return this
        val newUpNextCount = if (upNextCount > 0) upNextCount - 1 else 0
        return copy(currentIndex = currentIndex + 1, upNextCount = newUpNextCount)
    }

    /**
     * Go back to the previous item.
     * [upNextCount] is unchanged so Up Next items still follow the new current.
     * Does nothing when already at the first item.
     */
    fun skipToPrevious(): QueueManager<T> {
        if (currentIndex == 0) return this
        return copy(currentIndex = currentIndex - 1)
    }

    /**
     * Jump to an arbitrary position in [items].
     *
     * When [index] points inside the Playing From section:
     *   Up Next items are extracted, stripped from the list, the seek is performed
     *   on the shorter list, then Up Next items are re-inserted immediately after
     *   the new current — so they still play before the natural queue resumes.
     *
     * When [index] points inside the Up Next zone:
     *   Items before [index] in the Up Next zone are implicitly skipped over and
     *   become part of the played section; [upNextCount] is reduced accordingly.
     *
     * No-ops for out-of-range or current-position targets.
     */
    fun skipToIndex(index: Int): QueueManager<T> {
        if (index < 0 || index >= items.size || index == currentIndex) return this

        // Target is inside the Up Next zone — just move currentIndex there.
        if (index > currentIndex && index <= currentIndex + upNextCount) {
            val consumed = index - currentIndex - 1          // upNext items skipped over
            val newUpNextCount = upNextCount - consumed - 1  // the target itself is now current
            return copy(currentIndex = index, upNextCount = newUpNextCount)
        }

        val existingUpNext = upNextItems

        // Remove the upNext block from items.
        val stripped = items.toMutableList()
        for (i in upNextCount - 1 downTo 0) {
            stripped.removeAt(currentIndex + 1 + i)
        }

        // Target position inside stripped list.
        val strippedTarget = if (index <= currentIndex) index else index - upNextCount

        // Re-insert upNext immediately after the new current.
        val newItems = stripped.toMutableList()
        for ((offset, upNextItem) in existingUpNext.withIndex()) {
            newItems.add(strippedTarget + 1 + offset, upNextItem)
        }

        return QueueManager(newItems, currentIndex = strippedTarget, upNextCount = existingUpNext.size)
    }

    /** Called when a track finishes playing naturally — identical to [skipToNext]. */
    fun markCurrentPlayed(): QueueManager<T> = skipToNext()

    /**
     * Called when an instant mix resolves while the seed is already playing.
     *
     * Keeps [currentIndex] and [upNextCount] unchanged so the seed continues
     * uninterrupted.  Any Up Next items added before the mix resolved are
     * preserved between the current item and the incoming mix tracks.
     * Everything after the Up Next zone is replaced by [mixTracks].drop(1)
     * ([mixTracks][0] is the seed, which is already [currentItem]).
     *
     * Mirrors the Media3 operation: `removeMediaItems(insertAt, end)` then
     * `addMediaItems(insertAt, mixTracks.drop(1))` — no `setMediaItems` call,
     * no rebuffering.
     */
    fun applyMixResult(mixTracks: List<T>): QueueManager<T> {
        if (mixTracks.isEmpty()) return this
        // Preserve: played items (0..currentIndex-1) + current + upNext
        // Replace: everything after the upNext zone with mix tracks minus the seed
        val preserved = items.take(currentIndex + 1) + upNextItems
        val newItems = preserved + mixTracks.drop(1)
        return copy(items = newItems)
    }

    /**
     * Reconciles [currentIndex] with the authoritative Media3 index after any
     * track transition (natural end, Next/Previous button, external seek).
     *
     * Rules:
     * - If [newIndex] == [currentIndex] + 1 **and** [upNextCount] > 0 the advance
     *   consumed an Up Next slot → decrement [upNextCount] by 1.
     * - Any other jump (backward, large skip, jump into playingFrom) leaves
     *   [upNextCount] unchanged because no Up Next item was consumed.
     * - [upNextCount] is always clamped to ≥ 0.
     *
     * This must be called from `onMediaItemTransition` in PlaybackViewModel so
     * [currentIndex] never drifts from what Media3 considers current.
     */
    fun syncCurrentIndex(newIndex: Int): QueueManager<T> {
        if (newIndex == currentIndex) return this
        val naturalAdvance = newIndex == currentIndex + 1
        val newUpNextCount = when {
            naturalAdvance && upNextCount > 0 -> upNextCount - 1
            else -> upNextCount
        }.coerceAtLeast(0)
        return copy(currentIndex = newIndex, upNextCount = newUpNextCount)
    }

    /** Remove all Up Next items, closing the gap so Playing From follows current directly. */
    fun clearUpNext(): QueueManager<T> {
        if (upNextCount == 0) return this
        val newItems = items.toMutableList()
        for (i in upNextCount - 1 downTo 0) {
            newItems.removeAt(currentIndex + 1 + i)
        }
        return copy(items = newItems, upNextCount = 0)
    }

    /** Remove a single Up Next item by its index *within* [upNextItems]. */
    fun removeUpNextItem(upNextIndex: Int): QueueManager<T> {
        if (upNextIndex < 0 || upNextIndex >= upNextCount) return this
        val absoluteIndex = currentIndex + 1 + upNextIndex
        val newItems = items.toMutableList().also { it.removeAt(absoluteIndex) }
        return copy(items = newItems, upNextCount = upNextCount - 1)
    }

    /**
     * Converts a *display* index within a [QueueSection] to the absolute index in [items].
     *
     * The Queue screen enumerates [upNextItems] and [playingFromItems] starting at 0.
     * Those display indices do **not** account for the played section or the current item,
     * so passing them directly to [skipToIndex] causes an off-by-(1 + upNextCount) error
     * when Up Next items exist. Always go through this method.
     *
     * Example — items = [current(0), u1(1), u2(2), pf1(3), pf2(4)], currentIndex=0, upNextCount=2
     *   absoluteIndexOf(PLAYING_FROM, 0) = 0 + 1 + 2 + 0 = 3  →  pf1  ✓
     *   absoluteIndexOf(UP_NEXT,      0) = 0 + 1     + 0 = 1  →  u1   ✓
     */
    fun absoluteIndexOf(section: QueueSection, displayIndex: Int): Int = when (section) {
        QueueSection.UP_NEXT -> currentIndex + 1 + displayIndex
        QueueSection.PLAYING_FROM -> currentIndex + 1 + upNextCount + displayIndex
    }

    /** Reorder an Up Next item; both indices are relative to [upNextItems]. */
    fun moveUpNextItem(fromIndex: Int, toIndex: Int): QueueManager<T> {
        if (fromIndex < 0 || fromIndex >= upNextCount) return this
        if (toIndex < 0 || toIndex >= upNextCount) return this
        if (fromIndex == toIndex) return this
        val absFrom = currentIndex + 1 + fromIndex
        val absTo = currentIndex + 1 + toIndex
        val newItems = items.toMutableList()
        val item = newItems.removeAt(absFrom)
        newItems.add(absTo, item)
        return copy(items = newItems)
    }
}
