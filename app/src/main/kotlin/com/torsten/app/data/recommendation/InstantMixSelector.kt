package com.torsten.app.data.recommendation

import com.torsten.app.data.db.entity.SongEntity

/**
 * Lightweight projection of an LB Radio entry for pure matching.
 * [recordingName] may be empty when only the artist name is known
 * (the LB Radio API returns MBIDs, not titles). [artistName] is the
 * similar-artist display name / identifier used to match against
 * [SongEntity.artistId] in the local catalogue.
 */
data class LbRadioCandidate(val recordingName: String, val artistName: String)

object InstantMixSelector {

    /**
     * Match LB Radio candidates against a local song pool and return matched
     * songs in LB candidate order.
     *
     * Matching rules (per candidate):
     *  - Artist: [SongEntity.artistId] == [LbRadioCandidate.artistName] (case-insensitive)
     *  - Title (when [LbRadioCandidate.recordingName] is non-empty):
     *    normalised([SongEntity.title]) == normalised([recordingName]) — exact normalised match
     *  - When [recordingName] is empty, any song by the matching artist qualifies.
     *
     * Each local song can only be returned once (deduplicated by song ID).
     */
    fun matchCandidates(
        lbCandidates: List<LbRadioCandidate>,
        localSongs: List<SongEntity>,
    ): List<SongEntity> {
        val usedSongIds = mutableSetOf<String>()
        val results = mutableListOf<SongEntity>()

        for (candidate in lbCandidates) {
            val normArtist = candidate.artistName.lowercase().trim()
            val normTitle = candidate.recordingName
                .takeIf { it.isNotEmpty() }
                ?.let { ArtistTopTracksSelector.normaliseTitle(it) }

            val match = localSongs.firstOrNull { song ->
                if (song.id in usedSongIds) return@firstOrNull false
                val artistMatch = song.artistId.lowercase().trim() == normArtist
                if (!artistMatch) return@firstOrNull false
                if (normTitle != null) {
                    ArtistTopTracksSelector.normaliseTitle(song.title) == normTitle
                } else {
                    true
                }
            } ?: continue

            usedSongIds.add(match.id)
            results.add(match)
        }

        return results
    }

    /**
     * Apply a per-artist diversity cap. First tries [initialCap] songs per artist.
     * If the result is fewer than [target], relaxes to [relaxedCap] per artist.
     * Output is trimmed to at most [target] entries.
     *
     * Cap key is [SongEntity.artistId] (falls back to [SongEntity.id] if blank).
     */
    fun applyDiversityCap(
        songs: List<SongEntity>,
        target: Int = 20,
        initialCap: Int = 5,
        relaxedCap: Int = 8,
    ): List<SongEntity> {
        val strict = capByArtist(songs, initialCap)
        val result = if (strict.size < target) capByArtist(songs, relaxedCap) else strict
        return result.take(target)
    }

    /**
     * Merge LB-matched songs and Subsonic fallback songs, deduplicating by song ID.
     * LB-matched songs always appear first; Subsonic songs are appended in their
     * original order, skipping any IDs already present in [lbMatched].
     */
    fun mergeAndDeduplicate(
        lbMatched: List<SongEntity>,
        subsonicCandidates: List<SongEntity>,
    ): List<SongEntity> {
        val seenIds = lbMatched.mapTo(mutableSetOf()) { it.id }
        val subsonic = subsonicCandidates.filter { seenIds.add(it.id) }
        return lbMatched + subsonic
    }

    /**
     * Fill [capped] up to [target] by appending songs from [uncappedPool] that
     * are not already in [capped]. If [capped] already meets or exceeds [target],
     * it is trimmed and returned as-is. Returns fewer than [target] songs only
     * when the combined pool is smaller than [target].
     */
    fun fillToTarget(
        capped: List<SongEntity>,
        uncappedPool: List<SongEntity>,
        target: Int = 20,
    ): List<SongEntity> {
        if (capped.size >= target) return capped.take(target)
        val result = capped.toMutableList()
        val includedIds = result.mapTo(mutableSetOf()) { it.id }
        for (song in uncappedPool) {
            if (result.size >= target) break
            if (includedIds.add(song.id)) result.add(song)
        }
        return result
    }

