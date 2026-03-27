package com.torsten.app.data.recommendation

import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.data.metabrainz.LbRecordingCandidate
import timber.log.Timber

object ArtistTopTracksSelector {

    private val SKIP_WORDS = setOf(
        "live", "acoustic", "wembley", "remix", "edition", "demo", "remaster",
    )

    /**
     * Normalise a title for fuzzy matching: lowercase, strip parenthetical content,
     * strip featured-artist suffixes, strip non-alphanumeric characters.
     */
    fun normaliseTitle(title: String): String {
        var s = title.lowercase()
        s = s.replace(Regex("\\(.*?\\)"), "")
        s = s.replace(Regex("\\[.*?\\]"), "")
        s = s.replace(Regex("\\bfe?a?t\\.?.*"), "")
        s = s.replace(Regex("[^a-z0-9 ]"), "")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    /** Trigram Jaccard similarity score in [0, 1]. */
    fun trigramScore(a: String, b: String): Float {
        val ta = trigrams(a)
        val tb = trigrams(b)
        if (ta.isEmpty() && tb.isEmpty()) return 1f
        val intersection = ta.intersect(tb).size
        val union = (ta + tb).size
        return if (union == 0) 0f else intersection.toFloat() / union.toFloat()
    }

    /**
     * Match LB candidates against local songs, returning matched songs in LB rank order.
     *
     * Deduplication rules:
     *  - Each local song can only be matched once (by song ID).
     *  - LB candidates that normalise to the same title as a previously matched candidate
     *    are skipped (prevents "Hey Jude" and "Hey Jude (Live)" both consuming a slot).
     *
     * Match tiers (lowest number wins):
     *  1. Exact normalised title match
     *  2. Substring containment (both strings >= 8 chars)
     *  3. Trigram Jaccard >= 0.55
     *
     * When multiple local songs qualify at the same tier, the one with the shorter raw title
     * is preferred (and live/remix variants are penalised).
     */
    fun matchCandidates(
        lbCandidates: List<LbRecordingCandidate>,
        localSongs: List<SongEntity>,
    ): List<SongEntity> {
        val usedSongIds = mutableSetOf<String>()
        val usedNormTitles = mutableSetOf<String>()
        val results = mutableListOf<SongEntity>()

        for (candidate in lbCandidates) {
            val normCandidate = normaliseTitle(candidate.trackName)
            if (!usedNormTitles.add(normCandidate)) continue

            val match = localSongs
                .filter { it.id !in usedSongIds }
                .mapNotNull { song ->
                    val tier = matchTier(normCandidate, normaliseTitle(song.title))
                        ?: return@mapNotNull null
                    Triple(song, tier, preferenceScore(song.title))
                }
                .sortedWith(compareBy({ it.second }, { it.third }))
                .firstOrNull() ?: continue

            usedSongIds.add(match.first.id)
            results.add(match.first)
        }

        return results
    }

    /**
     * From a ranked list, pick up to 5 songs with album diversity.
     *
     * Pass 1 (LB diversity): one song per album from lbRanked.
     * Pass 2 (LB fill): remaining slots from lbRanked ignoring album constraint.
     * Pass 3 (fill diversity): if still < 5, one song per album from allArtistSongs.
     * Pass 4 (fill relax): if still < 5, any song from allArtistSongs not already in result.
     *
     * Result size is always min(5, totalAvailableSongs).
     */
    fun selectTopFive(
        lbRanked: List<SongEntity>,
        allArtistSongs: List<SongEntity> = emptyList(),
        artistName: String = "",
    ): List<SongEntity> {
        Timber.d("[ArtistTop] selectTopFive: artistName=$artistName")
        Timber.d("[ArtistTop] selectTopFive: lbRanked.size=${lbRanked.size}")
        Timber.d("[ArtistTop] selectTopFive: allArtistSongs.size=${allArtistSongs.size}")

        val seenAlbumIds = mutableSetOf<String>()
        val result = mutableListOf<SongEntity>()
        val resultIds = mutableSetOf<String>()

        // Pass 1: LB diversity
        for (song in lbRanked) {
            if (result.size >= 5) break
            if (seenAlbumIds.add(song.albumId)) {
                result.add(song)
                resultIds.add(song.id)
            }
        }
        Timber.d("[ArtistTop] selectTopFive: after LB diversity pass result.size=${result.size}")

        // Pass 2: LB fill (same-album allowed)
        if (result.size < 5) {
            for (song in lbRanked) {
                if (result.size >= 5) break
                if (song.id !in resultIds) {
                    result.add(song)
                    resultIds.add(song.id)
                }
            }
        }

        // Pass 3 & 4: fill from allArtistSongs
        if (result.size < 5 && allArtistSongs.isNotEmpty()) {
            val fillCandidates = allArtistSongs
                .filter { it.id !in resultIds }
                .sortedWith(compareBy({ it.albumId }, { it.discNumber }, { it.trackNumber }))
            Timber.d("[ArtistTop] selectTopFive: fillCandidates.size=${fillCandidates.size}")

            // Pass 3: diversity fill
            for (song in fillCandidates) {
                if (result.size >= 5) break
                if (seenAlbumIds.add(song.albumId)) {
                    result.add(song)
                    resultIds.add(song.id)
                }
            }
            Timber.d("[ArtistTop] selectTopFive: after diversity fill result.size=${result.size}")

            // Pass 4: relaxed fill — allow any song not already in result
            // Recomputes seen IDs from the current result to avoid stale state from Pass 3.
            if (result.size < 5) {
                val seenIds = result.map { it.id }.toMutableSet()
                for (song in allArtistSongs) {
                    if (result.size >= 5) break
                    if (seenIds.add(song.id)) {
                        result.add(song)
                    }
                }
            }
            Timber.d("[ArtistTop] selectTopFive: after relaxed fill result.size=${result.size}")
        } else {
            Timber.d("[ArtistTop] selectTopFive: fillCandidates.size=0 (allArtistSongs empty or result already full)")
        }

        Timber.d("[ArtistTop] selectTopFive: final=${result.map { it.title }}")
        return result
    }

    /** From a ranked list, pick up to 20 songs for the full playback queue. */
    fun selectFullQueue(rankedSongs: List<SongEntity>): List<SongEntity> = rankedSongs.take(20)

    /**
     * Build a playback queue for when the user taps one of the top-5 tracks.
     *
     * Layout:
     *   [0..4]           topFive in **original** (ranked) order — no rotation
     *   [5..target-1]    remaining artist songs, shuffled, excluding top-five
     *
     * @return Pair of (fullQueue, startIndex) where startIndex is the position of
     *         [tappedSong] in fullQueue. Pass both to Media3 so tracks before the
     *         tapped position appear as history and playback begins at the tapped track.
     */
    fun buildTopTrackQueue(
        tappedSong: SongEntity,
        topFive: List<SongEntity>,
        allArtistSongs: List<SongEntity>,
        target: Int = 20,
    ): Pair<List<SongEntity>, Int> {
        // Step 1: top five in original ranked order (no rotation)
        val topFiveIds = topFive.map { it.id }.toSet()

        // Step 2: remaining songs not in top five, shuffled
        val remaining = allArtistSongs
            .filter { it.id !in topFiveIds }
            .shuffled()

        // Step 3: full queue = top five + shuffled remaining, trimmed to target
        val fullQueue = (topFive + remaining).take(target)

        // Step 4: start index = position of tapped song in full queue
        val startIndex = fullQueue.indexOfFirst { it.id == tappedSong.id }.coerceAtLeast(0)

        return Pair(fullQueue, startIndex)
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun matchTier(normA: String, normB: String): Int? = when {
        normA == normB -> 1
        normA.length >= 8 && normB.length >= 8 &&
            (normA.contains(normB) || normB.contains(normA)) -> 2
        trigramScore(normA, normB) >= 0.55f -> 3
        else -> null
    }

    private fun preferenceScore(title: String): Int {
        val lower = title.lowercase()
        return if (SKIP_WORDS.any { lower.contains(it) }) title.length + 10_000 else title.length
    }

    private fun trigrams(s: String): Set<String> {
        if (s.length < 3) return if (s.isNotEmpty()) setOf(s) else emptySet()
        return (0..s.length - 3).map { s.substring(it, it + 3) }.toSet()
    }
}
