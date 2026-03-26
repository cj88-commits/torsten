package com.torsten.app.data.recommendation

import com.torsten.app.data.db.entity.SongEntity
import com.torsten.app.data.metabrainz.LbRecordingCandidate

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
     * From a ranked list, pick up to 5 songs with album diversity (one per album in first pass,
     * then fill remaining slots from the same ranked list ignoring album constraint).
     */
    fun selectTopFive(rankedSongs: List<SongEntity>): List<SongEntity> {
        val seenAlbumIds = mutableSetOf<String>()
        val result = mutableListOf<SongEntity>()

        for (song in rankedSongs) {
            if (result.size >= 5) break
            if (seenAlbumIds.add(song.albumId)) result.add(song)
        }

        if (result.size < 5) {
            val resultIds = result.map { it.id }.toHashSet()
            for (song in rankedSongs) {
                if (result.size >= 5) break
                if (song.id !in resultIds) result.add(song)
            }
        }

        return result
    }

    /** From a ranked list, pick up to 20 songs for the full playback queue. */
    fun selectFullQueue(rankedSongs: List<SongEntity>): List<SongEntity> = rankedSongs.take(20)

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