    /**
     * Place [seed] at index 0 and trim the total to exactly [target] tracks.
     * If [seed] is already present in [pool] it is removed to avoid duplication.
     * Returns only [[seed]] when [pool] is empty.
     */
    fun assembleFinalMix(
        seed: SongEntity,
        pool: List<SongEntity>,
        target: Int = 20,
    ): List<SongEntity> {
        val companions = pool.filter { it.id != seed.id }.take(target - 1)
        return listOf(seed) + companions
    }

    /**
     * Build the final mix with controlled artist sequencing:
     *  - Position 0: [seed]
     *  - Position 1: the next song in [pool] by the same artist as [seed] (if one exists)
     *  - Position 2+: iterate [pool] in order; if a song's artist matches the immediately
     *    preceding result entry it is deferred. After each successful placement the deferred
     *    queue is drained — any deferred song whose artist no longer matches the new last
     *    entry is inserted immediately. Songs still deferred once [pool] is exhausted are
     *    appended in order (accepting consecutive same-artist rather than leaving gaps).
     *
     * [seed] is removed from [pool] if present (defensive deduplication).
     * Returns at most [target] songs.
     */
    fun interleaveMix(
        seed: SongEntity,
        pool: List<SongEntity>,
        target: Int = 20,
    ): List<SongEntity> {
        val remaining = pool.filter { it.id != seed.id }.toMutableList()
        val result = mutableListOf(seed)

        // Position 1: a companion from the seed artist
        val sameArtistIdx = remaining.indexOfFirst { it.artistId == seed.artistId }
        if (sameArtistIdx >= 0 && result.size < target) {
            result.add(remaining.removeAt(sameArtistIdx))
        }

        if (result.size >= target) return result

        // Positions 2+: no consecutive same-artist; defer collisions and retry after each insert
        val deferred = ArrayDeque<SongEntity>()

        fun drainDeferred() {
            var placed = true
            while (placed && result.size < target) {
                placed = false
                val lastArtist = result.last().artistId
                val idx = deferred.indexOfFirst { it.artistId != lastArtist }
                if (idx >= 0) {
                    result.add(deferred.removeAt(idx))
                    placed = true
                }
            }
        }

        for (song in remaining) {
            if (result.size >= target) break
            if (song.artistId != result.last().artistId) {
                result.add(song)
                drainDeferred()
            } else {
                deferred.add(song)
            }
        }

        // Flush remaining deferred (consecutive same-artist acceptable at tail)
        for (song in deferred) {
            if (result.size >= target) break
            result.add(song)
        }

        return result
    }

    /**
     * Hard cap on how many times the seed artist may appear in the final mix.
     * Songs from [seedArtistId] beyond position [maxSeedArtist] are dropped; all
     * other songs are kept in their original order.
     *
     * The seed is always at index 0 and counts toward the cap, so the caller
     * should always pass [maxSeedArtist] >= 1.
     *
     * This is a last-resort safety net applied after [interleaveMix]. It ensures
     * that even when the Subsonic fallback pool is dominated by the seed artist
     * (e.g. a niche artist whose "similar songs" list is mostly self-referential),
     * the final result does not read as a single-artist playlist.
     *
     * If removal makes the result shorter than [target], the caller may choose to
     * accept the shorter mix rather than backfill with more seed-artist songs.
     */
    fun enforceSeedArtistCap(
        mix: List<SongEntity>,
        seedArtistId: String,
        maxSeedArtist: Int = 4,
    ): List<SongEntity> {
        var seedArtistCount = 0
        return mix.filter { song ->
            if (song.artistId == seedArtistId) {
                seedArtistCount++
                seedArtistCount <= maxSeedArtist
            } else {
                true
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun capByArtist(songs: List<SongEntity>, cap: Int): List<SongEntity> {
        val counts = mutableMapOf<String, Int>()
        val result = mutableListOf<SongEntity>()
        for (song in songs) {
            val key = song.artistId.ifEmpty { song.id }
            val count = counts.getOrDefault(key, 0)
            if (count < cap) {
                result.add(song)
                counts[key] = count + 1
            }
        }
        return result
    }
}
