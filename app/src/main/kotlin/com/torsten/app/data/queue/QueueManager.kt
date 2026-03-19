package com.torsten.app.data.queue

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class QueueTrack(
    val songId: String,
    val title: String,
    val artistName: String,
    val albumTitle: String,
    val coverArtUrl: String?,
    val durationMs: Long,
)

/**
 * In-memory two-layer queue model.
 *
 * [priorityQueue]         — tracks explicitly enqueued by the user ("play next").
 * [backgroundSequence]    — the current album or playlist context (loaded via playAlbum).
 * [backgroundCurrentIndex]— which item in the background sequence is currently active.
 *
 * [PlaybackViewModel] is responsible for keeping this state in sync with the Media3 playlist.
 * The Media3 playlist layout after enqueueing is:
 *   [bg[0]…bg[current], p[0]…p[m], bg[current+1]…bg[n]]
 */
class QueueManager {

    private val _priorityQueue = MutableStateFlow<List<QueueTrack>>(emptyList())
    val priorityQueue: StateFlow<List<QueueTrack>> = _priorityQueue.asStateFlow()

    private val _backgroundSequence = MutableStateFlow<List<QueueTrack>>(emptyList())
    val backgroundSequence: StateFlow<List<QueueTrack>> = _backgroundSequence.asStateFlow()

    private val _backgroundCurrentIndex = MutableStateFlow(0)
    val backgroundCurrentIndex: StateFlow<Int> = _backgroundCurrentIndex.asStateFlow()

    // ─── Mutations ────────────────────────────────────────────────────────────

    /** Called when a new album/playlist is loaded. Replaces the background and clears priority. */
    fun setBackgroundSequence(tracks: List<QueueTrack>, startIndex: Int = 0) {
        _backgroundSequence.value = tracks
        _backgroundCurrentIndex.value = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        _priorityQueue.value = emptyList()
    }

    /**
     * Updates the background sequence and current index without touching the priority queue.
     * Used when the user taps an individual track row — the "play this track now" gesture —
     * as opposed to the Play button which signals a deliberate fresh start.
     */
    fun setBackgroundSequenceOnly(tracks: List<QueueTrack>, startIndex: Int = 0) {
        _backgroundSequence.value = tracks
        _backgroundCurrentIndex.value = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
    }

    /** Appends [track] to the end of the priority queue. */
    fun enqueueTrack(track: QueueTrack) {
        _priorityQueue.update { it + track }
    }

    /** Removes the priority queue item at [index]. */
    fun removeFromPriorityQueue(index: Int) {
        _priorityQueue.update { queue ->
            if (index !in queue.indices) queue
            else queue.toMutableList().also { it.removeAt(index) }
        }
    }

    /** Moves item at [fromIndex] to [toIndex] in the priority queue. */
    fun movePriorityQueueItem(fromIndex: Int, toIndex: Int) {
        _priorityQueue.update { queue ->
            if (fromIndex !in queue.indices || toIndex !in queue.indices) return@update queue
            val mutable = queue.toMutableList()
            val item = mutable.removeAt(fromIndex)
            mutable.add(toIndex, item)
            mutable
        }
    }

    /** Removes all items from the priority queue. */
    fun clearPriorityQueue() {
        _priorityQueue.value = emptyList()
    }

    /**
     * Called by [PlaybackViewModel] when the current media item transitions.
     *
     * @param songId     New current song ID.
     * @param isPriority True when the new item came from the priority queue (queueType extra).
     */
    fun onAdvancedToItem(songId: String, isPriority: Boolean) {
        if (isPriority) {
            // Pop the now-playing track from the front of the priority queue.
            val idx = _priorityQueue.value.indexOfFirst { it.songId == songId }
            if (idx >= 0) {
                _priorityQueue.update { it.filterIndexed { i, _ -> i != idx } }
            }
        } else {
            // Background track became active — update the background index.
            val bgIndex = _backgroundSequence.value.indexOfFirst { it.songId == songId }
            if (bgIndex >= 0) {
                _backgroundCurrentIndex.value = bgIndex
            }
        }
    }
}
