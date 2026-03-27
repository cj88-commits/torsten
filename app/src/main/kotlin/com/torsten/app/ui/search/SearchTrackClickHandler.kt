package com.torsten.app.ui.search

import com.torsten.app.data.db.entity.SongEntity

/**
 * Pure, stateless helper that builds the playback queue when a track is tapped in search results.
 *
 * Returns the **full album** sorted by disc then track number, together with the index of the
 * tapped song.  The caller passes both to the player so that:
 *  - Tracks before [tappedSong] appear as scrolled-past history in the queue UI.
 *  - Playback starts at the tapped position (identical to tapping a track in Album Detail).
 */
object SearchTrackClickHandler {

    /**
     * @param tappedSong  The song the user tapped.
     * @param albumSongs  All songs that belong to the same album (any order, may include duplicates).
     * @return            Full album sorted by disc/track + the index of [tappedSong] within that list.
     *                    If [tappedSong] cannot be located, startIndex falls back to 0 (play from top).
     */
    fun buildQueue(
        tappedSong: SongEntity,
        albumSongs: List<SongEntity>,
    ): Pair<List<SongEntity>, Int> {
        val sorted = albumSongs
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }))
        val startIndex = sorted.indexOfFirst { it.id == tappedSong.id }.coerceAtLeast(0)
        return Pair(sorted, startIndex)
    }
}
